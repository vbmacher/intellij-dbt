package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.{DbtLineage, DbtSelection}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonParser}
import com.intellij.openapi.progress.ProgressManager
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Try

private[ide] final case class DbtStateChange(id: String, status: String, reason: String, relation: String)
private[ide] final case class DbtStateDependency(id: String, resolution: String, relation: String, evidence: String, reason: String)
private[ide] final case class DbtStatePreview(baseline: String, selected: Vector[DbtSymbol], dependencies: Vector[DbtStateDependency],
  problems: Vector[String])

/** Static source comparison is deliberately distinct from dbt's runtime state and warehouse existence checks. */
private[ide] object DbtState {
  private val Limit = 50000000
  private val Sections = Vector("nodes", "sources", "exposures", "unit_tests", "metrics", "semantic_models", "saved_queries")
  private val Schemas = (9 to 12).map(v => s"https://schemas.getdbt.com/dbt/manifest/v$v.json").toSet
  private val ConfigKeys = Set("materialized", "enabled", "alias", "database", "schema", "tags", "access", "group", "unique_key",
    "on_schema_change", "incremental_strategy", "partition_by", "cluster_by", "contract", "persist_docs", "grants", "quoting", "full_refresh", "meta", "pre_hook", "post_hook")
  private val ColumnKeys = Set("description", "data_type", "quote", "constraints", "meta", "tags")
  private def obj(json: JsonObject, key: String): Option[JsonObject] = Option(json.get(key)).filter(_.isJsonObject).map(_.getAsJsonObject)
  private def str(json: JsonObject, key: String): Option[String] = Option(json.get(key)).filter(e =>
    e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString)
  private def entries(json: JsonObject): Map[String, JsonObject] = Sections.flatMap(section => obj(json, section).toVector
    .flatMap(_.entrySet().asScala).map(e => e.getKey -> e.getValue.getAsJsonObject)).toMap

  /** Resolve real existing ancestors too, so a not-yet-created output directory cannot hide a symlink alias. */
  private[ide] def canonical(path: Path): Either[String, Path] = Try {
    def resolve(p: Path): Path = {
      if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p.toRealPath()
      else Option(p.getParent).map(parent => resolve(parent).resolve(p.getFileName)).getOrElse(p)
    }
    resolve(path.toAbsolutePath.normalize()).normalize()
  }.toEither.left.map(_ => "Cannot resolve baseline/output paths safely; check symlinks and permissions.")

  private[ide] def separate(path: Path, output: Path): Either[String, Path] = for {
    state <- canonical(path)
    target <- canonical(output)
    stateManifest <- canonical(path.resolve("manifest.json"))
    targetManifest <- canonical(output.resolve("manifest.json"))
    _ <- Either.cond(!state.startsWith(target) && !target.startsWith(state) && !stateManifest.startsWith(target) &&
      stateManifest != targetManifest, (), "Baseline state must be separate from the active artifact directory, including symlink aliases.")
  } yield state

  def baseline(root: DbtRoot): Either[String, Path] = {
    val configured = root.runOptions.stateDirectory.trim
    val path = root.executionDirectory.resolve(configured).normalize().toAbsolutePath
    if (configured.isEmpty) Left("Choose a baseline state directory.")
    else for {
      resolved <- separate(path, root.artifactDirectory)
      _ <- Either.cond(!Files.isRegularFile(path), (), "Choose a baseline directory containing manifest.json, not a file.")
      _ <- Either.cond(Files.isRegularFile(path.resolve("manifest.json")) || root.psiFile(path.resolve("manifest.json").toString).nonEmpty,
        (), "Baseline directory must contain manifest.json.")
    } yield resolved
  }
  def manifest(root: DbtRoot): Either[String, JsonObject] = baseline(root).flatMap { path =>
    val file = path.resolve("manifest.json")
    val source = root.psiFile(file.toString)
    val content = source match {
      case Some(psi) if psi.getTextLength > Limit => Left("Baseline manifest exceeds the 50 MB limit.")
      case Some(psi) => Right(psi.getText)
      case None => Try {
        val stream = Files.newInputStream(file)
        try stream.readNBytes(Limit + 1) finally stream.close()
      }.toEither.left.map(_ => "Cannot read baseline manifest.").flatMap(bytes =>
        if (bytes.length > Limit) Left("Baseline manifest exceeds the 50 MB limit.") else Right(new String(bytes, StandardCharsets.UTF_8)))
    }
    content.flatMap(parse)
  }
  private[ide] def parse(text: String): Either[String, JsonObject] = {
    if (text.length > Limit) Left("Baseline manifest exceeds the 50 MB limit.")
    else Try(JsonParser.parseString(text)).toEither.left.map(_ => "Baseline manifest is malformed.").flatMap { parsed =>
      if (!parsed.isJsonObject) Left("Baseline manifest must be an object.")
      else {
        val json = parsed.getAsJsonObject
        val schema = obj(json, "metadata").flatMap(str(_, "dbt_schema_version"))
        val sectionsValid = Sections.forall(key => !json.has(key) || obj(json, key).nonEmpty)
        if (!schema.exists(Schemas) || obj(json, "nodes").isEmpty) Left("Baseline manifest schema is missing or unsupported; supported manifest schemas are v9-v12.")
        else if (!sectionsValid) Left("Baseline manifest resource sections must be objects.")
        else {
          val resources = Sections.flatMap(section => obj(json, section).toVector.flatMap(_.entrySet().asScala))
          val valid = resources.map(_.getKey).distinct.size == resources.size && resources.forall { entry =>
            val value = entry.getValue
            value.isJsonObject && {
              val node = value.getAsJsonObject
              str(node, "unique_id").forall(_ == entry.getKey) &&
                Vector("config", "columns", "depends_on").forall(key => !node.has(key) || obj(node, key).nonEmpty) &&
                obj(node, "columns").forall(_.entrySet().asScala.forall(_.getValue.isJsonObject)) &&
                obj(node, "depends_on").flatMap(d => Option(d.get("nodes"))).forall(e => e.isJsonArray &&
                  e.getAsJsonArray.asScala.forall(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isString))
            }
          }
          if (resources.size > 50000) Left("Baseline manifest exceeds 50000 resource entries.")
          else if (!valid) Left("Baseline resource identities, columns, config or dependency shapes are malformed.")
          else Right(json)
        }
      }
    }
  }
  private def current(w: DbtWorkspace, root: DbtRoot): Map[String, Vector[DbtSymbol]] = w.symbols.filter(s => !s.metadata &&
    (s.isCode || s.kind == "source_table" || s.kind == "exposure" || s.isProperty && Set("unit_test", "metric", "semantic_model", "saved_query")(s.kind)) &&
    w.owner(s).exists(r => w.executionRoot(r).directory == w.executionRoot(root).directory)).groupBy(_.uniqueId)
    .view.mapValues { symbols =>
      symbols.groupBy(s => w.owner(s).map(_.directory.getPath)).values.toVector.flatMap { owned =>
        val code = owned.filter(_.isCode)
        (if (code.nonEmpty) code else owned).distinctBy(_.id)
      }
    }.toMap

  private def normalized(value: JsonElement): String = if (value.isJsonNull) "null" else if (value.isJsonPrimitive) new com.google.gson.JsonPrimitive(value.getAsString).toString
    else if (value.isJsonArray) value.getAsJsonArray.asScala.map(normalized).mkString("[", ",", "]")
    else value.getAsJsonObject.entrySet().asScala.toVector.sortBy(_.getKey).map(e => new com.google.gson.JsonPrimitive(e.getKey).toString + ":" + normalized(e.getValue)).mkString("{", ",", "}")
  private def empty(value: JsonElement): Boolean = value.isJsonNull || value.isJsonPrimitive && value.getAsString.isEmpty ||
    value.isJsonArray && value.getAsJsonArray.isEmpty || value.isJsonObject && value.getAsJsonObject.size() == 0
  private def tags(value: JsonElement): Vector[String] = (if (value.isJsonArray) value.getAsJsonArray.asScala.toVector.map(normalized)
    else if (value.isJsonPrimitive) value.getAsString.split(",").toVector.map(s => new com.google.gson.JsonPrimitive(s.trim).toString) else Vector(normalized(value))).sorted
  private def sourceDescription(w: DbtWorkspace, symbol: DbtSymbol): String =
    (Vector(symbol) ++ w.properties(symbol)).flatMap(_.mapping.flatMap(m => DbtYaml.string(m, "description").orElse(
      DbtYaml.parentMapping(m).filter(p => DbtYaml.value(p, "versions").nonEmpty).flatMap(DbtYaml.string(_, "description"))))).headOption
      .orElse(symbol.attributes.get("description")).getOrElse("")
  private final case class Difference(changed: Boolean = false, unknown: Boolean = false) {
    def combine(other: Difference): Difference = Difference(changed || other.changed, unknown || other.unknown)
  }
  /** Missing resolved nested fields can be defaults; they never prove a source change. */
  private def difference(before: JsonElement, after: JsonElement): Difference = {
    if (before.isJsonObject && after.isJsonObject) {
      val old = before.getAsJsonObject; val now = after.getAsJsonObject
      (old.keySet().asScala ++ now.keySet().asScala).toSet.foldLeft(Difference()) { (result, key) =>
        val change = (Option(old.get(key)), Option(now.get(key))) match {
          case (Some(a), Some(b)) => difference(a, b)
          case (None, Some(_)) => Difference(changed = true)
          case (Some(value), None) => Difference(unknown = !empty(value))
          case _ => Difference()
        }
        result.combine(change)
      }
    } else if (before.isJsonArray && after.isJsonArray && before.getAsJsonArray.size() == after.getAsJsonArray.size())
      before.getAsJsonArray.asScala.zip(after.getAsJsonArray.asScala).foldLeft(Difference()) { case (result, (a, b)) => result.combine(difference(a, b)) }
    else Difference(changed = normalized(before) != normalized(after))
  }
  private def configKey(key: String): String = key match { case "pre_hook" => "pre-hook"; case "post_hook" => "post-hook"; case other => other }
  private def yaml(value: org.jetbrains.yaml.psi.YAMLValue): JsonElement = value match {
    case scalar: org.jetbrains.yaml.psi.YAMLScalar =>
      if (Set("null", "~")(scalar.getText.toLowerCase(java.util.Locale.ROOT))) com.google.gson.JsonNull.INSTANCE else new com.google.gson.JsonPrimitive(scalar.getTextValue)
    case mapping: org.jetbrains.yaml.psi.YAMLMapping =>
      val result = new JsonObject; mapping.getKeyValues.asScala.foreach(k => Option(k.getValue).foreach(v => result.add(k.getKeyText, yaml(v)))); result
    case sequence: org.jetbrains.yaml.psi.YAMLSequence =>
      val result = new JsonArray; sequence.getItems.asScala.flatMap(i => Option(i.getValue)).foreach(v => result.add(yaml(v))); result
    case _ => com.google.gson.JsonNull.INSTANCE
  }
  private def sourceConfig(w: DbtWorkspace, symbol: DbtSymbol): Map[String, JsonElement] = {
    val scalar: Map[String, JsonElement] = symbol.attributes.filter(x => ConfigKeys(x._1)).map { case (key, value) => configKey(key) -> new com.google.gson.JsonPrimitive(value) }
    val mappings = (w.properties(symbol) :+ symbol).distinctBy(_.id).flatMap(_.mapping)
    mappings.foldLeft(scalar) { (values, mapping) =>
      DbtYaml.map(mapping, "config").toVector.flatMap(_.getKeyValues.asScala).foldLeft(values) { (previous, key) =>
        Option(key.getValue).fold(previous)(v => previous.updated(configKey(key.getKeyText), yaml(v)))
      }
    }
  }
  def compare(w: DbtWorkspace, root: DbtRoot): Either[String, Vector[DbtStateChange]] = manifest(root).map(compareManifest(w, root, _))
  private[ide] def compareManifest(w: DbtWorkspace, root: DbtRoot, baseline: JsonObject): Vector[DbtStateChange] = {
    val old = entries(baseline)
    val now = current(w, root)
    (old.keySet ++ now.keySet).toVector.sorted.flatMap { id =>
      ProgressManager.checkCanceled()
      (old.get(id), now.get(id)) match {
        case (None, Some(_)) => Some(DbtStateChange(id, "added", "No baseline declaration", ""))
        case (Some(previous), None) =>
          val generatedTest = id.startsWith("test.") && (previous.has("test_metadata") || str(previous, "original_file_path").exists(p => p.endsWith(".yml") || p.endsWith(".yaml")))
          Some(DbtStateChange(id, if (generatedTest) "unknown" else "removed", if (generatedTest) "Generated test identity cannot be proven from current source" else "No current source declaration", str(previous, "relation_name").getOrElse("")))
        case (Some(previous), Some(Vector(symbol))) =>
          val changed = scala.collection.mutable.ArrayBuffer.empty[String]
          val unknown = scala.collection.mutable.ArrayBuffer.empty[String]
          if (symbol.isCode) {
            val oldCode = str(previous, "raw_code").orElse(str(previous, "raw_sql"))
            if (oldCode.isEmpty) unknown += "Baseline source code unavailable"
            else if (oldCode.exists(_ != symbol.file.getText)) changed += "Source SQL/code changed"
          }
          val config = sourceConfig(w, symbol)
          obj(previous, "unrendered_config").orElse(obj(previous, "config")) match {
            case None => unknown += "Baseline resolved config unavailable"
            case Some(oldConfig) =>
              (ConfigKeys.map(configKey) ++ config.keySet).toVector.sorted.foreach { key =>
                val before = Option(oldConfig.get(key))
                val after = config.get(key)
                after match {
                  case Some(source) if normalized(source).contains("{{") || normalized(source).contains("{%") => unknown += s"Dynamic config: $key"
                  case Some(source) => before match {
                    case None => changed += s"Config added: $key"
                    case Some(value) if Set("pre-hook", "post-hook")(key) && obj(previous, "unrendered_config").isEmpty && !empty(value) =>
                      unknown += s"Hook normalization requires dbt: $key"
                    case Some(value) =>
                      val result = if (key == "tags" && !source.isJsonNull && !value.isJsonNull) Difference(changed = tags(value) != tags(source)) else difference(value, source)
                      if (result.changed) changed += s"Config changed: $key"
                      if (result.unknown) unknown += s"Resolved nested config defaults unknown: $key"
                  }
                  case None if before.exists(v => !empty(v)) => unknown += s"Current resolved config unknown: $key"
                  case _ => ()
                }
              }

          }
          val description = sourceDescription(w, symbol)
          if (description.contains("{{")) unknown += "Dynamic description"
          else if (str(previous, "description").exists(_ != description)) changed += "Description changed"
          obj(previous, "columns") match {
            case None => unknown += "Baseline column declarations unavailable"
            case Some(columns) =>
              val declared = w.columns(symbol).filterNot(_.metadata).distinctBy(_.name)
              val names = declared.map(_.name).toSet
              if (columns.keySet().asScala.toSet != names) changed += "Column declarations changed"
              declared.foreach { column => obj(columns, column.name).foreach { oldColumn =>
                ColumnKeys.toVector.sorted.foreach { key =>
                  val before = Option(oldColumn.get(key)).filterNot(_.isJsonNull)
                  val after = column.mapping.flatMap(DbtYaml.value(_, key)).map(yaml).orElse(column.attributes.get(key).map(new com.google.gson.JsonPrimitive(_)))
                  if (after.exists(v => normalized(v).contains("{{"))) unknown += s"Dynamic column metadata: ${column.name}.$key"
                  else {
                    val result = (before, after) match {
                      case (Some(a), Some(b)) => difference(a, b)
                      case (Some(a), None) => Difference(changed = !empty(a))
                      case (None, Some(b)) => Difference(changed = !empty(b))
                      case _ => Difference()
                    }
                    if (result.changed) changed += s"Column metadata changed: ${column.name}.$key"
                    if (result.unknown) unknown += s"Resolved column defaults unknown: ${column.name}.$key"
                  }
                }
              }}
          }
          val reasons = changed.toVector ++ unknown.toVector.distinct.map("Unresolved: " + _)
          if (changed.nonEmpty || unknown.nonEmpty) Some(DbtStateChange(id, if (changed.nonEmpty) "modified" else "unknown", reasons.mkString("; "), str(previous, "relation_name").getOrElse(""))) else None
        case _ => Some(DbtStateChange(id, "unknown", "Ambiguous current owning project/resource identity", ""))
      }
    }
  }

  /** A preview never probes whether the current target's relation exists. */
  def preview(w: DbtWorkspace, root: DbtRoot, expression: String, exclude: String = ""): Either[String, DbtStatePreview] = for {
    path <- baseline(root)
    data <- manifest(root)
  } yield {
    val old = entries(data); val now = current(w, root)
    val changes = compareManifest(w, root, data)
    val problems = scala.collection.mutable.ArrayBuffer.empty[String]
    val unknown = changes.count(_.status == "unknown")
    if (unknown > 0) problems += s"$unknown resources have unknown state; local state selection is partial."
    if (root.runOptions.defer && root.engine != "core") problems += "Core deferral flags are unavailable for the configured engine."
    if (root.runOptions.favorState && (!root.runOptions.defer || root.engine != "core" || !root.supports(4)))
      problems += "Favor state requires deferral and configured dbt Core 1.4+; the command is not executable with these options."
    val edges = DbtLineage.edges(w)
    def key(s: DbtSymbol): String = w.owner(s).map(_.directory.getPath).getOrElse("") + "|" + s.uniqueId
    def term(raw: String): Vector[DbtSymbol] = {
      val core = raw.replaceFirst("^[0-9]*\\+", "").replaceFirst("\\+[0-9]*$", "")
      if (!core.startsWith("state:")) {
        val resolved = DbtSelection.resolve(w, root, raw); problems ++= resolved.problems; resolved.resources
      } else {
        val status = core.stripPrefix("state:")
        val ids = status match {
          case "modified" => changes.filter(_.status == "modified").map(_.id).toSet
          case "new" => changes.filter(_.status == "added").map(_.id).toSet
          case "old" => old.keySet
          case "unmodified" => old.keySet -- changes.map(_.id)
          case _ => problems += s"Unsupported local state selector: $core"; Set.empty[String]
        }
        val matched = now.toVector.filter(p => ids(p._1)).flatMap(_._2).filter(_.isEnabled)
        val parents = raw.matches("^[0-9]*\\+.*"); val children = raw.matches(".*\\+[0-9]*$")
        val parentDepth = "^([0-9]*)\\+".r.findFirstMatchIn(raw).flatMap(m => m.group(1).toIntOption).getOrElse(100)
        val childDepth = "\\+([0-9]*)$".r.findFirstMatchIn(raw).flatMap(m => m.group(1).toIntOption).getOrElse(100)
        (matched ++ matched.flatMap(s => (if (parents) DbtLineage.traverse(edges, s, downstream = false, parentDepth.min(100)) else Vector.empty) ++
          (if (children) DbtLineage.traverse(edges, s, downstream = true, childDepth.min(100)) else Vector.empty))).distinctBy(key)
      }
    }
    def selection(text: String): Vector[DbtSymbol] = text.trim.split("\\s+").toVector.filter(_.nonEmpty).flatMap { token =>
      val groups = token.split(",").toVector.map(term)
      groups.headOption.getOrElse(Vector.empty).filter(s => groups.drop(1).forall(_.exists(t => key(t) == key(s))))
    }.distinctBy(key)
    val chosen = selection(if (expression.trim.isEmpty) "*" else expression)
    val removed = if (exclude.trim.isEmpty) Set.empty[String] else selection(exclude).map(key).toSet
    val selected = chosen.filterNot(s => removed(key(s)))
    val selectedIds = selected.map(key).toSet
    val upstream = selected.flatMap(s => DbtLineage.traverse(edges, s, downstream = false, 100)).distinctBy(key).filterNot(s => selectedIds(key(s)))
    val dependencies = upstream.filter(s => DbtSymbol.RelationKinds(s.kind)).take(1000).map { symbol =>
      val previous = old.get(symbol.uniqueId)
      val relation = previous.flatMap(str(_, "relation_name")).filterNot(v => v.contains("{{") || v.contains("{%")) .getOrElse("")
      val ephemeral = symbol.attributes.get("materialized").contains("ephemeral")
      val resolution = if (symbol.kind == "source_table") "source" else if (ephemeral || !root.runOptions.defer) "local"
        else if (relation.isEmpty || symbol.isStale || symbol.metadata || symbol.attributes.get("materialized").exists(_.contains("{{"))) "unknown" else if (root.runOptions.favorState) "deferred" else "candidate"
      val reason = resolution match {
        case "source" => "source() references do not defer; use current source configuration."
        case "local" => if (ephemeral) "Ephemeral dependency is compiled locally." else "Deferral is disabled."
        case "unknown" => "Baseline physical relation or current materialization/identity is unavailable; no target fallback is assumed."
        case "deferred" => "Unselected dependency uses the baseline relation with favor-state."
        case _ => "May use baseline only if the current target relation does not exist; warehouse existence was not queried."
      }
      DbtStateDependency(symbol.uniqueId, resolution, if (resolution == "source" || resolution == "local") "" else relation,
        path.resolve("manifest.json").toString + "#" + symbol.uniqueId, reason)
    }
    if (upstream.size > 1000) problems += "Dependency preview is limited to 1000 resources and 100 graph steps."
    DbtStatePreview(path.toString, selected, dependencies, problems.toVector.distinct)
  }
}
