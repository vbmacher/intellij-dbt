# Edit resource properties

[Capability index](README.md) · [User guide](../docs/editing.md#edit-and-generate-yaml-properties)

Edit authored properties in YAML and generate reviewed column changes.

## Contract

- **Inputs:** a local resource, current authored YAML and optional known-column
  synchronization or description inheritance.
- **Outputs:** direct navigation to its YAML properties, or a native diff containing
  a generated proposal that writes only after Apply and supports Undo.
- Code Generate and applicable Alt+Enter intentions use the same generation rules.
  No property form is required to edit descriptions, tags, tests or types.
- Custom fields and tests are preserved. Description inheritance fills only empty
  descriptions proven to come unchanged from one complete, unambiguous upstream
  column. The upstream description must be nonempty and contain no `{{` expressions.
- Parameterized tests, anchors, commented sequences and shared inherited
  model-version columns require direct YAML edits when generation cannot preserve them.
- Read-only resources and source/settings changes since proposal preparation must
  not be overwritten.

## Example

If fct_orders.id directly passes through stg_orders.id, Generate → Inherit dbt
Descriptions proposes its nonempty upstream description for an empty local one.
Review Current YAML / Generated YAML, then Apply.

## Interactions

[Column lineage](column-lineage.md) supplies inheritance evidence;
[resource documentation](resource-documentation.md) displays the authored result.
