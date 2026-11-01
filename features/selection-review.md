# Selection review

[Capability index](README.md) · [User guide](../docs/workspace.md#review-a-selection-and-compare-a-baseline)

Resolve a dbt command's resource scope locally or through `dbt ls`, with explicit
limits on incomplete and stale results.

## Contract

- **Inputs:** the owning execution project and target; Scope, Selection, Exclude
  and Named selector; current source, local metadata and selector definitions.
  Named selectors use `selectors.yml`, or manifest definitions when that file is absent.
- **Outputs:** matched resource declarations, unresolved-term warnings, a prepared
  command when valid, and an optional reusable `dbt ls` result.
- **Precedence:** Named selector overrides Selection, which overrides Scope. A file
  or directory Scope selects its models; blank scope fields select the whole
  execution project. Exclude removes matches for commands that accept selection.
- **Local resolution:** reads local project knowledge without starting dbt. It
  includes only enabled resources and never widens a result to hide an unresolved term.
- **Incomplete results:** unsupported terms, missing or ambiguous named selectors
  and selector cycles produce warnings. `state:` terms remain unresolved locally;
  [baseline comparison](state-comparison.md) compares resource changes separately.
- **Command preparation:** incomplete or empty local matches block a selected run.
  If every unresolved term is a `state:` term, dbt receives the expression unchanged
  with a compatible baseline. Other unsupported terms still block execution.
- **CLI resolution:** an explicit `dbt ls` uses the configuration's target and
  execution context. Failed or canceled runs supply no reusable result. Resources
  unknown locally are reported and prevent result reuse; output without resource
  records produces a warning.
- **Named selectors:** CLI resolution passes a single named selector as
  `--selector`. Normal selected execution uses resolved resource names, except for
  state-dependent expressions. Selectors combined with other terms or used in
  Exclude must expand completely before launch. State-dependent requests require
  a compatible baseline.
- **Reuse:** a complete CLI result selects exactly its known matched resources
  after the configuration is applied. Configuration, settings, artifact identity
  or source changes invalidate it; it is not retained across IDE restarts. A reply
  arriving after the configuration changes is discarded.
- **Exclusions:** selected commands also pass Exclude terms as `--exclude`, so dbt
  does not select tests of excluded resources indirectly.
- **Exceptions:** Generate Docs and Source Freshness always use the whole project.
  Preview requires a model scope and rejects selection expressions.

## Supported local expressions

| Expression | Meaning |
| --- | --- |
| Plain resource names or glob patterns | Matching local resources. |
| `fqn`, `path`, `file`, `package`, `tag`, `config.tags`, `config.materialized`, `access`, `group`, `source`, `resource_type` / `type`, `exposure`, `metric`, `saved_query`, `test`, `function`, `check`, `selector` | Supported selector methods; values can contain glob patterns. |
| Terms separated by spaces | Union: combine their matches. |
| Terms separated by commas | Intersection: keep resources matching every term. |
| `N+resource`, `resource+N` | Add upstream or downstream resources to an optional depth `N`. |
| `@resource` | Include downstream resources and all their upstream dependencies. |
| Named selector definitions | Support `union`, `intersection`, `exclude`, method mappings and references to other named selectors. |

## Example

A Run configuration selects `orders+` and excludes `daily_totals`. Local review
returns `orders` and its known downstream resources except `daily_totals`, without
running dbt. The prepared command uses their `fqn:` names and
`--exclude daily_totals`.

Adding `state:modified` makes the local review incomplete. With a compatible
baseline, `dbt ls` can resolve that expression. Its complete result can be reused
for execution while the configuration and project context remain unchanged.

## Interactions

[Workspace execution](workspace.md) owns process management;
[baseline comparison](state-comparison.md) owns baseline validation and deferral.
[Resource lineage](resource-lineage.md) accepts a reviewed selection through an
explicit handoff; graph filtering never changes existing Run configurations.
Selector completion in Run configuration fields is separate from
[file completion](completion.md). Its controls are documented in
[Workspace](../docs/workspace.md#run-configurations).
