package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{Gson, JsonArray, JsonObject}
import com.intellij.openapi.progress.ProgressManager
import scala.collection.mutable
import scala.jdk.CollectionConverters._

private[ide] final case class DbtGraphSnapshot(payload: JsonObject, targets: Map[String, DbtSymbolElement])
private[ide] object DbtGraphData {
  private val gson = new Gson
  def key(w: DbtWorkspace, s: DbtSymbol): String = w.owner(s).map(_.directory.getPath).getOrElse("") + "|" + s.uniqueId

  def build(w: DbtWorkspace, focus: Option[String], upstream: Int, downstream: Int, exposures: Boolean): DbtGraphSnapshot = {
    val resources = w.symbols.filter(s => DbtResourceData.GraphKinds(s.kind) && s.isEnabled && (exposures || s.kind != "exposure"))
      .groupBy(key(w, _)).values.toVector.flatMap { candidates =>
        val source = candidates.filterNot(_.metadata)
        val preferred = if (source.nonEmpty) source else candidates
        val code = preferred.filter(_.isCode)
        if (code.nonEmpty) code else preferred.filterNot(s => s.isProperty && preferred.exists(t => t != s && !t.isProperty))
      }
    // Resource ids include the declaration location when there are genuinely ambiguous declarations.
    val groups = resources.groupBy(key(w, _))
    def id(s: DbtSymbol): String = {
      val k = key(w, s)
      if (groups.getOrElse(k, Vector.empty).size > 1) k + "|" + s.id else k
    }
    val byId = resources.map(s => id(s) -> s).toMap
    def canonical(s: DbtSymbol): Vector[String] = groups.getOrElse(key(w, s), Vector.empty).map(id)
    val edges = DbtLineage.edges(w).filter(e => DbtResourceData.GraphKinds(e.from.kind) && DbtResourceData.GraphKinds(e.to.kind))
      .flatMap(e => for (from <- canonical(e.to); to <- canonical(e.from) if from != to) yield (from, to, e.ambiguous)).distinct
    val start = focus.filter(byId.contains).orElse(focus.flatMap(k => resources.find(s => key(w, s) == k).map(id)))
    def walk(down: Boolean, depth: Int): Set[String] = {
      val adjacency = edges.groupMap(e => if (down) e._1 else e._2)(e => if (down) e._2 else e._1)
      val seen = mutable.Set.empty[String] ++ start
      var frontier = start.toVector
      var level = 0
      while (frontier.nonEmpty && level < depth) {
        ProgressManager.checkCanceled()
        frontier = frontier.flatMap(s => adjacency.getOrElse(s, Vector.empty)).filter(seen.add)
        level += 1
      }
      seen.toSet
    }
    val visible = if (start.isEmpty) byId.keySet else walk(down = false, upstream) ++ walk(down = true, downstream)
    val nodes = new JsonArray
    resources.filter(s => visible(id(s))).sortBy(s => (s.namespace, s.name, s.version)).foreach { s =>
      ProgressManager.checkCanceled()
      val data = DbtResourceData.get(w, s)
      val node = new JsonObject
      node.addProperty("id", id(s)); node.addProperty("name", s.name + s.version.map(" v" + _).getOrElse(""))
      node.addProperty("kind", s.kind); node.addProperty("schema", data.attributes.getOrElse("schema", ""))
      node.addProperty("package", s.namespace); node.addProperty("materialized", data.attributes.getOrElse("materialized", ""))
      node.addProperty("description", data.description); node.addProperty("focus", start.contains(id(s)))
      node.addProperty("manifest", data.attributes.contains("artifact-project"))
      node.addProperty("candidate", groups(key(w, s)).size > 1)
      nodes.add(node)
    }
    val links = new JsonArray
    edges.filter(e => visible(e._1) && visible(e._2)).groupBy(e => (e._1, e._2)).toVector.sortBy(_._1).foreach { case ((from, to), candidates) =>
      val edge = new JsonObject; edge.addProperty("source", from); edge.addProperty("target", to)
      edge.addProperty("candidate", candidates.exists(_._3)); links.add(edge)
    }
    val boundary = new JsonArray
    Vector(false, true).foreach { down =>
      val remaining = if (start.isEmpty) Set.empty[String] else walk(down, Int.MaxValue) -- visible
      if (remaining.nonEmpty) {
        val b = new JsonObject; b.addProperty("direction", if (down) "downstream" else "upstream")
        b.addProperty("count", remaining.size)
        b.add("anchors", gson.toJsonTree(edges.collect {
          case (from, to, _) if visible(from) && remaining(to) => from
          case (from, to, _) if remaining(from) && visible(to) => to
        }.distinct.asJava)); boundary.add(b)
      }
    }
    val payload = new JsonObject; payload.add("nodes", nodes); payload.add("edges", links); payload.add("boundary", boundary)
    payload.addProperty("focus", start.getOrElse(""))
    payload.addProperty("empty", if (resources.isEmpty) "No dbt resources found. Open a dbt project or check its paths and artifact settings." else "")
    DbtGraphSnapshot(payload, byId.view.mapValues(_.element).toMap)
  }

  def documentation(w: DbtWorkspace, s: DbtSymbol): JsonObject = {
    val data = DbtResourceData.get(w, s)
    val doc = new JsonObject
    doc.addProperty("name", s.presentableText); doc.addProperty("kind", s.kind); doc.addProperty("description", data.description)
    doc.addProperty("source", s.file.getVirtualFile.getPath); doc.addProperty("logical", s.relationName)
    doc.addProperty("rawSql", data.rawSql.orNull); doc.addProperty("compiledSql", data.compiledSql.orNull)
    doc.add("tags", gson.toJsonTree(data.tags.asJava)); doc.add("attributes", gson.toJsonTree(data.attributes.asJava))
    val columns = new JsonArray
    data.columns.foreach { c =>
      val col = new JsonObject; col.addProperty("name", c.name); col.addProperty("type", c.dataType); col.addProperty("description", c.description)
      col.add("declarations", gson.toJsonTree(c.declarations.asJava)); columns.add(col)
    }
    doc.add("columns", columns)
    val tests = new JsonArray
    data.tests.foreach { t => val test = new JsonObject; test.addProperty("name", t.name); test.addProperty("column", t.column); tests.add(test) }
    doc.add("tests", tests)
    val (upstream, downstream) = DbtDocumentation.dependencies(w, s)
    doc.add("upstream", gson.toJsonTree(upstream.asJava))
    doc.add("downstream", gson.toJsonTree(downstream.asJava))
    doc
  }
}
