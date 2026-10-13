# dbt resource icons

[User guide](README.md) · [Navigation](navigation.md)

The same icon identifies a resource in the Project window, Go to Declaration,
Find Usages, dbt Properties and dbt Dependencies. An icon shows the resource type;
the **Uses** and **Used by** headings show the dependency direction. Indented
`file:line` entries open the references. Some related types share an icon, as listed below.

## Declaration legend

This legend also covers declarations inside files, such as source tables, columns,
parameters and variables; these are shown by navigation, rather than as separate
files in the Project window.

| Icon | Meaning | Rule |
| --- | --- | --- |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/source-dark.svg"><img src="icons/source.svg" alt="model" width="20" height="20"></picture> | SQL or Python model | `.sql` or `.py` in `model-paths` (default `models/`). |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/source-dark.svg"><img src="icons/source.svg" alt="source" width="20" height="20"></picture> | Source | Named `sources:` entry in resource YAML. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/source-dark.svg"><img src="icons/source.svg" alt="table" width="20" height="20"></picture> | Source table, SQL CTE or Information Schema view | Table declared under a source, CTE defined in SQL, or view from selected Information Schema metadata. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/seed-dark.svg"><img src="icons/seed.svg" alt="seed" width="20" height="20"></picture> | Seed | `.csv` in `seed-paths` (default `seeds/`). |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/snapshot-dark.svg"><img src="icons/snapshot.svg" alt="snapshot" width="20" height="20"></picture> | Snapshot | `.sql` in `snapshot-paths` (default `snapshots/`), including legacy `{% snapshot %}` blocks. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/query-dark.svg"><img src="icons/query.svg" alt="query" width="20" height="20"></picture> | Analysis | `.sql` in `analysis-paths` (default `analyses/`). |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/query-dark.svg"><img src="icons/query.svg" alt="query" width="20" height="20"></picture> | Saved query | Named `saved_queries:` entry in resource YAML. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/macro-dark.svg"><img src="icons/macro.svg" alt="macro" width="20" height="20"></picture> | Macro | A `{% macro %}` definition in a resource `.sql` file, usually in `macro-paths` (default `macros/`). |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/macro-dark.svg"><img src="icons/macro.svg" alt="macro" width="20" height="20"></picture> | Builtin Jinja function | Builtin function target resolved by Jinja navigation. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/function-dark.svg"><img src="icons/function.svg" alt="function" width="20" height="20"></picture> | UDF body | `.sql`, `.py` or `.js` in `function-paths` (default `functions/`), when supported by the selected dbt version. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Singular data test | `.sql` in `test-paths` (default `tests/`). |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Generic test | A `{% test %}` definition in a resource `.sql` file. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Attached data tests | Resource YAML containing only model, seed or snapshot names and `data_tests:` or `tests:`, including column tests. Other resource or column properties retain the resource icon. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="unit test" width="20" height="20"></picture> | Unit test | Named `unit_tests:` entry in resource YAML, with `model`, `given` and `expect`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="fixture" width="20" height="20"></picture> | Unit-test fixture | `.csv` or `.sql` under `<test-path>/fixtures/`. Takes precedence over the data-test rule. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/check-dark.svg"><img src="icons/check.svg" alt="check" width="20" height="20"></picture> | dbt v2 check | `.sql` in `check-paths` (default `checks/`), with the dbt v2 engine selected. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/doc-dark.svg"><img src="icons/doc.svg" alt="doc" width="20" height="20"></picture> | Docs block | A `{% docs %}` definition in a resource `.sql` or `.md` file. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/doc-dark.svg"><img src="icons/doc.svg" alt="doc" width="20" height="20"></picture> | Builtin documentation target | Builtin documentation target resolved by navigation. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/semantic-model-dark.svg"><img src="icons/semantic-model.svg" alt="semantic model" width="20" height="20"></picture> | Semantic model | Definition of entities, dimensions and measures. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/metric-dark.svg"><img src="icons/metric.svg" alt="metric" width="20" height="20"></picture> | Metric or measure | Named calculation in the Semantic Layer. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/alias-dark.svg"><img src="icons/alias.svg" alt="alias" width="20" height="20"></picture> | SQL relation alias or metric input alias | Navigation to a table, CTE or subquery alias used as a SQL qualifier, such as `o` in `o.id` after `FROM … AS o`; or a metric input's `alias:` referenced in that metric's `expr` (`type_params.metrics[]`, or `input_metrics[]` in Core 1.12+). SELECT-list aliases use the Column icon. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/dimension-dark.svg"><img src="icons/dimension.svg" alt="dimension" width="20" height="20"></picture> | Dimension | Attribute used to group or filter semantic data. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/entity-dark.svg"><img src="icons/entity.svg" alt="entity" width="20" height="20"></picture> | Entity | Semantic identifier used to relate models. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/column-dark.svg"><img src="icons/column.svg" alt="column" width="20" height="20"></picture> | Column | Declared column, SQL projection or selected Information Schema column. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/export-dark.svg"><img src="icons/export.svg" alt="export" width="20" height="20"></picture> | Exposure or export | Declared consumer or output of dbt data. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/group-dark.svg"><img src="icons/group.svg" alt="group" width="20" height="20"></picture> | Group | Resource ownership group. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/package-dark.svg"><img src="icons/package.svg" alt="package" width="20" height="20"></picture> | Project or installed package | Project or package declaration resolved by navigation. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/variable-dark.svg"><img src="icons/variable.svg" alt="variable" width="20" height="20"></picture> | Variable | Dbt variable, Jinja local or environment key. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/parameter-dark.svg"><img src="icons/parameter.svg" alt="parameter" width="20" height="20"></picture> | Parameter | Macro or function argument. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/config-dark.svg"><img src="icons/config.svg" alt="config" width="20" height="20"></picture> | Configuration | Config key, profile or target setting. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/property-dark.svg"><img src="icons/property.svg" alt="property" width="20" height="20"></picture> | Property | Property, Jinja namespace attribute or another declaration without a dedicated icon. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/result-dark.svg"><img src="icons/result.svg" alt="result" width="20" height="20"></picture> | Statement result | Named result referenced from Jinja. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/selector-dark.svg"><img src="icons/selector.svg" alt="selector" width="20" height="20"></picture> | Selector file | Named entries under `selectors:` in the project's `selectors.yml`. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/materialization-dark.svg"><img src="icons/materialization.svg" alt="materialization" width="20" height="20"></picture> | Materialization | A `{% materialization %}` definition in a resource `.sql` file. |
| Declared resource's icon | YAML resource properties | Named entries under resource keys such as `models:`, `sources:`, `macros:` or `unit_tests:`, in a configured resource path. |

Icon colors follow the IDE theme.
