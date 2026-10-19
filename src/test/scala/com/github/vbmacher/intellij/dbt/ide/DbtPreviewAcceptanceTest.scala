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
