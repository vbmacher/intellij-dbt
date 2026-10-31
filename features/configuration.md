# Project and integration configuration

[Capability index](README.md) · [User guide](../docs/configuration.md)

Settings tell the plugin how to read and run your dbt projects. If one IDE project
contains several dbt projects, each keeps its own navigation and run settings.

## Contract

- **Inputs:** the owning dbt root (the directory containing `dbt_project.yml`), the
  **General** and **Integrations** tabs in **Settings → dbt Workspace**.
- **Outputs:** the values that navigation, commands, formatting, state comparison and
  integrations use.
- **Per-root settings:** engine, version, adapter, variables, environment overrides, local definitions, metadata paths,
  executable, profiles, target, formatter and state settings
  are saved separately for each owning root. Choosing another **Owning dbt root**
  shows that root's saved values and does not change any other root's values.
- **Shared settings:** lineage view and integration settings apply to the whole IDE
  project.
- **Environment files:**
  - By default, commands load `.env` from the owning root.
  - A nonempty **Environment files** setting replaces the default list.
  - Clear **Load environment files** to disable file loading.
  - Relative paths always resolve from the owning root.
  - Files are read again for every command. Later files override earlier ones, and
    values in **Environment variables** override all files. See
    [Environment files](../docs/configuration.md#environment-files)
    for the file syntax and full precedence.
- **Variables:** **Variables (JSON)** supplies typed values to dbt commands through
  `--vars` and overrides editor declarations. Invalid JSON or a value other than an
  object prevents **Apply** and command preparation. Configured names open read-only
  lists without their values; edits belong in IntelliJ settings.
- **Apply and Reset:** **Apply** checks values, such as allowed number ranges, before
  saving changes from both tabs. **Reset** puts the saved values back into both tabs.
- **No side effects:** changing settings does not run dbt, refresh Metabase data or
  start the local MCP server.
- **Credentials:** the Metabase API key is stored in IDE Password Safe, never in
  project files.
- Field names, defaults, limits and file examples are defined in
  [Configuration](../docs/configuration.md).

## Example

An IDE project contains `analytics/` and `finance/`, each with its own
`dbt_project.yml`. In **Settings → dbt Workspace**, select `analytics` as
**Owning dbt root** and set a wrapper script as the executable. Then select
`finance` and set a different executable. Selecting `analytics` again shows its
wrapper script. Both projects use the same lineage layout.

For `analytics`, enter `{"start_date":"2026-03-01"}` in **Variables (JSON)** and
add `DBT_SCHEMA` with value `analytics` in **Environment variables**, then click
**Apply**. Commands use that variable object and environment override; `finance`
keeps its own values.

## Screenshot

![dbt Workspace settings with per-root navigation fields.](../docs/screenshots/configuration.png)

The General tab for one owning root, showing variables, environment overrides and navigation settings.

## Interactions

Provides settings to [project discovery](project-context.md),
[workspace commands](workspace.md), [formatting](sql-formatting.md) and
[integrations](downstream-consumers.md). Each of those capabilities defines how it
uses the settings.
