# Getting started

dbt Workspace lets you explore, edit, run and inspect dbt projects in IntelliJ IDEA.
Open a project containing `dbt_project.yml`; resources are recognized from local
files without running dbt. See [installation](installation.md) for requirements.

See the [capability index](../features/README.md) for feature contracts, examples
and screenshots.

<img src="screenshots/model-usages.png" alt="Find Usages lists the references to the orders model in YAML files" width="640">

## Navigation

- [Gutter markers and dependencies](navigation.md#gutter-markers): open properties, **Uses** / **Used by** and reference locations.
- [Models, seeds, snapshots and sources](navigation.md#models-seeds-snapshots-and-sources), including [model versions](navigation.md#model-versions) and [packages and other projects](navigation.md#packages-and-other-projects).
- [Macros and Jinja names](navigation.md#macros-and-jinja-names), [variables](navigation.md#variables) and [documentation blocks](navigation.md#documentation-blocks).
- [Data tests and unit tests](navigation.md#data-tests-and-unit-tests) and [configuration, profiles and dispatch](navigation.md#configuration-profiles-and-dispatch).
- [Semantic Layer and resource ownership](navigation.md#semantic-layer-and-resource-ownership) and [user-defined functions](navigation.md#user-defined-functions).
- [SQL names and columns](navigation.md#sql-names-and-columns), [selectors](navigation.md#selectors), [Python models](navigation.md#python-models) and [Information Schema references](navigation.md#information-schema-references).
- [Where references work](navigation.md#where-references-work): supported SQL, Jinja, YAML and Python locations.

## Editing

- [Completion and parameter information](editor.md#completion-and-parameter-information) for dbt calls, SQL columns and live templates.
- [Quick documentation](editor.md#quick-documentation) for resources, macros and builtin dbt names.
- [Editor warnings](editor.md#editor-warnings) for references, columns, contracts, macro arguments and YAML.
- [Expand wildcard columns](editor.md#expand-wildcard-columns) and [create a missing resource](editor.md#create-a-missing-resource).
- [Rename and Safe Delete](editor.md#rename-and-safe-delete) for models, macros, CTEs, aliases and columns.
- [SQL linting and formatting](editor.md#sql-linting-and-formatting) with sqlfmt or SQLFluff.
- [SQL clipboard conversion](editor.md#sql-clipboard-conversion): **Copy for Target DB** and **Paste as dbt Refs**.
- [Syntax highlighting](editor.md#syntax-highlighting) for SQL/Jinja, YAML/Jinja and Markdown/Jinja.

## Execution and lineage

- [dbt Run configurations](execution.md#commands) for model, directory and project commands.
- [Selection review and baseline comparison](execution.md#selection-and-state) in native Find and Diff views.
- [Query previews](execution.md#preview-results) and read-only compiled SQL editors.
- [CTE query inspection](execution.md#inspect-cte-queries) in the native Debug window.
- [Data and unit tests](execution.md#tests) with native test results and pass/fail gutters.
- [Lineage](workspace.md#lineage), including [filters, grouping and image export](workspace.md#filter-group-and-share).
- [Build and freshness badges](workspace.md#build-and-freshness-badges) linking to recorded Run results.
- [CTE Structure and dependency Hierarchy](navigation.md#cte-structure-and-dependency-hierarchy).
- [YAML property editing and generation](editor.md#edit-and-generate-yaml-properties).

## Integrations

- [Git change impact](integrations.md#review-the-impact-of-git-changes): affected resources and a suggested validation selection.
- [Downstream consumers](integrations.md#find-downstream-consumers): declared exposures and Metabase questions and dashboards.
- [Airflow context](integrations.md#inspect-airflow-context): automatic local DAG detection and dbt associations.
- [Local MCP server](integrations.md#connect-an-ai-assistant-with-the-local-mcp-server): read-only project access for an AI assistant.

## Reference

- [Configuration](configuration.md): `.dbt-navigation.yml`, the **General** and **Integrations** tabs in **Settings → dbt Workspace**.
- [Environment variables](configuration.md#environment-variables): load project environment files for dbt commands and SQL formatters.
- [Icons](icons.md): icon meanings and where they appear.
- [Troubleshooting](troubleshooting.md): missing targets, unavailable actions and stale metadata.
