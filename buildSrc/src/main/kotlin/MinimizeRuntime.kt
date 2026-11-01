import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Builds the dependency JAR used by the installable plugin and packaged-runtime tests.
 *
 * The plugin JAR participates in reachability analysis but remains a separate, unchanged archive.
 * ProGuard removes unused dependency classes without optimizing or obfuscating them; the compile
 * classpath and selected JDK supply library definitions rather than packaged inputs. Native Parquet
 * codecs are excluded because the reader uses Airlift decompression.
 *
 * Third-party notices retain their archive identities. Service descriptors keep only providers
 * present after shrinking, so discovery cannot reference classes removed from the distribution.
 */
abstract class MinimizeRuntime @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    abstract val pluginJar: RegularFileProperty
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection
    @get:Classpath
    abstract val libraryClasspath: ConfigurableFileCollection
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection
    @get:InputFile
    abstract val rules: RegularFileProperty
    @get:Input
    abstract val javaHome: Property<String>
    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun minimize() {
        val work = temporaryDir
        val plugin = pluginJar.get().asFile
        val jars = (listOf(plugin) + runtimeClasspath.files.filter { it.extension == "jar" }).sortedBy { it.name }
        val pluginEntries = ZipFile(plugin).use { archive -> archive.entries().asSequence().map { it.name }.toSet() }
        val inputs = jars.filterNot { it.name.startsWith("snappy-java-") || it.name.startsWith("zstd-jni-") }
        val merged = work.resolve("input.jar")
        merge(inputs, merged)
        val libraries =
            libraryClasspath.files.filter { it.isFile && jars.none { jar -> jar.name == it.name } }.distinct()
        val modules = work.resolve("jdk-modules")
        modules.deleteRecursively()
        exec.javaexec {
            executable = File(javaHome.get(), "bin/java").absolutePath
            mainModule.set("jdk.jlink")
            mainClass.set("jdk.tools.jimage.Main")
            args("extract", "--dir", modules.absolutePath, File(javaHome.get(), "lib/modules").absolutePath)
        }.assertNormalExitValue()
        check(modules.resolve("java.base/java/lang/Object.class").isFile) { "JDK runtime image is missing java.lang.Object" }
        val shrunk = work.resolve("shrunk.jar")
        fun path(file: File) = "'${file.absolutePath}'"
        val config = work.resolve("plugin.pro")
        config.writeText(
            (listOf("-injars ${path(merged)}", "-outjars ${path(shrunk)}") +
                libraries.map { "-libraryjars ${path(it)}(!META-INF/versions/**)" } +
                modules.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
                    .map { "-libraryjars ${path(it)}(!module-info.class)" } +
                listOf(
                    "-include ${path(rules.get().asFile)}",
                    "-printusage ${path(work.resolve("removed.txt"))}"
                )).joinToString("\n"))
        exec.javaexec {
            executable = File(javaHome.get(), "bin/java").absolutePath
            maxHeapSize = "3g"
            classpath(toolClasspath)
            mainClass.set("proguard.ProGuard")
            args("@${config.absolutePath}")
        }.assertNormalExitValue()
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        ZipFile(shrunk).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            val entries =
                archive.entries().asSequence().filterNot { it.isDirectory || it.name in pluginEntries }.map { entry ->
                    val bytes = archive.getInputStream(entry).use { it.readAllBytes() }
                    val content = if (!entry.name.startsWith("META-INF/services/")) bytes else
                        bytes.toString(UTF_8).split('\n').filter { line ->
                            val provider = line.substringBefore('#').trim()
                            provider.isEmpty() || "${provider.replace('.', '/')}.class" in names
                        }.joinToString("\n").toByteArray(UTF_8)
                    entry.name to content
                }.toList()
            write(output, entries)
        }
    }

    /** Merge service providers, preserve notices, and discard signatures invalidated by repackaging. */
    private fun merge(inputs: List<File>, output: File) {
        val entries = linkedMapOf<String, ByteArray>()
        inputs.forEach { file ->
            ZipFile(file).use { archive ->
                archive.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val name = entry.name
                    val signed = name.startsWith("META-INF/") && name.matches(Regex(".*\\.(SF|RSA|DSA)"))
                    if (!signed && !name.startsWith("META-INF/versions/") && name != "module-info.class" && name != "META-INF/MANIFEST.MF") {
                        val bytes = archive.getInputStream(entry).use { it.readAllBytes() }
                        val upper = name.uppercase(Locale.ROOT)
                        val target =
                            if ("LICENSE" in upper || "NOTICE" in upper) "META-INF/third-party/${file.name}/$name" else name
                        if (target.startsWith("META-INF/services/") && target in entries)
                            entries[target] = entries.getValue(target) + "\n".toByteArray(UTF_8) + bytes
                        else entries.putIfAbsent(target, bytes)
                    }
                }
            }
        }
        entries["META-INF/third-party/README.txt"] =
            "Dependency archives were merged and shrunk with ProGuard; optimization and obfuscation are disabled.\n".toByteArray(
                UTF_8
            )
        write(output, entries.toList())
    }

    private fun write(output: File, entries: List<Pair<String, ByteArray>>) {
        JarOutputStream(output.outputStream()).use { archive ->
            entries.forEach { (name, bytes) ->
                archive.putNextEntry(JarEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
    }
}
