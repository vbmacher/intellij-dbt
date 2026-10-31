# Editing

[User guide](getting_started.md)

Editor features use local project files, including unsaved changes. Installed
packages and declarations known only from metadata are read-only.

## Completion and parameter information

Press **Ctrl+Space** inside a dbt call to complete resource names, macro names,
configuration keys or named macro arguments:

```jinja
{{ ref('ord') }}
{{ source('raw', 'ev') }}
{{ format_amount(amount=order_total) }}
```

At `ord`, completion can suggest `orders`; after the source name `raw`, it offers
that source's tables. Suggestions include their package and model version where
applicable. Completion also works in `doc`, `var` and supported dispatch calls. In
SQL, type a relation qualifier such as `o.` to list its known columns from YAML
declarations, model outputs, subqueries or CTEs. Selector YAML offers resource, tag,
package and named-selector terms.

Inside a macro call, **Parameter Info** shows argument names, defaults and documented
types, and highlights the current argument. For this macro it shows
`format_amount(amount, places=2)`:

```jinja
{% macro format_amount(amount, places=2) %}
  round({{ amount }}, {{ places }})
{% endmacro %}
```

**Insert Live Template** offers `dbtref`, `dbtsource` and `dbtconfig`, which insert a
`ref`, `source` or materialization `config` call.

## Quick documentation

