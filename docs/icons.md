# dbt resource icons

[User guide](getting_started.md) · [Navigation](navigation.md)

**Navigation results** below means the Go to Declaration chooser, Find Usages
window, and applicable dbt Properties / dbt Dependencies popups. Ctrl+Click on a
single target jumps directly. Dependency icons identify types; **Uses** / **Used by**
shows direction, and indented `file:line` entries open references.

## Declaration legend

Paths come from the nearest `dbt_project.yml`; defaults appear in parentheses.
[Engine/version settings](configuration.md#engine-and-version) control availability.
Project-window files use their resource icon first; otherwise, top-level declarations
must share one icon. Mixed icons retain the normal file-type icon. Ignored/generated
files keep their normal icons. Declarations inside files have navigation icons, not
separate Project-window entries.

| Icon | Meaning | Rule |
| --- | --- | --- |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/gutter-dark.svg"><img src="icons/gutter.svg" alt="dbt gutter navigation" width="20" height="20"></picture> | Gutter navigation | Editor gutter: resource/declaration lines with related targets, ambiguous references or metadata-only targets. Click for a chooser; tooltips explain ambiguity/staleness. One marker per line. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/table-dark.svg"><img src="icons/table.svg" alt="model" width="20" height="20"></picture> | SQL or Python model | Project window and navigation results: `.sql`/`.py` files in `model-paths` (`models/`) and their `models:` YAML properties. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/table-dark.svg"><img src="icons/table.svg" alt="source" width="20" height="20"></picture> | Source | Project window and navigation results: `sources:` declarations in resource YAML, e.g. `sources.yml`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/table-dark.svg"><img src="icons/table.svg" alt="table" width="20" height="20"></picture> | Source table, SQL CTE or Information Schema view | Navigation results: tables under `sources:`, SQL CTE definitions and views from selected Information Schema metadata. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/seed-dark.svg"><img src="icons/seed.svg" alt="seed" width="20" height="20"></picture> | Seed | Project window and navigation results: `.csv` files in `seed-paths` (`seeds/`) and their YAML properties. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/snapshot-dark.svg"><img src="icons/snapshot.svg" alt="snapshot" width="20" height="20"></picture> | Snapshot | Project window and navigation results: `.sql` files in `snapshot-paths` (`snapshots/`), legacy `{% snapshot %}` blocks and YAML properties. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/query-dark.svg"><img src="icons/query.svg" alt="query" width="20" height="20"></picture> | Analysis | Project window and navigation results: `.sql` files in `analysis-paths` (`analyses/`) and their YAML properties. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/query-dark.svg"><img src="icons/query.svg" alt="query" width="20" height="20"></picture> | Saved query | Project window and navigation results: top-level `saved_queries:` YAML entries. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/macro-dark.svg"><img src="icons/macro.svg" alt="macro" width="20" height="20"></picture> | Macro | Project window and navigation results: `{% macro %}` blocks in resource `.sql` files, usually under `macro-paths` (`macros/`), and `macros:` YAML properties. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/jinja-dark.svg"><img src="icons/jinja.svg" alt="Jinja" width="20" height="20"></picture> | Builtin Jinja function | Navigation results: builtin function targets, opening documentation or configured local definitions. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/function-dark.svg"><img src="icons/function.svg" alt="function" width="20" height="20"></picture> | UDF body | Project window and navigation results: `.sql`/`.py`/`.js` files in `function-paths` (`functions/`) and `functions:` YAML properties. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Singular data test | Project window and navigation results: `.sql` files in `test-paths` (`tests/`), including nested folders such as `tests/unit/`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Generic test | Project window and navigation results: `{% test %}` blocks in resource `.sql` files. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Attached data tests | Project window: YAML entries containing only model/seed/snapshot names and nonempty `data_tests:`/`tests:`, including column tests. Other properties keep the resource icon; declaration results always keep it. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="unit test" width="20" height="20"></picture> | Unit test | Project window and navigation results: `unit_tests:` YAML entries with `model`, `given` and `expect`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="fixture" width="20" height="20"></picture> | Unit-test fixture | Project window and navigation results: `.csv`/`.sql` files under `<test-path>/fixtures/`; this rule takes precedence over data tests. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/check-dark.svg"><img src="icons/check.svg" alt="check" width="20" height="20"></picture> | dbt v2 check | Project window and navigation results: `.sql` files in `check-paths` (`checks/`) and `checks:` YAML entries with `engine: v2`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/doc-dark.svg"><img src="icons/doc.svg" alt="doc" width="20" height="20"></picture> | Docs block | Project window and navigation results: `{% docs %}` blocks in resource `.sql`/`.md` files. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/doc-dark.svg"><img src="icons/doc.svg" alt="doc" width="20" height="20"></picture> | Builtin documentation target | Navigation results: documentation targets for builtin names. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/semantic-model-dark.svg"><img src="icons/semantic-model.svg" alt="semantic model" width="20" height="20"></picture> | Semantic model | Project window and navigation results: top-level `semantic_models:` YAML entries. Model-embedded definitions appear in navigation results. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/metric-dark.svg"><img src="icons/metric.svg" alt="metric" width="20" height="20"></picture> | Metric or measure | Project window and navigation results: top-level `metrics:` YAML entries. Nested measures and model-embedded metrics appear in navigation results. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/alias-dark.svg"><img src="icons/alias.svg" alt="alias" width="20" height="20"></picture> | SQL relation alias or metric input alias | Find Usages, declaration chooser: table/CTE/subquery qualifiers (`o` in `o.id` after `FROM … AS o`), or metric-input `alias:` names used in `expr` (`type_params.metrics[]`; Core 1.12+ `input_metrics[]`). SELECT-list aliases use Column; Project-window files keep their resource icon. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/dimension-dark.svg"><img src="icons/dimension.svg" alt="dimension" width="20" height="20"></picture> | Dimension | Navigation results: semantic dimensions declared in YAML. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/entity-dark.svg"><img src="icons/entity.svg" alt="entity" width="20" height="20"></picture> | Entity | Navigation results: semantic entities declared in YAML. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/column-dark.svg"><img src="icons/column.svg" alt="column" width="20" height="20"></picture> | Column | Navigation results: YAML columns, SQL projections (including SELECT-list aliases) and selected Information Schema columns. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/export-dark.svg"><img src="icons/export.svg" alt="export" width="20" height="20"></picture> | Exposure or export | Project window and navigation results: top-level `exposures:` YAML entries. Nested saved-query exports appear in navigation results. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/group-dark.svg"><img src="icons/group.svg" alt="group" width="20" height="20"></picture> | Group | Project window and navigation results: top-level `groups:` YAML entries defining resource ownership. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/package-dark.svg"><img src="icons/package.svg" alt="package" width="20" height="20"></picture> | Project or installed package | Navigation results: dbt project and installed-package declarations. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/variable-dark.svg"><img src="icons/variable.svg" alt="variable" width="20" height="20"></picture> | Variable | Navigation results: dbt variable, Jinja local and environment-key definitions. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/parameter-dark.svg"><img src="icons/parameter.svg" alt="parameter" width="20" height="20"></picture> | Parameter | Navigation results: macro parameters and function argument declarations. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/config-dark.svg"><img src="icons/config.svg" alt="config" width="20" height="20"></picture> | Configuration | Navigation results: configuration keys, profiles and target settings. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/property-dark.svg"><img src="icons/property.svg" alt="property" width="20" height="20"></picture> | Property | Navigation results: properties, Jinja namespace attributes and declarations without a dedicated icon. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/result-dark.svg"><img src="icons/result.svg" alt="result" width="20" height="20"></picture> | Statement result | Navigation results: named Jinja statement results. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/selector-dark.svg"><img src="icons/selector.svg" alt="selector" width="20" height="20"></picture> | Selector file | Project window and navigation results: named entries under `selectors:` in the project's `selectors.yml`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/materialization-dark.svg"><img src="icons/materialization.svg" alt="materialization" width="20" height="20"></picture> | Materialization | Project window and navigation results: `{% materialization %}` blocks in resource `.sql` files. |
| Declared resource's icon | YAML resource properties | Project window and navigation results: named entries such as `models:`, `sources:`, `macros:` and `unit_tests:` in configured resource paths. Use the declared resource's icon, except test-only files above. |

The same resource icons also identify completion suggestions. Test results use
the IDE's status icons and text labels for running, passed, failed and ignored
tests; warning and cancellation details remain visible in their output.

## Tool-window icons

The dbt tool-window icon is a dbt resource icon; the buttons use the IDE's standard
action icons. See [the dbt window](workspace.md) for the corresponding controls.

| Icon | Meaning | Rule |
| --- | --- | --- |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/table-dark.svg"><img src="icons/table.svg" alt="dbt tool window" width="20" height="20"></picture> | dbt tool window | Tool-window stripe entry labeled **dbt**. |
| Run triangle | Run | Runner **Run** button. |
| Build/compile symbol | Build or Compile | Runner **Build** and **Compile** buttons; the button label distinguishes the command. |
| Test/run symbol | Run tests | Runner **Test** button. |
| Preview symbol | Preview rows | Runner **Preview** button. |
| Preview/details symbol | Documentation | Runner **Generate Docs** button and Lineage **Toggle documentation** button. |
| Refresh arrows | Refresh or generate | Runner **Source Freshness** and Lineage **Regenerate Docs** buttons; the label or tooltip identifies which command runs. |
| Suspend/stop symbol | Stop execution | Runner **Stop** button. |
| Clear/garbage-collection symbol | Clear output | Runner **Clear** button. |
| Gear | Settings | Runner **Settings** button. |
| Close cross | Clear or close | Lineage **Clear search** and **Close documentation** buttons; each has its own tooltip. |
| Magnifier plus/minus | Zoom | Lineage **Zoom in** and **Zoom out** buttons. |
| Fit-content corners | Fit graph | Lineage **Fit graph** button. |

## Lineage resource symbols

The graph uses compact symbols next to resource kinds. Execution badges display
status text separately; a resource symbol never means that a command succeeded.

| Icon | Meaning | Rule |
| --- | --- | --- |
| ▤ | Model or other resource | Model cards and documentation headings; also the fallback for resource kinds without another graph symbol. |
| ⇥ | Source table | Source-table cards and documentation headings. |
| ▦ | Seed | Seed cards and documentation headings. |
| ◷ | Snapshot | Snapshot cards and documentation headings. |
| ◈ | Exposure | Exposure cards and documentation headings. |
