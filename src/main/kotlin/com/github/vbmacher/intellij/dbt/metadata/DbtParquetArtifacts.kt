package com.github.vbmacher.intellij.dbt.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vbmacher.intellij.dbt.lineage.*
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.json.JsonFileType
import com.intellij.json.psi.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.*
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.*
import com.intellij.testFramework.LightVirtualFile
import io.airlift.compress.lz4.Lz4Decompressor
import io.airlift.compress.snappy.SnappyDecompressor
import io.airlift.compress.zstd.ZstdDecompressor
import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.compression.CompressionCodecFactory
import org.apache.parquet.compression.CompressionCodecFactory.BytesInputCompressor
import org.apache.parquet.compression.CompressionCodecFactory.BytesInputDecompressor
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.MessageType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * Reads explicitly configured local metadata tables; it never queries a warehouse.
 * Validated rows supply only resources absent from authored and earlier metadata sources. Stale rows remain marked
 * as metadata but cannot supply current columns or dependencies. Each file is limited to 64 MiB and 100,000 rows.
 */
class DbtParquetArtifactSource : DbtDeclarationSource, DbtDependencySource {
    override fun inputs(workspace: DbtWorkspace): List<VirtualFile> = workspace.roots.flatMap { root ->
        directory(root)?.let { d -> tableNames.mapNotNull { d.findChild("dbt.$it.parquet") } }.orEmpty()
    }

    override fun declarations(workspace: DbtWorkspace): List<DbtSymbol> = workspace.roots.flatMap { root ->
        val loaded = tables(workspace, root)
        val resources = loaded.filter { it.name in kinds }.flatMap { table ->
            table.rows.flatMap { row ->
                val id = row.values["unique_id"].orEmpty();
                val parts = id.split('.');
                val namespace = row.values["package_name"] ?: parts.getOrNull(1) ?: root.name
                val name = row.values["name"].orEmpty();
                val kind = kinds.getValue(table.name);
                val owner =
                    if (kind == "source_table") row.values["source_name"] ?: parts.getOrNull(2).orEmpty() else "";
                val version = row.values["version"]
                val existing = workspace.symbols.any {
                    DbtArtifacts.visible(
                        workspace,
                        root,
                        it
                    ) && (it.uniqueId == id || it.kind == kind && it.namespace == namespace && it.name == name && it.owner == owner && it.version == version)
                }
                val idKind = if (kind == "source_table") "source" else kind
                val consistent =
                    parts.firstOrNull() == idKind && parts.getOrNull(1) == namespace && (row.values["resource_type"]?.let { it == idKind }
                        ?: true)
                if (parts.size < 3 || !consistent || name.isEmpty() || existing || DbtArtifacts.authored(
                        root,
                        namespace,
                        row.values["original_file_path"]
                    ) || row.values["enabled"].equals("false", true)
                ) emptyList()
                else {
                    val attrs = attributes(
                        table,
                        row,
                        outdated(workspace, root, row)
                    ) + ("resource-root" to root.directory.path)
                    DbtArtifacts.withSource(
                        DbtSymbol(
                            kind,
                            name,
                            namespace,
                            table.file,
                            row.range("name"),
                            owner,
                            version,
                            attrs,
                            metadata = true
                        ), owner.takeIf { it.isNotEmpty() }) { row.range("source_name") }
                }
            }
        }
        val columns = loaded.filter { it.name == "node_columns" }.flatMap { table ->
            table.rows.flatMap { row ->
                val id = row.values["node_unique_id"].orEmpty();
                val name = row.values["column_name"].orEmpty()
                val owners = (workspace.symbols + resources).filter {
                    it.kind != "column" && it.uniqueId == id && DbtArtifacts.visible(
                        workspace,
                        root,
                        it
                    )
                }
                owners.firstOrNull()?.takeIf { !it.isStale && name.isNotEmpty() && !outdated(workspace, root, row) }
                    ?.let { owner ->
                        if (workspace.columns(owner).any { it.name == name }) emptyList() else listOf(
                            DbtSymbol(
                                "column",
                                name,
                                owner.namespace,
                                table.file,
                                row.range("column_name"),
                                owner.relationName,
                                owner.version,
                                (attributes(table, row, false) - "unique_id") + (workspace.owner(owner)
                                    ?.let { mapOf("resource-root" to it.directory.path) } ?: emptyMap()),
                                metadata = true))
                    }.orEmpty()
            }
        }
        val views = loaded.flatMap { table ->
            val attrs = mapOf(
                "artifact" to "info-schema",
                "artifact_path" to table.artifact.path,
                "resource-root" to root.directory.path
            )
            val top = table.file.topLevelValue as JsonObject
            listOf(
                DbtSymbol(
                    "information_schema_view",
                    table.name,
                    root.name,
                    table.file,
                    top.findProperty("view")!!.value!!.textRange,
                    attributes = attrs,
                    metadata = true
                )
            ) + table.columns.map { (name, range) ->
                DbtSymbol(
                    "information_schema_column",
                    name,
                    root.name,
                    table.file,
                    range,
                    table.name,
                    attributes = attrs,
                    metadata = true
                )
            }
        }
        resources + columns + views
    }

