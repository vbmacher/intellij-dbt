val intellijVersion = "262.9437.185"

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
