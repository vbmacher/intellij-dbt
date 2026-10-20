# Change impact and integrations

[User guide](getting_started.md) · [Configuration](configuration.md)

Open these actions from **Tools**:

| Action | Use it to |
| --- | --- |
| **dbt Change Impact** | Compare the working project with a Git revision and review potentially affected resources. |
| **dbt Downstream Consumers** | Find declared exposures and cached Metabase consumers. |
| **dbt Airflow Context** | View automatically detected local Airflow pipelines. |
| **dbt Local MCP Server** | Start or stop read-only project access for an AI assistant on this computer. |

## Review the impact of Git changes

Open **Tools → dbt Change Impact** in a Git checkout and enter a branch, tag or
commit, such as `main`. The dialog compares that commit with the current source,
including unsaved edits, and lists added, removed, moved and changed resources.
The tabs show:

- **Affected resources (potential)**: downstream resources and the dependency path
  from each changed resource. Select an entry and click **Go to definition**.
- **Consumers**: declared exposures and cached Metabase consumers.
- **Airflow**: detected task associations and unresolved selections.
- **Validation plan / limitations**: the suggested selection and what the analysis
  could not resolve.

Click **Copy selection** to copy the suggested selector terms, or **Build reviewed
selection** to build them with the owning project's
[execution settings](configuration.md#workspace-execution-defaults); output
appears in the [Run window](execution.md#commands). Changes spanning several execution
projects need separate builds, and removed-only changes have no runnable selection.

Analysis fails for a baseline with more than 1,500 relevant files or 32 MiB of
source, or when a single Git read exceeds 2 MiB or 10 seconds.

## Find downstream consumers

Open **Tools → dbt Downstream Consumers** from a model to list its downstream
exposures and cached Metabase consumers; without a model, it lists all consumers.
Each entry shows the consumer's name, type, owner and why it is associated.

Select an entry and click **Open consumer** to open it in the browser, or **Go to
exposure definition** for a declared exposure. Choose a column instead of **All
columns** to narrow Metabase matches.

For Metabase, [configure the connection](configuration.md#metabase-connection),
then click **Refresh Metabase metadata**. Refresh is manual. The cache is marked
stale after 15 minutes and discarded when the project closes; a failed refresh
keeps the previous data and shows the error. **Configure…** opens the settings.

A Metabase match is **direct physical metadata** when the question's database,
schema and table identify one resource, and **inferred** when it comes from native
SQL text or incomplete metadata.

## Inspect Airflow context

First [choose local DAG folders](configuration.md#airflow-dag-discovery). Open
**Tools → dbt Airflow Context** to see detected DAGs and jobs, schedules, selected dbt
resources, exclusions, ingestion destinations and unresolved selections.

The scanner recognizes dbt keyword arguments such as `dbt_selector`, `dbt_select`
and `select`, nested job dictionaries, and shell commands such as
`dbt build --select tag:finance+ --exclude old_report`. `dbt_selector_name` and
`selector_name`, or shell `--selector`, name a definition in `selectors.yml`.
The `selector` key in a Python job dictionary contains a selection expression.

Ingestion pipelines are associated through explicit destination keys such as
`bq_dataset` with `bq_table`, `destination_table`, or a full destination table
reference. A generic `dataset` / `table` pair does not establish ingestion.
Matches show whether the schema and table matched exactly, matched without regard
to case, or used a table name unique to the dbt project and its packages.
Detected ingestion pipelines upstream of a dbt selection appear with that job.

Select an entry and click **Open DAG definition** to open its Python selection or
destination entry. **Open Airflow task** opens a browser link when configured.
**Configure…** opens integration settings. When code computes a pipeline name at
runtime, the Python filename supplies the displayed name. Computed selections
remain unresolved; the plugin cannot determine runtime schedules or execution status.

## Connect an AI assistant with the local MCP server

An AI assistant that only reads your files sees raw SQL and Jinja. It does not
know which model `ref('orders')` points to, where a column comes from, or which
models a change affects. To learn this on its own, it would have to run dbt,
which needs a warehouse connection and credentials. The local MCP server gives
the assistant the answers the IDE already has, without running dbt and without
exposing credentials.

Use it when you ask an assistant to:

- write or check model and column descriptions (`get_documentation`);
- explain where a column comes from or what uses it (`get_column_lineage`, `get_lineage`);
- find which resource a `ref(...)` or `source(...)` points to (`resolve_reference`);
- choose which models to rebuild or test after your Git changes (`suggest_validation_selection`);
- check which resources a selector such as `+orders` matches (`resolve_selection`).

You can use it only when:

- the assistant runs on the same computer as the IDE;
- the dbt project is open in the IDE and the server is started;
- the assistant supports MCP over HTTP (see below).

Do not use it to run dbt, query the warehouse or change files. It has no tools
for that.

First [enable the local MCP server](configuration.md#local-mcp-server). Open
**Tools → dbt Local MCP Server**, then:

1. Click **Start**.
2. Click **Copy MCP client configuration** and paste it into your MCP client's
   server configuration. If the client asks for fields instead, click
   **Copy endpoint and authorization**: it copies the server URL and the
   `Authorization` header.
3. Click **Stop** when finished.

The copied configuration has this shape:

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

The client must support MCP over HTTP (Streamable HTTP) with custom headers. A
client that only starts local commands (stdio) needs an HTTP bridge. See
[Local MCP server](configuration.md#local-mcp-server) for supported MCP versions.

Each start creates a new port and token, so copy the details again after restarting. Closing
the project or disabling the option also stops the service. Keep the token private.

The client can list projects and resources, resolve references and selections,
read documentation, trace resource and column lineage, and suggest a validation
selection from Git changes. It cannot run dbt or change warehouse data. Resource
lineage returns one dependency step per call. Review project descriptions in the
results before sending them to an external assistant.
