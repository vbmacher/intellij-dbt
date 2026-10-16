package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtProjectSettings, DbtSettingsListener, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtSqlText
import com.intellij.lang.annotation.{AnnotationHolder, ExternalAnnotator, HighlightSeverity}
import com.intellij.codeInspection.{LocalInspectionTool, SuppressionUtil}
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiFile, PsiManager, SmartPointerManager, SmartPsiElementPointer}
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.Alarm
import scala.jdk.CollectionConverters._

private[ide] final case class DbtDiagnostic(range: TextRange, message: String)
private[ide] final case class DbtAnnotationInput(pointer: SmartPsiElementPointer[PsiFile], stamp: Long)
private[ide] final case class DbtAnnotationResult(stamp: Long, diagnostics: Vector[DbtDiagnostic])

/** Workspace resolution runs in the external annotator's cancellable background phase. */
final class DbtUnresolvedAnnotator extends ExternalAnnotator[DbtAnnotationInput, DbtAnnotationResult] {
  override def getPairedBatchInspectionShortName: String = "DbtUnresolvedReference"
  override def collectInformation(file: PsiFile): DbtAnnotationInput =
    if (Set("sql", "jinja", "jinja2")(Option(file.getVirtualFile).flatMap(f => Option(f.getExtension)).getOrElse(""))) {
      file.getProject.getService(classOf[DbtHighlightingRefresh])
      DbtAnnotationInput(SmartPointerManager.getInstance(file.getProject).createSmartPsiElementPointer(file), file.getModificationStamp)
    }
    else null

  override def doAnnotate(input: DbtAnnotationInput): DbtAnnotationResult = ReadAction.nonBlocking[DbtAnnotationResult](() => {
    Option(input.pointer.getElement).filter(_.getModificationStamp == input.stamp).map { file =>
      val text = file.getText
      val inactive = DbtSqlText.tokens(text).filter(_.protectedText).map(_.range)
      val w = DbtWorkspace.get(file.getProject)
      val conclusive = w.root(file).exists(root => DbtDeclarationSource.EP.getExtensionList.asScala.forall(_.canReportMissing(w, root)))
      val diagnostics = (if (conclusive) DbtNavigation.inFile(file) else Vector.empty).groupBy(_.getRangeInElement).toVector
        .filter { case (range, refs) => refs.forall(_.candidates.isEmpty) && !inactive.exists(_.containsOffset(range.getStartOffset)) }
        .flatMap { case (range, refs) =>
          val messages = refs.flatMap(_.unresolvedMessage).distinct
          if (messages.size == 1) messages.map(DbtDiagnostic(range, _)) else Vector.empty
        }.sortBy(_.range.getStartOffset)
      DbtAnnotationResult(input.stamp, diagnostics)
    }.getOrElse(DbtAnnotationResult(input.stamp, Vector.empty))
  }).expireWith(input.pointer.getProject).executeSynchronously()

  override def apply(file: PsiFile, result: DbtAnnotationResult, holder: AnnotationHolder): Unit = {
    val key = Option(HighlightDisplayKey.find(getPairedBatchInspectionShortName))
    val profile = InspectionProjectProfileManager.getInstance(file.getProject).getCurrentProfile
    val severity = key.map(k => profile.getErrorLevel(k, file).getSeverity).getOrElse(HighlightSeverity.WARNING)
    if (file.getModificationStamp == result.stamp && key.forall(profile.isToolEnabled(_, file))) result.diagnostics
      .filter(d => Option(file.findElementAt(d.range.getStartOffset)).forall(e => !SuppressionUtil.isSuppressed(e, getPairedBatchInspectionShortName)))
      .foreach(d => holder.newAnnotation(severity, d.message).range(d.range).create())
  }
}

/** External annotators otherwise rerun only when their own file is dirty, even if a referenced definition changed. */
@Service(Array(Service.Level.PROJECT))
final class DbtHighlightingRefresh(project: Project) extends Disposable {
  private val alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(PsiModificationTracker.TOPIC, new PsiModificationTracker.Listener {
    override def modificationCountChanged(): Unit = schedule()
  })
  connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener {
    override def after(events: java.util.List[_ <: VFileEvent]): Unit = schedule()
  })
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = schedule()
  })

  private def schedule(): Unit = ApplicationManager.getApplication.invokeLater(() => if (!project.isDisposed && !alarm.isDisposed) {
    alarm.cancelAllRequests()
    alarm.addRequest(() => WriteIntentReadAction.run(() => {
      val psi = PsiManager.getInstance(project)
      val daemon = com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
      FileEditorManager.getInstance(project).getOpenFiles
        .filter(f => f.isValid && Set("sql", "jinja", "jinja2")(Option(f.getExtension).getOrElse("")))
        .flatMap(f => Option(psi.findFile(f))).foreach(file => daemon.restart(file, "dbt workspace changed"))
    }), 200)
  })

  override def dispose(): Unit = ()
}

/** The paired inspection gives background annotations normal severity/enablement controls in Settings → Inspections. */
final class DbtUnresolvedReferenceInspection extends LocalInspectionTool {
  override def getShortName: String = "DbtUnresolvedReference"
}
