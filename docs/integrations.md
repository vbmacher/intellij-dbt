# Integrations

[User guide](getting_started.md)

Open these actions from the **Tools** menu:

| Action | Use it to |
| --- | --- |
| **dbt Downstream Consumers** | Open the [Exposures tab](workspace.md#exposures) for the current resource. |
| **dbt Airflow Context** | View automatically detected local Airflow pipelines. |
| **dbt Local MCP Server** | Start or stop read-only project access for an AI assistant on this computer. |

## Find downstream consumers

Declared dbt exposures and cached Metabase questions and dashboards appear in the
**Exposures** tab beside **Lineage** in the **dbt** tool window. See
[Exposures](workspace.md#exposures) for its controls, column filter, refresh behavior
and examples, and [Metabase connection](configuration.md#metabase-connection) for
connection settings.

## Inspect Airflow context

First [add local DAG folders](configuration.md#airflow-dag-discovery). Open
**Tools → dbt Airflow Context** to see detected DAGs and jobs, schedules, selected
dbt resources, exclusions, ingestion destinations and unresolved selections. Select
an entry and click **Open DAG definition** to open its Python selection or
destination entry; **Open Airflow task** opens a browser link when an Airflow URL is
configured. **Configure…** opens the integration settings.

Detected pipelines also appear in [Related Symbol](navigation.md#cte-structure-and-hierarchy)
navigation and in a resource's [Quick documentation](editing.md#quick-documentation).

Discovery is automatic and reads Python files in the configured folders
recursively. It never executes Python and does not contact Airflow or cloud
storage. Changes to watched files, including unsaved edits, update the results.
It skips `.git`, `.venv`, `venv`, `node_modules` and `__pycache__` directories and
files larger than 1 MB; unreadable paths are reported in the dialog. With one dbt project
open, that project is used; with several, a DAG file is matched to the nearest dbt project containing it;
files outside all dbt projects remain unresolved.

What is recognized:

- dbt keyword arguments such as `dbt_selector`, `dbt_select` and `select`, nested
  job dictionaries, and shell commands such as
  `dbt build --select tag:finance+ --exclude old_report`.
- `dbt_selector_name`, `selector_name` and shell `--selector` name a definition in
  `selectors.yml`. The `selector` key in a Python job dictionary holds a selection
  expression.
- Constants, lists, concatenation and string joins. Dynamic Python and runtime
  templates remain unresolved. A pipeline name computed at runtime is shown as the
  Python filename.
- Ingestion destinations from explicit keys such as `bq_dataset` with `bq_table`,
  `destination_table`, or a full destination table reference. A generic `dataset` /
  `table` pair is not treated as ingestion. Matches show whether schema and table
  matched exactly, matched ignoring case, or used a table name unique to the dbt
  project and its packages. Ingestion pipelines upstream of a dbt selection appear
  with that job.

Runtime schedules and execution status cannot be determined.

## Connect an AI assistant with the local MCP server

The local MCP (Model Context Protocol) server lets an AI assistant ask the IDE what
it already knows about the project, without running dbt or exposing credentials.
Use it when you ask an assistant to:

- write or check model and column descriptions (`get_documentation`);
- explain where a column comes from or what uses it (`get_column_lineage`, `get_lineage`);
- find which resource a `ref(...)` or `source(...)` points to (`resolve_reference`);
- check which resources a selection such as `+orders` matches (`resolve_selection`).

The assistant must run on the same computer and support MCP over HTTP with custom
headers, and the project must be open in the IDE. The server cannot run dbt, query the warehouse or change files. Responses never
include profile or environment values, connection secrets or warehouse rows; review
project descriptions in the results before sending them to an external assistant.

1. [Enable the local MCP server](configuration.md#local-mcp-server).
2. Open **Tools → dbt Local MCP Server** and click **Start**.
3. Click **Copy MCP client configuration** and paste it into your MCP client's
   server configuration. If the client asks for separate fields, click
   **Copy endpoint and authorization** to copy the URL and `Authorization` header.
4. Click **Stop** when finished. Closing the project or disabling the option also
   stops the server.

```json
{
  "mcpServers": {
    "dbt-workspace": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Each start uses a new random port and token, so copy the details again after a
restart. Keep the token private.

### MCP protocol reference

- **Transport:** Streamable HTTP only (no stdio; a client that only starts local
  commands needs an HTTP bridge). Every request is a POST with
  `Authorization: Bearer <token>`; responses are single `application/json` bodies,
  never SSE streams. The server keeps no sessions and offers only tools (no
  resources, prompts or logging).
- **Versions:** `2026-07-28` needs no handshake: each request carries `_meta` with
  `io.modelcontextprotocol/protocolVersion` and
  `io.modelcontextprotocol/clientCapabilities`, plus the headers
  `MCP-Protocol-Version`, `Mcp-Method` and, for `tools/call`, `Mcp-Name`; methods are
  `server/discover`, `tools/list` and `tools/call`. `2025-11-25`, `2025-06-18` and
  `2025-03-26` start with `initialize` (another requested version is answered with
  `2025-11-25`); later requests send `MCP-Protocol-Version`, or `2025-03-26` is
  assumed. Methods are `initialize`, `ping`, `tools/list` and `tools/call`; only
  `2025-03-26` accepts JSON-array batches.
- **Rejections:** wrong or missing token → HTTP 401; an `Origin` header (sent by
  browsers) → 403; GET or DELETE → 405; notifications and client responses → 202
  with no body; body over 64 KiB → 413; unsupported version → 400 with error
  `-32022` and the supported versions; header not matching the body → 400 with
  error `-32020`; unknown tool → error `-32602`. Calls over 30 seconds are canceled.
- **Results:** `tools/list` returns tool names and argument schemas. The `project`
  argument accepts a directory returned by `list_projects` or a unique project
  name. Resource lineage returns one dependency step per call. Answers appear in
  `structuredContent` and as JSON text in `content`, with `status` `ok`, `partial`,
  `missing`, `ambiguous`, `error`, `unsupported` or `disposed`; the last three, and
  arguments not matching the schema, set `isError: true`. Lists and traces cut short
  by a cap are `partial`. Responses over 2 MB are replaced by an error asking you to
  narrow the request.

Example call with version `2025-06-18`, after `initialize`:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_column_lineage","arguments":{"project":"/work/analytics","resource_id":"model.analytics.report","column":"amount","direction":"upstream"}}}
```
