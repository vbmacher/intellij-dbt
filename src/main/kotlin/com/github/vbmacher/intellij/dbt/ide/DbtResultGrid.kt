package com.github.vbmacher.intellij.dbt.ide

import com.google.gson.*
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.nio.file.Files
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/** Bounded preview rows retaining JSON types, SQL NULL and the original process output for inspection. */
internal data class DbtQueryResult(
    val columns: List<String>, val rows: List<List<JsonElement>>, val target: String,
    val limit: Int, val elapsedMillis: Long, val sql: String, val raw: String
) {
    fun value(row: Int, column: Int): Any? {
        val element = rows[row][column]
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> element.asJsonPrimitive.let { p ->
                when {
                    p.isBoolean -> p.asBoolean; p.isNumber -> p.asBigDecimal; else -> p.asString
                }
            }

            else -> element.toString()
        }
    }

    /** Quoted strings distinguish empty text and the literal "NULL" from SQL NULL in CSV and TSV. */
    fun export(
        delimiter: Char = ',',
        selectedRows: List<Int> = rows.indices.toList(),
        selectedColumns: List<Int> = columns.indices.toList(),
        includeHeaders: Boolean = true
    ): String {
        fun quote(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""
        fun cell(row: Int, column: Int): String {
            val raw = rows[row][column]
            return when {
                raw.isJsonNull -> "NULL"; raw.isJsonPrimitive && (raw.asJsonPrimitive.isNumber || raw.asJsonPrimitive.isBoolean) -> value(
                    row,
                    column
                ).toString()

                else -> quote(if (raw.isJsonPrimitive) raw.asString else raw.toString())
            }
        }

        val header =
            if (includeHeaders && selectedColumns.isNotEmpty()) listOf(selectedColumns.map { quote(columns[it]) }) else emptyList()
        val lines = header + selectedRows.map { r -> selectedColumns.map { c -> cell(r, c) } }
        return if (lines.isEmpty()) "" else lines.joinToString("\r\n") { it.joinToString(delimiter.toString()) } + "\r\n"
    }

    companion object {
        /** Rejects non-object rows, clamps the limit and derives columns only from rows inside that limit. */
        fun parse(raw: String, target: String, limit: Int, elapsed: Long, sql: String): Result<DbtQueryResult> =
            dbtResult {
                val array = DbtPreview.rows(raw)
                    ?: throw IllegalArgumentException("Preview JSON was unavailable. See raw dbt output.")
                val objects = array.toList()
                require(objects.all { it.isJsonObject }) { "Preview contains malformed rows. See raw dbt output." }
                val boundedLimit = DbtCommandRequest.limit(limit);
                val bounded = objects.take(boundedLimit)
                val columns = bounded.flatMap { it.asJsonObject.keySet() }.distinct()
                val rows = bounded.map { o -> columns.map { o.asJsonObject[it] ?: JsonNull.INSTANCE } }
                DbtQueryResult(columns, rows, target, boundedLimit, elapsed, sql, raw)
            }
    }
}

/**
 * Swing view of an existing result: sorting, filtering, selection and exports do not execute queries.
 * Only the explicit limit button calls [onLimit]; its owner prepares and launches any replacement query.
 */
