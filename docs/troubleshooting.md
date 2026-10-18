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

Literals, constant assignments and simple wrappers can navigate. Names requiring
query results, environment values, recursion or macro execution may remain unresolved.

## Navigation offers several choices

Choose a target, or qualify the reference with its project/package, version or
table alias. Names, versions, columns and semantic paths can match several declarations;
dependencies marked **candidate** are ambiguous.

Separate dbt projects remain distinct even when their names match.

## Python navigation is unavailable

Check the [Python prerequisite](installation.md). Calls must use the model's `dbt`
parameter or a local alias; unrelated objects and interpolated strings do not navigate.

Python files have no Jinja navigation. SQL, Jinja and YAML work without the Python plugin.

## A SQL column has no target

Declare the column, use a simple model projection or select fresh metadata.
Qualify ambiguous columns with a table alias.

Some SQL dialect syntax and dynamic queries are unsupported. `SELECT *` does not supply
missing declarations; the plugin does not query warehouse schemas.

## Local metadata is missing or stale

Check paths and [supported formats](configuration.md#local-metadata), and whether
the metadata was generated for the current project and target. Local source takes
precedence over metadata.

Metadata does not prove warehouse existence or dbt's deferral choice.
State-dependent selections need sufficient information in the selected files.

## A selector or template import has no target

YAML aliases are not expanded into selections. Use `method: selector` for named
references; string `selector:nightly` terms require Core 1.12+.

Template `import`, `from`, `include` and `extends` have no target in standard dbt.

## Rename, deletion or wildcard expansion is unavailable

These actions need writable local declarations and enough column information to
make the change unambiguous. Dynamic consumers, unknown output columns, shared
version declarations and unsupported SQL can prevent a change. Review the
[editing limits and supported actions](editor.md#rename-and-safe-delete).

## SQLFluff warnings disappear after editing

An edit makes the previous inspection result out of date. Follow the
[inspection workflow](editor.md#sql-linting-and-formatting) to inspect the current
source. Tool failures and canceled formatting leave the source unchanged; check
the [formatter configuration](configuration.md#sql-linting-and-formatting).

## Clipboard conversion leaves a relation unchanged

Conversion requires a unique match in metadata generated for the current target.
Ambiguous or unknown relations and unsupported SQL remain as written. See
[SQL clipboard conversion](editor.md#sql-clipboard-conversion) for the metadata
requirements and warnings.

## Lineage is blank or a command fails

See the [Lineage limits](workspace.md#lineage) and [Runner output](workspace.md#runner).
Runtime and browser prerequisites are listed in [installation](installation.md).
