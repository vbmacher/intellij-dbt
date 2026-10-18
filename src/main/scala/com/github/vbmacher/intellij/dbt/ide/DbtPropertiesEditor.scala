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

private[ide] final case class DbtPropertyEdit(path: String, before: String, after: String, file: Option[PsiFile]) {
  def apply(project: Project): Unit = WriteCommandAction.writeCommandAction(project).withName("Edit dbt properties").run(new com.intellij.util.ThrowableRunnable[RuntimeException] {
    override def run(): Unit = {
      val documents = PsiDocumentManager.getInstance(project)
      file match {
        case Some(source) =>
          if (documents.getDocument(source).getText != before) throw new IllegalStateException("Properties changed; reopen the editor to review current source.")
          documents.getDocument(source).setText(after); documents.commitAllDocuments()
        case None =>
          val parent = com.intellij.openapi.vfs.LocalFileSystem.getInstance.findFileByPath(path.substring(0, path.lastIndexOf('/')))
          if (parent.findChild(path.substring(path.lastIndexOf('/') + 1)) != null) throw new IllegalStateException("The destination already exists.")
          val created = parent.createChildData(this, path.substring(path.lastIndexOf('/') + 1))
          com.intellij.openapi.vfs.VfsUtil.saveText(created, after)
      }
    }
  })
}
private[ide] object DbtPropertiesEditor {
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
          val values = missing.map(column => s"{name: ${quoted(column.name)}, data_type: ${quoted(column.dataType)}, description: ${quoted(column.description)}}")
          val after = if (values.isEmpty) copy.getText else DbtYaml.value(mapping, "columns") match {
            case Some(sequence: YAMLSequence) =>
              val flow = sequence.getText.trim.startsWith("[")
              val offset = sequence.getTextRange.getEndOffset - (if (flow) 1 else 0)
              val insertion = if (flow) (if (sequence.getItems.isEmpty) "" else ", ") + values.mkString(", ")
                else values.map(v => "\n" + " " * org.jetbrains.yaml.YAMLUtil.getIndentInThisLine(sequence) + "- " + v).mkString
              copy.getText.substring(0, offset) + insertion + copy.getText.substring(offset)
            case None => put(mapping, "columns", values.mkString("[", ", ", "]")); copy.getText
            case _ => throw new IllegalArgumentException("Columns must be a YAML sequence before synchronization.")
          }
          DbtPropertyEdit(path, before, after, source)
        }.toEither.left.map(e => Option(e.getMessage).getOrElse("The properties edit could not preserve the current YAML structure.")) }
      }
    }
  }

  def show(w: DbtWorkspace, resource: DbtSymbol): Unit = {
    val data = DbtResourceData.get(w, resource)
    val rawDescription = (if (resource.mapping.nonEmpty) Vector(resource) else w.properties(resource)).flatMap(_.mapping)
      .flatMap(DbtYaml.string(_, "description")).headOption.getOrElse("")
    val dialog = new DialogWrapper(w.project) {
      private val openingSources = w.files.map(f => (f, f.getModificationStamp, f.getVirtualFile.getModificationStamp))
      private val openingSettings = DbtProjectSettings.get(w.project).getModificationCount
      private val description = new JBTextArea(rawDescription, 5, 70)
      private val tags = new JBTextField(data.tags.mkString(", "))
      private val tests = new JBTextField(data.tests.filter(_.column.isEmpty).map(_.name).mkString(", "))
      private val originalTests = tests.getText
      tests.setEditable(!w.properties(resource).flatMap(_.mapping).exists(m => Vector("tests", "data_tests").flatMap(DbtYaml.value(m, _))
        .collect { case sequence: YAMLSequence => sequence }.exists(_.getItems.asScala.exists(i => Option(i.getValue).exists(_.isInstanceOf[YAMLMapping])))))
      tests.setToolTipText("Parameterized tests retain their YAML arguments/configuration; edit them in their source file.")
      private val yaml = new JBTextArea(14, 70)
      private val panel = new JPanel(new BorderLayout)
      private var proposed = Option.empty[DbtPropertyEdit]
      yaml.setEditable(false)
      private val sync = new JButton("Sync columns and preview YAML")
      private val inherit = new JButton("Inherit empty descriptions")
      private var columns = data.columns.map(c => c.copy(description = w.columns(resource).find(_.name == c.name)
        .flatMap(_.mapping).flatMap(DbtYaml.string(_, "description")).getOrElse(c.description)))
      private val tableModel = new javax.swing.table.DefaultTableModel(Array[AnyRef]("Column", "Type", "Description"), 0) {
        override def isCellEditable(row: Int, column: Int): Boolean = column != 0
      }
      private val table = new JBTable(tableModel)
      columns.foreach(c => tableModel.addRow(Array[AnyRef](c.name, c.dataType, c.description)))
      private def readColumns(): Unit = {
        if (table.isEditing) table.getCellEditor.stopCellEditing()
        columns = columns.indices.map(i => columns(i).copy(dataType = String.valueOf(tableModel.getValueAt(i, 1)),
          description = String.valueOf(tableModel.getValueAt(i, 2)))).toVector
      }
      def preview(): Unit = com.intellij.openapi.application.WriteAction.run(() => {
        readColumns()
        val stale = openingSettings != DbtProjectSettings.get(w.project).getModificationCount || openingSources.exists { case (file, psi, vfs) =>
          !file.isValid || file.getModificationStamp != psi || file.getVirtualFile.getModificationStamp != vfs
        }
        val result = if (stale) Left("Project sources or metadata changed. Reopen the editor to review current values.") else DbtPropertiesEditor.plan(w, resource, description.getText, columns,
          if (tags.getText == data.tags.mkString(", ")) Vector.empty else tags.getText.split(",").map(_.trim).filter(_.nonEmpty).toVector,
          if (tests.getText == originalTests) Vector.empty else tests.getText.split(",").map(_.trim).filter(_.nonEmpty).toVector,
          updateTags = tags.getText != data.tags.mkString(", "), updateTests = tests.getText != originalTests)
        result match {
          case Left(error) => yaml.setText(error); proposed = None
          case Right(edit) => proposed = Some(edit); yaml.setText(edit.path + "\n\n" + edit.after)
        }
      })
      sync.addActionListener(_ => preview())
      inherit.addActionListener(_ => {
        readColumns()
        val inherited = DbtColumnLineage.inherited(w, resource).map { case (name, text, _) => name -> text }.toMap
        columns = columns.map(c => if (c.description.isEmpty) c.copy(description = inherited.getOrElse(c.name, "")) else c)
        columns.indices.foreach(i => tableModel.setValueAt(columns(i).description, i, 2))
        preview()
      })
      private val diff = new JButton("View YAML diff")
      diff.addActionListener(_ => { preview(); proposed.foreach(edit => {
        val factory = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(w.project, new SimpleDiffRequest(edit.path, factory.create(edit.before), factory.create(edit.after), "Current source", "Proposed properties"))
      }) })
      val actions = new JPanel; actions.add(sync); actions.add(inherit); actions.add(diff)
      val editor = new JPanel(new BorderLayout); editor.add(new JBLabel("Description (doc() expressions are preserved):"), BorderLayout.NORTH)
      editor.add(new JBScrollPane(description), BorderLayout.CENTER)
      val fields = new JPanel(new java.awt.GridLayout(2, 2)); fields.add(new JBLabel("Tags (comma-separated):")); fields.add(tags)
      fields.add(new JBLabel("Model data tests (simple names):")); fields.add(tests); editor.add(fields, BorderLayout.SOUTH)
      val center = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, new JBScrollPane(table), new JBScrollPane(yaml))
      panel.add(editor, BorderLayout.NORTH); panel.add(center, BorderLayout.CENTER); panel.add(actions, BorderLayout.SOUTH)
      setTitle("Edit dbt properties: " + resource.presentableText); setOKButtonText("Apply"); init()
      override def createCenterPanel(): JComponent = panel
      override def doOKAction(): Unit = {
        preview(); proposed.foreach { edit => edit.apply(w.project); super.doOKAction() }
      }
    }
    dialog.show()
  }
}