internal class DbtResultGrid(
    private val project: com.intellij.openapi.project.Project,
    private val onLimit: ((Int) -> Unit)? = null
) : JPanel(BorderLayout()) {
    private val label = JBLabel("Run an explicit preview to see rows.")
    private val filter = JBTextField(20).apply { accessibleContext.accessibleName = "Filter preview rows" }
    private val table = JBTable().apply { accessibleContext.accessibleName = "dbt preview results" }
    private var result: DbtQueryResult? = null
    private var activeSorter: TableRowSorter<AbstractTableModel>? = null
    private var running = false
    private val bar = DbtUi.buttons()
    private val filterLabel = JBLabel("Filter:").apply { labelFor = filter }
    private val limit =
        JSpinner(SpinnerNumberModel(20, 1, 1000, 10)).apply { accessibleContext.accessibleName = "Preview row limit" }
    private val rerun = DbtUi.button("Run with limit") {
        if (onLimit != null && result != null && !running) dbtResult { limit.commitEdit() }.onSuccess { onLimit((limit.value as Number).toInt()) }
    }
    private val copy = DbtUi.button("Copy selection") {
        if (table.selectedRowCount > 0 && table.selectedColumnCount > 0) DbtUi.copy(
            exportText(
                '\t',
                selectedOnly = true
            )
        )
    }
    private val format =
        JComboBox(arrayOf("CSV", "TSV")).apply { accessibleContext.accessibleName = "Preview export format" }
    private val export = DbtUi.button("Export CSV") {
        if (result != null) {
            val extension = if (format.selectedItem == "TSV") "tsv" else "csv"
            FileChooserFactory.getInstance().createSaveFileDialog(
                FileSaverDescriptor("Export dbt preview", "Save preview rows", extension),
                project
            )
                .save("preview.$extension")?.let { exportTo(it.file.toPath(), overwriteConfirmed = true) }
        }
    }
    private val sql = DbtUi.button("Open SQL") { result?.let { DbtSqlEditors.open(project, "dbt preview", it.sql) } }

    init {
        bar.add(filterLabel); bar.add(filter); bar.add(limit); bar.add(rerun)
        format.addActionListener { export.text = "Export " + format.selectedItem }
        listOf<Component>(copy, format, export, sql).forEach(bar::add)
        val header = JPanel(BorderLayout()); header.add(label, BorderLayout.NORTH); header.add(bar, BorderLayout.CENTER)
        add(header, BorderLayout.NORTH); add(JBScrollPane(table), BorderLayout.CENTER)
        table.cellSelectionEnabled = true; table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val renderer = object : DefaultTableCellRenderer() {
            init {
                putClientProperty("html.disable", true)
            }

            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                selected: Boolean,
                focus: Boolean,
                row: Int,
                column: Int
            ): Component {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column)
                text = value?.toString() ?: "NULL"
                horizontalAlignment = if (value is Number) SwingConstants.RIGHT else SwingConstants.LEFT
                if (value == null && !selected) foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                toolTipText = if (value == null) "SQL NULL (not an empty string)" else null
                return this
            }
        }
        listOf(
            Any::class.java,
            Number::class.java,
            java.lang.Boolean::class.java,
            String::class.java
        ).forEach { table.setDefaultRenderer(it, renderer) }
        table.selectionModel.addListSelectionListener { updateButtons() }
        table.columnModel.selectionModel.addListSelectionListener { updateButtons() }
        filter.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) {
                updateFilter()
            }
        })
        updateButtons()
    }

    fun exportTo(destination: java.nio.file.Path, overwriteConfirmed: Boolean = false) {
        val replace =
            overwriteConfirmed || !Files.exists(destination) || com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "Replace ${destination.fileName}? Its current contents will be lost.",
                "Replace preview export",
                "Replace",
                "Cancel",
                com.intellij.openapi.ui.Messages.getWarningIcon()
            ) == com.intellij.openapi.ui.Messages.YES
        if (replace) {
            val snapshot = exportSnapshot(if (format.selectedItem == "TSV") '\t' else ',')
            object : Task.Backgroundable(project, "Export dbt preview", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.checkCanceled();
                    val text = snapshot(); indicator.checkCanceled(); Files.writeString(destination, text)
                }

                override fun onThrowable(error: Throwable) {
                    DbtNotifications.show(project, "Cannot export dbt preview", error.message ?: error.toString())
                }
            }.queue()
        }
    }

    private fun updateButtons() {
        copy.isEnabled = result != null && table.selectedRowCount > 0 && table.selectedColumnCount > 0
        export.isEnabled = result != null; sql.isEnabled = result != null
        rerun.isEnabled = result != null && onLimit != null && !running; limit.isEnabled = onLimit != null && !running
    }

    /**
     * Captures the current Swing view order/selection and result on the UI thread.
     * The returned serializer no longer reads Swing state and may run after the grid changes or closes.
     */
    fun exportSnapshot(delimiter: Char, selectedOnly: Boolean = false): () -> String {
        val r = result ?: return { "" }
        val rows = if (selectedOnly) table.selectedRows.toList() else (0 until table.rowCount).toList()
        val columns = if (selectedOnly) table.selectedColumns.toList() else (0 until table.columnCount).toList()
        val modelRows = rows.map(table::convertRowIndexToModel);
        val modelColumns = columns.map(table::convertColumnIndexToModel)
        return { r.export(delimiter, modelRows, modelColumns, includeHeaders = !selectedOnly) }
    }

    fun exportText(delimiter: Char, selectedOnly: Boolean = false): String = exportSnapshot(delimiter, selectedOnly)()
    fun setRunning(value: Boolean) {
        running = value; updateButtons()
    }

    fun clear(message: String) {
        result = null; activeSorter = null; label.text = message; label.toolTipText = message
        table.rowSorter = null; table.model = javax.swing.table.DefaultTableModel(); updateButtons()
    }

    fun showResult(r: DbtQueryResult) {
        result = r; limit.value = DbtCommandRequest.limit(r.limit)
        label.text = "Target: ${r.target} · Returned ${r.rows.size} / limit ${r.limit} · ${r.elapsedMillis} ms" +
                if (r.rows.size >= r.limit) " · May be truncated" else if (r.rows.isEmpty()) " · No data returned" else ""
        label.toolTipText = label.text
        val columnTypes = r.columns.indices.map { column ->
            r.rows.indices.mapNotNull { r.value(it, column)?.javaClass }.distinct().singleOrNull() ?: Any::class.java
        }
        val model = object : AbstractTableModel() {
            override fun getRowCount(): Int = r.rows.size
            override fun getColumnCount(): Int = r.columns.size
            override fun getColumnName(column: Int): String = r.columns[column]
            override fun getValueAt(row: Int, column: Int): Any? = r.value(row, column)
            override fun getColumnClass(column: Int): Class<*> = columnTypes[column]
        }
        table.rowSorter = null; table.model = model
        val sorter = TableRowSorter<AbstractTableModel>(model)
        r.columns.indices.forEach { c ->
            sorter.setComparator(
                c,
                Comparator<Any?> { a, b ->
                    if (a is java.math.BigDecimal && b is java.math.BigDecimal) a.compareTo(b) else java.lang.String.valueOf(
                        a
                    ).compareTo(java.lang.String.valueOf(b))
                })
            table.columnModel.getColumn(c).preferredWidth = 160
        }
        table.rowSorter = sorter; activeSorter = sorter; updateFilter(); updateButtons()
    }

    private fun updateFilter() {
        val sorter = activeSorter ?: return
        val text = filter.text.lowercase(java.util.Locale.ROOT)
        sorter.rowFilter = object : RowFilter<AbstractTableModel, Int>() {
            override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean =
                (0 until entry.valueCount).any {
                    (entry.getValue(it)?.toString() ?: "NULL").lowercase(java.util.Locale.ROOT).contains(text)
                }
        }
        updateButtons()
    }
}

