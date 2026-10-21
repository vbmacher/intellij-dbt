# dbt workspace

[User guide](getting_started.md)

Open **View → Tool Windows → dbt** for the **Lineage** tab. dbt commands run in the
IDE's **Run** window, CTE inspection uses the **Debug** window, and resource details
open in the **Documentation** tool window. Browsing the graph does not run dbt or
query the warehouse.

## Lineage

Cards represent models, source tables, seeds, snapshots and exposures; arrows lead
from upstream resources to their consumers. The graph follows the model in the
active editor unless its focus is pinned. The **Focus** label marks the graph focus;
a checkmark and border mark the selected card. Depth and layout direction are
[Lineage settings](configuration.md#lineage-settings). If the tab is blank, check the
[JCEF requirement](installation.md#requirements).

| Control | Action |
| --- | --- |
| Click a card | Select it, open native Documentation and dim unrelated resources. |
| Double-click a card | Focus the graph on it and open its definition. |
| Enter / Shift+Enter | On a keyboard-focused card: select it / focus the graph on it and open its definition. |
| **Inspect** | Choose a resource to center it at a readable size and open its details. |
| **Search resources** | Find a resource by resource or package name. **Clear search** returns to the selection. |
| **+ N more** | Reveal five more dependency levels in that direction. |
| **Pin focus** / **Unpin focus** | Stop or resume following the editor. Double-clicking a card still changes focus. |
| Drag | Move a card, or pan by dragging empty space. |
| Mouse wheel, **Zoom in**, **Zoom out** | Zoom. Automatic views keep cards at 80–100% zoom, so large graphs extend beyond the canvas. |
| **Fit graph** | Show the full overview. |
| Zoom percentage | Return to 100% around the selected or focused resource. |
| **Open Documentation** | Open details for the selected resource, or the focused one when nothing is selected. See [Quick documentation](editing.md#quick-documentation). |
| **Regenerate Docs** | Open a Generate Docs [Run configuration](#run-configurations) for the graph's execution project without launching it. |

### Filter, group and export

Enter **Selection** and **Exclude** expressions ([syntax](#review-a-selection-and-compare-a-baseline)),
then click **Apply**. Direct neighbors that do not match remain as dashed
**Filtered boundary** cards; other omitted resources are counted as hidden.
**Clear** restores the graph. **Run configuration…** opens a new dbt Run
configuration with the same Selection and Exclude values for you to review and save;
filtering never modifies existing Run configurations.

**Group by** offers **Schema**, **Folder** or **Tag (first alphabetically)**; a
resource with several tags appears under its alphabetically first tag. The **Groups**
panel beside the graph lists the groups: click one to collapse or expand it and move
the view to it.

Click or drag in the **Minimap** to navigate; arrow keys pan when it has focus.
Clear the **Minimap** checkbox to hide it. **PNG**, **SVG** and **Copy image**
capture the graph as filtered and collapsed; **Include legend** adds a legend. If a
graph is too large for PNG, collapse groups, narrow the filter or use SVG.

### Build and freshness badges

Cards with compatible execution results show build status, failing-test counts and
source freshness. Badge details identify the target and invocation. Click a badge
to open the recorded results in **Run**; this starts no command. Editing source
makes earlier results stale, and a missing badge does not mean success. Run the
**Source Freshness** command to update freshness.

## Run dbt commands

### Run configurations

Create a **dbt** configuration in **Run → Edit Configurations…**, or start one from:

- the Run gutter icon on the first line of a model, or the context Run action on a
  model, model directory or test file;
- the Run gutter icon beside a test ([Tests](#tests));
- **Run configuration…** or **Regenerate Docs** in [Lineage](#filter-group-and-export).

| Field | Effect |
| --- | --- |
| **Owning dbt root** | The source project's `dbt_project.yml` directory. |
| **Scope** | Absolute path to a model or model directory. Blank selects the whole execution project. |
| **Command** | One of the [commands](#commands) below. |
| **Target** | Overrides the active target. Blank uses the project setting or profile default. |
| **Selection** | dbt selection expression; overrides Scope. **Ctrl+Space** offers completions. |
| **Exclude** | Selection terms to remove; also supports **Ctrl+Space**. |
| **Named selector (overrides Selection)** | A definition in `selectors.yml`. |
| **Full Refresh (Run / Build)** | Rebuild selected incremental resources from all source data. |
| **Inline SQL (Preview only)** | A SELECT query previewed in the selected model's context. Blank previews the complete model. |
| **Executable override** | Executable or wrapper for this configuration. Blank uses the project's executable and prefix arguments. |
| **Prefix arguments** | Fixed arguments for the overridden executable, before the dbt command. |
| **Profiles directory override** | Directory containing `profiles.yml`. Blank uses the project setting. |

A configuration keeps its scope when you switch editors. Overrides do not change
project settings; other values come from the [execution settings](configuration.md#execution-settings)
current at launch. Installed-package models run through their consuming project.

### Commands

| Command | Result |
| --- | --- |
| **Run** | Materialize models in the scope. |
| **Build** | Build selected resources and run their tests in the test console. |
| **Test** | Run selected data and unit tests in the test console. |
| **Compile** | Produce compiled SQL for the scope. |
| **Preview** | Query one model with `dbt show`; see [Preview query results](#preview-query-results). |
| **Generate Docs** | Run `dbt docs generate` for the whole execution project. |
| **Source Freshness** | Check sources for the whole execution project; leave Scope blank. |
| **Resolve Selection** | Run `dbt ls` and show the matched resources in **Find**. |

Commands save open documents first. One dbt command runs at a time per IDE project,
including previews, tests and CTE queries. Each invocation has its own **Run**
console with output, exit status and **Stop**. The console shows dbt log messages
with timestamps and warning or error labels and renders ANSI colors; other output
appears as received. Successful Run, Build, Compile and Generate Docs commands
reload artifacts. Completion notifications follow the
[notification setting](configuration.md#execution-settings).

### Review a selection and compare a baseline

| Selection | Meaning |
| --- | --- |
| `orders+` | orders and downstream consumers |
| `+orders` | orders and upstream dependencies |
| `tag:finance,resource_type:model` | Models with the finance tag |
| `orders customers` | Both selections combined |
| `selector:nightly` | The named definition in `selectors.yml` |

In the configuration editor, **Review selection** opens the locally matched resources
in **Find** without running dbt; entries open their declarations and incomplete
resolution is reported. **Copy command** copies the prepared command.
**Resolve with dbt ls** runs dbt in **Run** and opens its matches in **Find**. The
result is reused while the configuration, source and execution settings are
unchanged and all matched resources are known locally. A failed or canceled
resolution supplies no matches.

With a [baseline](configuration.md#baseline-state-and-deferral), `state:modified+`
selects changed resources and their consumers. **Compare baseline** opens a resource
chooser and a native diff of the recorded baseline values against the current SQL,
properties and configuration; current runtime defaults stay unresolved. It does not
check warehouse relations and is separate from [Git change impact](integrations.md#review-the-impact-of-git-changes).
A missing or incompatible baseline blocks state-dependent commands.

### Tests

Click the Run gutter icon beside a singular SQL test, a YAML unit-test name or an
attached YAML data test. These configurations select the exact tests. Test and Build
results appear in the native test tree with output, durations, source navigation,
**Stop**, **Rerun** and **Rerun Failed Tests**. Rerunning failed tests selects only
failed or errored tests and keeps the original executable, prefix arguments,
profiles, target and environment. Model build results never count as passed tests.

The gutter icon then shows the latest result: green for passed, red for failed or
errored, yellow for warnings or skipped. Failure wins when several tests share a
line. The plain Run icon returns when no current result exists, including after
source or execution settings change.

Some attached generic tests need manifest identities to run exactly; run
**Generate Docs** for the matching project and target first. A missing or removed
identity stops the run with an explanation; the selection is never widened.

## Preview query results

**Preview** needs a dbt version that supports `show`. In a Run configuration, select
one model in **Scope** and leave **Selection** and **Exclude** empty. In a local model
editor, use the context menu:

- **Preview selected dbt SQL** runs the selected SELECT query.
- **Preview dbt CTE** runs the CTE at the caret with its dependencies.

These two need dbt Core 1.5+ and cannot preserve installed-package context; preview
the complete installed-package model instead.

The **Rows** view shows the target, row count, limit and elapsed time. **May be
truncated** means the row limit was reached. Sort by column headers, resize or
reorder columns, and use **Filter** to search the returned rows. **Copy selection**
copies selected cells as tab-separated text. Choose CSV or TSV and click **Export
CSV** / **Export TSV** to save the filtered rows; SQL null exports as unquoted
`NULL` and the string NULL as `"NULL"`.

Changing **Preview row limit** alone runs nothing; **Run with limit** runs the query
again with that limit. **Open SQL** or **Open preview SQL** opens the executed SQL in
a read-only editor; process output stays in the console. After a CTE failure, **Go to CTE error** opens the matching
position in your source.

## Compiled SQL

In a model editor, **Open Compiled dbt SQL** opens the compiled SQL read-only, and
**Compare Authored and Compiled dbt SQL** opens a diff of **Authored SQL** and
**Compiled SQL**. Run **Compile** or
**Generate Docs** for the matching project and target first.

## Inspect CTE queries

In a local SQL model, choose **Inspect dbt CTEs** from the context menu to open a
**Debug** session. Opening it runs no query. It uses the project's
[execution settings](configuration.md#execution-settings), including the active
target; Run configuration overrides do not apply. **Frames** lists the model's
top-level CTEs; select a frame to see its cached result in **Rows**.

| Control | Action |
| --- | --- |
| **Step Over** | Query the next CTE. The first step queries the first CTE. |
| **Resume** | Query successive CTEs until a CTE breakpoint, a failure or the end. |
| Editor breakpoint | Set on a top-level CTE's definition line; **Resume** pauses after that CTE's result is available. |
| **Cached Step Back** | Show the previous cached result without querying. |
| **Count all CTE rows** | Count all rows of the selected CTE in a separate query. |
| **Restart CTE inspection** | Clear progress and cached results. |
| **Stop** | Cancel the active query and end the session. |
| **Step Into** | Open another inspection, without querying, when the selected CTE references exactly one local model. |

**Step Out** and **Run to Cursor** do nothing in a CTE inspection.

Each step is a separate query that recalculates its dependencies, so random values
and timestamps can differ between steps. Editing source or changing execution
settings cancels the active query and discards cached results. Recursive CTEs, CTE
column lists and template setup that cannot be preserved are rejected before any
query runs. To browse CTEs without querying, use the
[CTE structure view](navigation.md#cte-structure-and-hierarchy).
