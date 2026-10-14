val intellijVersion = "262.9437.185"
val intellijRepository = "https://cache-redirector.jetbrains.com/intellij-repository/releases"
val rebuildPluginJar = taskKey[Unit]("Rebuild plugin libraries and grammars so incremental packages include only current files")
val testPython = taskKey[Unit]("Run navigation tests with an installed Python Community plugin")
val pythonTestSdk = settingKey[File]("IntelliJ SDK containing the Python Community plugin")
val pythonTestPlugins = settingKey[Seq[File]]("Installed Python test plugins and their dependencies")
val testPluginIds = "com.github.vbmacher.intellij-dbt,org.jetbrains.plugins.yaml,org.jetbrains.plugins.textmate,com.intellij.modules.json,intellij.libraries.misc.plugin,intellij.structureView.plugin"
lazy val PythonTest = config("python") extend Test

lazy val root = (project in file("."))
  .enablePlugins(SbtIdeaPlugin)
  .configs(PythonTest)
  .settings(inConfig(PythonTest)(Defaults.testTasks))
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
      "org.jetbrains.plugins.textmate".toPlugin,
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
    Test / javaOptions += "-Didea.load.plugins.id=" + testPluginIds,
    pythonTestSdk := intellijBaseDirectory.value,
    pythonTestPlugins := Seq("custom-plugins/python-ce", "plugins/toml", "plugins/jcef-plugin", "plugins/platform-testRunner-plugin")
      .map(pythonTestSdk.value / _),
    PythonTest / fullClasspath := {
      val plugins = pythonTestPlugins.value
      val missing = plugins.filterNot(_.isDirectory)
      if (missing.nonEmpty) throw new MessageOnlyException(
        s"Missing Python test plugins: ${missing.mkString(", ")}. Install Python Community Edition in the development IDE, or set pythonTestSdk.")
      (Test / fullClasspath).value ++ plugins.flatMap(p => (p / "lib" ** "*.jar").get).map(Attributed.blank)
    },
    PythonTest / testOptions := Seq(Tests.Filter(_ == "com.github.vbmacher.intellij.dbt.navigation.python.DbtPythonNavigationTest")),
    PythonTest / parallelExecution := false,
    PythonTest / javaOptions := {
      val pluginPaths = (packageOutputDir.value +: pythonTestPlugins.value).map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      (Test / javaOptions).value.filterNot(opt => opt.startsWith("-Didea.load.plugins.id=") || opt.startsWith("-Dplugin.path=")) ++ Seq(
        "-Didea.load.plugins.id=" + testPluginIds + ",PythonCore,org.toml.lang,com.intellij.modules.jcef,com.intellij.platform.testRunner",
        "-Dplugin.path=" + pluginPaths)
    },
    PythonTest / forkOptions := (Test / forkOptions).value.withRunJVMOptions((PythonTest / javaOptions).value.toVector),
    testPython := (PythonTest / test).value,
    Compile / javacOptions ++= "--release" :: "21" :: Nil,
    fork := true,
    packageMethod := PackagingMethod.Standalone(),
    packageFileMappings ++= {
      val bundle = baseDirectory.value / "src/main/textmate/dbt"
      (bundle ** "*").get.filter(_.isFile).map(f => f -> s"textmate/dbt/${IO.relativize(bundle, f).get}")
    },
    // Rebuild libraries: updating an existing jar can omit classes, and removed dependencies can remain in the ZIP.
    rebuildPluginJar := {
      IO.delete(packageOutputDir.value / "lib")
      IO.delete(packageOutputDir.value / "textmate")
    },
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
