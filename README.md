# intellij-dbt
IntelliJ Idea plugin supporting DBT projects

## Building

The plugin is built with [sbt](https://www.scala-sbt.org/) and
[sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin). JDK 25 is required.

```bash
sbt compile        # downloads IntelliJ SDK (first run) and compiles the plugin
sbt test           # runs tests
sbt packageArtifactZip  # builds the plugin distribution zip
sbt runIDE         # runs IntelliJ IDEA with the plugin installed
```
