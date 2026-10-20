package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.progress.ProgressManager
import scala.collection.mutable
import scala.jdk.CollectionConverters._

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
