package com.github.vbmacher.intellij.dbt.ide

import com.google.gson.{JsonArray, JsonObject}
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import java.awt.{Component, Container}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import javax.swing.{JTable, JTextField}
import org.junit.Assert._

final class DbtPreviewAcceptanceTest extends DbtCodeInsightTestCase {
  private def components(component: Component): Vector[Component] = Vector(component) ++ (component match {
    case container: Container => container.getComponents.toVector.flatMap(components)
    case _ => Vector.empty
  })
  private def component[A](grid: DbtResultGrid, kind: Class[A]): A = components(grid).find(kind.isInstance).map(kind.cast).get

  def testExportSnapshotRetainsFilteredSortedReorderedViewAfterTheGridIsCleared(): Unit = {
    val grid = new DbtResultGrid
    val rows = """{"show":[{"id":10,"text":"a,\"b\"\n日本語","kind":"keep"},{"id":2,"text":"","kind":"keep"},{"id":1,"text":null,"kind":"hide"}]}"""
    grid.showResult(DbtQueryResult.parse(rows, "prod", 20, 17, "select * from orders").toOption.get)
    val table = component(grid, classOf[JTable])
    table.getRowSorter.toggleSortOrder(0)
    component(grid, classOf[JTextField]).setText("keep")
    table.moveColumn(1, 0)
    val snapshot = grid.exportSnapshot(',')
    grid.clear("Query canceled")
    val exported = CompletableFuture.supplyAsync(() => snapshot()).get(5, TimeUnit.SECONDS)
    assertEquals("\"text\",\"id\",\"kind\"\r\n\"\",2,\"keep\"\r\n\"a,\"\"b\"\"\n日本語\",10,\"keep\"\r\n", exported)
    assertEquals(0, table.getRowCount)
  }

  def testLargeWideRowsStayBoundedAndExportWithoutTouchingTheClearedGrid(): Unit = {
    val rows = new JsonArray
    val wide = "日本語\t\"quoted\"\n" + "x" * 2048
    (0 until 1002).foreach { index =>
      val row = new JsonObject
      row.addProperty("id", index); row.addProperty("wide", wide)
      if (index >= 1000) row.addProperty("outside_limit", true)
      rows.add(row)
    }
    val raw = new JsonObject; raw.add("show", rows)
    val result = DbtQueryResult.parse(raw.toString, "analytics-dev", 1000, 81, "select wide").toOption.get
    assertEquals(1000, result.rows.size)
    assertEquals(Vector("id", "wide"), result.columns)
    assertEquals("analytics-dev", result.target); assertEquals(81L, result.elapsedMillis)
    val grid = new DbtResultGrid; grid.showResult(result)
    val snapshot = grid.exportSnapshot('\t')
    grid.clear("Closed preview")
    val text = CompletableFuture.supplyAsync(() => snapshot()).get(10, TimeUnit.SECONDS)
    assertTrue(text.contains("999\t\"日本語\t\"\"quoted\"\"\n"))
    assertFalse(text.contains("outside_limit")); assertFalse(text.contains("1000\t"))
    assertEquals("Original raw process output remains available", raw.toString, result.raw)
  }

  def testMalformedSqlFluffPositionsNeverBecomeRoundedSourceEdits(): Unit = {
    val template = """[{"filepath":"stdin","violations":[{"code":"CP01","start_file_pos":POSITION,"end_file_pos":6,"fixes":[{"type":"replace","edit":"SELECT","start_file_pos":0,"end_file_pos":6}]}]}]"""
    Vector("0.5", "2147483648", "\"0\"", "null").foreach { position =>
      assertTrue(position, DbtSqlFluff.parse("select 1", template.replace("POSITION", position)).isLeft)
    }
    val badFix = template.replace("POSITION", "0").replace("\"end_file_pos\":6}]", "\"end_file_pos\":5.5}]")
    val diagnostic = DbtSqlFluff.parse("select 1", badFix).toOption.get.head
    assertTrue("A malformed fix retains the diagnostic but offers no source edit", diagnostic.edits.isEmpty)
  }


}
