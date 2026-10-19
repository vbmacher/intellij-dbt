package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{Gson, JsonObject, JsonParser}
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.util.Try

private[ide] final case class DbtExecutionResult(id: String, status: String, message: String, failures: Long = 0L,
  relation: String = "", duration: Long = 0L)
private[ide] final case class DbtExecutionRun(context: Vector[String], target: String, invocation: String, timestamp: String,
  artifact: String, results: Vector[DbtExecutionResult], problem: String = "", active: Boolean = false, root: String = "")

/** Execution evidence is separate from declarations. Only changed artifacts from this invocation receive provenance. */
private[ide] object DbtExecutionEvidence {
  private val gson = new Gson
  def string(o: JsonObject, key: String): String = Option(o.get(key)).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")
  def obj(o: JsonObject, key: String): JsonObject = Option(o.get(key)).filter(_.isJsonObject).map(_.getAsJsonObject).getOrElse(new JsonObject)
  def objects(o: JsonObject, key: String): Vector[JsonObject] = Option(o.get(key)).toVector.flatMap { e =>
    if (e.isJsonArray) e.getAsJsonArray.asScala.toVector.filter(_.isJsonObject).map(_.getAsJsonObject)
    else if (e.isJsonObject) e.getAsJsonObject.entrySet.asScala.toVector.map(_.getValue).filter(_.isJsonObject).map(_.getAsJsonObject)
    else Vector.empty
  }
  def json(path: Path): Option[JsonObject] = Try {
    require(Files.size(path) <= 25000000L, "Artifact exceeds 25 MB")
    JsonParser.parseString(Files.readString(path)).getAsJsonObject
  }.toOption
  def fingerprint(path: Path): Option[String] = Try {
    require(Files.size(path) <= 25000000L, "Artifact exceeds 25 MB")
    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(b => f"${b & 255}%02x").mkString
  }.toOption
  def normalize(status: String): String = status.toLowerCase match {
    case "pass" | "success" => "passed"
    case "fail" => "failed"
    case "warn" => "warning"
    case "runtime error" | "error" => "error"
    case "skipped" | "skip" => "skipped"
    case "running" | "started" | "compiling" | "executing" => "running"
    case "cancelled" | "canceled" => "canceled"
    case _ => "unknown"
  }
  def parse(path: Path, context: Vector[String], target: String, expectedInvocation: String = ""): DbtExecutionRun = {
    def invalid(reason: String) = DbtExecutionRun(context, target, "", "", path.toString, Vector.empty, reason)
    json(path).fold(invalid(if (Files.exists(path)) "Malformed execution artifact" else "Not run / result unavailable")) { top =>
      val metadata = obj(top, "metadata")
      val schema = string(metadata, "dbt_schema_version")
      val invocation = string(metadata, "invocation_id")
      val generated = string(metadata, "generated_at")
      val artifactTarget = string(obj(top, "args"), "target")
      if (!schema.matches("https://schemas\\.getdbt\\.com/dbt/(run-results/v[4-6]|sources/v[1-3])\\.json")) invalid("Unsupported execution artifact schema")
      else if (artifactTarget.nonEmpty && artifactTarget != target) invalid("Artifact target does not match the selected target")
      else if (expectedInvocation.nonEmpty && invocation != expectedInvocation) invalid("Artifact invocation does not match the active run")
      else if (invocation.isEmpty || Try(Instant.parse(generated)).isFailure) invalid("Artifact run identity or timestamp unavailable")
      else {
        val completeRecords = Option(top.get("results")).exists { records =>
          if (records.isJsonArray) records.getAsJsonArray.asScala.forall(_.isJsonObject)
          else if (records.isJsonObject) records.getAsJsonObject.entrySet.asScala.forall(_.getValue.isJsonObject)
          else false
        }
        val results = objects(top, "results").map { r =>
          DbtExecutionResult(string(r, "unique_id"), normalize(string(r, "status")), string(r, "message"),
            Try(string(r, "failures").toLong).getOrElse(0L), string(r, "relation_name"),
            Try((string(r, "execution_time").toDouble * 1000).toLong).getOrElse(0L))
        }
        DbtExecutionRun(context, target, invocation, generated, path.toString, results,
          if (!completeRecords) "Partial results: malformed or missing result records"
          else if (results.exists(r => r.id.isEmpty || r.status == "unknown")) "Partial results: missing identity or unsupported state" else "")
      }
    }
  }
  def marker(path: Path): Path = path.resolveSibling("." + path.getFileName + ".dbt-evidence")
  def record(path: Path, context: Vector[String]): Unit = fingerprint(path).foreach { digest =>
    val m = new JsonObject; m.add("context", gson.toJsonTree(context.asJava)); m.addProperty("digest", digest)
    Files.writeString(marker(path), m.toString)
  }
  def read(root: DbtRoot, name: String): DbtExecutionRun = {
    val path = root.artifactDirectory.resolve(name)
    val context = DbtArtifactContext.identity(root)
    val target = DbtExecutionContext.activeTarget(root).getOrElse("")
    val run = parse(path, context, target).copy(root = root.directory.getPath)
    if (run.results.isEmpty) run
    else {
      val matches = json(marker(path)).exists { m =>
        Option(m.get("context")).exists(_.toString == gson.toJsonTree(context.asJava).toString) &&
          fingerprint(path).contains(string(m, "digest"))
      }
      if (!matches) run.copy(results = Vector.empty, problem = "Artifact provenance unverified or target/configuration changed; run dbt explicitly")
      else if (DbtWorkspace.get(root.config.getProject).outdated(root, Try(Instant.parse(run.timestamp).toEpochMilli).toOption))
        run.copy(problem = "Stale result: project sources changed after this run")
      else run
    }
  }
}

