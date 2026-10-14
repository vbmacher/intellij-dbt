# Troubleshooting and limits

[User guide](README.md) · [Navigation](navigation.md) · [Configuration](configuration.md)

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

Enable the free Python Community Edition plugin. Calls must use the model's `dbt`
parameter or a local alias; unrelated objects and interpolated strings do not navigate.

Python files have no Jinja navigation. SQL, Jinja and YAML work without the Python plugin.

## A SQL column has no target

Declare the column, use a simple model projection or select fresh metadata.
Qualify ambiguous columns with a table alias.

Some SQL dialect syntax and dynamic queries are unsupported. `SELECT *` does not supply
missing declarations; the plugin does not query warehouse schemas.

## Local metadata is missing or stale

Check paths and [supported formats](configuration.md#local-metadata), refresh
artifacts and save source edits. Local source takes precedence over metadata.

Metadata does not prove warehouse existence or dbt's deferral choice.
State-dependent selections need sufficient information in the selected files.

## A selector or template import has no target

YAML aliases are not expanded into selections. Use `method: selector` for named
references; string `selector:nightly` terms require Core 1.12+.

Template `import`, `from`, `include` and `extends` have no target in standard dbt.

## Feature boundaries

Navigation and documentation browse local source and metadata. The [Runner](workspace.md#runner)
executes the configured local dbt CLI when a command button is clicked. Install dbt,
adapters and dependencies with your environment tools. Autocompletion and Rename
refactoring are outside the plugin's scope.
