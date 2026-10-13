# Navigation

[User guide](README.md) · [Configuration](configuration.md) · [Icon legend](icons.md) · [Troubleshooting](troubleshooting.md)

Use **Ctrl+Click** or **Go to Declaration** on a name to open its definition, and
**Find Usages** on a definition to list its references. These examples extend the
[example project](../README.md#example-project). For dbt v2 checks and resources
without local source, see [local metadata](configuration.md#local-metadata). If a
reference has no target or several, see [troubleshooting](troubleshooting.md).

## Where references work

| Location | References you can follow |
| --- | --- |
| SQL models, macros, analyses, tests, snapshots and supported function/check bodies | dbt calls, Jinja names, applicable builtin context and SQL names |
| Resource descriptions in YAML | Docs blocks, variables and environment keys |
| Properties and configuration YAML | Variables, environment keys and applicable context fields |
| Hooks, test arguments and SQL expression fields | Applicable dbt calls, including relationship targets and snapshot relations |
| Unit-test and Semantic Layer expressions | Applicable resource and semantic references |
| `dbt_project.yml` outside hooks | Project context; variable navigation depends on version and explicit settings |
| Selected profiles and `packages.yml` | Environment keys and explicitly configured runtime variables |
| `dependencies.yml` and `package-lock.yml` | Declarative dependency references |
| Python models | Supported `dbt` API calls |

Quoted and multiline YAML values support navigation. Ordinary strings, variable
definition values, values in selected run files and unrelated YAML do not acquire
Jinja navigation merely because they contain a call.

## Models, seeds, snapshots and sources

```sql
select *
from {{ ref('orders') }}
join {{ source('raw', 'events') }} e on true
```

Click `orders` to open the model; a `ref()` can also target a seed or snapshot.
In `source('raw', 'events')`, `raw` opens the source declaration and `events` its
table declaration. Use the logical YAML names even when `identifier` supplies a
different warehouse table name.

A resource's YAML properties link back to its source file, and **dbt Properties**
opens the YAML entries from the resource file.

### Model versions

In `models/schema.yml`:

```yaml
models:
  - name: orders
    latest_version: 2
    versions:
      - v: 1
        defined_in: orders_previous
      - v: 2
```

```jinja
{{ ref('orders') }}                  {# opens orders_v2.sql #}
{{ ref('orders', v=1) }}             {# opens orders_previous.sql #}
{{ ref('finance', 'orders', v=2) }}  {# opens the finance version #}
```

Without an explicit version, navigation follows `latest_version`. A version's
`defined_in` points to its source file.

### Packages and other projects

Use the package's dbt project name as its namespace, such as
`ref('finance', 'orders')` or `finance.format_amount()`. Navigation uses locally
installed package files.

Cross-project references require a project dependency in `dependencies.yml` and a
public target. Dependency entries link to installed sources, and version/revision
entries can link to `package-lock.yml`.

## Macros and Jinja names

```jinja
{% macro double(value) %}
  {{ value * 2 }}
{% endmacro %}

{% set model_name = 'orders' %}
{{ double(value=2) }}
{{ ref(model_name) }}
{{ var('start_' ~ 'date') }}
```

Click `double` to open the macro and `value` to open its parameter; Find Usages on
the macro shows its callers. Simple constant expressions, such as `model_name` and
`'start_' ~ 'date'`, also navigate.

Assignments, loop variables, macro and call-block parameters, `with` bindings,
captures and namespace attributes link to their definitions in the applicable scope.
A local name can shadow a dbt context name. Simple wrapper macros connect a literal
caller argument to a `ref()`, `source()`, `var()`, `doc()` or `function()` inside
the wrapper.

Jinja comments and raw blocks do not count as usages; active Jinja inside SQL
comments does.

## Variables

In `dbt_project.yml`:

```yaml
name: analytics
vars:
  region: us
  start_date: '2026-01-01'
  analytics:
    start_date: '2026-02-01'
  options:
    include_archived: false
```

```jinja
{{ var('start_date') }}
{{ var('region', 'eu') }}
{{ var('options')['include_archived'] }}
```

`start_date` opens the project-scoped declaration. The fallback `'eu'` is a default
value with no definition target. `options` opens the dictionary variable; its members
are not separate dbt variables.

Root package-scoped defaults take precedence over root global defaults, which take
precedence over installed package defaults. A package macro uses variables from the
calling node's project. `builtins.var`, `builtins.ref` and `builtins.source` bypass
same-named user macro overrides.

For run-specific variables, environment keys and `vars.yml`, see
[local overrides](configuration.md#variables-and-environment).

## Documentation blocks

```yaml
models:
  - name: orders
    description: "{{ doc('orders_description') }}"
```

Click `orders_description` to open the matching `{% docs orders_description %}`
block. Find Usages on that block lists descriptions that reference it. `doc()`
navigation applies only to resource descriptions, not to SQL or
`config.meta.description`.

<img src="screenshots/doc-usages.png" alt="Find Usages for a documentation block" width="640">

## Data tests and unit tests

```yaml
models:
  - name: orders
    columns:
      - name: customer_id
        data_tests:
          - relationships:
              arguments:
                to: "ref('customers')"
                field: customer_id
unit_tests:
  - name: orders_example
    model: orders
    given:
      - input: ref('customers')
        format: csv
        fixture: customers_input
    expect:
      format: csv
      fixture: orders_expected
```

A custom test name opens its `{% test ... %}` block; a builtin test name opens its
documentation. Named arguments link to test parameters, and relationship targets and
columns navigate separately.

Unit-test models, inputs, and macro and variable overrides link to their declarations.
CSV/SQL fixtures link to files in the `fixtures/` directories of the configured test
paths, and Find Usages on a fixture file lists its references. Inline rows are data
and do not link to files. Test syntax support depends on the
[selected version](configuration.md#engine-and-version).

## Configuration, profiles and dispatch

```jinja
{{ config(materialized='audit', schema='reporting') }}
{{ config.get('materialized') }}
{{ adapter.dispatch('format_amount', 'finance')(amount) }}
```

Configuration keys link to applicable project, folder, resource-property or inline
settings. Schema, alias and database settings can also link to available
`generate_*_name` macros, and custom materialization names to their definitions.

Dispatch follows `macro_namespace`, `search_order` and the selected adapter, including
default fallbacks and Redshift's Postgres inheritance. Select the adapter, profile and
target in [configuration](configuration.md#profiles-and-adapters); the target also
supplies fields such as `target.schema`.

## Semantic Layer and resource ownership

```jinja
{{ Dimension('customer__country') }}
{{ Metric('revenue') }}
```

Standalone `semantic_models` and model-embedded semantic definitions both support
navigation for their applicable dbt versions. Entity paths, dimensions, measures,
metrics, metric inputs and expression aliases link to their definitions, as do
saved-query metrics and export owners. `standard_granularity_column` in a time-spine
definition links to the model column. Exposure dependencies and group names link to
the resources they describe.

## User-defined functions

```jinja
{{ function('hash_customer') }}(customer_id)
```

Click `hash_customer` to open the configured function source. **dbt Properties**
opens its YAML properties for SQL, Python and JavaScript bodies.
`overloads[].defined_in` links to additional bodies, and argument names in SQL
function bodies can link to their YAML declarations. Availability depends on the
selected version, adapter and language; see
[function compatibility](configuration.md#function-compatibility).

## SQL names and columns

```sql
with recent_orders as (
  select order_id from {{ ref('orders') }}
)
select r.order_id as id from recent_orders r order by id
```

Click `recent_orders` to open the CTE definition, `r` to open its alias, or `id` in
`order by` to open the selected expression. Columns can link to YAML declarations,
simple model projections or explicitly selected local metadata. Ambiguous joins can
offer several targets.

## Selectors

In `selectors.yml`:

```yaml
selectors:
  - name: nightly
    definition: '+orders+'
  - name: finance_sources
    definition:
      method: source
      value: raw.*
```

References in selector definitions navigate to matching resources; wildcards can
offer several matches. Named selectors, nested unions/intersections/exclusions and
graph operators expose their individual references. YAML `method: selector`
inheritance is supported, as are string `selector:nightly` terms in Core 1.12+.

`select`, `exclude` and `selector` fields in configured local selection files also
navigate. State-dependent selections require explicitly selected state metadata.

## Python models

With the free Python Community Edition plugin enabled:

```python
def model(dbt, session):
    orders = dbt.ref("orders", v=2)
    events = dbt.source("raw", "events")
    dbt.config(materialized="table")
    return orders
```

Calls through the model's `dbt` parameter or a local alias navigate. Unrelated
objects, shadowed names, interpolated strings and comments are not treated as dbt
calls.
