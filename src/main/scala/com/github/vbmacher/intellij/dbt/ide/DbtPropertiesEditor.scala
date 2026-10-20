package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{DialogWrapper, ValidationInfo}
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiFileFactory}
import com.intellij.ui.components.{JBLabel, JBScrollPane, JBTextArea, JBTextField}
import com.intellij.ui.table.JBTable
import org.jetbrains.yaml.{YAMLElementGenerator, YAMLFileType}
import org.jetbrains.yaml.psi.{YAMLMapping, YAMLSequence}
import java.awt.BorderLayout
import javax.swing.{JButton, JComponent, JPanel}
import scala.jdk.CollectionConverters._

private[ide] final case class DbtPropertyEdit(path: String, before: String, after: String, file: Option[PsiFile],
  directory: Option[com.intellij.openapi.vfs.VirtualFile] = None) {
  def apply(project: Project): Unit = WriteCommandAction.writeCommandAction(project).withName("Edit dbt properties").run(new com.intellij.util.ThrowableRunnable[RuntimeException] {
    override def run(): Unit = {
      val documents = PsiDocumentManager.getInstance(project)
      file match {
        case Some(source) =>
          if (documents.getDocument(source).getText != before) throw new IllegalStateException("Properties changed; reopen the editor to review current source.")
          documents.getDocument(source).setText(after); documents.commitAllDocuments()
        case None =>
          val parent = directory.orElse(Option(com.intellij.openapi.vfs.LocalFileSystem.getInstance
            .findFileByPath(path.substring(0, path.lastIndexOf('/'))))).getOrElse(
            throw new IllegalStateException("The destination directory is unavailable. Reopen the editor."))
          if (parent.findChild(path.substring(path.lastIndexOf('/') + 1)) != null) throw new IllegalStateException("The destination already exists.")
          val created = parent.createChildData(this, path.substring(path.lastIndexOf('/') + 1))
          com.intellij.openapi.vfs.VfsUtil.saveText(created, after)
      }
    }
  })
}
private[ide] object DbtPropertiesEditor {
  def editableColumns(w: DbtWorkspace, resource: DbtSymbol): Vector[DbtColumnData] =
    DbtResourceData.get(w, resource).columns.map(c => c.copy(description = w.columns(resource).find(_.name == c.name)
      .flatMap(_.mapping).flatMap(DbtYaml.string(_, "description")).getOrElse("")))

  /** Review text and exact navigable definitions for the selected output column. */
  def origins(w: DbtWorkspace, resource: DbtSymbol, column: String): (String, Vector[DbtSymbol]) = {
    val edges = DbtColumnLineage.upstream(w, resource, column).filter(_.kind == "value")
    val inputs = edges.map(_.input).distinctBy(_.id)
    val state = if (edges.isEmpty || !edges.forall(_.complete)) "Unknown lineage"
      else if (inputs.size == 1 && edges.forall(_.direct)) "Passthrough"
      else "Calculated / multiple inputs"
    val names = inputs.map(input => input.namespace + "." + Option(input.owner).filter(_.nonEmpty).map(_ + ".").getOrElse("") +
      input.name + input.version.map(" v" + _).getOrElse("") + " · " + input.file.getName)
    (state + (if (names.isEmpty) "" else ": " + names.mkString(", ")), inputs)
  }
  def plan(w: DbtWorkspace, resource: DbtSymbol, description: String, columns: Vector[DbtColumnData],
    tags: Vector[String], tests: Vector[String], updateTags: Boolean = false, updateTests: Boolean = false): Either[String, DbtPropertyEdit] = {
    val root = w.owner(resource).toRight("The owning project is unavailable.")
    root.flatMap { r =>
      val properties = (if (resource.kind == "source_table" || resource.isProperty) Vector(resource) else w.properties(resource)).filterNot(_.metadata)
      if (resource.metadata || w.packageOwner(r).nonEmpty || !resource.file.isWritable) Left("Choose a writable local source definition.")
      else if (properties.size > 1) Left("Multiple properties entries exist; choose an unambiguous declaration.")
      else if (properties.exists(p => !p.file.isWritable)) Left("The properties declaration is read-only.")
      else if (resource.version.nonEmpty && properties.isEmpty) Left("Create explicit model-version properties before editing this version.")
      else if ((updateTests || tests.nonEmpty) && properties.flatMap(_.mapping).exists(m => Vector("tests", "data_tests").flatMap(DbtYaml.value(m, _))
        .collect { case sequence: YAMLSequence => sequence }.exists(_.getItems.asScala.exists(i => Option(i.getValue).exists(_.isInstanceOf[YAMLMapping])))))
        Left("This resource has parameterized tests. Edit those declarations in YAML to preserve their arguments and configuration.")
      else {
        val inheritedColumns = resource.version.nonEmpty && properties.flatMap(_.mapping).exists(m => DbtYaml.value(m, "columns").isEmpty)
        val inheritedChanges = inheritedColumns && columns.exists { column =>
          w.columns(resource).find(_.name == column.name).forall { existing =>
            column.dataType != existing.attributes.getOrElse("data_type", "") ||
              column.description != existing.mapping.flatMap(DbtYaml.string(_, "description")).getOrElse(existing.attributes.getOrElse("description", ""))
          }
        }
        if (inheritedChanges) return Left("This version inherits its columns. Edit the base columns or create explicit version overrides in YAML before synchronizing.")
        val editedColumns = if (inheritedColumns) Vector.empty else columns
        val source = properties.headOption.map(_.file)
        val path = source.map(_.getVirtualFile.getPath).getOrElse(resource.file.getVirtualFile.getParent.getPath + "/" + resource.name + ".yml")
        val before = source.map(_.getText).getOrElse("")
        val section = Map("model" -> "models", "seed" -> "seeds", "snapshot" -> "snapshots", "macro" -> "macros").get(resource.kind)
        if (source.isEmpty && section.isEmpty) return Left("Create this resource's properties declaration in YAML before editing it.")
        val initial = if (source.nonEmpty) before else s"version: 2\n${section.get}:\n  - name: ${resource.name}\n"
        val copy = PsiFileFactory.getInstance(w.project).createFileFromText("properties.yml", YAMLFileType.YML, initial)
        val offset = properties.headOption.flatMap(_.mapping).map(_.getTextOffset)
        val target = if (source.nonEmpty) offset.flatMap(o => Option(copy.findElementAt(o)))
          .flatMap(e => Iterator.iterate(e)(_.getParent).takeWhile(_ != null).collectFirst { case m: YAMLMapping => m })
          else DbtYaml.mapping(copy).toVector.flatMap(DbtYaml.items(_, section.get)).headOption
        target.toRight("The properties mapping could not be located.").flatMap { mapping => scala.util.Try {
          val generator = YAMLElementGenerator.getInstance(w.project)
          def quoted(value: String): String = new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(value)
          def put(m: YAMLMapping, key: String, value: String): Unit = {
            val replacement = generator.createYamlKeyValue(key, value)
            m.getKeyValues.asScala.find(_.getKeyText == key) match {
              case Some(existing) if existing.getValue != null =>
                if (com.intellij.psi.util.PsiTreeUtil.findChildOfType(existing.getValue, classOf[org.jetbrains.yaml.psi.YAMLAnchor]) != null)
                  throw new IllegalArgumentException(s"'$key' defines a YAML anchor. Edit it in source to preserve its aliases.")
                if (com.intellij.psi.util.PsiTreeUtil.findChildOfType(existing.getValue, classOf[com.intellij.psi.PsiComment]) != null)
                  throw new IllegalArgumentException(s"'$key' contains comments. Edit its sequence in source to preserve those comments.")
                existing.setValue(replacement.getValue)
              case None if m.getFirstChild.getNode.getElementType == org.jetbrains.yaml.YAMLTokenTypes.LBRACE =>
                val closing = m.getLastChild
                m.getKeyValues.asScala.lastOption.foreach { previous =>
                  val trailingComma = Iterator.iterate(previous.getNextSibling)(_.getNextSibling)
                    .takeWhile(e => e != null && e != closing).exists(_.getNode.getElementType == org.jetbrains.yaml.YAMLTokenTypes.COMMA)
                  if (!trailingComma) m.addAfter(generator.createComma(), previous)
                }
                // YAMLHashImpl.putKeyValue does not reliably attach its separator to the inserted copy.
                // Append with explicit native PSI tokens, retaining existing key order and comments.
                m.addBefore(generator.createSpace(), closing)
                m.addBefore(replacement, closing)
              case _ => m.putKeyValue(replacement)
            }
          }
          if (!DbtYaml.string(mapping, "description").contains(description)) put(mapping, "description", quoted(description))
          if (tags.nonEmpty || updateTags) {
            val target = DbtYaml.map(mapping, "config").filter(m => DbtYaml.value(m, "tags").nonEmpty).getOrElse(mapping)
            put(target, "tags", tags.map(quoted).mkString("[", ", ", "]"))
          }
          val testKey = if (r.supports(8)) "data_tests" else "tests"
          if (tests.nonEmpty || updateTests) {
            val actualKey = if (DbtYaml.value(mapping, "tests").nonEmpty && DbtYaml.value(mapping, "data_tests").isEmpty) "tests" else testKey
            put(mapping, actualKey, tests.map(quoted).mkString("[", ", ", "]"))
          }
          val missing = editedColumns.filterNot(column => DbtYaml.items(mapping, "columns").exists(m => DbtYaml.string(m, "name").contains(column.name)))
          editedColumns.foreach { column =>
            val existing = DbtYaml.items(mapping, "columns").find(m => DbtYaml.string(m, "name").contains(column.name))
            existing match {
              case Some(m) =>
                if (!DbtYaml.string(m, "description").contains(column.description)) put(m, "description", quoted(column.description))
                if (column.dataType.nonEmpty && !DbtYaml.string(m, "data_type").contains(column.dataType)) put(m, "data_type", quoted(column.dataType))
              case None => ()
            }
          }
          val values = missing.map { column =>
            val dataType = if (column.dataType.nonEmpty) s", data_type: ${quoted(column.dataType)}" else ""
            s"{name: ${quoted(column.name)}$dataType, description: ${quoted(column.description)}}"
          }
          val after = if (values.isEmpty) copy.getText else DbtYaml.value(mapping, "columns") match {
            case Some(sequence: YAMLSequence) =>
              val flow = sequence.getText.trim.startsWith("[")
              val offset = sequence.getTextRange.getEndOffset - (if (flow) 1 else 0)
              val insertion = if (flow) (if (sequence.getItems.isEmpty) "" else ", ") + values.mkString(", ")
                else values.map(v => "\n" + " " * org.jetbrains.yaml.YAMLUtil.getIndentInThisLine(sequence) + "- " + v).mkString
              copy.getText.substring(0, offset) + insertion + copy.getText.substring(offset)
            case None =>
              // YAML's key generator moves long flow sequences to a new line
              // with root indentation. Insert at the native mapping range so
              // new columns remain children of this resource.
              val text = copy.getText
              val flow = mapping.getText.trim.startsWith("{")
              val offset = mapping.getTextRange.getEndOffset - (if (flow) 1 else 0)
              val firstKey = mapping.getKeyValues.asScala.head.getTextOffset
              val indent = firstKey - text.lastIndexOf('\n', firstKey - 1) - 1
              val prefix = if (flow) (if (mapping.getKeyValues.isEmpty) "" else ", ") else "\n" + " " * indent
              text.substring(0, offset) + prefix + "columns: " + values.mkString("[", ", ", "]") + text.substring(offset)
            case _ => throw new IllegalArgumentException("Columns must be a YAML sequence before synchronization.")
          }
          DbtPropertyEdit(path, before, after, source, Some(resource.file.getVirtualFile.getParent))
        }.toEither.left.map(e => Option(e.getMessage).getOrElse("The properties edit could not preserve the current YAML structure.")) }
      }
    }
  }

  def show(w: DbtWorkspace, resource: DbtSymbol): Unit = {
    val properties = (if (resource.kind == "source_table" || resource.isProperty) Vector(resource) else w.properties(resource)).distinctBy(_.id)
    properties match {
      case Vector(one) => one.element.navigate(true)
      case entries if entries.nonEmpty => DbtNavigationItem.popup("dbt properties", entries.map(s => DbtNavigationItem.declaration(s.element)))
        .showCenteredInCurrentWindow(w.project)
      case _ => generate(w, resource, inherit = false)
    }
  }

  private[ide] def generatedColumns(w: DbtWorkspace, resource: DbtSymbol, inherit: Boolean): Vector[DbtColumnData] =
    if (inherit) DbtColumnLineage.inherited(w, resource).map { case (name, text, _) => DbtColumnData(name, "", text) }
    else editableColumns(w, resource)

  def generate(w: DbtWorkspace, resource: DbtSymbol, inherit: Boolean): Unit = {
    val description = (Vector(resource) ++ w.properties(resource)).flatMap(_.mapping)
      .flatMap(DbtYaml.string(_, "description")).headOption.getOrElse("")
    val proposedColumns = generatedColumns(w, resource, inherit)
    val prepared = if (inherit && proposedColumns.isEmpty) Left("No empty descriptions have an unambiguous documented passthrough source.")
      else com.intellij.openapi.application.WriteAction.compute[Either[String, DbtPropertyEdit], RuntimeException](() =>
        plan(w, resource, description, proposedColumns, Vector.empty, Vector.empty))
    prepared match {
      case Left(error) => com.intellij.openapi.ui.Messages.showInfoMessage(w.project, error, "Generate dbt properties")
      case Right(edit) =>
        val sources = w.files.map(f => (f, f.getModificationStamp))
        val settings = DbtProjectSettings.get(w.project).getModificationCount
        new DialogWrapper(w.project) {
          private val diff = DiffManager.getInstance.createRequestPanel(w.project, getDisposable, null)
          private val factory = DiffContentFactory.getInstance()
          diff.setRequest(new SimpleDiffRequest(edit.path, factory.create(edit.before), factory.create(edit.after), "Current YAML", "Generated YAML"))
          setTitle(if (inherit) "Inherit empty dbt descriptions" else "Generate dbt columns")
          setOKButtonText("Apply"); init()
          override def createCenterPanel(): JComponent = {
            val component = diff.getComponent; component.setPreferredSize(new java.awt.Dimension(850, 550)); component
          }
          override def doValidate(): ValidationInfo =
            if (settings != DbtProjectSettings.get(w.project).getModificationCount || sources.exists { case (f, stamp) => !f.isValid || f.getModificationStamp != stamp })
              new ValidationInfo("Source or settings changed. Generate the properties again.") else null
          override def doOKAction(): Unit = if (doValidate() == null) { edit.apply(w.project); super.doOKAction() }
        }.show()
    }
  }
}
