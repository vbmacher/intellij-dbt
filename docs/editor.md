# Editing dbt projects

[User guide](getting_started.md) · [Navigation](navigation.md) · [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md)

Editor features use local project files, including unsaved changes. Installed
packages and declarations available only through metadata are read-only.

## Completion and parameter information

Invoke **Code → Code Completion → Basic** inside a dbt call to suggest resource
names, macro names, configuration keys or named macro arguments:

```jinja
{{ ref('ord') }}
{{ source('raw', 'ev') }}
{{ format_amount(amount=order_total) }}
```

At `ord`, completion can suggest `orders`; after the source name `raw`, it offers
that source's tables. Suggestions include their package and model version where
applicable. Completion also supports `doc`, `var` and supported dispatch calls.
In SQL, type a relation qualifier such as `o.` to suggest its known columns.
Columns can come from YAML declarations, model outputs, subqueries or CTEs.
Selector YAML also offers supported resource, tag, package and named-selector terms.

Inside a macro call, **Parameter Info** shows argument names, defaults and any
documented types, and highlights the current argument:

```jinja
{% macro format_amount(amount, places=2) %}
  round({{ amount }}, {{ places }})
{% endmacro %}
```

For this macro, the hint shows `format_amount(amount, places=2)`. Documented types
help explain parameters; they do not validate the types of supplied values.

The `dbtref`, `dbtsource` and `dbtconfig` live templates insert a `ref`, `source`
or materialization configuration call through **Insert Live Template**.

## Quick documentation