Hover a model name in `ref`, a table name in `source` or a macro name, or press
**Ctrl+Q** (macOS: **F1**) on it. Resource documentation lists the description,
known columns, dependencies, attached tests and detected
[Airflow](integrations.md#inspect-airflow-context) selections, exclusions and
schedules. Click a column name to see its upstream and downstream dependencies, with
value, join and filter dependencies labeled separately. You can keep these details
in the Documentation tool window.

Builtin names such as `ref`, `source` and `var` show the official dbt reference
article; links to other dbt reference articles stay in the Documentation window.
Source headings show logical names such as `analytics.raw.events`, with known
physical database, schema and relation names listed separately. To open local
definitions of builtins instead, see
[local builtin definitions](configuration.md#local-builtin-definitions).

## Edit and generate YAML properties

**dbt → Edit dbt Properties YAML** (editor context menu) opens the resource's
authored YAML, where descriptions, tags, data tests, types and custom fields stay editable.

**Code → Generate → dbt Columns** adds missing known column declarations.
**Inherit dbt Descriptions** fills empty descriptions, only for proven single-column
passthroughs. Both are also **Alt+Enter** intentions. Review the **Current YAML** /
**Generated YAML** diff, then click **Apply**. Changes support Undo; if sources or
settings change, review again.

Existing custom fields and tests are kept. Parameterized tests, YAML anchors,
commented sequences and inherited model-version columns may need direct edits; the
generator explains when it cannot preserve them.

### Background resource resolution

Resource actions resolve the selected resource in a background task. You can cancel
the task from the IDE's progress indicator. If source files, settings or the selected
resource change during resolution, the action stops and a notification explains
why. Run the action again to use the current context.

## Editor warnings

| Warning | When |
| --- | --- |
| Unresolved reference | A literal `ref()` or `source()` argument has no target; unknown sources and unknown tables have separate messages. Names built at runtime are not checked. |
| SQL column | A column problem that local files can resolve. |
| Model contract | For `contract.enforced: true`, a known output column is missing from YAML, or its known string, integer or decimal type is incompatible. |
| Macro argument | An unknown keyword argument, when the macro has a single definition whose body does not use `kwargs`. Argument counts and value types are not checked. |
| YAML structure | Invalid structure in project, dependency, selector and resource-property YAML, following the [engine and version](configuration.md#engine-and-version). Jinja values and custom adapter configuration are accepted. |

These YAML files also get key and value completion and hover help.

To suppress SQL, macro-argument and contract warnings, press **Alt+Enter** and open
the **Inspection 'dbt SQL, macro arguments and contracts' options** submenu.
**Suppress for statement** adds a comment that applies until the statement's
semicolon or the end of the file:

```sql
-- noinspection DbtStaticAnalysis
select o.external_column from {{ ref('orders') }} o;
```

**Suppress for file** adds `--file: noinspection DbtStaticAnalysis` before the first
statement. Delete the comment to restore the warnings.

## Expand wildcard columns

Put the caret on `*` or `o.*`, press **Alt+Enter** and choose
**Expand dbt SQL columns**. Review the preview before applying. With known
`order_id` and `customer_id` columns:

```sql
select o.* from {{ ref('orders') }} o
-- becomes
select o.order_id, o.customer_id from {{ ref('orders') }} o
```

Expansion needs a complete, unambiguous column list and keeps column order and
quoting. `count(*)` is not expanded. For unknown columns, an ambiguous alias or an
`EXCEPT`, `EXCLUDE` or `REPLACE` modifier (such as `select * exclude id`), the
action explains what to change and leaves the SQL unchanged: write the columns
explicitly, remove the modifier, or declare columns in YAML or run
[Generate Docs](workspace.md#commands).

## Rename and Safe Delete

**Refactor → Rename** updates references to local models, macros, CTEs, relation
aliases and known SQL output columns; review usages and conflicts. A model rename
also renames the file and its YAML properties while keeping warehouse aliases and
version `defined_in` mappings. Renaming the output `order_id` to `id` in a simple
projection adds an alias, so downstream references use `id`:

```sql
select order_id as id from {{ ref('orders') }}
```

Columns declared on sources, seeds or snapshots, explicit CTE column lists and
declarations shared by several model versions need manual edits.

**Safe Delete** checks usages before removing a model and its properties, a macro
block, or an unused CTE or projection. Removing the only projection is rejected.

Dynamic consumers, ambiguous declarations, unknown columns, name collisions and
read-only files block both refactorings. Both support Undo.

## Create a missing resource

On an unresolved literal `ref('new_orders')` or `source('raw', 'new_events')`, press
**Alt+Enter** and choose **Create missing dbt resource**. Review the proposed path
and contents before applying. The file goes into the owning project's resource
paths; a missing source table can be added to an existing local source declaration.
Existing files are never overwritten. Runtime-computed names and installed packages
have no creation action.

## SQL linting and formatting

First choose a [SQL formatter and executable](configuration.md#sql-formatter).

- **Code → Reformat Code** formats the SQL/Jinja document in the background with
  sqlfmt or SQLFluff. SQLFluff `format` applies only its formatting rules. If
  formatting fails or the document changes meanwhile, the document stays unchanged.
- With SQLFluff, **Inspect dbt SQL** (Tools menu or editor context menu under **dbt**)
  shows the project's lint results as editor warnings or errors. **Alt+Enter → Apply SQLFluff
  … fix** applies an offered fix; no fixes are offered while the file has parse or
  templating errors. Editing clears the results; run the action again.
- With [**Format dbt SQL on save**](configuration.md#sql-formatter), saving formats the file in the background.

Formatting and fixes support Undo.

## SQL clipboard conversion

In a SQL editor, **Copy for Target DB** (Edit menu or editor context menu under
**dbt**) copies the selection or whole document without changing it. Static
`ref`, `source` and `this` references become physical relation names, including
aliases and quoting; other Jinja is wrapped in marked SQL comments:

```sql
select * from {{ ref('orders') }}
-- copies as
select * from "analytics"."reporting"."orders"
```

**Paste as dbt Refs** (same menus) replaces the
selection or inserts at the caret. Two- or three-part table names become `ref` or
`source` calls only when they uniquely match physical metadata; package names and
explicit versions are kept and other text is unchanged. Marked Jinja comments from
the copy action are restored. Pasting supports Undo.
Both actions have no default shortcut; assign one in **Settings → Keymap** if needed.

Both actions need [Generate Docs](workspace.md#commands) output for the current
project and target, and warn when the metadata may belong to another project or
target. Reassign the shortcuts in **Settings → Keymap**.

## Syntax highlighting

dbt SQL/Jinja, YAML/Jinja and Markdown/Jinja files in dbt resource paths and project
configuration are highlighted with the IDE theme colors, including Jinja inside SQL
strings and comments, whitespace control and raw blocks. Requires
[TextMate Bundles](installation.md#requirements).

```sql
{# Use the selected dbt model. #}
{% set model_name = 'orders' %}
select * from {{ ref(model_name) }}
```
