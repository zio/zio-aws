package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.{
  CodeFileGenerator,
  Generator,
  GeneratorFailure,
  ScalaType
}
import software.amazon.awssdk.codegen.model.config.customization.ShapeModifier
import zio.ZIO
import zio.aws.codegen.generator.context.AwsGeneratorContext._
import zio.aws.codegen.generator.context._
import zio.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.meta._

trait ServiceModelGenerator {
  this: HasConfig with GeneratorBase with Blacklists =>

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
        .map(
          _.asScala.collect {
            case (name, modifier) if modifier.isExcludeShape => name
          }.toSet
        )
        .getOrElse(Set.empty)

      result = modelMap.all.filterNot { model =>
        excluded.contains(model.serviceModelName) ||
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
          generateStructure(m, javaType, isException = false)
        case ModelType.Exception =>
          generateStructure(m, javaType, isException = true)
        case ModelType.Document =>
          generateDocument(m)
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
    if (isBlacklistedNewtype(wrapperType))
      ZIO.succeed(
        ModelWrapper(
          None,
          code =
            List(q"""type ${wrapperType.typName} = ${underlyingType.typ}"""),
          wrapperType
        )
      )
    else
      ZIO.succeed(
        ModelWrapper(
          None,
          code = List(
            q"""object ${wrapperType.termName} extends ${Types
              .subtype(underlyingType)
              .init}""",
            q"""type ${wrapperType.typName} = ${Type
              .Select(wrapperType.term, Type.Name("Type"))}"""
          ),
          wrapperType
        )
      )

  private def generateStructure(
      m: Model,
      javaType: ScalaType,
      isException: Boolean
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] = {
    val readOnlyType = m.generatedType / "ReadOnly"
    val shapeNameRoInit = Init(readOnlyType.typ, Name.Anonymous(), List.empty)

    for {
      serviceName <- getServiceName
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
        val propertyNameJavaTerm =
          if (isException) Term.Name(s"get${property.javaName.capitalize}")
          else Term.Name(property.javaName)

        val effectualGetterNameTerm =
          Term.Name("get" + property.wrapperName.capitalize)
        val pureGetterNameTerm = Term.Name(property.wrapperName)

        val fluentSetter = Term.Name(
          namingStrategy.getFluentSetterMethodName(
            property.javaName,
            m.shape,
            finalFieldModel.shape
          )
        )

        val witherNameTerm =
          Term.Name("with" + property.wrapperName.capitalize)

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
                        .flatMap { toEditable =>
                          roToEditable(
                            finalFieldModel,
                            Term.Select(
                              Term.Name("asReadOnly"),
                              pureGetterNameTerm
                            )
                          ).map { editableFromReadOnly =>
                            val applyToBuilderFn: Term.Apply => Term.Apply =
                              builder =>
                                q"""$builder.$fluentSetter($unwrappedGet)"""
                            val witherChain =
                              q"""impl.toBuilder().$fluentSetter($unwrappedGet).build()"""
                            val witherBuild =
                              if (isException)
                                q"""$witherChain.asInstanceOf[${javaType.typ}]"""
                              else witherChain
                            ModelFieldFragments(
                              paramDef =
                                param"""$pureGetterNameTerm: ${memberType.typ}""",
                              getterCall = toEditable,
                              getterInterface =
                                q"""def $pureGetterNameTerm: ${memberRoType.typ}""",
                              getterImplementation = q"""override val ${Pat.Var(
                                pureGetterNameTerm
                              )}: ${memberRoType.typ} = $wrappedGet""",
                              zioGetterImplementation =
                                q"""def $effectualGetterNameTerm: ${Types
                                  .zio(
                                    ScalaType.any,
                                    ScalaType.nothing,
                                    memberRoType
                                  )
                                  .typ} = ZIO.succeed($pureGetterNameTerm)""",
                              applyToBuilder = applyToBuilderFn,
                              editableGetter =
                                q"""def $pureGetterNameTerm: ${memberType.typ} = $editableFromReadOnly""",
                              wither = q"""def $witherNameTerm(..${List(
                                param"""$pureGetterNameTerm: ${memberType.typ}"""
                              )}): ${m.generatedType.typ} = ${Term.New(
                                Init(
                                  m.generatedType.typ,
                                  Name.Anonymous(),
                                  List(List(witherBuild))
                                )
                              )}""",
                              isRequired = true
                            )
                          }
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
                          val applyToBuilderFn: Term.Apply => Term.Apply =
                            builder =>
                              q"""$builder.optionallyWith($pureGetterNameTerm.map(value => $unwrappedGet))(_.$fluentSetter)"""
                          val witherChain = q"""${applyToBuilderFn(
                            Term.Apply(
                              Term.Select(
                                Term.Name("impl"),
                                Term.Name("toBuilder")
                              ),
                              List.empty
                            )
                          )}.build()"""
                          val witherBuild =
                            if (isException)
                              q"""$witherChain.asInstanceOf[${javaType.typ}]"""
                            else witherChain
                          ModelFieldFragments(
                            paramDef = param"""$pureGetterNameTerm: ${Types
                              .optional(memberType)
                              .typ} = ${Types.optionalAbsent.term}""",
                            getterCall =
                              q"""$pureGetterNameTerm.map(value => $toEditable)""",
                            getterInterface =
                              q"""def ${pureGetterNameTerm}: ${Types
                                .optional(memberRoType)
                                .typ}""",
                            getterImplementation =
                              if (wrappedGet == valueTerm) {
                                q"""override val ${Pat
                                  .Var(pureGetterNameTerm)}: ${Types
                                  .optional(memberRoType)
                                  .typ} = ${Types.optionalFromNullable}($get)"""
                              } else {
                                q"""override val ${Pat
                                  .Var(pureGetterNameTerm)}: ${Types
                                  .optional(memberRoType)
                                  .typ} = ${Types.optionalFromNullable}($get).map(value => $wrappedGet)"""
                              },
                            zioGetterImplementation =
                              q"""def $effectualGetterNameTerm: ${Types
                                .zio(
                                  ScalaType.any,
                                  Types.awsError,
                                  memberRoType
                                )
                                .typ} = ${Types.awsError.term}.unwrapOptionField($propertyNameLit, $pureGetterNameTerm)""",
                            applyToBuilder = applyToBuilderFn,
                            editableGetter =
                              q"""def $pureGetterNameTerm: ${Types
                                .optional(memberType)
                                .typ} = asReadOnly.$pureGetterNameTerm.map(value => $toEditable)""",
                            wither = q"""def $witherNameTerm(..${List(
                              param"""$pureGetterNameTerm: ${Types
                                .optional(memberType)
                                .typ}"""
                            )}): ${m.generatedType.typ} = {
                                  import ${m.generatedType.termName}.zioAwsBuilderHelper.BuilderOps
                                  ${Term.New(
                              Init(
                                m.generatedType.typ,
                                Name.Anonymous(),
                                List(List(witherBuild))
                              )
                            )}
                                }""",
                            isRequired = false
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
      build =
        if (isException) {
          q"""$builderChain.build().asInstanceOf[${javaType.typ}]"""
        } else {
          q"""$builderChain.build()"""
        }

      // Generate has* methods for DynamoDB AttributeValue
      dynamoDbAttributeValueMethods =
        if (
          serviceName == "dynamodb" && m.generatedType.name == "AttributeValue"
        ) {
          generateDynamoDbAttributeValueHasMethods()
        } else List.empty[Defn.Def]

      dynamoDbAttributeValueWrapperMethods =
        if (
          serviceName == "dynamodb" && m.generatedType.name == "AttributeValue"
        ) {
          generateDynamoDbAttributeValueWrapperMethods()
        } else List.empty[Defn.Def]

    } yield ModelWrapper(
      fileName = Some(m.generatedType.name),
      code =
        if (fields.size > maxCaseClassFields)
          generateOversizedStructureCode(
            m,
            javaType,
            isException,
            fields,
            readOnlyType,
            shapeNameRoInit
          )
        else
          generateCaseClassStructureCode(
            m,
            javaType,
            fields,
            readOnlyType,
            shapeNameRoInit,
            build,
            dynamoDbAttributeValueMethods,
            dynamoDbAttributeValueWrapperMethods
          ),
      generatedType = m.generatedType
    )
  }

  private def generateCaseClassStructureCode(
      m: Model,
      javaType: ScalaType,
      fields: List[ModelFieldFragments],
      readOnlyType: ScalaType,
      shapeNameRoInit: Init,
      build: Term,
      dynamoDbAttributeValueMethods: List[Defn.Def],
      dynamoDbAttributeValueWrapperMethods: List[Defn.Def]
  ): List[Defn] =
    List(
      q"""final case class ${m.generatedType.typName}(..${fields.map(
        _.paramDef
      )}) {
                        def buildAwsValue(): ${javaType.typ} = {
                          import ${m.generatedType.termName}.zioAwsBuilderHelper.BuilderOps
                          $build
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
                              ..$dynamoDbAttributeValueMethods
                            }

                            private final class Wrapper(impl: ${javaType.typ}) extends $shapeNameRoInit {
                              ..${fields.map(_.getterImplementation)}
                              ..$dynamoDbAttributeValueWrapperMethods
                            }

                            def wrap(impl: ${javaType.typ}): ${readOnlyType.typ} = new Wrapper(impl)
                          }
                         """
    )

  // The JVM limits method descriptors (and thus constructors) to 254
  // parameters, and scalac refuses larger parameter lists ("Platform
  // restriction: a parameter list's length cannot exceed 254").
  private val maxCaseClassFields: Int = 254

  // Structures too large to be represented as a case class (e.g. the
  // QuickSight Capabilities shape with 279 members) are generated as a wrapper
  // around the built Java value instead, with one getter and one `with*`
  // method per field taking the place of the constructor parameters. The
  // public contract used by the rest of the generated code (`apply` with the
  // required fields, `buildAwsValue`, `asReadOnly`, `wrap`, and
  // `ReadOnly.asEditable`) stays the same as for case class structures.
  private def generateOversizedStructureCode(
      m: Model,
      javaType: ScalaType,
      isException: Boolean,
      fields: List[ModelFieldFragments],
      readOnlyType: ScalaType,
      shapeNameRoInit: Init
  ): List[Defn] = {
    val (requiredFields, optionalFields) = fields.partition(_.isRequired)
    val createBuilderTerm = Term.Apply(
      Term.Select(javaType.term, Term.Name("builder")),
      List.empty
    )
    val applyBuilderChain = requiredFields.foldLeft(createBuilderTerm) {
      case (term, fieldFragments) =>
        fieldFragments.applyToBuilder(term)
    }
    val applyBuild =
      if (isException)
        q"""$applyBuilderChain.build().asInstanceOf[${javaType.typ}]"""
      else
        q"""$applyBuilderChain.build()"""
    val asEditableChain = optionalFields.foldLeft(
      Term.Apply(m.generatedType.term, requiredFields.map(_.getterCall)): Term
    ) { case (term, fieldFragments) =>
      q"""$term.${fieldFragments.witherName}(${fieldFragments.getterCall})"""
    }

    val classMembers: List[Stat] =
      List[Stat](
        q"""def buildAwsValue(): ${javaType.typ} = impl""",
        q"""def asReadOnly: ${readOnlyType.typ} = ${m.generatedType.term}.wrap(impl)"""
      ) ++
        fields.map(_.editableGetter) ++
        fields.map(_.wither) ++
        List[Stat](
          q"""override def equals(other: Any): Boolean = other.isInstanceOf[${m.generatedType.typ}] && impl == other.asInstanceOf[${m.generatedType.typ}].buildAwsValue()""",
          q"""override def hashCode(): Int = impl.hashCode()""",
          q"""override def toString(): String = impl.toString()"""
        )

    List(
      Defn.Class(
        mods = List(Mod.Final()),
        name = m.generatedType.typName,
        tparams = List.empty,
        ctor = Ctor.Primary(
          mods = List(Mod.Private(Name.Anonymous())),
          name = Name.Anonymous(),
          paramss = List(List(param"""private val impl: ${javaType.typ}"""))
        ),
        templ = Template(
          List.empty,
          List.empty,
          Self(Name.Anonymous(), None),
          classMembers
        )
      ),
      q"""object ${m.generatedType.termName} {
            private lazy val zioAwsBuilderHelper: ${Types
        .builderHelper(javaType)
        .typ} = ${Types.builderHelper_.term}.apply
            def apply(..${requiredFields.map(
        _.paramDef
      )}): ${m.generatedType.typ} = ${Term
        .New(
          Init(
            m.generatedType.typ,
            Name.Anonymous(),
            List(List(applyBuild))
          )
        )}
            trait ${readOnlyType.typName} {
              def asEditable: ${m.generatedType.typ} = $asEditableChain
              ..${fields.map(_.getterInterface)}
              ..${fields.map(_.zioGetterImplementation)}
            }
            private final class Wrapper(impl: ${javaType.typ}) extends $shapeNameRoInit {
              ..${fields.map(_.getterImplementation)}
            }
            def wrap(impl: ${javaType.typ}): ${readOnlyType.typ} = new Wrapper(impl)
          }
         """
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
      generatedType = m.generatedType
    )
  }

  private def generateDocument(
      m: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ModelWrapper] =
    ZIO.succeed {
      ModelWrapper(
        fileName = None,
        code = List(
          q"""type ${m.generatedType.typName} = ${Types.awsDocument.typ}"""
        ),
        generatedType = m.generatedType
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
      generatedType = m.generatedType
    )
  }

  // Scalameta backtick-escapes hard keywords (`private`, `type`, ...) when
  // printing identifiers, but not Scala 3 soft keywords. Of those, `inline` is
  // rejected by the Scala 3 parser in the positions the generated enum code
  // uses it (e.g. the IAM PolicyIdentifierPolicyType enum has an "inline"
  // value), so such enum values get a trailing underscore in the generated
  // case object name.
  private val unescapableScalaKeywords: Set[String] = Set("inline")

  private def enumValueTermName(enumValue: String): Term.Name =
    if (unescapableScalaKeywords.contains(enumValue))
      Term.Name(enumValue + "_")
    else
      Term.Name(enumValue)

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
        val enumValueTerm = enumValueTermName(enumValue)
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
          val enumValueTerm = enumValueTermName(enumValue)
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
      generatedType = m.generatedType
    )
  }

  private def generateDynamoDbAttributeValueHasMethods(): List[Defn.Def] = {
    List(
      // Only generate methods that exist in the AWS SDK
      q"""def hasSs: Boolean = ss.isDefined""",
      q"""def hasNs: Boolean = ns.isDefined""",
      q"""def hasBs: Boolean = bs.isDefined""",
      q"""def hasM: Boolean = m.isDefined""",
      q"""def hasL: Boolean = l.isDefined"""
    )
  }

  private def generateDynamoDbAttributeValueWrapperMethods(): List[Defn.Def] = {
    List(
      // Only generate methods that exist in the AWS SDK
      q"""override def hasSs: Boolean = impl.hasSs()""",
      q"""override def hasNs: Boolean = impl.hasNs()""",
      q"""override def hasBs: Boolean = impl.hasBs()""",
      q"""override def hasM: Boolean = impl.hasM()""",
      q"""override def hasL: Boolean = impl.hasL()"""
    )
  }

  private def generateServiceModelsCode()
      : ZIO[Generator with AwsGeneratorContext, GeneratorFailure[
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
        for {
          _ <- ZIO.foreachDiscard(namesInModel)(
            CodeFileGenerator.knownLocalName(_)
          )
          _ <- ZIO.foreachDiscard(
            primitiveModels.map(m => m.generatedType / "Type")
          )(
            CodeFileGenerator.keepFullyQualified(_)
          )
          _ <- CodeFileGenerator.keepFullyQualified(Types.subtype_)
        } yield q"""import scala.jdk.CollectionConverters._

                    object primitives {
                      ..${primitiveModels.flatMap(_.code)}
                    }

                    ..${modelsForPackage.flatMap(_.code)}
                 """
      }
      models <- ZIO.foreach(separateModels) { case (fileName, code) =>
        Generator.generateScalaPackage[Any, Nothing](pkg / "model", fileName) {
          ZIO
            .foreachDiscard(namesInModel)(CodeFileGenerator.knownLocalName(_))
            .as(
              q"""
              import scala.jdk.CollectionConverters._

              ..${code}
             """
            )
        }
      }
    } yield (modelPkgObject :: models).toSet

  protected def generateServiceModels()
      : ZIO[Generator with AwsGeneratorContext, GeneratorFailure[
        AwsGeneratorFailure
      ], Set[Path]] =
    for {
      _ <- Generator.setScalaVersion(scalaVersion)
      _ <- Generator.setRoot(config.targetRoot)
      paths <- generateServiceModelsCode()
    } yield paths
}
