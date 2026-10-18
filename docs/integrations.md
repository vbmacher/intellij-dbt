# Change impact and integrations

[User guide](getting_started.md) · [Configuration](configuration.md)

Open these actions from **Tools**:

| Action | Use it to |
| --- | --- |
| **dbt Change Impact** | Compare the working project with a Git revision and review potentially affected resources. |
| **dbt Downstream Consumers** | Find declared exposures and cached Metabase consumers. |
| **dbt Airflow Context** | View declared Airflow task associations. |
| **dbt Local Agent Tools** | Start or stop read-only project access for a local client. |

Reading this information does not run dbt or query a warehouse.

## Review the impact of Git changes

Open **Tools → dbt Change Impact** in a Git checkout. Enter a branch, tag or commit,
such as `main`. The dialog shows the resolved commit and compares it with current
source, including unsaved edits.

The heading lists added, removed, moved and changed resources. Separate package and
model-version identities remain separate. The tabs show:

- **Affected resources (potential)**: downstream resources and the dependency paths
  that connect them to changed resources. Select an entry and click **Go to definition**.
- **Consumers**: declared exposures and Metabase consumers already in the cache.
- **Airflow**: declared task associations and unresolved mappings.
- **Validation plan / limitations**: the suggested selection and reasons the analysis
  may be incomplete.

Click **Copy selection** to copy the suggested dbt selector terms. Review the
selection before clicking **Build reviewed selection**: this starts a build using
the owning project's [execution settings](configuration.md#workspace-and-runner-settings).
See [Workspace](workspace.md) for build output and cancellation. Changes spanning
multiple execution projects require separate builds; removed-only changes have
no runnable selection.

The affected resources may need validation; the list does not prove their output
changed or that a downstream resource will fail. Every declared or projected column
of a changed resource is treated as potentially changed. `SELECT *`, dynamic Jinja,
unsupported SQL, deleted dependencies and historical configuration changes can
leave results incomplete. Dependency paths follow the current project. Historical
files use the current configured paths and exclusions; configuration changes are
reported as limitations.

Large baselines can exceed the source limits: 1,500 relevant files, 32 MiB total,
2 MiB per Git read, or 10 seconds per read. The dialog reports incomplete analysis;
review its limitations before using the selection.

## Find downstream consumers

Open **Tools → dbt Downstream Consumers** from a model to list its downstream
exposures and cached Metabase consumers. Without a model context, the action lists
all consumers. Exposures declared in YAML are available offline.

Each entry shows the consumer name, type, owner and evidence for its association.
Select an entry and click **Open consumer** for its browser URL or **Go to exposure
definition** for a declared exposure. Choose a column instead of **All columns**
to narrow Metabase matches; entries with unknown column use remain visible as
model-level consumers.

For Metabase, first [configure the connection](configuration.md#metabase-connection),
then click **Refresh Metabase metadata**. Refreshing reads metadata without executing
questions. There is no background polling. **Configure…** opens the integration settings.

The view shows the cache timestamp and marks it stale after 15 minutes. Closing the
project discards the cache. A failed refresh leaves previous data and its timestamp
available and shows the error. Missing consumers can reflect incomplete permissions,
refresh limits or an empty cache.

A **direct physical metadata** Metabase match requires a unique physical database, schema and table.
Missing database information, competing package/version identities and resource names
mentioned in native SQL produce **inferred** matches. Native SQL mentions do not
establish column lineage. Dashboard associations follow the questions attached to
each dashboard; inaccessible dashboard details are omitted with an explanation.

## Inspect Airflow context

First [declare the task mappings](configuration.md#airflow-task-mappings). Open
**Tools → dbt Airflow Context** to see each DAG/task, selected dbt resources,
exclusions, declared upstream ingestion tasks and any unresolved selection.

Select an entry and click **Open mapping definition** to open its exact declaration.
Click **Open Airflow task** to open its browser link when a base URL is configured.

This view uses declared mappings. It does not discover associations by importing
or running Python DAG files. Missing or dynamic selections remain unknown, so an
unresolved task does not imply that no dbt resources are affected.

## Give a local assistant read-only project access

First [enable local agent tools and check client compatibility](configuration.md#local-agent-tools).
Open **Tools → dbt Local Agent Tools**, then:

1. Click **Start**.
2. Click **Copy endpoint and authorization** and give those details to your local client.
3. Click **Stop** when finished.

The dialog displays whether the service is running. **Configure…** opens its opt-in
setting. Closing the project or disabling the option also stops the service. Each
restart creates a new temporary token; copied details from a previous start stop
working. Keep the token private.

The client can list projects and resources, resolve references and selections,
read documentation, inspect resource or column lineage, and suggest a validation
selection from Git changes. It cannot run dbt or change warehouse data. No AI
subscription or warehouse connection is required.

Results identify their source and explain missing, ambiguous, unsupported or partial
information. Resource lineage returns one dependency step per call; column lineage
can include longer paths, stopping at unknown origins, cycles or response limits.
A partial result does not establish a complete dependency chain. Source listings
and documentation remain available without dbt artifacts, but some lineage may
be unavailable. Review project-authored descriptions and expression evidence before
sending results to an external assistant.
