package io.github.vigoo.zioaws.codegen.generator

import java.io.File
import java.nio.charset.StandardCharsets

import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.model.config.customization.ShapeModifier
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.file.Files

import scala.meta._

trait ServiceModelGenerator {
  this: HasConfig with GeneratorBase =>

  private def removeDuplicates(modelSet: Set[Model]): Set[Model] =
    modelSet
      .foldLeft(Map.empty[String, Model]) { case (result, model) =>
        result.updated(model.name, model)
      }
      .values
      .toSet

  private def filterModels(): ZIO[GeneratorContext, Nothing, Set[Model]] =
    for {
      modelMap <- getModelMap
      models <- getModels
      excluded = Option(models.customizationConfig.getShapeModifiers)
        .map(_.asScala.collect {
          case (name, modifier) if modifier.isExcludeShape => name
        }.toSet)
        .getOrElse(Set.empty)

      result = modelMap.all.filterNot { model =>
        excluded.contains(model.serviceModelName) ||
        model.shape.isException ||
        model.shape.isEventstream ||
        TypeMapping.isBuiltIn(model.shapeName)
      }
    } yield result

  private def filterMembers(
      shape: String,
      members: List[
        (String, software.amazon.awssdk.codegen.model.service.Member)
      ]
  ): ZIO[GeneratorContext, Nothing, List[
    (String, software.amazon.awssdk.codegen.model.service.Member)
  ]] = {
    getModels.flatMap { models =>
      val shapeModifiers = Option(
        models.customizationConfig().getShapeModifiers
      ).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
      val global = shapeModifiers.get("*")
      val local = shapeModifiers.get(shape)
      val globalExcludes = global
        .flatMap(g => Option(g.getExclude))
        .map(_.asScala)
        .getOrElse(List.empty)
        .map(_.toLowerCase)
      val localExcludes = local
        .flatMap(l => Option(l.getExclude))
        .map(_.asScala)
        .getOrElse(List.empty)
        .map(_.toLowerCase)

      ZIO.succeed(members.filterNot { case (memberName, member) =>
        globalExcludes.contains(memberName.toLowerCase) ||
          localExcludes.contains(memberName.toLowerCase) ||
          member.isStreaming || {
            val shape = models.serviceModel().getShape(member.getShape)
            shape.isStreaming || shape.isEventstream
          }
      })
    }
  }

  private def applyEnumModifiers(
      model: Model,
      enumValueList: List[String]
  ): ZIO[GeneratorContext, Nothing, List[String]] = {
    getModels.map { models =>
      val shapeModifiers = Option(
        models.customizationConfig().getShapeModifiers
      ).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
      shapeModifiers.get(model.shapeName) match {
        case Some(shapeModifier) =>
          val renames = Option(shapeModifier.getModify)
            .map(_.asScala.flatMap(_.asScala.toList))
            .getOrElse(List.empty)
            .toMap

          enumValueList.map { enumValue =>
            renames.get(enumValue) match {
              case Some(modifier)
                  if Option(modifier.getEmitEnumName).isDefined =>
                modifier.getEmitEnumName
              case _ => enumValue
            }
          }
        case None =>
          enumValueList
      }
    }
  }

