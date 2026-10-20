package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Reads current editor content and bounded local files without depending on workspace discovery. */
private[metadata] final class DbtJsonManifestReader extends DbtManifestReader {
  private val sections = Vector("nodes", "sources", "macros", "docs", "metrics", "exposures", "groups", "semantic_models", "saved_queries", "functions", "checks", "unit_tests")
  private val baselineSections = Set("nodes", "sources", "exposures", "unit_tests", "metrics", "semantic_models", "saved_queries")
  private val schemas = (9 to 12).map(v => s"https://schemas.getdbt.com/dbt/manifest/v$v.json" -> v).toMap
  private val cache: Map[DbtManifestPolicy, Key[CachedValue[Either[String, DbtManifest]]]] = Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery, DbtManifestPolicy.Baseline)
    .map(p => p -> Key.create[CachedValue[Either[String, DbtManifest]]](s"dbt.manifest.$p")).toMap

  private def obj(json: JsonObject, name: String): Option[JsonObject] = Option(json.get(name)).filter(_.isJsonObject).map(_.getAsJsonObject)
  private def string(json: JsonObject, name: String): Option[String] = Option(json.get(name))
    .filter(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isString).map(_.getAsString)

  override def parse(text: String, policy: DbtManifestPolicy): Either[String, DbtManifest] = {
    val label = if (policy == DbtManifestPolicy.Baseline) "Baseline manifest" else "Manifest"
    if (text.length > policy.maxBytes) Left(s"$label exceeds the ${policy.maxBytes / 1000000} MB limit.")
    else Try(JsonParser.parseString(text)).toEither.left.map(_ => s"$label is malformed.").flatMap { value =>
      if (!value.isJsonObject) Left(s"$label must be an object.")
      else {
        val top = value.getAsJsonObject
        val version = obj(top, "metadata").flatMap(string(_, "dbt_schema_version")).flatMap(schemas.get)
        val records = sections.flatMap(section => obj(top, section).toVector.flatMap(_.entrySet.asScala).collect {
          case entry if entry.getValue.isJsonObject => DbtManifestResource(section, entry.getKey, entry.getValue.getAsJsonObject)
        })
        val baseline = policy == DbtManifestPolicy.Baseline
        val checked = records.filter(r => baselineSections(r.section))
        val shapesValid = baselineSections.forall(section => !top.has(section) || obj(top, section).nonEmpty)
        val entriesValid = baselineSections.forall(section => obj(top, section).forall(_.entrySet.asScala.forall(_.getValue.isJsonObject)))
        val valid = entriesValid && checked.map(_.id).distinct.size == checked.size && checked.forall { r =>
          val node = r.data
          string(node, "unique_id").forall(_ == r.id) &&
            Vector("config", "columns", "depends_on").forall(key => !node.has(key) || obj(node, key).nonEmpty) &&
            obj(node, "columns").forall(_.entrySet.asScala.forall(_.getValue.isJsonObject)) &&
            obj(node, "depends_on").flatMap(d => Option(d.get("nodes"))).forall(v => v.isJsonArray &&
              v.getAsJsonArray.asScala.forall(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString))
        }
        if (version.isEmpty || baseline && obj(top, "nodes").isEmpty)
          Left(s"$label schema is missing or unsupported; supported manifest schemas are v9-v12.")
        else if (baseline && !shapesValid) Left("Baseline manifest resource sections must be objects.")
        else if (baseline && checked.size > 50000) Left("Baseline manifest exceeds 50000 resource entries.")
        else if (baseline && !valid) Left("Baseline resource identities, columns, config or dependency shapes are malformed.")
        else Right(DbtManifest(version.get, obj(top, "metadata").flatMap(string(_, "project_name")), if (baseline) checked else records))
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
      val file = java.nio.file.Paths.get(root.directory.getPath).resolve(path).normalize()
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
