# intellij-dbt

[![Build](https://img.shields.io/github/actions/workflow/status/vbmacher/intellij-dbt/build.yml?branch=main&style=flat-square)](https://github.com/vbmacher/intellij-dbt/actions?query=branch%3Amain) [![License](https://img.shields.io/github/license/vbmacher/intellij-dbt?style=flat-square)](LICENSE)

An IntelliJ IDEA plugin for [dbt](https://www.getdbt.com/) navigation, documentation, lineage and local dbt execution.

The plugin does not require Ultimate edition or Database Tools. Python model navigation optionally uses the free Python
Community Edition plugin. Enable the bundled **TextMate Bundles** plugin for [dbt syntax highlighting](docs/highlighting.md).

## Getting started

Open a directory containing `dbt_project.yml` in IntelliJ IDEA. Use **Ctrl+Click**
or **Go to Declaration** on a referenced name to open the file or YAML entry where it
is defined. Use **Find Usages** on a definition to see where it is referenced.

For example, in `ref('orders')`, click **orders** to open `models/orders.sql`.
If several definitions match, the IDE offers a chooser. Names that depend on runtime
values may have no navigation target.

See the [user guide](docs/README.md) for installation, navigation, settings and troubleshooting.

## Features

- **Navigation and Find Usages** for models, seeds, snapshots, sources, docs blocks,
  macros, variables, tests, UDFs and Semantic Layer objects, including package and
  cross-project references.
- **Jinja, SQL and Python:** jump from macros, parameters, CTEs, aliases, columns and
  Python `dbt.ref` / `dbt.source` / `dbt.config` calls to their definitions.
- **Selectors:** open the resources matched by selectors, wildcards and graph operators.
- **Local metadata:** navigate without source using manifest, catalog or state
  artifacts; local source takes precedence.
- **IDE tools:** **dbt Properties** and **dbt Dependencies** actions, gutter markers,
  resource icons and syntax highlighting for dbt templates.
- **Project settings:** choose the dbt engine/version and resource paths, and exclude
  generated or `.dbtignore` files.

See the [navigation guide](docs/navigation.md),
[configuration reference](docs/configuration.md) and [icon legend](docs/icons.md)
for details and version requirements.

Open the bottom **dbt** tool window for interactive lineage, a documentation sidebar,
model/directory run selection and model previews. Native hover, missing-reference warnings and
Copy for Target DB / Paste as dbt Refs are described in the [workspace guide](docs/workspace.md).
Autocompletion and Rename refactoring are outside this plugin.

## Development

To set up the development environment:

1. `git clone https://github.com/vbmacher/intellij-dbt.git`
2. Configure JDK 25
3. Open the project in IntelliJ IDEA as an sbt project

```bash
sbt compile             # downloads IntelliJ SDK (first run) and compiles the plugin
sbt test                # runs tests
sbt packageArtifactZip  # builds the plugin distribution zip
sbt testPackaged        # tests the minimized runtime, including all supported Parquet codecs
sbt runPluginVerifier   # checks binary compatibility and reports API warnings
sbt runIDE              # runs IntelliJ IDEA with the plugin installed
```

For Lineage in the development IDE (in production IDE it works out of the box), install the SDK's matching native JCEF bundle;
see [JCEF setup](docs/workspace.md#jcef-setup).

The default suite runs without a Python plugin. To also test Python navigation, install the
free Python Community Edition plugin in the development SDK, then run:

```bash
sbt testPython
```

To use another SDK containing the Python plugin:

```bash
sbt 'set pythonTestSdk := file("/path/to/sdk")' testPython
```

Plugin Verifier reports are written to `target/verifier/reports`. CI verifies the
packaged plugin against the development SDK and fails on Internal API usage,
compatibility problems and other API warnings. Public experimental Symbol APIs
used for native navigation, documentation and Find Usages are reported without
failing the build.

To verify another installed IDE, pass its directory:

```bash
sbt 'set pluginVerifierOptions := pluginVerifierOptions.value.copy(overrideIDEs = Seq("/path/to/idea"))' runPluginVerifier
```

Check that the output includes a completed verification and a report for each IDE.

The lineage renderer's browser interaction tests use an installed Playwright/Chromium:

```bash
node --test src/test/js/lineage.test.cjs
# Set DBT_CHROMIUM to an existing Chromium executable if Playwright has no browser installed.
```

Bundled graph-library versions and licenses are in
[`src/main/resources/lineage/vendor`](src/main/resources/lineage/vendor/NOTICE.md).


### Architecture

Sources are organized by feature under `com.github.vbmacher.intellij.dbt`:

| Package                                        | Responsibility                                                                             |
|------------------------------------------------|--------------------------------------------------------------------------------------------|
| `syntax`                                       | YAML, Jinja and Python source access shared by every feature                               |
| `project`                                      | dbt projects, declarations and configuration (the domain), and the declaration source port |
| `navigation`                                   | reference resolution and the reference provider port                                       |
| `navigation.jinja`, `.yaml`, `.sql`, `.python` | language adapters providing references                                                     |
| `lineage`                                      | dependencies, node selection and the dependency source port                                |
| `metadata`                                     | artifact adapters providing declarations and dependencies                                  |
| `ide`                                          | IntelliJ extensions and actions                                                            |

Adapters are registered for the ports in `plugin.xml`, in priority order. `DbtArchitectureTest` enforces the
allowed dependencies between packages and the confinement of third-party parsers.

## Contributing

Contributions are welcome. Pick an existing [issue](https://github.com/vbmacher/intellij-dbt/issues)
or open a new issue to discuss an idea.

## Publishing

To publish to [JetBrains Marketplace](https://plugins.jetbrains.com/), set a publish token
in `IJ_PLUGIN_REPO_TOKEN` or in the file `~/.ij-plugin-repo-token`, then run:

```bash
sbt publishPlugin
```

Signing requires enabling `signPluginOptions` and configuring a certificate and private key.
See [sbt-idea-plugin publishing and signing](https://github.com/JetBrains/sbt-idea-plugin#publishing-and-verification)
for details.

## License

This project is released under the [Apache License, Version 2.0](LICENSE).
