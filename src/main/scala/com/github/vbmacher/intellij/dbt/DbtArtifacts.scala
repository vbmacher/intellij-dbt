package com.github.vbmacher.intellij.dbt

import com.intellij.json.psi._
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiFile, PsiManager}
import scala.jdk.CollectionConverters._

/** Only user-selected local artifacts are read; editor source remains authoritative. */
private[dbt] object DbtArtifacts {
  private def property(obj: JsonObject, name: String): Option[JsonValue] = Option(obj.findProperty(name)).flatMap(p => Option(p.getValue))
  private def text(obj: JsonObject, name: String): Option[String] = property(obj, name).collect {
    case s: JsonStringLiteral => s.getValue
    case n: JsonNumberLiteral => n.getText
  }
  private def objects(obj: JsonObject, name: String): Vector[JsonObject] = property(obj, name).collect {
    case xs: JsonObject => xs.getPropertyList.asScala.toVector.flatMap(p => Option(p.getValue).collect { case x: JsonObject => x })
    case xs: JsonArray => xs.getValueList.asScala.toVector.collect { case x: JsonObject => x }
  }.getOrElse(Vector.empty)
  private def strings(obj: JsonObject, name: String): Vector[String] = property(obj, name).collect {
    case xs: JsonArray => xs.getValueList.asScala.toVector.collect { case s: JsonStringLiteral => s.getValue }
  }.getOrElse(Vector.empty)
  def uniqueId(symbol: DbtSymbol): String = symbol.attributes.getOrElse("unique_id", {
    val resource = if (symbol.kind == "source_table") "source" else symbol.kind
    val name = if (symbol.kind == "source_table") symbol.owner + "." + symbol.name else symbol.name
    s"$resource.${symbol.namespace}.$name${symbol.version.map(".v" + _).getOrElse("")}"
  })

  def symbols(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
    Vector("manifest", "state-manifest", "catalog").flatMap { setting => root.setting(setting).toVector.flatMap { path =>
      Option(root.directory.findFileByRelativePath(path)).flatMap(f => Option(PsiManager.getInstance(workspace.project).findFile(f))).toVector.flatMap { file =>
        file match {
          case json: JsonFile => Option(json.getTopLevelValue).collect { case top: JsonObject => top }.toVector.flatMap { top =>
            val metadata = property(top, "metadata").collect { case m: JsonObject => m }
            val schema = metadata.flatMap(text(_, "dbt_schema_version"))
            val valid = schema.exists(s => if (setting == "catalog") s == "https://schemas.getdbt.com/dbt/catalog/v1.json"
              else s.matches("https://schemas\\.getdbt\\.com/dbt/manifest/v(9|10|11|12)\\.json"))
            if (!valid) Vector.empty else {
              val entries = Vector("nodes", "sources", "macros", "docs", "metrics", "exposures", "groups", "semantic_models", "saved_queries", "functions", "checks")
                .flatMap(objects(top, _))
              entries.flatMap { entry =>
                ProgressManager.checkCanceled()
                val id = text(entry, "unique_id").getOrElse("")
                val kind = text(entry, "resource_type").orElse(id.split("\\.").headOption)
                val catalogSource = workspace.symbols.find(s => uniqueId(s) == id)
                val namespace = text(entry, "package_name").orElse(catalogSource.map(_.namespace)).getOrElse(root.name)
                val name = text(entry, "name").orElse(catalogSource.map(s => if (s.kind == "source_table") s.owner + "." + s.name else s.name))
                val original = text(entry, "original_file_path")
                val sourceExists = original.exists(p => root.directory.findFileByRelativePath(p) != null)
                val existing = workspace.symbols.exists(s => s.namespace == namespace && name.contains(s.name) && kind.contains(s.kind))
                val enabled = property(entry, "config").collect { case c: JsonObject => c }.flatMap(property(_, "enabled")).forall(_.getText != "false")
                val generated = metadata.flatMap(text(_, "generated_at")).flatMap(s => scala.util.Try(java.time.Instant.parse(s).toEpochMilli).toOption)
                val stale = generated.isEmpty || workspace.files.exists(f => workspace.root(f).contains(root) && root.resource(f) &&
                  (generated.exists(f.getVirtualFile.getTimeStamp > _) || Option(com.intellij.psi.PsiDocumentManager.getInstance(workspace.project).getDocument(f))
                    .exists(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved)))
                val dependencies = property(entry, "depends_on").collect { case d: JsonObject => strings(d, "nodes") ++ strings(d, "macros") }.getOrElse(Vector.empty)
                val attrs = Map("unique_id" -> id, "artifact" -> setting, "stale" -> stale.toString, "dependencies" -> dependencies.mkString(",")) ++
                  text(entry, "access").map("access" -> _) ++ text(entry, "latest_version").map("latest_version" -> _)
                val declaration = for (k <- kind.toVector; n <- name.toVector if enabled && !existing && !sourceExists && setting != "catalog") yield {
                  val range = property(entry, "name").map(_.getTextRange).getOrElse(entry.getTextRange)
                  DbtSymbol(k, n, namespace, file, range, version = text(entry, "version"), attributes = attrs, metadata = true)
                }
                val columns = if (setting == "catalog" && !stale) objects(entry, "columns").flatMap { col =>
                  for (n <- text(col, "name").toVector; owner <- name.toVector if !workspace.symbols.exists(s => s.kind == "column" && s.namespace == namespace && s.owner == owner && s.name == n)) yield DbtSymbol("column", n, namespace, file,
                    property(col, "name").map(_.getTextRange).getOrElse(col.getTextRange), owner, version = catalogSource.flatMap(_.version), attributes = attrs, metadata = true)
                } else Vector.empty
                declaration ++ columns
              }
            }
          }
          case _ => Vector.empty
        }
      }
    }}
  }
}
