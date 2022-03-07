package zio.aws.codegen.loader

case class ModuleId(name: String, subModule: Option[String]) {
  val moduleName: String = subModule.getOrElse(name)

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
  def parse(value: String): Either[String, ModuleId] =
    value.split(':').toList match {
      case List(single)    => Right(ModuleId(single, None))
      case List(main, sub) => Right(ModuleId(main, Some(sub)))
      case _ =>
        Left(
          s"Failed to parse $value as ModuleId, use 'svc' or 'svc:subsvc' syntax."
        )
    }
}
