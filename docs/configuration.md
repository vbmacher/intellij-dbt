# Configuration

[User guide](getting_started.md) · [Navigation](navigation.md) · [Troubleshooting](troubleshooting.md)

Most navigation needs no settings. Create **`.dbt-navigation.yml`** at the dbt
project root to select a version, profile, overrides or metadata. Paths are relative
to that root. This file configures the plugin and is not passed to dbt.

For environment prerequisites, see [Installation](installation.md). For command and artifact workflows, see [Workspace](workspace.md).

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
| Model versions and model contracts | Core 1.5+ |
| Semantic models and Mesh source dependencies | Core 1.6+ |
| Saved queries | Core 1.7+ |
| Unit tests and `data_tests` | Core 1.8+ |
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

Use `builtin-sources` to open local definitions instead of the selected dbt version's
reference documentation. For documentation popups, see [Quick documentation](editor.md#quick-documentation).

```yaml
# .dbt-navigation.yml
builtin-sources:
  adapter.get_relation: vendor/adapter.py
  run_query: vendor/core_macros.sql
```

SQL mappings name files containing macros; Python mappings name files containing
functions. See [Installation](installation.md) for Python support prerequisites.
Missing definitions fall back to documentation.

## Local metadata

Select metadata for resources or columns without local source. Local source
definitions take precedence. A manifest records dbt resources and dependencies;
a catalog adds database column information.

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

For check-view and column navigation examples, see [Navigation](navigation.md).
Runtime tables, variable values and arbitrary Parquet are not navigation sources.

See dbt's [Information Schema file layout](https://docs.getdbt.com/docs/build/dbt-information-schema)
and [table definitions](https://docs.getdbt.com/reference/info-schema?version=2).

## Workspace and runner settings

Open **Settings → dbt Workspace** and choose **Owning dbt root**. Executable,
profiles, target, formatter and state settings apply independently to that root.
The settings are saved with the IDE project.

| Setting | Default and effect |
| --- | --- |
| **dbt executable** | `dbt`; a project `.venv` or `venv` is discovered first. A command name, executable or wrapper script is accepted. |
| **Executable arguments** | Empty. Arguments precede the dbt command; quote arguments containing spaces. |
| **Project root override** | The owning directory containing `dbt_project.yml`. |
| **Profiles directory** | `DBT_PROFILES_DIR`, project-local `profiles.yml`, then `~/.dbt`. Choose the directory containing `profiles.yml`. |
| **Artifact directory** | `target`. Active commands write artifacts here. |
| **Active target (blank uses profile default)** | Blank. Uses the selected profile's default target. |
| **Preview row limit** | 10; accepts 1–1000. |
| **Colored output** | Enabled. |
| **Desktop notifications when dbt commands finish** | Enabled. Requires system notifications to be available and permitted. |

A blank target uses the profile named in that root's `dbt_project.yml`. The plugin
does not evaluate Jinja in profiles; dbt evaluates it when a command runs. Execution
uses `DBT_TARGET_PATH`, `DBT_PROFILES_DIR` and the active `--target`.

Existing `manifest.json` and `catalog.json` in the artifact directory load automatically.
Explicit [local metadata paths](#local-metadata) can select additional files. Keep
those files consistent with the active target when using both.

Desktop notifications also require **Settings → Appearance & Behavior → Notifications →
Enable system notifications**, an inactive IDE window, and an available desktop
notification service. See [Workspace](workspace.md) for command output and results.

### Script wrappers

Use the browse button beside **dbt executable** to select a script or executable,
or type a command name. For example:

| Setting | Value |
| --- | --- |
| **dbt executable** | `zsh` |
| **Executable arguments** | `scripts/dbt-wrapper.zsh analytics` |

The command prefix is `zsh scripts/dbt-wrapper.zsh analytics`. The selected dbt
command and its options follow it. The same prefix applies to command execution,
documentation generation and previews. The wrapper supplies any additional
project selection or environment setup it needs.

Executable paths containing `/` or `\` resolve relative to the execution root.
Arguments are passed directly to the process; shell expressions are not evaluated.
When a local `.venv` or `venv` contains dbt, its executable directory is added to
`PATH` for the wrapper's dbt and Python calls.

## Lineage settings

These **Settings → dbt Workspace** options apply to the IDE project's lineage view:

| Setting | Default and choices |
| --- | --- |
| **Upstream depth** / **Downstream depth** | 5 each; accepts 1–20. |
| **Layout direction** | `LR` (left to right). Also `TB` (top to bottom), `RL` (right to left), `BT` (bottom to top). |
| **Edge style** | `round-taxi`. Also `bezier`, `taxi`, `segments`, `straight`, `unbundled-bezier`, `haystack`. |
| **Show dbt exposures in lineage** | Enabled. Includes downstream consumers declared as dbt exposures. |

For graph controls and resource documentation, see [Workspace](workspace.md).

## State comparison and deferral

In **Settings → dbt Workspace**, select the owning dbt root and set **Baseline
state directory** to a separate directory containing `manifest.json`. Supported
manifest schema versions are 9–12. This setting supplies comparison and command
state; the `.dbt-navigation.yml` `state-manifest` option instead supplies navigation
references to state metadata.

The baseline must be separate from **Artifact directory**. Overlapping directories,
including aliases through symbolic links, are rejected before a command runs.
Missing, malformed or unsupported manifests also block state-dependent commands.
The baseline reader accepts files up to 50 MB and 50,000 resource entries; larger
baselines cannot be compared locally.

| Option | Effect and prerequisite |
| --- | --- |
| **Defer unselected upstream resources to baseline state** | Adds `--defer --state <directory>`. Requires a valid baseline and configured dbt Core. |
| **Favor baseline relations (dbt Core 1.4+)** | Adds `--favor-state`. Requires deferral and configured Core 1.4+. |

Both options are off by default. Core deferral flags are unavailable for configured
`engine: v2`. Choose the [engine and version](#engine-and-version) that match your
executable; these options do not change the executable itself.

Ordinary deferral can use baseline relations for unselected upstream models, but
an existing relation in the active target can take precedence. Favoring state asks
dbt to use baseline relations instead. Ephemeral models compile locally;
`source()` references use current source configuration. The plugin does not query
the warehouse to determine whether a relation exists.

For comparison results, selections and dependency review, see [Workspace](workspace.md#selection-and-state).

## SQL linting and formatting

A working sqlfmt or SQLFluff executable is required; see [Installation](installation.md)
for environment prerequisites. In **Settings → dbt Workspace**, choose the owning root:

| Setting | Effect |
| --- | --- |
| **SQL formatter** | `sqlfmt` (default) or `sqlfluff`. SQLFluff is required for SQL inspection diagnostics. |
| **Formatter executable (optional)** | Blank disables both formatting and SQL inspection. Enter a command name or choose an executable with the browse button. Relative paths resolve from the execution root. |
| **Format dbt SQL on save** | Off by default. Enables background formatting when saving a supported dbt SQL resource. |

The formatter runs in the configured execution directory with the project's local
environment and receives the raw SQL/Jinja source. SQLFluff also receives the source
filename so nested project configuration applies. The project's SQLFluff
configuration and any templater dependencies must work in that environment.
A run that does not finish within 60 seconds is canceled.

For reformatting, inspection and fixes, see [Editor](editor.md#sql-linting-and-formatting).

## Metabase connection

Open **Settings → dbt Integrations**:

| Setting | Effect |
| --- | --- |
| **Metabase URL (optional)** | Base HTTP or HTTPS URL without embedded credentials. Use HTTPS for a remote server. |
| **Metabase API key (stored in IDE Password Safe; blank keeps saved key)** | Saves the API key outside project files. A blank field retains the saved key. |
| **Remove saved Metabase API key** | Deletes the stored key when settings are applied. |

The API key needs read access to questions, databases and their table/field metadata,
and any dashboards you want to see. Requests use `x-api-key` authentication.
Redirects are refused; enter the final server URL. Applying integration settings
clears the in-memory Metabase cache. Metadata refresh is explicit; see
[Downstream consumers](integrations.md#find-downstream-consumers).

A refresh reads up to 20 databases, 1,000 questions and 100 dashboards. Each response
is limited to 4 MB, each request to 10 seconds, and the refresh to 60 seconds. Larger
or inaccessible collections can leave consumers missing; the dialog shows refresh
errors and retains the previous cache when refresh fails.

## Airflow task mappings

Create `.dbt-airflow.yml` at the dbt project root that owns the mappings:

```yaml
airflow_url: https://airflow.example.com
tasks:
  - dag: daily_finance
    task: build_finance
    project: analytics
    select: tag:finance+
    exclude: resource_type:seed
    upstream_ingestion: [raw_orders_loaded]
  - dag: monthly_finance
    task: reports
    project: analytics
    selector: finance_reports
```

Each entry requires `dag`, `task` and `project`. The project name must uniquely match
a dbt project within the same consuming project and its packages. Use either
`select` with optional `exclude`, or `selector` naming a definition in `selectors.yml`.
Selections support the same local forms as [Workspace selection review](workspace.md#selection-and-state).

A missing selection records a task with unknown resources. Dynamic values such as
`{{ ... }}` or `${...}`, missing projects and unsupported selections stay unresolved.
Declare `upstream_ingestion` to display known ingestion task names; no associations
are inferred from DAG Python. `airflow_url` supplies browser links and must be an
HTTP or HTTPS URL without embedded credentials.

Files larger than 1 MB are skipped; only the first 1,000 task entries are read.
For viewing and opening mappings, see [Airflow context](integrations.md#inspect-airflow-context).

## Local agent tools

In **Settings → dbt Integrations**, select **Enable local read-only agent tools
(start explicitly from Tools)** and apply. The option is off by default. It permits
an explicit start; opening a project does not start the service. Clearing the option
stops it immediately. See [Local agent actions](integrations.md#give-a-local-assistant-read-only-project-access).

A client must support authenticated HTTP JSON-RPC requests or provide a bridge.
The endpoint supports `initialize`, `ping`, `tools/list` and `tools/call`; it is not
a complete MCP Streamable HTTP or SSE endpoint. It listens only on `127.0.0.1` at a
random port under `/tools`. Requests with a browser origin are rejected.

Use the endpoint and temporary token displayed by **dbt Local Agent Tools**. Send
POST requests with `Authorization: Bearer <temporary-token>` and JSON bodies, for example:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list"}
```

Use `tools/list` for the tool names, required arguments and JSON schemas. Project
arguments accept the exact owning directory returned by `list_projects`, or a
project name if unique. Treat resource IDs together with their owning project.
For example:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_column_lineage","arguments":{"project":"/work/analytics","resource_id":"model.analytics.report","column":"amount","direction":"upstream"}}}
```

Requests are limited to 64 KiB, responses to 2 MB, and authenticated calls to 30 seconds.
Excess queued connections can be rejected. Results beyond a tool's resource or path
limit are marked partial. Use the returned status and explanations before relying
on a result. No profile values, environment values, connection secrets or warehouse
rows are returned. Descriptions and SQL expression evidence can contain project-authored
information; review them before sharing results with an external assistant.
