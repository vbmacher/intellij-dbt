package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.{FileVisitResult, Files, Path, Paths, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[ide] final case class DbtAirflowScan(pipelines: Vector[(String, DbtAirflowParser.Pipeline)], problems: Vector[String])

/** Watches external DAG checkouts as well as files already belonging to this IDEA project. */
@Service(Array(Service.Level.PROJECT))
final class DbtAirflowDiscovery(project: Project) extends Disposable {
  private val skip = Set("__pycache__", "node_modules", ".git", ".venv", "venv")
  private var watched = Vector.empty[String]
  private var requests = Vector.empty[LocalFileSystem.WatchRequest]
  private var cache = DbtAirflowScan(Vector.empty, Vector.empty)
  private case class FileEntry(stamp: (java.nio.file.attribute.FileTime, Long, Option[Long]), pipelines: Vector[DbtAirflowParser.Pipeline])
  private val parsed = mutable.Map.empty[Path, FileEntry]

  private[ide] def directories: Vector[String] = {
    val s = project.getService(classOf[DbtConsumersSettings]).getState
    s.airflowPaths.asScala.toVector.map(_.trim).filter(_.nonEmpty).map { value =>
      val path = Paths.get(value)
      (if (path.isAbsolute) path else Paths.get(Option(project.getBasePath).getOrElse(".")).resolve(path)).normalize().toString
    }.distinct
  }

  private def watch(paths: Vector[String]): Unit = if (watched != paths) {
    val fs = LocalFileSystem.getInstance()
    requests.foreach(fs.removeWatchedRoot)
    requests = paths.flatMap(p => Option(fs.addRootToWatch(p, true)))
    watched = paths
    // Register the external tree with VFS so edits, additions and removals refresh open lineage views.
    ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
      override def run(): Unit = if (!project.isDisposed) paths.foreach { p =>
        Option(fs.refreshAndFindFileByPath(p)).foreach(_.refresh(true, true))
      }
    })
  }

  private[ide] def scan(): DbtAirflowScan = synchronized {
    val paths = directories
    watch(paths)
    val pipelines = mutable.ArrayBuffer.empty[(String, DbtAirflowParser.Pipeline)]
    val problems = mutable.ArrayBuffer.empty[String]
    val seen = mutable.Set.empty[Path]
    paths.foreach { directory =>
      val root = Paths.get(directory)
      if (!Files.isDirectory(root)) problems += s"Airflow DAG folder is missing or is not a directory: $directory"
      else try Files.walkFileTree(root, new SimpleFileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
          ProgressManager.checkCanceled()
          if (Option(dir.getFileName).exists(name => skip(name.toString))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        }
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          ProgressManager.checkCanceled()
          if (file.toString.endsWith(".py") && attrs.isRegularFile && seen.add(file.toAbsolutePath.normalize())) {
            if (attrs.size() > 1000000) problems += s"Airflow DAG file exceeds 1 MB: $file"
            else try {
              val virtual = Option(LocalFileSystem.getInstance().findFileByNioFile(file))
              val documents = FileDocumentManager.getInstance()
              val unsaved = virtual.flatMap(f => Option(documents.getCachedDocument(f))).filter(documents.isDocumentUnsaved)
              val stamp = (attrs.lastModifiedTime(), attrs.size(), unsaved.map(_.getModificationStamp))
              val entry = parsed.get(file).filter(_.stamp == stamp).getOrElse {
                val source = unsaved.map(_.getText).getOrElse(Files.readString(file))
                FileEntry(stamp, DbtAirflowParser.parse(file.toString, source))
              }
              parsed(file) = entry
              pipelines ++= entry.pipelines.map(file.toString -> _)
            } catch {
              case canceled: com.intellij.openapi.progress.ProcessCanceledException => throw canceled
              case NonFatal(_) => problems += s"Cannot read Airflow DAG file: $file"
            }
          }
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult = {
          problems += s"Cannot read Airflow DAG path: $file"; FileVisitResult.CONTINUE
        }
      }) catch {
        case canceled: com.intellij.openapi.progress.ProcessCanceledException => throw canceled
        case NonFatal(_) => problems += s"Cannot scan Airflow DAG folder: $directory"
      }
    }
    parsed.keys.filterNot(p => seen(p.toAbsolutePath.normalize())).toVector.foreach(parsed.remove)
    val result = DbtAirflowScan(pipelines.toVector.sortBy { case (path, p) => (path, p.name) }, problems.toVector.distinct)
    if (result != cache) cache = result
    cache
  }

  override def dispose(): Unit = synchronized {
    requests.foreach(LocalFileSystem.getInstance().removeWatchedRoot)
    requests = Vector.empty
  }
}
