package com.github.vbmacher.intellij.dbt.ide

import com.google.gson.{JsonArray, JsonElement, JsonNull, JsonParser}
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.fileChooser.{FileChooserFactory, FileSaverDescriptor}
import com.intellij.ui.components.{JBLabel, JBScrollPane, JBTextField}
import com.intellij.ui.table.JBTable
import java.awt.{BorderLayout, Component}
import java.nio.file.Files
import javax.swing.{JComboBox, JPanel, JSpinner, JTable, RowFilter, SpinnerNumberModel, SwingConstants}
import javax.swing.table.{AbstractTableModel, DefaultTableCellRenderer, TableRowSorter}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try

private[ide] final case class DbtQueryResult(columns: Vector[String], rows: Vector[Vector[JsonElement]], target: String,
  limit: Int, elapsedMillis: Long, sql: String, raw: String) {
  def value(row: Int, column: Int): AnyRef = {
    val element = rows(row)(column)
    if (element.isJsonNull) null
    else if (element.isJsonPrimitive) {
      val p = element.getAsJsonPrimitive
      if (p.isBoolean) Boolean.box(p.getAsBoolean) else if (p.isNumber) p.getAsBigDecimal else p.getAsString
    } else element.toString
  }

  /** Quoted strings distinguish empty text and the literal "NULL" from SQL NULL in CSV and TSV. */
  def `export`(delimiter: Char = ',', selectedRows: Vector[Int] = rows.indices.toVector,
    selectedColumns: Vector[Int] = columns.indices.toVector, includeHeaders: Boolean = true): String = {
    def quote(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

    def cell(row: Int, column: Int): String = {
      val raw = rows(row)(column)
      if (raw.isJsonNull) "NULL"
      else if (raw.isJsonPrimitive && (raw.getAsJsonPrimitive.isNumber || raw.getAsJsonPrimitive.isBoolean)) value(row, column).toString
      else quote(if (raw.isJsonPrimitive) raw.getAsString else raw.toString)
    }

    val header = if (includeHeaders && selectedColumns.nonEmpty) Vector(selectedColumns.map(c => quote(columns(c)))) else Vector.empty
    val body = selectedRows.map(r => selectedColumns.map(c => cell(r, c)))
    val lines = header ++ body
    if (lines.isEmpty) "" else lines.map(_.mkString(delimiter.toString)).mkString("\r\n") + "\r\n"
  }
}

private[ide] object DbtQueryResult {
  def parse(raw: String, target: String, limit: Int, elapsed: Long, sql: String): Either[String, DbtQueryResult] =
    DbtPreview.rows(raw).toRight("Preview JSON was unavailable. See raw dbt output.").flatMap { array =>
      val objects = array.asScala.toVector
      if (!objects.forall(_.isJsonObject)) Left("Preview contains malformed rows. See raw dbt output.")
      else {
        val boundedLimit = DbtCommandRequest.limit(limit)
        val bounded = objects.take(boundedLimit)
        val columns = bounded.flatMap(_.getAsJsonObject.keySet().asScala).distinct
        val rows = bounded.map(o => columns.map(c => Option(o.getAsJsonObject.get(c)).getOrElse(JsonNull.INSTANCE)))
        Right(DbtQueryResult(columns, rows, target, boundedLimit, elapsed, sql, raw))
      }
    }
}

private[ide] final class DbtResultGrid(project: com.intellij.openapi.project.Project, onLimit: Int => Unit = null) extends JPanel(new BorderLayout) {
  private val label = new JBLabel("Run an explicit preview to see rows.")
  private val filter = new JBTextField(20)
  filter.getAccessibleContext.setAccessibleName("Filter preview rows")
  private val table = new JBTable
  table.getAccessibleContext.setAccessibleName("dbt preview results")
  private var result = Option.empty[DbtQueryResult]
  private var activeSorter = Option.empty[TableRowSorter[AbstractTableModel]]
  private var running = false
  private val bar = DbtUi.buttons()
  private val filterLabel = new JBLabel("Filter:")
  filterLabel.setLabelFor(filter)
  bar.add(filterLabel);
  bar.add(filter)
  private val limit = new JSpinner(new SpinnerNumberModel(20, 1, 1000, 10))
  limit.getAccessibleContext.setAccessibleName("Preview row limit")
  private val rerun = DbtUi.button("Run with limit") {
    if (onLimit != null && result.nonEmpty && !running)
      scala.util.Try(limit.commitEdit()).toOption.foreach(_ => onLimit(limit.getValue.asInstanceOf[Number].intValue()))
  }
  bar.add(limit);
  bar.add(rerun)
  private val copy = DbtUi.button("Copy selection") {
    if (table.getSelectedRowCount > 0 && table.getSelectedColumnCount > 0) DbtUi.copy(exportText('\t', selectedOnly = true))
  }
  private val format = new JComboBox[String](Array("CSV", "TSV"))
  format.getAccessibleContext.setAccessibleName("Preview export format")
  private val `export` = DbtUi.button("Export CSV")(result.foreach { _ =>
    val extension = if (format.getSelectedItem == "TSV") "tsv" else "csv"
    Option(FileChooserFactory.getInstance().createSaveFileDialog(
      new FileSaverDescriptor("Export dbt preview", "Save preview rows", extension), project)
      .save("preview." + extension)).foreach(file => exportTo(file.getFile.toPath, overwriteConfirmed = true))
  })

  private[ide] def exportTo(destination: java.nio.file.Path, overwriteConfirmed: Boolean = false): Unit = {
    val replace = overwriteConfirmed || !Files.exists(destination) || com.intellij.openapi.ui.Messages.showYesNoDialog(project,
      s"Replace ${destination.getFileName}? Its current contents will be lost.", "Replace preview export",
      "Replace", "Cancel", com.intellij.openapi.ui.Messages.getWarningIcon) == com.intellij.openapi.ui.Messages.YES
    if (replace) {
      val snapshot = exportSnapshot(if (format.getSelectedItem == "TSV") '\t' else ',')
      new Task.Backgroundable(project, "Export dbt preview", true) {
        override def run(indicator: ProgressIndicator): Unit = {
          indicator.checkCanceled()
          val text = snapshot()
          indicator.checkCanceled()
          Files.writeString(destination, text)
        }

        override def onThrowable(error: Throwable): Unit =
          DbtNotifications.show(project, "Cannot export dbt preview", Option(error.getMessage).getOrElse(error.toString))
      }.queue()
    }
  }
  format.addActionListener(_ => export.setText("Export " + format.getSelectedItem))
  private val sql = DbtUi.button("Open SQL")(result.foreach(r => DbtSqlEditors.open(project, "dbt preview", r.sql)))
  Vector[Component](copy, format, export, sql).foreach(bar.add)
  private val header = new JPanel(new BorderLayout)
  header.add(label, BorderLayout.NORTH)
  header.add(bar, BorderLayout.CENTER)
  add(header, BorderLayout.NORTH);
  add(new JBScrollPane(table), BorderLayout.CENTER)
  table.setCellSelectionEnabled(true);
  table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF)
  private val renderer = new DefaultTableCellRenderer {
    putClientProperty("html.disable", java.lang.Boolean.TRUE)

    override def getTableCellRendererComponent(table: JTable, value: Any, selected: Boolean, focus: Boolean, row: Int, column: Int): Component = {
      super.getTableCellRendererComponent(table, value, selected, focus, row, column)
      setText(if (value == null) "NULL" else value.toString)
      setHorizontalAlignment(if (value.isInstanceOf[Number]) SwingConstants.RIGHT else SwingConstants.LEFT)
      if (value == null && !selected) setForeground(com.intellij.util.ui.UIUtil.getInactiveTextColor)
      setToolTipText(if (value == null) "SQL NULL (not an empty string)" else null)
      this
    }
  }
  Vector[Class[_]](classOf[Object], classOf[Number], classOf[java.lang.Boolean], classOf[String]).foreach(table.setDefaultRenderer(_, renderer))

  private def updateButtons(): Unit = {
    copy.setEnabled(result.nonEmpty && table.getSelectedRowCount > 0 && table.getSelectedColumnCount > 0)
    export.setEnabled(result.nonEmpty);
    sql.setEnabled(result.nonEmpty)
    rerun.setEnabled(result.nonEmpty && onLimit != null && !running)
    limit.setEnabled(onLimit != null && !running)
  }

  table.getSelectionModel.addListSelectionListener(_ => updateButtons())
  table.getColumnModel.getSelectionModel.addListSelectionListener(_ => updateButtons())

  /** Capture Swing view indices now; serialization can run after the grid changes or closes. */
  private[ide] def exportSnapshot(delimiter: Char, selectedOnly: Boolean = false): () => String = result.fold[() => String](() => "") { r =>
    val rows = if (selectedOnly) table.getSelectedRows.toVector else (0 until table.getRowCount).toVector
    val columns = if (selectedOnly) table.getSelectedColumns.toVector else (0 until table.getColumnCount).toVector
    val modelRows = rows.map(table.convertRowIndexToModel)
    val modelColumns = columns.map(table.convertColumnIndexToModel)
    () => r.export(delimiter, modelRows, modelColumns, includeHeaders = !selectedOnly)
  }

  private[ide] def exportText(delimiter: Char, selectedOnly: Boolean = false): String = exportSnapshot(delimiter, selectedOnly)()

  def setRunning(value: Boolean): Unit = {
    running = value;
    updateButtons()
  }

  def clear(message: String): Unit = {
    result = None;
    activeSorter = None;
    label.setText(message)
    label.setToolTipText(message)
    table.setRowSorter(null);
    table.setModel(new javax.swing.table.DefaultTableModel);
    updateButtons()
  }

  def showResult(r: DbtQueryResult): Unit = {
    result = Some(r);
    limit.setValue(Int.box(DbtCommandRequest.limit(r.limit)))
    label.setText(s"Target: ${r.target} · Returned ${r.rows.size} / limit ${r.limit} · ${r.elapsedMillis} ms" +
      (if (r.rows.size >= r.limit) " · May be truncated" else if (r.rows.isEmpty) " · No data returned" else ""))
    label.setToolTipText(label.getText)
    val columnTypes = r.columns.indices.map { column =>
      val classes = r.rows.indices.map(row => r.value(row, column)).filter(_ != null).map(_.getClass).distinct
      if (classes.size == 1) classes.head else classOf[Object]
    }.toVector
    val model = new AbstractTableModel {
      override def getRowCount: Int = r.rows.size

      override def getColumnCount: Int = r.columns.size

      override def getColumnName(column: Int): String = r.columns(column)

      override def getValueAt(row: Int, column: Int): AnyRef = r.value(row, column)

      override def getColumnClass(column: Int): Class[_] = columnTypes(column)
    }
    table.setRowSorter(null);
    table.setModel(model)
    val sorter = new TableRowSorter[AbstractTableModel](model)
    r.columns.indices.foreach { c =>
      sorter.setComparator(c, new java.util.Comparator[AnyRef] {
        override def compare(a: AnyRef, b: AnyRef): Int = (a, b) match {
          case (x: java.math.BigDecimal, y: java.math.BigDecimal) => x.compareTo(y)
          case _ => String.valueOf(a).compareTo(String.valueOf(b))
        }
      })
      table.getColumnModel.getColumn(c).setPreferredWidth(160)
    }
    table.setRowSorter(sorter);
    activeSorter = Some(sorter);
    updateFilter();
    updateButtons()
  }

  filter.getDocument.addDocumentListener(new com.intellij.ui.DocumentAdapter {
    override def textChanged(event: javax.swing.event.DocumentEvent): Unit = updateFilter()
  })

  private def updateFilter(): Unit = activeSorter.foreach { sorter =>
    val text = filter.getText.toLowerCase(java.util.Locale.ROOT)
    sorter.setRowFilter(new RowFilter[AbstractTableModel, Integer] {
      override def include(entry: RowFilter.Entry[_ <: AbstractTableModel, _ <: Integer]): Boolean =
        (0 until entry.getValueCount).exists(c => Option(entry.getValue(c)).fold("NULL")(_.toString).toLowerCase(java.util.Locale.ROOT).contains(text))
    })
    updateButtons()
  }

  updateButtons()
}

