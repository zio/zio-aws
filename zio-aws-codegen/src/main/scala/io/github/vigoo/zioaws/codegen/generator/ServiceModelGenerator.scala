package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._
import io.github.vigoo.metagen.core.{
  CodeFileGenerator,
  Generator,
  GeneratorFailure,
  Package,
  ScalaType
}

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.model.config.customization.ShapeModifier
import zio.{Has, ZIO}
import zio.blocking.Blocking
import zio.nio.core.file.Path

import scala.meta._

trait ServiceModelGenerator {
  this: HasConfig with GeneratorBase =>

  private def removeDuplicates(modelSet: Set[Model]): Set[Model] =
    modelSet
      .foldLeft(Map.empty[ScalaType, Model]) { case (result, model) =>
        result.updated(model.sdkType, model)
      }
      .values
      .toSet

  private def filterModels(): ZIO[AwsGeneratorContext, Nothing, Set[Model]] =
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
  ): ZIO[AwsGeneratorContext, Nothing, List[
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
  ): ZIO[AwsGeneratorContext, Nothing, List[String]] = {
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
  ): ZIO[AwsGeneratorContext, Nothing, Model] = {
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
              val (sdk, generated) = newTyp match {
                case ModelType.String  => (ScalaType.string, ScalaType.string)
                case ModelType.Integer => (ScalaType.int, ScalaType.int)
                case ModelType.Long    => (ScalaType.long, ScalaType.long)
                case ModelType.Float   => (ScalaType.float, ScalaType.float)
                case ModelType.Double  => (ScalaType.double, ScalaType.double)
                case ModelType.Boolean => (ScalaType.boolean, ScalaType.boolean)
                case ModelType.Timestamp => (Types.instant, Types.instant)
                case ModelType.BigDecimal =>
                  (Types.bigDecimal, Types.bigDecimal)
                case ModelType.Blob =>
                  (Types.chunk(ScalaType.byte), Types.chunk(ScalaType.byte))
                case _ => (fieldModel.sdkType, fieldModel.generatedType)
              }
              fieldModel.copy(
                sdkType = sdk,
                generatedType = generated,
                shapeName = sdk.name,
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
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Term] =
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
        ZIO.succeed(q"""$term.asEditable""")
      case _ =>
        ZIO.succeed(term)
    }

  private def generateModel(
      m: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] = {
    for {
      javaType <- TypeMapping.toJavaType(m)
      wrapper <- m.typ match {
        case ModelType.Structure =>
          generateStructure(m, javaType)
        case ModelType.List =>
          generateList(m)
        case ModelType.Map =>
          generateMap(m)
        case ModelType.Enum =>
          generateEnum(m, javaType)
        case ModelType.String =>
          generateNewtype(
            m.generatedType,
            ScalaType.string
          )
        case ModelType.Integer =>
          generateNewtype(
            m.generatedType,
            ScalaType.int
          )
        case ModelType.Long =>
          generateNewtype(
            m.generatedType,
            ScalaType.long
          )
        case ModelType.Float =>
          generateNewtype(
            m.generatedType,
            ScalaType.float
          )
        case ModelType.Double =>
          generateNewtype(
            m.generatedType,
            ScalaType.double
          )
        case ModelType.Boolean =>
          generateNewtype(
            m.generatedType,
            ScalaType.boolean
          )
        case ModelType.Timestamp =>
          generateNewtype(
            m.generatedType,
            Types.instant
          )
        case ModelType.Blob =>
          generateNewtype(
            m.generatedType,
            Types.chunk(ScalaType.byte)
          )
        case _ =>
          generateNewtype(
            m.generatedType,
            ScalaType.unit
          )
      }
    } yield wrapper
  }

  private def generateNewtype(
    wrapperType: ScalaType,
    underlyingType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] =
    ZIO.succeed(
      ModelWrapper(
        None,
        code = List(
          q"""object ${wrapperType.termName} extends ${Types.subtype(underlyingType).init}""",
          q"""type ${wrapperType.typName} = ${Type.Select(wrapperType.term, Type.Name("Type"))}"""
        ),
        wrapperType.name
      )
    )

  private def generateStructure(
      m: Model,
      javaType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] = {
    val readOnlyType = m.generatedType / "ReadOnly"
    val shapeNameRoInit = Init(readOnlyType.typ, Name.Anonymous(), List.empty)

    for {
      namingStrategy <- getNamingStrategy
      fieldList <- filterMembers(m.shapeName, m.shape.getMembers.asScala.toList)
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
        val propertyNameJavaTerm = Term.Name(property.javaName)

        val effectualGetterNameTerm = Term.Name("get" + property.wrapperName.capitalize)
        val pureGetterNameTerm = Term.Name(property.wrapperName)

        val fluentSetter = Term.Name(
          namingStrategy.getFluentSetterMethodName(
            property.javaName,
            m.shape,
            finalFieldModel.shape
          )
        )

        TypeMapping.toWrappedType(finalFieldModel).flatMap { memberType =>
          TypeMapping.toWrappedTypeReadOnly(finalFieldModel).flatMap {
            memberRoType =>
              if (required contains memberName) {
                unwrapSdkValue(finalFieldModel, pureGetterNameTerm).flatMap {
                  unwrappedGet =>
                    wrapSdkValue(
                      finalFieldModel,
                      Term.Apply(
                        Term.Select(Term.Name("impl"), propertyNameJavaTerm),
                        List.empty
                      )
                    ).flatMap { wrappedGet =>
                      roToEditable(finalFieldModel, pureGetterNameTerm)
                        .map { toEditable =>
                          ModelFieldFragments(
                            paramDef =
                              param"""$pureGetterNameTerm: ${memberType.typ}""",
                            getterCall = toEditable,
                            getterInterface =
                              q"""def $pureGetterNameTerm: ${memberRoType.typ}""",
                            getterImplementation =
                              q"""override val ${Pat.Var(pureGetterNameTerm)}: ${memberRoType.typ} = $wrappedGet""",
                            zioGetterImplementation =
                              q"""def $effectualGetterNameTerm: ${Types
                                .zio(
                                  ScalaType.any,
                                  ScalaType.nothing,
                                  memberRoType
                                )
                                .typ} = ZIO.succeed($pureGetterNameTerm)""",
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
                            paramDef = param"""$pureGetterNameTerm: ${ScalaType
                              .option(memberType)
                              .typ} = None""",
                            getterCall =
                              q"""$pureGetterNameTerm.map(value => $toEditable)""",
                            getterInterface =
                              q"""def ${pureGetterNameTerm}: ${ScalaType
                                .option(memberRoType)
                                .typ}""",
                            getterImplementation =
                              if (wrappedGet == valueTerm) {
                                q"""override val ${Pat.Var(pureGetterNameTerm)}: ${ScalaType
                                  .option(memberRoType)
                                  .typ} = ${ScalaType
                                  .option(memberRoType)
                                  .term}($get)"""
                              } else {
                                q"""override val ${Pat.Var(pureGetterNameTerm)}: ${ScalaType
                                  .option(memberRoType)
                                  .typ} = ${ScalaType
                                  .option(memberRoType)
                                  .term}($get).map(value => $wrappedGet)"""
                              },
                            zioGetterImplementation =
                              q"""def $effectualGetterNameTerm: ${Types
                                .zio(
                                  ScalaType.any,
                                  Types.awsError,
                                  memberRoType
                                )
                                .typ} = ${Types.awsError.term}.unwrapOptionField($propertyNameLit, $pureGetterNameTerm)""",
                            applyToBuilder = builder =>
                              q"""$builder.optionallyWith($pureGetterNameTerm.map(value => $unwrappedGet))(_.$fluentSetter)"""
                          )
                      }
                  }
                }
              }
          }
        }
      }
      createBuilderTerm = Term.Apply(
        Term.Select(javaType.term, Term.Name("builder")),
        List.empty
      )
      builderChain = fields.foldLeft(createBuilderTerm) {
        case (term, fieldFragments) =>
          fieldFragments.applyToBuilder(term)
      }
    } yield ModelWrapper(
      fileName = Some(m.generatedType.name),
      code = List(
        q"""final case class ${m.generatedType.typName}(..${fields.map(
          _.paramDef
        )}) {
                        def buildAwsValue(): ${javaType.typ} = {
                          import ${m.generatedType.termName}.zioAwsBuilderHelper.BuilderOps
                          $builderChain.build()
                        }

                        def asReadOnly: ${readOnlyType.typ} = ${m.generatedType.term}.wrap(buildAwsValue())
                      }""",
        q"""object ${m.generatedType.termName} {
                            private lazy val zioAwsBuilderHelper: ${Types
          .builderHelper(javaType)
          .typ} = ${Types.builderHelper_.term}.apply
                            trait ${readOnlyType.typName} {
                              def asEditable: ${m.generatedType.typ} = ${m.generatedType.term}(..${fields
          .map(_.getterCall)})
                              ..${fields.map(_.getterInterface)}
                              ..${fields.map(_.zioGetterImplementation)}
                            }

                            private final class Wrapper(impl: ${javaType.typ}) extends $shapeNameRoInit {
                              ..${fields.map(_.getterImplementation)}
                            }

                            def wrap(impl: ${javaType.typ}): ${readOnlyType.typ} = new Wrapper(impl)
                          }
                         """
      ),
      name = m.generatedType.name
    )
  }

  private def generateList(
      m: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] = {
    for {
      itemModel <- get(m.shape.getListMember.getShape)
      elemType <- TypeMapping.toWrappedType(itemModel)
    } yield ModelWrapper(
      fileName = None,
      code = List(
        q"""type ${m.generatedType.typName} = ${ScalaType.list(elemType).typ}"""
      ),
      name = m.generatedType.name
    )
  }

  private def generateMap(
      m: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] = {
    for {
      keyModel <- get(m.shape.getMapKeyType.getShape)
      valueModel <- get(m.shape.getMapValueType.getShape)
      keyType <- TypeMapping.toWrappedType(keyModel)
      valueType <- TypeMapping.toWrappedType(valueModel)
    } yield ModelWrapper(
      fileName = None,
      code = List(q"""type ${m.generatedType.typName} = ${ScalaType
        .map(keyType, valueType)
        .typ}"""),
      name = m.generatedType.name
    )
  }

  private def generateEnum(
      m: Model,
      javaType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] = {
    for {
      namingStrategy <- getNamingStrategy
      shapeNameI = Init(m.generatedType.typ, Name.Anonymous(), List.empty)
      enumVals <- applyEnumModifiers(m, m.shape.getEnumValues.asScala.toList)
      enumValueList = "unknownToSdkVersion" :: enumVals
      enumValues = enumValueList.map { enumValue =>
        val enumValueTerm = Term.Name(enumValue)
        val enumValueScreaming =
          Term.Name(namingStrategy.getEnumValueName(enumValue))
        q"""case object $enumValueTerm extends $shapeNameI {
              override def unwrap: ${javaType.typ} = ${m.sdkType.term}.${enumValueScreaming}
            }
         """
      }
      wrapPatterns = Term.Match(
        Term.Name("value"),
        enumValueList.map { enumValue =>
          val enumValueTerm = Term.Name(enumValue)
          val enumValueScreaming =
            Term.Name(namingStrategy.getEnumValueName(enumValue))
          val term = Term.Select(m.sdkType.term, enumValueScreaming)
          p"""case $term => { val r = $enumValueTerm; r }"""
        }
      )
    } yield ModelWrapper(
      fileName = Some(m.generatedType.name),
      code = List(
        q"""sealed trait ${m.generatedType.typName} {
              def unwrap: ${javaType.typ}
            }
         """,
        q"""object ${m.generatedType.termName} {
              def wrap(value: ${javaType.typ}): ${m.generatedType.typ} =
                $wrapPatterns

              ..$enumValues
            }
         """
      ),
      name = m.generatedType.name
    )
  }

  private def generateServiceModelsCode(): ZIO[Has[
    Generator
  ] with Blocking with AwsGeneratorContext, GeneratorFailure[
    AwsGeneratorFailure
  ], Set[Path]] =
    for {
      pkg <- getPkg

      filteredModels <- filterModels()
      partition = filteredModels.partition(model =>
        TypeMapping.isPrimitiveType(model.shape)
      )
      (primitives, complexes) = partition

      primitiveModels = removeDuplicates(primitives)
      complexModels = removeDuplicates(complexes)

      primitiveModels <-
        ZIO
          .foreach(primitiveModels.toList.sortBy(_.sdkType.name))(
            generateModel
          )
          .mapError(GeneratorFailure.CustomFailure.apply)
      models <- ZIO
        .foreach(complexModels.toList.sortBy(_.sdkType.name))(
          generateModel
        )
        .mapError(GeneratorFailure.CustomFailure.apply)
      modelsForPackage = models.filter(_.fileName.isEmpty)
      separateModels = models.collect {
        case ModelWrapper(Some(fileName), code, _) => (fileName, code)
      }
      namesInModel = models.map(_.name)
      modelPkgObject <- Generator.generateScalaPackageObject[Any, Nothing](
        pkg,
        "model"
      ) {
        ZIO
          .foreach_(namesInModel)(CodeFileGenerator.knownLocalName(_))
          .as(
            q"""import scala.jdk.CollectionConverters._

              object primitives {
                ..${primitiveModels.flatMap(_.code)}
              }

              ..${modelsForPackage.flatMap(_.code)}
           """
          )
      }
      models <- ZIO.foreach(separateModels) { case (fileName, code) =>
        Generator.generateScalaPackage[Any, Nothing](pkg / "model", fileName) {
          ZIO
            .foreach_(namesInModel)(CodeFileGenerator.knownLocalName(_))
            .as(
              q"""
              import scala.jdk.CollectionConverters._

              ..${code}
             """
            )
        }
      }
    } yield (modelPkgObject :: models).toSet

  protected def generateServiceModels(): ZIO[Has[
    Generator
  ] with AwsGeneratorContext with Blocking, GeneratorFailure[
    AwsGeneratorFailure
  ], Set[Path]] =
    for {
      _ <- Generator.setScalaVersion(scalaVersion)
      _ <- Generator.setRoot(config.targetRoot)
      paths <- generateServiceModelsCode()
    } yield paths
}
