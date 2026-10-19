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

The default is dbt Core 1.10; `require-dbt-version` in `dbt_project.yml`, as a single
string or a list, can provide a hint. To select explicitly:

```yaml
# .dbt-navigation.yml
engine: core
dbt-version: '1.12'
```

A minor version such as `'1.10'` uses that series' supported patch level. Set a full
version such as `'1.10.5'` for patch-specific behavior.

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
narrows dispatch and function targets; without it, navigation can offer several candidates.

### Function compatibility

| UDF language | Supported adapters |
| --- | --- |
| Python | Snowflake, BigQuery, Databricks |
| JavaScript | Snowflake, BigQuery |

Overloads are supported on Snowflake and Postgres.

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

Run-config `vars` and `env` override project defaults. Values come only from the
configured files; shell variables, `dbt --vars` and IDE run configurations are not read.

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

Metadata targets are labeled, including stale ones, and open read-only.

### dbt v2 Information Schema and checks

Requires `engine: v2` and, if set in `dbt_project.yml`, `info_schema.version: 1`.

```yaml
# .dbt-navigation.yml
engine: v2
info-schema-dir: target/info_schema/v1
```

Files include resources (`dbt.models.parquet`), columns (`dbt.node_columns.parquet`)
and dependencies (`dbt.edges.parquet`). Each file is limited to 100,000 rows and
64 MiB, and must be uncompressed or use Snappy, Gzip, Zstd or LZ4 raw compression.
For example, `ref('remote_orders')` opens metadata when local source is absent.

Resources with a missing or old `ingested_at` are marked stale; their columns and
dependencies are ignored, as are those of resources with unsaved source edits.

For check-view and column navigation examples, see [Navigation](navigation.md#information-schema-references).

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

Commands run with `DBT_TARGET_PATH`, `DBT_PROFILES_DIR` and `--target` set from
these settings. `manifest.json` and `catalog.json` in the artifact directory load
automatically; [local metadata paths](#local-metadata) can add other files.

Desktop notifications also require **Settings → Appearance & Behavior → Notifications →
Enable system notifications** and appear only while the IDE window is inactive.

### Script wrappers

Use the browse button beside **dbt executable** to select a script or executable,
or type a command name. For example:

| Setting | Value |
| --- | --- |
| **dbt executable** | `zsh` |
| **Executable arguments** | `scripts/dbt-wrapper.zsh analytics` |

Every command then runs as `zsh scripts/dbt-wrapper.zsh analytics <dbt command and options>`.
Executable paths containing `/` or `\` resolve relative to the execution root.
Arguments are passed as-is; shell expressions are not evaluated. A local `.venv`
or `venv` containing dbt is added to `PATH`.

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

The baseline must not overlap **Artifact directory**, including through symbolic
links. Baselines larger than 50 MB or 50,000 resources cannot be compared.

| Option | Effect and prerequisite |
| --- | --- |
| **Defer unselected upstream resources to baseline state** | Adds `--defer --state <directory>`. Requires a valid baseline and configured dbt Core. |
| **Favor baseline relations (dbt Core 1.4+)** | Adds `--favor-state`. Requires deferral and configured Core 1.4+. |

Both options are off by default and unavailable for `engine: v2`. Set the
[engine and version](#engine-and-version) to match your executable. Without
**Favor baseline relations**, dbt uses a relation that already exists in the active
target instead of the baseline one.

For comparison results, selections and dependency review, see [Workspace](workspace.md#selection-and-state).

## SQL linting and formatting

A working sqlfmt or SQLFluff executable is required; see [Installation](installation.md)
for environment prerequisites. In **Settings → dbt Workspace**, choose the owning root:

| Setting | Effect |
| --- | --- |
| **SQL formatter** | `sqlfmt` (default) or `sqlfluff`. SQLFluff is required for SQL inspection diagnostics. |
| **Formatter executable (optional)** | Blank disables both formatting and SQL inspection. Enter a command name or choose an executable with the browse button. Relative paths resolve from the execution root. |
| **Format dbt SQL on save** | Off by default. Enables background formatting when saving a supported dbt SQL resource. |

The formatter runs in the execution directory with the project's environment, so
the project's SQLFluff configuration and templater must work there. Runs longer
than 60 seconds are canceled.

For reformatting, inspection and fixes, see [Editor](editor.md#sql-linting-and-formatting).

## Metabase connection

Open **Settings → dbt Integrations**:

| Setting | Effect |
| --- | --- |
| **Metabase URL (optional)** | Base HTTP or HTTPS URL without embedded credentials. Use HTTPS for a remote server. |
| **Metabase API key (stored in IDE Password Safe; blank keeps saved key)** | Saves the API key outside project files. A blank field retains the saved key. |
| **Remove saved Metabase API key** | Deletes the stored key when settings are applied. |

The API key needs read access to questions, databases and their table/field metadata,
and any dashboards you want to see. Redirects are refused, so enter the final server
URL. Applying these settings clears the cached Metabase data; see
[Downstream consumers](integrations.md#find-downstream-consumers) to refresh it.

A refresh reads at most 20 databases, 1,000 questions and 100 dashboards; consumers
beyond these limits are missing.

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

Tasks without a selection, or with dynamic values such as `{{ ... }}` or `${...}`,
are shown as unresolved. `upstream_ingestion` lists ingestion task names to display.
`airflow_url` supplies browser links and must be an HTTP or HTTPS URL without
embedded credentials. Files larger than 1 MB are skipped, and only the first 1,000
tasks are read.
For viewing and opening mappings, see [Airflow context](integrations.md#inspect-airflow-context).

## Local agent tools

In **Settings → dbt Integrations**, select **Enable local read-only agent tools
(start explicitly from Tools)** and apply. The option is off by default; the service
still starts only when you click **Start**. Clearing the option stops it. See
[Local agent actions](integrations.md#give-a-local-assistant-read-only-project-access).

The client must send authenticated HTTP JSON-RPC requests, directly or through a
bridge. The endpoint supports `initialize`, `ping`, `tools/list` and `tools/call`;
it is not a full MCP Streamable HTTP or SSE endpoint. It listens on `127.0.0.1` at
a random port under `/tools` and rejects requests from browsers.

Send POST requests to the displayed endpoint with `Authorization: Bearer <token>`
and a JSON body, for example:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list"}
```

`tools/list` returns the tool names and argument schemas. The `project` argument
accepts a directory returned by `list_projects`, or a unique project name.
For example:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_column_lineage","arguments":{"project":"/work/analytics","resource_id":"model.analytics.report","column":"amount","direction":"upstream"}}}
```

Requests are limited to 64 KiB, responses to 2 MB, and calls to 30 seconds; results
cut short by a limit are marked partial. Responses never include profile or
environment values, connection secrets or warehouse rows.
