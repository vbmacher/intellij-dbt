# Integrations

[User guide](getting_started.md)

Open these actions from the **Tools** menu:

| Action | Use it to |
| --- | --- |
| **dbt Downstream Consumers** | Open the [Exposures tab](workspace.md#exposures) for the current resource. |
| **dbt Airflow Context** | View automatically detected local Airflow pipelines. |
| **dbt Local MCP Server** | Start or stop read-only project access for an AI assistant on this computer. |

## Find downstream consumers

See [Exposures](workspace.md#exposures) to browse declared dbt exposures and cached
Metabase questions and dashboards. Connection settings are in
[Metabase connection](configuration.md#metabase-connection).

## Inspect Airflow context

After [adding local DAG folders](configuration.md#airflow-dag-discovery), choose
**Tools → dbt Airflow Context**. The **Find** tool window lists detected DAGs and
tasks with their owning project and known schedule. Open a result to navigate to
its Python selection or destination entry.

The **Airflow details** preview shows the selected task's resources, exclusions,
ingestion destinations, unresolved selections and exact source location.
**Open Airflow task** is available there when the selected task has a configured
browser link. **Configure…** opens the integration settings and refreshes discovery
when you return. Use **Rerun** to refresh after editing DAG files.

Detected pipelines also appear in [Related Symbol](navigation.md#cte-structure-and-hierarchy)
navigation and in a resource's [Quick documentation](editing.md#quick-documentation).

Discovery reads Python files in the configured folders and their subfolders,
including unsaved edits when you refresh. It never executes Python or contacts
Airflow or cloud storage. It skips `.git`, `.venv`, `venv`, `node_modules` and
`__pycache__` directories and files larger than 1 MB. Results report unreadable paths.

With one dbt project open, discovery uses that project. With several, it uses the
nearest dbt project containing the DAG file; files outside all dbt projects remain
unresolved.

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

Discovery cannot resolve schedules computed at runtime or report whether a task ran.

## Connect an AI assistant with the local MCP server

The local MCP (Model Context Protocol) server gives an AI assistant access to the
project's resource names, documentation and dependencies. Use it to:

- write or check model and column descriptions (`get_documentation`);
- explain where a column comes from or what uses it (`get_column_lineage`, `get_lineage`);
- find which resource a `ref(...)` or `source(...)` points to (`resolve_reference`);
- check which resources a selection such as `+orders` matches (`resolve_selection`).

The assistant must run on the same computer and support MCP over HTTP with custom
headers. Keep the project open in the IDE. The server cannot run dbt, query the
warehouse or change files. It omits profile and environment settings, connection
secrets and warehouse rows. Responses include project descriptions and source
paths; review them before sending them to an external assistant.

1. [Enable the local MCP server](configuration.md#local-mcp-server).
2. Open **Tools → dbt Local MCP Server** to select **dbt Workspace → Local MCP Server**
   in the **Services** tool window, then choose **Start**.
3. Choose **Copy MCP client configuration** and paste it into your MCP client's
   server configuration. If the client asks for separate fields, click
   **Copy endpoint and authorization** to copy the URL and `Authorization` header.
4. Choose **Stop** when finished. Closing the project or disabling the option also
   stops the server.

```json
{
  "mcpServers": {
    "dbt-workspace": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

The Services node shows whether the server is running. **Start** requires the
server to be enabled in settings. **Stop** and the copy actions are available only
while it runs. Copy actions confirm when the details reach the clipboard.
**Configure…** opens the integration settings.

Each start uses a new random port and token. Copy the configuration again after a
restart. The status panel shows the URL; copy actions include the authorization
token. Keep the token private.

### MCP protocol reference

This section is for configuring MCP clients by hand or writing your own client.

#### Transport

- **Endpoint:** `http://127.0.0.1:<port>/mcp`, reachable only from this computer.
  Other paths answer HTTP 404.
- **Requests:** HTTP POST with a JSON-RPC 2.0 body and the header
  `Authorization: Bearer <token>`. Only Streamable HTTP is supported; there is no
  stdio transport, so a client that can only start local commands needs an HTTP
  bridge.
- **Responses:** a single `application/json` body, without an event stream. The
  server keeps no sessions and processes one request at a time.
- **Capabilities:** tools only (no resources, prompts or logging). The server
  identifies itself as `dbt-workspace` version `1.0`.

#### Protocol versions

| Version | How a client starts | Methods | Batches |
| --- | --- | --- | --- |
| `2026-07-28` | No handshake; optionally call `server/discover`. | `server/discover`, `tools/list`, `tools/call` | No |
| `2025-11-25`, `2025-06-18` | Call `initialize`. | `initialize`, `ping`, `tools/list`, `tools/call` | No |
| `2025-03-26` | Call `initialize`. | `initialize`, `ping`, `tools/list`, `tools/call` | Yes, as a JSON array |

**Version `2026-07-28`.** A request uses this version when its
`params._meta.io.modelcontextprotocol/protocolVersion` or its
`MCP-Protocol-Version` header is `2026-07-28`. Every request must contain:

- `params._meta` with `io.modelcontextprotocol/protocolVersion` and
  `io.modelcontextprotocol/clientCapabilities`;
- the headers `MCP-Protocol-Version` and `Mcp-Method`, plus `Mcp-Name` (the tool
  name) for `tools/call`, with the same values as the body. A header value with
  non-ASCII characters can be sent as `=?base64?<Base64 of UTF-8 value>?=`.

`server/discover` returns the supported versions, capabilities, server name and
usage instructions. Results include `"resultType": "complete"`; `server/discover`
and `tools/list` results may be cached privately for 5 minutes (`ttlMs`,
`cacheScope`).

**Older versions.** `initialize` answers with the requested version when it is one
of the older versions above, and with `2025-11-25` otherwise. Later requests should
send the `MCP-Protocol-Version` header; without it, `2025-03-26` is assumed. The
server does not remember `initialize`, so any request may be sent at any time.

#### Tools

All tools are read-only. `tools/list` returns each tool's argument schema.

| Tool | Required arguments | Optional arguments | Returns |
| --- | --- | --- | --- |
| `list_projects` | – | – | Open dbt projects: `name`, `project` (directory) and `path` to `dbt_project.yml`. Up to 100. |
| `list_resources` | `project` | – | Resources of one project. Up to 1,000. |
| `resolve_reference` | `project`, `reference` | `file`, `offset` | Resources the reference points to. Without `offset`, looks up `reference` as an exact resource name; with `file` and `offset`, resolves the reference at that character offset in the file. Up to 100. |
| `get_documentation` | `project`, `resource_id` | – | The resource, its `description` and `columns` (`name`, `type`, `description`). Up to 500 columns. |
| `get_lineage` | `project`, `resource_id` | `direction` | Direct dependency `edges` (`from`, `to`) one step away. Call again on a neighbor to go further. Up to 1,000 edges. |
| `get_column_lineage` | `project`, `resource_id`, `column` | `direction` | Direct column `edges` (`column`, `expression`, `input_column`, `resource`, `input`, `complete`, `direct`) and multi-step `paths` (`columns`, `complete`), with `paths_truncated`. Up to 1,000 edges and 50 paths of 20 columns. |
| `resolve_selection` | `project`, `selection` | `exclude` | Resources matched by a selection such as `+orders`, and `problems` for terms that could not be resolved. Up to 1,000. |

Argument rules:

- `project` is a directory returned by `list_projects` or a project name that
  matches exactly one open project.
- `resource_id` is a dbt unique ID such as `model.analytics.report`.
- `file` is an absolute path or a path relative to the project directory.
- `direction` is `upstream` (default) or `downstream`.
- `offset` is a non-negative integer; every other argument is a string of at most
  16,384 characters. Unknown arguments and empty required arguments are rejected.

Each returned resource contains `resource_id`, `project`, `kind`, `name`, `owner`,
`package`, `version`, `path`, `offset`, `line`, `stale` and `provenance`
(`current source` or `local artifact`). A column also has `relation_resource_id`.

#### Results

A `tools/call` result contains the answer twice: as an object in
`structuredContent` and as JSON text in `content`. Every answer has `status`,
`message` and `schema_version` (`1.0`).

| `status` | Meaning |
| --- | --- |
| `ok` | The answer is complete. |
| `partial` | A list hit its limit, the resource is stale, or the answer comes from static analysis that may miss dependencies. `get_lineage` and `get_column_lineage` are always `partial`. |
| `missing` | Nothing matched. |
| `ambiguous` | The project or resource did not match exactly one item. Resource lookups list the matches in `candidates`. |
| `disposed` | The project was closed; the result sets `isError: true`. |

Arguments that break the rules above return a result with `isError: true` and an
explanation in `content`, not a JSON-RPC error.

#### Errors and limits

| Condition | HTTP status | JSON-RPC error |
| --- | --- | --- |
| Path other than `/mcp` | 404 | `-32600` |
| Method other than POST | 405 | `-32600` |
| `Origin` header present (sent by browsers) | 403 | `-32600` |
| Missing or wrong token | 401 | `-32600` |
| Body larger than 64 KiB | 413 | `-32600` |
| Body is not valid JSON | 400 | `-32700` |
| Not a JSON-RPC message, or a batch outside `2025-03-26` | 400 | `-32600` |
| Unsupported protocol version | 400 | `-32022`; `data` lists `supported` and `requested` versions |
| `2026-07-28` request without required `params._meta` | 400 | `-32602` |
| `2026-07-28` headers not matching the body | 400 | `-32020` |
| Unknown method | 404 for `2026-07-28`, 200 otherwise | `-32601` |
| Unknown tool | 200 | `-32602` |
| Project closed | 200 | `-32603` |
| Response larger than 2 MB, or another failure | 500 | `-32603` |

Notifications and client responses are accepted with HTTP 202 and no body. A
`tools/call` result larger than 2 MB is replaced by an `isError` result asking you
to narrow the request. Requests running longer than 30 seconds are canceled and
their connection is closed.

#### Examples

Version `2026-07-28`, sent with the headers `MCP-Protocol-Version: 2026-07-28`,
`Mcp-Method: tools/call` and `Mcp-Name: get_documentation`:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientCapabilities": {}
    },
    "name": "get_documentation",
    "arguments": {
      "project": "/work/analytics",
      "resource_id": "model.analytics.report"
    }
  }
}
```

Version `2025-06-18`, sent after `initialize` with the header
`MCP-Protocol-Version: 2025-06-18`:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "get_column_lineage",
    "arguments": {
      "project": "/work/analytics",
      "resource_id": "model.analytics.report",
      "column": "amount",
      "direction": "upstream"
    }
  }
}
```
