# Lineage, documentation and dbt Runner

[User guide](README.md) · [Settings](configuration.md#workspace-and-runner-settings)

Open **View → Tool Windows → dbt**. The bottom tool window contains **Lineage**,
**Runner**, and **Status** tabs. Browsing and navigation work from source files and
existing artifacts without running dbt. Execution starts only from a command button.

## Lineage and documentation sidebar

The graph follows the active model. Cards represent models, sources, seeds,
snapshots and exposures, with upstream → downstream arrows. Tests appear in docs.

- Single click selects documentation and dims unrelated resources.
- Double click focuses that resource and opens its definition. Drag a card to rearrange it.
- Drag empty space to pan; use the wheel or **Zoom in / Zoom out** to zoom, and **Fit graph** to recenter.
- Search by resource/package name; **Clear search** restores the selected resource's neighborhood.
- **+ N more** expands another five levels in that direction, keeping the same focus.
- **Toggle documentation** shows or hides the sidebar; **Close documentation** hides it. Drag its divider to resize.
- **Regenerate Docs**, beside the documentation toggle on the right, uses the same command lifecycle as Runner → **Generate Docs**.

Lineage toolbar buttons use native IntelliJ icons; hover over an icon to see its action.
Graph refreshes preserve sidebar visibility, including when the focused model changes.

The sidebar has **Columns**, **Tests**, **SQL**, and **Metadata** tabs. Descriptions
scroll independently and can expand. SQL preserves whitespace and scrolls horizontally.
Source/YAML descriptions and columns take precedence; compatible manifest/catalog
metadata fills gaps. Compiled SQL is shown only when available. Keys, constraints
and tests are labeled as declarations, never as verified warehouse constraints or
passing results. Resources without SQL/columns show explicit empty states.
Descriptions support static `doc()` calls joined with `~` and literal text; unresolved
or unsupported expressions remain visible without dropping their content.

Settings control depth, direction, connectors and exposures.
Theme, source and artifact updates refresh the view; the same graph focus preserves
zoom/pan. Ambiguous declarations remain distinct candidates. The graph uses bundled
Cytoscape.js/dagre assets; it makes no CDN requests.

### JCEF setup

Use a full IntelliJ IDEA installation with its bundled JetBrains Runtime and Web Browser (JCEF) plugin.
The [SDK archive](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html#plugin-dependency)
downloaded for `sbt runIDE` omits the native JCEF bundle, which can leave Lineage blank
and log `Startup JCEF test is failed (because CefApp wasn't initialized)`.
Copy or symlink `plugins/jcef-plugin/jcef` from a full IDEA installation into the same location in
the development SDK. Match the operating system, architecture, and SDK `dependencies.txt` entry
`jcefBuild` against the bundle's `jcef.version`, then restart the development IDE.
Runner and **dbt Dependencies** remain usable without JCEF.

## Native hover and warnings

Hover a `ref()` model argument, `source()` table argument, or macro name, or invoke
**Quick Documentation**. Native hover can be enabled under **Editor → Code Editing →
Show quick documentation on hover**. Existing Ctrl+hover still navigates the exact
reference name. Popups show at most 15 columns and eight entries in each dependency
direction, with counts; the sidebar has the full lists.
For builtin names such as `ref`, `source`, and `var`, Quick Documentation loads the
official dbt reference article in the native pane. Ctrl+Click on a builtin opens that
article in the Documentation tool window; links to other dbt reference pages stay there.
Source headings retain the logical source/table identity (for example, `analytics.raw.events`).
Known database, schema and physical relation fields are listed separately; unavailable fields are omitted.

Unresolved static `ref()` / `source()` calls have warning ranges on their arguments.
Unknown sources and unknown tables are distinguished. Dynamic names, incomplete
calls, comments, ordinary SQL strings, ambiguous candidates and unavailable configured
metadata do not produce missing-resource claims. Warnings update after edits and file
changes. Source-only resolution cannot prove runtime macro behavior or warehouse state.

## Runner

The configuration row contains **Target**, **Model or directory**, and **Executable
arguments**, from left to right. Enter a model path or use its **Browse** button to
choose a model file or directory. Leave the model path blank for **all models**;
Run, Build, Test, and Compile then omit `--select`. Paths can be absolute or relative
to the IDE project. The choice is remembered
when the tool window is reopened and stays selected when editors change or close.
Directory runs include enabled models in that folder and its descendants. The choice determines
the execution project, profiles and target settings. Lineage still follows the editor.
Clearing the model path keeps the last selected execution project. Initially, all
models uses the dbt project at the IDE project root, or the sole discovered execution
project. If several projects exist without either choice, select a path once to set
the execution project before clearing it.

Select a discovered target or type one, including targets supplied by a wrapper.
A blank target uses the profile default. Pending edits survive background refreshes and are saved when
launching a command or changing the run selection. Arguments
are passed before the dbt command and shared with **Settings → dbt Workspace** for
the selected execution root. They also apply to **Generate Docs** and the graph's
**Regenerate Docs** action.

The toolbar uses IDE icons alongside **Run | Build | Full Refresh | Test | Compile |
Preview | Generate Docs | Clear | Stop | Settings**. The selected scope and target appear below it.

| Button | Command |
| --- | --- |
| Run / Build / Test / Compile | `dbt <command> --select <selected model FQNs>` |
| Run / Build / Test / Compile with blank model path | `dbt <command>` |
| Preview | `dbt show --select <model> --limit N --output json` |
| Generate Docs | `dbt docs generate` |

Directory selections expand to the contained models' fully qualified names, preserving
their packages and versions. **Preview** requires a single selected model.
**Full Refresh** appears when the selection includes incremental models and affects
Run/Build. It resets when the run selection changes. Commands save documents and use
argument vectors. **Generate Docs** generates artifacts for the selected execution
project; the graph's **Regenerate Docs** button uses that execution project too.

The log streams stdout/stderr, renders ANSI colors, and records completion/exit codes.
**Clear** clears the log and preview; **Stop** cancels the active process. Only one
command can run at a time, including graph regeneration. Preview results appear as
an aligned table beneath the log; nulls and empty results are explicit. Ordinary log
text can soft-wrap using the console's normal soft-wrap control; preview tables stay
unwrapped. Success/failure produces IDE notifications and, when enabled and supported
by the OS, native notifications. Cancellation is reported separately.
Native delivery also requires IntelliJ's **Appearance & Behavior → Notifications → Enable system notifications**
setting, an inactive IDE window, and an available desktop notification service (such as a Linux notification daemon).

Successful Run/Build/Compile/Generate Docs refreshes artifacts. **Status** shows the
executable, available manifest CLI version, target/adapter, run selection, lineage focus, resource count,
artifact errors and timestamps. It never executes a version probe merely to open a tab.

The command surface follows dbt Core 1.5+ (`dbt show`/JSON preview) and installed
adapter support; navigation artifact schemas remain manifest v9–v12/catalog v1.
The upstream [`dbt show` command](https://docs.getdbt.com/reference/commands/show) does not support Python models.
Earlier CLIs, unsupported Fusion command/flag combinations and adapter-specific failures
are reported through CLI output. The plugin does not install dbt, profiles or adapters.
A fake executable validates argument/process behavior without warehouse access. Native
OS delivery and actual warehouse/adapter execution need checks on the user's platform.

## SQL clipboard conversion

**Copy for Target DB**: Ctrl+Shift+C (Cmd+Shift+C on macOS), also in Edit and the
editor context menu. Copies a selection or the whole document without changing it.
Static `ref()`, `source()` and `this` use the manifest's physical `relation_name`,
including aliases, source identifiers and adapter quoting. Remaining Jinja is wrapped
in marked SQL block comments. This is reference conversion, not full compilation.

**Paste as dbt Refs**: Ctrl+Shift+V (Cmd+Shift+V on macOS). Replaces the selection
or inserts at the caret as one undoable command. Parsed table references with two or
three parts become refs/sources only when they uniquely match physical metadata.
Packages and explicit versions are retained. Strings, comments, alias-column
qualifiers and unknown/ambiguous relations stay unchanged. Deliberately marked Jinja
comments from Copy are restored; ordinary SQL comments are preserved. SQL dialects
that the bundled parser cannot read are left unchanged.

Run **Generate Docs** for the selected project/target before clipboard conversion.
The Runner records provenance for the exact manifest bytes and execution configuration
(including profiles, project configuration and wrapper arguments) in `.dbt-workspace-context`
beside the manifest after a successful command writes changed manifest content.
Commands that leave the manifest unchanged do not certify it for another target.
Changed execution configuration or externally replaced manifests require regeneration;
conversion warns before modifying clipboard/document when provenance is unavailable.
Shortcuts can conflict with IDE keymaps; remap the named actions under **Settings → Keymap**.
