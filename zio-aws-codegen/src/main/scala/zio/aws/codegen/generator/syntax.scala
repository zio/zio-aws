package zio.aws.codegen.generator

import software.amazon.awssdk.codegen.internal.Utils

/** String extension methods used by the generator. */
object syntax {

  /** Adds `toCamelCase` and `uncapitalize` to `String`. */
  implicit class StringOps(val value: String) extends AnyVal {

    /** Returns the string with its first character lower-cased and the rest
      * unchanged; an empty string stays empty.
      */
    def toCamelCase: String =
      (value.toList match {
        case ::(head, next) => head.toLower :: next
        case Nil            => Nil
      }).mkString

    /** Lower-cases the first character and the capitals that follow it, except
      * a capital that is followed by a lower-case letter (`URLPath` gives
      * `urlPath`), using the AWS SDK codegen's `Utils.unCapitalize`. Throws
      * `java.lang.IllegalArgumentException` for an empty string.
      */
    def uncapitalize: String = Utils.unCapitalize(value)
  }
}
