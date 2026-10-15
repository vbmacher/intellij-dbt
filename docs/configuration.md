# Configuration

[User guide](README.md) · [Navigation](navigation.md) · [Troubleshooting](troubleshooting.md)

Most navigation needs no settings. Create **`.dbt-navigation.yml`** at the dbt
project root to select a version, profile, overrides or metadata. Paths are relative
to that root. This file configures the plugin and is not passed to dbt.

Install packages with your dbt tools. Existing artifacts load locally; the Runner can regenerate them.

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

Each directory containing `dbt_project.yml` has its own names, variables and paths.

Navigation uses the paths in `dbt_project.yml`: `model-paths`,
`seed-paths`, `snapshot-paths`, `macro-paths`, `test-paths`, `docs-paths` and
supported function/check paths. Packages use `packages-install-path` and their own
dbt project names.

Navigation excludes `.git`, `.idea`, `logs`, `target-path`, other generated output
and `.dbtignore` matches, but includes installed package sources. `.dbtignore`
supports ordered exclusions/re-inclusions, anchored paths, directories and wildcards.
Generated paths cannot be re-included.

```gitignore
models/experimental/
!models/experimental/keep.sql
```

Edits and file moves update navigation.

## Engine and version

The default is dbt Core 1.10; scalar `require-dbt-version` in `dbt_project.yml` can
provide a hint. To select explicitly:

```yaml
# .dbt-navigation.yml
engine: core
dbt-version: '1.12'
```

A minor version such as `'1.10'` uses that series' supported patch level. Set a full
version such as `'1.10.5'` for patch-specific behavior. Unsupported engine or schema
versions do not enable newer features.

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

The selected profile output defines fields such as `target.schema`. The adapter
narrows dispatch and function targets; an unknown adapter may yield several candidates.

### Function compatibility

| UDF language | Supported adapters |
| --- | --- |
| Python | Snowflake, BigQuery, Databricks |
| JavaScript | Snowflake, BigQuery |

Overloads use supported Snowflake/Postgres combinations. Unknown adapters may retain
source candidates; navigation does not prove warehouse installation or support.

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

Run-config variable and environment keys override source defaults. Environment
navigation shows names only. The plugin does not execute this file or infer values
from terminal variables, shell scripts, `dbt --vars` or unselected IDE run configs.

Selected profiles and `packages.yml` resolve environment keys and configured runtime
variables. Project defaults do not count as CLI overrides.

Core 1.12+ supports a top-level `vars` mapping in `vars.yml`. Define variables there
or in `dbt_project.yml`; nonempty mappings in both leave conflicts unresolved.

```yaml
# vars.yml
vars:
  start_date: '2026-01-01'
```

## Local builtin definitions

Builtin names open documentation for the selected dbt version. To open local
definitions instead:

```yaml
# .dbt-navigation.yml
builtin-sources:
  adapter.get_relation: vendor/adapter.py
  run_query: vendor/core_macros.sql
```

SQL mappings open macros; Python mappings open functions with the Python Community
Edition plugin enabled. Missing definitions fall back to documentation. Find Usages
works on local definitions, not documentation targets.

## Local metadata

Select metadata for resources or columns without local source. Source definitions
and declared columns take precedence.

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

Metadata targets are labeled, including stale entries; metadata-only targets open
read-only. They do not prove warehouse existence or runtime deferral. Unsupported
versions and unreadable files provide no targets.

### dbt v2 Information Schema and checks

Requires `engine: v2` and, if set in `dbt_project.yml`, `info_schema.version: 1`.

```yaml
# .dbt-navigation.yml
engine: v2
info-schema-dir: target/info_schema/v1
```

Files include resources (`dbt.models.parquet`), columns (`dbt.node_columns.parquet`)
and dependencies (`dbt.edges.parquet`). Each file is limited to 100,000 rows and
64 MiB. For example, `ref('remote_orders')` opens metadata when local source is absent.
Supported pages: uncompressed, Snappy, Gzip, Zstd and LZ4 raw; other formats supply
no targets.

Missing or old `ingested_at` marks resources stale. Columns and dependencies are
ignored for stale resources or those with unsaved source edits.

In a check:

```sql
select name from {{ info_schema('models') }} where name = 'orders'
```

The view and known columns open metadata, or builtin schema documentation without
`info-schema-dir`. The literal `orders` can open the model if the condition identifies
it in that view. Unsupported views, versions or conditions may have no target.
Runtime tables, variable values and arbitrary Parquet are not navigation sources.

See dbt's [Information Schema file layout](https://docs.getdbt.com/docs/build/dbt-information-schema)
and [table definitions](https://docs.getdbt.com/reference/info-schema?version=2).

## Workspace and runner settings

Open **Settings → dbt Workspace** to configure each owning dbt root independently:

| Setting | Default |
| --- | --- |
| Executable | `dbt`; discover `.venv` / `venv` first |
| Executable arguments | Empty; arguments inserted before the dbt command |
| Project root override | Owning directory containing `dbt_project.yml` |
| Profiles directory | `DBT_PROFILES_DIR`, project-local `profiles.yml`, then `~/.dbt` |
| Artifact directory | `target` |
| Active target | Selected profile's default target |
| Preview rows | 10 (1–1000) |
| Colored output / native notifications | Enabled |
| Upstream / downstream graph depth | 5 / 5 (each 1–20) |
| Layout / edges | Left-to-right / `round-taxi` |
| Exposures | Shown |

Graph directions are `LR`, `TB`, `RL`, `BT`; edge options are `bezier`, `taxi`,
`round-taxi`, `segments`, `straight`, `unbundled-bezier`, `haystack`. Settings persist
in the IDE project. The Runner target selector lists outputs of the profile named
in that root's `dbt_project.yml`. A blank target uses the profile default. Jinja in
profiles is not evaluated by the plugin; the CLI evaluates it when executing.

Existing `manifest.json` and `catalog.json` in the artifact directory load automatically.
Explicit `.dbt-navigation.yml` artifact paths still select additional metadata. Keep
the selected manifest and Runner artifact directory consistent when using both.
The Runner passes `DBT_TARGET_PATH` and `DBT_PROFILES_DIR` to the child process and
adds the active `--target` to every command.

### Script wrappers

Set **Executable** to a wrapper script or interpreter and **Executable arguments**
to any arguments it needs before the dbt command. For example:

| Setting | Value |
| --- | --- |
| Executable | `zsh` |
| Executable arguments | `scripts/dbt-wrapper.zsh analytics` |

For **Run**, the resulting command starts with
`zsh scripts/dbt-wrapper.zsh analytics run`. The Runner then adds the model selector,
target and other dbt options. All Runner actions use the same prefix, including
**Generate Docs** and inline **Preview**. Configure each owning dbt root independently;
the wrapper handles any additional project selection or environment setup.

Executable paths containing `/` or `\` resolve relative to the execution root.
Quote executable arguments containing spaces. Arguments are passed directly to the
process; shell expressions are not evaluated. When a local
`.venv` or `venv` contains dbt, its executable directory is added to `PATH` for the
wrapper's dbt and Python calls.
