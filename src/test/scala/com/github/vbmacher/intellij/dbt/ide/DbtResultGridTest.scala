package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.{Component, Container}
import java.awt.datatransfer.DataFlavor
import javax.swing.{JButton, JComboBox, JLabel, JSpinner, JTable, JTextField}
import org.junit.Assert._
import scala.collection.mutable.ArrayBuffer

final class DbtResultGridTest extends DbtCodeInsightTestCase {
  private def components(component: Component): Vector[Component] = Vector(component) ++ (component match {
    case c: Container => c.getComponents.toVector.flatMap(components)
    case _ => Vector.empty
  })
  private def component[A](grid: DbtResultGrid, kind: Class[A]): A = components(grid).find(kind.isInstance).map(kind.cast).get
  private def button(grid: DbtResultGrid, text: String): JButton = components(grid).collectFirst { case b: JButton if b.getText == text => b }.get
  private def result = DbtQueryResult.parse("""{"show":[{"id":10,"text":"a,b\nž\t日本語","optional":null,"flag":true},{"id":2,"text":"","optional":"NULL","flag":false},{"id":3,"text":"wide value","extra":1}]}""",
    "dev", 20, 12, "select * from orders").toOption.get

  def testTypedNullRendererPreservesEmptyLiteralNullUnicodeAndWideValues(): Unit = {
    val grid = new DbtResultGrid(getProject); val r = result; grid.showResult(r)
    val table = component(grid, classOf[JTable])
    assertEquals(classOf[java.math.BigDecimal], table.getColumnClass(0))
    val nullCell = table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2).asInstanceOf[JLabel]
    assertEquals("NULL", nullCell.getText); assertEquals("SQL NULL (not an empty string)", nullCell.getToolTipText)
    val empty = table.prepareRenderer(table.getCellRenderer(1, 1), 1, 1).asInstanceOf[JLabel]
    assertEquals("", empty.getText)
    val text = table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1).asInstanceOf[JLabel]
    assertEquals("a,b\nž\t日本語", text.getText)
    assertTrue(table.getColumnModel.getColumn(1).getPreferredWidth >= 160)
    assertEquals(JTable.AUTO_RESIZE_OFF, table.getAutoResizeMode)
    assertEquals(java.lang.Boolean.TRUE, table.getValueAt(0, 3))
  }
  def testSortingFilteringSelectionCopyAndCsvTsvKeepExactValuesAndViewOrder(): Unit = {
    val grid = new DbtResultGrid(getProject); grid.showResult(result)
    val table = component(grid, classOf[JTable])
    table.getRowSorter.toggleSortOrder(0)
    assertEquals(new java.math.BigDecimal("2"), table.getValueAt(0, 0))
    table.setRowSelectionInterval(0, 0); table.setColumnSelectionInterval(1, 2)
    button(grid, "Copy selection").doClick()
    assertEquals("\"\"\t\"NULL\"\r\n", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
    val csv = grid.exportText(',')
    assertTrue(csv.startsWith("\"id\",\"text\",\"optional\",\"flag\",\"extra\"\r\n2,"))
    assertTrue(csv.contains("10,\"a,b\nž\t日本語\",NULL,true,NULL"))
    component(grid, classOf[JTextField]).setText("wide value")
    assertEquals(1, table.getRowCount)
    val tsv = grid.exportText('\t')
    assertTrue(tsv.contains("3\t\"wide value\"\tNULL\tNULL\t1"))
    assertFalse(tsv.contains("日本語"))
    component(grid, classOf[JComboBox[_]]).setSelectedItem("TSV")
    assertTrue(button(grid, "Export TSV").isEnabled)
  }
  def testChangingLimitSortingAndFilteringNeverRunsQueriesUntilExplicitButton(): Unit = {
    val requests = ArrayBuffer.empty[Int]
    val grid = new DbtResultGrid(getProject, value => requests += value); grid.showResult(result)
    val spinner = component(grid, classOf[JSpinner])
    spinner.setValue(Int.box(100))
    component(grid, classOf[JTextField]).setText("wide")
    component(grid, classOf[JTable]).getRowSorter.toggleSortOrder(0)
    assertTrue(requests.isEmpty)
    button(grid, "Run with limit").doClick(); assertEquals(Vector(100), requests.toVector)
    grid.setRunning(true); assertFalse(button(grid, "Run with limit").isEnabled)
    grid.clear("Query canceled"); assertEquals(0, component(grid, classOf[JTable]).getRowCount)
    assertFalse(button(grid, "Copy selection").isEnabled); assertFalse(button(grid, "Export CSV").isEnabled)
  }
  def testEmptyAndMalformedResultsAreDistinctAndRowsAreBoundedWithHeterogeneousColumns(): Unit = {
    val grid = new DbtResultGrid(getProject)
    grid.showResult(DbtQueryResult.parse("{\"show\":[]}", "prod", 10, 5, "select empty").toOption.get)
    assertTrue(components(grid).collect { case l: JLabel => l.getText }.mkString(" ").contains("No data returned"))
    assertEquals("", grid.exportText(','))
    assertTrue(DbtQueryResult.parse("{\"show\":[1,2]}", "dev", 10, 1, "").isLeft)
    assertTrue(DbtQueryResult.parse("broken", "dev", 10, 1, "").isLeft)
    val bounded = DbtQueryResult.parse("{\"show\":[{\"first\":1},{\"hidden_only\":2}]}", "dev", 1, 1, "").toOption.get
    assertEquals(Vector("first"), bounded.columns); assertEquals(1, bounded.rows.size)
    val zero = DbtQueryResult.parse("{\"show\":[{\"id\":1}]}", "dev", 0, 1, "").toOption.get
    assertEquals(1, zero.limit)
    grid.showResult(result); grid.clear("Malformed output; see raw dbt output.")
    assertTrue(components(grid).collect { case l: JLabel => l.getText }.mkString(" ").contains("Malformed output"))
  }
  def testNullAwareFilteringAndNumericSortingDoNotConfuseNullWithEmptyText(): Unit = {
    val grid = new DbtResultGrid(getProject)
    grid.showResult(DbtQueryResult.parse("{\"show\":[{\"v\":null},{\"v\":\"\"},{\"v\":\"NULL\"}]}", "dev", 20, 1, "").toOption.get)
    component(grid, classOf[JTextField]).setText("NULL")
    assertEquals(2, component(grid, classOf[JTable]).getRowCount)
    assertEquals("\"v\"\r\nNULL\r\n\"NULL\"\r\n", grid.exportText(','))
    component(grid, classOf[JTextField]).setText("")
    grid.showResult(DbtQueryResult.parse("{\"show\":[{\"v\":10},{\"v\":null},{\"v\":2}]}", "dev", 20, 1, "").toOption.get)
    val table = component(grid, classOf[JTable])
    table.getRowSorter.toggleSortOrder(0)
    assertNull(table.getValueAt(0, 0))
    assertEquals(new java.math.BigDecimal("2"), table.getValueAt(1, 0))
    assertEquals(new java.math.BigDecimal("10"), table.getValueAt(2, 0))
  }

}
