# dbt Workspace

<!-- impeccable:product-schema 1 -->

## Platform

Desktop plugin for IntelliJ IDEA. The interface combines native IntelliJ and Swing
controls with an embedded HTML, CSS and JavaScript Lineage view rendered through
JCEF, IntelliJ's embedded browser.

Preserve native desktop interaction conventions throughout the plugin. Web-specific
design tools apply to the embedded web view; they do not inspect native Swing
controls. Impeccable has no platform preset for this desktop and web combination;
do not classify the whole product as a website or mobile app.

## Users

The primary users are dbt newcomers learning and exploring existing projects in
IntelliJ IDEA. They need to understand unfamiliar resources, follow dependencies,
find definitions and documentation, and discover what an action will do before
running it.

## Product Purpose

Help users understand, edit, run and inspect dbt projects without leaving IntelliJ
IDEA. Success means a newcomer can move from an unfamiliar model to its source,
documentation, dependencies and relevant execution results, with clear guidance
when the available information is incomplete.

## Positioning

Bring dbt project knowledge into the IDE's existing editing, navigation, Run,
Debug and test workflows. Local source provides useful navigation and dependency
context without requiring a dbt process or warehouse connection. Optional metadata
and integrations add context without replacing the local source workflow.

## Operating Context

Users work in local projects containing `dbt_project.yml`, alongside SQL, Jinja,
YAML and optional Python files. A workspace may contain several dbt roots, packages
and related pipeline definitions. Actions must make the owning project and
execution context understandable.

Users move between source files, the **dbt** tool window, native execution results
and project settings. Keep those surfaces connected through consistent resource
names, navigation and explanations. Detailed UI procedures belong in the
[user guide](docs/getting_started.md).

## Capabilities and Constraints

The [capability index](features/README.md) owns the current feature contracts.
The main capabilities are navigation, editing assistance, resource and column
lineage, documentation, dbt execution, query and CTE inspection, and optional
integration context. Extend these capabilities before introducing overlapping
concepts.

- Preserve IntelliJ-native workflows and keep the initial setup effort low.
- Keep local browsing distinct from actions that start dbt, query a warehouse,
  change files or refresh external metadata. Users must understand and control
  those actions.
- Explain ambiguity, missing information, stale metadata and unsupported dynamic
  expressions. Candidate relationships are not confirmed dependencies, and missing
  execution evidence is not success.
- Keep navigation tied to the owning dbt project. Optional metadata augments source
  knowledge; it must not make inferred results appear authoritative.
- Protect credentials and distinguish project metadata from warehouse data. The
  local MCP integration exposes read-only project knowledge, not command execution,
  file changes, secrets or warehouse rows.

Environment requirements and setup belong in [Installation](docs/installation.md).
Settings and configuration behavior belong in [Configuration](docs/configuration.md).
Reference those documents rather than duplicating their procedures or defaults.

## Brand Commitments

Use **dbt Workspace** as the product name and **dbt** for the tool window. Use plain,
direct language, exact UI labels and concrete examples. Explain unfamiliar dbt
terms in context. Preserve IntelliJ conventions and the existing product identity;
visual changes should support the user's task.

## Evidence on Hand

- [User guide](docs/getting_started.md): current user-visible capabilities and links
  to their instructions.
- [Capability specifications](features/README.md): behavior, inputs, outputs,
  constraints and failure boundaries.
- [Screenshots](docs/screenshots/): reference captures of navigation, settings,
  Lineage and CTE inspection. Check their freshness before treating them as current
  visual authority.
- [Existing UI implementation](src/main/scala/com/github/vbmacher/intellij/dbt/ide/)
  and [Lineage assets](src/main/resources/lineage/): evidence for the current
  interface, to be examined when documenting or changing a surface.

No audience research, usage measurements or comparative product claims are
established by this record. Do not invent them.

## Product Principles

1. **Help newcomers understand the project.** Make actions discoverable, explain
   unfamiliar terms, and connect resources to their definitions and documentation.
2. **Work naturally inside IntelliJ.** Use the IDE's established controls and
   workflows, preserving keyboard navigation, themes and familiar behavior.
3. **Provide value before configuration.** Keep local exploration useful and make
   prerequisites visible when a requested capability needs more context.
4. **Make execution deliberate.** Explain what runs, which project and environment
   it uses, and where results or failures appear.
5. **Show the limits of the evidence.** Distinguish confirmed, inferred, stale,
   incomplete and unavailable information so users can decide what to trust.

## Open Decisions

No product-specific accessibility standard or user research baseline has been
confirmed. Preserve the host IDE's accessibility behavior and keyboard workflows;
do not claim a compliance level without evidence.