private[ide] object DbtPreview {
  private[ide] val Ansi = "\u001b\\[[0-9;]*[A-Za-z]".r

  def rows(output: String): Option[JsonArray] = {
    val plain = Ansi.replaceAllIn(output, "")

    def rows(element: JsonElement): Option[JsonArray] = {
      if (element.isJsonArray) Some(element.getAsJsonArray)
      else if (element.isJsonObject) {
        val obj = element.getAsJsonObject
        Option(obj.get("preview")).flatMap(e => if (e.isJsonPrimitive && e.getAsJsonPrimitive.isString)
            Try(JsonParser.parseString(e.getAsString)).toOption.flatMap(rows) else rows(e))
          .orElse(Option(obj.get("data")).flatMap(rows))
          .orElse(Option(obj.get("show")).flatMap(rows))
      } else None
    }

    // dbt's ordinary log format prints a multiline {"show": [...]} object among human-readable messages.
    // Extract balanced JSON values without assuming one log record per line or rewriting string contents.
    val values = ArrayBuffer.empty[String]
    var stack = List.empty[(Char, Int)]
    var quoted = false
    var escaped = false
    plain.indices.foreach { i =>
      val c = plain(i)
      if (quoted) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') quoted = false
      } else if (c == '"' && stack.nonEmpty) quoted = true
      else if (c == '{' || c == '[') stack = (c -> i) :: stack
      else if (c == '}' || c == ']') stack match {
        case (open, start) :: rest if open == (if (c == '}') '{' else '[') => values += plain.substring(start, i + 1); stack = rest
        case _ => stack = Nil
      }
    }
    val candidates = Vector(plain) ++ values
    candidates.iterator.flatMap(s => Try(JsonParser.parseString(s.trim)).toOption.flatMap(rows)).toVector.lastOption
  }

  def format(output: String): String = {
    rows(output) match {
      case Some(array) if array.isEmpty => "No data returned."
      case Some(array) =>
        val objects = array.asScala.filter(_.isJsonObject).map(_.getAsJsonObject).toVector
        val headers = objects.flatMap(_.keySet().asScala).distinct
        if (headers.isEmpty) "No data returned."
        else {
          val values = objects.map(o => headers.map(h => Option(o.get(h)).filterNot(_.isJsonNull)
            .map(e => if (e.isJsonPrimitive) e.getAsString else e.toString).getOrElse("null").replace("\n", "\\n")))
          val widths = headers.indices.map(i => (headers(i).length +: values.map(_(i).length)).max)

          def row(xs: Seq[String]): String = xs.indices.map(i => xs(i).padTo(widths(i), ' ')).mkString(" | ")

          row(headers) + "\n" + widths.map(n => "-" * n).mkString("-+-") + "\n" + values.map(row).mkString("\n")
        }
      case _ => "Preview JSON was unavailable. See dbt output for details."
    }
  }
}
