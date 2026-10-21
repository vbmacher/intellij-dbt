package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.ide.DbtAgentProtocol.{array, obj, strings}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.{DbtLineage, DbtSelection}
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.navigation.{GotoRelatedItem, GotoRelatedProvider}
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, DataContext}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileVisitResult, Files, Path, Paths, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.{JList, JScrollPane}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[dbt] final case class DbtAirflowTask(dag: String, task: String, project: String, selection: String,
  exclude: String, resources: Vector[DbtSymbol], evidence: String, offset: Int, ingestion: Vector[String],
  url: Option[String], problems: Vector[String], projectRoot: Option[String] = None, definitionRoot: String = "",
  line: Int = 1, schedule: String = "", kind: String = "dbt", indirect: String = "") {
  def id: String = evidence + ":" + offset + "|" + dag + "|" + task + "|" + project
}
private[ide] final case class DbtAirflowDestination(path: String, offset: Int, url: Option[String] = None)

/** Automatic static discovery of local DAG code; no Python or Airflow execution. */
private[dbt] object DbtAirflow {
  private val Tasks = Key.create[(DbtAirflowScan, Vector[String], Vector[DbtAirflowTask])]("dbt.airflow.tasks")
  private def resourceKey(w: DbtWorkspace, resource: DbtSymbol): String = DbtResourceIdentity.key(w, resource) + "|" + resource.id

  def problems(w: DbtWorkspace): Vector[String] = w.project.getService(classOf[DbtAirflowDiscovery]).scan().problems

  def tasks(w: DbtWorkspace): Vector[DbtAirflowTask] = {
    val scan = w.project.getService(classOf[DbtAirflowDiscovery]).scan()
    val settings = w.project.getService(classOf[DbtConsumersSettings]).getState
    val configuration = Vector(settings.airflowUrl)
    Option(w.getUserData(Tasks)).filter(c => (c._1 eq scan) && c._2 == configuration).map(_._3).getOrElse {
      val executionRoots = w.roots.filter(r => w.executionRoot(r) == r)
      val base = safeUrl(settings.airflowUrl)
      def rootFor(file: String): Option[DbtRoot] = {
        if (executionRoots.size == 1) executionRoots.headOption
        else executionRoots.filter(r => file.startsWith(r.directory.getPath + "/")).sortBy(_.directory.getPath.length).lastOption
      }
      def link(dag: String, task: String): Option[String] = base.map(_.stripSuffix("/") + "/dags/" + encode(dag) + "/grid" +
        (if (task == dag) "" else "?task_id=" + encode(task)))
      val raw = scan.pipelines.flatMap { case (file, pipeline) =>
        ProgressManager.checkCanceled()
        val root = rootFor(file)
        val missingProject = Option.when(root.isEmpty)("dbt project is missing or ambiguous. Open its project in IDEA; with multiple dbt projects, place DAG files inside the owning project.").toVector
        val sites = pipeline.sites.map { site =>
          val expression = if (site.named.nonEmpty) "selector:" + site.named else if (site.select.isEmpty && !site.dynamic) "*" else site.select
          val dynamic = site.dynamic || Vector(expression, site.exclude).exists(s => s.contains("{{") || s.contains("${"))
          val invalid = missingProject ++ Option.when(dynamic)("Dynamic/runtime selection is unresolved.")
          val resolved = if (invalid.nonEmpty) None else root.map(DbtSelection.resolve(w, _, expression, site.exclude))
          DbtAirflowTask(pipeline.name, site.task, root.map(_.name).getOrElse(""), expression, site.exclude,
            resolved.toVector.flatMap(_.resources), file, site.offset, Vector.empty, link(pipeline.name, site.task),
            invalid ++ resolved.toVector.flatMap(_.problems), root.map(_.directory.getPath), root.map(_.directory.getPath).getOrElse(""),
            site.line, pipeline.schedule, indirect = site.indirect)
        }
        val ingestion = if (pipeline.ingests.isEmpty) Vector.empty else {
          val relations = root.toVector.flatMap(r => w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind) &&
            w.owner(s).exists(w.executionRoot(_) == r))).sortBy(s => (s.isProperty, s.metadata))
            .distinctBy(s => DbtResourceIdentity.key(w, s)).map { symbol =>
              val attributes = DbtResourceData.get(w, symbol).attributes
              (symbol, attributes.get("schema"), attributes.getOrElse("identifier", attributes.getOrElse("alias", symbol.name)))
            }
          val matches = pipeline.ingests.flatMap { ref =>
            val exact = ref.dataset.toVector.flatMap(ds => relations.filter(r => r._2.contains(ds) && r._3 == ref.table))
            val found = if (ref.dataset.nonEmpty && exact.nonEmpty) exact.map(_._1 -> "exact")
            else if (ref.dataset.nonEmpty) relations.filter(r => r._2.exists(_.equalsIgnoreCase(ref.dataset.get)) && r._3.equalsIgnoreCase(ref.table)).map(_._1 -> "case-insensitive")
            else {
              val byTable = relations.filter(_._3 == ref.table)
              if (byTable.size == 1) byTable.map(_._1 -> "table-only") else Vector.empty
            }
            found.map { case (symbol, confidence) => (symbol, ref.dataset.map(_ + ".").getOrElse("") + ref.table + " (" + confidence + "; " + ref.via + ")") }
          }
          val first = pipeline.ingests.head
          Vector(DbtAirflowTask(pipeline.name, pipeline.name, root.map(_.name).getOrElse(""), "", "",
            matches.map(_._1).distinctBy(s => DbtResourceIdentity.key(w, s)), file, first.offset, matches.map(_._2).distinct,
            link(pipeline.name, pipeline.name), missingProject, root.map(_.directory.getPath), root.map(_.directory.getPath).getOrElse(""),
            first.line, pipeline.schedule, "ingestion"))
        }
        sites ++ ingestion
      }
      val ingestors = raw.filter(_.kind == "ingestion")
      val edges = if (ingestors.nonEmpty) DbtLineage.edges(w) else Vector.empty
      val result = raw.map { task =>
        if (task.kind != "dbt" || ingestors.isEmpty) task else {
          val upstream = task.resources.flatMap(s => s +: DbtLineage.traverse(edges, s, downstream = false, Int.MaxValue))
            .map(DbtResourceIdentity.key(w, _)).toSet
          task.copy(ingestion = ingestors.filter(_.resources.exists(s => upstream(DbtResourceIdentity.key(w, s)))).map(_.dag).distinct)
        }
      }
      w.putUserData(Tasks, (scan, configuration, result))
      result
    }
  }

  /** Unresolved selections provide project context without claiming resource membership. */
  def context(w: DbtWorkspace, resource: DbtSymbol): Vector[DbtAirflowTask] = tasks(w).filter { task =>
    selected(w, resource, task) || task.problems.nonEmpty && w.owner(resource).exists(r => task.projectRoot.contains(w.executionRoot(r).directory.getPath))
  }

  def selected(w: DbtWorkspace, resource: DbtSymbol, task: DbtAirflowTask): Boolean =
    task.resources.exists(other => resourceKey(w, other) == resourceKey(w, resource))

  def details(w: DbtWorkspace, resource: DbtSymbol): JsonObject = {
    val all = context(w, resource)
    val tasks = all.take(50).map { task =>
      val matches = selected(w, resource, task)
      val item = obj("id" -> task.id, "dag" -> task.dag, "task" -> task.task,
        "project" -> task.project, "projectRoot" -> task.projectRoot.getOrElse(""), "selected" -> matches,
        "status" -> (if (!matches) "Unresolved selection — association unknown" else if (task.problems.nonEmpty) "Partial detected selection"
          else if (task.kind == "ingestion") "Detected ingestion destination" else "Detected selection"),
        "schedule" -> task.schedule, "kind" -> task.kind, "indirect" -> task.indirect,
        "selection" -> task.selection.take(4000), "exclude" -> task.exclude.take(4000),
        "path" -> task.evidence, "offset" -> task.offset, "line" -> task.line)
      task.url.foreach(item.addProperty("url", _))
      item.add("ingestion", strings(task.ingestion.take(100))); item.add("problems", strings(task.problems.map(_.take(2000))))
      item
    }
    obj("tasks" -> array(tasks), "truncated" -> (all.size > 50), "selectedCount" -> all.count(selected(w, resource, _)),
      "unresolvedCount" -> all.count(task => !selected(w, resource, task)))
  }

  def navigation(w: DbtWorkspace, resource: DbtSymbol, mappingId: String, target: String): Either[String, DbtAirflowDestination] =
    context(w, resource).find(_.id == mappingId).toRight("Airflow DAG selection changed or no longer applies to this resource. Refresh its details.")
      .flatMap { task => target match {
        case "definition" => Right(DbtAirflowDestination(task.evidence, task.offset))
        case "browser" => task.url.toRight("No Airflow URL is configured. Set it in Settings > dbt Workspace > Integrations.")
          .map(url => DbtAirflowDestination(task.evidence, task.offset, Some(url)))
        case _ => Left("Unknown Airflow navigation action.")
      }}

  private def encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
  def safeUrl(text: String): Option[String] = scala.util.Try(new URI(text)).toOption.filter(u =>
    Set("http", "https")(Option(u.getScheme).getOrElse("")) && u.getHost != null && u.getUserInfo == null).map(_.toASCIIString)
}