@Service(Array(Service.Level.PROJECT))
final class DbtExecutionEvidence(project: Project) {
  import DbtExecutionEvidence._
  @volatile private var current: Option[DbtExecutionRun] = None
  private var before = Map.empty[String, Option[String]]
  private var pending = ""
  private var finishedFingerprint: Option[String] = None
  private var finishedPath = ""
  private val names = Vector("run_results.json", "sources.json")
  def active: Option[DbtExecutionRun] = current.filter(_.active)
  def begin(command: DbtCommand): Unit = synchronized {
    if (Set("Run", "Build", "Test", "Source Freshness")(command.action)) {
      pending = ""
      before = names.map(n => n -> fingerprint(Paths.get(command.artifactDirectory).resolve(n))).toMap
      current = Some(DbtExecutionRun(command.artifactContext, command.target, "", Instant.now.toString,
        command.artifactDirectory, Vector.empty, active = true, root = Option(command.root).filter(_.nonEmpty).getOrElse(command.artifactContext.headOption.getOrElse(command.directory))))
    } else current = None
  }
  def stream(text: String): Unit = synchronized {
    current.filter(_.active).foreach { run =>
      pending = (pending + text).takeRight(1000000)
      val lines = pending.split("\n", -1).toVector
      pending = lines.lastOption.getOrElse("")
      lines.dropRight(1).foreach { line => Try(JsonParser.parseString(line).getAsJsonObject).toOption.foreach { top =>
        val info = obj(top, "info"); val data = obj(top, "data"); val node = obj(data, "node_info")
        val id = string(node, "unique_id"); val status = normalize(string(node, "node_status"))
        val invocation = string(info, "invocation_id")
        val next = current.getOrElse(run)
        current = Some(next.copy(invocation = if (invocation.nonEmpty) invocation else next.invocation,
          results = if (id.isEmpty) next.results else next.results.filterNot(_.id == id) :+
            DbtExecutionResult(id, status, string(info, "msg"))))
      }}
    }
  }
  def finish(command: DbtCommand, canceled: Boolean): Option[DbtExecutionRun] = synchronized {
    current.filter(_.active).map { run =>
      val name = if (command.action == "Source Freshness") "sources.json" else "run_results.json"
      val path = Paths.get(command.artifactDirectory).resolve(name)
      val parsed = if (fingerprint(path).nonEmpty && fingerprint(path) != before.getOrElse(name, None))
        parse(path, command.artifactContext, command.target, run.invocation) else
        run.copy(results = run.results.map(r => if (r.status == "running" || r.status == "unknown")
          r.copy(status = if (canceled) "canceled" else "error") else r), problem = "No new matching result artifact was written")
      val old = Try(Instant.parse(parsed.timestamp).toEpochMilli).toOption.exists(_ < Instant.parse(run.timestamp).toEpochMilli - 2000L)
      val checked = if (old) parsed.copy(results = Vector.empty, problem = "Stale artifact predates this invocation") else parsed
      val remaining = run.results.filterNot(r => checked.results.exists(_.id == r.id)).map(r =>
        if (Set("running", "unknown")(r.status)) r.copy(status = if (canceled) "canceled" else "error") else r)
      val outcome = checked.copy(results = checked.results ++ remaining, active = false, root = run.root,
        problem = if (canceled) "Run canceled; partial results" else checked.problem)
      finishedPath = path.toString
      finishedFingerprint = fingerprint(path)
      val recorded = if (!canceled && outcome.invocation.nonEmpty && parsed.results.nonEmpty && outcome.problem.isEmpty &&
        Try(record(path, command.artifactContext)).isFailure) outcome.copy(problem = "Cannot persist artifact provenance; results are available for this session") else outcome
      current = Some(recorded); recorded
    }
  }
  def runs(root: DbtRoot): Vector[DbtExecutionRun] = {
    val latest = current.filter(r => r.context == DbtArtifactContext.identity(root) &&
      (r.active || finishedPath.nonEmpty && fingerprint(Paths.get(finishedPath)) == finishedFingerprint))
    latest.map { run =>
      if (DbtWorkspace.get(project).outdated(root, Try(Instant.parse(run.timestamp).toEpochMilli).toOption))
        run.copy(problem = (run.problem + "\nStale result: project sources changed after this run").trim)
      else run
    }.toVector ++ names.map(read(root, _))
  }
  def overlay(symbol: DbtSymbol, runs: Vector[DbtExecutionRun], definitions: Vector[DbtTestDefinition]): JsonObject = {
    val data = new JsonObject
    val direct = runs.flatMap(run => run.results.find(_.id == symbol.uniqueId).map(run -> _)).headOption
    val tests = definitions.filter(_.dependencies.contains(symbol.uniqueId)).map(_.id).toSet
    val failures = runs.flatMap(run => run.results.map(run -> _)).distinctBy(_._2.id)
      .filter { case (_, result) => tests(result.id) && Set("failed", "error")(result.status) }
    data.addProperty("status", direct.map(_._2.status).getOrElse("unknown"))
    data.addProperty("label", direct.map { case (run, r) => (if (symbol.kind == "source_table") "Freshness " else "Build ") + r.status + (if (run.problem.toLowerCase.contains("stale")) " (stale)" else "") }.getOrElse("Not run / result unavailable"))
    data.addProperty("failingTests", failures.size)
    val related = (direct.toVector ++ failures).distinctBy { case (run, result) => (run.context, run.invocation, run.artifact, result.id) }
    data.addProperty("details", if (related.nonEmpty) related.map { case (run, result) =>
      s"${result.id} · ${run.target} · ${run.timestamp} · ${run.invocation}\n${run.artifact}\n${result.message}\n${run.problem}"
    }.mkString("\n\n") else runs.map(_.problem).filter(_.nonEmpty).distinct.mkString("\n"))
    data
  }
}
