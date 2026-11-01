package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.searches.ReferencesSearch
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.column.ParquetProperties.WriterVersion
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageTypeParser
import org.junit.Assert.*
import java.nio.file.Files

class DbtParquetNavigationTest : DbtTestCase() {
    // Keep physical artifact fixtures out of the light project used by source-only suites.
    override fun getProjectDescriptor(): com.intellij.testFramework.LightProjectDescriptor =
        DbtParquetNavigationTest.descriptor

    override fun createTempDirTestFixture(): com.intellij.testFramework.fixtures.TempDirTestFixture =
        com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        val directory = myFixture.tempDirFixture.findOrCreateDir("")
        com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule(), { model ->
            model.contentEntries.forEach(model::removeContentEntry)
            model.addContentEntry(directory)
        })
    }

    private val future = "2099-01-01T00:00:00Z"

    private fun infoSchemaProject(engine: String = "v2", directory: String = "target/info_schema/v1") {
        add("dbt_project.yml", "name: analytics\ninfo_schema:\n  version: 1\n")
        configureSettings { o -> o.engine = engine; o.infoSchemaDirectory = directory }
    }

    private fun parquet(
        table: String, columns: String, rows: List<Map<String, String>>,
        codec: CompressionCodecName = CompressionCodecName.SNAPPY,
        directory: String = "target/info_schema/v1", version: WriterVersion = WriterVersion.PARQUET_1_0
    ): VirtualFile {
        val schema = MessageTypeParser.parseMessageType("message dbt { " + columns + " }")
        val temporary = Files.createTempFile("dbt-info-schema-", ".parquet")
        Files.delete(temporary)
        try {
            val writer = ExampleParquetWriter.builder(LocalOutputFile(temporary)).withConf(PlainParquetConfiguration())
                .withType(schema).withCompressionCodec(codec).withWriterVersion(version).build()
            try {
                rows.forEach { row ->
                    val group = SimpleGroupFactory(schema).newGroup()
                    row.forEach { (key, value) ->
                        val field = schema.getType(schema.getFieldIndex(key)).asPrimitiveType()
                        when (field.primitiveTypeName.toString()) {
                            "BOOLEAN" -> group.add(key, value.toBoolean())
                            "INT64" -> group.add(key, value.toLong())
                            "INT32" -> group.add(key, value.toInt())
                            else -> group.add(key, value)
                        }
                    }
                    writer.write(group)
                }
            } finally {
                writer.close()
            }
            val bytes = Files.readAllBytes(temporary)
            val parent = myFixture.tempDirFixture.findOrCreateDir(directory)
            lateinit var file: VirtualFile
            WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
                override fun run() {
                    file = parent.findChild("dbt.$table.parquet") ?: parent.createChildData(this, "dbt.$table.parquet")
                    file.setBinaryContent(bytes)
                }
            })
            return file
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private val modelSchema =
        "optional binary unique_id (UTF8); optional binary name (UTF8); optional binary package_name (UTF8); " +
                "optional binary original_file_path (UTF8); optional boolean enabled; optional int32 version; optional int32 latest_version; optional binary ingested_at (UTF8);"

    private fun model(name: String, extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "unique_id" to "model.analytics.$name",
            "name" to name,
            "package_name" to "analytics",
            "ingested_at" to future
        ) + extra

    private fun references(file: PsiFile, name: String): List<DbtNavigationReference> =
        DbtNavigation.at(file, file.text.lastIndexOf(name) + 1)

    private fun target(file: PsiFile, name: String): DbtSymbolElement {
        val candidates = references(file, name).flatMap { it.candidates }.distinct()
        assertEquals(
            "Targets for $name: $candidates; symbols: ${workspace.symbols.map { s -> s.kind + ":" + s.name }}",
            1,
            candidates.size
        )
        return candidates.first() as DbtSymbolElement
    }

    fun testSameNamedProjectsKeepSelectedMetadataAndEdgesSeparate() {
        infoSchemaProject()
        add("other/dbt_project.yml", "name: analytics\n")
        add("other/models/remote.sql", "select 1")
        add("other/models/schema.yml", "models:\n- name: remote\n  columns: [{name: customer_id}]\n")
        parquet("models", modelSchema, listOf(model("remote")))
        parquet(
            "node_columns",
            "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
            listOf(
                mapOf(
                    "node_unique_id" to "model.analytics.remote",
                    "column_name" to "customer_id",
                    "ingested_at" to future
                )
            )
        )
        val edgeFile = parquet(
            "edges",
            "optional binary parent_unique_id (UTF8); optional binary child_unique_id (UTF8); optional binary ingested_at (UTF8);",
            listOf(
                mapOf(
                    "parent_unique_id" to "model.analytics.remote",
                    "child_unique_id" to "model.analytics.use",
                    "ingested_at" to future
                )
            )
        )
        val use = add("models/use.sql", "select r.customer_id from {{ ref('remote') }} r")
        assertTrue(target(use, "remote").symbol.metadata)
        assertTrue(target(use, "customer_id").symbol.metadata)
        val w = workspace
        val metadataEdge = DbtLineage.edges(w).find { it.reference.label == "Information Schema dependency" }!!
        assertTrue(metadataEdge.to.metadata)
        assertEquals(w.root(use), w.owner(metadataEdge.to))
        assertEquals(
            1,
            DbtLineage.edges(w, (com.intellij.psi.search.GlobalSearchScope.fileScope(getProject(), edgeFile))).size
        )
    }

    fun testParquetMetadataTargetsAndReverseSearch() {
        infoSchemaProject()
        parquet("models", modelSchema, listOf(model("remote"), model("disabled", mapOf("enabled" to "false"))))
        val usage = add("models/use.sql", "{{ ref('remote') }} {{ ref('disabled') }}")
        val declaration = target(usage, "remote")
        assertTrue(declaration.symbol.metadata)
        assertEquals("info-schema", declaration.symbol.attributes["artifact"])
        assertEquals("false", declaration.symbol.attributes["stale"])
        assertFalse(declaration.containingFile.virtualFile.isWritable)
        assertTrue(declaration.containingFile.name.endsWith("parquet.metadata.json"))
        assertTrue(declaration.textRange.substring(declaration.containingFile.text).contains("remote"))
        assertEquals(1, ReferencesSearch.search(declaration).findAll().size)
        assertTrue(references(usage, "disabled").all { it.candidates.isEmpty() })
        val local = add("models/remote.sql", "select 1")
        assertEquals(local, target(usage, "remote").containingFile)
    }

    fun testMetadataVersionsSourcesAndSchemaDefinitions() {
        infoSchemaProject()
        parquet(
            "models",
            modelSchema,
            listOf(
                model(
                    "orders",
                    mapOf("unique_id" to "model.analytics.orders.v1", "version" to "1", "latest_version" to "1")
                )
            )
        )
        parquet(
            "sources",
            "optional binary unique_id (UTF8); optional binary name (UTF8); optional binary source_name (UTF8); optional binary package_name (UTF8);",
            listOf(
                mapOf(
                    "unique_id" to "source.analytics.raw.events",
                    "name" to "events",
                    "source_name" to "raw",
                    "package_name" to "analytics"
                )
            )
        )
        val usage = add("models/use.sql", "{{ ref('orders', v=1) }} {{ source('raw', 'events') }}")
        assertEquals(("1"), target(usage, "orders").symbol.version)
        assertEquals("source", target(usage, "raw").symbol.kind)
        assertEquals("source_table", target(usage, "events").symbol.kind)
        val check = add("checks/check.sql", "select name from {{ info_schema('models') }}")
        assertEquals("information_schema_view", target(check, "models").symbol.kind)
        assertEquals("information_schema_column", target(check, "name").symbol.kind)
    }

    fun testColumnsAndEdgesMapToSourceWhileKeepingSourceColumns() {
        infoSchemaProject()
        val local = add("models/local.sql", "select 1")
        add("models/schema.yml", "models:\n- name: local\n  columns:\n  - name: declared\n")
        parquet("models", modelSchema, listOf(model("remote")))
        parquet(
            "node_columns",
            "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
            listOf(
                mapOf("node_unique_id" to "model.analytics.remote", "column_name" to "id", "ingested_at" to future),
                mapOf("node_unique_id" to "model.analytics.local", "column_name" to "declared", "ingested_at" to future)
            )
        )
        parquet(
            "edges",
            "optional binary parent_unique_id (UTF8); optional binary child_unique_id (UTF8); optional binary ingested_at (UTF8);",
            listOf(
                mapOf(
                    "parent_unique_id" to "model.analytics.remote",
                    "child_unique_id" to "model.analytics.local",
                    "ingested_at" to future
                )
            )
        )
        val usage = add("models/use.sql", "select r.id from {{ ref('remote') }} r")
        assertEquals("column", target(usage, "id").symbol.kind)
        assertEquals(
            1,
            workspace.symbols.count { s -> s.kind == "column" && s.owner == "local" && s.name == "declared" })
        assertFalse(workspace.symbols.find { s -> s.kind == "column" && s.name == "declared" }!!.metadata)
        val edge = DbtLineage.edges(workspace).find { e -> e.from.file == local && e.to.name == "remote" }!!
        assertTrue(edge.reference.element.name.endsWith("parquet.metadata.json"))
        assertTrue(
            edge.reference.rangeInElement.substring(edge.reference.element.text).contains("model.analytics.remote")
        )
    }

    fun testStaleMissingTimestampAndUnsavedEditsDoNotSupplyColumnsOrEdges() {
        infoSchemaProject()
        parquet("models", modelSchema, listOf(model("remote", mapOf("ingested_at" to "2000-01-01T00:00:00Z"))))
        parquet(
            "node_columns",
            "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
            listOf(
                mapOf(
                    "node_unique_id" to "model.analytics.remote",
                    "column_name" to "id",
                    "ingested_at" to "2000-01-01T00:00:00Z"
                )
            )
        )
        val usage = add("models/use.sql", "select r.id from {{ ref('remote') }} r")
        assertEquals("true", target(usage, "remote").symbol.attributes["stale"])
        assertTrue(references(usage, "id").all { it.candidates.isEmpty() })
        parquet("models", modelSchema, listOf(model("remote")))
        assertEquals("false", target(usage, "remote").symbol.attributes["stale"])
        val doc = PsiDocumentManager.getInstance(getProject()).getDocument(usage)!!
        WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run() {
                doc.insertString(0, "-- unsaved\n")
                PsiDocumentManager.getInstance(getProject()).commitDocument(doc)
            }
        })
        assertEquals("true", target(usage, "remote").symbol.attributes["stale"])
    }

    fun testExplicitSelectionVersionAndMalformedFiles() {
        infoSchemaProject(directory = "")
        parquet("models", modelSchema, listOf(model("remote")))
        val usage = add("models/use.sql", "{{ ref('remote') }}")
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
        configureSettings { o -> o.engine = "v2"; o.infoSchemaDirectory = "target/info_schema/v2" }
        parquet("models", modelSchema, listOf(model("remote")), directory = "target/info_schema/v2")
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
        configureSettings { o -> o.engine = "v2"; o.infoSchemaDirectory = "target/info_schema/v1" }
        val artifact = myFixture.findFileInTempDir("target/info_schema/v1/dbt.models.parquet")
        WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run(): Unit = artifact.setBinaryContent(byteArrayOf(1, 2, 3))
        })
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
        parquet("models", "optional binary unrelated (UTF8);", listOf(mapOf("unrelated" to "remote")))
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
        parquet("models", modelSchema, listOf(model("remote", mapOf("unique_id" to "seed.analytics.remote"))))
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
        parquet(
            "models",
            "optional int32 unique_id; optional binary name (UTF8);",
            listOf(mapOf("unique_id" to "1", "name" to "remote"))
        )
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
    }

    fun testFreshColumnsAndEdgesDoNotReviveStaleResources() {
        infoSchemaProject()
        parquet("models", modelSchema, listOf(model("remote", mapOf("ingested_at" to "2000-01-01T00:00:00Z"))))
        parquet(
            "node_columns",
            "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
            listOf(mapOf("node_unique_id" to "model.analytics.remote", "column_name" to "id", "ingested_at" to future))
        )
        parquet(
            "edges",
            "optional binary parent_unique_id (UTF8); optional binary child_unique_id (UTF8); optional binary ingested_at (UTF8);",
            listOf(
                mapOf(
                    "parent_unique_id" to "model.analytics.remote",
                    "child_unique_id" to "model.analytics.use",
                    "ingested_at" to future
                ),
                mapOf(
                    "parent_unique_id" to "model.analytics.use",
                    "child_unique_id" to "model.analytics.remote",
                    "ingested_at" to future
                )
            )
        )
        val usage = add("models/use.sql", "select r.id from {{ ref('remote') }} r")
        assertTrue(target(usage, "remote").symbol.isStale)
        assertTrue(references(usage, "id").all { it.candidates.isEmpty() })
        assertTrue(DbtLineage.edges(workspace).all { it.reference.label != "Information Schema dependency" })

        parquet("models", modelSchema, listOf(model("remote")))
        assertFalse(target(usage, "remote").symbol.isStale)
        assertEquals("column", target(usage, "id").symbol.kind)
        assertEquals(2, DbtLineage.edges(workspace).count { it.reference.label == "Information Schema dependency" })
    }

    fun testCompressionAndTimestampLogicalTypes() {
        infoSchemaProject()
        val schema =
            "optional binary unique_id (UTF8); optional binary name (UTF8); optional int64 ingested_at (TIMESTAMP(MICROS,true));"
        val micros = java.time.Instant.parse(future).epochSecond * 1000000L
        for (codec in listOf(
            CompressionCodecName.UNCOMPRESSED, CompressionCodecName.SNAPPY, CompressionCodecName.GZIP,
            CompressionCodecName.ZSTD, CompressionCodecName.LZ4_RAW
        )) {
            parquet(
                "models",
                schema,
                listOf(
                    mapOf(
                        "unique_id" to "model.analytics.remote",
                        "name" to "remote",
                        "ingested_at" to micros.toString()
                    )
                ),
                codec
            )
            val usage = add("models/use_${codec.toString().lowercase()}.sql", "{{ ref('remote') }}")
            assertEquals(codec.toString(), "false", target(usage, "remote").symbol.attributes["stale"])
        }
    }

    fun testCoreProjectsAndOversizedTablesStayUnresolved() {
        infoSchemaProject(engine = "core")
        parquet("models", modelSchema, listOf(model("remote")))
        val usage = add("models/use.sql", "{{ ref('remote') }}")
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
        configureSettings { o -> o.engine = "v2"; o.infoSchemaDirectory = "target/info_schema/v1" }
        parquet("models", modelSchema, List(100001) { model("remote") })
        assertTrue(references(usage, "remote").all { it.candidates.isEmpty() })
    }

    fun testCompressedV2PagesSupplyMetadata() {
        infoSchemaProject()
        for (codec in listOf(
            CompressionCodecName.SNAPPY, CompressionCodecName.GZIP,
            CompressionCodecName.ZSTD, CompressionCodecName.LZ4_RAW
        )) {
            parquet(
                "models", modelSchema, (1..20).map { i -> model("remote_$i") }.toList(),
                codec, version = WriterVersion.PARQUET_2_0
            )
            val usage = add("models/use_${codec.toString().lowercase()}.sql", "{{ ref('remote_20') }}")
            assertFalse(codec.toString(), target(usage, "remote_20").symbol.isStale)
        }
    }

    companion object {
        val descriptor = com.intellij.testFramework.LightProjectDescriptor()
    }
}
