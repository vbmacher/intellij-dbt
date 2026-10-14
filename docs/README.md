# User guide

The dbt plugin provides Go to Declaration, Find Usages, properties navigation and
dependency browsing in IntelliJ IDEA Community Edition. SQL navigation works even
when SQL files are opened as plain text.

## Installation

The current build supports **IntelliJ IDEA 2026.2**, from build `262.8665` through
`262.*`. Earlier IDE versions are not supported. Enable the bundled YAML plugin.
Python model navigation additionally requires the free Python Community Edition
plugin.

To install a locally built distribution:

1. Build the ZIP with JDK 25 and `sbt packageArtifactZip` from the repository root.
2. Open **Settings → Plugins**, select the gear menu, then **Install Plugin from Disk**.
3. Choose `target/intellij-dbt-1.0.0.zip` and restart the IDE if prompted.

## First navigation

Open a directory containing `dbt_project.yml`. In a model, use **Ctrl+Click** or
**Go to Declaration** on `orders` in `ref('orders')` to open its definition. Use
**Find Usages** on that definition to find references to it.

Right-click in a resource file and choose **dbt Properties** to open its YAML
properties, or **dbt Dependencies** to browse **Uses** and **Used by** and their
reference locations.

Most navigation needs no settings. The default is dbt Core 1.10; select a different
version or local metadata in `.dbt-navigation.yml` when needed.

## References

- [Example project](../README.md#example-project)
- [Navigation](navigation.md): supported references and examples
- [Configuration](configuration.md): project scope, versions, overrides and metadata
- [Icon legend](icons.md): resource icons in the editor and Project window
- [Troubleshooting and limits](troubleshooting.md)

Use your usual dbt tools to install packages, run projects and generate artifacts.
The plugin reads local source and selected metadata; it does not execute dbt or query
a warehouse.
