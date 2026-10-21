package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.ide.DbtAgentProtocol.obj
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.{DbtLineage, DbtSelection}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.{JsonArray, JsonElement, JsonObject}
import com.intellij.diff.{DiffContentFactory, DiffManager}
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters._
import scala.util.Try

private[ide] final case class DbtStateChange(id: String, status: String, reason: String, relation: String)
private[ide] final case class DbtStateDependency(id: String, resolution: String, relation: String, evidence: String, reason: String)
private[ide] final case class DbtStatePreview(baseline: String, selected: Vector[DbtSymbol], dependencies: Vector[DbtStateDependency],
  problems: Vector[String])

/** Static source comparison is deliberately distinct from dbt's runtime state and warehouse existence checks. */
private[ide] object DbtState {
  private val ConfigKeys = Set("materialized", "enabled", "alias", "database", "schema", "tags", "access", "group", "unique_key",
    "on_schema_change", "incremental_strategy", "partition_by", "cluster_by", "contract", "persist_docs", "grants", "quoting", "full_refresh", "meta", "pre_hook", "post_hook")
  private val ColumnKeys = Set("description", "data_type", "quote", "constraints", "meta", "tags")
  private def entries(manifest: DbtManifest): Map[String, JsonObject] = manifest.resources.map(r => r.id -> r.data).toMap

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
  def manifest(root: DbtRoot): Either[String, DbtManifest] = baseline(root).flatMap { path =>
    DbtManifestReader.get(root.config.getProject).read(root, path.resolve("manifest.json").toString, DbtManifestPolicy.Baseline)
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
  /** The diff labels distinguish recorded baseline values from authored, unresolved source values. */
  def comparison(w: DbtWorkspace, root: DbtRoot, id: String): Either[String, (String, String)] = manifest(root).map { baseline =>
    val before = entries(baseline).get(id).map { entry =>
      obj("raw_code" -> DbtJson.text(entry, "raw_code").orElse(DbtJson.text(entry, "raw_sql")).getOrElse(""),
        "description" -> DbtJson.text(entry, "description").getOrElse(""),
        "config" -> DbtJson.child(entry, "unrendered_config").orElse(DbtJson.child(entry, "config")).getOrElse(new JsonObject),
        "columns" -> DbtJson.child(entry, "columns").getOrElse(new JsonObject))
    }
    val after = current(w, root).get(id).filter(_.size == 1).flatMap(_.headOption).map { symbol =>
      val config = new JsonObject
      sourceConfig(w, symbol).toVector.sortBy(_._1).foreach { case (key, value) => config.add(key, value) }
      val columns = new JsonObject
      w.columns(symbol).filterNot(_.metadata).foreach { column =>
        column.mapping.foreach(m => columns.add(column.name, yaml(m)))
      }
      obj("raw_code" -> (if (symbol.isCode) symbol.file.getText else ""), "description" -> sourceDescription(w, symbol),
        "config" -> config, "columns" -> columns)
    }
    val gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
    before.map(gson.toJson(_)).getOrElse("") -> after.map(gson.toJson(_)).getOrElse("")
  }

  def compare(w: DbtWorkspace, root: DbtRoot): Either[String, Vector[DbtStateChange]] = manifest(root).map(compareManifest(w, root, _))
  private[ide] def compareManifest(w: DbtWorkspace, root: DbtRoot, baseline: DbtManifest): Vector[DbtStateChange] = {
    val old = entries(baseline)
    val now = current(w, root)
    (old.keySet ++ now.keySet).toVector.sorted.flatMap { id =>
      ProgressManager.checkCanceled()
      (old.get(id), now.get(id)) match {
        case (None, Some(_)) => Some(DbtStateChange(id, "added", "No baseline declaration", ""))
        case (Some(previous), None) =>
          val generatedTest = id.startsWith("test.") && (previous.has("test_metadata") || DbtJson.text(previous, "original_file_path").exists(p => p.endsWith(".yml") || p.endsWith(".yaml")))
          Some(DbtStateChange(id, if (generatedTest) "unknown" else "removed", if (generatedTest) "Generated test identity cannot be proven from current source" else "No current source declaration", DbtJson.text(previous, "relation_name").getOrElse("")))
        case (Some(previous), Some(Vector(symbol))) =>
          val changed = scala.collection.mutable.ArrayBuffer.empty[String]
          val unknown = scala.collection.mutable.ArrayBuffer.empty[String]
          if (symbol.isCode) {
            val oldCode = DbtJson.text(previous, "raw_code").orElse(DbtJson.text(previous, "raw_sql")).filter(_.nonEmpty)
            if (oldCode.isEmpty) unknown += "Baseline source code unavailable"
            else if (oldCode.exists(_.trim != symbol.file.getText.trim)) changed += "Source SQL/code changed"
          }
          val config = sourceConfig(w, symbol)
          DbtJson.child(previous, "unrendered_config").orElse(DbtJson.child(previous, "config")) match {
            case None => unknown += "Baseline resolved config unavailable"
            case Some(oldConfig) =>
              (ConfigKeys.map(configKey) ++ config.keySet).toVector.sorted.foreach { key =>
                val before = Option(oldConfig.get(key)).orElse(
                  if (symbol.kind == "source_table" && Set("database", "schema")(key)) Option(previous.get("unrendered_" + key)) else None)
                val after = config.get(key)
                after match {
                  case Some(source) if normalized(source).contains("{{") || normalized(source).contains("{%") => unknown += s"Dynamic config: $key"
                  case Some(source) => before match {
                    case None => changed += s"Config added: $key"
                    case Some(value) if Set("pre-hook", "post-hook")(key) && DbtJson.child(previous, "unrendered_config").isEmpty && !empty(value) =>
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
          else if (DbtJson.text(previous, "description").exists(_ != description)) changed += "Description changed"
          DbtJson.child(previous, "columns") match {
            case None => unknown += "Baseline column declarations unavailable"
            case Some(columns) =>
              val declared = w.columns(symbol).filter(c => !c.metadata && c.mapping.nonEmpty).distinctBy(_.name)
              val names = declared.map(_.name).toSet
              if (columns.keySet().asScala.toSet != names) changed += "Column declarations changed"
              declared.foreach { column => DbtJson.child(columns, column.name).foreach { oldColumn =>
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
          if (changed.nonEmpty || unknown.nonEmpty) Some(DbtStateChange(id, if (changed.nonEmpty) "modified" else "unknown", reasons.mkString("; "), DbtJson.text(previous, "relation_name").getOrElse(""))) else None
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
    def key(s: DbtSymbol): String = DbtResourceIdentity.key(w, s)
    def term(raw: String): Vector[DbtSymbol] = {
      val core = DbtLineage.withoutOperators(raw)
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
        val (parents, children) = DbtLineage.operators(raw)
        val parentDepth = parents.flatMap(_.dropRight(1).toIntOption).getOrElse(100)
        val childDepth = children.flatMap(_.drop(1).toIntOption).getOrElse(100)
        (matched ++ matched.flatMap(s => parents.toVector.flatMap(_ => DbtLineage.traverse(edges, s, downstream = false, parentDepth.min(100))) ++
          children.toVector.flatMap(_ => DbtLineage.traverse(edges, s, downstream = true, childDepth.min(100))))).distinctBy(key)
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
      val relation = previous.flatMap(DbtJson.text(_, "relation_name")).filterNot(v => v.contains("{{") || v.contains("{%")) .getOrElse("")
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

private[ide] object DbtStateComparison {
  def show(configuration: DbtRunConfiguration): Unit = {
    val project = configuration.getProject
    DbtUi.readLater(project) {
      val w = DbtWorkspace.get(project)
      for {
        root <- configuration.configuredRoot(w)
        changes <- DbtState.compare(w, root)
      } yield configuration.sourceKey(w) -> changes
    } {
      case Left(error) => Messages.showErrorDialog(project, error, "dbt baseline comparison")
      case Right((_, changes)) if changes.isEmpty => Messages.showInfoMessage(project, "No locally detectable changes.", "dbt baseline comparison")
      case Right((key, changes)) =>
        val choices = changes.map(c => (c.status + ": " + c.id + " · " + c.reason) -> c.id).toMap
        JBPopupFactory.getInstance.createPopupChooserBuilder(choices.keys.toVector.sorted.asJava)
          .setTitle("Compare baseline with current source").setItemChosenCallback((label: String) => {
            DbtUi.readLater(project) {
              val w = DbtWorkspace.get(project)
              for {
                _ <- Either.cond(configuration.sourceKey(w) == key, (), "Source or settings changed. Compare baseline again.")
                root <- configuration.configuredRoot(w)
                text <- DbtState.comparison(w, root, choices(label))
              } yield text
            } {
              case Left(error) => Messages.showErrorDialog(project, error, "dbt baseline comparison")
              case Right((before, after)) =>
                val factory = DiffContentFactory.getInstance()
                DiffManager.getInstance.showDiff(project, new SimpleDiffRequest(label, factory.create(before), factory.create(after),
                  "Baseline manifest (recorded)", "Current source (unresolved defaults)"))
            }
          }).createPopup().showCenteredInCurrentWindow(project)
    }
  }
}
