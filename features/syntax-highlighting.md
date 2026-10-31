# SQL, YAML and Markdown template highlighting

[Capability index](README.md) · [User guide](../docs/editor.md#syntax-highlighting)

Distinguish SQL and Jinja syntax while editing dbt resources and configuration.

## Contract

- **Inputs:** recognized SQL/Jinja, YAML/Jinja and Markdown/Jinja files in supported project locations.
- **Output:** theme-aware syntax colors for keywords, expressions, strings and comments, including whitespace control and raw blocks.
- Active Jinja can be highlighted within SQL strings/comments. Colors do not establish that a reference resolves or that SQL can execute.
- The TextMate prerequisite is documented in [Installation](../docs/installation.md).

## Example

```sql
{# Choose a model. #}
{% set model_name = 'orders' %}
select * from {{ ref(model_name) }}
```

Template tags, the string and SQL keywords receive their respective syntax colors.

## Screenshot

![SQL keywords, Jinja delimiters and quoted resource name use distinct colors.](screenshots/syntax-highlighting.png)

SQL keywords, Jinja delimiters and quoted resource name use distinct colors.

## Interactions

[Navigation](navigation.md) and [warnings](static-analysis.md) operate on names independently of syntax colors.
