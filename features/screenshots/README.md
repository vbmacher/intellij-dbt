# Screenshot sources

Native IDE captures reused from the repository's local QA artifacts and existing
navigation guide. Files here are checked in so feature specifications do not depend
on ignored `target/` contents. Captures are unmodified.

The [current configuration form](../../docs/screenshots/configuration.png) is rendered
from the actual settings component in `DbtSettingsTest` with a fixture project.
Set `DBT_SETTINGS_SCREENSHOT` to an absolute output path when running that test to
recreate it. This image does not show an installed IDE session.

The `downstream-consumers-*.png` images render the production Lineage and Exposures
contents in IntelliJ's tabbed content UI with a local dbt fixture and local HTTP
metadata responses. Set `DBT_CONSUMERS_SCREENSHOT_DIR` to an absolute output
directory when running `DbtConsumersPanelTest` to recreate them. They show all
consumers, the `id` column filter and a failed refresh retaining cached consumers,
without an installed IDE window frame.

Editor and navigation captures show local source operations. dbt process/result
views use a recording executable and fixture artifacts. Metabase views use a local
HTTP fixture. Airflow discovery uses local Python files without contacting a live scheduler.
The stopped agent view contains no endpoint authorization token.

Command, property-form and CTE captures in this table show custom layouts and do
not represent the current native controls. Use [Execution](../../docs/workspace.md),
[Workspace](../../docs/workspace.md) and [Configuration](../../docs/configuration.md)
for current procedures. Editor and navigation captures illustrate their named
source operations.

