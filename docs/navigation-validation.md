# Navigation validation

Validated on 2026-10-13 with IntelliJ IDEA Community 2026.2.1 (`IC-262.9437.185`),
JDK 25, and plugin build `2026.2`. The examples and capability matrix are in the
[README](../README.md).

## Automated checks

| Command | Result |
| --- | --- |
| `sbt --batch test` | 88 passed: 18 variable, 63 broader navigation, and 7 Parquet tests |
| `python3 scripts/test-python.py` | 3 passed with the free Python Community plugin |
| `sbt --batch packageArtifactZip` | Distribution ZIP built successfully |
| `git diff --check` | Passed |

The default suite runs without Python, Ultimate, or Database Tools plugins. Fixtures
exercise source identities, reverse reference searches, lexical and project scope,
packages, versions, YAML offsets, ignored/disabled resources, incomplete templates,
unsaved edits, file moves, SQL symbols, semantics, tests, selectors, lineage, and
explicit JSON and Parquet artifacts. They also check native Go to Declaration and Find Usages
target selection. The Python suite uses native PSI without executing model code.

The ZIP contains JSqlParser 5.4, Apache Parquet 1.18.1, their Apache licenses/notices,
the required Hadoop client runtime, and the plugin's navigation classes. Test libraries,
the IntelliJ SDK, and optional language plugins are not bundled.
The artifact is `target/intellij-dbt-2026.2.zip`.

## Manual Community IDE checks

Used an isolated configuration and sample project. SQL and CSV files were plain-text
editors; Python Community and Markdown were enabled for their respective checks.
Opened source files, placed the caret on each reference, invoked the native navigation
or search action, and inspected the resulting file/caret or chooser. These checks did
not run dbt, templates, models, or warehouse queries.

| Action / example | Observed result |
| --- | --- |
| `ref('orders')` | Opened `models/orders.sql` |
| `source('raw', 'events')` | Source and table arguments reached their distinct YAML names |
| `double(value=2)` | Reached the macro block and its `value` parameter |
| Find Usages on the macro | Found its model call |
| YAML `doc('orders_description')` | Reached the Markdown docs identifier |
| Find Usages on that docs block with Markdown enabled | Found two YAML description usages |
| Jinja local `label` | Reached its `set` declaration |
| `{% with x=x %}` | RHS reached the outer `set`; body usage reached the inner `with` binding |
| `function('hash')` | Opened `functions/hash.sql` |
| `target.schema` | Reached the selected profile output's YAML field |
| SQL CTE `recent` | Reached the CTE declaration |
| SQL `r.order_id` through a CTE | Offered both matching YAML column declarations |
| `utils.package_macro()` | Reached the installed package macro identifier |
| `ref('versioned_orders', v=1)` | Opened its `defined_in` body, `orders_v1.sql` |
| Python `dbt.ref("orders")` | Opened `orders.sql` |
| YAML generic test `positive` / `minimum` | Reached the test block / parameter |
| Unit-test `fixture: orders_input`, `format: csv` | Opened `tests/fixtures/orders_input.csv` |
| Find Usages from the CSV fixture | Found the one unit-test fixture reference |
| `Dimension('customer__country')` | Reached the semantic dimension's YAML declaration |
| `ref('remote')` with selected manifest and no local source | Reached the manifest's resource name |
| Check predicate `name = 'orders'` on `info_schema('models')` | Opened the source model |
| Selector `+orders+` | Offered the model and resolved downstream resources |
| Find Usages from `orders.sql` | Found ten references across SQL, Python, YAML, checks, and selectors |
| dbt Properties | Offered both properties files with correct line numbers |
| dbt Dependencies | Listed children and original call sites with correct line numbers |
| Parquet-only `ref('parquet_orders')` | Reached the model name in a read-only metadata JSON document |
| SQL column `p.customer_id` on that resource | Reached its `dbt.node_columns.parquet` metadata row |
| `info_schema('models')` with selected Parquet | Reached the table's metadata view definition |
| dbt Dependencies with selected `dbt.edges.parquet` | Included the model's parent and the original metadata edge row |

The bundled JCEF browser reported a Markdown-preview startup error in this isolated
environment. The IDE attributed it to Web Browser (JCEF); no error was attributed to
the dbt plugin. Markdown source navigation and Find Usages still succeeded.

Local screenshots and IDE logs for this run are retained in
`/tmp/intellij-dbt-manual/`. Representative evidence files are `final-package.png`,
`final-version.png`, `final-python.png`, `final-semantic.png`, `final-doc-find.png`,
`verified-model-usages.png`, `verified-fixture-usages.png`, `verified-properties.png`,
and `verified-dependencies.png`. Parquet evidence is in `parquet-model-confirmed.png`,
`parquet-column-confirmed.png`, `parquet-view-confirmed.png`, and `parquet-dependencies.png`.
The Parquet manual session reported no IDE or plugin errors.

## Limits and remaining work

This is representative manual coverage, not a complete manual audit of every acceptance
criterion in issues #4–31. Engine/version and malformed/ambiguous input cases primarily
have fixture coverage.

Issue #30 now includes explicitly selected manifest/catalog/state JSON and v2 Information
Schema v1 Parquet resource, column, and edge metadata. Parquet tests cover Snappy, Gzip,
Zstandard, uncompressed data, timestamp logical types, version/source identities, source
precedence, reverse reference search, file replacement, stale/unsaved source, engine gates,
malformed/incompatible input, and the row limit. Runtime deferral and warehouse existence
are not inferred. Runtime tables and column-level lineage tables are not indexed.
SQL navigation remains bounded by JSqlParser
and available static schema evidence. Ignore negation patterns and selector YAML alias
expansion are unsupported. See the README for the full supported scope.
