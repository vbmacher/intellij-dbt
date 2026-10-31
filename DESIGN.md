---
name: dbt Workspace
description: 'The IDE Workbench: restrained, practical interfaces grounded in IntelliJ conventions.'
colors:
  accent: '#68a7ee'
  link: color-mix(in srgb,var(--accent) 50%,var(--fg))
  model: '#6b9be6'
  source: '#53b8ac'
  seed: '#c6a55f'
  snapshot: '#b88adb'
  exposure: '#dd8d76'
  error: '#df7272'
  edge: '#697484'
  bg: '#202124'
  panel: '#2b2d30'
  fg: '#dfe1e5'
  muted: color-mix(in srgb,var(--fg) 75%,var(--bg))
  line: color-mix(in srgb,var(--fg) 30%,var(--bg))
typography:
  body:
    fontFamily: system-ui
    fontSize: 13px
    fontWeight: 400
  resource-title:
    fontFamily: system-ui
    fontSize: 13px
    fontWeight: 700
    lineHeight: 17px
  panel-heading:
    fontFamily: system-ui
    fontSize: 14px
    fontWeight: 700
  status:
    fontFamily: system-ui
    fontSize: 12px
  metadata:
    fontFamily: system-ui
    lineHeight: 15px
  chip:
    fontFamily: system-ui
    fontSize: 11px
  execution-badge:
    fontFamily: system-ui
    fontSize: 10px
    lineHeight: 16px
  code:
    fontFamily: ui-monospace, monospace
    fontSize: 12px
    lineHeight: 1.5
rounded:
  control: 4px
  tooltip: 5px
  pill: 10px
spacing:
  compact: 4px
  field-block: 5px
  inline: 6px
  toolbar-gap: 7px
  card-block: 8px
  field-inline: 9px
  toolbar-inline: 10px
  card-inline: 12px
  panel-inline: 16px
components:
  toolbar-action:
    backgroundColor: '{colors.panel}'
    textColor: '{colors.fg}'
    rounded: '{rounded.control}'
    height: 28px
    padding: 3px 9px
    typography: '{typography.body}'
  icon-action:
    backgroundColor: transparent
    textColor: '{colors.fg}'
    rounded: '{rounded.control}'
    size: 28px
    padding: 5px
  search-field:
    backgroundColor: '{colors.panel}'
    textColor: '{colors.fg}'
    rounded: '{rounded.control}'
    padding: 5px 9px
    typography: '{typography.body}'
  resource-card:
    backgroundColor: '{colors.panel}'
    textColor: '{colors.fg}'
    rounded: '{rounded.control}'
    width: 250px
    height: 104px
    padding: 8px 12px
  resource-card-execution:
    height: 132px
  metadata-pill:
    textColor: '{colors.fg}'
    rounded: '{rounded.pill}'
    padding: 2px 6px
    typography: '{typography.chip}'
  documentation-link:
    textColor: '{colors.link}'
    typography: '{typography.body}'
---

# Design System: dbt Workspace

## Overview

**Creative North Star: "The IDE Workbench"**

The interface is restrained, practical, and clear. Familiar controls support
focused work, with readable resource names and concise secondary information.
Components should feel familiar, restrained, and immediately understandable.
The system fits the IntelliJ desktop environment and its existing visual language.

This record captures the current implementation, not a replacement visual identity.
Native IntelliJ controls inherit the host theme, fonts, scale and interaction
states. The embedded Lineage view has its own compact web styling and receives
host colors. The frontmatter describes that web surface; it is not a replacement
palette or sizing specification for native Swing controls. Product intent lives in
[PRODUCT.md](PRODUCT.md).

**Key Characteristics:**

- Familiar native controls alongside a compact graph workspace.
- Graphite fallback surfaces and cool blue interaction accents.
- Resource colors supported by text, icons, outlines and border patterns.
- Mostly flat surfaces, with small shadows for cards and floating content.
- Immediate state changes rather than decorative motion.

## Colors

Graphite surfaces frame cool blue interaction accents and semantic resource colors.
The `bg`, `panel` and `fg` tokens are web fallback values. At runtime the IDE supplies
these three values from the active editor scheme and panel theme. The muted text
and separator formulas track those values. Do not treat the dark fallback as the
only supported appearance.