private[ide] final case class DbtAirflowScan(pipelines: Vector[(String, DbtAirflowParser.Pipeline)], problems: Vector[String])

/** Watches external DAG checkouts as well as files already belonging to this IDEA project. */
@Service(Array(Service.Level.PROJECT))
final class DbtAirflowDiscovery(project: Project) extends Disposable {
  private val skip = Set("__pycache__", "node_modules", ".git", ".venv", "venv")
  private var watched = Vector.empty[String]
  private var requests = Vector.empty[LocalFileSystem.WatchRequest]
  private var cache = DbtAirflowScan(Vector.empty, Vector.empty)
  private case class FileEntry(stamp: (java.nio.file.attribute.FileTime, Long, Option[Long]), pipelines: Vector[DbtAirflowParser.Pipeline])
  private val parsed = mutable.Map.empty[Path, FileEntry]

  private[ide] def directories: Vector[String] = {
    val s = project.getService(classOf[DbtConsumersSettings]).getState
    s.airflowPaths.asScala.toVector.map(_.trim).filter(_.nonEmpty).map { value =>
      val path = Paths.get(value)
      (if (path.isAbsolute) path else Paths.get(Option(project.getBasePath).getOrElse(".")).resolve(path)).normalize().toString
    }.distinct
  }

  private def watch(paths: Vector[String]): Unit = if (watched != paths) {
    val fs = LocalFileSystem.getInstance()
    requests.foreach(fs.removeWatchedRoot)
    requests = paths.flatMap(p => Option(fs.addRootToWatch(p, true)))
    watched = paths
    // Register the external tree with VFS so edits, additions and removals refresh open lineage views.
    ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
      override def run(): Unit = if (!project.isDisposed) paths.foreach { p =>
        Option(fs.refreshAndFindFileByPath(p)).foreach(_.refresh(true, true))
      }
    })
  }

  private[ide] def scan(): DbtAirflowScan = synchronized {
    val paths = directories
    watch(paths)
    val pipelines = mutable.ArrayBuffer.empty[(String, DbtAirflowParser.Pipeline)]
    val problems = mutable.ArrayBuffer.empty[String]
    val seen = mutable.Set.empty[Path]
    paths.foreach { directory =>
      val root = Paths.get(directory)
      if (!Files.isDirectory(root)) problems += s"Airflow DAG folder is missing or is not a directory: $directory"
      else try Files.walkFileTree(root, new SimpleFileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
          ProgressManager.checkCanceled()
          if (Option(dir.getFileName).exists(name => skip(name.toString))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        }
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          ProgressManager.checkCanceled()
          if (file.toString.endsWith(".py") && attrs.isRegularFile && seen.add(file.toAbsolutePath.normalize())) {
            if (attrs.size() > 1000000) problems += s"Airflow DAG file exceeds 1 MB: $file"
            else try {
              val virtual = Option(LocalFileSystem.getInstance().findFileByNioFile(file))
              val documents = FileDocumentManager.getInstance()
              val unsaved = virtual.flatMap(f => Option(documents.getCachedDocument(f))).filter(documents.isDocumentUnsaved)
              val stamp = (attrs.lastModifiedTime(), attrs.size(), unsaved.map(_.getModificationStamp))
              val entry = parsed.get(file).filter(_.stamp == stamp).getOrElse {
                val source = unsaved.map(_.getText).getOrElse(Files.readString(file))
                FileEntry(stamp, DbtAirflowParser.parse(file.toString, source))
              }
              parsed(file) = entry
              pipelines ++= entry.pipelines.map(file.toString -> _)
            } catch {
              case canceled: ProcessCanceledException => throw canceled
              case NonFatal(_) => problems += s"Cannot read Airflow DAG file: $file"
            }
          }
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult = {
          problems += s"Cannot read Airflow DAG path: $file"; FileVisitResult.CONTINUE
        }
      }) catch {
        case canceled: ProcessCanceledException => throw canceled
        case NonFatal(_) => problems += s"Cannot scan Airflow DAG folder: $directory"
      }
    }
    parsed.keys.filterNot(p => seen(p.toAbsolutePath.normalize())).toVector.foreach(parsed.remove)
    val result = DbtAirflowScan(pipelines.toVector.sortBy { case (path, p) => (path, p.name) }, problems.toVector.distinct)
    if (result != cache) cache = result
    cache
  }

  override def dispose(): Unit = synchronized {
    requests.foreach(LocalFileSystem.getInstance().removeWatchedRoot)
    requests = Vector.empty
  }
}

