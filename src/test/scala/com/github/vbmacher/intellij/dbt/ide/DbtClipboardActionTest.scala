package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._

import java.awt.datatransfer.{DataFlavor, StringSelection, Transferable, UnsupportedFlavorException}
import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, InputStream}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters._

/** Copying and pasting dbt SQL as physical relations, and copying the lineage graph as a PNG image. */
final class DbtClipboardActionTest extends DbtTestCase {
  override protected def createTempDirTestFixture(): TempDirTestFixture = IdeaTestFixtureFactory.getFixtureFactory.createTempDirTestFixture()

  def testConversionActionsDoNotClaimYamlOrMarkdownEditorsOrStandardShortcuts(): Unit = {
    project()
    for (path <- Vector("models/schema.yml", "docs/readme.md")) {
      configure(path, "text<caret>")
      for (id <- Vector("Dbt.CopyForTarget", "Dbt.PasteAsRefs")) {
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance.getAction(id)
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(action, com.intellij.ide.DataManager.getInstance.getDataContext(myFixture.getEditor.getContentComponent),
          null, com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP, com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
        action.update(event)
        assertFalse(path, event.getPresentation.isEnabledAndVisible)
        assertTrue(action.getShortcutSet.getShortcuts.isEmpty)
      }
    }
  }

  private def setupRelations(): Unit = {
    val config = project()
    PsiTestUtil.addContentRoot(getModule, config.getVirtualFile.getParent)
    add("models/orders.sql", "select 1")
    add("seeds/countries.csv", "id\n1\n")
    add("models/sources.yml", "sources: [{name: raw, tables: [{name: events}]}]\n")
    add("target/manifest.json",
      """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "model.analytics.orders":{"unique_id":"model.analytics.orders","resource_type":"model","name":"orders","package_name":"analytics","original_file_path":"models/orders.sql","database":"db","schema":"mart","alias":"physical_orders","relation_name":"\"db\".\"mart\".\"physical_orders\""},
      "seed.analytics.countries":{"unique_id":"seed.analytics.countries","resource_type":"seed","name":"countries","package_name":"analytics","relation_name":"db.lookups.country_codes"}
      },"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","resource_type":"source","source_name":"raw","name":"events","package_name":"analytics","relation_name":"db.landing.actual_events"}}}""")
    save()
    CopyPasteManager.getInstance().setContents(new StringSelection(""))
    val root = workspace.roots.head
    DbtArtifactContext.record(root.directory.getPath + "/target", DbtArtifactContext.identity(root))
  }

  private def clipboard: String = Option(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)).getOrElse("")

  private def save(): Unit = {
    FileDocumentManager.getInstance().saveAllDocuments()
    // Save actions can change a document (for example, adding an EOF newline) after the edit was committed.
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }

  def testCopyWholeDocumentPreservesSourceAndCommentsJinjaForRoundTrip(): Unit = {
    setupRelations()
    val sql = "{{ config(materialized='table') }}\nselect * from {{- ref('orders') -}} join {{ source('raw', 'events') }} using(id)"
    val file = configure("models/report.sql", sql + "<caret>")
    myFixture.testAction(new DbtCopyForTargetAction)
    await("Clipboard action completed")(clipboard.contains("physical_orders"))
    assertEquals(sql, file.getText)
    assertTrue(clipboard.contains("\"db\".\"mart\".\"physical_orders\""))
    assertTrue(clipboard.contains("db.landing.actual_events"))
    assertTrue(clipboard.contains("/* dbt-workspace-jinja:"))
    val w = workspace;
    val root = w.root(file).get
    val restored = DbtClipboard.paste(w, root, clipboard).toOption.get
    assertTrue(restored.startsWith("{{ config(materialized='table') }}"))
    assertTrue(restored.contains("{{ ref('orders') }}"));
    assertTrue(restored.contains("{{ source('raw', 'events') }}"))
  }

  def testCopySelectionAndThisUseTheResolvedPhysicalRelation(): Unit = {
    setupRelations()
    val file = configure("models/orders.sql", "select * from {{ this }} where id > 0<caret>")
    val start = file.getText.indexOf("{{")
    myFixture.getEditor.getSelectionModel.setSelection(start, start + "{{ this }}".length)
    myFixture.testAction(new DbtCopyForTargetAction)
    await("Clipboard action completed")(clipboard == "\"db\".\"mart\".\"physical_orders\"")
    assertTrue(file.getText.contains("{{ this }}"))
  }

