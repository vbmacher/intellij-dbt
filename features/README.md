# Capability index

Current, user-visible capabilities of dbt Workspace. Each linked specification
owns its capability's inputs, outputs, invariants, failure boundaries and example.
The linked user guides own UI procedures; [Configuration](../docs/configuration.md)
owns settings and file syntax, [Installation](../docs/installation.md) owns environment
setup, and [Icons](../docs/icons.md) owns the icon rules. Implementation details,
proposals and decisions are outside these specifications.

## Project

| Capability | Usage reference |
| --- | --- |
| [Project context and local metadata](project-context.md) | [User guide](../docs/configuration.md) |
| [Project and integration configuration](configuration.md) | [User guide](../docs/configuration.md) |

## Navigation

| Capability | Usage reference |
| --- | --- |
| [Declaration navigation and Find Usages](navigation.md) | [User guide](../docs/navigation.md) |
| [Properties, dependencies and gutter navigation](properties-and-dependencies.md) | [User guide](../docs/navigation.md#gutter-markers-and-popups) |
| [Resource icons](resource-icons.md) | [User guide](../docs/icons.md) |

## Editing

| Capability | Usage reference |
| --- | --- |
| [dbt and SQL completion](completion.md) | [User guide](../docs/editing.md#completion-and-parameter-information) |
| [Macro parameter information](parameter-information.md) | [User guide](../docs/editing.md#completion-and-parameter-information) |
| [Quick documentation](quick-documentation.md) | [User guide](../docs/editing.md#quick-documentation) |
| [Reference, SQL and contract warnings](static-analysis.md) | [User guide](../docs/editing.md#editor-warnings) |
| [YAML completion and validation](yaml-authoring.md) | [User guide](../docs/editing.md#editor-warnings) |
| [Expand wildcard columns](wildcard-expansion.md) | [User guide](../docs/editing.md#expand-wildcard-columns) |
| [Create missing resources](resource-creation.md) | [User guide](../docs/editing.md#create-a-missing-resource) |
| [Rename dbt resources and SQL symbols](rename.md) | [User guide](../docs/editing.md#rename-and-safe-delete) |
| [Safe Delete](safe-delete.md) | [User guide](../docs/editing.md#rename-and-safe-delete) |
| [SQL formatting](sql-formatting.md) | [User guide](../docs/editing.md#sql-linting-and-formatting) |
| [SQLFluff inspection and fixes](sql-inspection.md) | [User guide](../docs/editing.md#sql-linting-and-formatting) |
| [SQL clipboard conversion](sql-clipboard.md) | [User guide](../docs/editing.md#sql-clipboard-conversion) |
| [SQL, YAML and Markdown template highlighting](syntax-highlighting.md) | [User guide](../docs/editing.md#syntax-highlighting) |

## Workspace

| Capability | Usage reference |
| --- | --- |
| [External command execution](external-command.md) | [User guide](../docs/configuration.md#executables-and-wrapper-scripts) |
| [Workspace commands and execution context](workspace.md) | [User guide](../docs/workspace.md#commands) |
| [Selection review](selection-review.md) | [User guide](../docs/workspace.md#review-a-selection-and-compare-a-baseline) |
| [Baseline comparison and deferral](state-comparison.md) | [User guide](../docs/workspace.md#review-a-selection-and-compare-a-baseline) |
| [Model, selected SQL and CTE previews](query-preview.md) | [User guide](../docs/workspace.md#preview-query-results) |
| [Step through CTE results](cte-inspection.md) | [User guide](../docs/workspace.md#inspect-cte-queries) |
| [Resource lineage graph](resource-lineage.md) | [User guide](../docs/workspace.md#lineage) |
| [Resource documentation](resource-documentation.md) | [User guide](../docs/editing.md#quick-documentation) |
| [Column lineage](column-lineage.md) | [User guide](../docs/editing.md#quick-documentation) |
| [Edit resource properties](documentation-editing.md) | [User guide](../docs/editing.md#edit-and-generate-yaml-properties) |
| [Test discovery, execution and failures](test-execution.md) | [User guide](../docs/workspace.md#tests) |
| [Build, test and freshness evidence](execution-evidence.md) | [User guide](../docs/workspace.md#build-and-freshness-badges) |

## Integrations

| Capability | Usage reference |
| --- | --- |
| [Downstream exposures and Metabase consumers](downstream-consumers.md) | [User guide](../docs/workspace.md#exposures) |
| [Automatic Airflow context](airflow-context.md) | [User guide](../docs/integrations.md#inspect-airflow-context) |
| [Local read-only MCP server](local-mcp-server.md) | [User guide](../docs/integrations.md#connect-an-ai-assistant-with-the-local-mcp-server) |

## Examples and screenshots

Capability specifications include examples. Navigation and editor captures are
listed in [screenshot sources](screenshots/README.md). Native command, selection,
documentation and debugger procedures are defined in the linked current guides.
