# Installation and development environment

## Requirements

Use IntelliJ IDEA 2026.2 with **YAML** enabled. Community and Ultimate are supported; Database Tools is not required.

In **Settings → Plugins**, enable **TextMate Bundles** for dbt SQL/Jinja, YAML/Jinja and Markdown/Jinja highlighting. No manual TextMate bundle import or global SQL file association is needed. Navigation remains available when TextMate highlighting is disabled.

The native dbt test tree requires the bundled **Test Runner** component. Python model navigation additionally needs the free **Python Community Edition** plugin.

The interactive graph needs the IDE's **Web Browser (JCEF)** plugin and its bundled JetBrains Runtime. Use a full IntelliJ installation with these components. The graph cannot open when JCEF is unavailable; command execution and dependency navigation remain available.

To execute dbt commands, install dbt, its adapters and project packages in your chosen environment. Configure their paths through [plugin configuration](configuration.md). Browsing local source does not require a dbt process or warehouse connection.

To use SQL linting or formatting, make the configured SQLFluff or sqlfmt executable available in the chosen environment. SQLFluff also needs the dialect and templater required by your project; its dbt templater needs the corresponding dbt adapter and project dependencies. See [tool configuration](configuration.md#sql-linting-and-formatting).

## Install the plugin

Build the ZIP using the development commands below, or use a supplied plugin ZIP.
Open the gear menu in **Settings → Plugins**, choose **Install Plugin from Disk**,
and select `target/intellij-dbt-[version].zip`. Restart the IDE if prompted.

## Prepare a development environment

Use JDK 25 and sbt. Clone the repository and open it as an sbt project:

```bash
git clone https://github.com/vbmacher/intellij-dbt.git
cd intellij-dbt
sbt compile
```

The first build downloads the IntelliJ SDK. Common commands are:

```bash
sbt test
sbt testPackaged
sbt packageArtifactZip
sbt runPluginVerifier
sbt runIDE
```

`packageArtifactZip` creates the installable ZIP in `target/`. Plugin Verifier writes its report to `target/verifier/reports`; check its completed verdict. To select another installed IDE:

```bash
sbt 'set pluginVerifierOptions := pluginVerifierOptions.value.copy(overrideIDEs = Seq("/path/to/idea"))' runPluginVerifier
```

### JCEF in the development IDE

The SDK archive used by `sbt runIDE` can omit the native JCEF bundle. If Lineage is blank and the IDE log reports `Startup JCEF test is failed (because CefApp wasn't initialized)`, copy or symlink `plugins/jcef-plugin/jcef` from a full IDEA installation into the corresponding SDK directory.

Match the operating system and architecture, and match the SDK's `jcefBuild` value in `dependencies.txt` to the bundle's `jcef.version`. Restart the development IDE after copying it. See JetBrains' [JCEF requirements](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html#plugin-dependency).

### Optional development tools

For Python integration, install Python Community Edition in the development SDK and run `sbt testPython`. To select another SDK:

```bash
sbt 'set pythonTestSdk := file("/path/to/sdk")' testPython
```

For the following command, make Playwright and Chromium available:

```bash
node --test src/test/js/lineage.test.cjs
```

Set `DBT_PLAYWRIGHT_MODULE` to the installed Playwright module path when it cannot be resolved normally. Set `DBT_CHROMIUM` to a Chromium executable when Playwright has no browser available.

Offline YAML schema sources are in `tools/schema-sources` and `src/main/resources/schemas/dbt-common.json`. After editing them, regenerate the checked-in profiles and check consistency:

```bash
python3 tools/bundle_yaml_schemas.py
python3 tools/bundle_yaml_schemas.py --check
```

Python 3 is needed for this maintenance command; ordinary plugin builds use the checked-in profiles.

## Publish a build

Set the Marketplace token in `IJ_PLUGIN_REPO_TOKEN` or `~/.ij-plugin-repo-token`, then run `sbt publishPlugin`. Signing additionally requires `signPluginOptions` with a certificate and private key. See [publishing and signing](https://github.com/JetBrains/sbt-idea-plugin#publishing-and-verification).
