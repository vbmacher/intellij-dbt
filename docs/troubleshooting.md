# Troubleshooting and limits

[User guide](getting_started.md) · [Navigation](navigation.md) · [Editing](editor.md) · [Configuration](configuration.md)

## A reference has no target

Check:

- The file is inside a project containing `dbt_project.yml` and a configured resource path.
- The resource is enabled, outside generated output and not excluded by `.dbtignore`.
- The logical name, namespace and model version match the declaration.
- Packages are installed locally; cross-project dependencies are declared and targets public.
- The [engine/version](configuration.md#engine-and-version) supports the feature.
- The call appears in a [supported location](navigation.md#where-references-work).

Names computed from query results, environment values or macro execution cannot
be followed; use a literal or a constant assignment.

## Navigation offers several choices

Choose a target, or qualify the reference with its project/package, version or
table alias. Names, versions, columns and semantic paths can match several declarations;
dependencies marked **candidate** are ambiguous.

## Python navigation is unavailable

Check the [Python prerequisite](installation.md). Calls must use the model's `dbt`
parameter or a local alias; unrelated objects and interpolated strings do not navigate.

Python files have no Jinja navigation. SQL, Jinja and YAML work without the Python plugin.

## A SQL column has no target

Declare the column, use a simple model projection or select fresh metadata.
Qualify ambiguous columns with a table alias.

Columns behind `SELECT *` are known only from declarations or metadata.

## Local metadata is missing or stale

Check paths and [supported formats](configuration.md#local-metadata), and whether
the metadata was generated for the current project and target. Local source takes
precedence over metadata.

## A selector or template import has no target

YAML aliases are not expanded into selections. Use `method: selector` for named
references; string `selector:nightly` terms require Core 1.12+.

Template `import`, `from`, `include` and `extends` have no target in standard dbt.

## Rename, deletion or wildcard expansion is unavailable

These actions need writable local declarations and a known, unambiguous column
list. Dynamic consumers and shared version declarations also block them; see
[Rename and Safe Delete](editor.md#rename-and-safe-delete).

## SQLFluff warnings disappear after editing

An edit makes the previous inspection result out of date. Follow the
[inspection workflow](editor.md#sql-linting-and-formatting) to inspect the current
source. If formatting fails, check the
[formatter configuration](configuration.md#sql-linting-and-formatting).

## Clipboard conversion leaves a relation unchanged

Conversion requires a unique match in documentation generated for the current
target; see [SQL clipboard conversion](editor.md#sql-clipboard-conversion).

## Lineage is blank or a command fails

Check the browser prerequisites in [installation](installation.md) and the
[Run](execution.md#commands) console.