  def testPasteReplacesSelectionMovesCaretAndHasOneUndoStep(): Unit = {
    setupRelations()
    configure("models/report.sql", "select <caret>replace_me")
    myFixture.getEditor.getSelectionModel.setSelection(7, 17)
    CopyPasteManager.getInstance().setContents(new StringSelection("* from db.mart.physical_orders"))
    val w = workspace
    assertEquals("* from {{ ref('orders') }}", DbtClipboard.paste(w, w.roots.find(_.name == "analytics").get, "* from db.mart.physical_orders").toOption.get)
    val event = myFixture.testAction(new DbtPasteAsRefsAction)
    assertTrue(event.isEnabled)
    await("Clipboard action completed")(myFixture.getEditor.getDocument.getText.contains("ref('orders')"))
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
    val w = workspace
    val converted = DbtClipboard.paste(w, w.root(file).get, sql).toOption.get
    assertTrue(converted, converted.contains("{{ ref('orders') }}"))
    CopyPasteManager.getInstance().setContents(new StringSelection(sql))
    myFixture.testAction(new DbtPasteAsRefsAction)
    await("Clipboard action completed")(myFixture.getEditor.getDocument.getText.contains("{{ ref('orders') }}"))
    val text = myFixture.getEditor.getDocument.getText
    assertTrue(text.contains("o.id"));
    assertTrue(text.contains("'db.mart.physical_orders'"))
    assertTrue(text.contains("-- db.mart.physical_orders"));
    assertTrue(text.contains("mystery.other_table"))
  }

  def testUnknownRefIsCommentedAndOnlyOurOwnCommentsAreRestored(): Unit = {
    setupRelations()
    val file = configure("models/report.sql", "select {{ ref('mis<caret>sing') }} /* ordinary {{ config(x=1) }} */")
    val copied = DbtClipboard.copy(file, new TextRange(0, file.getTextLength)).toOption.get
    assertFalse(copied.contains("invented"))
    val w = workspace
    val restored = DbtClipboard.paste(w, w.root(file).get, copied).toOption.get
    assertTrue(restored.contains("{{ ref('missing') }}"))
    assertTrue(restored.contains("/* ordinary {{ config(x=1) }} */"))
  }

  def testChangingTargetOrManifestBytesInvalidatesPhysicalConversion(): Unit = {
    setupRelations()
    val w = workspace;
    val root = w.roots.head
    assertTrue(DbtClipboard.ready(root).isRight)
    root.runOptions.target = "prod"
    assertTrue(DbtClipboard.ready(root).isLeft)
    root.runOptions.target = ""
    val manifest = root.psiFile("target/manifest.json").get
    edit(manifest, manifest.getText.replace("physical_orders", "new_orders"))
    save()
    assertTrue(DbtClipboard.ready(root).isLeft)
  }

  def testProfilesDirectoryAndUnsavedProfileChangesInvalidatePhysicalConversion(): Unit = {
    setupRelations()
    val w = workspace
    val root = w.roots.head
    val first = add("profiles_one/profiles.yml", "analytics:\n  target: dev\n  outputs:\n    dev: {type: postgres, dbname: warehouse_one, schema: public}\n")
    add("profiles_two/profiles.yml", "analytics:\n  target: dev\n  outputs:\n    dev: {type: postgres, dbname: warehouse_two, schema: public}\n")
    root.runOptions.target = "dev"
    root.runOptions.profilesDirectory = "profiles_one"
    save()
    val artifact = root.artifactDirectory.toString
    DbtArtifactContext.record(artifact, DbtArtifactContext.identity(root))
    assertEquals(Right(()), DbtClipboard.ready(root))
    root.runOptions.profilesDirectory = "profiles_two"
    assertTrue("Another profiles directory cannot reuse physical relations", DbtClipboard.ready(root).isLeft)
    root.runOptions.profilesDirectory = "profiles_one"
    assertEquals(Right(()), DbtClipboard.ready(root))
    edit(first, first.getText.replace("schema: public", "schema: another_schema"))
    assertTrue("Unsaved profile changes also invalidate conversion", DbtClipboard.ready(root).isLeft)
  }

