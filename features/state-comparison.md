# Baseline comparison and deferral

[Capability index](README.md) · [User guide](../docs/workspace.md#review-a-selection-and-compare-a-baseline)

Compare current resources with a recorded baseline and supply that baseline to
dbt state selection and deferral.

## Contract

- **Inputs:** current source and metadata, a separate compatible baseline manifest,
  and the owning execution context.
- **Comparison output:** added, removed, modified and unknown classifications with
  change reasons, plus a diff of recorded baseline values against current SQL,
  properties and configuration. Current runtime defaults remain unresolved.
- Comparison reads local data. It does not run dbt, check warehouse relations or
  preview which dependencies dbt will defer.
- **State selection:** local selection review leaves `state:` terms unresolved.
  CLI resolution and execution pass supported state-dependent requests to dbt with
  the baseline; they do not substitute a locally computed state selection.
- **Deferral:** enabled deferral adds `--defer --state <directory>` to the command;
  eligible favor-state requests also add `--favor-state`. dbt determines which
  warehouse relations to use when the command runs.
- Baseline files are read-only inputs and must be separate from artifact output.
  Missing, incompatible or overlapping baselines block dependent operations.
- Eligibility, version requirements and size bounds are owned by
  [Configuration](../docs/configuration.md#baseline-state-and-deferral).

## Example

A baseline contains `orders` and `report`; current source changes `orders`.
Comparison lists the modified resource and shows its recorded and current values.
A command selecting `state:modified+` passes that expression and the baseline to
dbt. The local comparison does not establish the command's final resource scope
or whether an upstream warehouse relation exists.

## Interactions

[Selection review](selection-review.md) distinguishes local and CLI resolution.
[Workspace execution](workspace.md) launches commands with the validated baseline
and deferral options.