Hover a model argument in `ref`, a table argument in `source`, or a macro name,
or invoke **Quick Documentation**. The popup includes descriptions, known columns
and dependencies. It shows up to 15 columns and eight dependencies in each
direction, with counts. See the [documentation sidebar](workspace.md#documentation)
for the full lists.

Builtin names such as `ref`, `source` and `var` open the official dbt reference
article in the Documentation tool window. Links to other dbt reference articles
stay in that window. Source headings show logical names such as
`analytics.raw.events`; known physical database, schema and relation names are
listed separately. To open local definitions instead, see
[local builtin definitions](configuration.md#local-builtin-definitions).

## Editor warnings

Literal unresolved `ref()` and `source()` arguments receive warnings. Unknown
sources and unknown tables have separate messages. Dynamic names, incomplete
calls and ambiguous matches do not produce missing-resource warnings.

SQL warnings identify column problems that can be resolved from local files.
For a model with `contract.enforced: true`, known outputs are checked against its
YAML column declarations. Missing declarations and incompatible known string,
integer or decimal types receive warnings. Missing column information or
unsupported SQL can prevent a warning; the editor does not query the warehouse.

Macro warnings identify unknown keyword arguments when one macro definition is
known and it does not accept arbitrary keywords. They do not check argument counts
or supplied value types.

For SQL warnings, open **Alt+Enter** and the
**Inspection 'dbt SQL, macro arguments and contracts' options** submenu. Choose
**Suppress for statement** or **Suppress for file**. Statement suppression adds
a comment before the SQL statement and applies through its semicolon or the end
of the file:

```sql
-- noinspection DbtStaticAnalysis
select o.external_column from {{ ref('orders') }} o;
```

File suppression adds `--file: noinspection DbtStaticAnalysis` before the first
statement. Remove the comment or use Undo to restore the warnings. These comments
apply to this inspection.

Project, dependency, selector and resource-property YAML files receive key/value
completion, hover help and structural warnings. Supported keys follow the
[engine and version](configuration.md#engine-and-version). Jinja values and custom
adapter configuration are accepted; unrelated and excluded YAML keeps its normal
editor behavior.

## Expand wildcard columns

Place the caret on `*` or `o.*` and choose
**Alt+Enter → Expand dbt SQL columns**. Review the proposed projection before
applying it. For known `order_id` and `customer_id` columns:

```sql
select o.* from {{ ref('orders') }} o
```

becomes:

```sql
select o.order_id, o.customer_id from {{ ref('orders') }} o
```

Expansion requires a complete, unambiguous column list and preserves its order
and quoting. `count(*)` has no expansion action.

## Rename and Safe Delete

**Refactor → Rename** updates resolving references to local models, macros, CTEs,
relation aliases and known SQL output columns. Review the usages and conflicts.
Model renames coordinate filenames and YAML properties while preserving warehouse
aliases and version `defined_in` mappings.

Renaming the output `order_id` to `id` in a simple projection can add an alias:

```sql
select order_id as id from {{ ref('orders') }}
```

The physical input column remains `order_id`; downstream references use `id`.
Columns declared on physical sources, seeds or snapshots require explicit source
edits, as do explicit CTE column lists and declarations shared by several model
versions.

**Safe Delete** checks usages before removing a model and its properties, a macro
block, or an unused CTE or projection. Removing the only projection is rejected
because it would leave invalid SQL. Dynamic consumers, ambiguous declarations,
incomplete column information, collisions and read-only files can prevent a
refactoring. Rename and deletion support Undo.

## Create a missing resource

For an unresolved literal `ref('new_orders')` or `source('raw', 'new_events')`,
choose **Alt+Enter → Create missing dbt resource**. Review the proposed path and
contents before applying. The action uses the owning project's resource paths;
it can add a table to an existing local source declaration.

Existing files are not overwritten. Names computed at runtime and declarations
belonging to installed packages have no creation action.

To edit descriptions, tags, tests and columns together, see
[documentation editing](workspace.md#documentation).

## SQL linting and formatting

Choose a formatter and executable in
[SQL linting and formatting configuration](configuration.md#sql-linting-and-formatting).
**Code → Reformat Code** formats the SQL/Jinja document in the background using
sqlfmt or SQLFluff. SQLFluff's `format` command applies only its formatting rules;
inspection applies the project's configured lint rules.

With SQLFluff selected, choose **Inspect dbt SQL** from Find Action or the editor
context menu. Messages appear as editor warnings or parse errors. Choose
**Alt+Enter → Apply SQLFluff … fix** to apply an offered fix. Edits invalidate the
inspection result; run the action again to refresh it.

Parse, templating and lexical errors disable automatic fixes for that result.
Fixes with incompatible filenames or source positions are not offered. Formatting
and fixes support Undo. Cancellation, tool errors, the formatter timeout and edits
made while formatting is running leave the document unchanged. When the
[format-on-save option](configuration.md#sql-linting-and-formatting) is enabled,
saving starts background formatting and saves the result only if the document
has not changed in the meantime.

## SQL clipboard conversion

Choose **Copy for Target DB** from Edit or the editor context menu, or use
**Ctrl+Shift+C** (**Cmd+Shift+C** on macOS). It copies the selection or whole
document without changing the source. Static `ref`, `source` and `this` references
become the target's physical relation names, including aliases and quoting:

```sql
select * from {{ ref('orders') }}
```

can copy as:

```sql
select * from "analytics"."reporting"."orders"
```

Other Jinja is wrapped in marked SQL comments. This conversion does not execute
macros or compile the model.

Choose **Paste as dbt Refs**, or use **Ctrl+Shift+V** (**Cmd+Shift+V** on macOS),
to replace the selection or insert at the caret. Two- or three-part table names
become refs or sources only when they uniquely match physical metadata. Package
names and explicit versions are retained. Unknown relations, ambiguous matches,
strings and ordinary comments stay unchanged. Marked Jinja comments from the
copy action are restored. Pasting supports Undo.

Conversion needs documentation generated for the current project and target; see
[Generate Docs](workspace.md#runner). If the manifest or execution configuration
changes, regenerate it before converting. The action warns when it cannot confirm
which project and target produced the metadata. Unsupported SQL remains unchanged.
Both shortcuts can be reassigned in **Settings → Keymap**.

## Syntax highlighting

SQL/Jinja, YAML/Jinja and Markdown/Jinja highlighting distinguishes SQL keywords,
template expressions, quoted strings and comments:

```sql
{# Use the selected dbt model. #}
{% set model_name = 'orders' %}
select * from {{ ref(model_name) }}
```

Colors follow the IDE theme, including Jinja in SQL strings and comments,
whitespace control and raw blocks. Highlighting applies to dbt resource paths
and project configuration. Generated output, ignored files and unrelated files
keep their existing highlighting. See [installation](installation.md) for the
TextMate prerequisite.
