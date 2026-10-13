# Troubleshooting and limits

[User guide](README.md) · [Navigation](navigation.md) · [Configuration](configuration.md)

## A reference has no target

Check that:

- The file belongs to a directory containing `dbt_project.yml` and lies in a
  configured resource path.
- The resource is enabled and is not excluded by `.dbtignore` or a generated-output path.
- The logical name, project/package namespace and model version match the declaration.
- Any referenced package is installed locally. A cross-project dependency is declared
  and its target is public.
- The [selected engine and version](configuration.md#engine-and-version) support the feature.
- The call is in a [supported location](navigation.md#documentation-and-yaml-fields).

Simple literal values, constant assignments and some wrapper calls can navigate.
Names that depend on query results, environment values, recursion or arbitrary macro
execution may remain unresolved.

## Navigation offers several choices

Several declarations can match the same name, version, SQL column or semantic path.
Choose the intended target, or make the reference more specific by naming its
project/package, version or table alias. A dependency marked **candidate** is ambiguous.

Resources from separate dbt projects retain their own identities, even when their
project names match.

## Python navigation is unavailable

Install and enable the free Python Community Edition plugin. Supported model calls
must use the model's `dbt` parameter or a local alias. Unrelated objects and interpolated
strings do not create dbt references.

Python files do not support Jinja navigation. SQL, Jinja and YAML navigation work
without the Python plugin.

## A SQL column has no target

Navigation needs a declared column, a simple model projection or fresh selected
metadata. Qualify names with a table alias when several relations have matching columns.

Some SQL dialect syntax and dynamic query structures are unsupported. `SELECT *`
does not supply missing column declarations. The plugin does not query the warehouse
to discover schemas.

## Local metadata is missing or stale

Check the configured paths and [supported metadata formats](configuration.md#local-metadata).
Use fresh artifacts and save source edits before relying on metadata columns or dependencies.
Local source takes precedence over metadata.

Metadata cannot confirm whether a resource exists in a warehouse or which source
dbt will choose during a deferred run. State-dependent selections remain unknown when
the selected files do not contain enough information.

## A selector or template import has no target

YAML selector aliases are not expanded into inferred selections. Named
`method: selector` references are supported; string `selector:nightly` terms
require Core 1.12+.

Template loader operations such as `import`, `from`, `include` and `extends`
have no navigation target in the standard dbt context.

## Feature boundaries

The plugin provides source navigation, reference search, properties navigation and
dependency browsing. It does not execute dbt, Jinja, Python or SQL; download packages
or remote metadata; query a warehouse; or provide SQL highlighting, completion,
data previews or Rename refactoring.

Use your normal dbt tools to run projects, install dependencies and generate artifacts.
