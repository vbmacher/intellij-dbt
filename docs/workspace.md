# dbt workspace

[User guide](getting_started.md) · [Configuration](configuration.md)

Open **View → Tool Windows → dbt** for the **Lineage**, **Runner**, **Tests**, and **Status** tabs.
Browsing source, documentation, and existing results does not run dbt. Command buttons
and explicit preview actions can execute warehouse queries. Configure execution in
[workspace and runner settings](configuration.md#workspace-and-runner-settings).

## Runner

### Choose the project and scope

Use **Model or directory** and its browse button to choose a model or folder. Paths
can be absolute or relative to the IDE project. A folder includes its enabled models
and subfolders. Leave the field blank or choose the execution project's root for
all models. If several projects exist, choose a path once to identify the execution
project before clearing it. Installed-package models run through their consuming
project. Changing editors does not change the Runner selection.

Use **Target** to select or type a target; blank uses the profile default. For the
**Executable arguments** field and wrapper behavior, see
[configuration](configuration.md#workspace-and-runner-settings).

| Control | Action |
| --- | --- |
| **Run**, **Build**, **Test**, **Compile** | Run the named dbt command for the chosen scope. An all-model selection omits `--select`. |
| **Full Refresh** | Rebuild selected incremental models from all source data when running **Run** or **Build**. Appears for incremental selections and resets when the selection changes. |
| **Preview** | Query one selected model with `dbt show` and display bounded results. Requires a CLI supporting `show` and a supported SQL model. |
| **Generate Docs** | Run `dbt docs generate` for the chosen execution project and target. |
| **Source Freshness** | Run `dbt source freshness`; select the entire execution project first. |
| **Clear** | Clear the command log and preview. |
| **Stop** | Cancel the active command. |
| **Settings** | Open [workspace configuration](configuration.md#workspace-and-runner-settings). |

Commands save documents before running. Only one command runs at a time, including
previews, test runs, and documentation generation. The log shows process output,
colors, completion, cancellation, and exit codes. Use the console's soft-wrap
control for long lines. Successful **Run**, **Build**, **Compile**, and **Generate
Docs** refresh available artifacts. Completion also produces IDE notifications;
see [notification prerequisites](configuration.md#workspace-and-runner-settings)
for desktop notifications. CLI or adapter errors appear in the log.

### Selection and state

Enter a dbt expression in **Selection**, optionally add **Exclude**, then click
**Review selection**. A nonempty **Selection** overrides **Model or directory**;
clear it to use the picker again. Press **Ctrl+Space** in either selector field
for completion, including package/version-qualified names and named selectors. For example, `tag:finance+` selects tagged
resources and their descendants.

The inline review shows the execution project, target, matched-resource count,
package and version identities, incomplete terms, and proposed **Build** command
without running it. **Current model**, **Upstream**, **Downstream**, and **Both**
fill the expression from the open model in the selected execution project.
**Show in Lineage** applies the fields to the graph; **Apply filter** in Lineage
also updates the Runner fields. **Copy command** copies the reviewed Build command
with quoted arguments. Use **Run**, **Build**, **Test**, or **Compile** to execute
the selection. Changes to the fields, target, source, or execution settings clear
the review; review again before copying the command. Supported local terms
include names, paths, fully qualified names, tags, resource types, packages, graph
operators, unions, intersections, excludes, and named selectors from `selectors.yml`.
Runtime-dependent terms can make the local review incomplete. **Refresh with dbt
ls** explicitly runs the CLI with the selected project, target, profile, and
exclusions, then shows the identities reported by dbt. It uses the same command
log and **Stop** button as other commands. Successful CLI matches can be run when
all reported resources have known local identities; unknown resources block
execution instead of substituting same-name models. Indirectly selected tests
remain subject to dbt's selection rules. No CLI refresh runs while typing or
browsing the graph.

| Expression | Selection |
| --- | --- |
| `orders+` | `orders` and its downstream consumers. |
| `+orders` | `orders` and its upstream dependencies. |
| `tag:finance,resource_type:model` | Models that also have the finance tag. |
| `orders customers` | The union of both selections. |
| `selector:nightly` | The named definition in `selectors.yml`. |

With a [baseline state](configuration.md#state-comparison-and-deferral), the review
also lists added, removed, and modified resources, reasons for changes, and upstream
dependencies that may use baseline relations. A baseline is a separate saved
`manifest.json` used for comparison; it must not overlap active output. Missing or
incompatible baselines block state-dependent commands. A proposed baseline relation
does not prove that it exists in the warehouse.
Use `state:modified+` to ask dbt for changed resources and their downstream consumers;
the local state review can be incomplete when comparison information is unavailable.

### Preview results

The result grid shows the target, returned row count, limit, and elapsed time.
**May be truncated** means the query reached its limit, not that the full relation
contains exactly that many rows. Empty results, invalid output, failure, and
cancellation are reported separately. **SQL / raw output** opens the SQL evidence
and original output for the successful query. **Preview SQL / raw output** also
works after a failed preview. Submitted source is labeled as being captured before
dbt compilation. Adapter SQL is shown only when dbt's structured output identifies
one preview statement from the current invocation; otherwise it is explicitly
unavailable.
For available compiled model SQL, use the documentation sidebar's **SQL** tab.

Click column headers to sort; numeric values sort numerically. Resize or reorder
columns as needed. **Filter** searches only returned rows, including the displayed
`NULL` marker. Sorting, filtering, and resizing do not run queries.

Select cells and click **Copy selection** for tab-separated text. Choose CSV or TSV
in the export selector, then click **Export CSV** or **Export TSV** to save the
filtered rows in their displayed order. Strings are quoted, embedded quotes are
doubled, and newlines and Unicode are retained. SQL null exports as unquoted
`NULL`; an empty string exports as `""`, and the literal string NULL as `"NULL"`.

Change **Preview row limit** within 1–1000, then click **Run with limit** to rerun
the query. Changing the value alone does not execute it. Canceled queries clear
the result instead of showing previous rows as current.

### Selected SQL and CTE inspection

In a model editor, select one supported SELECT query and choose **Preview selected
dbt SQL**. To inspect a CTE (a named query in a SQL `WITH` clause), place the caret
in its definition or on a reference and choose **Preview dbt CTE**. The preview
includes required preceding CTEs and preserves supported leading Jinja setup.
These inline previews require dbt Core 1.5+ command support. Installed-package
source cannot retain its package lookup context in an inline query; use a complete
model **Preview** instead. Inspection does not rewrite model source.

Choose **Inspect dbt CTEs** to open an **Inspect:** tab with the CTE list and grid.

| Control | Action |
| --- | --- |
| **Step** | Query the next CTE and show its bounded result. |
| **Continue to breakpoint** | Query successive CTEs until a marked CTE or the end. |
| **Break at selected CTE** | Mark or unmark the selected CTE as a stopping point. |
| **Step Back** | Display the preceding valid cached result without querying. |
| **Count all rows** | Run a separate count query for the selected CTE; `full_row_count` is distinct from the bounded preview count. |
| **Restart** | Cancel this inspection's query and clear its progress and results; click **Step** to query again. |
| **Stop** | Cancel this inspection's query and stop stepping until **Restart**. |
| **Step into referenced model** | Open an inspection for an unambiguous referenced model. |
| **Run with limit** | Rerun the selected CTE with the chosen limit without advancing. |

Each step is a separate query, so values such as random numbers or current timestamps
can change between steps. Source, dependency, target, and execution changes invalidate
cached results and cancel the inspection's active query. **Step** refreshes the CTE
list afterward. Recursive CTEs, explicit CTE column lists, unavailable dependencies,
and template setup that cannot be preserved are reported before execution; preview
the complete model in those cases.

Inline CTE previews request structured debug output to capture adapter SQL. After
a failure, **Go to CTE error** opens an authored location only when the adapter
reports a supported, unambiguous line/column and the query contains that unchanged
source span exactly once. Locations in generated SQL, Jinja rewrites, ambiguous
statements, and unsupported adapter error formats remain unavailable; inspect
**Preview SQL / raw output** instead. Editing the source disables navigation until
the preview is rerun.

## Lineage

Cards represent models, sources, seeds, snapshots, and exposures. Arrows lead from
upstream resources to their consumers. The graph follows the active model unless
its focus is pinned. Graph appearance is described in
[lineage settings](configuration.md#lineage-settings); browser requirements are in
[installation](installation.md).

- Click a card to select it, show documentation, and dim unrelated resources.
- Double-click a card to focus it and open its definition.
- Drag cards to move them or empty space to pan. Use the mouse wheel, **Zoom in**,
  **Zoom out**, and **Fit graph** to adjust the view.
- Search by resource or package name. **Clear search** returns to the selection.
- Click **+ N more** to reveal five more dependency levels in that direction.
- **Pin focus** prevents editor changes from moving the focus. Double-clicking
  another card still changes it. **Unpin focus** resumes editor following.

### Filter, group, and share

Enter **Selector** and **Exclude**, then click **Apply filter**. The expression
rules match [Runner selection](#selection-and-state). Messages identify incomplete
terms. Immediate nonmatching neighbors remain as dashed **Filtered boundary**
cards; other omitted resources are counted as hidden. **Clear filter** restores
the unfiltered graph and clears Runner's **Selection** and **Exclude** fields.

Use **Group by** to choose **Schema**, **Folder**, or **Tag (first alphabetically)**.
Group buttons collapse or expand their resources; clicking a collapsed group also
expands it. A resource with several tags appears once, under its alphabetically
first tag. All tags remain visible in documentation.

Click or drag in the **Minimap** to navigate; arrow keys pan when it has keyboard
focus. Clear its checkbox to hide it. **Export PNG**, **Export SVG**, and **Copy
image** include the current filtered and collapsed graph, labels, execution badges,
and theme. **Legend** controls whether the export includes a legend. For a graph
too large for PNG, collapse groups, narrow the filter, or use SVG.

### Documentation

**Toggle documentation** shows or hides the sidebar; **Close documentation** hides
it. Drag the divider to resize it and use **Expand description** for longer text.
The sidebar retains its open or closed state across refreshes.

| Tab | Content and actions |
| --- | --- |
| **Columns** | Column types, descriptions, and declarations. Click a column name to trace its upstream and downstream dependencies. **Open declaration** opens its authored definition. Origin buttons open upstream definitions and label direct or derived relationships, dependency kinds, expressions, and partial analysis. |
| **Tests** | Attached test declarations. Use the workspace **Tests** tab for execution results. |
| **SQL** | Raw model SQL and available compiled SQL. Missing compiled SQL is identified explicitly; use **Compile** or **Generate Docs** for the target. |
| **Metadata** | Source, logical identity, available physical relation fields, descriptions, and upstream/downstream resources. |

Descriptions resolve supported static `doc()` expressions combined with literal
text using `~`; unsupported expressions remain visible. Missing columns or SQL are
identified explicitly. **Regenerate Docs** runs documentation generation for the
Runner's selected execution project and target.
Column value dependencies contribute output values. Filter and join dependencies
affect which rows contribute; they are labeled separately from value derivation.

#### Edit properties

Select a writable local resource and click **Edit properties**. Edit its description,
tags, simple model data tests, and column types or descriptions. **Sync columns and
preview YAML** adds known missing columns while retaining existing declarations.
**Inherit empty descriptions** fills only empty descriptions with descriptions from
proven direct, single-source column passthroughs. Calculated or ambiguous columns
receive no inferred description.

Click **View YAML diff** to review the proposed source changes, then **Apply** to
write them. Cancel writes nothing. Existing custom fields and test definitions are
retained. Parameterized tests, anchored values, and sequences whose comments would
be lost must be edited in YAML. For versions inheriting base columns, edit the base
columns or create explicit version overrides before changing those columns here.
If project source or execution settings change while the dialog is open, reopen it
before applying.

### Build and freshness badges

Cards can show build results, failing-test counts, and source freshness. Badge
details identify the target, run, generation time, artifact, and any problems.
Click an execution badge to open **Tests** and inspect its associated evidence.
Run **Source Freshness** from Runner to obtain freshness results.

Source edits make older results stale. Missing, unsupported, malformed, or imported
artifacts can be unavailable or unverified; a test declaration alone never means it
passed. Browsing the graph does not execute commands or queries.

## Tests

Click **Refresh tests** to discover data tests, generic YAML test declarations, and
dbt 1.8+ unit tests from source and an available supported manifest. Refresh does
not run dbt. Select an execution project and use these controls:

| Control | Action |
| --- | --- |
| **Run tests** | Run project tests through Runner. |
| **Run selected test** | Run the selected test when its dbt identity is known. |
| **Rerun Failed** | Rerun failed/error test identities without indirectly selecting other tests. |
| **Stop** | Cancel the active command. |
| **Go to Definition** | Open the selected test's available source definition. |
| **Open Fixture** | Open its declared fixture file when available. |
| **Stored failing rows** | Open SQL for the failure relation recorded by dbt. |
| **Preview stored rows** | Explicitly query that relation with a bounded preview using the test run's project and target. |

The test tree distinguishes passed, failed, error, skipped, canceled, and not-run
states. Warnings display **WARN** rather than passing. Partial runs retain completed
tests and mark unfinished ones appropriately. **Build** also supplies test results.
Select a test to inspect its output and failure counts; Runner retains full process
output. Test commands share Runner's single active command and cancellation.

Some generic test identities require a manifest before individual runs are possible.
Their declarations remain browsable; use **Run tests** to generate the identities.
Missing or ambiguous source locations are reported instead of guessed. Stored rows
require a relation recorded by dbt; without one, the panel explains `store_failures`.

## Status

**Status** shows the selected executable, CLI version when available in the manifest,
target, adapter, run selection, lineage focus, resource count, and artifact paths,
timestamps, or problems. It also reports whether physical relation metadata matches
the current execution context. Opening the tab does not run a version command.
