# Baseline comparison and deferral

[Capability index](README.md) · [User guide](../docs/execution.md#selection-and-state)

Compare resources with a baseline manifest and review upstream relations eligible for dbt deferral.

## Contract

- **Inputs:** current source/metadata, a separate compatible baseline manifest and execution context.
- **Outputs:** added, removed and modified resources, change reasons and deferred upstream dependency information.
- Source comparison is static; it cannot prove warehouse relation existence or guarantee that runtime `dbt ls` matches the local approximation.
- Unknown physical provenance stays unknown. Baseline files are read-only inputs and must be separate from the artifact output directory.
- Missing, incompatible or overlapping baselines block dependent operations rather than silently selecting all resources.
- Deferral and favor-state eligibility, version requirements and size bounds are defined in [Configuration](../docs/configuration.md#state-comparison-and-deferral).

## Example

A baseline has orders and report; current source changes orders. Reviewing `state:modified+` includes the changed model and known downstream consumers. An unchanged upstream dependency can be listed as eligible for baseline deferral, without claiming it exists in the active warehouse.

## Interactions

Extends [selection review](selection-review.md).
