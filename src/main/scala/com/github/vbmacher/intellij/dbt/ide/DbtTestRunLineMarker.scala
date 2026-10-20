package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtWorkspace}
import com.intellij.execution.lineMarker.{ExecutorAction, RunLineMarkerContributor}
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}

final class DbtTestRunLineMarker extends RunLineMarkerContributor {
  override def getInfo(element: PsiElement): RunLineMarkerContributor.Info = null
  override def getSlowInfo(element: PsiElement): RunLineMarkerContributor.Info = {
    if (element.getFirstChild != null || element.isInstanceOf[PsiFile]) null
    else DbtTestRunLineMarker.testsAt(element).filter(tests => tests.minByOption(_.offset).exists { test =>
      val file = element.getContainingFile
      val offset = if (test.offset == 0) file.getText.indexWhere(c => !c.isWhitespace) else test.offset
      Option(file.findElementAt(offset)).contains(element)
    }).map { tests =>
      val state = DbtTestRunLineMarker.status(element.getContainingFile, tests)
      val icon = state match {
        case "passed" => AllIcons.RunConfigurations.TestState.Green2
        case "failed" | "error" => AllIcons.RunConfigurations.TestState.Red2
        case "warning" | "skipped" => AllIcons.RunConfigurations.TestState.Yellow2
        case _ => AllIcons.RunConfigurations.TestState.Run
      }
      new RunLineMarkerContributor.Info(icon, ExecutorAction.getActions(0),
        new java.util.function.Function[PsiElement, String] {
          override def apply(value: PsiElement): String = "dbt tests: " + state +
            (if (tests.exists(_.selector.isEmpty)) ". Exact identity unavailable; Generate Docs for this project and target." else ". Run tests")
        })
    }.orNull
  }
}

private[ide] object DbtTestRunLineMarker {
  private val Definitions = Key.create[CachedValue[Vector[DbtTestDefinition]]]("dbt.test.definitions")
  def definitions(file: PsiFile): Vector[DbtTestDefinition] = {
    val project = file.getProject
    CachedValuesManager.getManager(project).getCachedValue(project, Definitions,
      () => CachedValueProvider.Result.create(DbtTestDiscovery.discover(DbtWorkspace.get(project)),
        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, DbtProjectSettings.get(project)), false)
  }
  def testsAt(element: PsiElement): Option[Vector[DbtTestDefinition]] = Option(element).flatMap(e => Option(e.getContainingFile)).filter(_.isPhysical).map { file =>
    val tests = definitions(file).filter(_.file == file.getVirtualFile)
    if (element.isInstanceOf[PsiFile] || tests.size == 1 && tests.head.offset == 0) tests
    else Option(file.getViewProvider.getDocument).fold(Vector.empty[DbtTestDefinition]) { document =>
      val line = document.getLineNumber(element.getTextOffset)
      tests.filter(t => t.offset < document.getTextLength && document.getLineNumber(t.offset) == line)
    }
  }
  def status(file: PsiFile, tests: Vector[DbtTestDefinition]): String = {
    val w = DbtWorkspace.get(file.getProject)
    val runs = w.roots.filter(r => tests.exists(_.root == r.directory.getPath))
      .flatMap(file.getProject.getService(classOf[DbtExecutionEvidence]).runs).filter(_.problem.isEmpty)
    val recorded = runs.flatMap(run => run.results.map(result => result.id -> (if (run.active) "running" else result.status)))
      .distinctBy(_._1).toMap
    val states = tests.map(t => recorded.getOrElse(t.id, "not run"))
    Vector("error", "failed", "running", "canceled", "not run", "warning", "skipped").find(states.contains)
      .getOrElse(if (states.nonEmpty) "passed" else "not run")
  }
}
