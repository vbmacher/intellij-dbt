package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.lineage.{DbtDependency, DbtDependencySource}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonParser
import com.intellij.json.psi._
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.{PsiSearchScopeUtil, SearchScope}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiTreeUtil}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Rules shared by the artifact sources: only user-selected local artifacts are read, and editor sources remain authoritative. */
private[metadata] object DbtArtifacts {
  /** Whether artifact content generated at the given instant predates the project sources. */
  def stale(w: DbtWorkspace, root: DbtRoot, generatedAt: Option[String]): Boolean =
    w.outdated(root, generatedAt.flatMap(s => Try(Instant.parse(s).toEpochMilli).toOption))

  /** A declaration of a project whose namespace is visible from the root. */
  def visible(w: DbtWorkspace, root: DbtRoot, s: DbtSymbol): Boolean = w.owner(s).exists(r => w.namespaceVisible(root, r, qualified = true))

  /** The artifact entry describes a file of the root itself, so its declaration comes from source. */
  def authored(root: DbtRoot, namespace: String, path: Option[String]): Boolean =
    namespace == root.name && path.exists(p => root.directory.findFileByRelativePath(p) != null)

  /** A declaration read from an artifact, preceded by the declaration of its source when it is a source table. */
  def withSource(symbol: DbtSymbol, source: Option[String], sourceRange: => TextRange): Vector[DbtSymbol] =
    (if (symbol.kind == "source_table") source.toVector.map(n => DbtSymbol("source", n, symbol.namespace, symbol.file, sourceRange,
      attributes = symbol.attributes - "unique_id", metadata = true)) else Vector.empty) :+ symbol

  /** Declarations with an artifact identity, as visible from the root. */
  def identified(w: DbtWorkspace, root: DbtRoot, id: String): Vector[DbtSymbol] = w.symbols.filter(s => s.uniqueId == id && s.kind != "column" &&
    w.declarations(Set(s.kind), s.name, root, Some(s.namespace)).contains(s))
}

