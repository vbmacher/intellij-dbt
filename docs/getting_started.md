# Getting started

Use dbt Workspace to navigate, edit, run and inspect dbt projects in IntelliJ IDEA.
Open a project containing `dbt_project.yml` to browse its local resources without
running dbt. See [Installation](installation.md) for requirements and setup.

<img src="screenshots/model-usages.png" alt="Find Usages lists references to the orders model" width="960">

## Navigation

- [Gutter markers and popups](navigation.md#gutter-markers-and-popups): properties, **Uses** / **Used by** and reference locations.
- [Models, seeds, snapshots and sources](navigation.md#models-seeds-snapshots-and-sources), [model versions](navigation.md#model-versions), [packages and other projects](navigation.md#packages-and-other-projects).
- [Macros and Jinja names](navigation.md#macros-and-jinja-names), [variables](navigation.md#variables), [documentation blocks](navigation.md#documentation-blocks).
- [Data tests and unit tests](navigation.md#data-tests-and-unit-tests), [configuration, profiles and dispatch](navigation.md#configuration-profiles-and-dispatch).
- [Semantic Layer and resource ownership](navigation.md#semantic-layer-and-resource-ownership), [user-defined functions](navigation.md#user-defined-functions).
- [SQL names and columns](navigation.md#sql-names-and-columns), [selectors](navigation.md#selectors), [Python models](navigation.md#python-models), [Information Schema references](navigation.md#information-schema-references).
- [CTE structure, dependency hierarchy and related Airflow definitions](navigation.md#cte-structure-and-hierarchy).
- [Where references work](navigation.md#where-references-work) and [what to check when a reference has no target](navigation.md#when-a-reference-has-no-target).

## Editing

- [Completion, parameter info and live templates](editing.md#completion-and-parameter-information).
- [Quick documentation](editing.md#quick-documentation) for resources, columns, macros and builtin dbt names.
- [YAML property editing and generation](editing.md#edit-and-generate-yaml-properties).
- [Editor warnings](editing.md#editor-warnings) for references, columns, contracts, macro arguments and YAML, with suppression.
- [Expand wildcard columns](editing.md#expand-wildcard-columns) and [create a missing resource](editing.md#create-a-missing-resource).
- [Rename and Safe Delete](editing.md#rename-and-safe-delete).
- [SQL linting and formatting](editing.md#sql-linting-and-formatting) with sqlfmt or SQLFluff.
- [SQL clipboard conversion](editing.md#sql-clipboard-conversion): **Copy for Target DB** and **Paste as dbt Refs**.
- [Syntax highlighting](editing.md#syntax-highlighting) for SQL/Jinja, YAML/Jinja and Markdown/Jinja.

## dbt workspace

- [Lineage](workspace.md#lineage) with [filters, grouping and image export](workspace.md#filter-group-and-export) and [build and freshness badges](workspace.md#build-and-freshness-badges).
- [dbt Run configurations](workspace.md#run-configurations) and [commands](workspace.md#commands).
- [Selection review and baseline comparison](workspace.md#review-a-selection-and-compare-a-baseline).
- [Data and unit tests](workspace.md#tests) with the native test tree and result gutters.
- [Query previews](workspace.md#preview-query-results) and [compiled SQL](workspace.md#compiled-sql).
- [CTE query inspection](workspace.md#inspect-cte-queries) in the Debug window.

## Integrations

- [Exposures](workspace.md#exposures): declared exposures and Metabase questions and dashboards.
- [Airflow context](integrations.md#inspect-airflow-context): local DAG detection and dbt associations.
- [Local MCP server](integrations.md#connect-an-ai-assistant-with-the-local-mcp-server): read-only project access for an AI assistant.

## Reference

- [Configuration](configuration.md): [**Settings → dbt Workspace**](configuration.md#settings--dbt-workspace), including [environment files](configuration.md#environment-files).
- [Icons](icons.md): icon meanings and where they appear.
