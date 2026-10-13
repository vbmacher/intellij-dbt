package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.lineage.{DbtDependency, DbtDependencySource}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtSymbol, DbtWorkspace}
import com.intellij.json.psi._
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.search.{PsiSearchScopeUtil, SearchScope}

import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Declarations and dependencies of the `manifest`, `state-manifest` and `catalog` artifacts selected in the settings. */
private[metadata] final class DbtArtifactSource extends DbtDeclarationSource with DbtDependencySource {
  override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = DbtArtifacts.symbols(workspace)

  override def dependencies(workspace: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] =
    DbtArtifacts.dependencies(workspace, scope)
}

/** Only user-selected local artifacts are read; editor source remains authoritative. */
private object DbtArtifacts {
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

  def symbols(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
    Vector("manifest", "state-manifest", "catalog").flatMap { setting =>
      root.setting(setting).toVector.flatMap { path =>
        root.psiFile(path).toVector.flatMap {
          case file@(json: JsonFile) => Option(json.getTopLevelValue).collect { case top: JsonObject => top }.toVector.flatMap { top =>
            val metadata = property(top, "metadata").collect { case m: JsonObject => m }
            val schema = metadata.flatMap(text(_, "dbt_schema_version"))
            val valid = schema.exists(s => if (setting == "catalog") s == "https://schemas.getdbt.com/dbt/catalog/v1.json"
            else s.matches("https://schemas\\.getdbt\\.com/dbt/manifest/v(9|10|11|12)\\.json"))
            if (!valid) Vector.empty else {
              val generated = metadata.flatMap(text(_, "generated_at")).flatMap(s => Try(Instant.parse(s).toEpochMilli).toOption)
              val stale = workspace.outdated(root, generated)
              val entries = Vector("nodes", "sources", "macros", "docs", "metrics", "exposures", "groups", "semantic_models", "saved_queries", "functions", "checks")
                .flatMap(objects(top, _))
              entries.flatMap { entry =>
                ProgressManager.checkCanceled()
                val id = text(entry, "unique_id").getOrElse("")
                val rawKind = text(entry, "resource_type").orElse(id.split("\\.").headOption)
                val kind = rawKind.map(k => if (k == "source") "source_table" else k)
                val sourceName = text(entry, "source_name").orElse(if (rawKind.contains("source")) id.split("\\.").lift(2) else None)
                val catalogSource = workspace.symbols.find(s => workspace.owner(s).exists(r => workspace.namespaceVisible(root, r, qualified = true)) && s.uniqueId == id)
                val namespace = text(entry, "package_name").orElse(catalogSource.map(_.namespace)).getOrElse(root.name)
                val name = text(entry, "name").orElse(catalogSource.map(_.relationName))
                val original = text(entry, "original_file_path")
                val sourceExists = namespace == root.name && original.exists(p => root.directory.findFileByRelativePath(p) != null)
                val version = text(entry, "version")
                val existing = workspace.symbols.exists(s => workspace.owner(s).exists(r => workspace.namespaceVisible(root, r, qualified = true)) && s.namespace == namespace &&
                  name.contains(s.name) && kind.contains(s.kind) && s.version == version && (kind != Some("source_table") || sourceName.contains(s.owner)))
                val enabled = property(entry, "config").collect { case c: JsonObject => c }.flatMap(property(_, "enabled")).forall(_.getText != "false")
                val dependencies = property(entry, "depends_on").collect { case d: JsonObject => strings(d, "nodes") ++ strings(d, "macros") }.getOrElse(Vector.empty)
                val attrs = Map("unique_id" -> id, "artifact" -> setting, "resource-root" -> root.directory.getPath,
                  "stale" -> stale.toString, "dependencies" -> dependencies.mkString(",")) ++
                  text(entry, "access").map("access" -> _) ++ text(entry, "latest_version").map("latest_version" -> _)
                val declaration = for (k <- kind.toVector; n <- name.toVector if enabled && !existing && !sourceExists && setting != "catalog") yield {
                  val range = property(entry, "name").map(_.getTextRange).getOrElse(entry.getTextRange)
                  DbtSymbol(k, n, namespace, file, range, owner = if (k == "source_table") sourceName.getOrElse("") else "",
                    version = version, attributes = attrs, metadata = true)
                }
                val sourceDeclarations = declaration.filter(_.kind == "source_table").flatMap(s => sourceName.map(n =>
                  DbtSymbol("source", n, namespace, file, property(entry, "source_name").map(_.getTextRange).getOrElse(entry.getTextRange),
                    attributes = attrs - "unique_id", metadata = true)))
                val columns = if (setting == "catalog" && !stale) objects(entry, "columns").flatMap { col =>
                  for (n <- text(col, "name").toVector; owner <- name.toVector if !workspace.symbols.exists(s => s.kind == "column" &&
                    workspace.owner(s) == catalogSource.flatMap(workspace.owner).orElse(Some(root)) &&
                    s.namespace == namespace && s.owner == owner && s.name == n)) yield DbtSymbol("column", n, namespace, file,
                    property(col, "name").map(_.getTextRange).getOrElse(col.getTextRange), owner, version = catalogSource.flatMap(_.version),
                    attributes = attrs ++ catalogSource.flatMap(workspace.owner).map(r => "resource-root" -> r.directory.getPath), metadata = true)
                } else Vector.empty
                sourceDeclarations ++ declaration ++ columns
              }
            }
          }
          case _ => Vector.empty
        }
      }
    }
  }

  /** Dependencies recorded by artifact nodes, resolved to the declarations visible from their project. */
  def dependencies(w: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] =
    w.symbols.filter(s => s.metadata && scope.forall(PsiSearchScopeUtil.isInScope(_, s.file))).flatMap { from =>
      from.attributes.get("dependencies").toVector.flatMap(_.split(",")).filter(_.nonEmpty).flatMap { id =>
        val targets = w.owner(from).toVector.flatMap(r => w.symbols.filter(s => s.uniqueId == id && s.kind != "column" &&
          w.declarations(Set(s.kind), s.name, r, Some(s.namespace)).contains(s)))
        val selected = targets.filterNot(s => s.isProperty && targets.exists(_.isCode))
        val reference = new DbtNavigationReference(from.file, from.range, selected.map(_.element), "artifact dependency")
        selected.map(to => DbtDependency(from, to, reference))
      }
    }
}
