package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonArray, JsonObject, JsonParser}
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.LightVirtualFile

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.util.Try

private[ide] final case class DbtCteOrigin(path: String, line: Int, instance: Long)
private[ide] final case class DbtCteSpan(range: TextRange, origins: Vector[DbtCteOrigin])

private[ide] object DbtCteCompilation {
  /** Emit already compiled text once; generated Jinja-looking literals must not be rendered again. */
  def inline(sql: String): String =
    if (sql.contains("{{") || sql.contains("{%")) "{{ " + new com.google.gson.JsonPrimitive(sql).toString + " }}" else sql
}

/** Source locations and generated SQL belong to the same immutable compilation. */
private[ide] final case class DbtCteCompilation(context: DbtCteContext, sql: String, analysis: DbtSqlAnalysis,
  spans: Vector[DbtCteSpan]) {
  val frames: Vector[DbtCte] = DbtCteInspection.frames(analysis)
  val file: LightVirtualFile = {
    val created = new LightVirtualFile("dbt-compiled-inspection.sql", sql)
    created.setWritable(false)
    created
  }

  private val originsByStep: Map[Int, Vector[DbtCteOrigin]] = {
    val sorted = spans.sortBy(_.range.getStartOffset)
    var first = 0
    frames.map { frame =>
      while (first < sorted.size && sorted(first).range.getEndOffset <= frame.range.getStartOffset) first += 1
      val mapped = sorted.iterator.drop(first).takeWhile(_.range.getStartOffset < frame.range.getEndOffset)
        .filter(_.range.intersectsStrict(frame.range)).flatMap(_.origins).toVector.distinct
      frame.range.getStartOffset -> mapped
    }.toMap
  }

  def origins(cte: DbtCte): Vector[DbtCteOrigin] = originsByStep.getOrElse(cte.range.getStartOffset, Vector.empty)
}

/** A single traced Compile command, launched exclusively through the shared runner. */
private[ide] final case class DbtCteCompileJob(command: DbtCommand, context: DbtCteContext,
  directory: Path, token: String, sources: Map[String, String]) extends Disposable {
  def load(): Either[String, DbtCteCompilation] = {
    val lineCounts = sources.iterator.map { case (path, text) => path -> text.count(_ == '\n') }.toMap
    val report = directory.resolve("report.json")
    if (!Files.isRegularFile(report)) Left("The configured CLI did not provide source mappings. " +
      "Use a Python dbt Core 1.8+ executable that preserves PYTHONPATH; native/Fusion CLIs are not supported for source-mapped inspection.")
    else if (Files.size(report) > 30000000) Left("The compilation source map exceeds 30 MB.")
    else Try(JsonParser.parseString(Files.readString(report)).getAsJsonObject).toEither.left
      .map(_ => "The compiler returned an invalid source map.").flatMap { root =>
        if (!DbtJson.text(root, "token").contains(token)) Left("The source map does not belong to this compilation.")
        else DbtJson.text(root, "error") match {
          case Some(error) => Left(error)
          case None => for {
            sql <- DbtJson.text(root, "sql").toRight("The compiler returned no SQL.")
            analysis <- DbtSqlAnalysis.compiled(sql)
            spans <- Try(Option(root.getAsJsonArray("spans")).toVector.flatMap(_.asScala).map { value =>
              val span = value.getAsJsonObject
              val start = span.get("start").getAsInt
              val end = span.get("end").getAsInt
              require(start >= 0 && start < end && end <= sql.length)
              val origins = span.getAsJsonArray("origins").asScala.toVector.map { item =>
                val origin = item.getAsJsonObject
                val path = origin.get("path").getAsString
                val line = origin.get("line").getAsInt
                require(lineCounts.get(path).exists(last => line >= 0 && line <= last))
                DbtCteOrigin(path, line, origin.get("instance").getAsLong)
              }
              DbtCteSpan(new TextRange(start, end), origins)
            }).toEither.left.map(_ => "The compiler returned invalid source locations.")
          } yield DbtCteCompilation(context, sql, analysis, spans)
        }
      }
  }

  override def dispose(): Unit = {
    if (Files.isDirectory(directory)) {
      val files = Files.walk(directory)
      try files.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally files.close()
    }
  }
}

private[ide] object DbtCteCompileJob {
  def prepare(w: DbtWorkspace, resource: DbtSymbol): Either[String, DbtCteCompileJob] = for {
    owner <- w.owner(resource).toRight("The owning dbt project is unavailable.")
    _ <- Either.cond(w.executionRoot(owner).engine == "core", (), "Source-mapped CTE inspection requires the Python dbt Core 1.8+ CLI.")
    selection <- DbtRunSelection.available(w).find(_.path == resource.file.getVirtualFile.getPath).toRight("Choose a source model.")
    base <- DbtCommand.create(w, selection, "Compile", fullRefresh = false)
    context <- DbtCteInspection.context(w, resource).toRight("The execution context is unavailable.")
    job <- Try {
      val directory = Files.createTempDirectory("dbt-cte-compile-")
      try {
        for (name <- Vector("cte_trace.py", "sitecustomize.py")) {
          val stream = getClass.getResourceAsStream("/dbt/" + name)
          require(stream != null, "Missing compiler tracing resource: " + name)
          try Files.copy(stream, directory.resolve(name)) finally stream.close()
        }
        val sourceJson = new JsonArray
        val sources = w.files.filter(_.getName.endsWith(".sql")).flatMap { file =>
          w.root(file).filter(r => w.executionRoot(r) == w.executionRoot(owner)).map { root =>
            val entry = new JsonObject
            entry.addProperty("package", root.name)
            entry.addProperty("relative", VfsUtilCore.getRelativePath(file.getVirtualFile, root.directory, '/'))
            entry.addProperty("path", file.getVirtualFile.getPath)
            entry.addProperty("text", file.getText)
            sourceJson.add(entry)
            file.getVirtualFile.getPath -> file.getText
          }
        }.toMap
        require(sources.valuesIterator.map(_.length.toLong).sum <= 20000000, "Compilation source snapshot exceeds 20 MB.")
        val token = UUID.randomUUID().toString
        val request = new JsonObject
        request.addProperty("token", token)
        request.addProperty("model", resource.file.getVirtualFile.getPath)
        request.addProperty("report", directory.resolve("report.json").toString)
        request.add("sources", sourceJson)
        Files.writeString(directory.resolve("request.json"), request.toString)
        val pythonPath = base.environment.get("PYTHONPATH").orElse(sys.env.get("PYTHONPATH")).filter(_.nonEmpty)
        val environment = base.environment ++ Map("DBT_IDE_CTE_TRACE" -> directory.resolve("request.json").toString,
          "PYTHONPATH" -> (directory.toString + pythonPath.map(java.io.File.pathSeparator + _).getOrElse("")),
          "DBT_PARTIAL_PARSE" -> "false", "DBT_TARGET_PATH" -> directory.resolve("target").toString)
        DbtCteCompileJob(base.copy(environment = environment, artifactDirectory = directory.resolve("target").toString),
          context, directory, token, sources)
      } catch {
        case error: Exception =>
          DbtCteCompileJob(base, context, directory, "", Map.empty).dispose()
          throw error
      }
    }.toEither.left.map(error => "Cannot prepare source-mapped compilation: " + error.getMessage)
  } yield job
}