    override fun dependencies(workspace: DbtWorkspace, scope: SearchScope?): List<DbtDependency> =
        workspace.roots.flatMap { root ->
            tables(workspace, root).filter { it.name == "edges" }.flatMap { table ->
                table.rows.filterNot { outdated(workspace, root, it) }.flatMap { row ->
                    fun targets(key: String): List<DbtSymbol> {
                        val all = row.values[key]?.let { DbtArtifacts.identified(workspace, root, it) }.orEmpty()
                            .filterNot { it.isStale }; return all.filter { it.isCode }.ifEmpty { all }
                    }
                    targets("child_unique_id").flatMap { child ->
                        targets("parent_unique_id").map { parent ->
                            val ref = DbtNavigationReference(
                                table.file,
                                row.range("parent_unique_id"),
                                listOf(parent.element),
                                "Information Schema dependency",
                                listOf("Artifact: " + table.artifact.path)
                            )
                            DbtDependency(child, parent, ref)
                        }
                    }
                }
            }
        }.filter { scope == null || inScope(scope, it) }

    companion object {
        private val log = Logger.getInstance(DbtParquetArtifactSource::class.java)
        private val mapper = ObjectMapper()
        private val cache = Key.create<CachedValue<Table?>>("dbt.parquet.metadata")
        private const val maxBytes = 64L * 1024 * 1024
        private const val maxRows = 100000L
        private val kinds = mapOf(
            "models" to "model",
            "seeds" to "seed",
            "snapshots" to "snapshot",
            "sources" to "source_table",
            "functions" to "function",
            "analyses" to "analysis",
            "checks" to "check",
            "data_tests" to "test",
            "unit_tests" to "unit_test",
            "macros" to "macro",
            "docs_blocks" to "doc",
            "groups" to "group",
            "exposures" to "exposure",
            "metrics" to "metric",
            "saved_queries" to "saved_query",
            "semantic_models" to "semantic_model"
        )
        private val tableNames = kinds.keys.sorted() + listOf("node_columns", "edges")
        private val fields = setOf(
            "unique_id",
            "name",
            "resource_type",
            "package_name",
            "original_file_path",
            "source_name",
            "version",
            "latest_version",
            "enabled",
            "access",
            "ingested_at",
            "node_unique_id",
            "column_name",
            "parent_unique_id",
            "child_unique_id"
        )

        private data class Row(val values: Map<String, String>, val json: JsonObject) {
            fun range(key: String): TextRange = json.findProperty(key)?.value?.textRange ?: json.textRange
        }

        private data class Table(
            val name: String,
            val artifact: VirtualFile,
            val file: JsonFile,
            val columns: List<Pair<String, TextRange>>,
            val rows: List<Row>
        )

        private fun directory(root: DbtRoot): VirtualFile? =
            if (root.engine != "v2" || DbtYaml.map(root.config, "info_schema")?.let { DbtYaml.string(it, "version") }
                    ?.let { it != "1" } == true) null else root.setting("info-schema-dir")
                ?.let { root.directory.findFileByRelativePath(it) }
                ?.takeIf { it.isDirectory && it.name == "v1" && it.isInLocalFileSystem }

        private fun tables(workspace: DbtWorkspace, root: DbtRoot): List<Table> = directory(root)?.let { d ->
            tableNames.mapNotNull { name ->
                DbtYaml.psiFile(workspace.project, d.findChild("dbt.$name.parquet"))?.let { binary ->
                    CachedValuesManager.getManager(workspace.project).getCachedValue(
                        binary,
                        cache,
                        { CachedValueProvider.Result.create(read(binary, name), binary) },
                        false
                    )
                }
            }
        }.orEmpty()

        private fun read(binary: PsiFile, name: String): Table? {
            val artifact = binary.virtualFile
            if (!artifact.isInLocalFileSystem || artifact.length > maxBytes) return null
            ProgressManager.checkCanceled()
            val options =
                ParquetReadOptions.builder(PlainParquetConfiguration()).withCodecFactory(DbtParquetCodecs).build()
            return try {
                ParquetFileReader.open(LocalInputFile(artifact.toNioPath()), options).use { reader ->
                    val schema = reader.footer.fileMetaData.schema
                    val required = if (name == "node_columns") setOf(
                        "node_unique_id",
                        "column_name"
                    ) else if (name == "edges") setOf("parent_unique_id", "child_unique_id") else setOf(
                        "unique_id",
                        "name"
                    )
                    val compatible = required.all {
                        if (!schema.containsField(it)) false else {
                            val field =
                                schema.getType(schema.getFieldIndex(it)); field.isPrimitive && field.asPrimitiveType().logicalTypeAnnotation == LogicalTypeAnnotation.stringType()
                        }
                    }
                    if (!compatible || reader.recordCount > maxRows || reader.footer.blocks.sumOf { it.totalByteSize } > maxBytes * 2) return null
                    val selected =
                        MessageType(schema.name, schema.fields.filter { it.name in fields && it.isPrimitive })
                    reader.setRequestedSchema(selected)
                    val columnIO = ColumnIOFactory().getColumnIO(selected, schema, true)
                    val rows = mutableListOf<Map<String, String>>()
                    var pages = reader.readNextRowGroup()
                    while (pages != null) {
                        ProgressManager.checkCanceled()
                        val records = columnIO.getRecordReader(pages, GroupRecordConverter(selected));
                        var i = 0L
                        while (i < pages.rowCount) {
                            ProgressManager.checkCanceled();
                            val group = records.read()
                            rows += selected.fields.mapNotNull { t ->
                                val index =
                                    selected.getFieldIndex(t.name); if (group.getFieldRepetitionCount(index) == 1) t.name to value(
                                group,
                                index
                            ) else null
                            }.toMap(); i++
                        }
                        pages = reader.readNextRowGroup()
                    }
                    val content = linkedMapOf<String, Any>(
                        "artifact" to artifact.path,
                        "view" to name,
                        "schema_version" to "dbt Information Schema v1",
                        "columns" to schema.fields.map { it.name },
                        "rows" to rows
                    )
                    val virtual = LightVirtualFile(
                        "dbt.$name.${Integer.toHexString(artifact.path.hashCode())}.parquet.metadata.json",
                        JsonFileType.INSTANCE,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content)
                    ); virtual.isWritable = false
                    val json = PsiManager.getInstance(binary.project).findFile(virtual) as JsonFile;
                    val top = json.topLevelValue as JsonObject
                    val declarations =
                        (top.findProperty("rows")!!.value as JsonArray).valueList.filterIsInstance<JsonObject>()
                    val columns =
                        (top.findProperty("columns")!!.value as JsonArray).valueList.filterIsInstance<JsonStringLiteral>()
                            .map { it.value to it.textRange }
                    Table(name, artifact, json, columns, rows.zip(declarations).map { (v, j) -> Row(v, j) })
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cannot read dbt Information Schema artifact ${artifact.path}", e); null
            } finally {
                options.codecFactory.release()
            }
        }

        private fun value(group: Group, index: Int): String =
            when (val type = group.type.getType(index).asPrimitiveType().logicalTypeAnnotation) {
                is LogicalTypeAnnotation.TimestampLogicalTypeAnnotation -> {
                    val units = when (type.unit) {
                        LogicalTypeAnnotation.TimeUnit.MILLIS -> 1000L; LogicalTypeAnnotation.TimeUnit.MICROS -> 1000000L; LogicalTypeAnnotation.TimeUnit.NANOS -> 1000000000L
                    }
                    val raw = group.getLong(index, 0)
                    Instant.ofEpochSecond(Math.floorDiv(raw, units), Math.floorMod(raw, units) * (1000000000L / units))
                        .toString()
                }

                else -> group.getValueToString(index, 0)
            }

        private fun outdated(workspace: DbtWorkspace, root: DbtRoot, row: Row): Boolean =
            DbtArtifacts.stale(workspace, root, row.values["ingested_at"])

        private fun attributes(table: Table, row: Row, stale: Boolean): Map<String, String> = mapOf(
            "artifact" to "info-schema",
            "artifact_path" to table.artifact.path,
            "stale" to stale.toString()
        ) + row.values.filterKeys { it in setOf("unique_id", "access", "latest_version", "enabled") }

        private fun inScope(scope: SearchScope, edge: DbtDependency): Boolean =
            PsiSearchScopeUtil.isInScope(scope, edge.reference.element) || edge.reference.evidence.find {
                it.startsWith("Artifact: ")
            }?.removePrefix("Artifact: ")?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ?.let { scope is GlobalSearchScope && scope.contains(it) } == true
    }
}