/** Static discovery adapted from tchandrap/dbt-airflow-lineage (MIT; see third-party/dbt-airflow-lineage).
  * Reads literals only: DAG modules, imports and user functions are never executed.
  */
private[ide] object DbtAirflowParser {
  final case class Site(select: String, exclude: String, named: String, dynamic: Boolean,
    task: String, indirect: String, offset: Int, line: Int)
  final case class Ingest(dataset: Option[String], table: String, offset: Int, line: Int, via: String)
  final case class Pipeline(name: String, schedule: String, sites: Vector[Site], ingests: Vector[Ingest])
  private case class Tok(kind: String, value: String, offset: Int, line: Int, formatted: Boolean = false, items: Vector[Tok] = Vector.empty)
  private case class KV(key: String, value: Vector[Tok], offset: Int, line: Int)
  private sealed trait Value
  private case class Text(value: String) extends Value
  private case class Values(value: Vector[Value]) extends Value
  private case object Unset extends Value
  private case object Dynamic extends Value
  private type Env = Map[String, Vector[Tok]]
  private val SelectKeys = Set("dbt_selector", "dbt_select", "selector", "select", "selectors", "dbt_selectors")
  private val ExcludeKeys = Set("dbt_exclude", "exclude", "excludes", "dbt_excludes")
  private val NameKeys = Set("name", "dag_name_suffix", "dag_id", "dag_name", "pipeline_name")
  private val NamedKeys = Set("dbt_selector_name", "selector_name")
  private val ScheduleKeys = Set("schedule", "schedule_interval")
  private val IndirectKeys = Set("dbt_indirect_selection", "indirect_selection")
  private val TableKeys = Set("bq_table", "bq_table_name", "bq_table_id", "destination_table", "target_table", "target_table_name")
  private val DatasetKeys = Set("bq_dataset", "bq_dataset_id", "destination_dataset", "target_dataset")
  private val FullKeys = Set("full_table_schema_reference", "target_table_fullname", "destination_table_fullname", "destination_project_dataset_table")
  private val FullRef = """`?(?:\{[^}]+\}|[\w-]+)\.([A-Za-z_]\w*)\.([A-Za-z_]\w*)`?""".r

  private def tokenize(source: String): Vector[Tok] = {
    var at = 0; var line = 1
    val prefix = """([rRbBuUfF]{0,2})(\"\"\"|'''|\"|')""".r
    def read(close: Char, depth: Int): Vector[Tok] = {
      val out = mutable.ArrayBuffer.empty[Tok]
      while (at < source.length) {
        ProgressManager.checkCanceled()
        val c = source(at)
        if (c == close && close != 0) { at += 1; return out.toVector }
        if (c == '\n') { line += 1; at += 1 }
        else if (c.isWhitespace || c == '\\') at += 1
        else if (c == '#') { while (at < source.length && source(at) != '\n') at += 1 }
        else prefix.findPrefixMatchOf(source.substring(at, (at + 6).min(source.length))) match {
          case Some(m) =>
            val start = at; val startLine = line; val quote = m.group(2); val raw = m.group(1).toLowerCase.contains('r')
            val value = new StringBuilder
            at += m.end
            while (at < source.length && !source.startsWith(quote, at)) {
              if (source(at) == '\\' && !raw && at + 1 < source.length) {
                val next = source(at + 1)
                value.append(if (next == 'n') '\n' else if (next == 't') '\t' else next)
                if (next == '\n') line += 1
                at += 2
              } else { if (source(at) == '\n') line += 1; value.append(source(at)); at += 1 }
            }
            if (at < source.length) at += quote.length
            out += Tok("str", value.toString, start, startLine, m.group(1).toLowerCase.contains('f'))
          case None if c.isLetter || c == '_' =>
            val start = at
            while (at < source.length && (source(at).isLetterOrDigit || source(at) == '_')) at += 1
            out += Tok("name", source.substring(start, at), start, line)
          case None if "([{".contains(c) && depth < 100 =>
            val start = at; val startLine = line; at += 1
            val children = read(Map('(' -> ')', '[' -> ']', '{' -> '}')(c), depth + 1)
            out += Tok("group", c.toString, start, startLine, items = children)
          case None => out += Tok("op", c.toString, at, line); at += 1
        }
      }
      out.toVector
    }
    read(0.toChar, 0)
  }

  private def split(items: Vector[Tok], separator: String = ","): Vector[Vector[Tok]] = {
    val out = mutable.ArrayBuffer(Vector.empty[Tok])
    items.foreach(t => if (t.kind == "op" && t.value == separator) out += Vector.empty else out(out.size - 1) :+= t)
    out.toVector.filter(_.nonEmpty)
  }
  private def flatten(value: Value): Option[Vector[String]] = value match {
    case Text(s) => Some(Vector(s))
    case Unset => Some(Vector.empty)
    case Values(xs) =>
      val parts = xs.map(flatten)
      Option.when(parts.forall(_.nonEmpty))(parts.flatten.flatten)
    case Dynamic => None
  }
  private def asText(value: Value): Option[String] = flatten(value).map(_.mkString(" ").trim).filter(_.nonEmpty)
  private def eval(tokens: Vector[Tok], env: Env, depth: Int = 0): Value = {
    if (tokens.isEmpty || depth > 8) return Dynamic
    val plus = split(tokens, "+")
    if (plus.size > 1) {
      val parts = plus.map(eval(_, env, depth + 1))
      if (parts.forall(_.isInstanceOf[Text])) Text(parts.collect { case Text(s) => s }.mkString)
      else if (parts.forall(_.isInstanceOf[Values])) Values(parts.collect { case Values(xs) => xs }.flatten)
      else Dynamic
    } else if (tokens.forall(_.kind == "str")) {
      if (tokens.exists(t => t.formatted && t.value.contains('{'))) Dynamic else Text(tokens.map(_.value).mkString)
    } else if (tokens.size == 1) {
      val t = tokens.head
      if (t.kind == "name" && t.value == "None") Unset
      else if (t.kind == "name" && env.contains(t.value)) eval(env(t.value), env, depth + 1)
      else if (t.kind == "group" && Set("[", "(")(t.value)) {
        val parts = split(t.items)
        if (t.value == "(" && parts.size == 1 && !t.items.exists(_.value == ",")) eval(parts.head, env, depth + 1)
        else Values(parts.map(eval(_, env, depth + 1)))
      } else Dynamic
    } else {
      val dot = tokens.indices.find(i => tokens(i).value == "." && tokens.lift(i + 1).exists(_.value == "join"))
      dot.flatMap { i => tokens.lift(i + 2).filter(_.kind == "group").map { group =>
        (eval(tokens.take(i), env, depth + 1), eval(Vector(group), env, depth + 1)) match {
          case (Text(separator), list: Values) => flatten(list).map(xs => Text(xs.mkString(separator))).getOrElse(Dynamic)
          case _ => Dynamic
        }
      }}.getOrElse(Dynamic)
    }
  }
  private def environment(top: Vector[Tok]): Env = {
    val env = mutable.Map.empty[String, Vector[Tok]]
    top.indices.foreach { i =>
      if (top(i).kind == "name" && top.lift(i + 1).exists(_.value == "=") && !top.lift(i - 1).exists(_.value == ".")) {
        val expressionLine = top.lift(i + 2).map(_.line).getOrElse(top(i).line)
        val expression = top.drop(i + 2).takeWhile(t => t.line == expressionLine && t.value != ";")
        if (eval(expression, env.toMap) != Dynamic) env(top(i).value) = expression
      }
    }
    env.toMap
  }
  private def kvs(group: Tok): Vector[KV] = split(group.items).flatMap { el =>
    if (group.value == "(" && el.head.kind == "name" && el.lift(1).exists(_.value == "="))
      Some(KV(el.head.value, el.drop(2), el.head.offset, el.head.line))
    else if (group.value == "{" && el.head.kind == "str" && el.lift(1).exists(_.value == ":"))
      Some(KV(el.head.value, el.drop(2), el.head.offset, el.head.line))
    else None
  }
  private def dagDefinition(items: Vector[Tok]): Option[Vector[KV]] = items.indices.iterator.flatMap { i =>
    val t = items(i)
    if (t.kind == "name" && Set("DAG", "dag")(t.value) && !items.lift(i - 1).exists(_.value == "def"))
      items.lift(i + 1).filter(g => g.kind == "group" && g.value == "(").map(kvs).filter(_.nonEmpty)
    else if (t.kind == "group") dagDefinition(t.items) else None
  }.nextOption()

  /** CLI flag extraction uses the same spellings as the reference, including --models and -m. */
  def shell(command: String): Option[(String, String, String)] = {
    if (!"""\bdbt\s+(run|build|test|seed|snapshot|ls|list)\b""".r.findFirstIn(command).isDefined) return None
    val tokens = com.intellij.util.execution.ParametersListUtil.parse(command, false, true).asScala.toVector
    def grab(flags: Set[String]): String = {
      val values = mutable.ArrayBuffer.empty[String]; var i = 0
      while (i < tokens.size) {
        val token = tokens(i)
        flags.find(f => token.startsWith(f + "=")) match {
          case Some(flag) => values += token.drop(flag.length + 1)
          case None if flags(token) =>
            while (i + 1 < tokens.size && !tokens(i + 1).startsWith("-") && !Set("&&", ";", "||", "|")(tokens(i + 1))) { i += 1; values += tokens(i) }
          case _ =>
        }
        i += 1
      }
      values.mkString(" ")
    }
    Some((grab(Set("--select", "--models", "--model", "-s", "-m")), grab(Set("--exclude")), grab(Set("--selector"))))
  }

  def parse(file: String, source: String): Vector[Pipeline] = {
    val top = tokenize(source); val env = environment(top); val dag = dagDefinition(top).getOrElse(Vector.empty)
    val pipelines = mutable.LinkedHashMap.empty[String, Pipeline]
    val base = file.replace('\\', '/').split('/').last.stripSuffix(".py")
    def text(kv: KV): String = asText(eval(kv.value, env)).getOrElse("")
    def find(entries: Vector[KV], keys: Set[String]): Option[KV] = entries.find(k => keys(k.key))
    def pipeline(chain: Vector[Vector[KV]]): Pipeline = {
      val flat = chain.flatten ++ dag
      val name = find(flat, NameKeys).map(text).filter(_.nonEmpty).getOrElse(base)
      val schedule = find(flat, ScheduleKeys).map(k => Option(text(k)).filter(_.nonEmpty).getOrElse(k.value.map(_.value).mkString.take(60))).getOrElse("")
      val p = pipelines.getOrElse(name, Pipeline(name, schedule, Vector.empty, Vector.empty))
      val result = if (p.schedule.isEmpty) p.copy(schedule = schedule) else p
      pipelines(name) = result; result
    }
    def visit(items: Vector[Tok], chain: Vector[Vector[KV]], depth: Int): Unit = if (depth <= 100) {
      items.indices.foreach { i =>
        ProgressManager.checkCanceled()
        val t = items(i)
        if (t.kind == "str") shell(t.value).foreach { case (select, exclude, named) =>
          if (select.nonEmpty || exclude.nonEmpty || named.nonEmpty) {
            val p = pipeline(chain)
            val task = find(chain.reverse.flatten, Set("task_id", "name")).map(text).filter(_.nonEmpty).getOrElse(p.name)
            val site = Site(select, exclude, named, t.value.contains("{{") || t.formatted && t.value.contains('{'), task, "", t.offset, t.line)
            pipelines(p.name) = p.copy(sites = p.sites :+ site)
          }
        }
        if (t.kind == "group") {
          val entries = if (items.lift(i - 2).exists(_.value == "def")) Vector.empty else kvs(t)
          val ancestors = chain.flatten
          val next = if (entries.nonEmpty) chain :+ entries else chain
          def inherited(keys: Set[String]): Option[KV] = find(entries, keys).orElse(find(ancestors, keys))
          if (entries.nonEmpty && t.value != "[") {
            val dataset = inherited(DatasetKeys).flatMap(k => eval(k.value, env) match { case Text(s) if s.trim.nonEmpty => Some(s.trim); case _ => None })
            val ingests = entries.flatMap { k =>
              val strong = if (TableKeys(k.key)) eval(k.value, env) match {
                case Text(s) if s.matches("[A-Za-z_]\\w*") => Vector(Ingest(dataset, s, k.offset, k.line, k.key))
                case _ => Vector.empty
              } else Vector.empty
              val full = if (FullKeys(k.key.toLowerCase) && k.value.size == 1 && k.value.head.kind == "str") k.value.head.value.trim match {
                case FullRef(ds, table) => Vector(Ingest(Some(ds), table, k.offset, k.line, k.key))
                case _ => Vector.empty
              } else Vector.empty
              strong ++ full
            }
            if (ingests.nonEmpty) {
              val p = pipeline(Vector(dag))
              pipelines(p.name) = p.copy(ingests = (p.ingests ++ ingests).distinctBy(r => (r.dataset, r.table)))
            }
            val select = find(entries, SelectKeys); val named = inherited(NamedKeys)
            if (select.nonEmpty || named.nonEmpty) {
              val sv = select.map(k => eval(k.value, env)).getOrElse(Unset)
              val xv = inherited(ExcludeKeys).map(k => eval(k.value, env)).getOrElse(Unset)
              val nv = named.map(k => eval(k.value, env)).getOrElse(Unset)
              val dynamic = flatten(sv).isEmpty || flatten(xv).isEmpty || flatten(nv).isEmpty
              val selection = asText(sv).getOrElse(""); val selector = asText(nv).getOrElse("")
              if (selection.nonEmpty || selector.nonEmpty || flatten(sv).isEmpty || flatten(nv).isEmpty) {
                val p = pipeline(next)
                val location = select.orElse(named).get
                val task = find(entries, Set("task_id", "name")).map(text).filter(_.nonEmpty).getOrElse(p.name)
                val indirect = inherited(IndirectKeys).map(_.value.map(_.value).mkString).getOrElse("")
                val site = Site(selection, asText(xv).getOrElse(""), selector, dynamic, task, indirect, location.offset, location.line)
                pipelines(p.name) = p.copy(sites = p.sites :+ site)
              }
            }
          }
          visit(t.items, next, depth + 1)
        }
      }
    }
    visit(top, Vector.empty, 0)
    pipelines.values.toVector.filter(p => p.sites.nonEmpty || p.ingests.nonEmpty)
  }
}

