package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert._
import java.awt.datatransfer.{DataFlavor, StringSelection}
import scala.jdk.CollectionConverters._

final class DbtClipboardActionTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  override protected def createTempDirTestFixture(): TempDirTestFixture = IdeaTestFixtureFactory.getFixtureFactory.createTempDirTestFixture()
  private def setupRelations(): Unit = {
    val config = project()
    com.intellij.testFramework.PsiTestUtil.addContentRoot(getModule, config.getVirtualFile.getParent)
    add("models/orders.sql", "select 1")
    add("seeds/countries.csv", "id\n1\n")
    add("models/sources.yml", "sources: [{name: raw, tables: [{name: events}]}]\n")
    add("target/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "model.analytics.orders":{"unique_id":"model.analytics.orders","resource_type":"model","name":"orders","package_name":"analytics","original_file_path":"models/orders.sql","database":"db","schema":"mart","alias":"physical_orders","relation_name":"\"db\".\"mart\".\"physical_orders\""},
      "seed.analytics.countries":{"unique_id":"seed.analytics.countries","resource_type":"seed","name":"countries","package_name":"analytics","relation_name":"db.lookups.country_codes"}
      },"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","resource_type":"source","source_name":"raw","name":"events","package_name":"analytics","relation_name":"db.landing.actual_events"}}}""")
    FileDocumentManager.getInstance().saveAllDocuments()
    CopyPasteManager.getInstance().setContents(new StringSelection(""))
    val root = DbtWorkspace.get(getProject).roots.head
    DbtArtifactContext.record(root.directory.getPath + "/target", root.directory.getPath, "")
  }
  private def await(condition: => Boolean): Unit = PlatformTestUtil.waitWithEventsDispatching("Clipboard action completed", () => condition, 10)
  private def clipboard: String = Option(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)).getOrElse("")

  def testCopyWholeDocumentPreservesSourceAndCommentsJinjaForRoundTrip(): Unit = {
    setupRelations()
    val sql = "{{ config(materialized='table') }}\nselect * from {{- ref('orders') -}} join {{ source('raw', 'events') }} using(id)"
    val file = configure("models/report.sql", sql + "<caret>")
    myFixture.testAction(new DbtCopyForTargetAction)
    await(clipboard.contains("physical_orders"))
    assertEquals(sql, file.getText)
    assertTrue(clipboard.contains("\"db\".\"mart\".\"physical_orders\""))
    assertTrue(clipboard.contains("db.landing.actual_events"))
    assertTrue(clipboard.contains("/* dbt-workspace-jinja:"))
    val w = DbtWorkspace.get(getProject); val root = w.root(file).get
    val restored = DbtClipboard.paste(w, root, clipboard).toOption.get
    assertTrue(restored.startsWith("{{ config(materialized='table') }}"))
    assertTrue(restored.contains("{{ ref('orders') }}")); assertTrue(restored.contains("{{ source('raw', 'events') }}"))
  }

  def testCopySelectionAndThisUseTheResolvedPhysicalRelation(): Unit = {
    setupRelations()
    val file = configure("models/orders.sql", "select * from {{ this }} where id > 0<caret>")
    val start = file.getText.indexOf("{{")
    myFixture.getEditor.getSelectionModel.setSelection(start, start + "{{ this }}".length)
    myFixture.testAction(new DbtCopyForTargetAction)
    await(clipboard == "\"db\".\"mart\".\"physical_orders\"")
    assertTrue(file.getText.contains("{{ this }}"))
  }

  def testPasteReplacesSelectionMovesCaretAndHasOneUndoStep(): Unit = {
    setupRelations()
    configure("models/report.sql", "select <caret>replace_me")
    myFixture.getEditor.getSelectionModel.setSelection(7, 17)
    CopyPasteManager.getInstance().setContents(new StringSelection("* from db.mart.physical_orders"))
    val w = DbtWorkspace.get(getProject)
    assertEquals("* from {{ ref('orders') }}", DbtClipboard.paste(w, w.roots.find(_.name == "analytics").get, "* from db.mart.physical_orders").toOption.get)
    val event = myFixture.testAction(new DbtPasteAsRefsAction)
    assertTrue(event.isEnabled)
    await(myFixture.getEditor.getDocument.getText.contains("ref('orders')"))
    val expected = "select * from {{ ref('orders') }}"
    assertEquals(expected, myFixture.getEditor.getDocument.getText)
    assertEquals(expected.length, myFixture.getEditor.getCaretModel.getOffset)
    myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO)
    assertEquals("select replace_me", myFixture.getEditor.getDocument.getText)
  }

  def testPasteInsertsAtCaretAndDoesNotRewriteQualifiersCommentsStringsOrUnknownRelations(): Unit = {
    setupRelations()
    val file = configure("models/report.sql", "<caret>")
    val sql = "select o.id, 'db.mart.physical_orders' from \"db\".\"mart\".\"physical_orders\" o -- db.mart.physical_orders\njoin mystery.other_table u on o.id=u.id"
    val w = DbtWorkspace.get(getProject)
    val converted = DbtClipboard.paste(w, w.root(file).get, sql).toOption.get
    assertTrue(converted, converted.contains("{{ ref('orders') }}"))
    CopyPasteManager.getInstance().setContents(new StringSelection(sql))
    myFixture.testAction(new DbtPasteAsRefsAction)
    await(myFixture.getEditor.getDocument.getText.contains("{{ ref('orders') }}"))
    val text = myFixture.getEditor.getDocument.getText
    assertTrue(text.contains("o.id")); assertTrue(text.contains("'db.mart.physical_orders'"))
    assertTrue(text.contains("-- db.mart.physical_orders")); assertTrue(text.contains("mystery.other_table"))
  }

  def testUnknownRefIsCommentedAndOnlyOurOwnCommentsAreRestored(): Unit = {
    setupRelations()
    val file = configure("models/report.sql", "select {{ ref('mis<caret>sing') }} /* ordinary {{ config(x=1) }} */")
    val copied = DbtClipboard.copy(file, new TextRange(0, file.getTextLength)).toOption.get
    assertFalse(copied.contains("invented"))
    val w = DbtWorkspace.get(getProject)
    val restored = DbtClipboard.paste(w, w.root(file).get, copied).toOption.get
    assertTrue(restored.contains("{{ ref('missing') }}"))
    assertTrue(restored.contains("/* ordinary {{ config(x=1) }} */"))
  }

  def testChangingTargetOrManifestBytesInvalidatesPhysicalConversion(): Unit = {
    setupRelations()
    val w = DbtWorkspace.get(getProject); val root = w.roots.head
    assertTrue(DbtClipboard.ready(root).isRight)
    root.runOptions.target = "prod"
    assertTrue(DbtClipboard.ready(root).isLeft)
    root.runOptions.target = ""
    val manifest = root.psiFile("target/manifest.json").get
    edit(manifest, manifest.getText.replace("physical_orders", "new_orders"))
    FileDocumentManager.getInstance().saveAllDocuments()
    assertTrue(DbtClipboard.ready(root).isLeft)
  }

  def testAmbiguousTwoPartRelationStaysUnchanged(): Unit = {
    setupRelations()
    val w = DbtWorkspace.get(getProject); val root = w.roots.head
    val manifest = root.psiFile("target/manifest.json").get
    edit(manifest, manifest.getText.replace("db.lookups.country_codes", "another.mart.physical_orders"))
    FileDocumentManager.getInstance().saveAllDocuments()
    DbtArtifactContext.record(root.directory.getPath + "/target", root.directory.getPath, "")
    val current = DbtWorkspace.get(getProject)
    assertEquals("select * from mart.physical_orders", DbtClipboard.paste(current, current.roots.head, "select * from mart.physical_orders").toOption.get)
  }
  def testMalformedAndRelationFreeManifestsWarnBeforeConversion(): Unit = {
    setupRelations()
    val root = DbtWorkspace.get(getProject).roots.head
    val manifest = root.psiFile("target/manifest.json").get
    edit(manifest, "{malformed")
    FileDocumentManager.getInstance().saveAllDocuments()
    DbtArtifactContext.record(root.directory.getPath + "/target", root.directory.getPath, "")
    assertTrue(DbtClipboard.ready(root).isLeft)
    edit(manifest, """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{}}""")
    FileDocumentManager.getInstance().saveAllDocuments()
    DbtArtifactContext.record(root.directory.getPath + "/target", root.directory.getPath, "")
    assertTrue(DbtClipboard.ready(root).isLeft)
  }

}
