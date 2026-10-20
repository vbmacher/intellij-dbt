# dbt workspace

[User guide](getting_started.md) · [Execution](execution.md) · [Configuration](configuration.md)

Open **View → Tool Windows → dbt** for the **Lineage** graph. Run commands in the
IDE's **Run** window, inspect CTE queries in **Debug**, and read resource details in
**Documentation**. Browsing the graph does not run dbt or query the warehouse.

## Lineage

Cards represent models, sources, seeds, snapshots and exposures. Arrows lead from
upstream resources to their consumers. The graph follows the active model unless
its focus is pinned. See [lineage settings](configuration.md#lineage-settings) for
appearance and [installation requirements](installation.md) for browser support.

- Click a card to select it, open native Documentation and dim unrelated resources.
  The **Focus** label marks graph focus; a checkmark and border mark selection.
  **Inspect** provides keyboard access to visible resources. Enter selects a focused
  card; Shift+Enter changes graph focus and opens its definition.
- Double-click a card to focus it and open its definition.
- Drag cards to move them or empty space to pan. Use the mouse wheel, **Zoom in**,
  **Zoom out** and **Fit graph** to adjust the view.
- Search by resource or package name. **Clear search** returns to the selection.
- Click **+ N more** to reveal five more dependency levels in that direction.
- **Pin focus** stops editor changes from moving graph focus. Double-clicking a
  card still changes it. **Unpin focus** resumes following the editor.
- **Open Documentation** opens details for the selected resource, or the focused
  resource when nothing is selected. See [resource documentation](editor.md#quick-documentation).

### Filter, group and share

Enter **Selection** and **Exclude**, then click **Apply filter**. Expressions work
as in [selection review](execution.md#selection-and-state). Direct neighbors that do
not match remain as dashed **Filtered boundary** cards; other omitted resources
are counted as hidden. **Clear filter** restores the graph.

**Use in Run Configuration** opens a native dbt configuration with the graph's
Selection and Exclude values. Review and save it there. Filtering or clearing the
graph does not modify existing Run configurations. **Regenerate Docs** opens a
Generate Docs configuration for the graph's execution project; it does not launch
that command.

Use **Group by** to choose **Schema**, **Folder** or **Tag (first alphabetically)**.
Click a group button to collapse or expand it. A resource with several tags appears
under its alphabetically first tag.

Click or drag in the **Minimap** to navigate; arrow keys pan when it has focus.
Clear **Minimap** to hide it. **Export PNG**, **Export SVG** and **Copy image** capture
the graph as filtered and collapsed; **Include legend** adds a legend. If a graph is
too large for PNG, collapse groups, narrow its filter or use SVG.

### Build and freshness badges

Cards with compatible execution evidence show build results, failing-test counts
and source freshness. Badge details identify the target and invocation. Click a
badge to open recorded results in **Run**; this does not start a command. Editing
source makes earlier results stale. Missing evidence does not indicate success.
Run **Source Freshness** through a dbt Run configuration to update freshness results.
