package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.{LightProjectDescriptor, fixtures}
import org.apache.parquet.hadoop.ParquetFileReader
import org.junit.Assert._

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Assert that the shipped reader runs without the original dependency archives on its classpath. */
final class DbtPackagedRuntimeTest extends DbtTestCase {
  override protected def getProjectDescriptor(): LightProjectDescriptor = DbtPackagedRuntimeTest.descriptor

  override protected def createTempDirTestFixture(): fixtures.TempDirTestFixture =
    new fixtures.impl.TempDirTestFixtureImpl()

  def testShippedReaderSupportsEveryCompressionAndPageVersion(): Unit = {
    val expected = Paths.get(sys.props("dbt.packaged.jar")).toRealPath()
    assertEquals(expected, Paths.get(classOf[DbtSymbolElement].getProtectionDomain.getCodeSource.getLocation.toURI).toRealPath())
    val runtime = expected.resolveSibling("dbt-runtime.jar")
    for (clazz <- Vector(classOf[ParquetFileReader], classOf[io.airlift.compress.zstd.ZstdDecompressor]))
      assertEquals(clazz.getName, runtime, Paths.get(clazz.getProtectionDomain.getCodeSource.getLocation.toURI).toRealPath())
    val directory = myFixture.getTempDirFixture.findOrCreateDir("")
    com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule, model => {
      model.getContentEntries.foreach(model.removeContentEntry)
      model.addContentEntry(directory)
    })
    add("dbt_project.yml", "name: analytics\ninfo_schema: {version: 1}\n")
    add(".dbt-navigation.yml", "engine: v2\ninfo-schema-dir: target/info_schema/v1\n")
    val use = add("models/use.sql", "{{ ref('remote_20') }}")
    val parent = myFixture.getTempDirFixture.findOrCreateDir("target/info_schema/v1")
    val fixtures = Files.list(Paths.get(sys.props("dbt.packaged.fixtures")))
    try {
      val files = fixtures.iterator().asScala.toVector.filter(_.toString.endsWith(".parquet"))
      assertEquals("Both page versions and all five codecs must be exercised", 10, files.size)
      files.foreach { path =>
        WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
          override def run(): Unit = {
            val file = Option(parent.findChild("dbt.models.parquet")).getOrElse(parent.createChildData(this, "dbt.models.parquet"))
            file.setBinaryContent(Files.readAllBytes(path))
          }
        })
        val targets = DbtNavigation.at(use, use.getText.indexOf("remote_20") + 1).flatMap(_.candidates)
        assertEquals(path.getFileName.toString, 1, targets.size)
        assertTrue(targets.head.asInstanceOf[DbtSymbolElement].symbol.metadata)
        assertFalse(targets.head.asInstanceOf[DbtSymbolElement].symbol.isStale)
      }
    } finally fixtures.close()
  }
}

object DbtPackagedRuntimeTest {
  val descriptor = new LightProjectDescriptor()
}
