package io.github.vigoo.zioaws.codegen.generator

import software.amazon.awssdk.codegen.internal.Utils

object syntax {
  implicit class StringOps(val value: String) extends AnyVal {
    def toCamelCase: String =
      (value.toList match {
        case ::(head, next) => head.toLower :: next
        case Nil            => Nil
      }).mkString

    def uncapitalize: String = Utils.unCapitalize(value)
  }
}
