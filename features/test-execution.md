# Test discovery, execution and failures

[Capability index](README.md) · [User guide](../docs/workspace.md#tests)

Find dbt tests, run a chosen scope and inspect their recorded outcomes.

## Contract

- **Inputs:** local singular SQL, attached generic YAML and unit-test declarations; supported manifest identities; an explicit Test or Build request.
- **Outputs:** an IntelliJ Run configuration and native test console, per-test outcomes, output, durations, declaration navigation and editor Run gutters.
- Discovery never executes dbt. Execution shares the [managed command process](workspace.md).
- Context execution selects the declaration or test file. Exact generic identities require matching manifest metadata; missing identities stop execution instead of widening scope.
- Test and Build configurations use the same native console. Model outcomes do not count as test successes. Tests enter the tree when reported by dbt.
- Native Rerun Failed Tests selects failed/errored identities with indirect selection disabled, retaining the session’s original executable prefix, environment, profiles and target. Unknown identities block the rerun.
- Passed, failed, error, warning, skipped and canceled states remain distinct. Missing, stale or incompatible artifacts do not establish success.
- Run gutters show compatible recorded outcomes. Source/configuration changes invalidate them. Multiple tests on one declaration line aggregate failures before success; incomplete evidence stays unresolved.
- Saved configurations retain command/scope and explicit execution overrides; unset fields load current root defaults. A live rerun retains its captured command context.

## Example

Run `tests/check_orders.sql` from its gutter. A failing result opens in the IDE’s
Run window and changes the declaration’s gutter to red. Rerun Failed Tests targets
that test. After a passing run the marker becomes green; editing the source makes
the earlier outcome unavailable.

## Interactions

[Execution evidence](execution-evidence.md) supplies provenance checks. [External command execution](external-command.md) defines launcher argument ordering. [Navigation](navigation.md) opens test definitions.
