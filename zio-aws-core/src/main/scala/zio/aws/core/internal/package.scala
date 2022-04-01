package zio.aws.core

import zio.prelude.data.Optional

package object internal {

  def optionalFromNullable[A](value: A): Optional[A] =
    if (value == null) Optional.Absent
    else Optional.Present(value)

}
