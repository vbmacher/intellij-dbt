package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSqlText, DbtSyntax, DbtYaml}
import com.intellij.codeInsight.daemon.{DaemonCodeAnalyzer, HighlightDisplayKey}
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.{LocalInspectionTool, SuppressionUtil}
import com.intellij.lang.annotation.{AnnotationHolder, ExternalAnnotator, HighlightSeverity}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{DialogWrapper, ValidationInfo}
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.{VfsUtil, VirtualFile, VirtualFileManager}
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiManager, SmartPointerManager, SmartPsiElementPointer}
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.{JBLabel, JBTextArea, JBTextField}
import com.intellij.util.Alarm
import com.jetbrains.jsonSchema.extension.{JsonSchemaFileProvider, JsonSchemaProviderFactory, SchemaType}
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLSequence
import java.awt.BorderLayout
import javax.swing.{JCheckBox, JComponent, JPanel}
import scala.jdk.CollectionConverters._

private[ide] object DbtUnresolvedAnnotator {
  val Extensions: Set[String] = Set("sql", "jinja", "jinja2")
}

private[ide] final case class DbtDiagnostic(range: TextRange, message: String)

private[ide] final case class DbtAnnotationInput(pointer: SmartPsiElementPointer[PsiFile], stamp: Long)

private[ide] final case class DbtAnnotationResult(stamp: Long, diagnostics: Vector[DbtDiagnostic])

/** Workspace resolution runs in the external annotator's cancellable background phase. */
final class DbtUnresolvedAnnotator extends ExternalAnnotator[DbtAnnotationInput, DbtAnnotationResult] {
  override def getPairedBatchInspectionShortName: String = "DbtUnresolvedReference"

  override def collectInformation(file: PsiFile): DbtAnnotationInput =
    if (DbtUnresolvedAnnotator.Extensions(Option(file.getVirtualFile).flatMap(f => Option(f.getExtension)).getOrElse(""))) {
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
      .foreach(d => holder.newAnnotation(severity, d.message).range(d.range).withFix(new DbtCreateResourceFix(d.range.getStartOffset)).create())
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
      val daemon = DaemonCodeAnalyzer.getInstance(project)
      FileEditorManager.getInstance(project).getOpenFiles
        .filter(f => f.isValid && DbtUnresolvedAnnotator.Extensions(Option(f.getExtension).getOrElse("")))
        .flatMap(f => Option(psi.findFile(f))).foreach(file => daemon.restart(file, "dbt workspace changed"))
    }), 200)
  })

  override def dispose(): Unit = ()
}

/** The paired inspection gives background annotations normal severity/enablement controls in Settings → Inspections. */
final class DbtUnresolvedReferenceInspection extends LocalInspectionTool {
  override def getShortName: String = "DbtUnresolvedReference"
}

private[ide] final case class DbtCreation(kind: String, name: String, source: Option[String], root: DbtRoot,
  file: Option[PsiFile], before: String, after: String, path: String,
  origin: Option[(PsiFile, TextRange, String)] = None)