The sidecar's tonal ramps are derived preview aids, not additional implemented
color scales.

### Primary

- **Cool interaction blue** (`accent`): focus outlines, active control borders
  and selected minimap markers.
- **Theme-aware link blue** (`link`): documentation links, text navigation actions
  and the Lineage help disclosure. It mixes the accent with the host foreground to
  retain readable text in light and dark themes.

### Semantic

- **Model blue** (`model`): the default resource-card stripe, including models.
- **Source teal** (`source`): source stripes and passed execution badge borders.
- **Seed gold** (`seed`): seed stripes and warning badge borders.
- **Snapshot violet** (`snapshot`): snapshot stripes.
- **Exposure coral** (`exposure`): exposure stripes.
- **Error red** (`error`): failed and error badge borders.
- **Connector slate** (`edge`): dependency lines, arrows and group outlines.

Resource color is not a global success or warning meaning: its meaning depends on
whether it marks a resource kind or an execution state.

### Neutral

- **Graphite canvas** (`bg`): the graph canvas and code backgrounds; host-overridable.
- **Graphite panel** (`panel`): web fields, cards and documentation surfaces;
  host-overridable.
- **Light foreground** (`fg`): primary text and the selected-card border;
  host-overridable.
- **Muted foreground** (`muted`): secondary metadata and context text, mixed from
  the current foreground and canvas.
- **Quiet separator** (`line`): field borders, table rules, dividers and hovered
  toolbar-button backgrounds, mixed from the current foreground and canvas.

**The Host Theme Rule.** Preserve host-supplied surface and text colors. Resolve
native colors through IntelliJ theme APIs rather than copying web fallback values.

**The Redundant Cue Rule.** Keep text, icons and border patterns alongside semantic
color. Selection, focus and execution state must remain distinguishable concepts.

## Typography

Native controls use the IntelliJ look and feel. Do not prescribe a fixed font
family, point size or web font for Swing components.

Lineage uses the system UI family for interface text and the platform monospace
stack for SQL. The implemented hierarchy is compact: resource names and section
headings gain weight rather than display-scale size. The frontmatter records the
actual web roles; there is no separate display or hero typography.

Resource titles reserve two lines. Names that exceed that space show their
beginning and end on separate lines; accessible names retain the complete identity.
Supporting card metadata uses browser `small`
sizing with the recorded line height, not a separately specified font size.
The zoom percentage uses tabular numerals. Long documentation text and table cells
can wrap; SQL retains whitespace and scrolls horizontally.

**The Host Type Rule.** Keep native typography host-controlled and preserve the
web view's system-font stack. Do not introduce a decorative font into either.

## Layout

Native forms use aligned labels and horizontally growing fields, with titled
separators and scrollable sections. General settings keep the owning-root selector
above the scrolling form. Basic execution, navigation, formatting and lineage
sections remain expanded; checkboxes reveal optional command, environment, baseline
and metadata groups. General settings provide top, left, bottom
and right insets of (11, 16, 16, 16) logical units. Integration sections use
host-scaled spacing. These observed values are local layout choices, not a
universal spacing grid to impose on every native surface.

Lineage fills its available height. Wrapping toolbars sit above the graph, with
compact gaps and status rows. A group rail, graph canvas and documentation pane
form adjacent regions separated by rules. The documentation pane starts at
(360px), is limited to (60%) of the available width, and uses a visible (6px)
divider. The graph retains its own panning and zoom coordinate system.

Cards use the dimensions in the frontmatter; execution evidence adds a taller
variant. Card names wrap within their reserved height. The current graph layout
uses node and rank gaps of (35px) and (80px). Keep card styling and graph layout
dimensions aligned when either changes.

At viewport widths up to (700px), toolbar gaps tighten, the minimap becomes
(120px × 74px), and the group rail narrows. This is adaptation to a narrow IDE
panel, not evidence of a separate mobile product layout.

## Elevation & Depth

