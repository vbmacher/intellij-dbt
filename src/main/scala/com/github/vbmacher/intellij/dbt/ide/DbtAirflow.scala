package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets

private[dbt] final case class DbtAirflowTask(dag: String, task: String, project: String, selection: String,
  exclude: String, resources: Vector[DbtSymbol], evidence: String, offset: Int, ingestion: Vector[String],
  url: Option[String], problems: Vector[String])

/** Explicit YAML declarations only. Python DAGs are never imported or evaluated. */
private[dbt] object DbtAirflow {
  val FileName = ".dbt-airflow.yml"
  def tasks(w: DbtWorkspace): Vector[DbtAirflowTask] = w.roots.flatMap { root =>
    w.rootFile(root, FileName).toVector.filter(_.getTextLength <= 1000000).flatMap { file =>
      DbtYaml.mapping(file).toVector.flatMap { mapping =>
        val base = DbtYaml.string(mapping, "airflow_url").filter(s => safeUrl(s).nonEmpty)
        DbtYaml.items(mapping, "tasks").take(1000).map { entry =>
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
            invalid ++ resolved.toVector.flatMap(_.problems))
        }
      }
    }
  }
  private def encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
  def safeUrl(text: String): Option[String] = scala.util.Try(new URI(text)).toOption.filter(u =>
    Set("http", "https")(Option(u.getScheme).getOrElse("")) && u.getHost != null && u.getUserInfo == null).map(_.toASCIIString)
}
