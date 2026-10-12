# intellij-dbt

[![Build](https://img.shields.io/github/actions/workflow/status/vbmacher/intellij-dbt/build.yml?branch=main&style=flat-square)](https://github.com/vbmacher/intellij-dbt/actions?query=branch%3Amain)

An IntelliJ IDEA plugin for [dbt](https://www.getdbt.com/) projects, focused on navigation
between variable definitions and their usages.

Works in **IntelliJ IDEA Community Edition**. Variable navigation and Find Usages require
the YAML plugin, with no Ultimate subscription or Database Tools and SQL plugin required.
In Community, `.sql` files can remain plain text; dbt variable navigation still works.

## Features

### Navigation from variable usage to definition (and back)

- Supports a single dbt project with `dbt_project.yml` at the repository root
- Resolves literal `var('name')` calls in SQL models, tests, snapshots, macros, and analyses
- Navigates to the YAML declaration using Ctrl+Click or Go to Declaration
- Finds SQL usages from the YAML declaration using Find Usages
- Supports global variables and variables scoped to the root project name; project-scoped variables take precedence
- Respects configured resource paths and excludes generated output, installed packages, and nested dbt projects
- Updates navigation as declarations and usages change in the editor, without running dbt

For example, in `dbt_project.yml`:

```yaml
name: analytics
config-version: 2
vars:
  start_date: '2026-01-01'
```

In `models/events.sql`, navigate from `start_date` to its declaration:

```sql
select * from events where event_date >= '{{ var("start_date") }}'
```

The first version supports definitions in `dbt_project.yml` and usages in `.sql` files.
Dynamic names, Jinja local variables, runtime `--vars`, `vars.yml`, and multi-project repositories
are outside this scope. Missing declarations remain unresolved, including variables supplied at
runtime or handled by a default argument.

## Development

To set up the development environment:

1. `git clone https://github.com/vbmacher/intellij-dbt.git`
2. Configure JDK 25
3. Open the project in IntelliJ IDEA as an sbt project

The plugin is built with [sbt](https://www.scala-sbt.org/) and
[sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin), targeting IntelliJ IDEA Community 2026.2
(build `262.9437.185`).

The plugin uses the bundled YAML plugin and [HubSpot Jinjava](https://github.com/HubSpot/jinjava)
for Jinja tokenization. SQL syntax highlighting is outside this first feature; the plugin
does not implement a SQL parser. Tests run against the Community SDK.

```bash
sbt compile        # downloads IntelliJ SDK (first run) and compiles the plugin
sbt test           # runs tests
sbt packageArtifactZip  # builds the plugin distribution zip
sbt runIDE         # runs IntelliJ IDEA with the plugin installed
```

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