  private def adjustFieldType(
      model: Model,
      fieldName: String,
      fieldModel: Model
  ): ZIO[GeneratorContext, Nothing, Model] = {
    getModels.map { models =>
      val shapeModifiers = Option(
        models.customizationConfig().getShapeModifiers
      ).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
      shapeModifiers
        .get(model.shapeName)
        .flatMap { shapeModifier =>
          val modifies =
            Option(shapeModifier.getModify).map(_.asScala).getOrElse(List.empty)
          val matchingModifiers = modifies.flatMap { modifiesMap =>
            modifiesMap.asScala
              .map { case (key, value) => (key.toLowerCase, value) }
              .get(fieldName.toLowerCase)
          }.toList

          matchingModifiers
            .map(modifier => Option(modifier.getEmitAsType))
            .find(_.isDefined)
            .flatten
            .map(ModelType.fromString)
            .map { newTyp =>
              val resetedName = newTyp match {
                case ModelType.String     => "String"
                case ModelType.Integer    => "Int"
                case ModelType.Long       => "Long"
                case ModelType.Float      => "Float"
                case ModelType.Double     => "Double"
                case ModelType.Boolean    => "Boolean"
                case ModelType.Timestamp  => "Instant"
                case ModelType.BigDecimal => "BigDecimal"
                case ModelType.Blob       => "Chunk[Byte]"
                case _                    => fieldModel.name
              }
              fieldModel.copy(
                name = resetedName,
                shapeName = resetedName,
                typ = newTyp
              )
            }
        }
        .getOrElse(fieldModel)
    }
  }

  private def roToEditable(
      model: Model,
      term: Term
  ): ZIO[GeneratorContext, GeneratorFailure, Term] =
    model.typ match {
      case ModelType.Map =>
        for {
          keyModel <- get(model.shape.getMapKeyType.getShape)
          valueModel <- get(model.shape.getMapValueType.getShape)
          key = Term.Name("key")
          value = Term.Name("value")
          keyToEditable <- roToEditable(keyModel, key)
          valueToEditable <- roToEditable(valueModel, value)
        } yield
          if (keyToEditable == key && valueToEditable == value) {
            term
          } else {
            q"""$term.map { case (key, value) => $keyToEditable -> $valueToEditable }"""
          }
      case ModelType.List =>
        for {
          valueModel <- get(model.shape.getListMember.getShape)
          item = Term.Name("item")
          itemToEditable <- roToEditable(valueModel, item)
        } yield
          if (itemToEditable == item) {
            term
          } else {
            q"""$term.map { item => $itemToEditable }"""
          }
      case ModelType.Structure =>
        ZIO.succeed(q"""$term.editable""")
      case _ =>
        ZIO.succeed(term)
    }

