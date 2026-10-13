# Navigation

[User guide](README.md) · [Configuration](configuration.md) · [Icon legend](icons.md) · [Troubleshooting](troubleshooting.md)

Use **Ctrl+Click** or **Go to Declaration** on a name to open its definition.
Use **Find Usages** on a definition to find references to that resource. These examples
extend the [example project](../README.md#example-project).

## Models, seeds, snapshots and sources

```sql
select *
from {{ ref('orders') }}
join {{ source('raw', 'events') }} e on true
```

Click `orders` to open the model. A `ref()` can also target a seed or snapshot.
In `source('raw', 'events')`, `raw` opens the source declaration and `events`
opens the table declaration in YAML. Use the logical names from YAML even when
`identifier` supplies a different warehouse table name.

A model's YAML properties link back to its source file. **dbt Properties** opens
the corresponding YAML entries from a resource file.

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

Use the package's dbt project name as its namespace, for example
`ref('finance', 'orders')` or `finance.format_amount()`. Install packages with
your usual dbt workflow; the plugin uses the locally available files.

Cross-project references require a declared project dependency in `dependencies.yml`
and a public target. Dependency entries link to locally installed sources, and
version/revision entries can link to `package-lock.yml`.

## Macros and Jinja names

```jinja
{% macro double(value) %}
  {{ value * 2 }}
{% endmacro %}
```

In `double(value=2)`, click `double` to open the macro and `value` to open
its parameter. Find Usages on the macro shows its callers.

Assignments, loop variables, macro and call-block parameters, `with` bindings,
captures and namespace attributes link to their definitions in the applicable scope.
A local name can shadow a dbt context name.

Simple constant expressions can also navigate:

```jinja
{% set model_name = 'orders' %}
{{ ref(model_name) }}
{{ var('start_' ~ 'date') }}
```

Simple wrapper macros can connect a literal caller argument to a `ref()`, `source()`,
`var()`, `doc()` or `function()` inside the wrapper. Values that depend on queries,
the environment or arbitrary macro execution may have no target.

Jinja comments and raw blocks do not count as usages. Active Jinja inside SQL comments
does count. Template loader operations such as `import`, `from`, `include`
and `extends` have no dbt navigation target.

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

`start_date` opens the project-scoped declaration. The fallback `'eu'` is a
default value, so it has no variable-definition target. `options` opens the
dictionary variable; its members are not separate dbt variables.

Root package-scoped defaults take precedence over root global defaults, which take
precedence over installed package defaults. A package macro uses variables from the
calling node's project. `builtins.var`, `builtins.ref` and `builtins.source`
bypass same-named user macro overrides.

See [local overrides](configuration.md#variables-and-environment) for run-specific
variables, environment keys and `vars.yml`.

## Documentation and YAML fields

In a resource's YAML description:

```yaml
models:
  - name: orders
    description: "{{ doc('orders_description') }}"
```

Click `orders_description` to open the matching `{% docs orders_description %}`
block. Find Usages on that block lists descriptions that reference it.

<img src="screenshots/doc-usages.png" alt="Find Usages for a documentation block" width="640">

`doc()` navigation applies to resource descriptions. A `doc()` in SQL or in
`config.meta.description` has no documentation target.

Supported references depend on the field:

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

Click a custom test name to open its `{% test ... %}` block, or a builtin test
name to open its documentation. Named arguments link to test parameters.
Relationship targets and columns navigate separately.

Unit-test model and input references link to their resources. CSV/SQL fixtures link
to files in the configured test paths' `fixtures/` directories. Find Usages on a
fixture file lists its references. Inline rows are data, so they do not link to files.
Unit-test macro and variable overrides link to their declarations.

Check the [version table](configuration.md#engine-and-version) for test syntax support.

## Configuration, profiles and dispatch

```jinja
{{ config(materialized='audit', schema='reporting') }}
{{ config.get('materialized') }}
{{ adapter.dispatch('format_amount', 'finance')(amount) }}
```

Configuration keys link to applicable project, folder, resource-property or inline
settings. Schema, alias and database settings can also link to available
`generate_*_name` macros. Custom materialization names link to their definitions.

Dispatch follows `macro_namespace`, `search_order` and the selected adapter,
including default fallbacks and Redshift's Postgres inheritance. If the adapter is
unknown, navigation can offer several candidates.

Select a local profile and target in [configuration](configuration.md#profiles-and-adapters)
to follow fields such as `target.schema`.

## Semantic Layer and resource ownership

Both standalone `semantic_models` and model-embedded semantic definitions support
navigation for their applicable dbt versions.

```jinja
{{ Dimension('customer__country') }}
{{ Metric('revenue') }}
```

Entity paths, dimensions, measures, metrics, metric inputs and expression aliases link
to their definitions. Saved-query metrics and export owners also navigate.
`standard_granularity_column` in a time-spine definition links to the model column.
Exposure dependencies and group names link to the resources they describe.

## User-defined functions

```jinja
{{ function('hash_customer') }}(customer_id)
```

Click `hash_customer` to open the configured function source. **dbt Properties**
opens its YAML properties for SQL, Python and JavaScript bodies.
`overloads[].defined_in` links to additional bodies. Argument names in SQL function
bodies can link to their YAML declarations.

Function navigation depends on the selected dbt version, adapter and language.
See [function compatibility](configuration.md#function-compatibility).

## SQL names and columns

```sql
with recent_orders as (
  select order_id from {{ ref('orders') }}
)
select r.order_id as id from recent_orders r order by id
```

Click `recent_orders` to open the CTE definition, `r` to open its alias, or
`id` in `order by` to open the selected expression. Columns can link to YAML
declarations, simple model projections or explicitly selected local metadata.

Ambiguous joins can offer several targets. Unsupported SQL syntax, unknown columns
and dynamic query structure may have no target. `SELECT *` does not create
column declarations.

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

Select a reference in a selector definition to navigate to matching resources.
Wildcards can offer several matches. Named selectors, nested unions/intersections/
exclusions and graph operators expose their individual references.

YAML `method: selector` inheritance is supported; string `selector:nightly`
terms require Core 1.12+. YAML aliases are not expanded into inferred selections.
State-dependent selections require explicitly selected state metadata.
`select`, `exclude` and `selector` fields in configured local selection files
also support navigation.

## Python models

Enable the free Python Community Edition plugin to navigate these calls:

```python
def model(dbt, session):
    orders = dbt.ref("orders", v=2)
    events = dbt.source("raw", "events")
    dbt.config(materialized="table")
    return orders
```

Calls through the model's `dbt` parameter or a local alias can navigate. Unrelated
objects, shadowed names, interpolated strings and comments are not treated as dbt calls.
Python files do not support Jinja navigation. SQL, Jinja and YAML navigation remain
available when the Python plugin is absent.

For dbt v2 checks and resources with no local source, see
[local metadata](configuration.md#local-metadata).