  def testCommandRecordsTheConfigurationCapturedBeforeDbtWasLaunched(): Unit = {
    setupRelations()
    val w = workspace
    val root = w.roots.head
    val command = DbtCommand.create(w, DbtRunSelection.available(w).find(_.path == root.directory.getPath).get, "Generate Docs", fullRefresh = false).toOption.get
    root.runOptions.executableArguments = "--another-warehouse"
    DbtArtifactContext.record(command.artifactDirectory, command.artifactContext)
    assertTrue("An in-flight command cannot certify later configuration changes", DbtClipboard.ready(root).isLeft)
  }

  def testProjectAndVarsChangesInvalidateConversionAndLegacyMarkersAreRejected(): Unit = {
    setupRelations()
    val root = workspace.roots.head
    val artifact = root.artifactDirectory
    val config = root.config.getContainingFile
    edit(config, config.getText + "vars: {schema_suffix: another}\n")
    assertTrue(DbtClipboard.ready(root).isLeft)
    DbtArtifactContext.record(artifact.toString, DbtArtifactContext.identity(root))
    assertEquals(Right(()), DbtClipboard.ready(root))
    add("vars.yml", "vars: {schema_suffix: changed}\n")
    assertTrue(DbtClipboard.ready(root).isLeft)
    java.nio.file.Files.write(artifact.resolve(".dbt-workspace-context"),
      Vector(root.executionDirectory.toString, "", DbtArtifactContext.fingerprint(artifact.toString).get).asJava)
    assertFalse("The old target-only marker must not be accepted", DbtArtifactContext.matches(root))
  }

  def testAmbiguousTwoPartRelationStaysUnchanged(): Unit = {
    val settings = com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance()
    val ensureNewline = settings.isEnsureNewLineAtEOF
    try {
      settings.setEnsureNewLineAtEOF(true)
      setupRelations()
      val w = workspace;
      val root = w.roots.head
      val manifest = root.psiFile("target/manifest.json").get
      edit(manifest, manifest.getText.replace("db.lookups.country_codes", "another.mart.physical_orders"))
      save()
      DbtArtifactContext.record(root.directory.getPath + "/target", DbtArtifactContext.identity(root))
      val current = workspace
      assertEquals(Right("select * from mart.physical_orders"), DbtClipboard.paste(current, current.roots.head, "select * from mart.physical_orders"))
    } finally settings.setEnsureNewLineAtEOF(ensureNewline)
  }

  def testMalformedAndRelationFreeManifestsWarnBeforeConversion(): Unit = {
    setupRelations()
    val root = workspace.roots.head
    val manifest = root.psiFile("target/manifest.json").get
    edit(manifest, "{malformed")
    save()
    DbtArtifactContext.record(root.directory.getPath + "/target", DbtArtifactContext.identity(root))
    assertTrue(DbtClipboard.ready(root).isLeft)
    edit(manifest, """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{}}""")
    save()
    DbtArtifactContext.record(root.directory.getPath + "/target", DbtArtifactContext.identity(root))
    assertTrue(DbtClipboard.ready(root).isLeft)
  }

  def testCompoundJinjaExpressionsRemainRecoverableAcrossCopyAndPaste(): Unit = {
    setupRelations()
    for (expression <- Vector("ref('orders') ~ ('_suffix')", "ref('orders') if execute else (none)",
      "ref('orders') | string", "ref('orders') ~ ')'", "source('raw', 'events') ~ ('_suffix')")) {
      val sql = "select * from {{ " + expression + " }}"
      val file = configure("models/report.sql", sql + "<caret>")
      val copied = DbtClipboard.copy(file, new TextRange(0, file.getTextLength)).toOption.get
      assertTrue(copied, copied.contains("dbt-workspace-jinja:"))
      val w = workspace
      assertEquals(sql, DbtClipboard.paste(w, w.root(file).get, copied).toOption.get)
    }
  }