private[ide] object DbtCreateResourceFix {
  private[ide] def properties(name: String): String = s"version: 2\nmodels:\n  - name: $name\n    columns:\n      - name: id\n"

  def proposal(file: PsiFile, offset: Int): Option[DbtCreation] = {
    val w = DbtWorkspace.get(file.getProject)
    for {
      root <- w.root(file).filter(r => w.packageOwner(r).isEmpty && !r.excluded(file.getVirtualFile))
      ref <- DbtNavigation.inFile(file).find(r => r.unresolvedMessage.nonEmpty && r.getRangeInElement.containsOffset(offset))
      call <- DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).find(c => c.closed && c.expressionRange.contains(ref.getRangeInElement))
      folder <- root.pathKinds.find(_._1 == "model").map(_._2)
      result <- call.name match {
        case "ref" | "builtins.ref" if call.positional.size == 1 && call.version.isEmpty => call.literal(0)
          .filter(DbtSyntax.isIdentifier).map(name => DbtCreation("model", name, None, root, None, "", "select\n    1 as id\n", folder + "/" + name + ".sql"))
        case "source" | "builtins.source" if call.positional.size == 2 =>
          for (source <- call.literal(0).filter(DbtSyntax.isIdentifier); table <- call.literal(1).filter(DbtSyntax.isIdentifier);
               result <- sourcePlan(w, root, folder, source, table)) yield result
        case _ => None
      }
    } yield result.copy(origin = Some((file, ref.getRangeInElement, file.getText)))
  }

  private def sourcePlan(w: DbtWorkspace, root: DbtRoot, folder: String, source: String, table: String): Option[DbtCreation] = {
    val parents = w.declarations(Set("source"), source, root).filter(s => !s.metadata && w.owner(s).contains(root))
    if (parents.size > 1 || parents.exists(s => !s.file.isWritable)) None
    else parents.headOption match {
      case Some(parent) => parent.mapping.flatMap { mapping =>
        val before = parent.file.getText
        val value = "{name: " + table + "}"
        val after = DbtYaml.value(mapping, "tables") match {
          case Some(sequence: YAMLSequence) => Some(DbtPropertiesEditor.appendItems(before, sequence, Vector(value)))
          case None =>
            val offset = mapping.getTextRange.getEndOffset
            Some(before.substring(0, offset) + "\n" + " " * YAMLUtil.getIndentInThisLine(mapping) + "tables: [" + value + "]" + before.substring(offset))
          case _ => None
        }
        after.map(text => DbtCreation("source", table, Some(source), root, Some(parent.file), before, text,
          root.relative(parent.file.getVirtualFile).getOrElse("")))
      }
      case None =>
        val path = folder + "/" + source + "_sources.yml"
        if (root.directory.findFileByRelativePath(path) != null) None else
          Some(DbtCreation("source", table, Some(source), root, None, "", s"version: 2\nsources:\n  - name: $source\n    tables:\n      - name: $table\n", path))
    }
  }

  def validate(proposal: DbtCreation, relativePath: String, includeProperties: Boolean = false,
    logicalName: Option[String] = None): Either[String, java.nio.file.Path] =
    scala.util.Try(java.nio.file.Paths.get(relativePath).normalize()).toEither
      .left.map(_ => "Choose a valid destination path.").flatMap { path =>
        val name = logicalName.getOrElse(proposal.name)
        val project = proposal.root.config.getProject
        val workspace = DbtWorkspace.get(project)
        val current = workspace.roots.find(_.directory == proposal.root.directory)
        val normalized = path.toString.replace('\\', '/')
        val valid = !path.isAbsolute && !path.startsWith("..") && current.exists(root =>
          !root.excludedPath(normalized) && root.pathKinds.exists { case (kind, folder) =>
            kind == "model" && root.pathContains(folder, normalized)
          })
        val propertiesPath = path.resolveSibling(name + ".yml").toString.replace('\\', '/')
        val filename = Option(path.getFileName).map(_.toString).getOrElse("")
        val wrongExtension = if (proposal.kind == "model") filename != name + ".sql"
        else !Set("yml", "yaml")(filename.split("\\.").lastOption.getOrElse(""))
        if (!DbtSyntax.isIdentifier(name) || proposal.kind != "model" && name != proposal.name) Left("Choose a valid dbt model name.")
        else if (wrongExtension) Left("Use the model name followed by .sql, or a .yml/.yaml source file.")
        else if (!valid) Left("Choose a file inside a configured model path, outside generated and ignored files.")
        else if (current.exists(root => workspace.roots.exists(other => other.directory != root.directory &&
          root.relative(other.directory).exists(root.pathContains(_, normalized)))))
          Left("The destination belongs to another dbt project.")
        else if (proposal.kind == "model" && current.exists(root => workspace.declarations(DbtSymbol.RefKinds, name, root).nonEmpty))
          Left("A resource with that logical name already exists.")
        else if (includeProperties && (proposal.kind != "model" || current.exists(_.excludedPath(propertiesPath))))
          Left("Choose an unexcluded model properties destination.")
        else if (includeProperties && proposal.root.directory.findFileByRelativePath(propertiesPath) != null)
          Left("The properties destination already exists.")
        else if (proposal.origin.exists { case (file, _, before) => !file.isValid || !file.isWritable || file.getText != before })
          Left("The reference changed. Reopen the quick fix.")
        else if (name != proposal.name && proposal.origin.isEmpty) Left("Reopen the quick fix to change the reference name.")
        else if (proposal.file.exists(f => f.getText != proposal.before)) Left("Source properties changed. Reopen the quick fix.")
        else if (proposal.file.isEmpty && proposal.root.directory.findFileByRelativePath(path.toString) != null) Left("The destination already exists.")
        else Right(path)
      }

  def create(proposal: DbtCreation, relativePath: String, includeProperties: Boolean = false,
    logicalName: Option[String] = None): Either[String, Unit] =
    validate(proposal, relativePath, includeProperties, logicalName).map { path =>
      val name = logicalName.getOrElse(proposal.name)
      val project = proposal.root.config.getProject
      WriteCommandAction.writeCommandAction(proposal.root.config.getProject).withName("Create dbt resource").run(new com.intellij.util.ThrowableRunnable[RuntimeException] {
        override def run(): Unit = proposal.file match {
          case Some(file) =>
            val manager = PsiDocumentManager.getInstance(file.getProject)
            manager.getDocument(file).setText(proposal.after);
            manager.commitAllDocuments()
          case None =>
            val parent = Option(path.getParent).fold(proposal.root.directory)(p => VfsUtil.createDirectoryIfMissing(proposal.root.directory, p.toString))
            val created = parent.createChildData(this, path.getFileName.toString)
            VfsUtil.saveText(created, proposal.after)
            if (includeProperties) VfsUtil.saveText(parent.createChildData(this, name + ".yml"), properties(name))
            if (name != proposal.name) proposal.origin.foreach { case (file, range, _) =>
              val manager = PsiDocumentManager.getInstance(project)
              manager.getDocument(file).replaceString(range.getStartOffset, range.getEndOffset, name)
              manager.commitAllDocuments()
            }
        }
      })
    }
}

