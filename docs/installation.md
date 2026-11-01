# Installation and development environment

## Requirements

Use IntelliJ IDEA 2026.2, Community or Ultimate. Database Tools is not required.
Enable these plugins in **Settings → Plugins**:

| Plugin | Needed for |
| --- | --- |
| **YAML** (bundled) | Required; the plugin does not load without it. |
| **Services View** (bundled) | Required; provides the local MCP server controls in Services. |
| **TextMate Bundles** (bundled) | dbt SQL/Jinja, YAML/Jinja and Markdown/Jinja [highlighting](editing.md#syntax-highlighting). No bundle import or SQL file association is needed. Navigation works without it. |
| **Test Runner** (bundled) | Required; provides the dbt [test tree](workspace.md#tests). |
| **Web Browser (JCEF)** (bundled) | Required plugin module; provides the [Lineage](workspace.md#lineage) graph. |
| **Python Community Edition** (free, from Marketplace) | [Python model](navigation.md#python-models) navigation and [local Python builtin definitions](configuration.md#local-builtin-definitions). |

Use a full IntelliJ installation so the bundled components are present.
Lineage also needs the bundled JetBrains Runtime and native JCEF files. If the
native browser cannot start, Lineage explains how to use Call Hierarchy and
Documentation instead; commands and navigation still work. This fallback requires
the JCEF plugin module to remain enabled.

To run dbt commands, install dbt, its adapters and the project's packages in your
chosen environment, then set the executable in
[execution settings](configuration.md#execution-settings). Browsing local source needs
no dbt process or warehouse connection.

To lint or format SQL, make the sqlfmt or SQLFluff executable available in that
environment and [select it](configuration.md#sql-formatter). SQLFluff also needs the
dialect and templater your project uses; its dbt templater needs the dbt adapter and
project dependencies.

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

`packageArtifactZip` creates the installable ZIP in `target/`. Plugin Verifier writes
its report to `target/verifier/reports`. To check another installed IDE:

```bash
sbt 'set pluginVerifierOptions := pluginVerifierOptions.value.copy(overrideIDEs = Seq("/path/to/idea"))' runPluginVerifier
```

### JCEF in the development IDE

The SDK archive used by `sbt runIDE` can omit the native JCEF bundle. If Lineage
cannot open and the IDE log reports
`Startup JCEF test is failed (because CefApp wasn't initialized)`:

1. Find `plugins/jcef-plugin/jcef` in a full IDEA installation for the same operating
   system and architecture.
2. Check that the SDK's `jcefBuild` in `dependencies.txt` matches the bundle's
   `jcef.version`.
3. Copy or symlink that directory into the corresponding SDK location, then restart
   the development IDE.

See JetBrains' [JCEF requirements](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html#plugin-dependency).

### Optional development tools

To download the Python Community plugin matching the development SDK and run the Python integration suite:

```bash
sbt 'set intellijPlugins += "PythonCore:262.9437.185".toPlugin(excludedIds = Set("intellij.python.ast", "intellij.python.parser", "intellij.python.syntax.core"))' updateIntellij testPython
```

To use another SDK with Python Community Edition already available:

```bash
sbt 'set pythonTestSdk := file("/path/to/sdk")' testPython
```

To run the browser suite with its pinned Playwright version and Chromium:

```bash
cd src/test/js
npm ci
npx playwright install chromium
npm test
```

If Playwright cannot find the module or browser, set `DBT_PLAYWRIGHT_MODULE` to the
installed Playwright module path or `DBT_CHROMIUM` to a Chromium executable.

Offline YAML schema sources are in `tools/schema-sources` and `src/main/resources/schemas/dbt-common.json`. After editing them, regenerate the checked-in profiles and check consistency:

```bash
python3 tools/bundle_yaml_schemas.py
python3 tools/bundle_yaml_schemas.py --check
```

Python 3 is needed for this maintenance command; ordinary plugin builds use the checked-in profiles.

## Publish a build

Set the Marketplace token in `IJ_PLUGIN_REPO_TOKEN` or `~/.ij-plugin-repo-token`,
then run `sbt publishPlugin`. To sign a build, set `signPluginOptions` with a
certificate and private key. See [publishing and signing](https://github.com/JetBrains/sbt-idea-plugin#publishing-and-verification).
