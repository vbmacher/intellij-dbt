package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.{DbtLineage, DbtSelection}
import com.intellij.openapi.util.Key
import com.intellij.openapi.progress.ProgressManager
import com.google.gson.{JsonArray, JsonObject}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets

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
    val tasks = new JsonArray
    all.take(50).foreach { task =>
      val item = new JsonObject
      item.addProperty("id", task.id); item.addProperty("dag", task.dag); item.addProperty("task", task.task)
      item.addProperty("project", task.project); item.addProperty("projectRoot", task.projectRoot.getOrElse(""))
      val matches = selected(w, resource, task)
      item.addProperty("selected", matches)
      item.addProperty("status", if (!matches) "Unresolved selection — association unknown" else if (task.problems.nonEmpty) "Partial detected selection" else if (task.kind == "ingestion") "Detected ingestion destination" else "Detected selection")
      item.addProperty("schedule", task.schedule); item.addProperty("kind", task.kind); item.addProperty("indirect", task.indirect)
      item.addProperty("selection", task.selection.take(4000)); item.addProperty("exclude", task.exclude.take(4000))
      item.addProperty("path", task.evidence); item.addProperty("offset", task.offset)
      item.addProperty("line", task.line)
      task.url.foreach(item.addProperty("url", _))
      val ingestion = new JsonArray; task.ingestion.take(100).foreach(ingestion.add); item.add("ingestion", ingestion)
      val problems = new JsonArray; task.problems.foreach(p => problems.add(p.take(2000))); item.add("problems", problems)
      tasks.add(item)
    }
    val result = new JsonObject
    result.add("tasks", tasks); result.addProperty("truncated", all.size > 50)
    result.addProperty("selectedCount", all.count(selected(w, resource, _)))
    result.addProperty("unresolvedCount", all.count(task => !selected(w, resource, task)))
    result
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
