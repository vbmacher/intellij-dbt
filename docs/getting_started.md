# Getting started

dbt Workspace lets you explore, edit, run and inspect dbt projects in IntelliJ IDEA.
Open a project containing `dbt_project.yml`; resources are recognized from local
files without running dbt. See [installation](installation.md) for requirements.

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

## dbt window

- [Runner](workspace.md#runner): run dbt commands, [review selections and baseline state](workspace.md#selection-and-state) and [preview query results](workspace.md#preview-results).
- [Selected SQL and CTE inspection](workspace.md#selected-sql-and-cte-inspection): preview a query or CTE and step through CTEs.
- [Lineage](workspace.md#lineage): trace resources, [filter, group and export the graph](workspace.md#filter-group-and-share), read [documentation and column lineage](workspace.md#documentation) and [edit properties](workspace.md#edit-properties).
- [Build and freshness badges](workspace.md#build-and-freshness-badges) on lineage cards.
- [Tests](workspace.md#tests): browse, run and rerun tests and inspect failures.
- [Workspace details](workspace.md#workspace-details): the active execution context and metadata availability.

## Integrations

- [Git change impact](integrations.md#review-the-impact-of-git-changes): affected resources and a suggested validation selection.
- [Downstream consumers](integrations.md#find-downstream-consumers): declared exposures and Metabase questions and dashboards.
- [Airflow context](integrations.md#inspect-airflow-context): declared DAG/task associations.
- [Local agent tools](integrations.md#give-a-local-assistant-read-only-project-access): read-only project access for a local assistant.

## Reference

- [Configuration](configuration.md): `.dbt-navigation.yml`, `.dbt-airflow.yml`, **Settings → dbt Workspace** and **Settings → dbt Integrations**.
- [Icons](icons.md): icon meanings and where they appear.
- [Troubleshooting](troubleshooting.md): missing targets, unavailable actions and stale metadata.
