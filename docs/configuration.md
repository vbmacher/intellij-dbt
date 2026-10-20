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

The default is dbt Core 1.10. When `require-dbt-version` is set in `dbt_project.yml`,
the plugin uses the lowest stable version allowed by all its bounds, including the
patch number. Bounds can be a comma-separated string or a list, in any order.
Invalid, contradictory or prerelease bounds leave version-dependent features
disabled. A range with only upper bounds establishes no minimum feature support.
Set `dbt-version` explicitly to override this hint:

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
| Generic-test `arguments` mapping | Core 1.10.5+ |
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

### Environment variables

The plugin loads `.env` from the owning dbt root for dbt commands and SQL formatters.
The owning root is the directory containing that project's `dbt_project.yml`.
Create `.env` beside it with one assignment per line:

```dotenv
# .env
ATHENA_WORK_GROUP=my-workgroup
DBT_SCHEMA=analytics
```

For Athena, reference the variable in the selected `profiles.yml` target:

```yaml
work_group: "{{ env_var('ATHENA_WORK_GROUP', 'primary') }}"
```

dbt reads the variable when the command runs. In this example, `primary` is used
when `ATHENA_WORK_GROUP` is absent.

To choose other files for a project:

1. Open **Settings → dbt Workspace** and select **Owning dbt root**.
2. In **Environment files**, use the browse button to choose a file, or enter
   paths separated by semicolons, such as `.env; .env.local`.
3. Click **Apply**. The next command uses those files.

Paths are absolute or relative to the owning dbt root, including when **Project
root override** points elsewhere. Each owning root saves its own file list.
A nonempty setting replaces the file list in `.dbt-navigation.yml`. Leave it blank
to use `env-files`, or `.env` when `env-files` is absent:

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

Commands inherit the IDE's environment. Files override inherited values in their
listed order; the last assignment wins. The run-config `env` mapping overrides
files and accepts scalar values, including quoted empty strings. Each new command
reads the current file contents. Missing files and invalid assignments are skipped.
Use `env-files: []` with a blank **Environment files** setting to disable file loading.

Environment files support `export KEY=value`, empty values, single or double quotes,
and comments. An unquoted inline comment starts with whitespace followed by `#`;
quoted `#` characters are literal. Double quotes support `\n`, `\r`, `\t`, `\"` and
`\\`. Multiline values, `$VAR` substitution and shell commands are not evaluated.

**Profiles directory** takes precedence over `DBT_PROFILES_DIR` from these files.
The plugin sets `DBT_TARGET_PATH` from **Artifact directory**. A discovered local
virtual environment is prepended to the configured `PATH`.

### dbt variables

For editor navigation, run-config `vars` override project defaults. Shell values,
`dbt --vars` and other IDE run configurations do not supply editor declarations.

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

## Workspace execution defaults

Open **Settings → dbt Workspace** and choose **Owning dbt root**. Executable,
profiles, target, formatter and state settings apply independently to that root.
The settings are saved with the IDE project.