| Capture | Source | Scope |
| --- | --- | --- |
| [configuration.png](configuration.png) | `target/manual-qa-1.2.0/authoring-sqlfluff-settings.png` | dbt Workspace settings form for the example project. |
| [root-workspace.png](root-workspace.png) | `target/manual-qa-1.2.0/root-workspace.png` | Disposable dbt project with local models, macros, tests and project files. |
| [model-usages.png](model-usages.png) | `docs/screenshots/model-usages.png` | Find Usages lists references to the orders model. |
| [model-dependencies.png](model-dependencies.png) | `docs/screenshots/model-dependencies.png` | Dependency chooser with consumers and their reference locations. |
| [model-properties.png](model-properties.png) | `docs/screenshots/model-properties.png` | Property chooser for the orders model. |
| [root-column-completion.png](root-column-completion.png) | `target/manual-qa-1.2.0/root-column-completion.png` | SQL column completion for the stg_orders relation. |
| [root-native-parameter-info.png](root-native-parameter-info.png) | `target/manual-qa-1.2.0/root-native-parameter-info.png` | Native parameter hint for money(amount, scale=2). |
| [quick-documentation.png](quick-documentation.png) | `target/manual-qa-1.1.0/screenshots/fixed-native-docs.png` | Native resource documentation popup. |
| [authoring-static-column-warning.png](authoring-static-column-warning.png) | `target/manual-qa-1.2.0/authoring-static-column-warning.png` | Editor warning for a column absent from the known relation. |
| [authoring-yaml-native-completion.png](authoring-yaml-native-completion.png) | `target/manual-qa-1.2.0/authoring-yaml-native-completion.png` | Native YAML key completion in a resource property file. |
| [authoring-yaml-invalid-diagnostic.png](authoring-yaml-invalid-diagnostic.png) | `target/manual-qa-1.2.0/authoring-yaml-invalid-diagnostic.png` | Structural YAML diagnostic for an invalid known value. |
| [root-wildcard-preview.png](root-wildcard-preview.png) | `target/manual-qa-1.2.0/root-wildcard-preview.png` | Intention preview replaces o.* with the known columns. |
| [authoring-create-review.png](authoring-create-review.png) | `target/manual-qa-1.2.0/authoring-create-review.png` | Create resource dialog showing the proposed model path and starter SQL. |
| [authoring-cte-rename-dialog.png](authoring-cte-rename-dialog.png) | `target/manual-qa-1.2.0/authoring-cte-rename-dialog.png` | Native Rename dialog for a local CTE. |
| [authoring-cte-safe-delete-conflict.png](authoring-cte-safe-delete-conflict.png) | `target/manual-qa-1.2.0/authoring-cte-conflict-jna-fixed.png` | Safe Delete reports a CTE usage that prevents removal. |
| [authoring-sqlfmt-jinja-reformat.png](authoring-sqlfmt-jinja-reformat.png) | `target/manual-qa-1.2.0/authoring-sqlfmt-jinja-reformat.png` | SQL/Jinja document formatted by sqlfmt in the native editor. |
| [authoring-sqlfluff-lint-jna-fixed.png](authoring-sqlfluff-lint-jna-fixed.png) | `target/manual-qa-1.2.0/authoring-sqlfluff-lint-jna-fixed.png` | SQLFluff capitalization diagnostic and source fix in the editor. |
| [sql-clipboard.png](sql-clipboard.png) | `target/manual-qa-1.1.0/screenshots/paste-as-refs-result.png` | Physical relation pasted back as a dbt reference. |
| [syntax-highlighting.png](syntax-highlighting.png) | `target/manual-qa-1.2.0/authoring-sqlfmt-jinja-reformat.png` | SQL keywords, Jinja delimiters and quoted resource name use distinct colors. |
| [resource-icons.png](resource-icons.png) | `target/manual-qa-1.2.0/authoring-yaml-native-completion.png` | Project tree and editor tabs display dbt resource icons. |
| [root-runner.png](root-runner.png) | `target/manual-qa-1.2.0/root-runner.png` | Command output for an example dbt scope; the recording executable supplies simulated results. |
| [selection-v7-downstream.png](selection-v7-downstream.png) | `target/manual-qa-1.2.0/selection-v7-downstream.png` | Local downstream selection review with matched resources and command preview. |
| [root-state-review.png](root-state-review.png) | `target/manual-qa-1.2.0/root-state-review.png` | Baseline review showing changes and deferred dependency information from fixture artifacts. |
| [root-typed-grid.png](root-typed-grid.png) | `target/manual-qa-1.2.0/root-typed-grid.png` | Typed grid containing simulated numbers, booleans, nulls and Unicode values. |
| [cte-native-adapter-sql-details.png](cte-native-adapter-sql-details.png) | `target/manual-qa-1.2.0/cte-native-adapter-sql-details.png` | Preview evidence separates submitted SQL, reported adapter SQL and raw output from a recording executable. |
| [cte-inspector-step-paid.png](cte-inspector-step-paid.png) | `target/manual-qa-1.2.0/cte-inspector-step-paid.png` | CTE inspection displaying the paid step with simulated rows. |
| [cte-lineage-filter.png](cte-lineage-filter.png) | `target/manual-qa-1.2.0/cte-lineage-filter.png` | Finance selection with dashed filtered-boundary resource cards. |
| [resource-documentation.png](resource-documentation.png) | `target/manual-qa-1.1.0/screenshots/docs-sql.png` | Resource SQL documentation with raw and compiled sections. |
| [root-column-paths-highlight.png](root-column-paths-highlight.png) | `target/manual-qa-1.2.0/root-column-paths-highlight.png` | Selected column path highlighted in the native resource documentation. |
| [root-flow-yaml-diff-fixed.png](root-flow-yaml-diff-fixed.png) | `target/manual-qa-1.2.0/root-flow-yaml-diff-fixed.png` | YAML diff preview of proposed authored property changes. |
| [root-tests-tree-expanded.png](root-tests-tree-expanded.png) | `target/manual-qa-1.2.0/root-tests-tree-expanded.png` | Native test tree with recorded simulated failure output. |
| [root-freshness-warn.png](root-freshness-warn.png) | `target/manual-qa-1.2.0/root-freshness-warn.png` | Source-freshness warning badge produced from simulated command artifacts. |
| [downstream-consumers-all.png](../../docs/screenshots/downstream-consumers-all.png) | `DbtConsumersPanelTest`, production panels and local fixtures | **All columns** with an exposure, a structured question, a native SQL question and a dashboard. |
| [downstream-consumers-column.png](../../docs/screenshots/downstream-consumers-column.png) | Same action and fixtures | **id** retains model-level consumers labeled **column use unknown**. |
| [downstream-consumers-refresh-error.png](../../docs/screenshots/downstream-consumers-refresh-error.png) | Same action and fixtures, HTTP 503 response | Refresh error with the previous consumers and refresh time retained. |
| [integrations-agent-stopped.png](integrations-agent-stopped.png) | `target/manual-qa-1.2.0/integrations-agent-stopped.png` | Local MCP server dialog in its stopped state, without an exposed token. |

| Source-mapped CTE inspection | Capture | Meaning |
| --- | --- | --- |
| [cte-source-breakpoint.png](../../docs/screenshots/cte-source-breakpoint.png) | `DbtInspectCtesActionTest` with the local Python dbt Core/DuckDB CLI | Native editor and Debug panels show a macro-call breakpoint and a generated CTE result. |
| [cte-compiled-step.png](../../docs/screenshots/cte-compiled-step.png) | Same session after Step Into | Read-only compiled SQL is selected without another inspection query. |
