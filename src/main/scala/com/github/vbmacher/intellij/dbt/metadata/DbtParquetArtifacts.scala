package com.github.vbmacher.intellij.dbt.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vbmacher.intellij.dbt.lineage.{DbtDependency, DbtDependencySource}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.json.JsonFileType
import com.intellij.json.psi.{JsonArray, JsonFile, JsonObject, JsonStringLiteral}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.search.{GlobalSearchScope, PsiSearchScopeUtil, SearchScope}
import com.intellij.psi.{PsiFile, PsiManager}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager}
import com.intellij.testFramework.LightVirtualFile
import io.airlift.compress.lz4.Lz4Decompressor
import io.airlift.compress.snappy.SnappyDecompressor
import io.airlift.compress.zstd.ZstdDecompressor
import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.compression.CompressionCodecFactory
import org.apache.parquet.compression.CompressionCodecFactory.{BytesInputCompressor, BytesInputDecompressor}
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.{ColumnIOFactory, LocalInputFile}
import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType}

import java.io.{ByteArrayInputStream, IOException}
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Declarations and dependencies of the dbt v2 Information Schema artifacts selected in the settings. */
private[metadata] final class DbtParquetArtifactSource extends DbtDeclarationSource with DbtDependencySource {

  import DbtArtifacts._
  import DbtParquetArtifactSource._

  override def inputs(workspace: DbtWorkspace): Vector[VirtualFile] = workspace.roots.flatMap { root =>
    directory(root).toVector.flatMap(d => tableNames.flatMap(n => Option(d.findChild(s"dbt.$n.parquet"))))
  }

  override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
    val loaded = tables(workspace, root)
    val resources = loaded.filter(t => kinds.contains(t.name)).flatMap { table =>
      table.rows.flatMap { row =>
        val id = row.values.getOrElse("unique_id", "")
        val parts = id.split("\\.").toVector
        val namespace = row.values.get("package_name").orElse(parts.lift(1)).getOrElse(root.name)
        val name = row.values.getOrElse("name", "")
        val kind = kinds(table.name)
        val owner = if (kind == "source_table") row.values.get("source_name").orElse(parts.lift(2)).getOrElse("") else ""
        val version = row.values.get("version")
        val existing = workspace.symbols.exists(s => visible(workspace, root, s) &&
          (s.uniqueId == id || (s.kind == kind && s.namespace == namespace && s.name == name && s.owner == owner && s.version == version)))
        val idKind = if (kind == "source_table") "source" else kind
        val consistent = parts.headOption.contains(idKind) && parts.lift(1).contains(namespace) &&
          row.values.get("resource_type").forall(_ == idKind)
        if (parts.size < 3 || !consistent || name.isEmpty || existing || authored(root, namespace, row.values.get("original_file_path")) ||
          row.values.get("enabled").exists(_.equalsIgnoreCase("false"))) Vector.empty
        else {
          val attrs = attributes(table, row, outdated(workspace, root, row)) + ("resource-root" -> root.directory.getPath)
          withSource(DbtSymbol(kind, name, namespace, table.file, row.range("name"), owner, version, attrs, metadata = true),
            Some(owner).filter(_.nonEmpty), row.range("source_name"))
        }
      }
    }
    val columns = loaded.filter(_.name == "node_columns").flatMap { table =>
      table.rows.flatMap { row =>
        val id = row.values.getOrElse("node_unique_id", "")
        val name = row.values.getOrElse("column_name", "")
        val owners = (workspace.symbols ++ resources).filter(s => s.kind != "column" && s.uniqueId == id && visible(workspace, root, s))
        owners.headOption.toVector.filter(owner => !owner.isStale && name.nonEmpty && !outdated(workspace, root, row))
          .flatMap { owner =>
            if (workspace.columns(owner).exists(_.name == name)) Vector.empty
            else Vector(DbtSymbol("column", name, owner.namespace, table.file, row.range("column_name"), owner.relationName, owner.version,
              (attributes(table, row, stale = false) - "unique_id") ++ workspace.owner(owner).map(r => "resource-root" -> r.directory.getPath),
              metadata = true))
          }
      }
    }
    val views = loaded.flatMap { table =>
      val attrs = Map("artifact" -> "info-schema", "artifact_path" -> table.artifact.getPath, "resource-root" -> root.directory.getPath)
      val top = table.file.getTopLevelValue.asInstanceOf[JsonObject]
      Vector(DbtSymbol("information_schema_view", table.name, root.name, table.file, top.findProperty("view").getValue.getTextRange,
        attributes = attrs, metadata = true)) ++ table.columns.map { case (name, range) =>
        DbtSymbol("information_schema_column", name, root.name, table.file, range, table.name, attributes = attrs, metadata = true)
      }
    }
    resources ++ columns ++ views
  }

  override def dependencies(workspace: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] = workspace.roots.flatMap { root =>
    tables(workspace, root).filter(_.name == "edges").flatMap { table =>
      table.rows.filterNot(outdated(workspace, root, _)).flatMap { row =>
        def targets(key: String): Vector[DbtSymbol] = {
          val all = row.values.get(key).toVector.flatMap(identified(workspace, root, _)).filterNot(_.isStale)
          val code = all.filter(_.isCode)
          if (code.nonEmpty) code else all
        }

        for (child <- targets("child_unique_id"); parent <- targets("parent_unique_id")) yield {
          val ref = new DbtNavigationReference(table.file, row.range("parent_unique_id"), Vector(parent.element), "Information Schema dependency",
            Vector("Artifact: " + table.artifact.getPath))
          DbtDependency(child, parent, ref)
        }
      }
    }
  }.filter(e => scope.forall(inScope(_, e)))
}

