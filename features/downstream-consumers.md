# Downstream exposures and Metabase consumers

[Capability index](README.md) · [User guide](../docs/workspace.md#exposures)

Find reports and dashboards that may be affected by a change to a dbt resource.
The **Exposures** tab in the **dbt** tool window combines declared dbt exposures
with cached Metabase questions and dashboards. It does not run dbt, execute
questions or query the warehouse.

## Scope and results

The tab follows the resource in the active editor, independently of a pinned
Lineage focus. It shows exposures downstream of that resource and Metabase
consumers matched to its physical relation: its database, schema and table name.
Without a recognized resource, it lists all known exposures and cached Metabase
consumers in the IDE project. Opening the Tools action selects the same tab.

Each row includes a name, consumer type, owner, association reason and supporting
location or relation. Exposure owners come from their declarations; Metabase owner
fields are currently blank. One question or dashboard can have several rows when
it matches several columns or resources.

[Workspace](../docs/workspace.md#exposures) describes the tab's controls and
navigation. [Configuration](../docs/configuration.md#metabase-connection) defines
the connection and credentials.

## Understand the association

| Label | Meaning |
| --- | --- |
| **declared** | A dbt exposure depends on the focused resource, directly or through downstream dbt dependencies. Local YAML declarations work without Metabase. |
| **declared (local artifact)** / **declared (stale local artifact)** | The exposure comes from local dbt metadata; the latter warns that the metadata is stale. |
| **direct physical metadata** | A Metabase question's source-table metadata matches the database, schema and table of one current, unversioned dbt resource. |
| **inferred; stale metadata, target/package/version or SQL ownership unproven** | A question matches through SQL text or physical metadata that cannot establish a unique, current resource. Treat it as a candidate to inspect. |
| **inferred via question** | A dashboard contains a matched question. Its association and any column match come from that question. |

Metabase matches require a known physical relation from local resource metadata.
SQL matching looks for that relation's name in the question text; it does not
resolve SQL references or establish column usage. Metabase consumers of a
downstream model are not automatically attributed to its upstream models.

### Filter by column

**All columns** shows every match in the current scope. Choose a known column to
keep Metabase rows matched to that column and hide rows matched only to other
columns. Column matches come from field references in Metabase's structured query
metadata.

Exposures and Metabase rows without a known column remain visible with
**model-level; column use unknown**. Their presence does not prove that they use
the selected column, and an empty result does not prove that changing the column
is safe.

## Example

In `models/exposures.yml`, declare a dashboard that depends on `fct_orders`:

```yaml
version: 2
exposures:
  - name: orders_dashboard
    type: dashboard
    url: https://example.org/orders
    depends_on:
      - ref('fct_orders')
    owner:
      name: Finance Analyst
      email: finance@example.org
```

Suppose `fct_orders` has columns `id` and `gross_amount`, and its known physical
relation is `warehouse.analytics.fct_orders`. Metabase contains:

- **Revenue by amount**, a structured question referencing `gross_amount`.
- **Finance dashboard**, containing **Revenue by amount**.
- **Orders overview**, a native SQL question containing
  `select count(*) from warehouse.analytics.fct_orders`.

Open `fct_orders.sql`, select **dbt → Exposures** and refresh Metabase metadata. With
**All columns**, all four consumers appear:

![All columns shows the declared exposure, two Metabase questions and the dashboard inferred through Revenue by amount.](../docs/screenshots/downstream-consumers-all.png)

Choose **id**. **Revenue by amount** and **Finance dashboard** disappear because
their known column match is `gross_amount`. The exposure and **Orders overview**
remain, both labeled **model-level; column use unknown**:

![The id filter retains the exposure and native SQL question with column use unknown.](../docs/screenshots/downstream-consumers-column.png)

These images render the production tab contents in IntelliJ’s tabbed content UI
with a local dbt project and local HTTP fixture metadata. They do not show an
installed IDE window frame.

## Refresh, failures and completeness

Metabase metadata is fetched only when you click **Refresh Metabase metadata**.
Before the first successful refresh, the tab can still show local exposures.
The status shows the last successful refresh time; cached data older than
15 minutes is labeled **stale** when the tab updates. There is no automatic
polling. The cache lasts for the open IDE project and is cleared when integration
settings are applied.

A failed refresh keeps the previous consumers and refresh time, and displays an
error. For example, an HTTP 503 response leaves the previous list available:

![An HTTP 503 refresh error is shown above the retained consumer list.](../docs/screenshots/downstream-consumers-refresh-error.png)

The list can be incomplete:

- More than 20 databases or 1,000 questions causes refresh to fail and retain the
  previous cache; these lists are not silently truncated.
- Only the first 100 dashboards are inspected; a larger list is marked partial.
  Dashboards whose details cannot be fetched are omitted.
- Missing permissions, unavailable physical metadata, and unsupported query
  shapes can leave consumers undiscovered. A successful refresh does not prove
  that every downstream report is listed.

## Interactions

[Project context](project-context.md) supplies resource identities and local
metadata. [Resource lineage](resource-lineage.md) supplies downstream exposure
dependencies. [Column lineage](column-lineage.md) follows dependencies between dbt
columns; the Exposures tab's column filter uses Metabase metadata instead.
