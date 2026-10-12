val intellijVersion = "262.9437.185"
val intellijRepository = "https://cache-redirector.jetbrains.com/intellij-repository/releases"

lazy val root = (project in file("."))
  .enablePlugins(SbtIdeaPlugin)
  .settings(
    name := "intellij-dbt",
    version := "2026.2",
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
      "junit" % "junit" % "4.13.2" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "org.opentest4j" % "opentest4j" % "1.3.0" % Test,
      ("com.jetbrains.intellij.platform" % "test-framework" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-core" % intellijVersion % Test).notTransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-common" % intellijVersion % Test).notTransitive()
    ),
    Test / parallelExecution := false,
    Compile / javacOptions ++= "--release" :: "21" :: Nil,
    fork := true,
    packageMethod := PackagingMethod.Standalone(),
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
