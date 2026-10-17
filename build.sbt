val intellijVersion = "262.9437.185"
val intellijRepository = "https://cache-redirector.jetbrains.com/intellij-repository/releases"
val rebuildPluginJar = taskKey[Unit]("Rebuild plugin libraries and grammars so incremental packages include only current files")
val testPython = taskKey[Unit]("Run navigation tests with an installed Python Community plugin")
val testPackaged = taskKey[Unit]("Test the minimized plugin without the original runtime dependencies")
val generatePackagedFixtures = taskKey[Unit]("Write Parquet fixtures using the test-only writer runtime")
val pythonTestSdk = settingKey[File]("IntelliJ SDK containing the Python Community plugin")
val pythonTestPlugins = settingKey[Seq[File]]("Installed Python test plugins and their dependencies")
val testPluginIds = "com.github.vbmacher.intellij-dbt,org.jetbrains.plugins.yaml,org.jetbrains.plugins.textmate,com.intellij.modules.json,intellij.libraries.misc.plugin,intellij.structureView.plugin"
val pythonNavigationTest = "com.github.vbmacher.intellij.dbt.navigation.python.DbtPythonNavigationTest"
val packagedRuntimeTest = "com.github.vbmacher.intellij.dbt.metadata.DbtPackagedRuntimeTest"
lazy val PythonTest = config("python") extend Test
lazy val PackagedTest = config("packaged") extend Test
lazy val Minimize = config("minimize").hide

