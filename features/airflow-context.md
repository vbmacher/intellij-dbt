# Automatic Airflow context

[Capability index](README.md) · [User guide](../docs/integrations.md#inspect-airflow-context)

Finds local Airflow pipelines that select or ingest dbt resources.

## Contract

- **Inputs:** the DAG projects from [Configuration](../docs/configuration.md#airflow-dag-discovery), their `.py` files (scanned recursively), detected dbt projects with their resources, named selectors, artifacts and dependencies. An empty DAG projects list yields no associations.
- **Selections:** read keyword arguments and dictionary entries named `dbt_selector`, `dbt_select`, `selector`, `select`, `selectors` or `dbt_selectors`, plus `--select`, `--models`, `--model`, `-s`, `-m`, `--selector` and `--exclude` in literal dbt shell commands. Excludes, named selectors, schedules and indirect selection are inherited from enclosing configuration entries. Function defaults and explicit `None` are ignored.
- **Static evaluation:** only literals, constants, lists, tuples, concatenation and string joins are evaluated. Modules are never imported and functions never called. Dynamic values, f-string expressions and runtime templates stay unresolved: they are shown as project context without claiming any resource, and package resources share their execution project's unresolved context.
- **Pipeline name:** the first fixed `name`, `dag_name_suffix`, `dag_id`, `dag_name` or `pipeline_name` from the enclosing configuration, else the file's DAG metadata, else its filename. Names are labels for navigation, not proof of a dynamically generated DAG or task identity.
- **Project:** selections resolve in one detected execution project and its packages, using [Selection review](selection-review.md) rules. With several projects, the nearest one containing the DAG file is used; a missing or ambiguous project is shown without resource badges.
- **Ingestion:** explicit destination table keys, inherited destination dataset keys, and fixed dataset/table parts of fully qualified destination strings identify a table; generic `dataset`/`table` keys do not. Matching tries exact schema and identifier, then case-insensitive; a table without a schema must be unique in the project and its packages. Match confidence is reported, and downstream dbt selections are linked through resource dependencies.
- **Outputs:** pipelines and jobs, selected resources, ingestion destinations, excludes, schedules, unresolved-selection problems, exact Python source locations and optional browser links. The context dialog lists every entry; resource documentation shows at most 50 tasks with the total count. Navigation revalidates an association before opening it. No Airflow or cloud requests are made.
- **Refresh:** edits, unsaved documents, file additions or deletions in watched directories (including external checkouts) and settings changes invalidate discovery. Unreadable paths and files over 1 MB are reported as problems; scanning can be cancelled.

## Example

```python
Cfg(name="daily_finance", dbt_selector="tag:finance+", dbt_exclude="old_report")
```

The context dialog lists `daily_finance` with its selected resources and opens `dbt_selector` in the Python file. `f"tag:{team}"` would stay unresolved. An ingestion dictionary with `bq_dataset="landing"` and `bq_table="orders"` links the pipeline to the matching `landing.orders` relation.

## Interactions

[Resource lineage](resource-lineage.md) supplies ingestion dependencies. [Resource documentation](resource-documentation.md) shows Airflow badges and source links, described in [Workspace](../docs/navigation.md#cte-structure-and-hierarchy).
