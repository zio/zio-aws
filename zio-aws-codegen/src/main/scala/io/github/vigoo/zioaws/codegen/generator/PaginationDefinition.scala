package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType

sealed trait PaginationDefinition

case class JavaSdkPaginationDefinition(
    name: String,
    model: Model,
    itemType: ScalaType,
    wrappedTypeRo: ScalaType
) extends PaginationDefinition

case class ListPaginationDefinition(
    memberName: String,
    listModel: Model,
    itemModel: Model,
    isSimple: Boolean
) extends PaginationDefinition

case class NestedListPaginationDefinition(
    innerName: String,
    innerModel: Model,
    resultName: String,
    resultModel: Model,
    listName: String,
    listModel: Model,
    itemModel: Model
) extends PaginationDefinition

case class MapPaginationDefinition(
    memberName: String,
    mapModel: Model,
    keyModel: Model,
    valueModel: Model,
    isSimple: Boolean
) extends PaginationDefinition

case class StringPaginationDefinition(
    memberName: String,
    stringModel: Model,
    isSimple: Boolean
) extends PaginationDefinition
