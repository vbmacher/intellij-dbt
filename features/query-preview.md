# Model, selected SQL and CTE previews

[Capability index](README.md) · [User guide](../docs/workspace.md#preview-query-results)

Inspect a bounded query result without editing the model source.

## Contract

- **Inputs:** one model, selected SELECT query or supported CTE, owning execution context and row limit.
- **Outputs:** returned rows with available value types, target, elapsed time, limit/truncation context and SQL/raw-output evidence.
- Querying is explicit and shares the [managed command lifecycle](workspace.md). Local sorting/filtering/copy/export operate on returned rows only.
- The model gutter and editor menu offer a whole-model preview. Failed CTE analysis
  offers that preview explicitly; canceling the dialog runs nothing.
- Exporting over an existing file requires confirmation before replacement.
- Reaching the row limit is labeled as potentially truncated. SQL null and the string `NULL` remain distinct when exported.
- Model previews require a dbt executable supporting `show`; selected SQL and CTE previews require Core 1.5+ and supported query structure.
- Selected SQL and CTE previews require a local model. They cannot preserve an
  installed package's context; a whole-model preview remains available instead.
- Available adapter SQL is shown only when it can be tied to the invocation/query; submitted SQL and raw output remain distinct. Error navigation requires an exact unchanged source mapping. Otherwise the location is unavailable.
- CTE extraction rejects recursive CTEs, explicit CTE column lists and Jinja setup
  it cannot preserve before starting a preview query. These extraction restrictions
  do not apply to whole-model previews; see [Workspace](../docs/workspace.md#preview-query-results).

## Example

```sql
with paid as (
  select id, amount from {{ ref('orders') }} where status = 'paid'
)
select * from paid
```

Previewing `paid` submits that CTE with its required predecessors and displays at most the chosen limit. Filtering `Unicode` in the grid filters only the rows already returned.

## Interactions

[CTE inspection](cte-inspection.md) coordinates several previews. [Test execution](test-execution.md) shares managed execution.
