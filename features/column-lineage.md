# Column lineage

[Capability index](README.md) · [User guide](../docs/editing.md#quick-documentation)

Trace which upstream columns supply a model column and which downstream columns
use it, without running dbt or a warehouse query.

## Contract

- **Inputs:** enabled SQL models, including unsaved edits, resolved resource
  references and known column declarations or metadata.
- **Outputs:** upstream and downstream column dependencies, dependency kind,
  expression, direct/derived status, completeness and multi-step paths.
- Native Documentation, Call Hierarchy, Lineage column details and the local MCP
  server expose this column knowledge. Their controls are owned by the linked
  [user guide](../docs/editing.md#quick-documentation) and
  [Workspace](../docs/workspace.md#resource-documentation).
- CTE value inputs resolve to upstream resource columns rather than CTE names.
  A value is a direct passthrough only if every intermediate CTE copies it unchanged.
- Filter and join inputs are associated with every output column of the model's
  final SELECT. Context inside intermediate CTEs is not included. GROUP BY and
  ORDER BY columns are not separate dependencies.
- Sources, seeds and snapshots appear as upstream column definitions; their SQL
  is not analyzed as a model.
- Unknown or ambiguous input columns are omitted and completeness remains
  explicit. A partial dependency list does not prove that other inputs are absent.
- Paths preserve dependency kind, expression and completeness. A branch stops
  expanding when it revisits a column. Available source definitions remain navigable.

## Dependency labels

| Label | Meaning |
| --- | --- |
| `value · direct passthrough` | The value is copied unchanged, including a simple alias such as `o.id as order_id`. |
| `value · derived` | An expression changes the value: arithmetic, aggregation, CASE or casts. UNION expressions use the label `set operation`. |
| `filter · derived · filter dependency` | A WHERE or HAVING input determines which rows remain. |
| `join · derived · join dependency` | A JOIN ON input determines how rows match. |
| `partial analysis` | The known inputs may be incomplete because columns or dependencies could not be resolved. |

## Example

For model `fct_orders` in project `my_project`:

```sql
select o.id, o.amount * 2 as gross_amount
from {{ ref('stg_orders') }} o
where o.status = 'paid'
```

`id` directly passes through `stg_orders.id`; `gross_amount` derives its value from
`stg_orders.amount`. Both depend on `stg_orders.status` as a filter:

| Output | Upstream dependencies |
| --- | --- |
| `id` | `my_project.stg_orders.id · value · direct passthrough · o.id`<br>`my_project.stg_orders.status · filter · derived · filter dependency` |
| `gross_amount` | `my_project.stg_orders.amount · value · derived · o.amount * 2`<br>`my_project.stg_orders.status · filter · derived · filter dependency` |

A complete, unambiguous passthrough can supply an inherited description for `id`.
The calculated `gross_amount` cannot inherit one. The full eligibility rules are
owned by [property editing](documentation-editing.md).

## Interactions

[Navigation](navigation.md) resolves column definitions.
[Property generation](documentation-editing.md) uses proven passthroughs to inherit
descriptions. [Local MCP access](local-mcp-server.md) exposes column links and paths
with explicit partial-result status.