final class DbtAirflowRelatedProvider extends GotoRelatedProvider {
  override def getItems(context: DataContext): java.util.List[_ <: GotoRelatedItem] =
    DbtEditorAction.caret(context).toVector.flatMap { case (project, file, offset) =>
      val w = DbtWorkspace.get(project)
      DbtResourceAction.at(w, file, offset).toVector.flatMap(DbtAirflow.context(w, _)).flatMap { task =>
        val definition = Option(LocalFileSystem.getInstance.findFileByPath(task.evidence))
          .flatMap(f => Option(PsiManager.getInstance(w.project).findFile(f)))
          .flatMap(f => Option(f.findElementAt(task.offset))).map { at =>
            new GotoRelatedItem(at, "Airflow DAGs / tasks") {
              override def getCustomName: String = task.dag + " / " + task.task
              override def getCustomContainerName: String = task.evidence + ":" + task.line
            }
          }.toVector
        val browser = task.url.toVector.map { url =>
          new GotoRelatedItem(null, "Airflow task links", -1) {
            override def getCustomName: String = "Open " + task.dag + " / " + task.task
            override def getCustomContainerName: String = task.schedule
            override def navigate(): Unit = BrowserUtil.browse(url)
          }
        }
        definition ++ browser
      }
    }.asJava
}

