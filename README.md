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

Ctrl+Click the variable name inside `var(...)`, or use **Go to Declaration**, to open its YAML key.
On the YAML key, use **Find Usages** to list matching SQL calls and navigate to them. The search
respects the scope selected in the IDE and follows the declaration each call actually resolves to.

### Supported call forms

The first argument must be a literal string. Both quote styles, defaults, nested calls, and
multiple calls in one file are supported:

```jinja
{{ var('start_date') }}
{{ var("start_date", '2026-01-01') }}
{{ var('start_date', var('fallback_date', '2026-01-01')) }}
```

Each literal name is looked up independently. A default argument does not create a declaration:
if `fallback_date` is absent from `dbt_project.yml`, it has no navigation target. Missing variables
do not produce an unresolved-variable error from this plugin.

Calls are also recognized inside Jinja statement blocks, including assignments and conditions:

```jinja
{% set date = var('start_date') %}
{% if var('include_archived', false) %}
  select * from archived_events
{% endif %}
```

Multiline calls and whitespace control retain navigation to the variable name:

```jinja
{{-
  var(
    'start_date',
    '2026-01-01'
  )
-}}
```

Navigation updates as files are edited. A complete call inside an unfinished Jinja block, such
as `{{ var('start_date')` while typing, can still resolve.

### Global and project-scoped variables

Given this `dbt_project.yml`:

```yaml
name: analytics
config-version: 2
vars:
  start_date: '2026-01-01'
  region: us
  analytics:
    start_date: '2026-02-01'
```

`var('start_date')` navigates to `vars.analytics.start_date`, because the scope matches the
project's `name`. `var('region')` navigates to the global `vars.region` declaration. Find Usages
on the overridden global `start_date` does not list calls resolving to the scoped declaration.
Variables defined only under another package name do not become available to the root project.

### Dictionary variables and quoted names

Dictionary-valued variables and literal names containing punctuation or quotes are supported:

```yaml
vars:
  options:
    region: us
  'date.start': '2026-01-01'
  "it's_ready": true
```

```jinja
{{ var('options')['region'] }}
{{ var('date.start') }}
{{ var("it's_ready") }}
{{ var('it\'s_ready') }}
```

`options` navigates to the dictionary declaration. Its `region` member is not an independent
dbt variable or a navigation target; `var('region')` needs its own variable declaration.
`date.start` is a literal key name, rather than a path through nested YAML keys.

### Supported files and paths

Open the directory containing `dbt_project.yml` as the project root. Calls are recognized in
`.sql` files under these resource paths, including subdirectories:

| Resources | Configuration key | Default directory |
| --- | --- | --- |
| Models | `model-paths` | `models/` |
| SQL tests | `test-paths` | `tests/` |
| Macros | `macro-paths` | `macros/` |
| Snapshots | `snapshot-paths` | `snapshots/` |
| Analyses | `analysis-paths` | `analyses/` |

For example, these settings in `dbt_project.yml` enable navigation in `transformations/` and
`checks/` instead of the default model and test directories:

```yaml
model-paths: [transformations]
test-paths: [checks]
```

An empty list, such as `model-paths: []`, disables that resource category. Generated SQL under
`target-path` (default `target/`), installed packages under `packages-install-path` (default
`dbt_packages/`), `.git/`, `.idea/`, and `logs/` are excluded. Files inside a nested directory
with its own `dbt_project.yml` are also excluded.

### Ignored text and current limits

Jinja comments and raw blocks do not produce variable references:

```jinja
{# {{ var('start_date') }} #}
{% raw %}{{ var('start_date') }}{% endraw %}
```

Active Jinja inside an SQL comment is recognized, so `-- {{ var('start_date') }}` remains a usage.
Ordinary SQL text such as `select var('start_date')` is ignored. A string containing call-like
text, a method named `var`, or a Jinja filter/test named `var` is not treated as a dbt variable call.

The current feature does not support:

- Dynamic names: `var(variable_name)` or `var('prefix_' ~ suffix)`
- Qualified calls such as `builtins.var('start_date')`
- Navigation for Jinja local variables declared with `{% set %}`
- Variable usages in YAML files, including schema tests or project configuration
- Definitions in `vars.yml`, runtime `--vars`, environment variables, or installed packages
- Multiple dbt projects in one IDE project
- `ref()`, `source()`, or macro-definition navigation
- dbt-aware Rename refactoring, completion, SQL syntax highlighting, compilation, or execution

The plugin links source declarations and usages; it does not evaluate variable values or run dbt.

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