  def testClipboardActionsOnlyEnableInNonExcludedDbtFiles(): Unit = {
    def enabled(action: DbtClipboardAction, file: com.intellij.psi.PsiFile): Boolean = {
      val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject)
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.getEditor)
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file).build()
      val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(context, null, "test",
        com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
      action.update(event)
      event.getPresentation.isEnabled
    }

    val outside = configure("notes.sql", "select 1<caret>")
    val actions = Vector(new DbtCopyForTargetAction, new DbtPasteAsRefsAction)
    actions.foreach(a => assertFalse(enabled(a, outside)))
    val config = add("analytics/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    val model = add("analytics/models/orders.sql", "select 1")
    val excluded = add("analytics/target/compiled/orders.sql", "select 1")
    actions.foreach { a =>
      assertFalse(enabled(a, outside))
      assertTrue("Available without requiring loaded artifacts", enabled(a, model))
      assertFalse("SQL conversion is unavailable in project YAML", enabled(a, config))
      assertFalse(enabled(a, excluded))
    }
  }

  private def png(): Array[Byte] = {
    val image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 0x40708090)
    image.setRGB(1, 0, 0xff123456)
    val output = new ByteArrayOutputStream
    assertTrue(ImageIO.write(image, "png", output))
    output.toByteArray
  }

  def testNormalCopyOffersEncodedPngAndStandardImageWithExactPixels(): Unit = {
    val bytes = png()
    var calls = 0
    DbtGraphExport.copyPng(bytes, content => {
      calls += 1
      assertEquals(Vector(DbtGraphExport.PngFlavor, DataFlavor.imageFlavor), content.getTransferDataFlavors.toVector)
      assertTrue(content.isDataFlavorSupported(DataFlavor.imageFlavor))
      val image = content.getTransferData(DataFlavor.imageFlavor).asInstanceOf[BufferedImage]
      assertEquals(0x40708090, image.getRGB(0, 0))
      assertEquals(0xff123456, image.getRGB(1, 0))
      val stream = content.getTransferData(DbtGraphExport.PngFlavor).asInstanceOf[InputStream]
      try assertArrayEquals(bytes, stream.readAllBytes()) finally stream.close()
      assertThrows(classOf[UnsupportedFlavorException], () => content.getTransferData(DataFlavor.stringFlavor))
    })
    assertEquals(1, calls)
  }

  def testFailedNativeImageMappingRetriesOnlyEncodedPngWithIndependentStreams(): Unit = {
    val bytes = png()
    val expected = bytes.clone()
    var attempts = Vector.empty[Transferable]
    DbtGraphExport.copyPng(bytes, content => {
      attempts :+= content
      if (content.isDataFlavorSupported(DataFlavor.imageFlavor))
        throw new NullPointerException("Native clipboard ImageWriter unavailable")
    })
    assertEquals(2, attempts.size)
    val encoded = attempts.last
    assertEquals(Vector(DbtGraphExport.PngFlavor), encoded.getTransferDataFlavors.toVector)
    assertEquals("image/png", DbtGraphExport.PngFlavor.getPrimaryType + "/" + DbtGraphExport.PngFlavor.getSubType)
    assertTrue(DbtGraphExport.PngFlavor.isRepresentationClassInputStream)
    assertFalse(encoded.isDataFlavorSupported(DataFlavor.imageFlavor))
    assertThrows(classOf[UnsupportedFlavorException], () => encoded.getTransferData(DataFlavor.imageFlavor))
    java.util.Arrays.fill(bytes, 0.toByte)
    val first = encoded.getTransferData(DbtGraphExport.PngFlavor).asInstanceOf[InputStream]
    try assertArrayEquals(expected, first.readAllBytes()) finally first.close()
    val next = encoded.getTransferData(DbtGraphExport.PngFlavor).asInstanceOf[InputStream]
    try {
      val image = ImageIO.read(next)
      assertEquals(2, image.getWidth)
      assertEquals(0x40708090, image.getRGB(0, 0))
    } finally next.close()
  }

  def testFailureOfBothNativeAndEncodedPublicationIsNotSuppressed(): Unit = {
    val failure = new IllegalStateException("Clipboard unavailable")
    var calls = 0
    val error = assertThrows(classOf[IllegalStateException], () => DbtGraphExport.copyPng(png(), _ => {
      calls += 1
      throw failure
    }))
    assertSame(failure, error)
    assertEquals(2, calls)
  }
}
