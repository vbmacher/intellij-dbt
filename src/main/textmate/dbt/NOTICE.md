# dbt TextMate grammar provenance

The SQL, Jinja, YAML and Markdown grammars are adapted from dbt-labs/atom-dbt,
commit 9269fe0d1716605f1b8aeef90caa24a335700e8b:
https://github.com/dbt-labs/atom-dbt/tree/9269fe0d1716605f1b8aeef90caa24a335700e8b/grammars

The CSON files were mechanically converted to JSON with cson-parser 4.0.9.
Conversion is a development step; no Node.js dependency is needed at runtime.
The upstream Apache-2.0 license is retained in LICENSE.

Upstream attributes the Jinja grammar and YAML/Markdown wrappers to Daniel
Chatfield's atom-jinja2, commit 7f989f22bcaf9de24722b7dd63691a2d9a8d8619:
https://github.com/danielchatfield/atom-jinja2
Its package metadata declares MIT and is retained in JINJA-UPSTREAM-PACKAGE.json.
The MIT terms and attribution are retained in JINJA-LICENSE.

The SQL rules derive from atom/language-sql, commit
b62c7085304caec4ec9a36975b1e691fc33d4272:
https://github.com/atom/language-sql
Its MIT and original TextMate permission notices are retained in SQL-LICENSE.

Local adaptations:

- Private dbt grammar extensions avoid global .sql/.yml/.md associations.
- Trimmed raw blocks and plus/minus whitespace control are recognized.
- SQL strings and comments permit embedded Jinja; string fast paths exclude
  template openings.
- YAML/Markdown templates include Jinja injections inside host-language scopes.
- Markdown references IntelliJ's bundled text.html.markdown scope.

Host YAML/Markdown grammars and TextMate's engine come from IntelliJ's bundled
TextMate plugin and are not redistributed here.
