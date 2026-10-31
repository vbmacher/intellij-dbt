# Declaration navigation and Find Usages

[Capability index](README.md) · [User guide](../docs/navigation.md)

Follow a dbt name to its declaration and find the local references that resolve to it.

## Contract

- **Inputs:** a reference or declaration in a supported SQL, Jinja, YAML or Python location and its [project context](project-context.md).
- **Outputs:** a source declaration, a chooser for several candidates, a labeled metadata target, or builtin documentation. Find Usages returns matching reference locations.
- Supported names include models and versions, seeds, snapshots, source tables, macros, parameters, Jinja locals, variables, environment keys, docs blocks, tests and fixtures, configuration and dispatch, Semantic Layer resources, groups, UDFs, SQL CTEs/aliases/columns, selectors and dbt v2 Information Schema names.
- Exact supported locations and examples are owned by [Navigation](../docs/navigation.md#where-references-work); [version and adapter gates](../docs/configuration.md#engine-and-version) apply.
- Scope, ownership and explicit versions are preserved. An unknown explicit model version remains unresolved.
- A runtime expression without a statically established value has no invented target. `doc()` references are recognized in resource descriptions, not arbitrary SQL.

## Example

Given `models/orders.sql` and a consumer:

```sql
select * from {{ ref('orders') }}
```

Following `orders` opens the model. Find Usages on that model lists the resolving SQL and YAML references. More examples for every supported name family are in [Navigation](../docs/navigation.md).

## Screenshot

![Find Usages lists references to the orders model.](screenshots/model-usages.png)

Find Usages lists references to the orders model.

## Interactions

[Properties and dependencies](properties-and-dependencies.md) present related declarations. [Refactoring](rename.md) uses resolving references when proposing edits.
