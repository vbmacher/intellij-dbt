# Configuration

[User guide](README.md) · [Navigation](navigation.md) · [Troubleshooting](troubleshooting.md)

Most source navigation works without additional settings. Create
**`.dbt-navigation.yml`** at the dbt project root when you need to select a version,
profile, local overrides or metadata. This file configures navigation; it is separate
from `dbt_project.yml` and is not passed to dbt.

Paths in the settings below are relative to that dbt project root.

## Engine and version

The default is dbt Core 1.10. A scalar `require-dbt-version` in `dbt_project.yml`
can supply a version hint. For an explicit selection:

```yaml
engine: core
dbt-version: '1.12'
```

| Feature | Required version or setting |
| --- | --- |
| Model versions | Core 1.5+ |
| Semantic models and Mesh source dependencies | Core 1.6+ |
| Saved queries | Core 1.7+ |
| Unit tests | Core 1.8+ |
| Generic-test `arguments` mapping | Core 1.10.5+ when selecting an explicit patch version |
| UDF resources | Core 1.11+ |
| `vars.yml`, model-embedded semantics, UDF overloads, JavaScript UDFs and string `selector:` terms | Core 1.12+ |
| Checks and Information Schema v1 | `engine: v2` |

A minor-only version such as `'1.10'` represents the supported patch level of that
minor series. Use a full version such as `'1.10.5'` when patch-specific behavior
matters. Unsupported engine or schema versions do not enable newer navigation features.

## Settings reference

Include only the settings you use.

| Setting | Purpose |
| --- | --- |
| `engine` | Select `core` or `v2` |
| `dbt-version` | Select the dbt version used for navigation |
| `profiles-file` | Select a local profiles YAML file |
| `target` | Select a profile output, such as `dev` |
| `adapter` | Select an adapter, such as `postgres` |
| `env-files` | Select local environment files |
| `run-config` | Select a YAML file with variable/environment overrides and run selections |
| `selection-files` | Select additional YAML files with `select`, `exclude` or `selector` fields |
| `manifest` | Select a local manifest JSON file |
| `catalog` | Select a local catalog JSON file |
| `state-manifest` | Select a local state manifest |
| `info-schema-dir` | Select a versioned dbt v2 Information Schema directory |
| `builtin-sources` | Map builtin names to locally available definitions |

## Profiles and adapters

```yaml
# .dbt-navigation.yml
profiles-file: profiles.yml
target: dev
adapter: postgres
```

The profile's selected output supplies targets for fields such as `target.schema`.
Select the adapter to narrow macro dispatch and function targets. If it is unknown,
navigation may offer several candidates.

### Function compatibility

Python UDF targets require Snowflake, BigQuery or Databricks. JavaScript UDF targets
require Snowflake or BigQuery. Overload navigation uses the supported Snowflake/Postgres
combinations. Unknown adapters can preserve source candidates; this does not confirm
that the function is installed or supported in a warehouse.

## Variables and environment

Select local overrides explicitly:

```yaml
# .dbt-navigation.yml
env-files: [.env, .env.local]
run-config: run/dev.yml
selection-files: [run/selection.yml]
```

For example, `run/dev.yml` can contain:

```yaml
vars:
  start_date: '2026-03-01'
env:
  DBT_SCHEMA: analytics
target: dev
select: orders
```

Variable and environment keys in this file take precedence over source defaults for
navigation. Environment navigation shows key names. The plugin does not run this
file as a command or infer values from terminal variables, shell scripts, `dbt --vars`
commands or unselected IDE run configurations.

Selected profiles and `packages.yml` support environment references and explicitly
configured runtime variable keys. Project variable defaults are not treated as CLI
variable overrides.

For Core 1.12+, `vars.yml` uses a top-level `vars` mapping:

```yaml
vars:
  start_date: '2026-01-01'
```

Avoid conflicting nonempty variable mappings in both `vars.yml` and
`dbt_project.yml`; those declarations remain unresolved.

