package com.github.vbmacher.intellij.dbt

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.json.JsonFileType
import com.intellij.json.psi.{JsonArray, JsonFile, JsonObject, JsonStringLiteral}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiFile, PsiManager}
import scala.util.Try
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager}
import com.intellij.testFramework.LightVirtualFile
import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.{ColumnIOFactory, LocalInputFile}
import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType}
import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Read explicitly selected v1 files with Apache Parquet; expose immutable JSON navigation targets. */
private[dbt] object DbtParquetArtifacts {
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

  def files(workspace: DbtWorkspace): Vector[VirtualFile] = workspace.roots.flatMap { root =>
    directory(root).toVector.flatMap(d => (kinds.keys.toVector ++ Vector("node_columns", "edges"))
      .flatMap(n => Option(d.findChild(s"dbt.$n.parquet"))))
  }

  private def tables(workspace: DbtWorkspace, root: DbtRoot): Vector[Table] = directory(root).toVector.flatMap { d =>
    (kinds.keys.toVector.sorted ++ Vector("node_columns", "edges")).flatMap { name =>
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
    val options = ParquetReadOptions.builder(new PlainParquetConfiguration()).build()
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
            val row = selected.getFields.asScala.flatMap { t =>
              val index = selected.getFieldIndex(t.getName)
              if (group.getFieldRepetitionCount(index) == 1) Some(t.getName -> value(group, index)) else None
            }.toMap
            rows += row
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

  private def stale(workspace: DbtWorkspace, root: DbtRoot, row: Row): Boolean =
    workspace.outdated(root, row.values.get("ingested_at").flatMap(s => Try(Instant.parse(s).toEpochMilli).toOption))

  private def attributes(table: Table, row: Row, stale: Boolean): Map[String, String] = Map(
    "artifact" -> "info-schema", "artifact_path" -> table.artifact.getPath, "stale" -> stale.toString) ++
    row.values.filter { case (k, _) => Set("unique_id", "access", "latest_version", "enabled").contains(k) }

  def symbols(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
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
        val existing = workspace.symbols.exists(s => workspace.owner(s).exists(r => workspace.namespaceVisible(root, r, qualified = true)) &&
          (DbtArtifacts.uniqueId(s) == id || (s.kind == kind && s.namespace == namespace && s.name == name && s.owner == owner && s.version == version)))
        val sourceExists = namespace == root.name && row.values.get("original_file_path").exists(p => root.directory.findFileByRelativePath(p) != null)
        val idKind = if (kind == "source_table") "source" else kind
        val consistent = parts.headOption.contains(idKind) && parts.lift(1).contains(namespace) &&
          row.values.get("resource_type").forall(_ == idKind)
        if (parts.size < 3 || !consistent || name.isEmpty || existing || sourceExists || row.values.get("enabled").exists(_.equalsIgnoreCase("false"))) Vector.empty
        else {
          val attrs = attributes(table, row, stale(workspace, root, row)) + ("resource-root" -> root.directory.getPath)
          val symbol = DbtSymbol(kind, name, namespace, table.file, row.range("name"), owner, version, attrs, metadata = true)
          if (kind != "source_table" || owner.isEmpty) Vector(symbol)
          else Vector(DbtSymbol("source", owner, namespace, table.file, row.range("source_name"), attributes = attrs - "unique_id", metadata = true), symbol)
        }
      }
    }
    val columns = loaded.filter(_.name == "node_columns").flatMap { table =>
      table.rows.flatMap { row =>
        val id = row.values.getOrElse("node_unique_id", "")
        val name = row.values.getOrElse("column_name", "")
        val owners = (workspace.symbols ++ resources).filter(s => s.kind != "column" && DbtArtifacts.uniqueId(s) == id &&
          workspace.owner(s).exists(r => workspace.namespaceVisible(root, r, qualified = true)))
        owners.headOption.toVector.filter(_ => name.nonEmpty && !stale(workspace, root, row)).flatMap { owner =>
          val ownerName = owner.relationName
          if (workspace.symbols.exists(s => s.kind == "column" && workspace.owner(s) == workspace.owner(owner) && s.namespace == owner.namespace &&
            s.owner == ownerName && s.name == name && s.version == owner.version)) Vector.empty
          else Vector(DbtSymbol("column", name, owner.namespace, table.file, row.range("column_name"), ownerName, owner.version,
            (attributes(table, row, stale = false) - "unique_id") ++ workspace.owner(owner).map(r => "resource-root" -> r.directory.getPath), metadata = true))
        }
      }
    }
    val views = loaded.flatMap { table =>
      val attrs = Map("artifact" -> "info-schema", "artifact_path" -> table.artifact.getPath, "resource-root" -> root.directory.getPath)
      val top = table.file.getTopLevelValue.asInstanceOf[JsonObject]
      Vector(DbtSymbol("information_schema_view", table.name, root.name, table.file, top.findProperty("view").getValue.getTextRange, attributes = attrs, metadata = true)) ++
        table.columns.map { case (name, range) => DbtSymbol("information_schema_column", name, root.name, table.file, range, table.name, attributes = attrs, metadata = true) }
    }
    resources ++ columns ++ views
  }

  def edges(workspace: DbtWorkspace): Vector[DbtDependency] = workspace.roots.flatMap { root =>
    tables(workspace, root).filter(_.name == "edges").flatMap { table =>
      table.rows.filterNot(stale(workspace, root, _)).flatMap { row =>
        def targets(key: String): Vector[DbtSymbol] = {
          val all = workspace.symbols.filter(s => s.kind != "column" && row.values.get(key).contains(DbtArtifacts.uniqueId(s)) &&
            workspace.declarations(Set(s.kind), s.name, root, Some(s.namespace)).contains(s))
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
  }
}