/** Read-only JVM Parquet decompression limited to 128 MiB per page; compression requests are unsupported. */
object DbtParquetCodecs : CompressionCodecFactory {
    private const val MaxPageBytes = 128 * 1024 * 1024
    override fun getCompressor(codec: CompressionCodecName): BytesInputCompressor =
        throw UnsupportedOperationException("dbt metadata is read-only")

    override fun getDecompressor(codec: CompressionCodecName): BytesInputDecompressor =
        object : BytesInputDecompressor {
            private fun checkedSize(size: Long): Int {
                if (size < 0 || size > MaxPageBytes) throw IOException("Parquet page exceeds the metadata size limit"); return size.toInt()
            }

            private fun decode(input: ByteArray, size: Int): ByteArray {
                checkedSize(size.toLong())
                if (codec == CompressionCodecName.UNCOMPRESSED) {
                    if (input.size != size) throw IOException("Invalid uncompressed Parquet page size"); return input
                }
                val output = ByteArray(size)
                val count = when (codec) {
                    CompressionCodecName.SNAPPY -> SnappyDecompressor().decompress(
                        input,
                        0,
                        input.size,
                        output,
                        0,
                        size
                    )

                    CompressionCodecName.ZSTD -> ZstdDecompressor().decompress(input, 0, input.size, output, 0, size)
                    CompressionCodecName.LZ4_RAW -> Lz4Decompressor().decompress(input, 0, input.size, output, 0, size)
                    CompressionCodecName.GZIP -> GZIPInputStream(ByteArrayInputStream(input)).use { stream ->
                        val bytes = stream.readNBytes(
                            output,
                            0,
                            size
                        ); if (stream.read() != -1) throw IOException("Invalid decompressed Parquet page size"); bytes
                    }

                    else -> throw IOException("Unsupported dbt metadata compression: $codec")
                }
                if (count != size) throw IOException("Invalid decompressed Parquet page size")
                return output
            }

            override fun decompress(bytes: BytesInput, size: Int): BytesInput {
                val input = bytes.toInputStream().readNBytes(checkedSize(bytes.size())); return BytesInput.from(
                    decode(
                        input,
                        size
                    )
                )
            }

            override fun decompress(input: ByteBuffer, compressedSize: Int, output: ByteBuffer, size: Int) {
                val bytes = ByteArray(checkedSize(compressedSize.toLong())); input.get(bytes); output.put(
                    decode(
                        bytes,
                        size
                    )
                )
            }

            override fun release() {}
        }

    override fun release() {}
}
