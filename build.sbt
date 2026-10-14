val intellijVersion = "262.9437.185"
val intellijRepository = "https://cache-redirector.jetbrains.com/intellij-repository/releases"
val rebuildPluginJar = taskKey[Unit]("Rebuild plugin libraries so incremental packages include current classes and dependencies")

lazy val root = (project in file("."))
  .enablePlugins(SbtIdeaPlugin)
  .settings(
    name := "intellij-dbt",
    version := "1.0.0",
    scalaVersion := "2.13.18",
    ThisBuild / intellijPluginName := "intellij-dbt",
    ThisBuild / intellijBuild := intellijVersion,
    ThisBuild / intellijPlatform := IntelliJPlatform.IdeaCommunity,
    ThisBuild / autoRemoveOldCachedIntelliJSDK := true,
    ThisBuild / autoRemoveOldCachedDownloads := true,
    intellijPlugins ++= Seq(
      "org.jetbrains.plugins.yaml".toPlugin,
      // YAML's dependencies live in separate plugins/modules in IntelliJ 2026.2.
      "com.intellij.modules.json".toPlugin,
      "intellij.libraries.misc.plugin".toPlugin,
      "intellij.structureView.plugin".toPlugin
    ),
    resolvers += "IntelliJ releases" at intellijRepository,
    libraryDependencies ++= Seq(
      "com.hubspot.jinjava" % "jinjava" % "2.8.4",
      "com.github.jsqlparser" % "jsqlparser" % "5.4",
      "org.apache.parquet" % "parquet-hadoop" % "1.18.1",
      // Parquet still links Hadoop API types. Its configuration/codec runtime is only needed to write test fixtures.
      "org.apache.hadoop" % "hadoop-client-api" % "3.5.0",
      "org.apache.hadoop" % "hadoop-client-runtime" % "3.5.0" % Test,
      "junit" % "junit" % "4.13.2" % Test,
      "com.tngtech.archunit" % "archunit" % "1.5.1" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "org.opentest4j" % "opentest4j" % "1.3.0" % Test,
      ("com.jetbrains.intellij.platform" % "test-framework" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-core" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-team-city" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.codeowners" % "codeowners-runtime-resolver" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.codeowners" % "codeowners-monorepo-resolver" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-common" % intellijVersion % Test).notTransitive()
    ),
    Test / parallelExecution := false,
    // Optional Python integration has a separate suite; the default suite proves it is not required.
    Test / testOptions += Tests.Filter(_ != "com.github.vbmacher.intellij.dbt.navigation.python.DbtPythonNavigationTest"),
    Test / javaOptions += "-Didea.load.plugins.id=com.github.vbmacher.intellij-dbt,org.jetbrains.plugins.yaml,com.intellij.modules.json,intellij.libraries.misc.plugin,intellij.structureView.plugin",
    Compile / javacOptions ++= "--release" :: "21" :: Nil,
    fork := true,
    packageMethod := PackagingMethod.Standalone(),
    // Rebuild libraries: updating an existing jar can omit classes, and removed dependencies can remain in the ZIP.
    rebuildPluginJar := IO.delete(packageOutputDir.value / "lib"),
    packageArtifact := (packageArtifact dependsOn rebuildPluginJar).value,
    patchPluginXml := pluginXmlOptions { xml =>
      xml.version = version.value
      xml.sinceBuild = "262.8665"
      xml.untilBuild = "262.*"
    },
    //  https://github.com/JetBrains/sbt-idea-plugin/issues/137
    buildIntellijOptionsIndex := {
      streams.value.log.info("Skipping buildIntellijOptionsIndex.")
    }
  )
