package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtAuthoringTest extends DbtCodeInsightTestCase {
  def testLineageDistinguishesPassthroughAndCalculation(): Unit = {
    schema()
    val file = configure("models/report.sql", "select o.id as order_id, o.amount * 2 as gross from {{ ref('orders') }} o<caret>")
    val result = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertEquals(Vector("order_id", "gross"), result.columns.map(_.name))
    assertTrue(result.columns.head.direct)
    assertEquals(Vector("id"), result.columns.head.inputs.map(_.name))
    assertFalse(result.columns(1).direct)
    assertEquals(Vector("amount"), result.columns(1).inputs.map(_.name))
    assertTrue(result.columns.forall(_.complete))
  }
  def testUnionRecordsEveryBranchAndUnknownSchemaStaysPartial(): Unit = {
    schema()
    val file = configure("models/report.sql", "select id from {{ ref('orders') }} union all select event_id from {{ source('raw', 'events') }}<caret>")
    val columns = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get.columns
    assertEquals(Set("id", "event_id"), columns.head.inputs.map(_.name).toSet)
    assertFalse(columns.head.direct)
    edit(file, "select * from unknown_relation")
    val result = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertTrue(result.issues.nonEmpty); assertTrue(result.wildcards.isEmpty)
  }
  def testJoinAndFilterDependenciesAreSeparateFromValueDerivation(): Unit = {
    schema()
    val file = configure("models/report.sql", "select a.id from {{ ref('orders') }} a join {{ ref('orders') }} b on a.id = b.id where b.amount > 0<caret>")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertEquals(Vector("id"), analysis.columns.head.inputs.map(_.name))
    assertEquals(Set("join", "filter"), analysis.columns.head.contextInputs.map(_._2).toSet)
  }

}
