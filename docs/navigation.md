# Navigation

[User guide](getting_started.md) · [Icons](icons.md)

Go to Declaration (Ctrl+Click) and Find Usages work on dbt references in SQL, Jinja,
YAML and Python. Targets respect the owning project, package and model version.

## Gutter markers and popups

Click the dbt gutter icon beside a resource or declaration to open its YAML
properties, source definitions, or **Uses** / **Used by** with reference locations.
Macro, test and docs declarations show their callers; unit tests link to their model
and fixture files. Markers also flag references with several definitions or
metadata-only targets; the tooltip explains ambiguity and staleness. Targets on the
same line share one popup.

Right-click in the editor and open **dbt** to find the same popups:

- **dbt Properties** opens the resource's YAML properties from its source file.
- **dbt Dependencies** groups dependencies under **Uses** and consumers under
  **Used by**. Choose a resource to open its declaration, or a `file:line` entry to
  open that reference.

<img src="screenshots/model-dependencies.png" alt="dbt Dependencies lists consumers and their reference locations" width="460">

## CTE structure and hierarchy

For a dbt SQL model, **Structure** and the **File Structure** popup list its CTEs
(named WITH queries), including nested ones. Select an entry to open its definition.
This runs no query.

**Navigate → Call Hierarchy** on a model or column opens **Upstream dependencies**
and **Downstream consumers** in the Hierarchy window. Column entries distinguish
value, join and filter inputs and mark incomplete origins. A cycle stops expanding
when it revisits a resource already in that branch.