/** Read explicitly selected v1 files with Apache Parquet; expose immutable JSON navigation targets. */
private object DbtParquetArtifactSource {
  private val log = Logger.getInstance(getClass)
  private val mapper = new ObjectMapper
  private val cache = Key.create[CachedValue[Option[Table]]]("dbt.parquet.metadata")
  private val maxBytes = 64L * 1024 * 1024
  private val maxRows = 100000L
  private val kinds = Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot",
    "sources" -> "source_table", "functions" -> "function", "analyses" -> "analysis", "checks" -> "check",
    "data_tests" -> "test", "unit_tests" -> "unit_test", "macros" -> "macro", "docs_blocks" -> "doc",
    "groups" -> "group", "exposures" -> "exposure", "metrics" -> "metric", "saved_queries" -> "saved_query",
    "semantic_models" -> "semantic_model")
  private val tableNames = kinds.keys.toVector.sorted ++ Vector("node_columns", "edges")
  private val fields = Set("unique_id", "name", "resource_type", "package_name", "original_file_path",
    "source_name", "version", "latest_version", "enabled", "access", "ingested_at",
    "node_unique_id", "column_name", "parent_unique_id", "child_unique_id")

  private final case class Row(values: Map[String, String], json: JsonObject) {
    def range(key: String): TextRange = Option(json.findProperty(key)).flatMap(p => Option(p.getValue))
      .map(_.getTextRange).getOrElse(json.getTextRange)
  }

  private final case class Table(name: String, artifact: VirtualFile, file: JsonFile,
    columns: Vector[(String, TextRange)], rows: Vector[Row])

  private def directory(root: DbtRoot): Option[VirtualFile] = if (root.engine != "v2" ||
    DbtYaml.map(root.config, "info_schema").flatMap(DbtYaml.string(_, "version")).exists(_ != "1")) None
  else root.setting("info-schema-dir").flatMap(p => Option(root.directory.findFileByRelativePath(p)))
    .filter(d => d.isDirectory && d.getName == "v1" && d.isInLocalFileSystem)

  private def tables(workspace: DbtWorkspace, root: DbtRoot): Vector[Table] = directory(root).toVector.flatMap { d =>
    tableNames.flatMap { name =>
      DbtYaml.psiFile(workspace.project, d.findChild(s"dbt.$name.parquet")).flatMap { binary =>
        CachedValuesManager.getManager(workspace.project).getCachedValue(binary, cache, () =>
          CachedValueProvider.Result.create(read(binary, name), binary), false)
      }
    }
  }

  private def read(binary: PsiFile, name: String): Option[Table] = {
    val artifact = binary.getVirtualFile
    if (!artifact.isInLocalFileSystem || artifact.getLength > maxBytes) return None
    ProgressManager.checkCanceled()
    val options = ParquetReadOptions.builder(new PlainParquetConfiguration()).withCodecFactory(DbtParquetCodecs).build()
    try {
      val reader = ParquetFileReader.open(new LocalInputFile(artifact.toNioPath), options)
      try {
        val schema = reader.getFooter.getFileMetaData.getSchema
        val required = if (name == "node_columns") Set("node_unique_id", "column_name")
        else if (name == "edges") Set("parent_unique_id", "child_unique_id") else Set("unique_id", "name")
        val compatible = required.forall { name =>
          if (!schema.containsField(name)) false else {
            val field = schema.getType(schema.getFieldIndex(name))
            field.isPrimitive && field.asPrimitiveType().getLogicalTypeAnnotation == LogicalTypeAnnotation.stringType()
          }
        }
        if (!compatible ||
          reader.getRecordCount > maxRows || reader.getFooter.getBlocks.asScala.map(_.getTotalByteSize).sum > maxBytes * 2) return None
        val selected = new MessageType(schema.getName, schema.getFields.asScala.filter(t => fields(t.getName) && t.isPrimitive).asJava)
        reader.setRequestedSchema(selected)
        val columnIO = new ColumnIOFactory().getColumnIO(selected, schema, true)
        val rows = Vector.newBuilder[Map[String, String]]
        var pages = reader.readNextRowGroup()
        while (pages != null) {
          ProgressManager.checkCanceled()
          val records = columnIO.getRecordReader(pages, new GroupRecordConverter(selected))
          var i = 0L
          while (i < pages.getRowCount) {
            ProgressManager.checkCanceled()
            val group = records.read()
            rows += selected.getFields.asScala.flatMap { t =>
              val index = selected.getFieldIndex(t.getName)
              if (group.getFieldRepetitionCount(index) == 1) Some(t.getName -> value(group, index)) else None
            }.toMap
            i += 1
          }
          pages = reader.readNextRowGroup()
        }
        val values = rows.result()
        val content = new java.util.LinkedHashMap[String, Object]()
        content.put("artifact", artifact.getPath)
        content.put("view", name)
        content.put("schema_version", "dbt Information Schema v1")
        content.put("columns", schema.getFields.asScala.map(_.getName).asJava)
        content.put("rows", values.map(_.asJava).asJava)
        val virtual = new LightVirtualFile(s"dbt.$name.${Integer.toHexString(artifact.getPath.hashCode)}.parquet.metadata.json",
          JsonFileType.INSTANCE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content))
        virtual.setWritable(false)
        val json = PsiManager.getInstance(binary.getProject).findFile(virtual).asInstanceOf[JsonFile]
        val top = json.getTopLevelValue.asInstanceOf[JsonObject]
        val declarations = top.findProperty("rows").getValue.asInstanceOf[JsonArray].getValueList.asScala.collect { case o: JsonObject => o }.toVector
        val columns = top.findProperty("columns").getValue.asInstanceOf[JsonArray].getValueList.asScala.collect {
          case s: JsonStringLiteral => s.getValue -> s.getTextRange
        }.toVector
        Some(Table(name, artifact, json, columns, values.zip(declarations).map { case (v, j) => Row(v, j) }))
      } finally reader.close()
    } catch {
      case e: ProcessCanceledException => throw e
      case NonFatal(e) => log.warn(s"Cannot read dbt Information Schema artifact ${artifact.getPath}", e); None
    } finally options.getCodecFactory.release()
  }

  private def value(group: Group, index: Int): String = {
    group.getType.getType(index).asPrimitiveType().getLogicalTypeAnnotation match {
      case t: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation =>
        val units = t.getUnit match {
          case LogicalTypeAnnotation.TimeUnit.MILLIS => 1000L
          case LogicalTypeAnnotation.TimeUnit.MICROS => 1000000L
          case LogicalTypeAnnotation.TimeUnit.NANOS => 1000000000L
        }
        val raw = group.getLong(index, 0)
        Instant.ofEpochSecond(Math.floorDiv(raw, units), Math.floorMod(raw, units) * (1000000000L / units)).toString
      case _ => group.getValueToString(index, 0)
    }
  }

  private def outdated(workspace: DbtWorkspace, root: DbtRoot, row: Row): Boolean =
    DbtArtifacts.stale(workspace, root, row.values.get("ingested_at"))

  private def attributes(table: Table, row: Row, stale: Boolean): Map[String, String] = Map(
    "artifact" -> "info-schema", "artifact_path" -> table.artifact.getPath, "stale" -> stale.toString) ++
    row.values.filter { case (k, _) => Set("unique_id", "access", "latest_version", "enabled").contains(k) }

  private def inScope(scope: SearchScope, edge: DbtDependency): Boolean = PsiSearchScopeUtil.isInScope(scope, edge.reference.getElement) ||
    edge.reference.evidence.find(_.startsWith("Artifact: ")).map(_.stripPrefix("Artifact: "))
      .flatMap(p => Option(LocalFileSystem.getInstance.findFileByPath(p)))
      .exists(v => scope match {
        case g: GlobalSearchScope => g.contains(v)
        case _ => false
      })
}

