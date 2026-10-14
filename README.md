# intellij-dbt

[![Build](https://img.shields.io/github/actions/workflow/status/vbmacher/intellij-dbt/build.yml?branch=main&style=flat-square)](https://github.com/vbmacher/intellij-dbt/actions?query=branch%3Amain) [![License](https://img.shields.io/github/license/vbmacher/intellij-dbt?style=flat-square)](LICENSE)

An IntelliJ IDEA plugin for [dbt](https://www.getdbt.com/) declaration navigation and Find Usages.

Works in **IntelliJ IDEA Community Edition**, including SQL files opened as plain text.
The bundled YAML plugin is required. SQL navigation does not require Ultimate or
Database Tools. Python model navigation optionally uses the free Python
Community Edition plugin.

## Getting started

Open a directory containing `dbt_project.yml` in IntelliJ IDEA. Use **Ctrl+Click**
or **Go to Declaration** on a referenced name to open the file or YAML entry where it
is defined. Use **Find Usages** on a definition to see where it is referenced.

For example, in `ref('orders')`, click **orders** to open `models/orders.sql`.
If several definitions match, the IDE offers a chooser. Names that depend on runtime
values may have no navigation target.

See the [user guide](docs/README.md) for installation, navigation, settings and troubleshooting.

## Features

- **IDE actions:** open definitions with Go to Declaration or Ctrl+Click, find
  references with Find Usages, open YAML properties with **dbt Properties**, and
  browse **Uses** / **Used by** with **dbt Dependencies**. Resource icons identify
  target types; choosers let you select between matching definitions.
- **dbt resources:** follow references to models, seeds, snapshots, sources and
  documentation blocks, including model versions, installed packages and public
  cross-project dependencies. Open UDF bodies, overloads and argument declarations
  in SQL, Python and JavaScript.
- **Jinja and configuration:** jump to definitions of macros, parameters, local
  names, variables, environment keys and statement results. Follow configuration
  keys to settings, builtin names to documentation or local source, and macro
  dispatch to adapter-specific implementations.
- **Tests:** open generic-test definitions and parameters, follow relationship
  targets and columns, and navigate from unit tests to models, inputs, overrides
  and CSV/SQL fixtures.
- **Semantic Layer:** follow references to semantic models, entities, dimensions,
  measures, metrics and aliases; resolve saved-query metrics, export owners,
  time-spine columns, exposure dependencies and groups.
- **SQL and Python:** jump from SQL CTEs, aliases and columns to their definitions
  in SQL, YAML or local metadata. Follow Python model `dbt.ref`, `dbt.source` and
  `dbt.config` calls to resources and settings.
- **Selections:** open resources matched by named selectors, wildcards, graph
  operators, set operations and configured run selections; follow selector
  inheritance and state metadata references.
- **Local metadata and dbt v2:** navigate to resources and columns without local
  source using manifest, catalog, state-manifest JSON or Information Schema Parquet.
  Follow check references to views, columns and resources. Metadata targets open
  read-only with stale indicators; local source takes precedence.
- **Project settings:** select the dbt engine/version and resource paths, keep
  projects scoped separately, and exclude generated files and `.dbtignore` matches.
  Navigation updates after edits or file moves and works in SQL, Jinja, YAML and
  optional Python.

See the [navigation guide](docs/navigation.md),
[configuration reference](docs/configuration.md) and [icon legend](docs/icons.md)
for details and version requirements. The default is dbt Core 1.10.

This plugin provides source navigation and Find Usages. It does not run dbt or
query a warehouse, and does not provide SQL highlighting, completion, data previews
or Rename refactoring.

## Development

To set up the development environment:

1. `git clone https://github.com/vbmacher/intellij-dbt.git`
2. Configure JDK 25
3. Open the project in IntelliJ IDEA as an sbt project

Development uses [sbt](https://www.scala-sbt.org/) and IntelliJ IDEA Community 2026.2.

```bash
sbt compile        # downloads IntelliJ SDK (first run) and compiles the plugin
sbt test           # runs tests
sbt packageArtifactZip  # builds the plugin distribution zip
sbt runPluginVerifier  # checks binary compatibility and reports API warnings
sbt runIDE         # runs IntelliJ IDEA with the plugin installed
```

The default suite runs without a Python plugin. To also test Python navigation, install the
free Python Community Edition plugin in the development SDK, then run:

```bash
sbt testPython
```

To use another SDK containing the Python plugin:

```bash
sbt 'set pythonTestSdk := file("/path/to/sdk")' testPython
```

Plugin Verifier reports are written to `target/verifier/reports`. The task treats
API warnings as failures. Native Ctrl-hover currently uses IntelliJ's experimental
symbol API and its internal `PsiSymbolService` adapter; review those warnings
separately from the binary compatibility verdict.

To verify another installed IDE, pass its directory:

```bash
sbt 'set pluginVerifierOptions := pluginVerifierOptions.value.copy(overrideIDEs = Seq("/path/to/idea"))' runPluginVerifier
```

Check that the output includes a completed verification and a report for each IDE.

### Architecture

Sources are organized by feature under `com.github.vbmacher.intellij.dbt`:

| Package | Responsibility |
|---|---|
| `syntax` | YAML, Jinja and Python source access shared by every feature |
| `project` | dbt projects, declarations and configuration (the domain), and the declaration source port |
| `navigation` | reference resolution and the reference provider port |
| `navigation.jinja`, `.yaml`, `.sql`, `.python` | language adapters providing references |
| `lineage` | dependencies, node selection and the dependency source port |
| `metadata` | artifact adapters providing declarations and dependencies |
| `ide` | IntelliJ extensions and actions |

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
