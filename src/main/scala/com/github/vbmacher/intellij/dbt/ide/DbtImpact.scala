package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnLineage, DbtLineage}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.syntax.{DbtYaml, DbtSyntax}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFileFactory
import org.jetbrains.yaml.YAMLFileType
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[dbt] final case class DbtImpactResource(id: String, path: String, fingerprint: String, columns: Vector[String])
private[dbt] final case class DbtImpactChange(resource: DbtImpactResource, state: String, columns: Vector[String])
private[dbt] final case class DbtImpactResult(baseline: String, changes: Vector[DbtImpactChange],
  affected: Vector[(DbtSymbol, Vector[String])], selection: String, problems: Vector[String])

/** Git is used only to read committed blobs. Current PSI includes unsaved documents. */
private[dbt] object DbtImpact {
  private val MaxFiles = 1500
  private val MaxBytes = 2 * 1024 * 1024
  private[ide] def git(directory: String, arguments: Vector[String]): Either[String, String] = {
    var process: Process = null
    try {
      process = new ProcessBuilder((Vector("git", "-C", directory) ++ arguments): _*).redirectErrorStream(true).start()
      val running = process
      val reader = java.util.concurrent.CompletableFuture.supplyAsync(() => {
        val stream = running.getInputStream
        try stream.readNBytes(MaxBytes + 1) finally stream.close()
      })
      if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); Left("Git read timed out.") }
      else {
        val bytes = reader.get(2, TimeUnit.SECONDS)
        if (bytes.length > MaxBytes) Left("Git response exceeds the 2 MiB analysis limit.")
        else if (process.exitValue() != 0) Left("Git revision or repository is unavailable.")
        else Right(new String(bytes, StandardCharsets.UTF_8))
      }
    } catch { case NonFatal(_) => Left("Git read is unavailable.") }
    finally if (process != null && process.isAlive) process.destroyForcibly()
  }
  def key(w: DbtWorkspace, s: DbtSymbol): String = w.owner(s).map(_.directory.getPath).getOrElse("") + "|" + s.uniqueId
  private def digest(text: String): String = java.util.HexFormat.of().formatHex(
    java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
  private def resources(w: DbtWorkspace): Vector[DbtSymbol] = w.symbols.filter(s => !s.metadata && (
    s.isCode || s.attributes.contains("block-start") || Set("exposure", "unit_test", "source_table")(s.kind) || s.isProperty && s.kind != "source_table" && s.kind != "source" &&
      !w.symbols.exists(c => !c.isProperty && c.sameResource(s) && w.owner(c) == w.owner(s)))).sortBy(_.isProperty).distinctBy(key(w, _))

  private def blockText(text: String, block: DbtSyntax.Block): String = {
    val endTag = block.tagName.map("end" + _)
    val end = DbtSyntax.blocks(text).find(b => endTag.exists(b.isTag) && b.range.getStartOffset >= block.range.getEndOffset)
      .map(_.range.getEndOffset).getOrElse(text.length)
    text.substring(block.range.getStartOffset, end)
  }
  private def propertyText(s: DbtSymbol): String = s.mapping.map { mapping =>
    val inherited = if (s.version.isEmpty) "" else Option(com.intellij.psi.util.PsiTreeUtil.getParentOfType(mapping,
      classOf[org.jetbrains.yaml.psi.YAMLMapping], true)).filter(m => DbtYaml.value(m, "versions").nonEmpty)
      .map(_.getKeyValues.asScala.filterNot(_.getKeyText == "versions").map(_.getText).mkString).getOrElse("")
    mapping.getText + inherited
  }.getOrElse("")
  def current(w: DbtWorkspace): Vector[DbtImpactResource] = resources(w).map { s =>
    ProgressManager.checkCanceled()
    val properties = w.properties(s).map(propertyText).mkString("\n")
    val sourceContext = if (s.kind != "source_table") "" else w.symbols.find(p => p.kind == "source" &&
      p.name == s.owner && p.namespace == s.namespace && w.owner(p) == w.owner(s)).flatMap(_.mapping).map(m =>
      m.getKeyValues.asScala.filterNot(_.getKeyText == "tables").map(_.getText).mkString).getOrElse("")
    val sourceText = if (s.attributes.contains("block-start")) DbtSyntax.inFile(s.file).find(_.range.containsOffset(s.range.getStartOffset))
      .map(blockText(s.file.getText, _)).getOrElse(s.file.getText) else if (s.isCode) s.file.getText else if (s.mapping.nonEmpty) propertyText(s) else s.file.getText
    val text = sourceText + properties + sourceContext + w.owner(s).map(_.config.getContainingFile.getText).getOrElse("")
    DbtImpactResource(key(w, s), s.file.getVirtualFile.getPath, digest(text), (w.columns(s).map(_.name) ++ (if (s.isCode && s.file.getName.endsWith(".sql")) DbtNavigation.projectedColumns(s.file.getText).toVector.flatMap(_._1) else Vector.empty)).distinct)
  }
  def compare(before: Vector[DbtImpactResource], after: Vector[DbtImpactResource]): Vector[DbtImpactChange] = {
    val old = before.map(s => s.id -> s).toMap; val now = after.map(s => s.id -> s).toMap
    (old.keySet ++ now.keySet).toVector.sorted.flatMap { id => (old.get(id), now.get(id)) match {
      case (None, Some(n)) => Some(DbtImpactChange(n, "added", n.columns))
      case (Some(o), None) => Some(DbtImpactChange(o, "removed", o.columns))
      case (Some(o), Some(n)) if o.fingerprint != n.fingerprint || o.path != n.path =>
        Some(DbtImpactChange(n, if (o.fingerprint == n.fingerprint) "moved" else "changed", (o.columns ++ n.columns).distinct))
      case _ => None
    }}
  }
  def analyze(initial: DbtWorkspace, revision: String): Either[String, DbtImpactResult] = {
    val directory = Option(initial.project.getBasePath).getOrElse("")
    for {
      repository <- git(directory, Vector("rev-parse", "--show-toplevel")).map(_.trim)
      commit <- git(directory, Vector("rev-parse", "--verify", "--end-of-options", revision + "^{commit}")).map(_.trim)
      listed <- git(repository, Vector("ls-tree", "-rz", "--name-only", commit))
      paths = listed.split("\u0000").toVector.filter(p => Set("sql", "py", "csv", "yml", "yaml", "md", "jinja", "jinja2", "js").exists(e => p.endsWith("." + e)))
      relevant = paths.filter { p => initial.roots.exists { root =>
        val absolute = Paths.get(repository).resolve(p).toString
        val prefix = root.directory.getPath + "/"
        absolute.startsWith(prefix) && {
          val relative = absolute.stripPrefix(prefix)
          (relative == "dbt_project.yml" || root.pathKinds.exists(k => root.pathContains(k._2, relative))) &&
            !root.excludedPath(relative)
        }
      }}
      _ <- Either.cond(relevant.size <= MaxFiles, (), s"Baseline exceeds the $MaxFiles file analysis limit.")
      contents <- relevant.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) { (result, path) =>
        ProgressManager.checkCanceled()
        result.flatMap { m =>
          if (m.valuesIterator.map(_.length.toLong).sum > 32000000L) Left("Baseline exceeds the 32 MiB analysis limit.")
          else git(repository, Vector("show", s"$commit:$path")).map(text => m.updated(Paths.get(repository).resolve(path).toString, text))
        }
      }
    } yield DbtImpactUi.read {
      val w = DbtWorkspace.get(initial.project)
      val baseline = baselineResources(w, contents)
      val changes = compare(baseline, current(w))
      val edges = DbtLineage.edges(w)
      val downstream = edges.groupBy(e => key(w, e.to))
      val resourceById = w.symbols.sortBy(s => (s.metadata, s.isProperty)).groupBy(key(w, _)).view.mapValues(_.head).toMap
      val frontier = mutable.Queue.empty[(String, Vector[String])]
      changes.foreach(c => frontier.enqueue(c.resource.id -> Vector(c.resource.id)))
      val removed = changes.filter(_.state == "removed")
      resources(w).filter(_.isCode).foreach { consumer =>
        DbtSyntax.inFile(consumer.file).flatMap(DbtSyntax.calls).filter(c => c.name == "ref" && c.closed).foreach { call =>
          call.qualifiedName.foreach { case (namespace, name) =>
            val matches = removed.filter { change =>
              val prefix = "|model." + namespace.getOrElse(consumer.namespace) + "." + name
              change.resource.id.endsWith(prefix + call.version.flatten.map(".v" + _).getOrElse("")) ||
                call.version.isEmpty && change.resource.id.contains(prefix + ".v")
            }
            if (matches.size == 1) frontier.enqueue(key(w, consumer) -> Vector(matches.head.resource.id,
              "unresolved literal ref after deletion", key(w, consumer)))
          }
        }
      }
      val seen = mutable.Set.empty[String]
      val affected = mutable.ArrayBuffer.empty[(DbtSymbol, Vector[String])]
      while (frontier.nonEmpty && seen.size < 10000) {
        ProgressManager.checkCanceled()
        val (id, trace) = frontier.dequeue()
        if (seen.add(id)) {
          resourceById.get(id).foreach(s => affected += s -> trace)
          downstream.getOrElse(id, Vector.empty).foreach(e => frontier.enqueue(key(w, e.from) -> (trace :+ key(w, e.from))))
        }
      }
      // Also include column-derived dependencies when a model reference was absent from resource lineage.
      val columnEdges = DbtColumnLineage.edges(w)
      columnEdges.filter(e => changes.exists(c => c.resource.id.endsWith("|model." + e.input.namespace + "." + e.input.owner +
        e.input.version.map(".v" + _).getOrElse("")) && c.columns.contains(e.input.name))).foreach { e =>
        val trace = Vector(e.input.owner + "." + e.input.name, e.resource.uniqueId + "." + e.column + " via " + e.expression)
        if (!affected.exists(a => key(w, a._1) == key(w, e.resource))) affected += e.resource -> trace
      }
      val models = affected.map(_._1).filter(s => DbtSymbol.RefKinds(s.kind) && s.isEnabled).toVector.distinctBy(key(w, _))
      val selectors = models.map { s =>
        val folders = w.owner(s).flatMap(_.resourceFolders(s.file.getVirtualFile)).map(_._2).getOrElse(Vector.empty)
        "fqn:" + (Vector(s.namespace) ++ folders :+ s.name).mkString(".") + s.version.map(".v" + _).getOrElse("")
      }.distinct.mkString(" ")
      DbtImpactResult(commit, changes, affected.toVector, selectors, Vector(
        "Potential impact uses current static lineage; removed/dynamic consumers may be unresolved.",
        "Column changes list potentially changed declared/projected outputs; expression-level equivalence is not proven. Wildcards and unnamed expressions may be incomplete.") ++
        (if (contents.keys.exists(_.endsWith("dbt_project.yml")) && w.roots.exists(r =>
          contents.get(r.directory.getPath + "/dbt_project.yml").exists(_ != r.config.getContainingFile.getText)))
          Vector("Project configuration changed; baseline paths use current configured paths.") else Vector.empty))
    }
  }
  private[ide] def baselineResources(w: DbtWorkspace, contents: Map[String, String]): Vector[DbtImpactResource] = {
    val result = mutable.ArrayBuffer.empty[DbtImpactResource]
    w.roots.foreach { root =>
      val projectConfig = contents.getOrElse(root.directory.getPath + "/dbt_project.yml", root.config.getContainingFile.getText)
      val baselineConfig = PsiFileFactory.getInstance(w.project).createFileFromText("baseline-project.yml", YAMLFileType.YML, projectConfig)
      val projectName = DbtYaml.mapping(baselineConfig).flatMap(DbtYaml.string(_, "name")).getOrElse(root.name)
      val owned = contents.filter { case (path, _) => path.startsWith(root.directory.getPath + "/") &&
        !w.roots.exists(r => r != root && r.directory.getPath.startsWith(root.directory.getPath + "/") && path.startsWith(r.directory.getPath + "/")) &&
        !root.excludedPath(path.stripPrefix(root.directory.getPath + "/")) }
      val properties = owned.toVector.filter { case (p, _) => p.endsWith(".yml") || p.endsWith(".yaml") }.flatMap { case (path, text) =>
        val file = PsiFileFactory.getInstance(w.project).createFileFromText("baseline.yml", YAMLFileType.YML, text)
        DbtYaml.mapping(file).toVector.flatMap(m => DbtDeclarations.ResourceKeys.toVector.filterNot(_._2 == "source").flatMap { case (plural, kind) =>
          DbtYaml.items(m, plural).flatMap { entry => DbtYaml.string(entry, "name").toVector.flatMap { name =>
            val versions = DbtYaml.items(entry, "versions")
            (if (versions.isEmpty) Vector(None -> entry) else versions.map(v => DbtYaml.string(v, "v") -> v)).map { case (version, item) =>
              val physical = DbtYaml.string(item, "defined_in").getOrElse(name + version.map("_v" + _).getOrElse(""))
              (kind, name, version, physical, path, item.getText + (if (version.nonEmpty) entry.getKeyValues.asScala.filterNot(_.getKeyText == "versions").map(_.getText).mkString else ""), DbtYaml.items(if (DbtYaml.value(item, "columns").isEmpty) entry else item, "columns").flatMap(DbtYaml.string(_, "name")))
            }
          }}
        })
      }
      val code = owned.toVector.filter { case (p, _) => Vector(".sql", ".py", ".csv", ".js").exists(p.endsWith) }.flatMap { case (path, text) =>
        val relative = path.stripPrefix(root.directory.getPath + "/")
        val extension = path.substring(path.lastIndexOf('.') + 1)
        root.pathKinds.find { case (_, folder) => root.pathContains(folder, relative) }.filter { case (kind, folder) =>
          val fixture = kind == "test" && root.pathContains(folder.stripSuffix("/") + "/fixtures", relative) && Set("csv", "sql")(extension)
          val supported = kind match {
            case "model" => Set("sql", "py")(extension)
            case "seed" => extension == "csv"
            case "function" => Set("sql", "py", "js")(extension)
            case "snapshot" | "analysis" | "test" | "check" => extension == "sql"
            case _ => false
          }
          (supported || fixture) && !(kind == "snapshot" && DbtSyntax.blocks(text).exists(_.isTag("snapshot")))
        }.map { case (configuredKind, folder) =>
          val fixture = configuredKind == "test" && root.pathContains(folder.stripSuffix("/") + "/fixtures", relative) && Set("csv", "sql")(extension)
          val kind = if (fixture) "fixture" else configuredKind
          val physical = Paths.get(path).getFileName.toString.replaceFirst("\\.[^.]+$", "")
          val property = properties.find(p => p._1 == kind && p._4 == physical)
          val name = property.map(_._2).getOrElse(physical); val version = property.flatMap(_._3)
          val id = root.directory.getPath + "|" + kind + "." + projectName + "." + name + version.map(".v" + _).getOrElse("")
          DbtImpactResource(id, path, digest(text + property.map(_._6).getOrElse("") + projectConfig), (property.map(_._7).getOrElse(Vector.empty) ++ (if (path.endsWith(".sql")) DbtNavigation.projectedColumns(text).toVector.flatMap(_._1) else Vector.empty)).distinct)
        }
      }
      val blocks = owned.toVector.filter(p => Vector(".sql", ".md").exists(p._1.endsWith) && root.pathKinds.exists(k => root.pathContains(k._2, p._1.stripPrefix(root.directory.getPath + "/")))).flatMap { case (path, text) =>
        DbtSyntax.blocks(text).filter(b => b.tagName.exists(Set("macro", "test", "docs", "materialization", "snapshot"))).flatMap { block =>
          block.tokens.lift(1).map { name =>
            val kind = Map("macro" -> "macro", "test" -> "generic_test", "docs" -> "doc", "materialization" -> "materialization", "snapshot" -> "snapshot")(block.tagName.get)
            val property = properties.find(p => p._1 == kind && p._2 == name.image)
            DbtImpactResource(root.directory.getPath + "|" + kind + "." + projectName + "." + name.image,
              path, digest(blockText(text, block) + property.map(_._6).getOrElse("") + projectConfig),
                if (kind == "snapshot") (property.map(_._7).getOrElse(Vector.empty) ++ DbtNavigation.projectedColumns(blockText(text, block)).toVector.flatMap(_._1)).distinct else Vector.empty)
          }
        }
      }
      val sources = owned.toVector.filter { case (p, _) => p.endsWith(".yml") || p.endsWith(".yaml") }.flatMap { case (path, text) =>
        val file = PsiFileFactory.getInstance(w.project).createFileFromText("baseline.yml", YAMLFileType.YML, text)
        DbtYaml.mapping(file).toVector.flatMap(DbtYaml.items(_, "sources")).flatMap { source =>
          val inherited = source.getKeyValues.asScala.filterNot(_.getKeyText == "tables").map(_.getText).mkString
          for (name <- DbtYaml.string(source, "name").toVector; table <- DbtYaml.items(source, "tables"); tableName <- DbtYaml.string(table, "name").toVector)
            yield DbtImpactResource(root.directory.getPath + "|source." + projectName + "." + name + "." + tableName,
              path, digest(table.getText + inherited + projectConfig), DbtYaml.items(table, "columns").flatMap(DbtYaml.string(_, "name")))
        }
      }
      result ++= code ++ blocks ++ sources
      properties.filterNot(p => (code ++ blocks).exists(_.id == root.directory.getPath + "|" + p._1 + "." + projectName + "." + p._2 + p._3.map(".v" + _).getOrElse("")))
        .foreach { p => result += DbtImpactResource(root.directory.getPath + "|" + p._1 + "." + projectName + "." + p._2 + p._3.map(".v" + _).getOrElse(""),
          p._5, digest(p._6 + p._6 + projectConfig), p._7) }
    }
    result.toVector
  }
}
