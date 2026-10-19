package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtWildcardExplanationTest extends DbtCodeInsightTestCase {
  private def explain(sql: String, expected: String, index: Int = 0): Unit = {
    configure(s"models/report_$index.sql", sql)
    val before = myFixture.getFile.getText
    val action = myFixture.findSingleIntention("Expand dbt SQL columns")
    val preview = action.generatePreview(getProject, myFixture.getEditor, myFixture.getFile)
    assertTrue("Unsupported expansion must explain instead of previewing an edit", preview.isInstanceOf[IntentionPreviewInfo.Html])
    assertTrue(preview.asInstanceOf[IntentionPreviewInfo.Html].content().toString.contains(expected))
    myFixture.launchAction(action)
    assertEquals("Explaining unsupported expansion must preserve source", before, myFixture.getFile.getText)
  }
  def testNativeIntentionExplainsMissingSchemaWithoutEditingSql(): Unit = {
    project()
    explain("select <caret>* from unknown_relation", "Add or update")
  }
  def testNativeIntentionExplainsWildcardModifiersAndPreservesThem(): Unit = {
    project(); add("models/orders.sql", "select 1 as id, 2 as amount")
    Vector("except (id)", "exclude id", "replace (3 as id)").zipWithIndex.foreach { case (modifier, index) =>
      explain(s"select <caret>* $modifier from {{ ref('orders') }}", "remove the modifier", index)
    }
  }
  def testNativeIntentionExplainsUnresolvedQualifierInsteadOfGuessingAnotherRelation(): Unit = {
    project(); add("models/orders.sql", "select 1 as id")
    explain("select other_alias.<caret>* from {{ ref('orders') }} o", "declared relation alias")
  }
  def testCountMultiplicationCommentsAndStringStarsHaveNoExpansionAction(): Unit = {
    project(); add("models/orders.sql", "select 1 as id")
    Vector("select count(<caret>*) from {{ ref('orders') }}", "select id <caret>* 2 from {{ ref('orders') }}",
      "select '<caret>*' as value", "select 1 /* <caret>* */", "select 1 -- <caret>*\n")
      .zipWithIndex.foreach { case (sql, i) =>
        configure(s"models/no_wildcard_$i.sql", sql)
        assertFalse(sql, myFixture.getAvailableIntentions.asScala.exists(_.getText == "Expand dbt SQL columns"))
      }
  }
  def testDuplicateRelationAliasesAreExplainedWithoutChoosingOne(): Unit = {
    project(); add("models/orders.sql", "select 1 as id")
    explain("select o.<caret>* from {{ ref('orders') }} o join {{ ref('orders') }} o on 1=1", "No unique relation")
  }
  def testKnownWildcardStillHasEditablePreviewAfterUnknownSchemaBecomesKnown(): Unit = {
    project()
    val upstream = add("models/orders.sql", "select * from unknown_relation")
    configure("models/report.sql", "select o.<caret>* from {{ ref('orders') }} o")
    val action = myFixture.findSingleIntention("Expand dbt SQL columns")
    assertTrue(action.generatePreview(getProject, myFixture.getEditor, myFixture.getFile).isInstanceOf[IntentionPreviewInfo.Html])
    edit(upstream, "select 1 as id, 2 as amount")
    val ready = myFixture.findSingleIntention("Expand dbt SQL columns")
    assertTrue(ready.generatePreview(getProject, myFixture.getEditor, myFixture.getFile).isInstanceOf[IntentionPreviewInfo.CustomDiff])
    myFixture.launchAction(ready)
    assertEquals("select o.id, o.amount from {{ ref('orders') }} o", myFixture.getFile.getText)
    assertTrue(DbtSqlAnalysis.get(DbtWorkspace.get(getProject), myFixture.getFile).nonEmpty)
  }
}
