package io.github.vigoo.zioaws.codegen.generator

import scala.meta.Type

sealed trait PaginationDefinition

case class JavaSdkPaginationDefinition(name: String,
                                       model: Model,
                                       itemType: Type,
                                       wrappedTypeRo: Type) extends PaginationDefinition

case class ListPaginationDefinition(memberName: String,
                                    listModel: Model,
                                    itemModel: Model,
                                    isSimple: Boolean) extends PaginationDefinition

case class MapPaginationDefinition(memberName: String,
                                   mapModel: Model,
                                   keyModel: Model,
                                   valueModel: Model,
                                   isSimple: Boolean) extends PaginationDefinition

case class StringPaginationDefinition(memberName: String,
                                      stringModel: Model,
                                      isSimple: Boolean) extends PaginationDefinition
