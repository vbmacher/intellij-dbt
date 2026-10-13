package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtNavigationReference}
import com.github.vbmacher.intellij.dbt.project.{DbtSymbolElement, DbtWorkspace}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiFile, PsiDocumentManager}
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageTypeParser
import org.junit.Assert._
import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class DbtParquetNavigationTest extends BasePlatformTestCase {
  // Keep physical artifact fixtures out of the light project used by source-only suites.
  override protected def getProjectDescriptor(): com.intellij.testFramework.LightProjectDescriptor =
    DbtParquetNavigationTest.descriptor
  override protected def createTempDirTestFixture(): com.intellij.testFramework.fixtures.TempDirTestFixture =
    new com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl()
  override protected def setUp(): Unit = {
    super.setUp()
    val directory = myFixture.getTempDirFixture.findOrCreateDir("")
    com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule, model => {
      model.getContentEntries.foreach(model.removeContentEntry)
      model.addContentEntry(directory)
    })
  }
  private val future = "2099-01-01T00:00:00Z"
  private def add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)
  private def root(settings: String = "engine: v2\ninfo-schema-dir: target/info_schema/v1\n"): Unit = {
    add("dbt_project.yml", "name: analytics\ninfo_schema:\n  version: 1\n")
    add(".dbt-navigation.yml", settings)
  }
  private def parquet(table: String, columns: String, rows: Vector[Map[String, String]],
    codec: CompressionCodecName = CompressionCodecName.SNAPPY,
    directory: String = "target/info_schema/v1"): VirtualFile = {
    val schema = MessageTypeParser.parseMessageType("message dbt { " + columns + " }")
    val temporary = Files.createTempFile("dbt-info-schema-", ".parquet")
    Files.delete(temporary)
    try {
      val writer = ExampleParquetWriter.builder(new LocalOutputFile(temporary)).withConf(new PlainParquetConfiguration())
        .withType(schema).withCompressionCodec(codec).build()
      try rows.foreach { row =>
        val group = new SimpleGroupFactory(schema).newGroup()
        row.foreach { case (key, value) =>
          val field = schema.getType(schema.getFieldIndex(key)).asPrimitiveType()
          field.getPrimitiveTypeName.toString match {
            case "BOOLEAN" => group.add(key, value.toBoolean)
            case "INT64" => group.add(key, value.toLong)
            case "INT32" => group.add(key, value.toInt)
            case _ => group.add(key, value)
          }
        }
        writer.write(group)
      } finally writer.close()
      val bytes = Files.readAllBytes(temporary)
      val parent = myFixture.getTempDirFixture.findOrCreateDir(directory)
      var file: VirtualFile = null
      WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
        override def run(): Unit = {
          file = Option(parent.findChild(s"dbt.$table.parquet")).getOrElse(parent.createChildData(this, s"dbt.$table.parquet"))
          file.setBinaryContent(bytes)
        }
      })
      file
    } finally Files.deleteIfExists(temporary)
  }
  private val modelSchema = "optional binary unique_id (UTF8); optional binary name (UTF8); optional binary package_name (UTF8); " +
    "optional binary original_file_path (UTF8); optional boolean enabled; optional int32 version; optional int32 latest_version; optional binary ingested_at (UTF8);"
  private def model(name: String, extra: Map[String, String] = Map.empty): Map[String, String] =
    Map("unique_id" -> s"model.analytics.$name", "name" -> name, "package_name" -> "analytics", "ingested_at" -> future) ++ extra
  private def references(file: PsiFile, name: String): Vector[DbtNavigationReference] =
    DbtNavigation.at(file, file.getText.lastIndexOf(name) + 1)
  private def target(file: PsiFile, name: String): DbtSymbolElement = {
    val candidates = references(file, name).flatMap(_.candidates).distinct
    assertEquals(s"Targets for $name: $candidates; symbols: ${DbtWorkspace.get(getProject).symbols.map(s => s.kind + ":" + s.name)}", 1, candidates.size)
    candidates.head.asInstanceOf[DbtSymbolElement]
  }

  def testSameNamedProjectsKeepSelectedMetadataAndEdgesSeparate(): Unit = {
    root()
    add("other/dbt_project.yml", "name: analytics\n")
    add("other/models/remote.sql", "select 1")
    add("other/models/schema.yml", "models:\n- name: remote\n  columns: [{name: customer_id}]\n")
    parquet("models", modelSchema, Vector(model("remote")))
    parquet("node_columns", "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
      Vector(Map("node_unique_id" -> "model.analytics.remote", "column_name" -> "customer_id", "ingested_at" -> future)))
    val edgeFile = parquet("edges", "optional binary parent_unique_id (UTF8); optional binary child_unique_id (UTF8); optional binary ingested_at (UTF8);",
      Vector(Map("parent_unique_id" -> "model.analytics.remote", "child_unique_id" -> "model.analytics.use", "ingested_at" -> future)))
    val use = add("models/use.sql", "select r.customer_id from {{ ref('remote') }} r")
    assertTrue(target(use, "remote").symbol.metadata)
    assertTrue(target(use, "customer_id").symbol.metadata)
    val w = DbtWorkspace.get(getProject)
    val metadataEdge = DbtLineage.edges(w).find(_.reference.label == "Information Schema dependency").get
    assertTrue(metadataEdge.to.metadata)
    assertEquals(w.root(use), w.owner(metadataEdge.to))
    assertEquals(1, DbtLineage.edges(w, Some(com.intellij.psi.search.GlobalSearchScope.fileScope(getProject, edgeFile))).size)
  }

  def testParquetMetadataTargetsAndReverseSearch(): Unit = {
    root()
    parquet("models", modelSchema, Vector(model("remote"), model("disabled", Map("enabled" -> "false"))))
    val usage = add("models/use.sql", "{{ ref('remote') }} {{ ref('disabled') }}")
    val declaration = target(usage, "remote")
    assertTrue(declaration.symbol.metadata)
    assertEquals("info-schema", declaration.symbol.attributes("artifact"))
    assertEquals("false", declaration.symbol.attributes("stale"))
    assertFalse(declaration.getContainingFile.getVirtualFile.isWritable)
    assertTrue(declaration.getContainingFile.getName.endsWith("parquet.metadata.json"))
    assertTrue(declaration.getTextRange.substring(declaration.getContainingFile.getText).contains("remote"))
    assertEquals(1, ReferencesSearch.search(declaration).findAll().size())
    assertTrue(references(usage, "disabled").forall(_.candidates.isEmpty))
    val local = add("models/remote.sql", "select 1")
    assertEquals(local, target(usage, "remote").getContainingFile)
  }

  def testMetadataVersionsSourcesAndSchemaDefinitions(): Unit = {
    root()
    parquet("models", modelSchema, Vector(model("orders", Map("unique_id" -> "model.analytics.orders.v1", "version" -> "1", "latest_version" -> "1"))))
    parquet("sources", "optional binary unique_id (UTF8); optional binary name (UTF8); optional binary source_name (UTF8); optional binary package_name (UTF8);",
      Vector(Map("unique_id" -> "source.analytics.raw.events", "name" -> "events", "source_name" -> "raw", "package_name" -> "analytics")))
    val usage = add("models/use.sql", "{{ ref('orders', v=1) }} {{ source('raw', 'events') }}")
    assertEquals(Some("1"), target(usage, "orders").symbol.version)
    assertEquals("source", target(usage, "raw").symbol.kind)
    assertEquals("source_table", target(usage, "events").symbol.kind)
    val check = add("checks/check.sql", "select name from {{ info_schema('models') }}")
    assertEquals("information_schema_view", target(check, "models").symbol.kind)
    assertEquals("information_schema_column", target(check, "name").symbol.kind)
  }

  def testColumnsAndEdgesMapToSourceWhileKeepingSourceColumns(): Unit = {
    root()
    val local = add("models/local.sql", "select 1")
    add("models/schema.yml", "models:\n- name: local\n  columns:\n  - name: declared\n")
    parquet("models", modelSchema, Vector(model("remote")))
    parquet("node_columns", "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
      Vector(Map("node_unique_id" -> "model.analytics.remote", "column_name" -> "id", "ingested_at" -> future),
        Map("node_unique_id" -> "model.analytics.local", "column_name" -> "declared", "ingested_at" -> future)))
    parquet("edges", "optional binary parent_unique_id (UTF8); optional binary child_unique_id (UTF8); optional binary ingested_at (UTF8);",
      Vector(Map("parent_unique_id" -> "model.analytics.remote", "child_unique_id" -> "model.analytics.local", "ingested_at" -> future)))
    val usage = add("models/use.sql", "select r.id from {{ ref('remote') }} r")
    assertEquals("column", target(usage, "id").symbol.kind)
    val workspace = DbtWorkspace.get(getProject)
    assertEquals(1, workspace.symbols.count(s => s.kind == "column" && s.owner == "local" && s.name == "declared"))
    assertFalse(workspace.symbols.find(s => s.kind == "column" && s.name == "declared").get.metadata)
    val edge = DbtLineage.edges(workspace).find(e => e.from.file == local && e.to.name == "remote").get
    assertTrue(edge.reference.getElement.getName.endsWith("parquet.metadata.json"))
    assertTrue(edge.reference.getRangeInElement.substring(edge.reference.getElement.getText).contains("model.analytics.remote"))
  }

  def testStaleMissingTimestampAndUnsavedEditsDoNotSupplyColumnsOrEdges(): Unit = {
    root()
    parquet("models", modelSchema, Vector(model("remote", Map("ingested_at" -> "2000-01-01T00:00:00Z"))))
    parquet("node_columns", "optional binary node_unique_id (UTF8); optional binary column_name (UTF8); optional binary ingested_at (UTF8);",
      Vector(Map("node_unique_id" -> "model.analytics.remote", "column_name" -> "id", "ingested_at" -> "2000-01-01T00:00:00Z")))
    val usage = add("models/use.sql", "select r.id from {{ ref('remote') }} r")
    assertEquals("true", target(usage, "remote").symbol.attributes("stale"))
    assertTrue(references(usage, "id").forall(_.candidates.isEmpty))
    parquet("models", modelSchema, Vector(model("remote")))
    assertEquals("false", target(usage, "remote").symbol.attributes("stale"))
    val doc = PsiDocumentManager.getInstance(getProject).getDocument(usage)
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit = {
      doc.insertString(0, "-- unsaved\n")
      PsiDocumentManager.getInstance(getProject).commitDocument(doc)
    }})
    assertEquals("true", target(usage, "remote").symbol.attributes("stale"))
  }

  def testExplicitSelectionVersionAndMalformedFiles(): Unit = {
    root("engine: v2\n")
    parquet("models", modelSchema, Vector(model("remote")))
    val usage = add("models/use.sql", "{{ ref('remote') }}")
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
    val settings = myFixture.findFileInTempDir(".dbt-navigation.yml")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit =
      settings.setBinaryContent("engine: v2\ninfo-schema-dir: target/info_schema/v2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) })
    parquet("models", modelSchema, Vector(model("remote")), directory = "target/info_schema/v2")
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit =
      settings.setBinaryContent("engine: v2\ninfo-schema-dir: target/info_schema/v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) })
    val artifact = myFixture.findFileInTempDir("target/info_schema/v1/dbt.models.parquet")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit = artifact.setBinaryContent(Array[Byte](1, 2, 3)) })
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
    parquet("models", "optional binary unrelated (UTF8);", Vector(Map("unrelated" -> "remote")))
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
    parquet("models", modelSchema, Vector(model("remote", Map("unique_id" -> "seed.analytics.remote"))))
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
    parquet("models", "optional int32 unique_id; optional binary name (UTF8);", Vector(Map("unique_id" -> "1", "name" -> "remote")))
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
  }

  def testCompressionAndTimestampLogicalTypes(): Unit = {
    root()
    val schema = "optional binary unique_id (UTF8); optional binary name (UTF8); optional int64 ingested_at (TIMESTAMP(MICROS,true));"
    val micros = java.time.Instant.parse(future).getEpochSecond * 1000000L
    for (codec <- Vector(CompressionCodecName.UNCOMPRESSED, CompressionCodecName.GZIP, CompressionCodecName.ZSTD)) {
      parquet("models", schema, Vector(Map("unique_id" -> "model.analytics.remote", "name" -> "remote", "ingested_at" -> micros.toString)), codec)
      val usage = add(s"models/use_${codec.toString.toLowerCase}.sql", "{{ ref('remote') }}")
      assertEquals(codec.toString, "false", target(usage, "remote").symbol.attributes("stale"))
    }
  }

  def testCoreProjectsAndOversizedTablesStayUnresolved(): Unit = {
    root("engine: core\ninfo-schema-dir: target/info_schema/v1\n")
    parquet("models", modelSchema, Vector(model("remote")))
    val usage = add("models/use.sql", "{{ ref('remote') }}")
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
    val settings = myFixture.findFileInTempDir(".dbt-navigation.yml")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit =
      settings.setBinaryContent("engine: v2\ninfo-schema-dir: target/info_schema/v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) })
    parquet("models", modelSchema, Vector.fill(100001)(model("remote")))
    assertTrue(references(usage, "remote").forall(_.candidates.isEmpty))
  }
}

object DbtParquetNavigationTest {
  val descriptor = new com.intellij.testFramework.LightProjectDescriptor()
}