/** Local metadata only needs page decompression, without Hadoop's configuration and codec runtime. */
private[metadata] object DbtParquetCodecs extends CompressionCodecFactory {
  private val MaxPageBytes = 128 * 1024 * 1024

  override def getCompressor(codec: CompressionCodecName): BytesInputCompressor =
    throw new UnsupportedOperationException("dbt metadata is read-only")

  override def getDecompressor(codec: CompressionCodecName): BytesInputDecompressor = new BytesInputDecompressor {
    private def checkedSize(size: Long): Int = {
      if (size < 0 || size > MaxPageBytes) throw new IOException("Parquet page exceeds the metadata size limit")
      size.toInt
    }

    private def decode(input: Array[Byte], size: Int): Array[Byte] = {
      checkedSize(size)
      if (codec == CompressionCodecName.UNCOMPRESSED) {
        if (input.length != size) throw new IOException("Invalid uncompressed Parquet page size")
        return input
      }
      val output = new Array[Byte](size)
      val count = codec match {
        case CompressionCodecName.SNAPPY => new SnappyDecompressor().decompress(input, 0, input.length, output, 0, size)
        case CompressionCodecName.ZSTD => new ZstdDecompressor().decompress(input, 0, input.length, output, 0, size)
        case CompressionCodecName.LZ4_RAW => new Lz4Decompressor().decompress(input, 0, input.length, output, 0, size)
        case CompressionCodecName.GZIP =>
          val stream = new GZIPInputStream(new ByteArrayInputStream(input))
          try {
            val bytes = stream.readNBytes(output, 0, size)
            if (stream.read() != -1) throw new IOException("Invalid decompressed Parquet page size")
            bytes
          } finally stream.close()
        case _ => throw new IOException(s"Unsupported dbt metadata compression: $codec")
      }
      if (count != size) throw new IOException("Invalid decompressed Parquet page size")
      output
    }

    override def decompress(bytes: BytesInput, size: Int): BytesInput = {
      val input = bytes.toInputStream.readNBytes(checkedSize(bytes.size()))
      BytesInput.from(decode(input, size))
    }

    override def decompress(input: ByteBuffer, compressedSize: Int, output: ByteBuffer, size: Int): Unit = {
      val bytes = new Array[Byte](checkedSize(compressedSize))
      input.get(bytes)
      output.put(decode(bytes, size))
    }

    override def release(): Unit = ()
  }

  override def release(): Unit = ()
}