| Setting | Default and effect |
| --- | --- |
| **Executable or external tool** | `dbt`; a project `.venv` or `venv` is discovered first. A command name, executable or wrapper script is accepted. |
| **Arguments before dbt command** | Empty. Arguments precede the dbt command; quote arguments containing spaces. |
| **Environment files** | Blank uses `env-files` from `.dbt-navigation.yml`, or `.env`. Choose a file or enter semicolon-separated paths. See [Variables and environment](#variables-and-environment). |
| **Project root override** | The owning directory containing `dbt_project.yml`. |
| **Profiles directory** | `DBT_PROFILES_DIR`, project-local `profiles.yml`, then `~/.dbt`. Choose the directory containing `profiles.yml`. |
| **Artifact directory** | `target`. Active commands write artifacts here. |
| **Manifest file (optional)** | Blank uses `manifest.json` in the artifact directory. Choose a file, or enter a path relative to the execution project. An explicit `.dbt-navigation.yml` `manifest` entry takes precedence. |
| **Active target (blank uses profile default)** | Blank. Uses the selected profile's default target. |
| **Preview row limit** | 10; accepts 1–1000. |
| **Colored output** | Enabled. |
| **Desktop notifications when dbt commands finish** | Enabled. Requires system notifications to be available and permitted. |

Commands run with `DBT_TARGET_PATH`, `DBT_PROFILES_DIR` and `--target` set from
these settings. The selected manifest and `catalog.json` in the artifact directory load
automatically; [local metadata paths](#local-metadata) can add other files.

Desktop notifications also require **Settings → Appearance & Behavior → Notifications →
Enable system notifications** and appear only while the IDE window is inactive.

### External tools and script wrappers

Use **Executable or external tool** for `dbt`, a script, an interpreter or a tool
that launches dbt. Use **Arguments before dbt command** for its fixed arguments.
Every command, including tests, previews, documentation and selection resolution,
uses the same prefix:

```text
<executable> <arguments before dbt command> <dbt command> <dbt options>
```

| Executable or external tool | Arguments before dbt command | Example invocation |
| --- | --- | --- |
| `dbt` | *(empty)* | `dbt test …` |
| `uv` | `run dbt` | `uv run dbt test …` |
| `poetry` | `run dbt` | `poetry run dbt test …` |
| `zsh` | `scripts/run_dbt.zsh analytics` | `zsh scripts/run_dbt.zsh analytics test …` |
| `bash` | `"scripts/dbt wrapper.sh"` | `bash "scripts/dbt wrapper.sh" test …` |

The selected tool must exist and accept the dbt command and options appended by the
plugin. A wrapper must forward those arguments, for example `dbt "$@"` in a shell
script, preserve dbt’s exit code, and leave its output and artifacts available.
The plugin does not validate third-party tool syntax.

Commands run in **Project root override**, or the owning dbt root when it is blank.
Executable paths containing `/` or `\` resolve relative to that directory.
Prefix arguments are split with quoted strings kept together and passed as individual
arguments. Shell substitutions, pipes, `$VAR` expansion and `~` expansion are not
evaluated; put shell logic in the wrapper script. A local `.venv` or `venv`
containing dbt is added to `PATH`.

`DBT_PROFILES_DIR` and `DBT_TARGET_PATH` supply the configured profile and artifact
directories. The plugin appends target and color options, plus the command’s selection
and other options. Put launcher arguments in the prefix; choose the dbt command and
its scope in a [dbt Run configuration](#dbt-run-configurations).

### dbt Run configurations

In **Run → Edit Configurations…**, add **dbt**. Each configuration stores its own
scope and optional execution overrides; switching editors does not retarget it.

| Field | Effect |
| --- | --- |
| **Owning dbt root** | Source project's dbt_project.yml directory; use its browse button. |
| **Scope** | Absolute path to a model or model directory. Blank selects the whole execution project. |
| **Command** | Run, Build, Test, Compile, Preview, Generate Docs, Source Freshness or Resolve Selection. |
| **Target** | Override the project's active target. Blank inherits the project setting or profile default. |
| **Selection** | dbt selector expression; overrides Scope's model/directory selection. Ctrl+Space offers completions. |
| **Exclude** | Selector terms to remove; also supports Ctrl+Space. |
| **Named selector (overrides Selection)** | A definition in selectors.yml. |
| **Full Refresh (Run / Build)** | Rebuild selected incremental resources using all source data. |
| **Inline SQL (Preview only)** | Saved SELECT query to preview in the selected source model's context. Blank previews the complete model. |
| **Executable override** | Executable or wrapper for this configuration. Blank inherits the project's executable and prefix arguments. |
| **Prefix arguments** | Fixed arguments for the overridden executable, before the dbt command. |
| **Profiles directory override** | Directory containing profiles.yml. Blank inherits the project setting. |

Generate Docs is project-wide. Source Freshness requires the whole project.
Configuration overrides do not modify project settings. Other defaults come from
[workspace execution settings](#workspace-execution-defaults).

Configurations created from test declarations select exact test identities.
Failed-test reruns within a session keep the original command context. Saved
configurations read current project defaults for fields without overrides.

See [execution](execution.md) for selection review, baseline comparison, query
results and CTE inspection.

## Lineage settings

These **Settings → dbt Workspace** options apply to the IDE project's lineage view:

| Setting | Default and choices |
| --- | --- |
| **Upstream levels to show** | Number of dependency levels before the focused resource. 1 shows direct inputs; 2 also shows their inputs. Default 5; accepts 1–20. |
| **Downstream levels to show** | Number of dependent levels after the focused resource. 1 shows resources that use it directly; 2 also shows resources that use those. Default 5; accepts 1–20. |
| **Layout direction** | `LR` (left to right). Also `TB` (top to bottom), `RL` (right to left), `BT` (bottom to top). |

For graph controls, see [Workspace](workspace.md). For resource documentation, see [Editing](editor.md#quick-documentation).

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

For comparison results, selections and dependency review, see [Execution](execution.md#selection-and-state).

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
| **Metabase API key (stored in IDE Password Safe)** | Shows the saved key as masked text. **Apply** saves the field value outside project files; clearing the field and clicking **Apply** removes the saved key. |

The API key needs read access to questions, databases and their table/field metadata,
and any dashboards you want to see. Redirects are refused, so enter the final server
URL. Applying these settings clears the cached Metabase data; see
[Downstream consumers](integrations.md#find-downstream-consumers) to refresh it.

A refresh reads at most 20 databases, 1,000 questions and 100 dashboards; consumers
beyond these limits are missing.

## Airflow DAG discovery

Open **Settings → dbt Integrations → Airflow — local DAG discovery**:

| Setting | Effect |
| --- | --- |
| **DAG projects** | Click **Add folder…** to choose DAG folders or complete Airflow checkouts. Python files are scanned recursively, including folders outside the IDEA project. **Remove** removes a selected folder from discovery. |
| **Airflow URL (optional)** | HTTP or HTTPS web URL without embedded credentials. Supplies browser links to detected DAGs and tasks. |

Discovery runs automatically for the configured DAG projects. With one dbt project
open, it uses that project. With several, it uses the nearest dbt project containing
the DAG file; files outside all dbt projects remain unresolved. Remove all DAG
projects to stop discovery.

Folders can also be workspace-relative paths in saved IDE settings. Discovery skips
`.git`, `.venv`, `venv`, `node_modules` and `__pycache__` directories. Changes to
watched DAG files, including unsaved editor changes, update the detected associations.
Files larger than 1 MB are skipped; unreadable paths are reported in **dbt Airflow Context**.

Discovery reads fixed dbt selections from Python keyword arguments, dictionaries
and dbt shell commands. It supports constants, lists, concatenation and string joins.
Dynamic Python and runtime templates remain unresolved. Python code is never
executed, and discovery does not contact Airflow or a cloud storage service.

See [Airflow context](integrations.md#inspect-airflow-context) for detected pipelines
and [resource documentation](editor.md#quick-documentation) for model context.

## Local MCP server

In **Settings → dbt Integrations**, select **Enable local read-only MCP server
(start explicitly from Tools)** and apply. The option is off by default; the service
still starts only when you click **Start**. Clearing the option stops it. See
[Connect an AI assistant](integrations.md#connect-an-ai-assistant-with-the-local-mcp-server).

The service is an MCP server (Model Context Protocol). It uses the Streamable HTTP
transport at `http://127.0.0.1:<port>/mcp`; the port is random and changes on every
start. Every request must be a POST with the header `Authorization: Bearer <token>`.

| Rule | Result |
|---|---|
| Wrong or missing token | HTTP 401 |
| Request has an `Origin` header (sent by browsers) | HTTP 403 |
| GET or DELETE | HTTP 405. The server opens no event streams and keeps no sessions. |
| Notification or client response | HTTP 202 with no body |
| Request body over 64 KiB | HTTP 413 |
| Call takes over 30 seconds | Canceled |

Responses are always single JSON bodies (`application/json`), never SSE streams.

Supported MCP versions:

| Version | How the client connects | Methods |
|---|---|---|
| `2026-07-28` | No handshake. Each request carries `_meta` with `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities`, and the headers `MCP-Protocol-Version`, `Mcp-Method` and, for `tools/call`, `Mcp-Name`. A header that does not match the body gives HTTP 400 with error `-32020`. | `server/discover`, `tools/list`, `tools/call` |
| `2025-11-25`, `2025-06-18`, `2025-03-26` | `initialize` first. If the client asks for another version, the server answers with `2025-11-25`. Later requests send `MCP-Protocol-Version`; without it, the server assumes `2025-03-26`. Only `2025-03-26` accepts batches (JSON arrays). | `initialize`, `ping`, `tools/list`, `tools/call` |

An unsupported version gives HTTP 400 with error `-32022` and the list of supported
versions. The server offers only tools; it has no resources, prompts or logging.
It has no stdio transport, so a client that only starts local commands needs an
HTTP bridge.

`tools/list` returns the tool names and argument schemas. The `project` argument
accepts a directory returned by `list_projects`, or a unique project name.
Example call with version `2025-06-18`, after `initialize`:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_column_lineage","arguments":{"project":"/work/analytics","resource_id":"model.analytics.report","column":"amount","direction":"upstream"}}}
```

A tool returns its answer in `structuredContent` and as JSON text in `content`. The
answer has a `status`: `ok`, `partial`, `missing`, `ambiguous`, `error`,
`unsupported` or `disposed`. For `error`, `unsupported` and `disposed`, and for
arguments that do not match the schema, the result has `isError: true`. An unknown
tool name gives error `-32602`.

Responses are limited to 2 MB; a larger tool answer is replaced by an error that
asks you to narrow the request. Lists and traces cut short by a cap have status
`partial`. Responses never include profile or environment values, connection
secrets or warehouse rows.
