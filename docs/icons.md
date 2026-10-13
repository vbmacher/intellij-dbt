# Navigation icons

[User guide](README.md) · [Navigation](navigation.md)

The same icon identifies a resource in Go to Declaration, Find Usages, dbt Properties
and dbt Dependencies. An icon shows the resource type; the **Uses** and **Used by**
headings show the dependency direction. Indented `file:line` entries open the references.
Some related types share an icon, as listed below.

| Icon | Meaning |
| --- | --- |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/model-dark.svg"><img src="icons/model.svg" alt="model" width="20" height="20"></picture> | Model — a dbt SQL or Python model. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/source-dark.svg"><img src="icons/source.svg" alt="source" width="20" height="20"></picture> | Source — a named source declared in YAML. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/table-dark.svg"><img src="icons/table.svg" alt="table" width="20" height="20"></picture> | Source table, SQL CTE or Information Schema view. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/seed-dark.svg"><img src="icons/seed.svg" alt="seed" width="20" height="20"></picture> | Seed — a CSV dataset in the seed paths. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/fixture-dark.svg"><img src="icons/fixture.svg" alt="fixture" width="20" height="20"></picture> | Fixture — a file containing unit-test input or expected output. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/snapshot-dark.svg"><img src="icons/snapshot.svg" alt="snapshot" width="20" height="20"></picture> | Snapshot — a resource that tracks changes over time. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/query-dark.svg"><img src="icons/query.svg" alt="query" width="20" height="20"></picture> | Analysis or saved query. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/macro-dark.svg"><img src="icons/macro.svg" alt="macro" width="20" height="20"></picture> | Macro or builtin Jinja function. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/function-dark.svg"><img src="icons/function.svg" alt="function" width="20" height="20"></picture> | UDF — a user-defined function resource. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/test-dark.svg"><img src="icons/test.svg" alt="test" width="20" height="20"></picture> | Data test or generic test definition. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/unit-test-dark.svg"><img src="icons/unit-test.svg" alt="unit test" width="20" height="20"></picture> | Unit test — a model test with supplied input and expected output. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/check-dark.svg"><img src="icons/check.svg" alt="check" width="20" height="20"></picture> | Check — a dbt v2 check resource. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/doc-dark.svg"><img src="icons/doc.svg" alt="doc" width="20" height="20"></picture> | Documentation — a docs block or builtin documentation target. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/semantic-model-dark.svg"><img src="icons/semantic-model.svg" alt="semantic model" width="20" height="20"></picture> | Semantic model — a definition of entities, dimensions and measures. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/metric-dark.svg"><img src="icons/metric.svg" alt="metric" width="20" height="20"></picture> | Metric or measure — a named calculation in the Semantic Layer. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/alias-dark.svg"><img src="icons/alias.svg" alt="alias" width="20" height="20"></picture> | Alias — an alternate name in SQL or a metric expression. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/dimension-dark.svg"><img src="icons/dimension.svg" alt="dimension" width="20" height="20"></picture> | Dimension — an attribute used to group or filter semantic data. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/entity-dark.svg"><img src="icons/entity.svg" alt="entity" width="20" height="20"></picture> | Entity — a semantic identifier used to relate models. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/column-dark.svg"><img src="icons/column.svg" alt="column" width="20" height="20"></picture> | Column — a declared column, SQL projection or Information Schema column. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/export-dark.svg"><img src="icons/export.svg" alt="export" width="20" height="20"></picture> | Exposure or export — a declared consumer or output of dbt data. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/group-dark.svg"><img src="icons/group.svg" alt="group" width="20" height="20"></picture> | Group — a resource ownership group. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/package-dark.svg"><img src="icons/package.svg" alt="package" width="20" height="20"></picture> | Project or installed package. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/variable-dark.svg"><img src="icons/variable.svg" alt="variable" width="20" height="20"></picture> | Variable — a dbt variable, Jinja local or environment key. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/parameter-dark.svg"><img src="icons/parameter.svg" alt="parameter" width="20" height="20"></picture> | Parameter — a macro or function argument. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/config-dark.svg"><img src="icons/config.svg" alt="config" width="20" height="20"></picture> | Configuration — a config key, profile or target setting. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/property-dark.svg"><img src="icons/property.svg" alt="property" width="20" height="20"></picture> | Property, Jinja namespace attribute or another declaration without a dedicated icon. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/result-dark.svg"><img src="icons/result.svg" alt="result" width="20" height="20"></picture> | Statement result — a named result referenced from Jinja. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/selector-dark.svg"><img src="icons/selector.svg" alt="selector" width="20" height="20"></picture> | Selector — a named resource selection. |
| <picture><source media="(prefers-color-scheme: dark)" srcset="icons/materialization-dark.svg"><img src="icons/materialization.svg" alt="materialization" width="20" height="20"></picture> | Materialization — a custom strategy for building a model. |

Icon colors follow the IDE theme.
