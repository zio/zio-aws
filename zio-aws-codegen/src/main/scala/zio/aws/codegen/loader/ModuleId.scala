package zio.aws.codegen.loader

/** Identifies an AWS SDK service module: its `name`, and optionally a `subModule` of it. It prints as `name` or `name:subModule`, the form that `ModuleId.parse` reads. */
case class ModuleId(name: String, subModule: Option[String]) {
  /** The `subModule` when there is one, otherwise `name`. */
  val moduleName: String = subModule.getOrElse(name)

  /** The `subModule` with the leading `name` removed, or `None` when there is no `subModule` or nothing is left after removing it. */
  val subModuleName: Option[String] = subModule.flatMap { s =>
    val stripped = s.stripPrefix(name)
    if (stripped.isEmpty) None else Some(stripped)
  }

  override def toString: String =
    subModule match {
      case Some(value) => s"$name:$value"
      case None        => name
    }
}

object ModuleId {
  /** Parses `svc` or `svc:subsvc` into a `ModuleId`. Returns `Left` with a message when the text splits into more than two parts on `:`. */
  def parse(value: String): Either[String, ModuleId] =
    value.split(':').toList match {
      case List(single)    => Right(ModuleId(single, None))
      case List(main, sub) => Right(ModuleId(main, Some(sub)))
      case _               =>
        Left(
          s"Failed to parse $value as ModuleId, use 'svc' or 'svc:subsvc' syntax."
        )
    }
}