final class DbtCreateResourceFix(offset: Int) extends IntentionAction {
  override def getText: String = "Create missing dbt resource"

  override def getFamilyName: String = "dbt resource creation"

  override def startInWriteAction(): Boolean = false

  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = DbtCreateResourceFix.proposal(file, offset).nonEmpty

  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    DbtCreateResourceFix.proposal(file, offset).foreach(proposal => new DbtCreateResourceDialog(project, proposal).show())
}

private[ide] final class DbtCreateResourceDialog(project: Project, proposal: DbtCreation) extends DialogWrapper(project) {
  val path = new JBTextField(proposal.path, 55)
  val name = new JBTextField(proposal.name, 30)
  name.setEditable(proposal.kind == "model")
  path.setEditable(proposal.file.isEmpty)
  val properties = new JCheckBox("Add model properties beside SQL")
  properties.setVisible(proposal.kind == "model")
  val content = new JPanel(new BorderLayout(0, 8))
  val preview = new JBTextArea(proposal.after, 12, 70);
  preview.setEditable(false)
  preview.getAccessibleContext.setAccessibleName("Proposed resource contents")
  val header = new JPanel(new BorderLayout(0, 8))
  header.add(new JBLabel(s"Project: ${proposal.root.name} · ${proposal.kind} ${proposal.source.map(_ + ".").getOrElse("")}${proposal.name}"), BorderLayout.NORTH)
  val fields = new JPanel(new java.awt.GridBagLayout)
  DbtUi.labeledRows(fields, com.intellij.util.ui.JBUI.insets(4, 0), Vector(
    "Logical name:" -> name, "Destination:" -> path, "" -> properties))
  header.add(fields, BorderLayout.CENTER)
  content.add(header, BorderLayout.NORTH)
  content.add(new com.intellij.ui.components.JBScrollPane(preview), BorderLayout.CENTER)

  def updatePreview(): Unit = preview.setText(proposal.after +
    (if (properties.isSelected) s"\n--- ${name.getText}.yml ---\n" + DbtCreateResourceFix.properties(name.getText) else ""))

  name.getDocument.addDocumentListener(new com.intellij.ui.DocumentAdapter {
    override def textChanged(event: javax.swing.event.DocumentEvent): Unit = {
      path.setText(path.getText.substring(0, path.getText.lastIndexOf('/') + 1) + name.getText + ".sql")
      updatePreview()
    }
  })
  properties.addActionListener(_ => updatePreview())
  setTitle("Create dbt resource");
  setOKButtonText("Create");
  init()
  initValidation()

  override def createCenterPanel(): JComponent = content

  override def getPreferredFocusedComponent: JComponent = if (name.isEditable) name else path
  override def getDimensionServiceKey: String = "dbt.create.resource"

  override def doValidate(): ValidationInfo =
    DbtCreateResourceFix.validate(proposal, path.getText.trim, properties.isSelected, Some(name.getText.trim))
      .left.toOption.map(message => new ValidationInfo(message,
        if (!DbtSyntax.isIdentifier(name.getText.trim)) name else path)).orNull

  override def doOKAction(): Unit = DbtCreateResourceFix.create(proposal, path.getText.trim, properties.isSelected, Some(name.getText.trim)) match {
    case Right(_) => super.doOKAction()
    case Left(error) => setErrorText(error)
  }
}

