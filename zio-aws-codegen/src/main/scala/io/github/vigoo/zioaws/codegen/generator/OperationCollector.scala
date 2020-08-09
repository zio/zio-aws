package io.github.vigoo.zioaws.codegen.generator

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.Operation

object OperationCollector {
  private def isExcluded(customizationConfig: CustomizationConfig, opName: String): Boolean =
    Option(customizationConfig.getOperationModifiers)
      .flatMap(_.asScala.get(opName))
      .exists(_.isExclude)

  def getFilteredOperations(models: C2jModels): Map[String, Operation] =
    models.serviceModel().getOperations.asScala
      .toMap
      .filter { case (_, op) => !op.isDeprecated }
      .filter { case (opName, _) => !isExcluded(models.customizationConfig(), opName) }

}