/** Declarations and dependencies of the `manifest`, `state-manifest` and `catalog` artifacts selected in the settings. */
private[metadata] final class DbtArtifactSource extends DbtDeclarationSource with DbtDependencySource {
  import DbtArtifactSource._
  import DbtArtifacts._

  override def inputs(workspace: DbtWorkspace): Vector[VirtualFile] = workspace.roots.flatMap(r =>
    Vector("manifest", "state-manifest", "catalog").flatMap(r.setting).flatMap(r.psiFile).map(_.getVirtualFile))

  override def artifactStatus(root: DbtRoot): Vector[String] = Vector("manifest", "catalog").map { kind =>
    val file = root.setting(kind).flatMap(root.psiFile)
    if (file.isEmpty) s"$kind unavailable. Generate Docs or check the artifact directory."
    else top(root, kind).fold(s"$kind malformed or unsupported schema. Regenerate Docs or select a supported artifact.") { obj =>
      val metadata = child(obj, "metadata")
      s"$kind loaded: ${file.get.getVirtualFile.getPath}; generated: ${metadata.flatMap(text(_, "generated_at")).getOrElse("unknown")}" +
        s"; dbt: ${metadata.flatMap(text(_, "dbt_version")).getOrElse("unknown")}; adapter: ${metadata.flatMap(text(_, "adapter_type")).getOrElse("unknown")}"
    }
  }

  override def canReportMissing(workspace: DbtWorkspace, root: DbtRoot): Boolean = {
    val explicit = root.settings.flatMap(DbtYaml.string(_, "manifest")).nonEmpty
    (!explicit && root.setting("manifest").flatMap(root.psiFile).isEmpty) || top(root, "manifest").nonEmpty
  }

  override def documentation(w: DbtWorkspace, s: DbtSymbol): Option[DbtResourceData] = w.owner(s).map { owner =>
    val root = w.packageOwner(owner).getOrElse(owner)
    Vector("manifest", "catalog").foldLeft(DbtResourceData()) { (data, setting) =>
      val selected = artifact(root, setting)
      val entries = selected.toVector.flatMap { case (_, top, manifest) => resources(top, manifest, "nodes", "sources", "macros", "exposures") }
      entries.find(e => text(e, "unique_id").contains(s.uniqueId)).fold(data) { entry =>
        val catalogMetadata = child(entry, "metadata")
        val attrs = Vector("database", "schema", "alias", "identifier", "relation_name", "original_file_path", "patch_path", "package_name", "version")
          .flatMap(k => text(entry, k).orElse(catalogMetadata.flatMap(text(_, k))).map(k -> _)).toMap ++
          child(entry, "config").toVector.flatMap(_.getPropertyList.asScala).flatMap(p => Option(p.getValue)
            .filterNot(_.isInstanceOf[JsonNullLiteral]).map(v => p.getName -> (v match {
              case str: JsonStringLiteral => str.getValue
              case _ => v.getText
            }))) ++ property(entry, "fqn").map(v => "fqn" -> v.getText) ++
          selected.flatMap(a => child(a._2, "metadata")).toVector.flatMap(m =>
            Vector("generated_at", "adapter_type", "dbt_version").flatMap(k => text(m, k).map(k -> _)))
        val deps = child(entry, "depends_on").map(strings(_, "nodes")).getOrElse(Vector.empty)
        val tests = selected.toVector.flatMap { case (_, top, manifest) => resources(top, manifest, "nodes") }
          .filter(e => text(e, "resource_type").contains("test") && child(e, "depends_on").exists(strings(_, "nodes").contains(s.uniqueId)))
          .map { t =>
            val generic = child(t, "test_metadata")
            val name = generic.flatMap(text(_, "name")).map(n => generic.flatMap(text(_, "namespace")).filter(_.nonEmpty).map(_ + ".").getOrElse("") + n)
              .orElse(text(t, "name")).getOrElse("test")
            DbtTestData(name, text(t, "column_name").getOrElse(""))
          }
        data.enrich(DbtResourceData(text(entry, "description").orElse(catalogMetadata.flatMap(text(_, "comment"))).getOrElse(""),
          objects(entry, "columns").flatMap { c =>
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

  override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
    Vector("manifest", "state-manifest", "catalog").flatMap { setting =>
      artifact(root, setting).toVector.flatMap { case (file, top, manifest) =>
        val outdated = stale(workspace, root, child(top, "metadata").flatMap(text(_, "generated_at")))
        val entries = resources(top, manifest, "nodes", "sources", "macros", "docs", "metrics", "exposures", "groups", "semantic_models",
          "saved_queries", "functions", "checks")
        entries.flatMap { entry =>
          ProgressManager.checkCanceled()
          val id = text(entry, "unique_id").getOrElse("")
          val rawKind = text(entry, "resource_type").orElse(id.split("\\.").headOption)
          val kind = rawKind.map(k => if (k == "source") "source_table" else k)
          val sourceName = text(entry, "source_name").orElse(if (rawKind.contains("source")) id.split("\\.").lift(2) else None)
          val catalogSource = workspace.symbols.find(s => visible(workspace, root, s) && s.uniqueId == id)
          val namespace = text(entry, "package_name").orElse(catalogSource.map(_.namespace)).getOrElse(root.name)
          val name = text(entry, "name").orElse(catalogSource.map(_.relationName))
          val version = text(entry, "version")
          val existing = workspace.symbols.exists(s => visible(workspace, root, s) && s.namespace == namespace &&
            name.contains(s.name) && kind.contains(s.kind) && s.version == version && (kind != Some("source_table") || sourceName.contains(s.owner)))
          val enabled = child(entry, "config").flatMap(property(_, "enabled")).forall(_.getText != "false")
          val dependencies = child(entry, "depends_on").map(d => strings(d, "nodes") ++ strings(d, "macros")).getOrElse(Vector.empty)
          val attrs = Map("unique_id" -> id, "artifact" -> setting, "resource-root" -> root.directory.getPath,
            "stale" -> outdated.toString, "dependencies" -> dependencies.mkString(",")) ++
            text(entry, "access").map("access" -> _) ++ text(entry, "latest_version").map("latest_version" -> _)
          val declaration = for (k <- kind.toVector; n <- name.toVector
                                 if enabled && !existing && !authored(root, namespace, text(entry, "original_file_path")) && setting != "catalog")
          yield DbtSymbol(k, n, namespace, file, range(entry, "name"), owner = if (k == "source_table") sourceName.getOrElse("") else "",
            version = version, attributes = attrs, metadata = true)
          val columns = if (setting == "catalog" && !outdated) objects(entry, "columns").flatMap { col =>
            for (n <- text(col, "name").toVector; owner <- name.toVector if !workspace.symbols.exists(s => s.kind == "column" &&
              workspace.owner(s) == catalogSource.flatMap(workspace.owner).orElse(Some(root)) &&
              s.namespace == namespace && s.owner == owner && s.name == n)) yield DbtSymbol("column", n, namespace, file,
              range(col, "name"), owner, version = catalogSource.flatMap(_.version),
              attributes = attrs ++ catalogSource.flatMap(workspace.owner).map(r => "resource-root" -> r.directory.getPath), metadata = true)
          } else Vector.empty
          declaration.flatMap(withSource(_, sourceName, range(entry, "source_name"))) ++ columns
        }
      }
    }
  }

  /** Dependencies recorded by artifact nodes, resolved to the declarations visible from their project. */
  override def dependencies(w: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] =
    w.symbols.filter(s => (s.metadata || DbtResourceData.GraphKinds(s.kind)) && !s.isProperty && scope.forall(PsiSearchScopeUtil.isInScope(_, s.file)))
      .flatMap { from =>
        val recorded = from.attributes.get("dependencies").toVector.flatMap(_.split(","))
        val ids = if (from.metadata) recorded else DbtResourceData.get(w, from).dependencies
        ids.filter(_.nonEmpty).flatMap { id =>
          val targets = w.owner(from).toVector.flatMap(identified(w, _, id))
          val selected = targets.filterNot(s => s.isProperty && targets.exists(_.isCode))
          val reference = new DbtNavigationReference(from.file, from.range, selected.map(_.element), "artifact dependency")
          selected.map(to => DbtDependency(from, to, reference))
        }
      }
}

private object DbtArtifactSource {
  private def property(obj: JsonObject, name: String): Option[JsonValue] = Option(obj.findProperty(name)).flatMap(p => Option(p.getValue))

  private def child(obj: JsonObject, name: String): Option[JsonObject] = property(obj, name).collect { case o: JsonObject => o }

  private def range(obj: JsonObject, name: String): TextRange = property(obj, name).map(_.getTextRange).getOrElse(obj.getTextRange)

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
  private def artifact(root: DbtRoot, setting: String): Option[(JsonFile, JsonObject, Option[DbtManifest])] =
    root.setting(setting).flatMap(root.psiFile).collect { case json: JsonFile => json }.filterNot(PsiTreeUtil.hasErrorElements)
      .flatMap(f => Option(f.getTopLevelValue).collect { case o: JsonObject => f -> o }).flatMap { case (file, top) =>
        if (setting == "catalog") {
          val schema = child(top, "metadata").flatMap(text(_, "dbt_schema_version"))
          Option.when(schema.contains("https://schemas.getdbt.com/dbt/catalog/v1.json"))((file, top, None))
        } else DbtManifestReader.get(root.config.getProject).read(root, file.getVirtualFile.getPath, DbtManifestPolicy.Navigation)
          .toOption.map(manifest => (file, top, Some(manifest)))
      }

  /** Bind shared manifest records back to PSI so navigation retains exact authored ranges. */
  private def resources(top: JsonObject, manifest: Option[DbtManifest], sections: String*): Vector[JsonObject] = manifest match {
    case Some(snapshot) => snapshot.in(sections: _*).flatMap(r => child(top, r.section).flatMap(child(_, r.id)))
    case None => sections.toVector.flatMap(objects(top, _))
  }

  private def top(root: DbtRoot, setting: String): Option[JsonObject] = artifact(root, setting).map(_._2)
}

/** Reads current editor content and bounded local files without depending on workspace discovery. */
private[metadata] final class DbtJsonManifestReader extends DbtManifestReader {
  private val sections = Vector("nodes", "sources", "macros", "docs", "metrics", "exposures", "groups", "semantic_models", "saved_queries",
    "functions", "checks", "unit_tests", "selectors")
  private val baselineSections = Set("nodes", "sources", "exposures", "unit_tests", "metrics", "semantic_models", "saved_queries")
  private val schemas = (9 to 12).map(v => s"https://schemas.getdbt.com/dbt/manifest/v$v.json" -> v).toMap
  private val cache: Map[DbtManifestPolicy, Key[CachedValue[Either[String, DbtManifest]]]] =
    Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery, DbtManifestPolicy.Baseline)
      .map(p => p -> Key.create[CachedValue[Either[String, DbtManifest]]](s"dbt.manifest.$p")).toMap

  override def parse(text: String, policy: DbtManifestPolicy): Either[String, DbtManifest] = {
    val label = if (policy == DbtManifestPolicy.Baseline) "Baseline manifest" else "Manifest"
    if (text.length > policy.maxBytes) Left(s"$label exceeds the ${policy.maxBytes / 1000000} MB limit.")
    else Try(JsonParser.parseString(text)).toEither.left.map(_ => s"$label is malformed.").flatMap { value =>
      if (!value.isJsonObject) Left(s"$label must be an object.")
      else {
        val top = value.getAsJsonObject
        val version = DbtJson.child(top, "metadata").flatMap(DbtJson.text(_, "dbt_schema_version")).flatMap(schemas.get)
        val records = sections.flatMap(section => DbtJson.child(top, section).toVector.flatMap(_.entrySet.asScala).collect {
          case entry if entry.getValue.isJsonObject => DbtManifestResource(section, entry.getKey, entry.getValue.getAsJsonObject)
        })
        val baseline = policy == DbtManifestPolicy.Baseline
        val checked = records.filter(r => baselineSections(r.section))
        val shapesValid = baselineSections.forall(section => !top.has(section) || DbtJson.child(top, section).nonEmpty)
        val entriesValid = baselineSections.forall(section =>
          DbtJson.child(top, section).forall(_.entrySet.asScala.forall(_.getValue.isJsonObject)))
        val valid = entriesValid && checked.map(_.id).distinct.size == checked.size && checked.forall { r =>
          val node = r.data
          DbtJson.text(node, "unique_id").forall(_ == r.id) &&
            Vector("config", "columns", "depends_on").forall(key => !node.has(key) || DbtJson.child(node, key).nonEmpty) &&
            DbtJson.child(node, "columns").forall(_.entrySet.asScala.forall(_.getValue.isJsonObject)) &&
            DbtJson.child(node, "depends_on").flatMap(d => Option(d.get("nodes"))).forall(v => v.isJsonArray &&
              v.getAsJsonArray.asScala.forall(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString))
        }
        if (version.isEmpty || baseline && DbtJson.child(top, "nodes").isEmpty)
          Left(s"$label schema is missing or unsupported; supported manifest schemas are v9-v12.")
        else if (baseline && !shapesValid) Left("Baseline manifest resource sections must be objects.")
        else if (baseline && checked.size > 50000) Left("Baseline manifest exceeds 50000 resource entries.")
        else if (baseline && !valid) Left("Baseline resource identities, columns, config or dependency shapes are malformed.")
        else Right(DbtManifest(version.get, DbtJson.child(top, "metadata").flatMap(DbtJson.text(_, "project_name")),
          if (baseline) checked else records))
      }
    }
  }

  override def read(root: DbtRoot, path: String, policy: DbtManifestPolicy): Either[String, DbtManifest] = {
    val psi = root.psiFile(path)
    def editor = psi.toRight("Cannot read manifest.").flatMap { file =>
      CachedValuesManager.getManager(file.getProject).getCachedValue(file, cache(policy), () =>
        CachedValueProvider.Result.create(parse(file.getText, policy), file), false)
    }
    def disk = Try {
      val file = Paths.get(root.directory.getPath).resolve(path).normalize()
      val stream = Files.newInputStream(file)
      try {
        val bytes = stream.readNBytes(if (policy.maxBytes == Int.MaxValue) Int.MaxValue else policy.maxBytes + 1)
        if (bytes.length > policy.maxBytes) Left(s"Manifest exceeds the ${policy.maxBytes / 1000000} MB limit.")
        else parse(new String(bytes, StandardCharsets.UTF_8), policy)
      } finally stream.close()
    }.toEither.left.map(_ => "Cannot read manifest.").flatten
    val unsaved = psi.exists(f => Option(PsiDocumentManager.getInstance(f.getProject).getDocument(f))
      .exists(FileDocumentManager.getInstance().isDocumentUnsaved))
    val result = if (policy == DbtManifestPolicy.TestDiscovery && !unsaved) disk.orElse(editor)
      else if (psi.nonEmpty) editor else disk
    result.flatMap { manifest =>
      if (policy == DbtManifestPolicy.TestDiscovery && manifest.projectName.exists(p => p.nonEmpty && p != root.name))
        Left("Manifest belongs to another project.")
      else Right(manifest)
    }
  }
}
