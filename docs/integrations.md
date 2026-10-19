# Change impact and integrations

[User guide](getting_started.md) · [Configuration](configuration.md)

Open these actions from **Tools**:

| Action | Use it to |
| --- | --- |
| **dbt Change Impact** | Compare the working project with a Git revision and review potentially affected resources. |
| **dbt Downstream Consumers** | Find declared exposures and cached Metabase consumers. |
| **dbt Airflow Context** | View declared Airflow task associations. |
| **dbt Local Agent Tools** | Start or stop read-only project access for a local client. |

## Review the impact of Git changes

Open **Tools → dbt Change Impact** in a Git checkout and enter a branch, tag or
commit, such as `main`. The dialog compares that commit with the current source,
including unsaved edits, and lists added, removed, moved and changed resources.
The tabs show:

- **Affected resources (potential)**: downstream resources and the dependency path
  from each changed resource. Select an entry and click **Go to definition**.
- **Consumers**: declared exposures and cached Metabase consumers.
- **Airflow**: declared task associations and unresolved mappings.
- **Validation plan / limitations**: the suggested selection and what the analysis
  could not resolve.

Click **Copy selection** to copy the suggested selector terms, or **Build reviewed
selection** to build them with the owning project's
[execution settings](configuration.md#workspace-and-runner-settings); output
appears in the [Runner](workspace.md#runner). Changes spanning several execution
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

First [declare the task mappings](configuration.md#airflow-task-mappings). Open
**Tools → dbt Airflow Context** to see each DAG/task with its selected dbt
resources, exclusions, declared upstream ingestion tasks and unresolved selections.
Only declared mappings appear; DAG files are not read.

Select an entry and click **Open mapping definition** to open its declaration, or
**Open Airflow task** to open it in the browser when a base URL is configured.

## Give a local assistant read-only project access

First [enable local agent tools](configuration.md#local-agent-tools). Open
**Tools → dbt Local Agent Tools**, then:

1. Click **Start**.
2. Click **Copy endpoint and authorization** and give those details to your local client.
3. Click **Stop** when finished.

Each start creates a new token, so copy the details again after restarting. Closing
the project or disabling the option also stops the service. Keep the token private.

The client can list projects and resources, resolve references and selections,
read documentation, trace resource and column lineage, and suggest a validation
selection from Git changes. It cannot run dbt or change warehouse data. Resource
lineage returns one dependency step per call. Review project descriptions in the
results before sending them to an external assistant.
