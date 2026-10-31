# Selection review

[Capability index](README.md) · [User guide](../docs/workspace.md#review-a-selection-and-compare-a-baseline)

Check which resources a dbt Run configuration selects before you run it. You can
match resources locally without running dbt, or run `dbt ls` and reuse its result.

## Inputs

These come from the Run configuration. Saved values are used, or the current
editor values while the configuration is being edited:

- **Owning dbt root**, **Target**, and executable or profiles overrides.
- **Scope**, **Selection**, **Exclude** and **Named selector**.
- Local source, `selectors.yml` and configured local metadata. If the project has
  no `selectors.yml`, named selectors come from the manifest.

The scope is chosen by the first nonblank field:

1. **Named selector**, used as `selector:<name>`.
2. **Selection**.
3. **Scope**, which selects the models in that file or directory.
4. If all three are blank, the whole execution project is selected.

**Exclude** removes matches in every case.

## Review selection

**Review selection** matches resources locally. It never starts dbt.

- **Output:** a **Find** tab named `dbt local selection · <target>`. Each entry
  opens a resource declaration. Only enabled resources are listed.
- **Supported terms:**
  - Plain names and these methods: `fqn`, `path`, `file`, `package`, `tag`,
    `config.tags`, `config.materialized`, `access`, `group`, `source`,
    `resource_type`/`type`, `exposure`, `metric`, `saved_query`, `test`, `function`,
    `check` and `selector`. Values can use glob patterns.
  - Spaces combine terms (union). Commas keep only resources that match every
    term (intersection).
  - Graph operators: `N+` adds upstream resources, `+N` adds downstream resources,
    and `@` adds downstream resources and all their upstream resources. `N` is an
    optional depth.
  - Named selectors with `union`, `intersection`, `exclude` and method mappings.
    Named selectors can reference other named selectors.
- **Unresolved terms:** these terms never widen the result. Instead, the tab is
  marked `Incomplete:` and a warning lists each problem:
  - an unsupported term, which "requires explicit dbt ls resolution";
  - a missing or ambiguous named selector;
  - a cycle between named selectors.
- `state:` terms are unresolved here. [Baseline comparison](state-comparison.md)
  evaluates them locally.
- **Show in Lineage** filters the [Lineage graph](resource-lineage.md) using the
  configuration's Selection or Named selector, plus Exclude. If both are blank, it
  uses the models in Scope.
- **Copy command** copies the full command line for the configuration's command,
  including executable, target, selection and exclusions. The button is hidden
  when the command cannot be prepared, for example when the selection is
  incomplete.

## Resolve with dbt ls

**Resolve with dbt ls** runs the **Resolve Selection** command. It uses the
configuration's target and execution settings, and its console appears in **Run**
as `dbt ls · <root>`.

- **Output:** a **Find** tab named `dbt CLI selection · <target>` that lists the
  resources dbt returned.
- **Named selectors:**
  - A single named selector is passed to dbt as `--selector`.
  - Named selectors combined with other terms, or used in Exclude, are expanded
    locally first. If they cannot be fully expanded, the command fails before
    running dbt.
- **State terms:** `state:` terms pass the configured baseline to dbt. If the
  baseline is missing, the command fails.
- **Unknown resources:** a resource dbt returns but the plugin cannot find
  locally is listed as `CLI resource unavailable locally; execution blocked`. In
  that case the result is not kept.
- **Failure:** if dbt fails or is canceled, the result is not kept. If dbt prints
  output but no resource records, a warning appears.

## Selection when running

Commands that use Selection, Exclude or Named selector apply these rules:

- **Kept dbt ls result:** after you click **Apply**, a complete dbt ls result runs
  exactly the resources dbt matched. It is used only while nothing it depends on
  changes:
  - any configuration field;
  - plugin settings;
  - artifact identity;
  - any project source file.

  After a change, or after the IDE restarts, the run uses local matching again.
  A result that finishes after you edit the configuration is discarded.
- **Local matching:** the run is blocked if local matching is incomplete or
  matches nothing. If every problem comes from a `state:` term, the expression is
  passed to dbt unchanged and requires a compatible baseline.
- **Exclusions:** Exclude terms are also passed to dbt as `--exclude`, so dbt
  does not select tests of excluded resources indirectly.
- **Commands without selection:** **Generate Docs** and **Source Freshness** run
  for the whole project. **Preview** rejects a selection.

## Example

Set **Selection** to `orders+` and **Exclude** to `daily_totals`. **Review
selection** lists `orders` and all its downstream resources except `daily_totals`,
without running dbt. **Copy command** copies a `dbt run` command. It selects each
matched resource by its `fqn:` name and includes `--exclude daily_totals` and the
configured target. Changing the selection to `orders+ state:modified` marks the review as incomplete.
**Resolve with dbt ls** then lists the resources dbt matched. If all of them are
known locally, the next run uses exactly those resources.

## Interactions

- [Baseline comparison](state-comparison.md) evaluates `state:` terms and supplies
  the baseline.
- [Workspace execution](workspace.md) owns the process lifecycle shared with
  `dbt ls`.
- In the [Lineage graph](resource-lineage.md), **Run configuration…** creates a new
  Run configuration from the graph's Selection and Exclude. Filtering the graph never
  changes existing configurations.
- In Selection and Exclude, **Ctrl+Space** completes the current term. It suggests
  resource selectors, `package:`, `tag:`, `resource_type:` and `selector:` values,
  method prefixes and `state:modified`. Graph operators around the term are kept.
