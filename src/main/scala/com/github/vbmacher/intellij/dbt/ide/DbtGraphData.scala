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
    val evidence = w.project.getService(classOf[DbtExecutionEvidence])
    val testDefinitions = DbtTestDiscovery.discover(w)
    val executionRuns = w.roots.map(w.executionRoot).distinct.map(r => r.directory.getPath -> evidence.runs(r)).toMap
    val nodes = new JsonArray
    resources.filter(s => visible(id(s))).sortBy(s => (s.namespace, s.name, s.version)).foreach { s =>
      ProgressManager.checkCanceled()
      val data = DbtResourceData.get(w, s)
      val node = new JsonObject
      node.addProperty("id", id(s)); node.addProperty("name", s.name + s.version.map(" v" + _).getOrElse(""))
      node.addProperty("kind", s.kind); node.addProperty("schema", data.attributes.getOrElse("schema", ""))
      node.addProperty("package", s.namespace); node.addProperty("materialized", data.attributes.getOrElse("materialized", ""))
      node.addProperty("description", data.description); node.addProperty("focus", start.contains(id(s)))
      node.addProperty("folder", w.owner(s).flatMap(_.relative(s.file.getVirtualFile)).getOrElse("").split('/').dropRight(1).mkString("/"))
      node.add("tags", gson.toJsonTree(data.tags.asJava))
      node.addProperty("manifest", data.attributes.contains("artifact-project"))
      node.addProperty("candidate", groups(key(w, s)).size > 1)
      val runs = w.owner(s).map(w.executionRoot).flatMap(r => executionRuns.get(r.directory.getPath)).getOrElse(Vector.empty)
      node.add("execution", evidence.overlay(s, runs, testDefinitions))
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
    doc.addProperty("name", DbtDocumentation.displayName(s)); doc.addProperty("kind", s.kind); doc.addProperty("description", data.description)
    doc.addProperty("source", s.file.getVirtualFile.getPath); doc.addProperty("logical", s.relationName)
    doc.addProperty("editable", !s.metadata && s.file.isWritable && w.owner(s).exists(w.packageOwner(_).isEmpty))
    doc.addProperty("rawSql", data.rawSql.orNull); doc.addProperty("compiledSql", data.compiledSql.orNull)
    doc.add("tags", gson.toJsonTree(data.tags.asJava)); doc.add("attributes", gson.toJsonTree(data.attributes.asJava))
    val columns = new JsonArray
    data.columns.foreach { c =>
      val col = new JsonObject; col.addProperty("name", c.name); col.addProperty("type", c.dataType); col.addProperty("description", c.description)
      col.add("declarations", gson.toJsonTree(c.declarations.asJava)); columns.add(col)
      val origins = new JsonArray
      com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage.upstream(w, s, c.name).foreach { edge =>
        val origin = new JsonObject
        origin.addProperty("resource", edge.input.namespace + "." + edge.input.owner)
        origin.addProperty("column", edge.input.name); origin.addProperty("direct", edge.direct)
        origin.addProperty("kind", edge.kind)
        origin.addProperty("complete", edge.complete); origin.addProperty("expression", edge.expression)
        origin.addProperty("path", edge.input.file.getVirtualFile.getPath); origin.addProperty("offset", edge.input.range.getStartOffset)
        origins.add(origin)
      }
      col.add("origins", origins)
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

  def columnPaths(w: DbtWorkspace, resource: DbtSymbol, column: String): JsonObject = {
    val edges = com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage.edges(w)
    val resources = w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind)).distinctBy(s => (key(w, s)))
    def owner(c: DbtSymbol): Option[DbtSymbol] = resources.find(r => r.namespace == c.namespace &&
      r.relationName == c.owner && r.version == c.version && w.owner(r) == w.owner(c))
    val seen = mutable.Set(key(w, resource) -> column.toLowerCase)
    val lines = mutable.ArrayBuffer.empty[String]
    def walk(up: Boolean): Unit = {
      val visited = mutable.Set(key(w, resource) -> column.toLowerCase)
      var frontier = Vector(resource -> column)
      while (frontier.nonEmpty && visited.size < 1000) {
      ProgressManager.checkCanceled()
      val next = frontier.flatMap { case (model, name) =>
        edges.flatMap { edge =>
          val upstream = edge.resource.sameResource(model) && w.owner(edge.resource) == w.owner(model) && edge.column.equalsIgnoreCase(name)
          val downstream = owner(edge.input).exists(r => r.sameResource(model) && w.owner(r) == w.owner(model)) && edge.input.name.equalsIgnoreCase(name)
          if (if (up) upstream else downstream) {
            lines += s"${edge.input.namespace}.${edge.input.owner}.${edge.input.name} -> ${edge.resource.presentableText}.${edge.column}" +
              (if (edge.kind != "value") " (" + edge.kind + " dependency)" else if (edge.direct) " (passthrough)" else " (" + edge.expression + ")") + (if (edge.complete) "" else " [partial]")
            if (up) owner(edge.input).map(_ -> edge.input.name).toVector else Vector(edge.resource -> edge.column)
          } else Vector.empty
        }
      }
      frontier = next.filter { case (s, name) => visited.add(key(w, s) -> name.toLowerCase) }
      }
      seen ++= visited
    }
    walk(up = true); walk(up = false)
    val result = new JsonObject
    result.add("ids", gson.toJsonTree(seen.toVector.map(_._1).distinct.asJava))
    result.addProperty("text", if (lines.isEmpty) "No proven column paths. Schemas or supported SQL may be unavailable." else lines.distinct.mkString("\n"))
    result
  }
}
