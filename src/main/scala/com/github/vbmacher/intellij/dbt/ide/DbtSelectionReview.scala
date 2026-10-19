package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonParser
import scala.util.Try

/** Immutable selection data: resolving and completing a selector never launches dbt. */
private[ide] object DbtSelectionReview {
  def expression(w: DbtWorkspace, root: DbtRoot, selected: Option[DbtRunSelection]): String =
    Option(root.runOptions.selection.trim).filter(_.nonEmpty).getOrElse(selected
      .filter(s => s.path.nonEmpty && s.path != root.directory.getPath)
      .map(DbtRunSelection.selectors(w, _).mkString(" ")).getOrElse(""))

  /** Expand named terms before passing compound selections or exclusions to the CLI. */
  def cliTerms(w: DbtWorkspace, root: DbtRoot, text: String): Either[String, Vector[String]] = {
    val terms = text.trim.split("\\s+").toVector.filter(_.nonEmpty)
    val named = terms.flatMap(_.split(",", -1)).exists(term =>
      term.stripPrefix("@").replaceFirst("^[0-9]*\\+", "").startsWith("selector:"))
    if (!named) Right(terms)
    else {
      val resolved = DbtSelection.resolve(w, root, text)
      if (resolved.complete) Right(resolved.resources.map(DbtSelection.selector(w, _)).distinct)
      else Left("Compound named-selector expressions must resolve completely before running dbt. " +
        "Use a locally resolvable named selector, or ordinary CLI terms for explicit dbt ls resolution.\n" +
        resolved.problems.mkString("\n"))
    }
  }

  def completions(w: DbtWorkspace, root: DbtRoot): Vector[String] = {
    val resources = DbtSelection.resolve(w, root, "").resources
    val named = w.rootFile(root, "selectors.yml").flatMap(DbtYaml.mapping).toVector
      .flatMap(DbtYaml.items(_, "selectors")).flatMap(DbtYaml.string(_, "name")).map("selector:" + _)
    (resources.map(DbtSelection.selector(w, _)) ++ resources.map(s => "package:" + s.namespace) ++
      resources.flatMap(s => com.github.vbmacher.intellij.dbt.project.DbtResourceData.get(w, s).attributes.get("tags").toVector.flatMap(_.split(",")).filter(_.nonEmpty).map("tag:" + _)) ++
      named ++ Vector("fqn:", "path:", "package:", "tag:", "group:", "source:", "selector:", "state:modified") ++
      Vector("model", "seed", "snapshot", "test", "source", "exposure").map("resource_type:" + _)).distinct.sorted
  }

  // Complete the current intersection/union term while retaining its graph-depth operators.
  def complete(text: String, caret: Int, candidates: Vector[String]): Vector[(String, String, Int)] = {
    val position = caret.max(0).min(text.length)
    val start = text.lastIndexWhere(c => c.isWhitespace || c == ',', position - 1) + 1
    val end = text.indexWhere(c => c.isWhitespace || c == ',', position) match {
      case -1 => text.length
      case i => i
    }
    val typed = text.substring(start, position)
    val prefix = "^[0-9]*\\+".r.findFirstIn(typed).getOrElse("")
    val suffix = "\\+[0-9]*$".r.findFirstIn(text.substring(position, end)).getOrElse("")
    candidates.filter(_.startsWith(typed.stripPrefix(prefix))).take(200).map { candidate =>
      val replacement = prefix + candidate + suffix
      (candidate, text.take(start) + replacement + text.drop(end), start + replacement.length)
    }
  }

  def cliResources(output: String): Either[String, Vector[String]] = {
    val rows = output.linesIterator.filter(_.trim.startsWith("{")).flatMap(line => Try {
      val json = JsonParser.parseString(line).getAsJsonObject
      Option(json.get("unique_id")).filter(_.isJsonPrimitive).map(_.getAsString)
    }.toOption.flatten).toVector.distinct
    if (rows.nonEmpty) Right(rows)
    else if (output.trim.isEmpty) Right(Vector.empty)
    else Left("dbt ls returned no resource records. Check raw process output; the selection may be empty or unsupported.")
  }
}
