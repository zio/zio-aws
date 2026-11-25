package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType

/** Describes which member of a paginated operation's response holds the items,
  * which the generator turns into a stream of items.
  */
sealed trait PaginationDefinition

/** Pagination through the paginator that the AWS Java SDK provides. `name` is
  * the result key, `model` the model of an item, `itemType` its SDK type and
  * `wrappedTypeRo` its read-only zio-aws type.
  */
case class JavaSdkPaginationDefinition(
    name: String,
    model: Model,
    itemType: ScalaType,
    wrappedTypeRo: ScalaType
) extends PaginationDefinition

/** Pagination over the list member `memberName` of the response. `isSimple` is
  * `true` when the response has no member other than `memberName` and
  * `NextToken`.
  */
case class ListPaginationDefinition(
    memberName: String,
    listModel: Model,
    itemModel: Model,
    isSimple: Boolean
) extends PaginationDefinition

/** Pagination over the list `listName` inside the member `innerName` of the
  * response, together with the member `resultName` of that inner shape.
  */
case class NestedListPaginationDefinition(
    innerName: String,
    innerModel: Model,
    resultName: String,
    resultModel: Model,
    listName: String,
    listModel: Model,
    itemModel: Model
) extends PaginationDefinition

/** Pagination over the map member `memberName` of the response. `isSimple` is
  * `true` when the response has no member other than `memberName` and
  * `NextToken`.
  */
case class MapPaginationDefinition(
    memberName: String,
    mapModel: Model,
    keyModel: Model,
    valueModel: Model,
    isSimple: Boolean
) extends PaginationDefinition

/** Pagination over the string member `memberName` of the response. `isSimple`
  * is `true` when the response has no member other than `memberName` and
  * `NextToken`.
  */
case class StringPaginationDefinition(
    memberName: String,
    stringModel: Model,
    isSimple: Boolean
) extends PaginationDefinition