  private def generateModel(
      m: Model
  ): ZIO[GeneratorContext, GeneratorFailure, ModelWrapper] = {
    for {
      awsShapeNameT <- TypeMapping.toJavaType(m)
      awsShapeNameTerm = awsShapeNameT match {
        case Type.Select(term, Type.Name(name)) =>
          Term.Select(term, Term.Name(name))
        case _ => Term.Name(m.name)
      }
      wrapper <- m.typ match {
        case ModelType.Structure =>
          generateStructure(m, awsShapeNameT, awsShapeNameTerm)
        case ModelType.List =>
          generateList(m)
        case ModelType.Map =>
          generateMap(m)
        case ModelType.Enum =>
          generateEnum(m, awsShapeNameT)
        case ModelType.String =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = String""")
        case ModelType.Integer =>
          generateSimple(m.name + ".scala" , q"""type ${m.asType} = Int""")
        case ModelType.Long =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Long""")
        case ModelType.Float =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Float""")
        case ModelType.Double =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Double""")
        case ModelType.Boolean =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Boolean""")
        case ModelType.Timestamp =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Instant""")
        case ModelType.Blob =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Chunk[Byte]""")
        case _ =>
          generateSimple(m.name + ".scala", q"""type ${m.asType} = Unit""")
      }
    } yield wrapper
  }

  private def generateSimple(
      fileName: String,
      defn: Defn
  ): ZIO[GeneratorContext, GeneratorFailure, ModelWrapper] =
    ZIO.succeed(
      ModelWrapper(
        None,
        code = List(defn)
      )
    )

  private def generateStructure(
      m: Model,
      awsShapeNameT: Type,
      awsShapeNameTerm: Term.Ref with Pat
  ) = {
    val roT = Type.Name("ReadOnly")
    val shapeNameRoT = Type.Select(m.asTerm, roT)
    val shapeNameRoInit = Init(shapeNameRoT, Name.Anonymous(), List.empty)

    for {
      namingStrategy <- getNamingStrategy
      fieldList <- filterMembers(m.name, m.shape.getMembers.asScala.toList)
      required =
        Option(m.shape.getRequired).map(_.asScala.toSet).getOrElse(Set.empty)
      fieldModels <-
        ZIO
          .foreach(fieldList) { case (memberName, member) =>
            for {
              fieldModel <- get(member.getShape)
              finalFieldModel <- adjustFieldType(m, memberName, fieldModel)
            } yield (memberName -> finalFieldModel)
          }
          .map(_.toMap)
      fieldNames <-
        ZIO
          .foreach(fieldModels.toList) { case (memberName, fieldModel) =>
            for {
              property <- propertyName(m, fieldModel, memberName)
            } yield (memberName -> property)
          }
          .map(_.toMap)
      fields <- ZIO.foreach(fieldList) { case (memberName, _) =>
        val finalFieldModel = fieldModels(memberName)
        val property = fieldNames(memberName)
        val propertyNameLit = Lit.String(property.wrapperName)
        val propertyNameTerm = Term.Name(property.wrapperName)
        val propertyNameJavaTerm = Term.Name(property.javaName)

        val propertyValueNameTerm =
          if (
            fieldNames.values.toSet
              .map((names: PropertyNames) => names.wrapperName)
              .contains(property.wrapperName + "Value")
          ) {
            Term.Name(property.wrapperName + "Value_")
          } else {
            Term.Name(property.wrapperName + "Value")
          }

        val fluentSetter = Term.Name(
          namingStrategy.getFluentSetterMethodName(
            property.javaName,
            m.shape,
            finalFieldModel.shape
          )
        )

        TypeMapping.toWrappedType(finalFieldModel).flatMap { memberT =>
          TypeMapping.toWrappedTypeReadOnly(finalFieldModel).flatMap {
            memberRoT =>
              if (required contains memberName) {
                unwrapSdkValue(finalFieldModel, propertyNameTerm).flatMap {
                  unwrappedGet =>
                    wrapSdkValue(
                      finalFieldModel,
                      Term.Apply(
                        Term.Select(Term.Name("impl"), propertyNameJavaTerm),
                        List.empty
                      )
                    ).flatMap { wrappedGet =>
                      roToEditable(finalFieldModel, propertyValueNameTerm)
                        .map { toEditable =>
                          ModelFieldFragments(
                            paramDef = param"""$propertyNameTerm: $memberT""",
                            getterCall = toEditable,
                            getterInterface =
                              q"""def $propertyValueNameTerm: $memberRoT""",
                            getterImplementation =
                              q"""override def $propertyValueNameTerm: $memberRoT = $wrappedGet""",
                            zioGetterImplementation =
                              q"""def $propertyNameTerm: ZIO[Any, Nothing, $memberRoT] = ZIO.succeed($propertyValueNameTerm)""",
                            applyToBuilder = builder =>
                              q"""$builder.$fluentSetter($unwrappedGet)"""
                          )
                        }
                    }
                }
              } else {
                val get = Term.Apply(
                  Term.Select(Term.Name("impl"), propertyNameJavaTerm),
                  List.empty
                )
                val valueTerm = Term.Name("value")
                wrapSdkValue(finalFieldModel, valueTerm).flatMap { wrappedGet =>
                  unwrapSdkValue(finalFieldModel, valueTerm).flatMap {
                    unwrappedGet =>
                      roToEditable(finalFieldModel, valueTerm).map {
                        toEditable =>
                          ModelFieldFragments(
                            paramDef =
                              param"""$propertyNameTerm: scala.Option[$memberT] = None""",
                            getterCall =
                              q"""$propertyValueNameTerm.map(value => $toEditable)""",
                            getterInterface =
                              q"""def ${propertyValueNameTerm}: scala.Option[$memberRoT]""",
                            getterImplementation =
                              if (wrappedGet == valueTerm) {
                                q"""override def $propertyValueNameTerm: scala.Option[$memberRoT] = scala.Option($get)"""
                              } else {
                                q"""override def $propertyValueNameTerm: scala.Option[$memberRoT] = scala.Option($get).map(value => $wrappedGet)"""
                              },
                            zioGetterImplementation =
                              q"""def $propertyNameTerm: ZIO[Any, io.github.vigoo.zioaws.core.AwsError, $memberRoT] = io.github.vigoo.zioaws.core.AwsError.unwrapOptionField($propertyNameLit, $propertyValueNameTerm)""",
                            applyToBuilder = builder =>
                              q"""$builder.optionallyWith($propertyNameTerm.map(value => $unwrappedGet))(_.$fluentSetter)"""
                          )
                      }
                  }
                }
              }
          }
        }
      }
      createBuilderTerm = Term.Apply(
        Term.Select(awsShapeNameTerm, Term.Name("builder")),
        List.empty
      )
      builderChain = fields.foldLeft(createBuilderTerm) {
        case (term, fieldFragments) =>
          fieldFragments.applyToBuilder(term)
      }
    } yield ModelWrapper(
      fileName = Some(m.name + ".scala"),
      code = List(
        q"""final case class ${m.asType}(..${fields.map(_.paramDef)}) {
                        def buildAwsValue(): $awsShapeNameT = {
                          import ${m.asTerm}.zioAwsBuilderHelper.BuilderOps
                          $builderChain.build()
                        }

                        def asReadOnly: ${m.asTerm}.$roT = ${m.asTerm}.wrap(buildAwsValue())
                      }""",
        q"""object ${m.asTerm} {
                            private lazy val zioAwsBuilderHelper: io.github.vigoo.zioaws.core.BuilderHelper[$awsShapeNameT] = io.github.vigoo.zioaws.core.BuilderHelper.apply
                            trait $roT {
                              def editable: ${m.asType} = ${m.asTerm}(..${fields
          .map(_.getterCall)})
                              ..${fields.map(_.getterInterface)}
                              ..${fields.map(_.zioGetterImplementation)}
                            }

                            private class Wrapper(impl: $awsShapeNameT) extends $shapeNameRoInit {
                              ..${fields.map(_.getterImplementation)}
                            }

                            def wrap(impl: $awsShapeNameT): $roT = new Wrapper(impl)
                          }
                         """
      )
    )
  }

  private def generateList(
      m: Model
  ): ZIO[GeneratorContext, GeneratorFailure, ModelWrapper] = {
    for {
      itemModel <- get(m.shape.getListMember.getShape)
      elemT <- TypeMapping.toWrappedType(itemModel)
    } yield ModelWrapper(
      fileName = None,
      code = List(q"""type ${m.asType} = List[$elemT]""")
    )
  }

  private def generateMap(
      m: Model
  ): ZIO[GeneratorContext, GeneratorFailure, ModelWrapper] = {
    for {
      keyModel <- get(m.shape.getMapKeyType.getShape)
      valueModel <- get(m.shape.getMapValueType.getShape)
      keyT <- TypeMapping.toWrappedType(keyModel)
      valueT <- TypeMapping.toWrappedType(valueModel)
    } yield ModelWrapper(
      fileName = None,
      code = List(q"""type ${m.asType} = Map[$keyT, $valueT]""")
    )
  }

  private def generateEnum(
      m: Model,
      awsShapeNameT: Type
  ): ZIO[GeneratorContext, GeneratorFailure, ModelWrapper] = {
    for {
      namingStrategy <- getNamingStrategy
      modelPkg <- getModelPkg
      shapeNameI = Init(m.asType, Name.Anonymous(), List.empty)
      enumVals <- applyEnumModifiers(m, m.shape.getEnumValues.asScala.toList)
      enumValueList = "unknownToSdkVersion" :: enumVals
      enumValues = enumValueList.map { enumValue =>
        val enumValueTerm = Term.Name(enumValue)
        val enumValueScreaming =
          Term.Name(namingStrategy.getEnumValueName(enumValue))
        q"""case object $enumValueTerm extends $shapeNameI {
              override def unwrap: $awsShapeNameT = ${Term
          .Select(modelPkg, m.asTerm)}.${enumValueScreaming}
            }
         """
      }
      wrapPatterns = Term.Match(
        Term.Name("value"),
        enumValueList.map { enumValue =>
          val enumValueTerm = Term.Name(enumValue)
          val enumValueScreaming =
            Term.Name(namingStrategy.getEnumValueName(enumValue))
          val term =
            Term.Select(Term.Select(modelPkg, m.asTerm), enumValueScreaming)
          p"""case $term => { val r = $enumValueTerm; r }"""
        }
      )
    } yield ModelWrapper(
      fileName = Some(m.name + ".scala"),
      code = List(
        q"""sealed trait ${m.asType} {
              def unwrap: $awsShapeNameT
            }
         """,
        q"""object ${m.asTerm} {
              def wrap(value: $awsShapeNameT): ${m.asType} =
                $wrapPatterns

              ..$enumValues
            }
         """
      )
    )
  }

  private def generateServiceModelsCode()
      : ZIO[GeneratorContext, GeneratorFailure, Map[String, String]] =
    for {
      id <- getService
      pkgName = Term.Name(id.moduleName)
      fullPkgName = Term.Select(q"io.github.vigoo.zioaws", pkgName)

      filteredModels <- filterModels()
      partition = filteredModels.partition(model =>
        TypeMapping.isPrimitiveType(model.shape)
      )
      (primitives, complexes) = partition

      primitiveModels = removeDuplicates(primitives)
      complexModels = removeDuplicates(complexes)

      primitiveModels <-
        ZIO.foreach(primitiveModels.toList.sortBy(_.name))(generateModel)
      models <- ZIO.foreach(complexModels.toList.sortBy(_.name))(generateModel)
      modelsForPackage = models.filter(_.fileName.isEmpty)
      separateModels = models.collect { case ModelWrapper(Some(fileName), code) => (fileName, code) }
      packageCode = 
        q"""package $fullPkgName {

            import scala.jdk.CollectionConverters._
            import java.time.Instant
            import zio.{Chunk, ZIO}
            import software.amazon.awssdk.core.SdkBytes

            package object model {
              object primitives {
                ..${primitiveModels.flatMap(_.code)}
              }

              ..${modelsForPackage.flatMap(_.code)}
            }}"""
          modelCodes = separateModels.map { case (fileName, code) =>
            fileName ->
            q"""package $fullPkgName.model {

              import scala.jdk.CollectionConverters._
              import java.time.Instant
              import zio.{Chunk, ZIO}
              import software.amazon.awssdk.core.SdkBytes

              ..${code}
            }"""
            }
    } yield ("package.scala" -> prettyPrint(packageCode) :: modelCodes.map { case (k, v) => (k, prettyPrint(v)) }).toMap

  protected def generateServiceModels()
      : ZIO[GeneratorContext with Blocking, GeneratorFailure, Set[File]] =
    for {
      codes <- generateServiceModelsCode()
      id <- getService
      moduleName = id.moduleName
      targetRoot = config.targetRoot
      packageParent = targetRoot / "io/github/vigoo/zioaws"
      packageRoot = packageParent / moduleName
      modelsRoot = packageRoot / "model"
      _ <-Files.createDirectories(modelsRoot).mapError(FailedToCreateDirectories)
      paths <- ZIO.foreach(codes.toSet) { case (name, code) => 
        val modelFile = modelsRoot / name
        writeIfDifferent(modelFile, code).as(modelFile)
      }
    } yield paths.map(_.toFile)
}
