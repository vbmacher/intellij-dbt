# Column lineage

[Capability index](README.md) · [User guide](../docs/editor.md#quick-documentation)

## What it does

For each column of a dbt model, column lineage answers two questions:

- Which upstream columns does this column come from?
- Which downstream columns use this column?

The plugin finds the answer by reading the model's SQL, including unsaved
edits. It does not run dbt or the query.

## Where you can see it

- Native resource Documentation: click a column name for its direct upstream
  inputs and downstream consumers.
- Native Call Hierarchy on SQL or YAML column references/declarations: expand
  upstream/downstream paths and open their source definitions.
- Generate → Inherit dbt Descriptions: propose descriptions only for proven
  unchanged passthroughs. See [property editing](documentation-editing.md).
- The local MCP server's get_column_lineage tool exposes links and paths for
  an AI assistant.

## Example

Model `fct_orders` in project `my_project`:

```sql
select o.id, o.amount * 2 as gross_amount
from {{ ref('stg_orders') }} o
where o.status = 'paid'
```

- `id` is copied from `stg_orders.id`.
- `gross_amount` is calculated from `stg_orders.amount`.
- `stg_orders.status` does not give a value to any column, but the `where`
  decides which rows are kept. So it is listed under **both** columns as a
  filter.

Column Documentation and Hierarchy label upstream inputs as follows:

| Column | Links shown |
| --- | --- |
| `id` | `my_project.stg_orders.id · value · direct passthrough · o.id`<br>`my_project.stg_orders.status · filter · derived · filter dependency` |
| `gross_amount` | `my_project.stg_orders.amount · value · derived · o.amount * 2`<br>`my_project.stg_orders.status · filter · derived · filter dependency` |

Choose an entry in Hierarchy to open that upstream column definition.

## What the labels mean

Each link reads `<project>.<resource>.<column> · <labels> · <SQL>`.

| Labels | Meaning | SQL that produces it |
| --- | --- | --- |
| `value · direct passthrough` | The value is copied unchanged. | Just a column, with or without a new name: `o.id`, `o.id as order_id`. |
| `value · derived` | The value is calculated. | Anything else: `o.amount * 2`, `sum(o.amount)`, `case ...`, `cast(...)`. For `union` / `union all` the SQL part is `set operation`. |
| `filter · derived · filter dependency` | The column decides which rows are kept. | The column appears in `where` or `having`. |
| `join · derived · join dependency` | The column decides how rows are matched. | The column appears in `join ... on`. |
| `partial analysis` (added to any of the above) | The list may be incomplete. | The plugin could not find a column used in the SQL, or does not know all columns of an upstream model or source. |

A filter or join column is listed under **every** column of that `select`,
because it affects every row.

## Column paths

Expand native Call Hierarchy to follow paths upstream or downstream. Each edge
identifies its dependency kind, direct or derived value, SQL expression and
partial-analysis status. A branch stops expanding when it revisits a column.
Opening a node navigates to its authored definition when available.

## Limits

- Only enabled models written in SQL are read. Sources, seeds and snapshots
  appear only as upstream columns.
- CTEs are followed to the real upstream model. A CTE name is never shown as
  the source. A column is `direct passthrough` only if every CTE copies it
  unchanged.
- Filters and joins are listed only from the model's last `select`, not from
  inside CTEs.
- Columns in `group by` and `order by` are not listed.
- If the plugin cannot find a column, it leaves it out. It never guesses.
- To fix `partial analysis`, declare the upstream columns in YAML or generate
  current dbt documentation.

## Copying descriptions

A column with an empty description can copy the description of its upstream
column. This works only when:

- the column is copied unchanged (`direct passthrough`), with no
  `partial analysis`;
- it comes from exactly one upstream column;
- that upstream column has a description, and the description has no Jinja
  (`{{`).

In the example, `id` can copy the description of `stg_orders.id`;
`gross_amount` cannot, because it is calculated. See
[documentation editing](documentation-editing.md).

## Related features

- [SQL name resolution](navigation.md) finds the upstream columns.
- [Property generation](documentation-editing.md) copies proven descriptions.
- The [local MCP server](local-mcp-server.md) gives the same data to AI assistants.
