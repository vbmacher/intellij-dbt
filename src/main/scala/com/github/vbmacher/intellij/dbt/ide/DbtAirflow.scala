package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.util.Key
import com.intellij.openapi.progress.ProgressManager
import com.google.gson.{JsonArray, JsonObject}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets

private[dbt] final case class DbtAirflowTask(dag: String, task: String, project: String, selection: String,
  exclude: String, resources: Vector[DbtSymbol], evidence: String, offset: Int, ingestion: Vector[String],
  url: Option[String], problems: Vector[String], projectRoot: Option[String] = None, definitionRoot: String = "") {
  def id: String = evidence + ":" + offset + "|" + dag + "|" + task + "|" + project
}
private[ide] final case class DbtAirflowDestination(path: String, offset: Int, url: Option[String] = None)

/** Explicit YAML declarations only. Python DAGs are never imported or evaluated. */
private[dbt] object DbtAirflow {
  val FileName = ".dbt-airflow.yml"
  private val Tasks = Key.create[Vector[DbtAirflowTask]]("dbt.airflow.tasks")
  private final case class ContextIndex(selected: Map[String, Vector[DbtAirflowTask]], unresolved: Map[String, Vector[DbtAirflowTask]])
  private val Contexts = Key.create[ContextIndex]("dbt.airflow.contexts")
  private def resourceKey(w: DbtWorkspace, resource: DbtSymbol): String = DbtImpact.key(w, resource) + "|" + resource.id

  def tasks(w: DbtWorkspace): Vector[DbtAirflowTask] = Option(w.getUserData(Tasks)).getOrElse {
    val result = w.roots.flatMap { root =>
    ProgressManager.checkCanceled()
    w.rootFile(root, FileName).toVector.filter(_.getTextLength <= 1000000).flatMap { file =>
      DbtYaml.mapping(file).toVector.flatMap { mapping =>
        val base = DbtYaml.string(mapping, "airflow_url").filter(s => safeUrl(s).nonEmpty)
        DbtYaml.items(mapping, "tasks").take(1000).map { entry =>
          ProgressManager.checkCanceled()
          def value(key: String): String = DbtYaml.string(entry, key).getOrElse("")
          val project = value("project"); val dag = value("dag"); val task = value("task")
          val selection = value("select"); val exclude = value("exclude")
          val selector = value("selector")
          val expression = if (selector.nonEmpty) "selector:" + selector else selection
          val roots = w.roots.filter(r => r.name == project && w.executionRoot(r) == w.executionRoot(root))
          val invalid = Vector(
            Option.when(dag.isEmpty || task.isEmpty)("DAG and task are required."),
            Option.when(roots.size != 1)("Project is missing or ambiguous; use the owning project name."),
            Option.when(expression.trim.isEmpty)("Selection is unknown. Declare select or selector."),
            Option.when(selection.nonEmpty && selector.nonEmpty)("Choose select or selector, not both."),
            Option.when(Vector(expression, exclude).exists(s => s.contains("{{") || s.contains("${")))("Dynamic/runtime selection is unresolved.")
          ).flatten
          val resolved = if (invalid.nonEmpty) None else roots.headOption.map(DbtSelection.resolve(w, _, expression, exclude))
          val url = base.map(_.stripSuffix("/") + "/dags/" + encode(dag) + "/grid?task_id=" + encode(task))
          DbtAirflowTask(dag, task, project, expression, exclude, resolved.toVector.flatMap(_.resources),
            file.getVirtualFile.getPath, entry.getTextOffset, DbtYaml.strings(entry, "upstream_ingestion"), url,
            invalid ++ resolved.toVector.flatMap(_.problems),
            roots.headOption.filter(_ => roots.size == 1).map(_.directory.getPath), root.directory.getPath)
        }
      }
    }
    }
    w.putUserData(Tasks, result)
    result
  }

  /** Unresolved mappings supply project context, never a claim that this resource is selected. */
  def context(w: DbtWorkspace, resource: DbtSymbol): Vector[DbtAirflowTask] = {
    val index = Option(w.getUserData(Contexts)).getOrElse {
      val mappings = tasks(w)
      val selected = mappings.flatMap { task =>
        ProgressManager.checkCanceled()
        task.resources.map(resourceKey(w, _)).distinct.map(_ -> task)
      }.groupMap(_._1)(_._2)
      val unresolved = mappings.filter(_.problems.nonEmpty).flatMap(t => t.projectRoot.map(_ -> t)).groupMap(_._1)(_._2)
      val result = ContextIndex(selected, unresolved)
      w.putUserData(Contexts, result)
      result
    }
    (index.selected.getOrElse(resourceKey(w, resource), Vector.empty) ++
      w.owner(resource).toVector.flatMap(root => index.unresolved.getOrElse(root.directory.getPath, Vector.empty))).distinctBy(_.id)
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
      item.addProperty("status", if (!matches) "Unresolved selection — association unknown" else if (task.problems.nonEmpty) "Partial declared selection" else "Declared selection")
      item.addProperty("selection", task.selection.take(4000)); item.addProperty("exclude", task.exclude.take(4000))
      item.addProperty("path", task.evidence); item.addProperty("offset", task.offset)
      item.addProperty("line", w.files.find(_.getVirtualFile.getPath == task.evidence).map(_.getText.take(task.offset).count(_ == '\n') + 1).getOrElse(1))
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
    context(w, resource).find(_.id == mappingId).toRight("Airflow mapping changed or no longer applies to this resource. Refresh its details.")
      .flatMap { task => target match {
        case "definition" => Right(DbtAirflowDestination(task.evidence, task.offset))
        case "browser" => task.url.toRight("No Airflow URL is configured for this mapping. See Airflow task mappings in Configuration.")
          .map(url => DbtAirflowDestination(task.evidence, task.offset, Some(url)))
        case _ => Left("Unknown Airflow navigation action.")
      }}

  private def encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
  def safeUrl(text: String): Option[String] = scala.util.Try(new URI(text)).toOption.filter(u =>
    Set("http", "https")(Option(u.getScheme).getOrElse("")) && u.getHost != null && u.getUserInfo == null).map(_.toASCIIString)
}
