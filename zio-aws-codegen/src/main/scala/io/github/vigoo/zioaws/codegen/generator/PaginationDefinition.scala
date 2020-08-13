package io.github.vigoo.zioaws.codegen.generator

import scala.meta.Type

case class PaginationDefinition(name: String,
                                model: Model,
                                itemType: Type,
                                wrappedTypeRo: Type)

