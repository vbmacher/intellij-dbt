# Workspace commands and execution context

[Capability index](README.md) · [User guide](../docs/workspace.md#commands)

Run dbt commands through native Run configurations for an explicitly chosen
project, target and resource scope.

## Contract

- **Inputs:** owning execution project, model/directory scope or selection, target,
  command, optional executable/profiles overrides and project execution defaults.
- **Outputs:** a managed dbt process in Run, streamed console output,
  exit/cancellation status and supported refreshed artifacts.
- Opening a project, graph or configuration does not launch dbt. Execution saves
  documents first; editor switching does not change a configuration's scope.
- Commands, previews, CTE inspection and tests share one active process per IDE
  project. Stop and disposal cancel only the process owned by that session.
- Installed-package models execute through their consuming project. Full refresh
  applies only to eligible Run/Build selections.
- **Compile dbt Project** in Tools and the editor's dbt menu starts a whole-project
  compile immediately, using execution defaults rather than a saved configuration's
  selection. The current file selects its execution project; without that context,
  a single project runs directly and multiple projects require a choice.
- Each invocation owns its console. Test and Build use the native test console;
  other commands use native Run sessions with applicable result views.
- Overrides do not modify project defaults or declaration/package identity.
  Saved configurations retain their scope and explicit overrides; unset fields
  read current defaults. Live reruns retain captured command context.
- Successful supported commands refresh artifacts. Completion notifications
  follow configuration preferences.

## Example

Run a Build configuration scoped to models/fct_orders.sql with target dev. Opening
another editor does not retarget it. Run Compile in another configuration after
the first command finishes; its console remains separate from the Build results.

## Interactions

[Selection review](selection-review.md), [query previews](query-preview.md),
[CTE inspection](cte-inspection.md) and [tests](test-execution.md) share this lifecycle.
[Configuration](../docs/configuration.md) owns execution fields and defaults.