final class DbtAirflowAction extends AnAction {
  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    DbtUi.background(project, "Discover local dbt Airflow context") { _ => DbtUi.read { val w = DbtWorkspace.get(project); (DbtAirflow.tasks(w), DbtAirflow.problems(w)) } } { case (tasks, problems) =>
      val status = DbtUi.textArea((Vector(if (tasks.isEmpty) "No Airflow pipelines detected. Choose local DAG folders in Settings > dbt Workspace > Integrations." else "Detected from local DAG Python files; Python is never executed.") ++ problems).mkString("\n"))
      val list = new JList[String](tasks.map(t => t.dag + " / " + t.task + " · " + t.project + " · " + t.selection + " exclude: " + t.exclude +
        " · " + t.kind + (if (t.schedule.nonEmpty) " · Schedule: " + t.schedule else "") +
        " · " + t.resources.map(_.uniqueId).mkString(", ") + " · " + t.problems.mkString("; ") + " · Source: " + t.evidence + ":" + t.line +
        (if (t.ingestion.nonEmpty) (if (t.kind == "ingestion") " · ingestion destinations: " else " · upstream ingestion pipelines: ") + t.ingestion.mkString(", ") else "")).toArray)
      def selected: Option[DbtAirflowTask] = DbtUi.selected(list, tasks)
      DbtUi.dialog(project, "dbt Airflow Context", new JScrollPane(list), north = status, south = DbtUi.buttons(
        DbtUi.button("Open DAG definition") { selected.foreach(t => DbtUi.open(project, t.evidence, t.offset)) },
        DbtUi.button("Open Airflow task") { selected match {
          case Some(t) if t.url.nonEmpty => t.url.foreach(BrowserUtil.browse)
          case _ => Messages.showInfoMessage(project, "Set Airflow URL in Settings > dbt Workspace > Integrations and choose a task.", "Airflow context")
        } },
        DbtUi.button("Configure…") { DbtSettingsConfigurable.showIntegrations(project) }))
    }
  }
}
