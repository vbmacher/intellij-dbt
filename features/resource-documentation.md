# Resource documentation

[Capability index](README.md) · [User guide](../docs/editor.md#quick-documentation)

Read resource properties through native Quick Documentation or the Lineage details pane.

## Contract

- **Inputs:** a referenced, declared or graph-selected resource; authored properties,
  resolved docs blocks and applicable metadata.
- **Outputs:** full known columns and dependencies, descriptions, attached test
  declarations, logical/physical names and detected Airflow context.
- Editor references, declaration targets and graph selections use the same
  documentation content. Descriptions render sanitized Markdown with HTTP/HTTPS
  links. Refreshing the graph updates open details without reopening a closed pane.
- Authored source remains authoritative. Inferred columns do not silently become
  authored YAML. Tests listed here are declarations, separate from execution results.
- Column links open native column documentation with upstream/downstream inputs.
  In Lineage, column links stay in its [details pane](../docs/workspace.md#resource-documentation).
  Native Call Hierarchy expands dependency paths and opens source definitions.
- Airflow context is omitted when the resource has no detected Airflow tasks.
- Compiled SQL opens through native editor and diff actions. Unavailable compiled
  SQL offers an explicit Compile and open/compare action for a source model.
  It opens matching SQL only after successful compilation; source SQL is never
  presented as an invented compilation.

## Example

Open Documentation for orders. Its full known column list includes id and amount.
Click amount to inspect value, filter and join dependencies. Use Call Hierarchy
from a column declaration to expand its upstream paths.

## Interactions

[Quick documentation](quick-documentation.md) provides editor access;
[property editing](documentation-editing.md) makes reviewed source changes;
[column lineage](column-lineage.md) defines dependency meaning.
