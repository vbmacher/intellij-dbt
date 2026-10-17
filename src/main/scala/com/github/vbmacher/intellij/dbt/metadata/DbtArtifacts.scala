package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.lineage.{DbtDependency, DbtDependencySource}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.{DbtColumnData, DbtDeclarationSource, DbtResourceData, DbtRoot, DbtSymbol, DbtTestData, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.json.psi._
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.search.{PsiSearchScopeUtil, SearchScope}
import com.intellij.psi.util.PsiTreeUtil

import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Declarations and dependencies of the `manifest`, `state-manifest` and `catalog` artifacts selected in the settings. */
private[metadata] final class DbtArtifactSource extends DbtDeclarationSource with DbtDependencySource {
  override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = DbtArtifacts.symbols(workspace)

  override def dependencies(workspace: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] =
    DbtArtifacts.dependencies(workspace, scope)

  override def inputs(workspace: DbtWorkspace): Vector[com.intellij.openapi.vfs.VirtualFile] = workspace.roots.flatMap(r =>
    Vector("manifest", "state-manifest", "catalog").flatMap(r.setting).flatMap(r.psiFile).map(_.getVirtualFile))

  override def documentation(workspace: DbtWorkspace, symbol: DbtSymbol): Option[DbtResourceData] =
    DbtArtifacts.documentation(workspace, symbol)

  override def canReportMissing(workspace: DbtWorkspace, root: DbtRoot): Boolean = DbtArtifacts.canReportMissing(root)

  override def artifactStatus(root: DbtRoot): Vector[String] = DbtArtifacts.status(root)
}

/** Only user-selected local artifacts are read; editor source remains authoritative. */
private object DbtArtifacts {
  def status(root: DbtRoot): Vector[String] = Vector("manifest", "catalog").map { kind =>
    val file = root.setting(kind).flatMap(root.psiFile)
    if (file.isEmpty) s"$kind unavailable. Generate Docs or check the artifact directory."
    else top(root, kind).fold(s"$kind malformed or unsupported schema. Regenerate Docs or select a supported artifact.") { obj =>
      val metadata = property(obj, "metadata").collect { case m: JsonObject => m }
      s"$kind loaded: ${file.get.getVirtualFile.getPath}; generated: ${metadata.flatMap(text(_, "generated_at")).getOrElse("unknown")}" +
        s"; dbt: ${metadata.flatMap(text(_, "dbt_version")).getOrElse("unknown")}; adapter: ${metadata.flatMap(text(_, "adapter_type")).getOrElse("unknown")}"
    }
  }
  def canReportMissing(root: DbtRoot): Boolean = {
    val explicit = root.settings.flatMap(DbtYaml.string(_, "manifest")).nonEmpty
    (!explicit && root.setting("manifest").flatMap(root.psiFile).isEmpty) || top(root, "manifest").nonEmpty
  }
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

  /** A well-formed artifact of a supported schema, with its top-level object. */
  private def artifact(root: DbtRoot, setting: String): Option[(JsonFile, JsonObject)] = root.setting(setting).flatMap(root.psiFile).collect {
    case json: JsonFile => json
  }.filterNot(PsiTreeUtil.hasErrorElements).flatMap(f => Option(f.getTopLevelValue).collect { case o: JsonObject => f -> o }).filter { case (_, o) =>
    val schema = property(o, "metadata").collect { case m: JsonObject => m }.flatMap(text(_, "dbt_schema_version"))
    schema.exists(s => if (setting == "catalog") s == "https://schemas.getdbt.com/dbt/catalog/v1.json"
      else s.matches("https://schemas\\.getdbt\\.com/dbt/manifest/v(9|10|11|12)\\.json"))
  }

  private def top(root: DbtRoot, setting: String): Option[JsonObject] = artifact(root, setting).map(_._2)

  def documentation(w: DbtWorkspace, s: DbtSymbol): Option[DbtResourceData] = w.owner(s).map { owner =>
    val root = w.packageOwner(owner).getOrElse(owner)
    Vector("manifest", "catalog").foldLeft(DbtResourceData()) { (data, setting) =>
      val artifact = top(root, setting)
      val entries = artifact.toVector.flatMap(o => Vector("nodes", "sources", "macros", "exposures").flatMap(objects(o, _)))
      entries.find(e => text(e, "unique_id").contains(s.uniqueId)).fold(data) { entry =>
        val config = property(entry, "config").collect { case c: JsonObject => c }
        val catalogMetadata = property(entry, "metadata").collect { case c: JsonObject => c }
        val attrs = Vector("database", "schema", "alias", "identifier", "relation_name", "original_file_path", "patch_path", "package_name", "version")
          .flatMap(k => text(entry, k).orElse(catalogMetadata.flatMap(text(_, k))).map(k -> _)).toMap ++
          config.toVector.flatMap(_.getPropertyList.asScala).flatMap(p => Option(p.getValue).map(v => p.getName -> (v match {
            case str: JsonStringLiteral => str.getValue
            case _ => v.getText
          }))) ++ property(entry, "fqn").map(v => "fqn" -> v.getText) ++
          artifact.flatMap(property(_, "metadata")).collect { case m: JsonObject => m }.toVector.flatMap(m =>
            Vector("generated_at", "adapter_type", "dbt_version").flatMap(k => text(m, k).map(k -> _)))
        val deps = property(entry, "depends_on").collect { case d: JsonObject => strings(d, "nodes") }.getOrElse(Vector.empty)
        val tests = artifact.toVector.flatMap(objects(_, "nodes")).filter(e => text(e, "resource_type").contains("test") &&
          property(e, "depends_on").collect { case d: JsonObject => strings(d, "nodes").contains(s.uniqueId) }.contains(true))
          .map { t =>
            val generic = property(t, "test_metadata").collect { case m: JsonObject => m }
            val name = generic.flatMap(text(_, "name")).map(n => generic.flatMap(text(_, "namespace")).filter(_.nonEmpty).map(_ + ".").getOrElse("") + n)
              .orElse(text(t, "name")).getOrElse("test")
            DbtTestData(name, text(t, "column_name").getOrElse(""))
          }
        data.enrich(DbtResourceData(text(entry, "description").orElse(catalogMetadata.flatMap(text(_, "comment"))).getOrElse(""), objects(entry, "columns").flatMap { c =>
          text(c, "name").map(n => DbtColumnData(n, text(c, "data_type").orElse(text(c, "type")).getOrElse(""),
            text(c, "description").orElse(text(c, "comment")).getOrElse(""), objects(c, "constraints").flatMap(text(_, "type"))))
        }, tests, strings(entry, "tags"), attrs ++ Map("artifact-project" -> root.directory.getPath,
          "artifact-target" -> root.runtimeSetting("target").getOrElse("")),
          text(entry, "raw_code").orElse(text(entry, "raw_sql")), text(entry, "compiled_code").orElse(text(entry, "compiled_sql")),
          objects(entry, "arguments").flatMap(a => text(a, "name").map(n => DbtColumnData(n,
            text(a, "type").getOrElse(""), text(a, "description").getOrElse("")))), deps))
      }
    }
  }

  def symbols(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
    Vector("manifest", "state-manifest", "catalog").flatMap { setting =>
      artifact(root, setting).toVector.flatMap { case (file, top) =>
        val metadata = property(top, "metadata").collect { case m: JsonObject => m }
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
  }

  /** Dependencies recorded by artifact nodes, resolved to the declarations visible from their project. */
  def dependencies(w: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] =
    w.symbols.filter(s => (s.metadata || DbtResourceData.GraphKinds(s.kind)) && !s.isProperty && scope.forall(PsiSearchScopeUtil.isInScope(_, s.file))).flatMap { from =>
      val recorded = from.attributes.get("dependencies").toVector.flatMap(_.split(","))
      val ids = if (from.metadata) recorded else DbtResourceData.get(w, from).dependencies
      ids.filter(_.nonEmpty).flatMap { id =>
        val targets = w.owner(from).toVector.flatMap(r => w.symbols.filter(s => s.uniqueId == id && s.kind != "column" &&
          w.declarations(Set(s.kind), s.name, r, Some(s.namespace)).contains(s)))
        val selected = targets.filterNot(s => s.isProperty && targets.exists(_.isCode))
        val reference = new DbtNavigationReference(from.file, from.range, selected.map(_.element), "artifact dependency")
        selected.map(to => DbtDependency(from, to, reference))
      }
    }
}
