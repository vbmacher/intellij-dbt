package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.{JsonSchemaFileProvider, JsonSchemaProviderFactory, SchemaType}
import scala.jdk.CollectionConverters._

/** Offline schemas use IntelliJ's YAML completion, documentation and inspection infrastructure. */
final class DbtYamlSchemaProvider extends JsonSchemaProviderFactory {
  override def getProviders(project: Project): java.util.List[JsonSchemaFileProvider] =
    (for (kind <- Vector("project", "packages", "selectors", "properties"); version <- Vector("precontracts", "legacy", "semantic", "queries", "units", "current", "functions", "v2"))
      yield new JsonSchemaFileProvider {
        override def getName: String = s"dbt $kind ($version)"
        override def getSchemaType: SchemaType = SchemaType.embeddedSchema
        override def getSchemaFile: VirtualFile = JsonSchemaProviderFactory.getResourceFile(classOf[DbtYamlSchemaProvider], if (kind == "properties") s"/schemas/dbt-$kind-$version.json" else s"/schemas/dbt-$kind.json")
        override def isAvailable(file: VirtualFile): Boolean = !project.isDisposed && file.isValid &&
          Set("yml", "yaml")(Option(file.getExtension).getOrElse("")) &&
          DbtWorkspace.rootForFile(project, file).filterNot(_.excluded(file)).exists { root =>
            val selectedVersion = if (root.engine == "v2") "v2" else if (root.supports(11)) "functions" else if (root.supports(10)) "current"
              else if (root.supports(8)) "units" else if (root.supports(7)) "queries" else if (root.supports(6)) "semantic"
              else if (root.supports(5)) "legacy" else "precontracts"
            val selectedKind = file.getName match {
              case "dbt_project.yml" => Some("project")
              case "packages.yml" | "dependencies.yml" => Some("packages")
              case "selectors.yml" => Some("selectors")
              case _ if root.resourceFolders(file).nonEmpty => DbtYaml.psiFile(project, file).flatMap(DbtYaml.mapping)
                .filter(m => m.getKeyValues.asScala.exists(k => Set("models", "sources", "seeds", "snapshots", "macros", "semantic_models",
                  "metrics", "unit_tests", "exposures", "saved_queries", "functions", "checks")(k.getKeyText))).map(_ => "properties")
              case _ => None
            }
            selectedKind.contains(kind) && selectedVersion == version
          }
      }).asJava
}
