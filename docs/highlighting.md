# Syntax highlighting

Enable IntelliJ's bundled **TextMate Bundles** plugin in Settings > Plugins.
The dbt plugin supplies SQL/Jinja, YAML/Jinja and Markdown/Jinja grammars; no
manual bundle import or global SQL file association is required.

Highlighting applies to configured dbt resource paths and project configuration.
Generated output, `.dbtignore` matches and unrelated files keep their existing
highlighting. SQL resources in Community retain plain-text PSI; YAML, Markdown
and installed SQL language parsers remain available for navigation.

For example, the whole comment receives comment coloring, while the statement,
SQL keywords, Jinja call and quoted argument receive their corresponding colors:

```sql
{# Resolve the root of the persisted dataset. #}
{% set dataset = 'uat_packaging_vpgm' %}
select * from {{ ref('orders') }}
```

The grammar also handles Jinja inside SQL strings and comments, whitespace
control, raw blocks, and unfinished input while editing. Editor colors come
from the current IDE theme. Navigation and Find Usages do not require TextMate
and remain available if it is disabled.

The bundled engine is provided by IntelliJ, not copied into the dbt distribution.
Grammar provenance and license notices are in `textmate/dbt/` in the plugin ZIP.
