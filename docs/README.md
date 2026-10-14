# User guide

Navigate dbt definitions, usages, YAML properties and dependencies in IntelliJ IDEA
Community Edition, including SQL files opened as plain text.

## Installation

Requires **IntelliJ IDEA 2026.2** (build `262.8665`–`262.*`) and the bundled YAML
plugin. Python navigation also requires the free Python Community Edition plugin.
Enable the bundled **TextMate Bundles** plugin for [dbt syntax highlighting](highlighting.md).

To install a locally built distribution:

1. Build the ZIP with JDK 25 and `sbt packageArtifactZip` from the repository root.
2. Open **Settings → Plugins**, select the gear menu, then **Install Plugin from Disk**.
3. Choose `target/intellij-dbt-1.1.0.zip` and restart the IDE if prompted.

## First navigation

Open a directory containing `dbt_project.yml`. Use **Ctrl+Click** or
**Go to Declaration** on `orders` in `ref('orders')` to open its definition;
**Find Usages** lists its references.

Right-click in a resource file for **dbt Properties** (YAML definitions) or
**dbt Dependencies** (**Uses** / **Used by**, with reference locations).
Click a [gutter marker](navigation.md#gutter-markers) for related definitions and references.

The default is dbt Core 1.10. Use `.dbt-navigation.yml` to select another version
or local metadata; most navigation needs no settings.

## References

- [Features](../README.md#features)
- [Navigation](navigation.md): supported references and examples
- [Lineage, documentation and Runner](workspace.md): controls, previews and SQL clipboard conversion
- [Configuration](configuration.md): project scope, versions, overrides and metadata
- [Icon legend](icons.md): resource icons in the editor and Project window
- [Troubleshooting and limits](troubleshooting.md)

Install dbt, its adapters and packages using your environment tools. Use the **dbt**
tool window to run commands and generate docs; navigation and documentation browsing
remain available without CLI execution.
