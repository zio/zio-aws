package zio.aws.core

import zio.prelude.data.Optional

package object internal {

  /** Returns `Optional.Absent` for `null`, and `Optional.Present(value)` otherwise. The generated wrappers use it to read fields of AWS SDK objects. */
  def optionalFromNullable[A](value: A): Optional[A] =
    if (value == null) Optional.Absent
    else Optional.Present(value)

}
