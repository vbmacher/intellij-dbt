import sbt._

import java.nio.charset.StandardCharsets.UTF_8
import java.util.jar.{JarEntry, JarOutputStream}
import java.util.zip.ZipFile
import scala.collection.JavaConverters._
import scala.collection.mutable

object PluginMinimizer {
  def minimize(artifact: File, compileClasspath: Seq[File], toolClasspath: Seq[File], javaHome: File,
    rules: File, work: File, log: Logger): Unit = {
    IO.createDirectory(work)
    val development = work.getParentFile / "unminimized-plugin"
    IO.delete(development)
    IO.copyDirectory(artifact, development)
    val jars = (artifact / "lib" ** "*.jar").get.sortBy(_.getName)
    val originalBytes = jars.map(_.length()).sum
    val plugin = artifact / "lib" / "intellij-dbt.jar"
    val pluginCopy = work / "plugin.jar"
    IO.copyFile(plugin, pluginCopy)
    val pluginArchive = new ZipFile(plugin)
    val pluginEntries = try pluginArchive.entries().asScala.map(_.getName).toSet finally pluginArchive.close()
    IO.writeLines(work / "runtime-jars.txt", jars.map(_.getName))
    // Page decompression uses Airlift, rather than the native codecs bundled by Parquet.
    val inputs = jars.filterNot(_.getName.startsWith("snappy-java-")).filterNot(_.getName.startsWith("zstd-jni-"))
    val merged = work / "input.jar"
    merge(inputs, merged)
    val libraries = compileClasspath.filter(f => f.isFile && !jars.exists(_.getName == f.getName)).distinct
    // Temurin 25 omits jmods; every supported JDK still supplies its runtime image.
    val moduleDirectory = work / "jdk-modules"
    IO.delete(moduleDirectory)
    val extraction = Fork.java(ForkOptions().withJavaHome(Some(javaHome)).withOutputStrategy(Some(StdoutOutput)),
      Vector("-m", "jdk.jlink/jdk.tools.jimage.Main", "extract", "--dir", moduleDirectory.getAbsolutePath,
        (javaHome / "lib" / "modules").getAbsolutePath))
    if (extraction != 0) sys.error("JDK runtime image extraction failed")
    val modules = IO.listFiles(moduleDirectory).filter(_.isDirectory).sortBy(_.getName)
    if (!(moduleDirectory / "java.base" / "java" / "lang" / "Object.class").isFile)
      sys.error("JDK runtime image is missing java.lang.Object")
    val output = work / "intellij-dbt.jar"
    def path(file: File): String = "'" + file.getAbsolutePath + "'"
    val config = work / "plugin.pro"
    IO.writeLines(config, Seq(s"-injars ${path(merged)}", s"-outjars ${path(output)}") ++
      libraries.map(f => s"-libraryjars ${path(f)}(!META-INF/versions/**)") ++
      modules.map(f => s"-libraryjars ${path(f)}(!module-info.class)") ++
      Seq(s"-include ${path(rules)}", s"-printusage ${path(work / "removed.txt")}"))
    val arguments = Vector("-Xmx3g", "-cp", toolClasspath.mkString(java.io.File.pathSeparator), "proguard.ProGuard", "@" + config.getAbsolutePath)
    val result = Fork.java(ForkOptions().withJavaHome(Some(javaHome)).withOutputStrategy(Some(StdoutOutput)), arguments)
    if (result != 0) sys.error("Plugin dependency minimization failed")
    cleanServices(output, work / "runtime.jar", pluginEntries)
    // Replace libraries only after ProGuard has produced a complete archive.
    IO.delete(artifact / "lib")
    IO.copyFile(pluginCopy, artifact / "lib" / "intellij-dbt.jar")
    IO.copyFile(work / "runtime.jar", artifact / "lib" / "dbt-runtime.jar")
    val finalBytes = pluginCopy.length() + (work / "runtime.jar").length()
    log.info(f"Minimized plugin libraries: ${originalBytes / 1000000.0}%.2f MB -> ${finalBytes / 1000000.0}%.2f MB")
  }

  private def merge(inputs: Seq[File], output: File): Unit = {
    val entries = mutable.LinkedHashMap.empty[String, Array[Byte]]
    inputs.foreach { file =>
      val archive = new ZipFile(file)
      try archive.entries().asScala.filterNot(_.isDirectory).foreach { entry =>
        val name = entry.getName
        val signed = name.startsWith("META-INF/") && name.matches(".*\\.(SF|RSA|DSA)")
        if (!signed && !name.startsWith("META-INF/versions/") && name != "module-info.class" && name != "META-INF/MANIFEST.MF") {
          val stream = archive.getInputStream(entry)
          val bytes = try stream.readAllBytes() finally stream.close()
          val upper = name.toUpperCase(java.util.Locale.ROOT)
          val target = if (upper.contains("LICENSE") || upper.contains("NOTICE"))
            s"META-INF/third-party/${file.getName}/$name" else name
          if (target.startsWith("META-INF/services/") && entries.contains(target))
            entries(target) = entries(target) ++ "\n".getBytes(UTF_8) ++ bytes
          else if (!entries.contains(target)) entries(target) = bytes
        }
      } finally archive.close()
    }
    entries("META-INF/third-party/README.txt") =
      "Dependency archives were merged and shrunk with ProGuard; optimization and obfuscation are disabled.\n".getBytes(UTF_8)
    write(output, entries.toSeq)
  }

  private def cleanServices(input: File, output: File, pluginEntries: Set[String]): Unit = {
    val archive = new ZipFile(input)
    try {
      val names = archive.entries().asScala.map(_.getName).toSet
      val entries = archive.entries().asScala.filterNot(e => e.isDirectory || pluginEntries(e.getName)).map { entry =>
        val stream = archive.getInputStream(entry)
        val bytes = try stream.readAllBytes() finally stream.close()
        val content = if (!entry.getName.startsWith("META-INF/services/")) bytes else
          new String(bytes, UTF_8).split("\n").filter { line =>
            val provider = line.takeWhile(_ != '#').trim
            provider.isEmpty || names(provider.replace('.', '/') + ".class")
          }.mkString("\n").getBytes(UTF_8)
        entry.getName -> content
      }.toVector
      write(output, entries)
    } finally archive.close()
  }

  private def write(output: File, entries: Seq[(String, Array[Byte])]): Unit = {
    val archive = new JarOutputStream(java.nio.file.Files.newOutputStream(output.toPath))
    try entries.foreach { case (name, bytes) =>
      archive.putNextEntry(new JarEntry(name))
      archive.write(bytes)
      archive.closeEntry()
    } finally archive.close()
  }
}