Mostly flat: tonal panels and separators establish the workspace structure.
Graph cards have a small ambient shadow. Tooltips and export menus use a slightly
larger shadow to separate floating content from the canvas. Selected cards replace
the shadow with an explicit foreground border. Dashed boundary cards have no
shadow. Exact web shadow values live in the sidecar.

**The Flat Workspace Rule.** Keep structural panels flat. Reserve the existing
small shadows for graph cards and floating content; do not turn native settings
sections into elevated web cards.

## Shapes

Web controls and resource cards share the small control radius. Tooltips use their
own slightly softer radius; metadata pills use the rounded pill token. The canvas,
structural panels and table dividers remain rectilinear.

Resource stripes sit on the left edge of cards. Dashed outlines identify graph
boundaries and collapsed groups. Do not replace meaningful border patterns with
color alone. Native control shapes remain owned by the IntelliJ look and feel.

## Components

### Native controls and forms

Familiar, restrained, and immediately understandable. Use the existing IntelliJ
fields, lists, tables, tabs, titled separators and toolbars. Native focus, selected,
disabled and validation treatments come from the host. Context help uses the
host's subdued help-text color. Keep labels aligned and fields free to grow.
Form labels identify the editable input inside browse fields, scroll panes and
other form wrappers. Validation reveals the relevant context and uses native field
markers with accessible error descriptions. Prerequisite hints accompany dependent
controls; incompatible existing selections remain available to clear.

### Web buttons and fields

Text actions and fields share panel backgrounds, thin separators and compact
padding. Toolbar icon buttons use a transparent background and a square footprint;
the icons inside are (16px). Text toolbar actions use the shorter toolbar padding.
Hover changes text-action borders or icon-action backgrounds. Keyboard focus uses
an accent outline; disabled buttons reduce opacity to (0.45).

Preserve the distinction between compact toolbar actions and ordinary web buttons.
There is no separately branded filled primary-button variant in this surface.
The sidecar previews the existing treatments rather than inventing one.

### Resource cards

Resource identity leads: kind and materialization above a bold name, followed by
schema and package context when available. A colored stripe identifies kind. A focused resource
has a solid accent outline and a **Focus** label; a selected resource has a
foreground border and checkmark. Keyboard focus adds a dashed outline. Unrelated
cards dim to (0.18) opacity. Boundary cards use a shallow, dashed treatment.

Cards have no custom hover animation. Execution badges use compact text and a
status border rather than replacing the resource-kind stripe. Keep the distinct
selection, graph-focus and keyboard-focus treatments.

### Pills, links and documentation

Pills are small outlined labels, not elevated cards. Navigation links are
underlined and use the theme-aware link color. Documentation uses compact headings,
ruled tables and scrollable SQL blocks. Its visual patterns belong to the same
panel and typography system as the graph.

### Native results and lists

Result grids retain native selection and header treatments. Numbers align right;
other values align left. SQL null values use explicit text and a subdued native
color. Consumer lists distinguish the title from supporting evidence with weight
and host help-text colors. Reuse these existing native patterns.

### Motion

The current Lineage stylesheet defines no custom transitions or keyframe
animations. Graph layout runs without animation. Preserve immediate feedback when
recording or extending these controls; this document establishes no new motion
language.

Interaction procedures and feature behavior remain in
[Workspace](docs/workspace.md), [Configuration](docs/configuration.md) and the
[capability specifications](features/README.md).

## Do's and Don'ts

### Do:

- **Do** let IntelliJ supply native themes, fonts, scaling and control states.
- **Do** keep Lineage surface colors connected to the active host theme.
- **Do** preserve resource identity and distinguish selection from focus.
- **Do** use labels, icons and patterns alongside color to explain state.
- **Do** use the current components and capability specifications as the source
  for new work; confirm screenshots against current code.

### Don't:

- **Don't** apply the dark fallback palette to every IDE theme or to Swing controls.
- **Don't** turn compact tool surfaces into marketing layouts or invent a hero
  type scale, filled primary action or decorative motion language.
- **Don't** give a resource-kind color an unconditional execution-state meaning.
- **Don't** flatten selected, focused, disabled and uncertain states into color alone.
- **Don't** duplicate UI procedures or settings documentation in the design record.
