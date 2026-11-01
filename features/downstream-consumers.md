# Downstream exposures and Metabase consumers

[Capability index](README.md) · [User guide](../docs/workspace.md#exposures)

Find reports and dashboards that may be affected by a change to a dbt resource.

## Contract

- **Inputs:** the resource in the active editor, local exposure declarations,
  physical relation metadata and explicitly refreshed Metabase metadata.
- **Outputs:** declared exposures and candidate Metabase questions and dashboards,
  with association reasons and supporting locations or relation names.
- **Scope:** follows the active editor independently of pinned Lineage focus.
  Without a recognized resource, results include all known exposures and cached
  Metabase consumers in the IDE project.
- **Rows:** identify name, type, owner when available, association reason and
  evidence. Exposure owners come from their declarations; Metabase owner fields
  are empty. A consumer can have several rows for different columns or resources.
- Browsing and column filtering do not run dbt, execute questions or query the
  warehouse. Local exposures remain available without a Metabase connection.
- **Column scope:** structured-query field references can establish column matches.
  A column-scoped result omits matches known only for other columns. Exposures and
  rows without column evidence remain visible as **model-level; column use unknown**.
- **Refresh:** remote metadata is fetched only on request. A failure retains the
  previous consumers and last successful refresh time. Cached results are labeled
  stale when appropriate; there is no automatic polling. Applying integration
  settings clears the cache.
- **Completeness:** excessive database or question lists fail the refresh rather
  than silently truncate it. Dashboard inspection is bounded and marks larger
  lists partial; unavailable dashboard details are omitted. Limits and cache
  lifetime are owned by [Workspace](../docs/workspace.md#exposures).
- Missing permissions, physical metadata or supported query structures can leave
  consumers undiscovered. Empty results never prove that a change is safe.

## Association meaning

| Label | Meaning |
| --- | --- |
| **declared** | A local dbt exposure depends on the resource directly or through downstream dbt dependencies. |
| **declared (local artifact)** / **declared (stale local artifact)** | The exposure comes from local metadata, with staleness reported explicitly. |
| **direct physical metadata** | A question's table metadata matches the database, schema and table of one current, unversioned dbt resource. |
| **inferred; stale metadata, target/package/version or SQL ownership unproven** | SQL text or physical metadata identifies a candidate but cannot establish a unique, current resource. |
| **inferred via question** | A dashboard contains a matched question and inherits its resource and column association. |

Metabase matching requires a known physical relation. SQL text matching finds its
relation name without resolving SQL references or proving column usage. Metabase
consumers of a downstream model are not attributed automatically to upstream models.

## Example

An exposure `orders_dashboard` depends on `fct_orders`, whose physical relation is
`warehouse.analytics.fct_orders`. Metabase contains a **Revenue by amount** question
using `gross_amount`, a **Finance dashboard** containing that question and an
**Orders overview** SQL question naming the model's relation.

The resource-scoped result contains all four consumers:

![The exposure, two Metabase questions and the dashboard associated with fct_orders.](../docs/screenshots/downstream-consumers-all.png)

The `id` column-scoped result omits **Revenue by amount** and **Finance dashboard**
because their known match is `gross_amount`. The exposure and **Orders overview**
remain with column use unknown:

![The id result retains the exposure and SQL question without proven column use.](../docs/screenshots/downstream-consumers-column.png)

A failed refresh preserves those results:

![An HTTP 503 refresh error above the retained consumer list.](../docs/screenshots/downstream-consumers-refresh-error.png)

## Interactions

[Project context](project-context.md) supplies identities and physical metadata.
[Resource lineage](resource-lineage.md) supplies exposure dependencies.
[Column lineage](column-lineage.md) describes dbt column dependencies; Metabase
column associations use structured-query metadata instead. Connection settings
belong to [Configuration](../docs/configuration.md#metabase-connection); controls,
navigation and the declaration example belong to [Workspace](../docs/workspace.md#exposures).
