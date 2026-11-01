package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import org.junit.Assert.*

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

/** Copying and pasting dbt SQL as physical relations, and copying the lineage graph as a PNG image. */
class DbtClipboardActionTest : DbtTestCase() {
    protected override fun createTempDirTestFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testConversionActionsDoNotClaimYamlOrMarkdownEditorsOrStandardShortcuts() {
        project()
        for (path in listOf("models/schema.yml", "docs/readme.md")) {
            configure(path, "text<caret>")
            for (id in listOf("Dbt.CopyForTarget", "Dbt.PasteAsRefs")) {
                val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id)
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    action,
                    com.intellij.ide.DataManager.getInstance()
                        .getDataContext(myFixture.getEditor().getContentComponent()),
                    null,
                    com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP,
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE,
                    null
                )
                action.update(event)
                assertFalse(path, event.getPresentation().isEnabledAndVisible)
                assertTrue(action.getShortcutSet().getShortcuts().isEmpty())
            }
        }
    }

    private fun setupRelations() {
        val config = project()
        PsiTestUtil.addContentRoot(module, config.getVirtualFile().getParent())
        add("models/orders.sql", "select 1")
        add("seeds/countries.csv", "id\n1\n")
        add("models/sources.yml", "sources: [{name: raw, tables: [{name: events}]}]\n")
        add(
            "target/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "model.analytics.orders":{"unique_id":"model.analytics.orders","resource_type":"model","name":"orders","package_name":"analytics","original_file_path":"models/orders.sql","database":"db","schema":"mart","alias":"physical_orders","relation_name":"\"db\".\"mart\".\"physical_orders\""},
      "seed.analytics.countries":{"unique_id":"seed.analytics.countries","resource_type":"seed","name":"countries","package_name":"analytics","relation_name":"db.lookups.country_codes"}
      },"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","resource_type":"source","source_name":"raw","name":"events","package_name":"analytics","relation_name":"db.landing.actual_events"}}}"""
        )
        save()
        CopyPasteManager.getInstance().setContents(StringSelection(""))
        val root = workspace.roots.first()
        DbtArtifactContext.record(root.directory.getPath() + "/target", DbtArtifactContext.identity(root))
    }

    private val clipboard: String
        get() = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()

    private fun save() {
        FileDocumentManager.getInstance().saveAllDocuments()
        // Save actions can change a document (for example, adding an EOF newline) after the edit was committed.
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments()
    }

    fun testCopyWholeDocumentPreservesSourceAndCommentsJinjaForRoundTrip() {
        setupRelations()
        val sql =
            "{{ config(materialized='table') }}\nselect * from {{- ref('orders') -}} join {{ source('raw', 'events') }} using(id)"
        val file = configure("models/report.sql", sql + "<caret>")
        myFixture.testAction(DbtCopyForTargetAction())
        await("Clipboard action completed") { clipboard.contains("physical_orders") }
        assertEquals(sql, file.getText())
        assertTrue(clipboard.contains("\"db\".\"mart\".\"physical_orders\""))
        assertTrue(clipboard.contains("db.landing.actual_events"))
        assertTrue(clipboard.contains("/* dbt-workspace-jinja:"))
        val w = workspace;
        val root = w.root(file)!!
        val restored = DbtClipboard.paste(w, root, clipboard).getOrThrow()
        assertTrue(restored.startsWith("{{ config(materialized='table') }}"))
        assertTrue(restored.contains("{{ ref('orders') }}"));
        assertTrue(restored.contains("{{ source('raw', 'events') }}"))
    }

    fun testCopySelectionAndThisUseTheResolvedPhysicalRelation() {
        setupRelations()
        val file = configure("models/orders.sql", "select * from {{ this }} where id > 0<caret>")
        val start = file.getText().indexOf("{{")
        myFixture.getEditor().getSelectionModel().setSelection(start, start + "{{ this }}".length)
        myFixture.testAction(DbtCopyForTargetAction())
        await("Clipboard action completed") { clipboard == "\"db\".\"mart\".\"physical_orders\"" }
        assertTrue(file.getText().contains("{{ this }}"))
    }

    fun testPasteReplacesSelectionMovesCaretAndHasOneUndoStep() {
        setupRelations()
        configure("models/report.sql", "select <caret>replace_me")
        myFixture.getEditor().getSelectionModel().setSelection(7, 17)
        CopyPasteManager.getInstance().setContents(StringSelection("* from db.mart.physical_orders"))
        val w = workspace
        assertEquals(
            "* from {{ ref('orders') }}",
            DbtClipboard.paste(w, w.roots.find { it.name == "analytics" }!!, "* from db.mart.physical_orders")
                .getOrThrow()
        )
        val event = myFixture.testAction(DbtPasteAsRefsAction())
        assertTrue(event.isEnabled)
        await("Clipboard action completed") { myFixture.getEditor().getDocument().getText().contains("ref('orders')") }
        val expected = "select * from {{ ref('orders') }}"
        assertEquals(expected, myFixture.getEditor().getDocument().getText())
        assertEquals(expected.length, myFixture.getEditor().getCaretModel().getOffset())
        myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO)
        assertEquals("select replace_me", myFixture.getEditor().getDocument().getText())
    }

    fun testPasteInsertsAtCaretAndDoesNotRewriteQualifiersCommentsStringsOrUnknownRelations() {
        setupRelations()
        val file = configure("models/report.sql", "<caret>")
        val sql =
            "select o.id, 'db.mart.physical_orders' from \"db\".\"mart\".\"physical_orders\" o -- db.mart.physical_orders\njoin mystery.other_table u on o.id=u.id"
        val w = workspace
        val converted = DbtClipboard.paste(w, w.root(file)!!, sql).getOrThrow()
        assertTrue(converted, converted.contains("{{ ref('orders') }}"))
        CopyPasteManager.getInstance().setContents(StringSelection(sql))
        myFixture.testAction(DbtPasteAsRefsAction())
        await("Clipboard action completed") {
            myFixture.getEditor().getDocument().getText().contains("{{ ref('orders') }}")
        }
        val text = myFixture.getEditor().getDocument().getText()
        assertTrue(text.contains("o.id"));
        assertTrue(text.contains("'db.mart.physical_orders'"))
        assertTrue(text.contains("-- db.mart.physical_orders"));
        assertTrue(text.contains("mystery.other_table"))
    }

    fun testUnknownRefIsCommentedAndOnlyOurOwnCommentsAreRestored() {
        setupRelations()
        val file = configure("models/report.sql", "select {{ ref('mis<caret>sing') }} /* ordinary {{ config(x=1) }} */")
        val copied = DbtClipboard.copy(file, TextRange(0, file.getTextLength())).getOrThrow()
        assertFalse(copied.contains("invented"))
        val w = workspace
        val restored = DbtClipboard.paste(w, w.root(file)!!, copied).getOrThrow()
        assertTrue(restored.contains("{{ ref('missing') }}"))
        assertTrue(restored.contains("/* ordinary {{ config(x=1) }} */"))
    }

    fun testChangingTargetOrManifestBytesInvalidatesPhysicalConversion() {
        setupRelations()
        val w = workspace;
        val root = w.roots.first()
        assertTrue(DbtClipboard.ready(root).isSuccess)
        root.runOptions.target = "prod"
        assertTrue(DbtClipboard.ready(root).isFailure)
        root.runOptions.target = ""
        val manifest = root.psiFile("target/manifest.json")!!
        edit(manifest, manifest.getText().replace("physical_orders", "new_orders"))
        save()
        assertTrue(DbtClipboard.ready(root).isFailure)
    }

    fun testProfilesDirectoryAndUnsavedProfileChangesInvalidatePhysicalConversion() {
        setupRelations()
        val w = workspace
        val root = w.roots.first()
        val first = add(
            "profiles_one/profiles.yml",
            "analytics:\n  target: dev\n  outputs:\n    dev: {type: postgres, dbname: warehouse_one, schema: public}\n"
        )
        add(
            "profiles_two/profiles.yml",
            "analytics:\n  target: dev\n  outputs:\n    dev: {type: postgres, dbname: warehouse_two, schema: public}\n"
        )
        root.runOptions.target = "dev"
        root.runOptions.profilesDirectory = "profiles_one"
        save()
        val artifact = root.artifactDirectory.toString()
        DbtArtifactContext.record(artifact, DbtArtifactContext.identity(root))
        assertEquals(Result.success(Unit), DbtClipboard.ready(root))
        root.runOptions.profilesDirectory = "profiles_two"
        assertTrue("Another profiles directory cannot reuse physical relations", DbtClipboard.ready(root).isFailure)
        root.runOptions.profilesDirectory = "profiles_one"
        assertEquals(Result.success(Unit), DbtClipboard.ready(root))
        edit(first, first.getText().replace("schema: public", "schema: another_schema"))
        assertTrue("Unsaved profile changes also invalidate conversion", DbtClipboard.ready(root).isFailure)
    }

    fun testCommandRecordsTheConfigurationCapturedBeforeDbtWasLaunched() {
        setupRelations()
        val w = workspace
        val root = w.roots.first()
        val command = DbtCommand.create(
            w,
            DbtRunSelection.available(w).find { it.path == root.directory.getPath() }!!,
            "Generate Docs",
            fullRefresh = false
        ).getOrThrow()
        root.runOptions.executableArguments = "--another-warehouse"
        DbtArtifactContext.record(command.artifactDirectory, command.artifactContext)
        assertTrue(
            "An in-flight command cannot certify later configuration changes",
            DbtClipboard.ready(root).isFailure
        )
    }

    fun testProjectAndVarsChangesInvalidateConversionAndLegacyMarkersAreRejected() {
        setupRelations()
        val root = workspace.roots.first()
        val artifact = root.artifactDirectory
        val config = root.config.getContainingFile()
        edit(config, config.getText() + "vars: {schema_suffix: another}\n")
        assertTrue(DbtClipboard.ready(root).isFailure)
        DbtArtifactContext.record(artifact.toString(), DbtArtifactContext.identity(root))
        assertEquals(Result.success(Unit), DbtClipboard.ready(root))
        add("vars.yml", "vars: {schema_suffix: changed}\n")
        assertTrue(DbtClipboard.ready(root).isFailure)
        java.nio.file.Files.write(
            artifact.resolve(".dbt-workspace-context"),
            listOf(root.executionDirectory.toString(), "", DbtArtifactContext.fingerprint(artifact.toString())!!)
        )
        assertFalse("The old target-only marker must not be accepted", DbtArtifactContext.matches(root))
    }

    fun testAmbiguousTwoPartRelationStaysUnchanged() {
        val settings = com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance()
        val ensureNewline = settings.isEnsureNewLineAtEOF
        try {
            settings.setEnsureNewLineAtEOF(true)
            setupRelations()
            val w = workspace;
            val root = w.roots.first()
            val manifest = root.psiFile("target/manifest.json")!!
            edit(manifest, manifest.getText().replace("db.lookups.country_codes", "another.mart.physical_orders"))
            save()
            DbtArtifactContext.record(root.directory.getPath() + "/target", DbtArtifactContext.identity(root))
            val current = workspace
            assertEquals(
                Result.success("select * from mart.physical_orders"),
                DbtClipboard.paste(current, current.roots.first(), "select * from mart.physical_orders")
            )
        } finally {
            settings.setEnsureNewLineAtEOF(ensureNewline)
        }
    }

    fun testMalformedAndRelationFreeManifestsWarnBeforeConversion() {
        setupRelations()
        val root = workspace.roots.first()
        val manifest = root.psiFile("target/manifest.json")!!
        edit(manifest, "{malformed")
        save()
        DbtArtifactContext.record(root.directory.getPath() + "/target", DbtArtifactContext.identity(root))
        assertTrue(DbtClipboard.ready(root).isFailure)
        edit(
            manifest,
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{}}"""
        )
        save()
        DbtArtifactContext.record(root.directory.getPath() + "/target", DbtArtifactContext.identity(root))
        assertTrue(DbtClipboard.ready(root).isFailure)
    }

    fun testCompoundJinjaExpressionsRemainRecoverableAcrossCopyAndPaste() {
        setupRelations()
        for (expression in listOf(
            "ref('orders') ~ ('_suffix')", "ref('orders') if execute else (none)",
            "ref('orders') | string", "ref('orders') ~ ')'", "source('raw', 'events') ~ ('_suffix')"
        )) {
            val sql = "select * from {{ " + expression + " }}"
            val file = configure("models/report.sql", sql + "<caret>")
            val copied = DbtClipboard.copy(file, TextRange(0, file.getTextLength())).getOrThrow()
            assertTrue(copied, copied.contains("dbt-workspace-jinja:"))
            val w = workspace
            assertEquals(sql, DbtClipboard.paste(w, w.root(file)!!, copied).getOrThrow())
        }
    }

    fun testClipboardActionsOnlyEnableInNonExcludedDbtFiles() {
        fun enabled(action: DbtClipboardAction, file: com.intellij.psi.PsiFile): Boolean {
            val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file).build()
            val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                context, null, "test",
                com.intellij.openapi.actionSystem.ActionUiKind.NONE, null
            )
            action.update(event)
            return event.presentation.isEnabled
        }

        val outside = configure("notes.sql", "select 1<caret>")
        val actions = listOf(DbtCopyForTargetAction(), DbtPasteAsRefsAction())
        actions.forEach { a -> assertFalse(enabled(a, outside)) }
        val config = add("analytics/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
        val model = add("analytics/models/orders.sql", "select 1")
        val excluded = add("analytics/target/compiled/orders.sql", "select 1")
        actions.forEach { a ->
            assertFalse(enabled(a, outside))
            assertTrue("Available without requiring loaded artifacts", enabled(a, model))
            assertFalse("SQL conversion is unavailable in project YAML", enabled(a, config))
            assertFalse(enabled(a, excluded))
        }
    }

    private fun png(): ByteArray {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x40708090)
        image.setRGB(1, 0, 0xff123456.toInt())
        val output = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", output))
        return output.toByteArray()
    }

    fun testNormalCopyOffersEncodedPngAndStandardImageWithExactPixels() {
        val bytes = png()
        var calls = 0
        DbtGraphExport.copyPng(bytes, { content ->
            calls += 1
            assertEquals(
                listOf(DbtGraphExport.PngFlavor, DataFlavor.imageFlavor),
                content.getTransferDataFlavors().toList()
            )
            assertTrue(content.isDataFlavorSupported(DataFlavor.imageFlavor))
            val image = content.getTransferData(DataFlavor.imageFlavor) as BufferedImage
            assertEquals(0x40708090, image.getRGB(0, 0))
            assertEquals(0xff123456.toInt(), image.getRGB(1, 0))
            val stream = content.getTransferData(DbtGraphExport.PngFlavor) as InputStream
            try {
                assertArrayEquals(bytes, stream.readAllBytes())
            } finally {
                stream.close()
            }
            org.junit.Assert.assertThrows(UnsupportedFlavorException::class.java) { content.getTransferData(DataFlavor.stringFlavor) }
        })
        assertEquals(1, calls)
    }

    fun testFailedNativeImageMappingRetriesOnlyEncodedPngWithIndependentStreams() {
        val bytes = png()
        val expected = bytes.clone()
        var attempts = emptyList<Transferable>()
        DbtGraphExport.copyPng(bytes, { content ->
            attempts += content
            if (content.isDataFlavorSupported(DataFlavor.imageFlavor))
                throw NullPointerException("Native clipboard ImageWriter unavailable")
        })
        assertEquals(2, attempts.size)
        val encoded = attempts.last()
        assertEquals(listOf(DbtGraphExport.PngFlavor), encoded.getTransferDataFlavors().toList())
        assertEquals(
            "image/png",
            DbtGraphExport.PngFlavor.getPrimaryType() + "/" + DbtGraphExport.PngFlavor.getSubType()
        )
        assertTrue(DbtGraphExport.PngFlavor.isRepresentationClassInputStream)
        assertFalse(encoded.isDataFlavorSupported(DataFlavor.imageFlavor))
        org.junit.Assert.assertThrows(UnsupportedFlavorException::class.java) { encoded.getTransferData(DataFlavor.imageFlavor) }
        java.util.Arrays.fill(bytes, 0.toByte())
        val first = encoded.getTransferData(DbtGraphExport.PngFlavor) as InputStream
        try {
            assertArrayEquals(expected, first.readAllBytes())
        } finally {
            first.close()
        }
        val next = encoded.getTransferData(DbtGraphExport.PngFlavor) as InputStream
        try {
            val image = ImageIO.read(next)
            assertEquals(2, image.getWidth())
            assertEquals(0x40708090, image.getRGB(0, 0))
        } finally {
            next.close()
        }
    }

    fun testFailureOfBothNativeAndEncodedPublicationIsNotSuppressed() {
        val failure = IllegalStateException("Clipboard unavailable")
        var calls = 0
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            DbtGraphExport.copyPng(png(), { _ ->
                calls += 1
                throw failure
            })
        }
        assertSame(failure, error)
        assertEquals(2, calls)
    }
}
