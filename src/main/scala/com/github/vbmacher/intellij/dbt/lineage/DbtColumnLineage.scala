package com.github.vbmacher.intellij.dbt.lineage

import com.github.vbmacher.intellij.dbt.navigation.{DbtSqlAnalysis, DbtSqlColumn}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key

private[dbt] final case class DbtColumnEdge(resource: DbtSymbol, column: String, input: DbtSymbol, direct: Boolean,
  expression: String, complete: Boolean, kind: String = "value")
private[dbt] object DbtColumnLineage {
  private val Cache = Key.create[Vector[DbtColumnEdge]]("dbt.column.lineage")
  def edges(w: DbtWorkspace): Vector[DbtColumnEdge] = Option(w.getUserData(Cache)).getOrElse {
    val result = w.symbols.filter(s => s.kind == "model" && s.isCode && s.isEnabled).flatMap { resource =>
      ProgressManager.checkCanceled()
      DbtSqlAnalysis.get(w, resource.file).toVector.flatMap { analysis =>
        def inputs(column: DbtSqlColumn, seen: Set[Int]): Vector[(DbtSymbol, Boolean)] = column.inputs.flatMap { input =>
          val local = if (input.kind == "sql_projection" && input.file == resource.file)
            analysis.intermediates.find(c => c.range == input.range && c.name == input.name) else None
          local match {
            case Some(parent) if !seen(parent.range.getStartOffset) => inputs(parent, seen + parent.range.getStartOffset)
              .map { case (s, direct) => s -> (direct && column.direct && column.complete) }
            case Some(_) => Vector.empty
            case None => Vector(input -> (column.direct && column.complete))
          }
        }
        analysis.columns.flatMap(c => inputs(c, Set.empty).map { case (input, direct) =>
          DbtColumnEdge(resource, c.name, input, direct, c.expression, c.complete)
        } ++ c.contextInputs.map { case (input, kind) => DbtColumnEdge(resource, c.name, input, direct = false,
          kind + " dependency", c.complete, kind) })
      }
    }
    w.putUserData(Cache, result); result
  }
  def upstream(w: DbtWorkspace, resource: DbtSymbol, column: String): Vector[DbtColumnEdge] = edges(w).filter(e =>
    e.resource.sameResource(resource) && w.owner(e.resource) == w.owner(resource) && e.column.equalsIgnoreCase(column))

  def inherited(w: DbtWorkspace, resource: DbtSymbol): Vector[(String, String, DbtSymbol)] = {
    val existing = DbtResourceData.get(w, resource).columns.map(c => c.name -> c.description).toMap
    edges(w).filter(e => e.kind == "value" && e.resource.sameResource(resource) && w.owner(e.resource) == w.owner(resource)).groupBy(_.column).toVector
      .sortBy(_._1).flatMap { case (column, candidates) =>
        val inputs = candidates.map(_.input).distinctBy(_.id)
        if (existing.getOrElse(column, "").nonEmpty || !candidates.forall(e => e.direct && e.complete) || inputs.size != 1) Vector.empty
        else inputs.headOption.toVector.flatMap { input =>
          input.attributes.get("description").filter(s => s.nonEmpty && !s.contains("{{")).map(text => (column, text, input))
        }
      }
  }
}