**Navigate → Related Symbol** on a resource lists detected
[Airflow](integrations.md#inspect-airflow-context) DAG and task definitions.

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

Quoted and multiline YAML values work in these locations. Files are recognized in
each `dbt_project.yml`'s `model-paths`, `seed-paths`, `snapshot-paths`,
`macro-paths`, `test-paths`, `docs-paths` and supported function/check paths;
packages use `packages-install-path` and their own project names. `.git`, `.idea`,
`.venv`, `venv`, `logs`, `target-path`, other generated output and `.dbtignore`
matches are excluded; installed package sources are included. `.dbtignore` supports
ordered exclusions and re-inclusions, anchored paths, directories and wildcards;
generated paths cannot be re-included:

```gitignore
models/experimental/
!models/experimental/keep.sql
```

## Models, seeds, snapshots and sources

```sql
select *
from {{ ref('orders') }}
join {{ source('raw', 'events') }} e on true
```

`orders` opens the model; `ref()` also targets seeds and snapshots. In `source()`,
`raw` opens the source and `events` its table. Use YAML names even when `identifier`
sets a different warehouse name. YAML properties link back to source files.

<img src="screenshots/model-properties.png" alt="dbt Properties offers the YAML declarations of the orders model" width="640">

### Model versions

```yaml
# models/schema.yml
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

Unversioned references use `latest_version`; `defined_in` names the source file.

### Packages and other projects

Use the package's dbt project name: `ref('finance', 'orders')` or
`finance.format_amount()`. The package must be installed locally. Cross-project
references need a `dependencies.yml` project dependency and a public target.
Dependency entries open installed sources; versions and revisions can open
`package-lock.yml`.

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

`double` opens the macro and `value` its parameter; Find Usages lists callers.
Constants such as `model_name` and `'start_' ~ 'date'` also navigate. Assignments,
loop variables, macro/call-block parameters, `with` bindings, captures and namespace
attributes resolve within their scope; local names can shadow dbt context. Simple
wrapper macros connect literal arguments to inner `ref()`, `source()`, `var()`,
`doc()` or `function()` calls.

Jinja comments and raw blocks are ignored; active Jinja inside SQL comments counts.
Template `import`, `from`, `include` and `extends` have no target in standard dbt.

## Variables

```yaml
# dbt_project.yml
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

`start_date` opens the project-scoped definition. The fallback `'eu'` has no target.
`options` opens the dictionary; its members are not separate variables. Package
macros use the calling node's project variables. `builtins.var`, `builtins.ref` and
`builtins.source` bypass same-named user macros.

In Core 1.12+, variables can also be defined in a top-level `vars` mapping in
`vars.yml`; nonempty mappings in both files leave conflicting names unresolved.
To navigate run-specific values, see
[run-specific variables](configuration.md#run-specific-variables-and-selections).

## Documentation blocks

```yaml
models:
  - name: orders
    description: "{{ doc('orders_description') }}"
```

`orders_description` opens its `{% docs %}` block; Find Usages lists referencing
descriptions. `doc()` navigates only in resource descriptions, not in SQL or
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

Custom tests open their `{% test %}` block; builtin tests open documentation. Named
arguments open parameters; relationship targets and columns navigate separately.
Unit-test models, inputs and macro/variable overrides open their declarations.
CSV/SQL fixtures open files under `<test-path>/fixtures/`, and Find Usages lists
their references; inline rows have no file target. Supported syntax depends on the
[dbt version](configuration.md#engine-and-version).

## Configuration, profiles and dispatch

```jinja
{{ config(materialized='audit', schema='reporting') }}
{{ config.get('materialized') }}
{{ adapter.dispatch('format_amount', 'finance')(amount) }}
```

Configuration keys open project, folder, resource-property or inline settings.
Schema, alias and database settings can open `generate_*_name` macros; custom
materializations open their definitions. Dispatch uses `macro_namespace`,
`search_order` and the selected adapter, with default fallbacks and Redshift's
Postgres inheritance. [Select a profile, target and adapter](configuration.md#profiles-and-adapters)
to resolve dispatch and fields such as `target.schema`.

## Semantic Layer and resource ownership

```jinja
{{ Dimension('customer__country') }}
{{ Metric('revenue') }}
```

Standalone `semantic_models` and model-embedded definitions work in supported dbt
versions. Entity paths, dimensions, measures, metrics, inputs, expression aliases,
saved-query metrics and export owners open their definitions. Time-spine
`standard_granularity_column` opens the model column; exposure dependencies and
group names open their resources.

## User-defined functions

```jinja
{{ function('hash_customer') }}(customer_id)
```

`hash_customer` opens its source; **dbt Properties** opens YAML for SQL, Python and
JavaScript bodies. `overloads[].defined_in` opens additional bodies; SQL argument
names can open YAML declarations. Support depends on
[version, adapter and language](configuration.md#profiles-and-adapters).

## SQL names and columns

```sql
with recent_orders as (
  select order_id from {{ ref('orders') }}
)
select r.order_id as id from recent_orders r order by id
```

`recent_orders` opens the CTE, `r` its alias, and `id` in `order by` the selected
expression. Columns open YAML declarations, simple model projections or selected
[local metadata](configuration.md#local-metadata); ambiguous joins offer several
targets. Columns behind `SELECT *` are known only from declarations or metadata.
Qualify ambiguous columns with a table alias.

## Selectors

```yaml
# selectors.yml
selectors:
  - name: nightly
    definition: '+orders+'
  - name: finance_sources
    definition:
      method: source
      value: raw.*
```

Selector references open matching resources; wildcards can offer several targets.
Named selectors, nested unions, intersections, exclusions and graph operators expose
individual references. Inheritance uses YAML `method: selector`, or string
`selector:nightly` terms in Core 1.12+. YAML aliases are not expanded into
selections. [Selection files](configuration.md#run-specific-variables-and-selections)
support the same navigation; `state:` terms need a
[state manifest](configuration.md#local-metadata).

## Python models

Requires [Python support](installation.md#requirements).

```python
def model(dbt, session):
    orders = dbt.ref("orders", v=2)
    events = dbt.source("raw", "events")
    dbt.config(materialized="table")
    return orders
```

Calls must use the model's `dbt` parameter or a local alias of it; unrelated
objects and interpolated strings do not navigate. Python files have no Jinja
navigation.

## Information Schema references

In a dbt v2 check, Information Schema views and known columns open their metadata,
or builtin documentation when no metadata is available:

```sql
select name
from {{ info_schema('models') }}
where name = 'orders'
```

A literal model name such as `orders` opens the model when its condition identifies
a unique resource. See [dbt v2 Information Schema](configuration.md#dbt-v2-information-schema).

## When a reference has no target

Check that:

- the file is inside a project containing `dbt_project.yml` and in a
  [recognized path](#where-references-work);
- the resource is enabled and not excluded;
- the logical name, namespace and model version match the declaration;
- packages are installed locally, and cross-project dependencies are declared and
  their targets public;
- the [engine and version](configuration.md#engine-and-version) support the feature.

Names computed from query results, environment values or macro execution cannot be
followed; use a literal or a constant assignment.

When navigation offers several choices, pick one, or qualify the reference with its
project or package, version or table alias. Dependencies marked **candidate** are
ambiguous.
