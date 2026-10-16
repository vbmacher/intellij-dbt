package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtSymbol, DbtWorkspace}
import com.intellij.openapi.vfs.VfsUtilCore

/** Only stable paths and display text cross from workspace reads into the Runner picker. */
private[ide] final case class DbtRunSelection(root: String, path: String, directory: Boolean, label: String) {
  override def toString: String = label
}

private[ide] object DbtRunSelection {
  val Prompt: DbtRunSelection = DbtRunSelection("", "", directory = false, "Choose a model or directory…")

  private def models(w: DbtWorkspace): Vector[DbtSymbol] = w.symbols.filter(s => s.kind == "model" && s.isCode && s.isEnabled &&
    w.owner(s).exists(r => !r.excluded(s.file.getVirtualFile))).distinctBy(_.file.getVirtualFile.getPath)

  def available(w: DbtWorkspace): Vector[DbtRunSelection] = {
    val sources = models(w)
    w.roots.flatMap { root =>
      val project = root.name + (if (w.roots.count(_.name == root.name) > 1) s" (${root.directory.getPath})" else "")
      val local = sources.filter(s => w.owner(s).contains(root))
      val folders = (Vector(root.directory) ++ local.flatMap(s =>
        Iterator.iterate(s.file.getVirtualFile.getParent)(_.getParent)
          .takeWhile(f => f != null && VfsUtilCore.isAncestor(root.directory, f, false)).toVector)).distinct
      folders.map { folder =>
        val relative = root.relative(folder).filter(_.nonEmpty).getOrElse(".")
        DbtRunSelection(root.directory.getPath, folder.getPath, directory = true, s"Directory: $project / $relative/")
      } ++ local.map { s =>
        DbtRunSelection(root.directory.getPath, s.file.getVirtualFile.getPath, directory = false,
          s"Model: $project.${s.relationName}${s.version.map(" v" + _).getOrElse("")} — ${root.relative(s.file.getVirtualFile).getOrElse(s.file.getName)}")
      }
    }.distinctBy(_.path).sortBy(s => (s.root, !s.directory, s.path))
  }

  def owner(w: DbtWorkspace, selection: DbtRunSelection): Option[DbtRoot] =
    w.roots.find(_.directory.getPath == selection.root)

  def selectedModels(w: DbtWorkspace, selection: DbtRunSelection): Vector[DbtSymbol] = models(w).filter { s =>
    w.owner(s).exists(r => owner(w, selection).exists(selected => w.executionRoot(r) == w.executionRoot(selected))) &&
      (if (selection.directory) s.file.getVirtualFile.getPath.startsWith(selection.path + "/")
       else s.file.getVirtualFile.getPath == selection.path)
  }

  /** Expand folders to model FQNs so similarly named paths in other packages cannot join the run. */
  def selectors(w: DbtWorkspace, selection: DbtRunSelection): Vector[String] = selectedModels(w, selection).flatMap { s =>
    w.owner(s).map { owner =>
      val folders = owner.resourceFolders(s.file.getVirtualFile).toVector.flatMap(_._2)
      "fqn:" + (Vector(s.namespace) ++ folders ++ Vector(s.name) ++ s.version.map("v" + _)).mkString(".")
    }
  }
}