/** Extracts preview JSON from structured or mixed human-readable dbt output without executing SQL. */
internal object DbtPreview {
    val Ansi = Regex("\u001b\\[[0-9;]*[A-Za-z]")
    fun rows(output: String): JsonArray? {
        val plain = Ansi.replace(output, "")
        fun rows(element: JsonElement): JsonArray? {
            if (element.isJsonArray) return element.asJsonArray
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject
            return obj["preview"]?.let { e ->
                if (e.isJsonPrimitive && e.asJsonPrimitive.isString) dbtResult { JsonParser.parseString(e.asString) }.getOrNull()
                    ?.let(::rows) else rows(e)
            } ?: obj["data"]?.let(::rows) ?: obj["show"]?.let(::rows)
        }
        // Extract balanced JSON values without rewriting string contents.
        val values = mutableListOf<String>();
        val stack = mutableListOf<Pair<Char, Int>>()
        var quoted = false;
        var escaped = false
        plain.indices.forEach { i ->
            val c = plain[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else if (c == '"' && stack.isNotEmpty()) quoted = true
            else if (c == '{' || c == '[') stack.add(c to i)
            else if (c == '}' || c == ']') {
                val last = stack.lastOrNull()
                if (last != null && last.first == if (c == '}') '{' else '[') {
                    values.add(plain.substring(last.second, i + 1)); stack.removeAt(stack.lastIndex)
                } else stack.clear()
            }
        }
        return (listOf(plain) + values).mapNotNull { s ->
            dbtResult { JsonParser.parseString(s.trim()) }.getOrNull()?.let(::rows)
        }.lastOrNull()
    }

    fun format(output: String): String {
        val array = rows(output) ?: return "Preview JSON was unavailable. See dbt output for details."
        if (array.isEmpty) return "No data returned."
        val objects = array.filter { it.isJsonObject }.map { it.asJsonObject }
        val headers = objects.flatMap { it.keySet() }.distinct()
        if (headers.isEmpty()) return "No data returned."
        val values = objects.map { o ->
            headers.map { h ->
                (o[h]?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                    ?: "null").replace("\n", "\\n")
            }
        }
        val widths = headers.indices.map { i -> (listOf(headers[i].length) + values.map { it[i].length }).max() }
        fun row(xs: List<String>): String = xs.indices.joinToString(" | ") { i -> xs[i].padEnd(widths[i], ' ') }
        return row(headers) + "\n" + widths.joinToString("-+-") { "-".repeat(it) } + "\n" + values.joinToString(
            "\n",
            transform = ::row
        )
    }
}
