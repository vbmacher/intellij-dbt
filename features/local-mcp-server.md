# Local read-only MCP server

[Capability index](README.md) · [User guide](../docs/integrations.md#connect-an-ai-assistant-with-the-local-mcp-server)

Give an AI assistant on the same computer read-only access to the IDE's resolved
dbt resources, documentation and dependencies.

## Contract

- **Inputs:** user opt-in, explicit service start and authenticated MCP requests over Streamable HTTP.
- **Outputs:** project/resource lists, reference/selection resolution, documentation, and resource/column lineage.
- **Controls:** the Services tool window owns Start, Stop and connection-copy
  actions. Start requires opt-in; Stop and copying require a running server. Copy
  actions confirm success. The status panel omits authorization;
  connection-copy actions include it.
- The service binds to loopback with a fresh bearer token on each start. Stop, project close or disabling opt-in ends access.
- It cannot run dbt, query the warehouse or change files. Responses omit profile
  and environment secret values and warehouse rows.
- Resource lineage returns one dependency step per request. Resource and column
  lineage results always carry `partial` status because static analysis can miss
  dependencies. Other tools also mark results partial when their limits are reached.
- The service exposes MCP tools at `/mcp` over Streamable HTTP, with JSON responses
  and no event streams, sessions or stdio transport. Supported versions, headers,
  error codes and bounds are owned by the
  [MCP protocol reference](../docs/integrations.md#mcp-protocol-reference).

## Example

An authenticated MCP client using version `2026-07-28` asks the running service for
its tools. The request carries the headers `MCP-Protocol-Version: 2026-07-28` and
`Mcp-Method: tools/list`:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {
    "_meta": {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientCapabilities": {}
    }
  }
}
```

It can then call `get_documentation` for a project/resource identity returned by `list_projects` and `list_resources`. These requests do not run dbt.

## Interactions

Provides read-only access to the same [project context](project-context.md), [navigation](navigation.md), [lineage](column-lineage.md), and [selection](selection-review.md) contracts.
