- Always start with a short plan before editing.
- Prefer minimal diffs.
- Ask before large refactors or new dependencies.
- After changes, run relevant tests if available.
- Explain what changed in plain language.
- Be concise and act like a collaborative pair programmer.

<!-- codebase-memory-mcp:start -->
# Codebase Knowledge Graph (codebase-memory-mcp)

This project uses codebase-memory-mcp to maintain a knowledge graph of the codebase.
ALWAYS prefer MCP graph tools over grep/glob/file-search for code discovery.

## Priority Order
1. `search_graph` — find functions, classes, routes, variables by pattern
2. `trace_path` — trace who calls a function or what it calls
3. `get_code_snippet` — read specific function/class source code
4. `query_graph` — run Cypher queries for complex patterns
5. `get_architecture` — high-level project summary

## When to fall back to grep/glob
- Searching for string literals, error messages, config values
- Searching non-code files (Dockerfiles, shell scripts, configs)
- When MCP tools return insufficient results

## Examples
- Find a handler: `search_graph(name_pattern=".*OrderHandler.*")`
- Who calls it: `trace_path(function_name="OrderHandler", direction="inbound")`
- Read source: `get_code_snippet(qualified_name="pkg/orders.OrderHandler")`
<!-- codebase-memory-mcp:end -->

# Documentation

Write documentation in plain, consolidate, direct language focused on what users can do, how to do it, and what to expect. Use concrete actions, exact UI labels, and named files instead of vague abstractions or implementation jargon; briefly explain unfamiliar technical terms. State prerequisites and limitations precisely, including their practical consequences, and distinguish separate capabilities such as checking argument counts and checking parameter types. Keep headings aligned with the content, use small examples when helpful, and omit filler and irrelevant caveats. Verify behavior before describing it; flag missing information rather than inventing details.

Never use historical narrative and never document statistics like how many tests passed or what is tested or not.

The user documentation should have a structure/organization:

- installation.md: Documentation of how the user can install the plugin, or prepare the development environment with all the caveats. Also should include information which other plugins should be enabled in order to make sure some functionality works (e.g. TextMate). No other document should mention any installation procedure but this one. 
- getting_started.md: Describes what this plugin does briefly, only list of all features along with links to other documents and paragraphs with detailed documentation. This document can include example screenshots of using the features.
- navigation.md: Describes all features in navigation. It should never explain implementation details and should not explain/document what is already considered as "standard" navigation features in IntelliJ IDEA as such. It should contain examples + screenshots.
- icons.md: description (table) of all icons in the project - Icon,Meaning,Rule - with descriptive and exact listing of places and rules when the icon appears and its meaning. The icons must be idiomatic.
- workspace.md: Describes the "dbt" window - Runner, Lineage and Status tabs. All information regarding Runner, Lineage or Status (e.g. controls, buttons, behavior) should be documented only in this file.
- configuration.md: Describes plugin configuration file and settings UI. No other document should describe them, but if applicable, other documents should only reference configuration.md file.
- others - if there are more "categories" to be documented which do not fall into previous ones above. Need to be consolidated. Every documented feature is from user perspective - and should be documented ONLY if it's visible and controllable by the user.
