# Configuration

[User guide](README.md) · [Navigation](navigation.md) · [Troubleshooting](troubleshooting.md)

Most source navigation works without additional settings. To select a version,
profile, local overrides or metadata, create **`.dbt-navigation.yml`** at the dbt
project root. This file configures navigation only; it is separate from
`dbt_project.yml` and is not passed to dbt. Paths in it are relative to the dbt
project root.

The plugin only reads existing files. Install packages and generate artifacts through
your normal dbt workflow.

## Settings reference

Include only the settings you use.

| Setting | Purpose | Details |
| --- | --- | --- |
| `engine` | `core` or `v2` | [Engine and version](#engine-and-version) |
| `dbt-version` | dbt version used for navigation | [Engine and version](#engine-and-version) |
| `profiles-file` | Local profiles YAML file | [Profiles and adapters](#profiles-and-adapters) |
| `target` | Profile output, such as `dev` | [Profiles and adapters](#profiles-and-adapters) |
| `adapter` | Adapter, such as `postgres` | [Profiles and adapters](#profiles-and-adapters) |
| `env-files` | Local environment files | [Variables and environment](#variables-and-environment) |
| `run-config` | YAML file with variable/environment overrides and run selections | [Variables and environment](#variables-and-environment) |
| `selection-files` | Additional YAML files with `select`, `exclude` or `selector` fields | [Variables and environment](#variables-and-environment) |
| `builtin-sources` | Builtin names mapped to local definitions | [Local builtin definitions](#local-builtin-definitions) |
| `manifest` | Local manifest JSON file | [Local metadata](#local-metadata) |
| `catalog` | Local catalog JSON file | [Local metadata](#local-metadata) |
| `state-manifest` | Local state manifest | [Local metadata](#local-metadata) |
| `info-schema-dir` | dbt v2 Information Schema `v1/` directory | [Information Schema and checks](#dbt-v2-information-schema-and-checks) |

## Projects and ignored files

Open one or more directories containing `dbt_project.yml`. Project names, variables
and configured paths remain scoped to each dbt project.

Navigation follows the resource paths in `dbt_project.yml`: `model-paths`,
`seed-paths`, `snapshot-paths`, `macro-paths`, `test-paths`, `docs-paths` and
supported function/check paths. Installed packages are read from
`packages-install-path` under their own dbt project names.

Source navigation omits `.git`, `.idea`, `logs`, the configured `target-path`, other
generated output and `.dbtignore` exclusions. Installed package sources remain
available for dependency navigation. `.dbtignore` supports ordered exclusions and
re-inclusions, anchored paths, directory patterns and wildcards; re-inclusions do not
apply to generated paths.

```gitignore
models/experimental/
!models/experimental/keep.sql
```

Navigation updates after file edits and moves.

## Engine and version

The default is dbt Core 1.10. A scalar `require-dbt-version` in `dbt_project.yml`
can supply a version hint. For an explicit selection:

```yaml
# .dbt-navigation.yml
engine: core
dbt-version: '1.12'
```

A minor-only version such as `'1.10'` represents the supported patch level of that
minor series; use a full version such as `'1.10.5'` when patch-specific behavior
matters. Unsupported engine or schema versions do not enable newer features.

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

## Profiles and adapters

```yaml
# .dbt-navigation.yml
profiles-file: profiles.yml
target: dev
adapter: postgres
```

The selected profile output supplies targets for fields such as `target.schema`.
The adapter narrows macro dispatch and function targets; if it is unknown, navigation
may offer several candidates.

### Function compatibility

| UDF language | Supported adapters |
| --- | --- |
| Python | Snowflake, BigQuery, Databricks |
| JavaScript | Snowflake, BigQuery |

Overload navigation uses the supported Snowflake/Postgres combinations. Unknown
adapters can preserve source candidates; this does not confirm that the function is
installed or supported in a warehouse.

## Variables and environment

```yaml
# .dbt-navigation.yml
env-files: [.env, .env.local]
run-config: run/dev.yml
selection-files: [run/selection.yml]
```

```yaml
# run/dev.yml
vars:
  start_date: '2026-03-01'
env:
  DBT_SCHEMA: analytics
target: dev
select: orders
```

Variable and environment keys in the run config take precedence over source
defaults. Environment navigation shows key names only. The plugin does not execute
the run config or infer values from terminal variables, shell scripts, `dbt --vars`
commands or unselected IDE run configurations.

Selected profiles and `packages.yml` support environment references and explicitly
configured runtime variable keys. Project variable defaults are not treated as CLI
variable overrides.

For Core 1.12+, `vars.yml` uses a top-level `vars` mapping. Avoid nonempty variable
mappings in both `vars.yml` and `dbt_project.yml`; conflicting declarations remain
unresolved.

```yaml
# vars.yml
vars:
  start_date: '2026-01-01'
```

## Local builtin definitions

Without a local definition, builtin names open the documentation for the selected
dbt version. To navigate into locally available builtin sources instead:

```yaml
# .dbt-navigation.yml
builtin-sources:
  adapter.get_relation: vendor/adapter.py
  run_query: vendor/core_macros.sql
```

SQL mappings open the named macro block. Python mappings open the matching function
when the Python Community Edition plugin is enabled. Unavailable definitions fall back
to the documentation. Find Usages works on local definitions; documentation targets
have no project usages.

## Local metadata

Select metadata files to get targets for resources or columns without local source.
Local source definitions and declared columns take precedence.

| Metadata | Setting | Supported selection |
| --- | --- | --- |
| Manifest JSON | `manifest` | Schema versions 9–12 |
| Catalog JSON | `catalog` | Schema version 1; fresh column metadata |
| State manifest | `state-manifest` | Manifest for state-related resource references |
| Information Schema Parquet | `info-schema-dir` | dbt v2 `v1/` directory; resource, column and dependency metadata |

```yaml
# .dbt-navigation.yml
manifest: target/manifest.json
catalog: target/catalog.json
state-manifest: state/manifest.json
```

Metadata targets are marked as metadata, including stale metadata, and metadata-only
targets open a read-only document. Metadata does not confirm warehouse existence or a
runtime deferral decision. Unsupported versions and unreadable files supply no targets.

### dbt v2 Information Schema and checks

Both require `engine: v2`. If `dbt_project.yml` sets `info_schema.version`, it must
be `1`.

```yaml
# .dbt-navigation.yml
engine: v2
info-schema-dir: target/info_schema/v1
```

The directory can contain resource files such as `dbt.models.parquet`, column
metadata in `dbt.node_columns.parquet` and dependencies in `dbt.edges.parquet`.
Selected files are limited to 100,000 rows and 64 MiB each. For example,
`ref('remote_orders')` can open a metadata entry when no local source exists.

Resources with a missing or old `ingested_at` are marked stale. Columns and
dependencies of stale resources, or of resources with unsaved source edits, are
ignored.

In a check:

```sql
select name from {{ info_schema('models') }} where name = 'orders'
```

The view and its known columns open their Information Schema metadata, or the builtin
schema documentation when `info-schema-dir` is not selected. The literal `orders`
can open the model when the condition clearly identifies a resource in that view.
Unsupported views, schema versions or conditions may have no target. Runtime tables,
variable values and arbitrary Parquet datasets are not navigation sources.

See dbt's [Information Schema file layout](https://docs.getdbt.com/docs/build/dbt-information-schema)
and [table definitions](https://docs.getdbt.com/reference/info-schema?version=2).
