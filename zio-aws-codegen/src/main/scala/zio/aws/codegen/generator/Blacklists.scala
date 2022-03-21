package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType

trait Blacklists {
  protected def isBlacklistedNewtype(wrapperType: ScalaType): Boolean =
    wrapperType.name match {
      case "Bool"                          => true
      case "Boolean"                       => true
      case "BooleanObject"                 => true
      case "BooleanNullable"               => true
      case "NullableBoolean"               => true
      case "BooleanValue"                  => true
      case "OptionalBoolean"               => true
      case "BooleanOptional"               => true
      case "BoxedBool"                     => true
      case "BoxedBoolean"                  => true
      case "BoxBool"                       => true
      case "BoxBoolean"                    => true
      case "GenericBoolean"                => true
      case "GenericBool"                   => true
      case "DoubleObject"                  => true
      case "DoubleNullable"                => true
      case "NullableDouble"                => true
      case "DoubleValue"                   => true
      case "Double"                        => true
      case "OptionalDouble"                => true
      case "DoubleOptional"                => true
      case "BoxedDouble"                   => true
      case "BoxDouble"                     => true
      case "GenericDouble"                 => true
      case "Int"                           => true
      case "Integer"                       => true
      case "IntegerObject"                 => true
      case "IntegerValue"                  => true
      case "OptionalInteger"               => true
      case "IntegerOptional"               => true
      case "IntegerNullable"               => true
      case "NullableInteger"               => true
      case "BoxInt"                        => true
      case "BoxInteger"                    => true
      case "BoxedInt"                      => true
      case "BoxedInteger"                  => true
      case "GenericInt"                    => true
      case "GenericInteger"                => true
      case "Long"                          => true
      case "LongObject"                    => true
      case "LongValue"                     => true
      case "OptionalLong"                  => true
      case "LongOptional"                  => true
      case "LongNullable"                  => true
      case "NullableLong"                  => true
      case "BoxedLong"                     => true
      case "BoxLong"                       => true
      case "GenericLong"                   => true
      case "GenericString"                 => true
      case "StringType"                    => true
      case "WrapperBoolean"                => true
      case "WrapperDouble"                 => true
      case "WrapperInt"                    => true
      case "WrapperLong"                   => true
      case s: String if s.startsWith("__") => true
      case _                               => false
    }

}
