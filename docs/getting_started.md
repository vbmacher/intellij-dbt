# Getting started

Open a project containing `dbt_project.yml`. dbt Workspace recognizes its resources and lets you explore and edit them from local files. See [installation](installation.md) for requirements and [configuration](configuration.md) for custom paths, engine versions and execution settings.

- [Resource navigation](navigation.md): follow models, sources, macros, docs, variables, tests and Semantic Layer references across supported SQL, Jinja, YAML and Python contexts.
- [Properties and dependencies](navigation.md): open resource declarations and their uses from dbt actions and gutter markers.
- [Editor tools](editor.md): complete dbt calls and SQL columns, view macro parameter hints, expand wildcards, create missing resources and use native Rename and Safe Delete.
- [Editor feedback](editor.md): read dbt reference and contract warnings, validate YAML, view documentation and format or inspect SQL with configured tools.
- [SQL clipboard actions](editor.md): copy physical relations for a target database and paste known relations as dbt refs.
- [Lineage and documentation](workspace.md#lineage): trace resources and columns, group or filter the graph, export images and edit resource properties.
- [Runner](workspace.md#runner): run dbt commands, review selectors and baseline state, use deferral and inspect query results.
- [CTE inspection](workspace.md): preview intermediate SQL relations, inspect counts and step through explicit query sessions.
- [Tests and execution evidence](workspace.md): browse tests, run or rerun them, inspect failures and read build or freshness badges.
- [Status](workspace.md#status): inspect the active execution context and metadata availability.
- [Git impact](integrations.md): review changed resources, downstream dependencies and a suggested validation selection.
- [Consumers and Airflow context](integrations.md): find declared exposures, optional Metabase consumers and configured DAG/task associations.
- [Local agent tools](integrations.md): give a local assistant explicitly enabled read-only access to project knowledge.
- [Icon legend](icons.md): identify dbt resources in the editor, Project window and navigation results.
