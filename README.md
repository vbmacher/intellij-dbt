# intellij-dbt

[![Build](https://img.shields.io/github/actions/workflow/status/vbmacher/intellij-dbt/build.yml?branch=main&style=flat-square)](https://github.com/vbmacher/intellij-dbt/actions?query=branch%3Amain)

An IntelliJ IDEA plugin for [dbt](https://www.getdbt.com/) declaration navigation and Find Usages.

Works in **IntelliJ IDEA Community Edition**, including SQL files opened as plain text.
The bundled YAML plugin is required. SQL navigation uses JSqlParser and does not require
Ultimate or Database Tools. Python model navigation optionally uses the free Python
Community Edition plugin.

## Features

### Go to Declaration and Find Usages

Use **Ctrl+Click** or **Go to Declaration** on a name to open its declaration. Use
**Find Usages** on a declaration to find calls that resolve to that identity. Ambiguous
names offer multiple candidates; missing or runtime-dependent names remain unresolved.
The index updates after editor changes and file moves and respects the IDE search scope.

Open a directory containing `dbt_project.yml`, or an IDE project containing several dbt
project directories. Each project retains its own names, configured paths and variables.
Installed packages are indexed separately, using their `dbt_project.yml` project names.
Generated output, `.git`, `.idea`, `logs`, and `.dbtignore` matches are excluded from source
navigation. Configured `target-path` and `packages-install-path` are respected.
Ignore negation patterns (`!pattern`) are not expanded.

| Construct | Navigation targets |
| --- | --- |
| `ref()` | Models, seeds, snapshots, package/project names, model versions |
| `source()` | Logical source and table declarations, independently |
| Macro calls | Macro blocks, package namespaces and named parameters |
| `doc()` | Markdown `{% docs ... %}` blocks, including YAML descriptions |
| `var()` / `builtins.var()` | Root/package defaults, applicable `vars.yml`, explicit local overrides |
| Jinja locals | Assignments, loop targets, macro/test/call parameters, `with` bindings, namespace attributes |
| YAML properties | Resource source, `defined_in`, columns, groups, semantic fields and dependencies |
| Generic tests | Custom test blocks, named arguments, relationship columns, builtin documentation |
| Unit tests | Models, `ref`/`source`/`this` inputs, CSV/SQL fixtures, macro and variable override identities |
| Configuration | Root/folder/property/inline declarations, hooks, custom materializations and snapshot strategies |
| Profiles / environment | Local profile, selected output fields, `.env` keys, explicit local overrides |
| Builtins / context | Current resource/project, named statement results, official documentation |
| Semantic Layer | Models, entities, dimensions, legacy measures, metrics, aliases, saved queries and exports |
| `function()` | UDF source, properties, argument declarations and supported overload bodies |
| SQL | CTEs, relation aliases, select aliases and unambiguous declared/projected columns |
| Selectors | Named selectors, resource methods, wildcards, tags and lineage operators |
| dbt v2 checks | Check source, Information Schema views/columns and provable resource-name predicates |

The editor context menu also provides **dbt Properties**, to navigate from resource code
to its YAML properties, and **dbt Dependencies**, to inspect resolved parents, children
and the call sites connecting them. Macro relationships and ambiguous candidates are labeled.

### Models, sources, versions and docs

For example, create `models/orders.sql` and these properties in `models/schema.yml`:

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
        identifier: warehouse_events
        columns:
          - name: order_id
```

In `docs/catalog.md`:

```jinja
{% docs orders_description %}
Orders prepared for reporting.
{% enddocs %}
```

In another model:

```sql
select e.order_id
from {{ ref('orders') }} o
join {{ source('raw', 'events') }} e on o.order_id = e.order_id
```

`orders` opens the model. `raw` and `events` open their separate YAML declarations;
`identifier` does not change the logical name. `e.order_id` opens the declared source column.
The description's `orders_description` opens the Markdown docs block.

Versioned models use `latest_version` and `versions[].defined_in`:

```yaml
models:
  - name: orders
    latest_version: 2
    versions:
      - v: 1
        defined_in: orders_previous
      - v: 2
```

```jinja
{{ ref('orders') }}                  {# orders_v2.sql #}
{{ ref('orders', v=1) }}             {# orders_previous.sql #}
{{ ref('finance', 'orders', v=2) }}  {# visible package/project version #}
```

Cross-project `ref('finance', 'orders')` requires a declared `dependencies.yml` project
and a public target. Installed package calls use the package's actual project namespace.
Dependency declarations link to locally installed sources; version/revision declarations
can link to their `package-lock.yml` entries. The plugin does not download packages.

### Variables, macros and Jinja scope

In `dbt_project.yml`:

```yaml
name: analytics
vars:
  region: us
  start_date: '2026-01-01'
  analytics:
    start_date: '2026-02-01'
  options:
    include_archived: false
```

```jinja
{{ var('start_date') }}   {# project-scoped declaration wins #}
{{ var('region', 'eu') }} {# default is not a declaration #}
{{ var('options')['include_archived'] }}
{% set model_name = 'orders' %}
{{ ref(model_name) }}
{{ var('start_' ~ 'date') }}
```

Dictionary variables navigate to their dictionary key; dictionary members are not separate
dbt variables. Root package-scoped defaults override root global defaults, which override
installed package defaults. Variables inside a package macro use the calling node's project.
`builtins.var/ref/source` bypass a same-named user macro override.

Macro calls and named arguments navigate to separate declarations:

```jinja
{% macro double(value) %}
  {% set result = value * 2 %}
  {{ result }}
{% endmacro %}
```

```sql
select {{ double(value=2) }} as doubled
```

Local names retain lexical scope and shadow dbt context names. Simple wrapper macros can
link a literal caller argument to a delegated `ref`, `source`, `var`, `doc` or `function`.
Literal concatenation, constant assignments and finite branches are bounded static proofs;
recursive or runtime-derived expressions remain unresolved.

Jinja comments and raw blocks are ignored. Active Jinja inside SQL comments remains a usage.
Complete calls in unfinished template blocks can still navigate. Template loader operations
(`import`, `from`, `include`, `extends`) remain unresolved in the standard dbt context.

### Tests, configuration and dispatch

```yaml
models:
  - name: orders
    columns:
      - name: customer_id
        data_tests:
          - relationships:
              arguments:
                to: "ref('customers')"
                field: customer_id
unit_tests:
  - name: orders_example
    model: orders
    given:
      - input: ref('customers')
        format: csv
        fixture: customers_input
    expect:
      format: csv
      fixture: orders_expected
```

Test names navigate to custom `{% test ... %}` blocks or builtin documentation. The
relationship target and field navigate separately. Fixtures link to files under the configured
test paths' `fixtures/` directories. Inline rows are not treated as filenames.

Calls inside YAML descriptions, configuration and hooks use decoded YAML offsets, including
quoted and block scalars. Variable definition values do not render nested Jinja references.
Profiles and `packages.yml` use their restricted `env_var` context.
`dependencies.yml` does not render Jinja. Python models do not render Jinja either.

```jinja
{{ config(materialized='audit', schema='reporting') }}
{{ config.get('materialized') }}
{{ adapter.dispatch('format_amount', 'finance')(amount) }}
{% materialization audit, adapter='default' %}...{% endmaterialization %}
```

Configuration keys link to inherited root/folder/property/inline declarations. Schema, alias
and database keys also link to available `generate_*_name` overrides. Dispatch respects
`macro_namespace`, `search_order`, an explicitly selected adapter/profile, default fallback
and Redshift's Postgres inheritance. Unknown adapters preserve candidates.

### Semantic Layer, UDFs, selectors and SQL

Legacy `semantic_models` entities, dimensions and measures retain semantic-model scope.
For the current model-embedded schema, column entity/dimension annotations and model metrics
are indexed separately. Typed expressions such as `Dimension('customer__country')` navigate
from the entity path and dimension name to their declarations. Metric inputs and derived
expression aliases, saved-query metrics and export owners are linked. Time-spine
`standard_granularity_column` links to the declared model column.

```jinja
{{ Dimension('customer__country') }}
{{ Metric('revenue') }}
{{ function('hash_customer') }}(customer_id)
```

UDF names link to configured function source files and properties. `overloads[].defined_in`
links to additional bodies; properties navigation works for SQL, Python and JavaScript bodies.
SQL bodies can link argument names to YAML argument declarations.

```sql
with recent_orders as (
  select order_id from {{ ref('orders') }}
)
select r.order_id as id from recent_orders r order by id
```

CTE names, `r`, `id` and unambiguous columns navigate using JSqlParser's syntax tree. YAML
columns and simple model projections supply cross-model evidence. Jinja is masked while
preserving original source offsets. Unsupported dialect syntax, dynamic control flow,
unknown schemas and ambiguous joins produce conservative results. `SELECT *` does not invent
column declarations.

```yaml
selectors:
  - name: nightly
    definition: '+orders+'
  - name: finance_sources
    definition:
      method: source
      value: raw.*
```

Selection terms navigate to matching resources; broad patterns offer multiple candidates.
Named selectors, nested unions/intersections/exclusions and graph operators expose their
individual references without executing a selection. Missing state information stays unknown.
YAML selector aliases are not expanded into inherited selections.
`select`, `exclude` and `selector` fields in explicitly configured local selection files
can also navigate.

### Engine, version and optional local settings

The plugin defaults to dbt Core 1.10. A scalar `require-dbt-version` supplies a version hint;
for precise navigation behavior, select the engine/version in **`.dbt-navigation.yml`** at
the dbt project root. This is plugin configuration, not a dbt project file.

| Schema / resource | Supported navigation |
| --- | --- |
| Core 1.10 baseline | SQL/Jinja/YAML resources, legacy Semantic Layer, packages, tests, selectors |
| Core 1.11+ | Adds UDF resources |
| Core 1.12+ | Adds UDF overloads, `vars.yml`, model-embedded semantics |
| Explicit `engine: v2` | Enables checks and Information Schema v1 documentation |
| Manifest JSON | Explicit local schema versions 9–12 |
| Catalog JSON | Explicit local schema version 1; fresh column metadata |
| Information Schema Parquet | Explicit local v2 `v1/` directory; resource metadata, fresh columns and DAG edges |
| Python models | Native PSI from optional free Python Community Edition plugin |

Example settings (omit anything you do not use):

```yaml
engine: core
dbt-version: '1.12'
profiles-file: profiles.yml
target: dev
adapter: postgres
env-files: [.env, .env.local]
run-config: run/dev.yml
selection-files: [run/selection.yml]
manifest: target/manifest.json
catalog: target/catalog.json
state-manifest: state/manifest.json
```

An explicitly selected local `run-config` is a YAML file containing declarations to navigate:

```yaml
vars:
  start_date: '2026-03-01'
env:
  DBT_ENV_SECRET_TOKEN: local-value
target: dev
select: orders
```

Its variable/environment keys override source defaults for navigation; the plugin never runs
this file as a command. Environment navigation exposes key names, not secret values.
Terminal variables, shell scripts, `dbt --vars` invocations and unselected IDE run configurations
are not inferred. For Core 1.12+, `vars.yml` contains a top-level `vars` mapping; conflicting
nonempty mappings in both `vars.yml` and `dbt_project.yml` remain unresolved.

Explicit JSON artifacts supply metadata targets when local source is absent. Targets are
labeled as metadata, including stale metadata; current source always takes precedence.
Incompatible schemas are ignored. A state manifest provides evidence about available
metadata, not proof of runtime warehouse existence or dbt deferral selection.
For existing dbt v2 Information Schema files, select their versioned directory:

```yaml
# .dbt-navigation.yml
engine: v2
info-schema-dir: target/info_schema/v1
```

The plugin uses [Apache Parquet](https://github.com/apache/parquet-java) to read `dbt.models.parquet`, the other supported resource
tables, `dbt.node_columns.parquet`, and `dbt.edges.parquet`. It never generates these files.
For example, `ref('remote_orders')` can navigate to a model's metadata row when its source
is unavailable. Local source and declared columns take precedence. Metadata-only targets
open a read-only JSON representation of the selected Parquet data; they are not code files.
`info_schema('models')` and known view columns can also navigate to that representation.

Resource metadata with a missing/old `ingested_at` is marked stale. Stale columns and edges
are ignored, including when a resource has unsaved edits. Unknown schemas, unsupported
directory versions and unreadable files are ignored. Reads are cancellable and capped at
100,000 rows, 64 MiB on disk and 128 MiB of row-group data per file. Runtime tables,
variable values and arbitrary Parquet datasets are not indexed. Without explicit selection,
Information Schema navigation continues to use the built-in schema documentation.
See dbt's [Information Schema file layout](https://docs.getdbt.com/docs/build/dbt-information-schema)
and [table definitions](https://docs.getdbt.com/reference/info-schema?version=2).

For v2 checks, select `engine: v2` and configure dbt's `info_schema.version: 1`:

```sql
select name from {{ info_schema('models') }} where name = 'orders'
```

The view/columns open schema documentation. The literal `orders` links to the source only
when the parsed predicate proves it names a resource in that known metadata view.

### Python models and limits

With the optional free Python plugin:

```python
def model(dbt, session):
    orders = dbt.ref("orders", v=2)
    events = dbt.source("raw", "events")
    dbt.config(materialized="table")
    return orders
```

The native Python resolver proves the `dbt` parameter or a local alias. Unrelated objects,
shadowed names, interpolated strings and comments are not treated as dbt calls. Python
navigation is unavailable without that plugin; SQL/Jinja/YAML navigation continues to work.

This plugin provides source navigation and reference search. It does not run dbt, render
macros, execute Python/SQL, fetch remote metadata, query a warehouse, provide SQL highlighting,
completion, data previews or Rename refactoring. Arbitrary runtime expressions, all SQL
dialects and complete warehouse schema inference are outside the static resolver's coverage.

## Development

To set up the development environment:

1. `git clone https://github.com/vbmacher/intellij-dbt.git`
2. Configure JDK 25
3. Open the project in IntelliJ IDEA as an sbt project

The plugin is built with [sbt](https://www.scala-sbt.org/) and
[sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin), targeting IntelliJ IDEA Community 2026.2
(build `262.9437.185`).

The plugin uses IntelliJ YAML and JSON PSI, [HubSpot Jinjava](https://github.com/HubSpot/jinjava)
for Jinja tokens, and [JSqlParser](https://github.com/JSQLParser/JSqlParser) for SQL syntax and
source ranges, plus [Apache Parquet](https://github.com/apache/parquet-java) for selected
Information Schema artifacts. It does not implement a SQL grammar. Tests run against the Community SDK.

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

The script accepts `--sdk /path/to/sdk`. It uses native Python PSI and never executes model code.

See [navigation validation](docs/navigation-validation.md) for the automated and manual
Community IDE checks, including the remaining artifact limitation.

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
