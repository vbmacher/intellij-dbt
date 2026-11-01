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
  are saved separately for each owning root. Switching roots preserves each root's
  unsaved draft and restores it when that root is selected again.
- **Shared settings:** lineage view and integration settings apply to the whole IDE
  project.
- **Resolved context:** the current draft can be inspected through the same profile,
  environment and executable resolution rules used for commands. Missing files and
  unresolved defaults remain explicit. This view does not save settings or start a
  process, and does not repeat environment, variable or wrapper argument values.
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
  saving changes from both tabs and all owning roots. Invalid entries retain their
  draft values, including incomplete and duplicate table rows, and identify their
  owning root and field. Environment names must be valid and unique; builtin mappings
  require a unique name and source file. Unsupported active options prevent saving.
  **Reset** puts the saved values back into both tabs.
- **External activity:** editing or applying settings does not run dbt, fetch
  Metabase data or start the local MCP server. Applying integration settings clears
  the Metabase cache; disabling MCP access stops a running server.
- **Credentials:** the Metabase API key is stored in IDE Password Safe, never in
  project files.
- Field names, defaults, limits and file examples are defined in
  [Configuration](../docs/configuration.md).

## Example

An IDE project contains `analytics/` and `finance/`, each with its own
`dbt_project.yml`. Their settings drafts contain different executables. Switching
between roots retains both drafts; applying saves both. The projects share the
lineage layout.

With `{"start_date":"2026-03-01"}` and `DBT_SCHEMA=analytics` configured for
`analytics`, its commands receive those variable and environment overrides.
`finance` keeps its own values.

## Screenshot

![dbt Workspace settings with per-root navigation fields.](../docs/screenshots/configuration.png)

The General tab for one owning root. See [Configuration](../docs/configuration.md)
for the settings groups and their controls.

## Interactions

Provides settings to [project discovery](project-context.md),
[workspace commands](workspace.md), [formatting](sql-formatting.md) and
[integrations](downstream-consumers.md). Each of those capabilities defines how it
uses the settings.
