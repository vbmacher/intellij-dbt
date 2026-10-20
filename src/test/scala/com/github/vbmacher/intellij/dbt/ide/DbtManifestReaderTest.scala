package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import org.junit.Assert._

/** features/project-context.md: supported metadata, source authority and owning-project boundaries. */
final class DbtManifestReaderTest extends DbtCodeInsightTestCase {
  private def reader = DbtManifestReader.get(getProject)
  private def manifest(version: Int, nodes: String, project: String = "analytics"): String =
    s"""{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v$version.json","project_name":"$project"},"nodes":$nodes}"""

  def testConsumersShareSupportedSchemasAndResourceIdentityWhileBaselineValidationIsStricter(): Unit = {
    val nodes = """{"model.analytics.orders":{"unique_id":"model.analytics.orders","name":"orders"}}"""
    for (version <- 9 to 12; policy <- Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery, DbtManifestPolicy.Baseline)) {
      val result = reader.parse(manifest(version, nodes), policy).toOption.get
      assertEquals(version, result.schemaVersion)
      assertEquals(Vector("model.analytics.orders"), result.in("nodes").map(_.id))
    }
    Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery, DbtManifestPolicy.Baseline).foreach { policy =>
      assertTrue(reader.parse(manifest(99, nodes), policy).isLeft)
    }
    val incomplete = manifest(12, """{"model.analytics.orders":{"config":[]}}""")
    assertTrue(reader.parse(incomplete, DbtManifestPolicy.Navigation).isRight)
    assertTrue(reader.parse(incomplete, DbtManifestPolicy.Baseline).isLeft)
  }

  def testUnsavedManifestEditsInvalidateCachedRecordsAndDiscoveryChecksTheProject(): Unit = {
    project()
    val file = add("metadata/manifest.json", manifest(12, """{"model.analytics.old":{"name":"old"}}"""))
    val root = DbtWorkspace.get(getProject).roots.head
    val path = file.getVirtualFile.getPath
    assertEquals(Vector("model.analytics.old"), reader.read(root, path, DbtManifestPolicy.Navigation).toOption.get.resources.map(_.id))
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit = {
      document.setText(manifest(12, """{"model.analytics.new":{"name":"new"}}"""))
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    }})
    Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery).foreach { policy =>
      assertEquals(Vector("model.analytics.new"), reader.read(root, path, policy).toOption.get.resources.map(_.id))
    }
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit = {
      document.setText(manifest(12, "{}", "another_project"))
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    }})
    assertTrue(reader.read(root, path, DbtManifestPolicy.TestDiscovery).isLeft)
    assertTrue(reader.read(root, path, DbtManifestPolicy.Navigation).isRight)
  }
}
