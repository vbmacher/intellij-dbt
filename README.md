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

## Example project

These files form one example: a model uses another model, a source, a macro and a
variable, while its YAML properties link to a documentation block.

### Define the resources

**`dbt_project.yml`** — project name and variable:

```yaml
name: navigation_demo
version: '1.0'
config-version: 2
profile: demo
vars:
  start_date: '2026-01-01'
```

**`models/orders.sql`** — the model to navigate to:

```sql
select 1 as order_id
```

**`models/schema.yml`** — model properties and source declarations:

```yaml
models:
  - name: orders
    description: "{{ doc('orders_description') }}"
    columns:
      - name: order_id
sources:
  - name: raw
    tables:
      - name: events
        columns:
          - name: event_id
```

**`macros/math.sql`** — a macro and its parameter:

```jinja
{% macro double(value) %}
  {{ value * 2 }}
{% endmacro %}
```

**`docs/catalog.md`** — the documentation used by the model description:

```jinja
{% docs orders_description %}
Order identifiers.
{% enddocs %}
```

### Follow references from a model

**`models/events.sql`** — uses the resources above:

```sql
select
  {{ double(value=2) }} as doubled,
  '{{ var("start_date") }}' as start_date
from {{ ref('orders') }}
join {{ source('raw', 'events') }} e on true
```

Try **Ctrl+Click** on these names:

- **orders** in `ref('orders')` opens `models/orders.sql`.
- **raw** in `source('raw', 'events')` opens the source's `name: raw` in `schema.yml`.
- **events** in the same call opens the table's `name: events` in `schema.yml`.
- **double** opens the macro in `macros/math.sql`; **value** in `value=2` opens its parameter.
- **start_date** opens the variable in `dbt_project.yml`.
- **orders_description** in the YAML `doc()` call opens the docs block in `docs/catalog.md`.

### Find where a resource is used

Open `models/orders.sql` and invoke **Find Usages**. The IDE lists references to
that model and shows a preview of each selected usage.

<img src="docs/screenshots/model-usages.png" alt="Find Usages results for orders, with a YAML model reference selected" width="640">

*The screenshot includes additional resources, so it shows
more usages than the small example above.*

The same action works on macro, variable, source and documentation definitions.
For example, invoke **Find Usages** on `orders_description` in `docs/catalog.md`
to find YAML descriptions that use it:

<img src="docs/screenshots/doc-usages.png" alt="Find Usages for orders_description, showing YAML descriptions that call doc()" width="640">

### Open properties and dependencies

Right-click inside `models/orders.sql` and choose:

- **dbt Properties** to open the model's YAML properties. If several files describe
  the model, choose one from the popup.
- **dbt Dependencies** to see what the model depends on and what depends on it.

<img src="docs/screenshots/model-properties.png" alt="dbt Properties popup offering YAML files that describe the orders model" width="560">

<img src="docs/screenshots/model-dependencies.png" alt="dbt Dependencies popup showing resource-type icons and indented file-and-line references" width="480">

**Uses** lists resources that `orders` depends on; **Used by** lists resources that
depend on `orders`. Each resource has an icon for its type, shared with Go to
Declaration, Find Usages and dbt Properties. Click its name to open its definition.
The indented entries below it open the references: `events.sql:4`, for example,
opens line 4 where `events` calls `ref('orders')`. Multiple references to the same
resource are grouped beneath its name.

See the [icon legend](docs/icons.md) for each icon's meaning.

*Screenshots show an expanded version of this example project in IntelliJ IDEA Community Edition.*

## More navigation examples

The [navigation guide](docs/navigation.md) and [configuration reference](docs/configuration.md) cover:

- Versioned models and installed packages, such as `ref('orders', v=1)`.
- Variable overrides, Jinja scope, configuration and macro dispatch.
- Generic tests, unit-test fixtures and Python models.
- SQL CTEs and columns, Semantic Layer names, UDFs and selectors.
- Engine/version settings and explicitly selected local JSON or Parquet metadata.

The plugin defaults to dbt Core 1.10 navigation. To select a different engine/version,
create **`.dbt-navigation.yml`** at the dbt project root, for example:

```yaml
engine: core
dbt-version: '1.12'
```

Navigation updates after edits and file moves. Generated output, `.git`, `.idea`,
`logs` and `.dbtignore` matches are excluded. See the guide for project scope and
other [navigation limits](docs/troubleshooting.md).

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
sbt runIDE         # runs IntelliJ IDEA with the plugin installed
```

The default suite runs without a Python plugin. To also test Python navigation, install the
free Python Community Edition plugin in the development SDK, then run:

```bash
python3 scripts/test-python.py
```

The script accepts `--sdk /path/to/sdk`.

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
