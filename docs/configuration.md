# Configuration

[User guide](getting_started.md)

The plugin has two places for settings:

- **`.dbt-navigation.yml`** at a dbt project root configures navigation and editor
  features for that project. Paths are relative to that root. The file is read only
  by the plugin and is not passed to dbt.
- **Settings → dbt Workspace** configures execution, formatting, lineage and
  integrations. It is saved with the IDE project.

Most navigation needs no settings.

## .dbt-navigation.yml

Include only the settings you use.

| Setting | Purpose | Details |
| --- | --- | --- |
| `engine` | `core` or `v2` | [Engine and version](#engine-and-version) |
| `dbt-version` | dbt version used for navigation | [Engine and version](#engine-and-version) |
| `profiles-file` | Local profiles YAML file | [Profiles and adapters](#profiles-and-adapters) |
| `target` | Profile output, such as `dev` | [Profiles and adapters](#profiles-and-adapters) |
| `adapter` | Adapter, such as `postgres` | [Profiles and adapters](#profiles-and-adapters) |
| `env-files` | Local environment files | [Environment files](#environment-files) |
| `run-config` | YAML file with variable/environment overrides and run selections | [Run-specific variables and selections](#run-specific-variables-and-selections) |
| `selection-files` | Additional YAML files with `select`, `exclude` or `selector` fields | [Run-specific variables and selections](#run-specific-variables-and-selections) |
| `builtin-sources` | Builtin names mapped to local definitions | [Local builtin definitions](#local-builtin-definitions) |
| `manifest` | Local manifest JSON file | [Local metadata](#local-metadata) |
| `catalog` | Local catalog JSON file | [Local metadata](#local-metadata) |
| `state-manifest` | Local state manifest | [Local metadata](#local-metadata) |
| `info-schema-dir` | dbt v2 Information Schema `v1/` directory | [dbt v2 Information Schema](#dbt-v2-information-schema) |

### Engine and version

The default is dbt Core 1.10. When `require-dbt-version` is set in `dbt_project.yml`,
the plugin uses the lowest stable version allowed by all its bounds, including the
patch number. Bounds can be a comma-separated string or a list, in any order.
Invalid, contradictory or prerelease bounds leave version-dependent features
disabled. A range with only upper bounds establishes no minimum feature support.
Set `dbt-version` to override this:

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

### Profiles and adapters

```yaml
# .dbt-navigation.yml
profiles-file: profiles.yml
target: dev
adapter: postgres
```

The selected profile output defines fields such as `target.schema`. The adapter
narrows dispatch and function targets; without it, navigation can offer several
candidates.

| UDF language | Supported adapters |
| --- | --- |
| Python | Snowflake, BigQuery, Databricks |
| JavaScript | Snowflake, BigQuery |

UDF overloads are supported on Snowflake and Postgres.

### Run-specific variables and selections

```yaml
# .dbt-navigation.yml
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

For editor navigation, run-config `vars` override project defaults. Shell values,
`dbt --vars` and IDE Run configurations do not supply editor declarations. The
run-config `env` mapping overrides [environment files](#environment-files) for dbt
commands and accepts scalar values, including quoted empty strings. Run-config and
selection files support `select`, `exclude` and `selector`
[navigation](navigation.md#selectors).

### Local builtin definitions

Use `builtin-sources` to open local definitions instead of the selected dbt version's
reference documentation:

```yaml
# .dbt-navigation.yml
builtin-sources:
  adapter.get_relation: vendor/adapter.py
  run_query: vendor/core_macros.sql
```

SQL mappings name files containing macros; Python mappings name files containing
functions and need [Python support](installation.md#requirements). Missing
definitions fall back to documentation.

### Local metadata

Select metadata for resources or columns without local source. A manifest records
dbt resources and dependencies; a catalog adds database column information. Local
source definitions take precedence. Metadata targets are labeled, including stale
ones, and open read-only.

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

The manifest and `catalog.json` in the [Artifact directory](#execution-settings)
load automatically. If metadata seems missing, check the paths, the supported
versions above, and whether the metadata was generated for the current project and
target.

### dbt v2 Information Schema

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
See dbt's [Information Schema file layout](https://docs.getdbt.com/docs/build/dbt-information-schema)
and [table definitions](https://docs.getdbt.com/reference/info-schema?version=2).

## Settings → dbt Workspace

The page has two tabs: **General** for execution, formatting and lineage, and
**Integrations** for Metabase, Airflow and the local MCP server. **Apply** saves
both tabs. On **General**, choose **Owning dbt root** first: execution,
environment, formatter and baseline settings apply to that dbt project only.

### Execution settings

| Setting | Default and effect |
| --- | --- |
| **Executable or external tool** | `dbt`; a project `.venv` or `venv` is discovered first. A command name, executable or wrapper script is accepted. See [Executables and wrapper scripts](#executables-and-wrapper-scripts). |
| **Arguments before dbt command** | Empty. Arguments placed before the dbt command; quote arguments containing spaces. |
| **Environment files** | Blank uses `env-files` from `.dbt-navigation.yml`, or `.env`. See [Environment files](#environment-files). |
| **Project root override** | Blank uses the owning directory containing `dbt_project.yml`. Commands run in this directory. |
| **Profiles directory** | Blank uses `DBT_PROFILES_DIR`, a project-local `profiles.yml`, then `~/.dbt`. Choose the directory containing `profiles.yml`. |
| **Artifact directory** | `target`. Commands write artifacts here. |
| **Manifest file (optional)** | Blank uses `manifest.json` in the artifact directory. Choose a file, or enter a path relative to the execution project. A `manifest` entry in `.dbt-navigation.yml` takes precedence. |
| **Active target (blank uses profile default)** | Blank uses the selected profile's default target. |
| **Preview row limit** | 10; accepts 1–1000. |
| **Colored output** | Enabled. |
| **Desktop notifications when dbt commands finish** | Enabled. Notifications appear only while the IDE window is inactive and require **Settings → Appearance & Behavior → Notifications → Enable system notifications**. |

Commands run with `DBT_TARGET_PATH` set from **Artifact directory**,
`DBT_PROFILES_DIR` from **Profiles directory** (this takes precedence over a value
from environment files) and `--target` from **Active target**. A discovered local
virtual environment is prepended to `PATH`. A [dbt Run configuration](workspace.md#run-configurations)
can override the executable, prefix arguments, profiles directory and target.

### Executables and wrapper scripts

Every command, including tests, previews, documentation and selection resolution,
is started as:

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

The tool must accept the dbt command and options appended by the plugin. A wrapper
must forward them (for example `dbt "$@"`), preserve dbt's exit code, and leave its
output and artifacts available. The plugin does not validate third-party tool syntax.

Executable paths containing `/` or `\` resolve relative to the command directory.
Prefix arguments are split with quoted strings kept together. Shell substitutions,
pipes, `$VAR` and `~` are not expanded; put shell logic in the wrapper script.

### Environment files

dbt commands and SQL formatters load `.env` from the owning dbt root (the directory
containing that project's `dbt_project.yml`):

```dotenv
# .env
ATHENA_WORK_GROUP=my-workgroup
DBT_SCHEMA=analytics
```

Reference a variable in `profiles.yml`; dbt reads it when the command runs, here
falling back to `primary`:

```yaml
work_group: "{{ env_var('ATHENA_WORK_GROUP', 'primary') }}"
```

To use other files, enter semicolon-separated paths in **Environment files**, such as
`.env; .env.local`, or choose a file with the browse button, then click **Apply**.
Paths are absolute or relative to the owning dbt root, even when **Project root
override** points elsewhere. A nonempty setting replaces `env-files`:

```yaml
# .dbt-navigation.yml
env-files: [.env, .env.local]
```

Use `env-files: []` with a blank **Environment files** setting to disable loading.

Commands inherit the IDE's environment. Files override inherited values in their
listed order; the last assignment wins, and the run-config `env` mapping overrides
files. Each command reads the current file contents. Missing files and invalid
assignments are skipped.

Supported syntax: `export KEY=value`, empty values, single or double quotes, and
comments. An unquoted inline comment starts with whitespace followed by `#`; quoted
`#` is literal. Double quotes support `\n`, `\r`, `\t`, `\"` and `\\`. Multiline
values, `$VAR` substitution and shell commands are not evaluated.

### Baseline state and deferral

Set **Baseline state directory** to a separate directory containing `manifest.json`
(schema versions 9–12). It supplies [baseline comparison](workspace.md#review-a-selection-and-compare-a-baseline)
and command state. It must not overlap **Artifact directory**, including through
symbolic links. Baselines larger than 50 MB or 50,000 resources cannot be compared.
The `state-manifest` file setting is separate and only supplies navigation targets.

| Option | Effect and prerequisite |
| --- | --- |
| **Defer unselected upstream resources to baseline state** | Adds `--defer --state <directory>`. Requires a valid baseline and dbt Core. |
| **Favor baseline relations (dbt Core 1.4+)** | Adds `--favor-state`. Requires deferral and Core 1.4+. Without it, dbt uses a relation that already exists in the active target instead of the baseline one. |

Both options are off by default and unavailable for `engine: v2`. Set the
[engine and version](#engine-and-version) to match your executable.

### SQL formatter

| Setting | Effect |
| --- | --- |
| **SQL formatter** | `sqlfmt` (default) or `sqlfluff`. SQLFluff is required for SQL inspection. |
| **Formatter executable (optional)** | Blank disables formatting and SQL inspection. Enter a command name or choose an executable. Relative paths resolve from the execution root. |
| **Format dbt SQL on save** | Off by default. Formats supported dbt SQL files in the background when saved. |

The formatter runs in the execution directory with the project's environment, so
the project's SQLFluff configuration and templater must work there. Runs longer
than 60 seconds are canceled. See [SQL linting and formatting](editing.md#sql-linting-and-formatting).

### Lineage settings

These apply to the [Lineage tab](workspace.md#lineage) of the IDE project.

| Setting | Default and choices |
| --- | --- |
| **Upstream levels to show** | Dependency levels before the focused resource; 1 shows direct inputs. Default 5; accepts 1–20. |
| **Downstream levels to show** | Dependent levels after the focused resource; 1 shows direct consumers. Default 5; accepts 1–20. |
| **Layout direction** | `LR` (left to right, default), `TB` (top to bottom), `RL` (right to left) or `BT` (bottom to top). |

### Metabase connection

On the **Integrations** tab:

| Setting | Effect |
| --- | --- |
| **Metabase URL (optional)** | Base HTTP or HTTPS URL without embedded credentials. Use HTTPS for a remote server. Redirects are refused, so enter the final server URL. |
| **Metabase API key (stored in IDE Password Safe)** | Shown masked. **Apply** saves it outside project files; clear the field and click **Apply** to remove it. |

The API key needs read access to questions, databases and their table/field
metadata, and any dashboards you want to see. Applying these settings clears cached
Metabase data. See [Find downstream consumers](integrations.md#find-downstream-consumers).

### Airflow DAG discovery

On the **Integrations** tab, under **Airflow — local DAG discovery**:

| Setting | Effect |
| --- | --- |
| **DAG projects** | Click **Add folder…** to add DAG folders or complete Airflow checkouts, including folders outside the IDE project. Saved settings may also hold workspace-relative paths. **Remove** removes the selected folder. Remove all folders to stop discovery. |
| **Airflow URL (optional)** | HTTP or HTTPS web URL without embedded credentials. Enables browser links to detected DAGs and tasks. |

See [Inspect Airflow context](integrations.md#inspect-airflow-context).

### Local MCP server

On the **Integrations** tab, select **Enable local read-only MCP server (start
explicitly from Tools)** and click **Apply**. The option is off by default; the
server starts only when you click **Start**. Clearing the option stops it. See
[Connect an AI assistant](integrations.md#connect-an-ai-assistant-with-the-local-mcp-server).