## Local builtin definitions

Without a local definition, builtin names can open the documentation for the selected
dbt version. To navigate into locally available builtin sources:

```yaml
# .dbt-navigation.yml
builtin-sources:
  adapter.get_relation: vendor/adapter.py
  run_query: vendor/core_macros.sql
```

SQL mappings open the named macro block. Python mappings open the matching function
when the Python Community Edition plugin is enabled. If the definition is unavailable,
navigation opens the builtin documentation instead. Find Usages on a local definition
can search the project; external documentation targets have no project usages.

## Project paths and ignored files

Open one or more directories containing `dbt_project.yml`. Project names,
variables and configured paths remain scoped to each dbt project.

Navigation respects the resource paths in `dbt_project.yml`, including
`model-paths`, `seed-paths`, `snapshot-paths`, `macro-paths`, `test-paths`,
`docs-paths` and supported function/check paths.

Installed package sources use their own dbt project names and the configured
`packages-install-path`. Install packages through your normal dbt workflow before
navigating into them.

Generated output, `.git`, `.idea`, `logs`, the configured `target-path` and
`.dbtignore` exclusions are omitted from source navigation. Installed package
sources remain available for dependency navigation.

`.dbtignore` supports ordered exclusions and re-inclusions, anchored paths,
directory patterns and wildcards:

```gitignore
models/experimental/
!models/experimental/keep.sql
```

Generated paths remain excluded. Navigation updates after file edits and moves.

## Local metadata

Select metadata files explicitly when you want targets for resources or columns whose
local source is unavailable. Local source definitions and declared columns take precedence.

| Metadata | Supported selection |
| --- | --- |
| Manifest JSON | Local schema versions 9–12 |
| Catalog JSON | Local schema version 1; fresh column metadata |
| State manifest | Local manifest for state-related resource references |
| Information Schema Parquet | dbt v2 `v1/` directory; resource, column and dependency metadata |

### JSON files

```yaml
# .dbt-navigation.yml
manifest: target/manifest.json
catalog: target/catalog.json
state-manifest: state/manifest.json
```

Targets that come from these files are marked as metadata, including stale metadata.
A state manifest supplies local metadata; it does not confirm warehouse existence or
a runtime deferral decision.

### dbt v2 Information Schema files

```yaml
# .dbt-navigation.yml
engine: v2
info-schema-dir: target/info_schema/v1
```

The directory can contain resource files such as `dbt.models.parquet`, column
metadata in `dbt.node_columns.parquet` and dependencies in `dbt.edges.parquet`.
The plugin reads existing files; generate them through your normal dbt workflow.

For example, `ref('remote_orders')` can open a metadata entry when no local
source exists. Metadata-only targets open a read-only document.
`info_schema('models')` and known view columns can also open their metadata definitions.

Resources with a missing or old `ingested_at` are marked stale. Stale columns and
dependencies are ignored, as are metadata columns/dependencies for resources with
unsaved source edits. Unsupported versions and unreadable files have no metadata targets.
Selected Parquet files are limited to 100,000 rows and 64 MiB per file.

Without `info-schema-dir`, Information Schema navigation uses the builtin
schema documentation. Runtime tables, variable values and arbitrary Parquet datasets
are not navigation sources.

See dbt's [Information Schema file layout](https://docs.getdbt.com/docs/build/dbt-information-schema)
and [table definitions](https://docs.getdbt.com/reference/info-schema?version=2).

## dbt v2 checks

Select `engine: v2` in `.dbt-navigation.yml` and configure this in
`dbt_project.yml`:

```yaml
info_schema:
  version: 1
```

For a check such as:

```sql
select name from {{ info_schema('models') }} where name = 'orders'
```

The view and known columns open their definitions or schema documentation. The
literal `orders` can open the model when the condition clearly identifies a resource
in that view. Unsupported views, schema versions or conditions may have no target.
