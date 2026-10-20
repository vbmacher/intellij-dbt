# dbt workspace

[User guide](getting_started.md) · [Configuration](configuration.md)

Open **View → Tool Windows → dbt** for the **Runner**, **Lineage**, and **Tests** tabs.
Only command buttons and preview actions run dbt or query the warehouse. Configure
execution in [workspace and runner settings](configuration.md#workspace-and-runner-settings).

## Runner

### Choose the project and scope

Choose **Project**, then use **Scope** and its browse button to choose a model or folder, as an
absolute path or relative to the IDE project. A folder includes its enabled models
and subfolders. Leave the field blank, or choose the project root, to select all
models. The project selector chooses the execution project when Scope is blank. Choosing a path selects its owning execution project. Installed-package models run through their consuming
project. Switching editors does not change the Runner selection.

Use **Target** to select or type a target; blank uses the profile default. Choose
**Run**, **Build**, or another command from the dropdown, then click the button bearing that command’s name.

| Control | Action |
| --- | --- |
| **Run**, **Build**, **Test**, **Compile** | Run the named dbt command for the chosen scope. Selecting all models omits `--select`. |
| **Options → Full Refresh** | Rebuild selected incremental models from all source data when running **Run** or **Build**. Appears for incremental selections and resets when the selection changes. |
| **Preview** | Query one selected SQL model with `dbt show` and display up to the row limit. Requires a dbt version with `show`; clear Selection and Exclude first. |
| **Generate Docs** | Run `dbt docs generate` for the chosen execution project and target. |
| **Source Freshness** | Run `dbt source freshness` for the whole execution project. Choose a blank Scope or the project root; model selectors do not limit freshness checks. |
| **More… → Clear output** | Clear the command log and preview. |
| **Stop** | Cancel the active command. |
| **More… → Settings** | Open [workspace configuration](configuration.md#workspace-and-runner-settings). |

Commands save open documents first. Only one command runs at a time, including
previews, CTE inspection and test runs. The **Console** shows process output and the exit
code. Preview results open **Rows**; selection reviews open **Selection**. These
views share the result area. The footer identifies the running operation, project, target and invocation when available, and reports the outcome or evidence problem. Successful **Run**, **Build**, **Compile**, and
**Generate Docs** reload the artifacts. A notification appears when a command finishes; see
[desktop notifications](configuration.md#workspace-and-runner-settings).

### Selection and state

Click **Options** to expand the selector fields. Enter a dbt expression
in **Selection**, optionally add **Exclude**, then click
**Review selection**. A nonempty **Selection** overrides the path in **Scope**.
The toggle reads **Options: on** while either field or Full Refresh is set. Clear both selector
fields to return to the model or directory scope.
Press **Ctrl+Space** in either field for completion of names, packages, versions
and named selectors.

The review shows the execution project, target, matched resources, terms that
could not be resolved locally, and the chosen command, without running
anything. The scope summary reports local or CLI matches separately from this command preview; incomplete local resolution is labeled explicitly. **Copy command** copies that command. Editing the fields, target, source
execution settings, or chosen command clears the review.

- **Show in Lineage** applies the fields to the graph. Lineage filters remain separate from Runner until you click **Use in Runner**.
- **Refresh with dbt ls** asks dbt to resolve the selection, for example terms that
  depend on state or runtime values. It runs as a Runner command and lists what dbt
  matched. Running is blocked if dbt reports resources that are unknown locally. If
  the refresh fails or is stopped, review the selection again before running.

| Expression | Selection |
| --- | --- |
| `orders+` | `orders` and its downstream consumers. |
| `+orders` | `orders` and its upstream dependencies. |
| `tag:finance,resource_type:model` | Models that also have the finance tag. |
| `orders customers` | The union of both selections. |
| `selector:nightly` | The named definition in `selectors.yml`. |

With a [baseline state](configuration.md#state-comparison-and-deferral), the review
also lists added, removed, and modified resources and the upstream dependencies that
can be deferred to baseline relations. Use `state:modified+` to select changed
resources and their consumers. A missing or incompatible baseline blocks
state-dependent commands.

### Preview results

The result grid shows the target, row count, limit, and elapsed time. **May be
truncated** means the query reached the row limit. **SQL / raw output** shows the
submitted SQL and dbt's output; **Preview SQL / raw output** also works after a
failed preview. Compiled model SQL is in the documentation sidebar's **SQL** tab.

Click column headers to sort, and resize or reorder columns as needed. **Filter**
searches the returned rows only.

Select cells and click **Copy selection** for tab-separated text. Choose CSV or TSV
in the export selector, then click **Export CSV** or **Export TSV** to save the
filtered rows. SQL null exports as unquoted `NULL`, and the string NULL as `"NULL"`.

Change **Preview row limit** (1–1000), then click **Run with limit** to rerun the query.

### Selected SQL and CTE inspection

These previews require dbt Core 1.5+. In a model editor, select one SELECT query
and choose **Preview selected dbt SQL**. To preview a CTE (a named query in a SQL
`WITH` clause), place the caret in its definition or on a reference and choose
**Preview dbt CTE**; the CTEs it depends on are included. For installed-package
models, use the Runner's **Preview** instead. The model source is not changed.

Choose **Inspect dbt CTEs** to open an **Inspect:** tab with the CTE list and grid.

| Control | Action |
| --- | --- |
| **Step** | Query the next CTE and show its result. |
| **Continue to breakpoint** | Query successive CTEs until a marked CTE or the end. |
| **Break at selected CTE** | Mark or unmark the selected CTE as a stopping point. |
| **Step Back** | Display the previous result without querying. |
| **Count all rows** | Count all rows of the selected CTE in a separate query. |
| **Restart** | Cancel the query and clear progress and results; click **Step** to query again. |
| **Stop** | Cancel the query and stop stepping until **Restart**. |
| **Step into referenced model** | Open an inspection for an unambiguous referenced model. |
| **Run with limit** | Rerun the selected CTE with the chosen limit without advancing. |

Each step is a separate query, so random values or current timestamps can change
between steps. Editing the source or changing the target or settings discards cached
results. Recursive CTEs, CTEs with column lists, and Jinja setup that cannot be kept
are reported before running; preview the whole model instead.

After a failure, **Go to CTE error** opens the failing line in your source when it
can be located exactly; otherwise use **Preview SQL / raw output**.

## Lineage

Cards represent models, sources, seeds, snapshots, and exposures. Arrows lead from
upstream resources to their consumers. The graph follows the active model unless
its focus is pinned. See [lineage settings](configuration.md#lineage-settings) for
appearance and [installation](installation.md) for browser requirements.

- Click a card to select it, show documentation, and dim unrelated resources. The **Focus** label marks graph focus; a checkmark and border mark selection. **Inspect** provides keyboard access to visible resources. Enter on a focused card selects it; Shift+Enter changes graph focus.
- Double-click a card to focus it and open its definition.
- Drag cards to move them or empty space to pan. Use the mouse wheel, **Zoom in**,
  **Zoom out**, and **Fit graph** to adjust the view.
- Search by resource or package name. **Clear search** returns to the selection.
- Click **+ N more** to reveal five more dependency levels in that direction.
- **Pin focus** stops editor changes from moving the focus; double-clicking a card
  still changes it. **Unpin focus** resumes following the editor.

### Filter, group, and share

Open **Filter**, enter **Selection** and **Exclude**, then click **Apply filter**.
Expressions work as in [Runner selection](#selection-and-state). Direct neighbors that do not match
remain as dashed **Filtered boundary** cards; other omitted resources are counted
as hidden. **Clear filter** restores the graph. **Use in Runner** explicitly copies the filter’s Selection and Exclude fields into Runner; applying or clearing a graph filter alone leaves Runner unchanged.

Open **View** and use **Group by** to choose **Schema**, **Folder**, or
**Tag (first alphabetically)**.
Click a group button to collapse or expand it. A resource with several tags appears
under its alphabetically first tag.

Click or drag in the **Minimap** to navigate; arrow keys pan when it has focus.
Clear **View → Minimap** to hide it. Open **Export** for **Export PNG**,
**Export SVG**, and **Copy image**. These actions capture the graph as currently filtered and collapsed; **Include legend** adds a legend.
If the graph is too large for PNG, collapse groups, narrow the filter, or use SVG.

### Documentation

**Toggle documentation** shows or hides the sidebar; **Close documentation** hides
it. Drag the divider to resize it, or focus the divider and use Left/Right arrow keys. Use **Expand description** for longer text. **Copy identity** and **Copy path** copy the resource identity and source path.

| Tab | Content and actions |
| --- | --- |
| **Columns** | Column types and descriptions. Click a column name to trace its upstream and downstream dependencies; origin buttons open upstream definitions. Select a column, then use **Open declaration** to open its definition. Double-clicking a column name also opens its definition. |
| **Tests** | Attached test declarations. Use the workspace **Tests** tab for results. |
| **SQL** | Raw model SQL and compiled SQL; run **Compile** or **Generate Docs** to produce compiled SQL. |
| **Metadata** | Source, logical and physical names, descriptions, and upstream/downstream resources. |

Descriptions that use `doc()` show the resolved text. Column lineage labels filter
and join dependencies separately from columns that supply values. **Regenerate
Docs** runs `dbt docs generate` for the Runner's execution project and target.

#### Edit properties

Select a local resource and click **Edit properties** to edit its description, tags,
simple data tests, and column types and descriptions. **Sync columns and preview
YAML** adds missing known columns. **Inherit empty descriptions** copies descriptions
into empty ones from columns passed through unchanged from a single upstream column.

Click **View YAML diff** to review the changes, then **Apply** to write them.
Existing custom fields and tests are kept. Parameterized tests, YAML anchors and
commented sequences must be edited in YAML, as must columns that model versions
inherit from the base model.

### Airflow task context

A resource with a matching [Airflow mapping](configuration.md#airflow-task-mappings)
shows an **Airflow DAG / task** badge in its documentation header. Click it to see
the selection, exclusions and declared upstream ingestion in **Metadata**. **Open
mapping definition** opens the mapping; **Open Airflow task** opens the task in the
browser when a URL is configured. **Airflow selection unknown** marks mappings
whose selection cannot be resolved locally.

### Build and freshness badges

Cards with execution evidence show build results, failing-test counts, and source freshness from the latest compatible
artifacts; badge details show the target and run. Click an execution badge to open
its results in **Tests**. Run **Source Freshness** from Runner to get freshness
results. Editing the source makes earlier results stale.

## Tests

Choose **More… → Refresh tests** to list data tests, generic tests declared in YAML, and
dbt 1.8+ unit tests. **Project** selects the execution project independently of Runner’s path scope. **Show** includes all packages by default; choose **Project-owned** or **Dependencies** to narrow the tree. The search field filters names and owning resources. Discovery counts report shown and discovered declarations separately from execution results.

Choose the execution scope beside **Run**:

| Control | Action |
| --- | --- |
| **Project** | Run all tests in the execution project, including dependencies. Search and package filters do not limit this command. |
| **Filtered tests** | Run exactly the tests matching the project, package and search controls. |
| **Selected tests** | Run the selected tree items; selecting a suite includes its tests. Unresolved identities block execution rather than widening the scope. |
| **Rerun Failed** | Rerun only the tests that failed or errored. |
| **Stop** | Cancel the active command. |
| **Go to Definition** | Open the selected test's definition. |
| **Investigate… → Open Fixture** | Open its fixture file. |
| **Investigate… → Stored failing rows** | Open SQL for the failure relation recorded by dbt. |
| **Investigate… → Preview stored rows** | Query that relation with the test run's project and target. |

The tree shows passed, failed, error, warning, skipped and canceled results; tests without matching evidence remain pending. Missing or stale artifacts are reported separately. **Build** results also appear here. Select a test to inspect its declaration, owning resource, package, selector, execution status, failure information and recorded run context. Drag the horizontal divider to resize these details. Runner keeps the full process output.

Some generic tests require manifest identities for exact execution. Use **Generate Docs** in Runner for the matching project and target, then **Refresh tests**. **Stored failing rows** requires `store_failures` for
the test.

## Workspace details

In Runner, choose **More… → Workspace details** to see the executable, the dbt
version recorded in the manifest, target, adapter, run selection, lineage focus, resource count, artifact paths and
timestamps, and whether relation metadata matches the current target.
