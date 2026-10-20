# Run and inspect dbt

[User guide](getting_started.md) · [Configuration](configuration.md#dbt-run-configurations) · [Lineage](workspace.md)

## Commands

Create a **dbt** configuration in **Run → Edit Configurations…**, or use the context
Run action on a model or model directory. Model and directory configurations keep
that scope when you switch editors. Installed-package models execute through their
consuming project. See [Run configuration settings](configuration.md#dbt-run-configurations)
for the fields and execution overrides.

| Command | Result |
| --- | --- |
| **Run** | Materialize models in the chosen scope. |
| **Build** | Build selected resources and run their tests using the native test console. |
| **Test** | Run selected data and unit tests using the native test console. |
| **Compile** | Produce compiled SQL for the chosen scope. |
| **Preview** | Query one selected SQL model with `dbt show` and display bounded results. |
| **Generate Docs** | Run `dbt docs generate` for the whole execution project. |
| **Source Freshness** | Check sources for the whole execution project. Leave Scope blank. |
| **Resolve Selection** | Run `dbt ls` and display the matched resources in Find. |

Commands save open documents first. One dbt command can run at a time per IDE
project, including previews, tests and CTE queries. Output, exit status and **Stop**
appear in **Run**. Each invocation owns its console and result view. Successful Run,
Build, Compile and Generate Docs commands reload artifacts. Completion notifications
follow the [notification settings](configuration.md#workspace-execution-defaults).

The console displays dbt log messages with timestamps and warning or error labels,
and renders ANSI text colors. Test and Build output uses the same readable format.
Ordinary script output and unrecognized JSON remain visible as received.

## Selection and state

Use **Review selection** in the configuration editor to open locally matched
resources in **Find**. Entries open their declarations. Incomplete resolution is
reported explicitly. **Copy command** copies the prepared command when preparation
succeeds. Review does not execute dbt.

**Resolve with dbt ls** starts an explicit CLI resolution in **Run** and opens its
matches in **Find**. A successful resolution can be reused while the configuration,
source and execution settings remain unchanged. Unknown local resource identities
block its reuse. Failed or canceled resolution does not supply runnable matches.

| Selection | Meaning |
| --- | --- |
| `orders+` | orders and downstream consumers. |
| `+orders` | orders and upstream dependencies. |
| `tag:finance,resource_type:model` | Models with the finance tag. |
| `orders customers` | Both selections combined. |
| `selector:nightly` | The named definition in selectors.yml. |

With a [baseline](configuration.md#state-comparison-and-deferral), use
`state:modified+` to select changed resources and consumers. **Compare baseline**
opens a resource chooser and native diff. It compares recorded baseline values with
current authored SQL, properties and configuration; current runtime defaults remain
unresolved. This comparison is separate from Git change impact and does not check
whether warehouse relations exist. A missing or incompatible baseline blocks
state-dependent commands.

## Preview results

**Preview** requires a dbt version supporting `show`. Select one model in Scope;
leave Selection and Exclude empty. In a local model editor, **Preview selected dbt
SQL** runs a selected SELECT query. **Preview dbt CTE** runs the CTE at the caret
with its dependencies. Inline previews require dbt Core 1.5+ and cannot preserve
installed-package context; preview the complete installed-package model instead.

The **Rows** view shows the target, row count, limit and elapsed time. **May be
truncated** means the query reached its row limit. Sort by column headers, resize
or reorder columns, and use **Filter** to search returned rows only. **Copy selection**
copies selected cells as tab-separated text. **Export CSV** or **Export TSV** saves
filtered rows; SQL null exports as unquoted NULL and the string NULL as `"NULL"`.

Changing **Preview row limit** alone runs no query. **Run with limit** starts another
query with the chosen limit. **Open SQL** or **Open preview SQL** opens a read-only
SQL editor; process output remains in the session console. After a mapped CTE
failure, **Go to CTE error** opens the corresponding authored source position.

**Open Compiled dbt SQL** and **Compare Authored and Compiled dbt SQL** are available
from the model editor. Compile or Generate Docs for the matching project and target
to produce compiled SQL.

## Inspect CTE queries

Choose **Inspect dbt CTEs** in a local SQL model to open a native **Debug** session.
Opening it runs no query. The session uses the project's
[execution defaults](configuration.md#workspace-execution-defaults), including its
active target; overrides on a separate Run configuration do not retarget it.
**Frames** lists the model's top-level CTEs; choose a frame
to view its cached result. **Rows** contains its preview grid.

| Control | Action |
| --- | --- |
| **Step Over** | Query the next CTE. The first step queries the first CTE. |
| **Resume** | Query successive CTEs until a CTE breakpoint, failure or the end. |
| Editor breakpoint | Set a dbt CTE breakpoint on a top-level CTE's definition line. Resume pauses after that CTE's result is available. |
| **Cached Step Back** | Display the previous cached result without querying. |
| **Count all CTE rows** | Count all rows of the selected CTE in a separate query. |
| **Restart CTE inspection** | Clear progress and cached results. Step explicitly to query again. |
| **Stop** | Cancel this inspection's active query and finish the session. |
| **Step Into** | Open another inspection when the selected CTE references exactly one local model. Opening it runs no query. |

**Step Out** and **Run to Cursor** are unavailable for independent CTE queries;
they leave the current inspection position unchanged.

Each step runs a separate query that recalculates its dependencies. No warehouse
query is paused; random values and timestamps can differ between steps. Editing
source or changing execution settings cancels the inspection's active query and
invalidates cached results. Recursive CTEs, CTE column lists and template setup
that cannot be preserved are rejected before execution.

For navigation without querying, use the [CTE structure view](navigation.md#cte-structure-and-dependency-hierarchy).

## Tests

Run from the gutter beside a singular SQL test, YAML unit-test name or attached
YAML data test. The context Run action also supports a test file. Test and Build
configurations use the same native test console.

The test tree provides output, durations, source navigation, Stop, Rerun and Rerun
Failed Tests. A failed-test rerun selects only failed or errored test identities
and keeps the original executable, prefix arguments, profiles, target and
environment. Model build results never count as passed tests.

Gutters show green for passed tests, red for failures/errors and yellow for
warnings/skips. A Run triangle means there is no compatible current result,
including after source or execution settings change. Failure takes precedence when
several tests share a declaration line.

Some attached generic tests require manifest identities for exact execution. Run
Generate Docs for the matching project and target first. Missing or removed exact
identities stop execution with an explanation; they never widen the selection.