lazy val root = (project in file("."))
  .enablePlugins(SbtIdeaPlugin)
  .configs(PythonTest, PackagedTest, Minimize)
  .settings(inConfig(PythonTest)(Defaults.testTasks))
  .settings(inConfig(PackagedTest)(Defaults.testTasks))
  .settings(
    name := "intellij-dbt",
    version := "1.1.0",
    scalaVersion := "2.13.18",
    // Inherit interface defaults instead of generating calls to deprecated platform methods.
    scalacOptions ++= Seq("-deprecation", "-Xmixin-force-forwarders:junit"),
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
      "intellij.structureView.plugin".toPlugin,
      "com.intellij.modules.jcef".toPlugin
    ),
    resolvers += "IntelliJ releases" at intellijRepository,
    libraryDependencies ++= Seq(
      "com.hubspot.jinjava" % "jinjava" % "2.8.4",
      "com.github.jsqlparser" % "jsqlparser" % "5.4",
      "org.apache.parquet" % "parquet-hadoop" % "1.18.1",
      // Parquet still links Hadoop API types. Its configuration/codec runtime is only needed to write test fixtures.
      "org.apache.hadoop" % "hadoop-client-api" % "3.5.0",
      "org.apache.hadoop" % "hadoop-client-runtime" % "3.5.0" % Test,
      "com.guardsquare" % "proguard-base" % "7.10.0" % Minimize,
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
    // Use the SDK's JetBrains Runtime so IDE tests load its matching native JCEF.
    Test / javaHome := Some(org.jetbrains.sbtidea.download.jbr.JbrInstaller.getJbrHome(intellijBaseDirectory.value.toPath).toFile),
    // Optional Python integration has a separate suite; the default suite proves it is not required.
    Test / testOptions += Tests.Filter(name => name != pythonNavigationTest && name != packagedRuntimeTest),
    Test / javaOptions += "-Didea.load.plugins.id=" + testPluginIds,
    Test / javaOptions := (Test / javaOptions).value.filterNot(_.startsWith("-Dplugin.path=")) :+
      "-Dplugin.path=" + (target.value / "unminimized-plugin").getAbsolutePath,
    Test / fullClasspath := {
      packageArtifact.value
      val packagedLibraries = packageOutputDir.value / "lib"
      (Test / fullClasspath).value.filterNot(entry => IO.relativize(packagedLibraries, entry.data).nonEmpty) ++
        (target.value / "unminimized-plugin" / "lib" ** "*.jar").get.map(Attributed.blank)
    },
    pythonTestSdk := intellijBaseDirectory.value,
    pythonTestPlugins := Seq("custom-plugins/python-ce", "plugins/toml", "plugins/jcef-plugin", "plugins/platform-testRunner-plugin")
      .map(pythonTestSdk.value / _),
    PythonTest / fullClasspath := {
      val plugins = pythonTestPlugins.value
      val missing = plugins.filterNot(_.isDirectory)
      if (missing.nonEmpty) throw new MessageOnlyException(
        s"Missing Python test plugins: ${missing.mkString(", ")}. Install Python Community Edition in the development IDE, or set pythonTestSdk.")
      (PackagedTest / fullClasspath).value ++ plugins.flatMap(p => (p / "lib" ** "*.jar").get).map(Attributed.blank)
    },
    PythonTest / testOptions := Seq(Tests.Filter(_ == pythonNavigationTest)),
    PythonTest / parallelExecution := false,
    PythonTest / javaOptions := {
      val pluginPaths = (packageOutputDir.value +: pythonTestPlugins.value).map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      (Test / javaOptions).value.filterNot(opt => opt.startsWith("-Didea.load.plugins.id=") || opt.startsWith("-Dplugin.path=")) ++ Seq(
        "-Didea.load.plugins.id=" + testPluginIds + ",PythonCore,org.toml.lang,com.intellij.modules.jcef,com.intellij.platform.testRunner",
        "-Dplugin.path=" + pluginPaths)
    },
    PythonTest / forkOptions := (Test / forkOptions).value.withRunJVMOptions((PythonTest / javaOptions).value.toVector),
    testPython := (PythonTest / test).value,
    PackagedTest / fullClasspath := {
      val artifact = packageArtifact.value
      val originals = IO.readLines(target.value / "minimized-plugin" / "runtime-jars.txt").toSet
      val compiledProducts = (Compile / products).value.toSet
      (Test / fullClasspath).value.filterNot { entry =>
        compiledProducts(entry.data) || originals(entry.data.getName) || entry.data.getName.startsWith("hadoop-client-runtime-")
      } ++ (artifact / "lib" ** "*.jar").get.map(Attributed.blank)
    },
    PackagedTest / testOptions := Seq(Tests.Filter(name => !Set(pythonNavigationTest,
      "com.github.vbmacher.intellij.dbt.metadata.DbtParquetNavigationTest",
      "com.github.vbmacher.intellij.dbt.metadata.DbtParquetCodecsTest")(name))),
    PackagedTest / parallelExecution := false,
    PackagedTest / javaOptions := (Test / javaOptions).value.filterNot(_.startsWith("-Dplugin.path=")) ++ Seq(
      "-Dplugin.path=" + packageOutputDir.value.getAbsolutePath,
      "-Ddbt.packaged.jar=" + (packageOutputDir.value / "lib" / "intellij-dbt.jar").getAbsolutePath,
      "-Ddbt.packaged.fixtures=" + (target.value / "packaged-fixtures").getAbsolutePath),
    PackagedTest / forkOptions := (Test / forkOptions).value.withRunJVMOptions((PackagedTest / javaOptions).value.toVector),
    generatePackagedFixtures := {
      (Test / compile).value
      val arguments = Vector("-cp", (Test / fullClasspath).value.files.mkString(java.io.File.pathSeparator),
        "com.github.vbmacher.intellij.dbt.metadata.DbtParquetFixtures", (target.value / "packaged-fixtures").getAbsolutePath)
      val exitCode = Fork.java(ForkOptions().withJavaHome(javaHome.value).withOutputStrategy(Some(StdoutOutput)), arguments)
      if (exitCode != 0) sys.error("Parquet fixture generation failed")
    },
    PackagedTest / test := ((PackagedTest / test) dependsOn generatePackagedFixtures).value,
    testPackaged := (PackagedTest / test).value,
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
    packageArtifact := {
      val artifact = (packageArtifact dependsOn rebuildPluginJar).value
      PluginMinimizer.minimize(artifact, (Compile / dependencyClasspath).value.files,
        update.value.select(configurationFilter(Minimize.name)), javaHome.value.getOrElse(file(sys.props("java.home"))),
        baseDirectory.value / "project" / "plugin.pro", target.value / "minimized-plugin", streams.value.log)
      artifact
    },
    packageArtifactZip := {
      val zip = packageArtifactZip.value
      if (zip.length() > 20000000L) sys.error("Plugin ZIP exceeds the 20 MB release size limit")
      zip
    },
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
