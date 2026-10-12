package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.{LocalFileSystem, VfsUtilCore, VirtualFile}
import com.intellij.psi.{PsiFile, PsiManager}
import org.jetbrains.yaml.psi.{YAMLFile, YAMLKeyValue, YAMLMapping, YAMLScalar, YAMLSequence}

import scala.jdk.CollectionConverters._

/** Source configuration for the single root dbt project; read through PSI to include editor changes. */
final class DbtProject private (val root: VirtualFile, val config: YAMLMapping) {
  private def value(key: String) = Option(config.getKeyValueByKey(key)).flatMap(k => Option(k.getValue))

  private def text(key: String, default: String): String = value(key).collect {
    case scalar: YAMLScalar => scalar.getTextValue
  }.getOrElse(default)

  private def paths(key: String, default: String): Seq[String] = value(key) match {
    case Some(sequence: YAMLSequence) => sequence.getItems.asScala.toSeq.flatMap { item =>
      Option(item.getValue).collect { case scalar: YAMLScalar => scalar.getTextValue }
    }
    case None => Seq(default)
    case _ => Seq.empty
  }

  private val resourcePaths = Seq(
    "model-paths" -> "models", "test-paths" -> "tests", "macro-paths" -> "macros",
    "snapshot-paths" -> "snapshots", "analysis-paths" -> "analyses"
  ).flatMap { case (key, default) => paths(key, default) }

  private val excludedPaths = Seq(text("target-path", "target"), text("packages-install-path", "dbt_packages"),
    ".git", ".idea", "logs")

  private def inside(file: VirtualFile, path: String): Boolean =
    Option(root.findFileByRelativePath(path)).exists(VfsUtilCore.isAncestor(_, file, false))

  def accepts(file: PsiFile): Boolean = {
    val virtualFile = file.getVirtualFile
    if (virtualFile == null || virtualFile.getExtension != "sql" ||
      !resourcePaths.exists(inside(virtualFile, _)) || excludedPaths.exists(inside(virtualFile, _))) return false

    // A nested dbt project must not inherit this root project's variable scope.
    var directory = virtualFile.getParent
    while (directory != null && directory != root) {
      if (directory.findChild(DbtPlugin.ProjectFileName) != null) return false
      directory = directory.getParent
    }
    directory == root
  }

  def resolve(name: String): Option[YAMLKeyValue] = value("vars").collect {
    case mapping: YAMLMapping => mapping
  }.flatMap { globals =>
    val scoped = Option(globals.getKeyValueByKey(text("name", ""))).flatMap(k => Option(k.getValue)).collect {
      case mapping: YAMLMapping => mapping
    }
    scoped.flatMap(mapping => Option(mapping.getKeyValueByKey(name)))
      .orElse(Option(globals.getKeyValueByKey(name)))
  }

  def isDeclaration(element: YAMLKeyValue): Boolean = {
    val parent = element.getParentMapping
    val globals = value("vars").collect { case mapping: YAMLMapping => mapping }
    globals.exists { mapping =>
      parent == mapping || Option(mapping.getKeyValueByKey(text("name", ""))).exists(_.getValue == parent)
    }
  }
}

object DbtProject {
  def forProject(project: Project): Option[DbtProject] = {
    val base = Option(project.getBasePath).flatMap(path => Option(LocalFileSystem.getInstance.findFileByPath(path)))
    val roots = (base.toSeq ++ ProjectRootManager.getInstance(project).getContentRoots.toSeq).distinct
    val configurations = roots.flatMap(root => Option(root.findChild(DbtPlugin.ProjectFileName))).distinct
    if (configurations.size != 1) return None

    Option(PsiManager.getInstance(project).findFile(configurations.head)).collect {
      case file: YAMLFile => file
    }.flatMap(_.getDocuments.asScala.headOption).flatMap(document => Option(document.getTopLevelValue)).collect {
      case mapping: YAMLMapping => new DbtProject(configurations.head.getParent, mapping)
    }
  }
}