/** Offline schemas use IntelliJ's YAML completion, documentation and inspection infrastructure. */
final class DbtYamlSchemaProvider extends JsonSchemaProviderFactory {
  override def getProviders(project: Project): java.util.List[JsonSchemaFileProvider] =
    (for (kind <- Vector("project", "packages", "selectors", "properties"); version <- Vector("precontracts", "legacy", "semantic", "queries", "units", "current", "functions", "v2"))
      yield new JsonSchemaFileProvider {
        override def getName: String = s"dbt $kind ($version)"

        override def getSchemaType: SchemaType = SchemaType.embeddedSchema

        override def getSchemaFile: VirtualFile = JsonSchemaProviderFactory.getResourceFile(classOf[DbtYamlSchemaProvider], if (kind == "properties") s"/schemas/dbt-$kind-$version.json" else s"/schemas/dbt-$kind.json")

        override def isAvailable(file: VirtualFile): Boolean = !project.isDisposed && file.isValid &&
          Set("yml", "yaml")(Option(file.getExtension).getOrElse("")) &&
          DbtWorkspace.rootForFile(project, file).filterNot(_.excluded(file)).exists { root =>
            val selectedVersion = if (root.engine == "v2") "v2" else if (root.supports(11)) "functions" else if (root.supports(10)) "current"
            else if (root.supports(8)) "units" else if (root.supports(7)) "queries" else if (root.supports(6)) "semantic"
            else if (root.supports(5)) "legacy" else "precontracts"
            val selectedKind = file.getName match {
              case "dbt_project.yml" => Some("project")
              case "packages.yml" | "dependencies.yml" => Some("packages")
              case "selectors.yml" => Some("selectors")
              case _ if root.resourceFolders(file).nonEmpty => DbtYaml.psiFile(project, file).flatMap(DbtYaml.mapping)
                .filter(m => m.getKeyValues.asScala.exists(k => Set("models", "sources", "seeds", "snapshots", "macros", "semantic_models",
                  "metrics", "unit_tests", "exposures", "saved_queries", "functions", "checks")(k.getKeyText))).map(_ => "properties")
              case _ => None
            }
            selectedKind.contains(kind) && selectedVersion == version
          }
      }).asJava
}
