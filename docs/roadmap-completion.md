# Navigation roadmap acceptance audit

The navigation and reference-search scope of [#3](https://github.com/vbmacher/intellij-dbt/issues/3)
and its feature tickets is implemented. Navigation retains resource, package, project,
version and lexical identities. Ambiguity produces candidates; unsupported or runtime
values remain unresolved. Source navigation does not execute dbt, templates, Python,
SQL, DuckDB or warehouse queries, and does not download packages or metadata.

## Verification

Validated with JDK 25 and IntelliJ IDEA Community `IC-262.9437.185`:

- `sbt --batch test`: 122 fixture tests without Python, Ultimate or Database Tools.
- `python3 scripts/test-python.py`: five fixtures with the free Python Community plugin.
- `sbt --batch packageArtifactZip`: distribution ZIP, `target/intellij-dbt-2026.2.zip`.
- `git diff --check`: no whitespace errors.

These are automated acceptance checks for the delivery snapshot. The earlier representative
manual IDE checks are recorded in [navigation validation](navigation-validation.md).
The fixes below have fixture coverage; the manual checks were not repeated for every fix.

## Rendering contexts

| Host | Supported references |
| --- | --- |
| SQL models, macros, analyses, tests, snapshots and supported UDF/check bodies | dbt calls, lexical Jinja bindings, permitted builtin context, SQL symbols |
| Resource descriptions in properties YAML | `doc()`, variables and environment names; nested configuration/meta descriptions do not become docs contexts |
| Properties/configuration YAML | Variable/environment names and documented context fields; ordinary strings do not become resource references |
| Deferred resource/project hooks, data-test arguments and SQL-valued fields | Applicable dbt calls, including bare relationship expressions and snapshot `relation` |
| Unit-test and Semantic Layer expression fields | Applicable `ref`, `source`, `this` and typed semantic calls |
| `dbt_project.yml` outside deferred hooks | Restricted project context; `var()` requires supported version and an explicit override or `vars.yml` |
| Explicit profiles and `packages.yml` | `env_var()` and explicitly configured runtime variable keys; project variable defaults are not CLI variables |
| `dependencies.yml`, `package-lock.yml`, variable-definition values, selected run-file values, unrelated YAML | No Jinja execution context; supported declarative dependency/selection fields retain navigation |
| Python models | Native optional Python PSI for proved `dbt` parameter/aliases; no Jinja interpretation |

Offsets use decoded YAML scalars. Each scalar has its own lexical/static scope.
For example, a hook can render a variable from `dbt_project.yml`:

```yaml
vars:
  start_date: '2026-01-01'
on-run-start: "{{ var('start_' ~ 'date') }}"
```

`doc()` resolves only resource description contexts. A `doc()` call in SQL or
`config.meta.description` does not acquire a docs target.

## Version and adapter boundaries

The default version hint is Core 1.10. `.dbt-navigation.yml` selects precise behavior.

| Feature | Capability gate |
| --- | --- |
| Model versions | Core 1.5+ |
| Semantic models and Mesh source dependencies | Core 1.6+ |
| Saved queries | Core 1.7+ |
| Unit tests | Core 1.8+ |
| Generic-test `arguments` mapping | Core 1.10.5+ when an explicit patch version is supplied |
| UDF resources | Core 1.11+ |
| Model-embedded semantics, `vars.yml`, UDF overloads, JavaScript UDFs, string `selector:` terms | Core 1.12+ |
| Checks and Information Schema v1 | Explicit `engine: v2`; unsupported schema versions remain unresolved |

A minor-only version hint represents the supported patch level of that minor series.
Unknown engines do not enable newer schemas. YAML `method: selector` inheritance remains
available on the baseline; string `selector:nightly` terms use the 1.12 gate.

Known UDF adapters/languages constrain targets: Python requires Snowflake, BigQuery or
Databricks; JavaScript requires Snowflake or BigQuery. Overloads require the supported
Snowflake/Postgres combinations. Unknown adapters preserve source candidates without
claiming warehouse support. The plugin never verifies runtime UDF installation.

Explicit local builtin sources are optional:

```yaml
# .dbt-navigation.yml; paths are relative to this dbt root
builtin-sources:
  adapter.get_relation: vendor/adapter.py
  run_query: vendor/core_macros.sql
```

SQL mappings identify the named macro block. Python mappings identify matching functions
through the free Python parser. Without a matching parser/declaration, the target remains
version-specific official documentation. Documentation targets have no project Find Usages.

## Ticket coverage

`N` denotes [DbtNavigationTest](../src/test/scala/com/github/vbmacher/intellij/dbt/DbtNavigationTest.scala),
`A` denotes [DbtAcceptanceTest](../src/test/scala/com/github/vbmacher/intellij/dbt/DbtAcceptanceTest.scala),
`P` denotes [DbtParquetNavigationTest](../src/test/scala/com/github/vbmacher/intellij/dbt/DbtParquetNavigationTest.scala),
`Y` denotes [DbtPythonNavigationTest](../src/test/scala/com/github/vbmacher/intellij/dbt/DbtPythonNavigationTest.scala).
The [variable suite](../src/test/scala/com/github/vbmacher/intellij/dbt/DbtVariableNavigationTest.scala)
also retains the baseline #2 behavior and exercises Community/parser integration and scopes.

| Ticket | Implemented behavior and representative fixture evidence |
| --- | --- |
| #4 | Configured paths, source identities, inherited enabled state, model versions, index invalidation and capability gates; N `testInheritedDisabledConfigurationAndPackageOverrides`, A `testIgnoreNegationsAndAnchoredPatternsUpdateOnEdits` |
| #5 | Models/seeds/snapshots, logical versions, `defined_in`, ambiguity and reverse usages; N `testModelVersionsDefinedInAndLatest`, A `testSnapshotBlockNamesReplaceTheContainerFilename` |
| #6 | Independent source/table arguments and shared SQL/YAML/Python/static resolution; N `testSourceArgumentsUseLogicalNamesAndExactDeclarations`, A `testManifestSourceArgumentsAndMissingVersionStayDistinct`, Y `testPythonSourcesRespectDuplicateRootsAndDisabledTables` |
| #7 | Scoped macro calls, call blocks, named parameters, hooks and reverse search; N `testMacroCallsArgumentsAndDocs`, `testCallBlockParametersDoNotShadowTheInvocationArguments` |
| #8 | Explicit YAML context matrix, scalar offsets, bare expressions and exclusions; A `testYamlContextMatrixAndDescriptionOnlyDocs`, N `testYamlDecodedQuotedAndBlockOffsets` |
| #9 | Individual docs blocks, namespaces and description-only forward/reverse references; N `testMacroCallsArgumentsAndDocs`, A `testYamlContextMatrixAndDescriptionOnlyDocs` |
| #10 | Scoped assignments, loops, macro/call parameters, namespace attributes, captures, shadowing and unsupported loader operations; A `testTupleAssignmentsAndCaptureBodiesRetainLexicalScope`, N `testUnsupportedTemplateLoaderDoesNotInventImports` |
| #11 | Related properties/code, columns, versions, exact reference identities and moves; A `testFindUsagesFromPropertiesKeepsTheResourceAndVersionIdentity`, N `testResourceMovesInvalidateIndex` |
| #12 | Generic test implementations/parameters, legacy/current arguments and proved relationship columns; A `testRelationshipsColumnsResolveTheActualSourceAndVersion`, `testVersionSpecificTestArguments` |
| #13 | Unit models, versioned/namespaced inputs, fixture formats, duplicate/missing fixtures and overrides; A `testUnitTestDuplicateMissingFixturesAndNamespace`, N `testUnitThisAndVariableOverrideIdentity` |
| #14 | Project/package/folder/resource config targets, inheritance, hooks and snapshot strategies; A `testConfigurationFoldersPackagesSourcesAndNewResourceGates`, `testDeferredHookVarsAndEnabledSourceTableOverrides` |
| #15 | Selected profiles/outputs, target fields, local environment keys and explicit runtime vars; A `testProfileRuntimeVarsAndCustomProfilesPath`, N `testProfileTargetAndEnvironmentNames` |
| #16 | Context/builtin source or documentation targets, config APIs, result names and filters; A `testBuiltinSourceAndDocumentationTargetsFindOnlyLocalUsages`, Y `testExplicitPythonBuiltinSourceWithoutExecutingIt` |
| #17 | Installed local/Git/registry package declarations, actual project names, configured install paths and lock entries; N `testInstalledDependencyDeclarationsAndLockNavigation`, `testPackagesAndNestedProjectsKeepNamespaces` |
| #18 | Dispatch search order, root overrides, decoded adapter types, inheritance, materializations and indirect usages; A `testDefaultDispatchPrefersRootOverrideAndProfileTypeIsDecoded`, N `testMaterializationAdapterSelectionAndIndirectUsages` |
| #19 | Root ownership, explicit public Mesh dependencies and conservative candidates; A `testDuplicateRootNamesKeepModelsPropertiesAndColumnsSeparate`, P `testSameNamedProjectsKeepSelectedMetadataAndEdgesSeparate`, N `testMeshDependenciesAndAccess` |
| #20 | Root/package defaults, transitive macro calling-node scope, overrides and conflicts; A `testTransitivePackageMacroVariablesUseTheirOwnCallingNode`, N `testVarsFileConflictAndPackageDefaults` |
| #21 | Bounded literal/constant/collection/branch/wrapper proofs, recorded evidence, shadowing and edit invalidation; A `testStaticEvidenceInvalidatesAfterConstantAndWrapperEdits`, `testNumericAdditionAndReassignedWrapperArgumentsStayUnknown` |
| #22 | Free optional Python PSI, literal dbt APIs, aliases, shadowing and incomplete files; all Y fixtures and N `testNoPythonPluginIsRequiredForSqlNavigation` |
| #23 | Legacy/current semantic identities, typed join paths, time-spine columns and ambiguous candidates; A `testSemanticJoinCandidatesStayAmbiguousAndUpdateOnEdits`, `testCurrentMetricInputsAliasesSavedQueriesAndTimeSpines` |
| #24 | Simple/ratio/derived/cumulative/conversion inputs, aliases, saved-query fields and exports; A `testCurrentMetricInputsAliasesSavedQueriesAndTimeSpines`, N `testMetricAliasesAndSavedQueryExports` |
| #25 | Exposure dependencies, group ownership and supported config/selector names; N `testSemanticMetricsGroupsAndExposures`, `testSelectorsRespectResourceIdentityAndExplicitRunSelection` |
| #26 | UDF sources/properties/parameters/overloads, callers and engine/adapter/language constraints; A `testFunctionLanguageAdapterOverloadAndCallerGates`, N `testFunctionBodyArgumentsUseLibraryExpressionParser` |
| #27 (already closed) | Community SQL CTE/alias/column targets using JSqlParser; N `testSqlCteAliasesAndDeclaredColumns`, `testNativeGoToDeclarationActionNavigatesWithinSqlFile` |
| #28 | Resource/macro/block identities, declarations/call sites, scopes, cycles, cancellation, edits and trusted metadata edges; A `testLineageHonorsScopeEditsCyclesAndCancellation`, `testLineageKeepsSnapshotAndGenericTestBlockOwners`, P `testColumnsAndEdgesMapToSourceWhileKeepingSourceColumns` |
| #29 | Named selectors, nested selection references, shorthand methods, wildcards, duplicates and conservative anchors/state; A `testSelectorReferencesShorthandInheritanceAndVersion`, `testSelectorsKeepDuplicateNamesAnchorsAndShorthandConservative` |
| #30 | Explicit manifest/catalog/state/Parquet metadata, source/version mapping, ownership, stale/schema gates and local source precedence; A `testCatalogColumnsRespectProjectAndInstalledPackageOwners`, P `testExplicitSelectionVersionAndMalformedFiles` |
| #31 | Check configuration/source, selected Information Schema views/columns and proved name predicates; A `testChecksIgnorePathsUnsupportedVersionsAndIncompleteQueries`, N `testInfoSchemaColumnsAndProvedResourceNamePredicates`, P `testMetadataVersionsSourcesAndSchemaDefinitions` |

## Deliberate limits

Template loaders remain unresolved in standard dbt. Static proofs are bounded and do not
evaluate environment/query values or arbitrary macro bodies. Selector YAML aliases are
left to YAML navigation and are not expanded into inferred selections. State selection
requires evidence; warehouse existence and runtime deferral are not inferred. SQL navigation
uses JSqlParser and available declared/projected/selected metadata columns; unsupported
dialects, dynamic control flow and ambiguous joins remain conservative.

`.dbtignore` supports ordered exclusions/re-inclusions, anchored paths, directory patterns
and wildcards. Generated paths remain excluded. Source-only navigation still works without
any selected artifacts, Python plugin, credentials, or network access.

The authoritative version/context references are dbt's [Jinja contexts](https://docs.getdbt.com/reference/dbt-jinja-functions),
[doc() restrictions](https://docs.getdbt.com/reference/dbt-jinja-functions/doc),
[data-test arguments](https://docs.getdbt.com/reference/resource-properties/data-tests),
[selectors](https://docs.getdbt.com/reference/node-selection/yaml-selectors),
[UDF support](https://docs.getdbt.com/docs/build/udfs), and
[Semantic Layer schemas](https://docs.getdbt.com/reference/semantic-layer-reference).
