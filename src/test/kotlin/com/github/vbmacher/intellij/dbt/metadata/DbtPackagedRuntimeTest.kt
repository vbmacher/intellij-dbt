package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import org.apache.parquet.hadoop.ParquetFileReader
import org.junit.Assert.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

class DbtPackagedRuntimeTest : DbtTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = descriptor
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    /** The platform loader omits CodeSource locations; its class resource still identifies the supplying JAR. */
    private fun supplyingJar(type: Class<*>): Path {
        val resource = requireNotNull(type.getResource("/${type.name.replace('.', '/')}.class"))
        assertEquals("${type.name} must be supplied by a JAR", "jar", resource.protocol)
        val jar = resource.toExternalForm().removePrefix("jar:").substringBeforeLast("!/")
        return Paths.get(URI.create(jar)).toRealPath()
    }

    fun testShippedReaderSupportsEveryCompressionAndPageVersion() {
        val expected = Paths.get(System.getProperty("dbt.packaged.jar")).toRealPath()
        assertEquals(expected, supplyingJar(DbtSymbolElement::class.java))
        val runtime = expected.resolveSibling("dbt-runtime.jar")
        for (clazz in listOf(
            ParquetFileReader::class.java,
            io.airlift.compress.zstd.ZstdDecompressor::class.java
        )) assertEquals(clazz.name, runtime, supplyingJar(clazz))
        val directory = myFixture.tempDirFixture.findOrCreateDir("")
        com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.forEach(
                model::removeContentEntry
            ); model.addContentEntry(directory)
        }
        add("dbt_project.yml", "name: analytics\ninfo_schema: {version: 1}\n")
        configureSettings { it.engine = "v2"; it.infoSchemaDirectory = "target/info_schema/v1" }
        val use = add("models/use.sql", "{{ ref('remote_20') }}");
        val parent = myFixture.tempDirFixture.findOrCreateDir("target/info_schema/v1")
        Files.list(Paths.get(System.getProperty("dbt.packaged.fixtures"))).use { fixtures ->
            val files = fixtures.filter { it.toString().endsWith(".parquet") }.toList()
            assertEquals("Both page versions and all five codecs must be exercised", 10, files.size)
            files.forEach { path ->
                WriteCommandAction.runWriteCommandAction(
                    project,
                    Runnable {
                        val file = parent.findChild("dbt.models.parquet") ?: parent.createChildData(
                            this,
                            "dbt.models.parquet"
                        ); file.setBinaryContent(Files.readAllBytes(path))
                    })
                val targets = DbtNavigation.at(use, use.text.indexOf("remote_20") + 1).flatMap { it.candidates }
                assertEquals(
                    path.fileName.toString(),
                    1,
                    targets.size
                ); assertTrue((targets.first() as DbtSymbolElement).symbol.metadata); assertFalse((targets.first() as DbtSymbolElement).symbol.isStale)
            }
        }
    }

    companion object {
        val descriptor = LightProjectDescriptor()
    }
}
