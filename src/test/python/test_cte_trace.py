"""Run with a Python environment containing dbt Core and dbt-duckdb."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


HOOK = Path(__file__).resolve().parents[2] / "main/resources/dbt"
MACROS = """-- The macro block starts below a header.
{% macro piece(n) %}
piece{{ n }} as (select {{ n }} as id, '😀' as label)
{% endmacro %}

{% macro packaging(n) %}
{% for i in range(n) %}{{ piece(i) }}{% if not loop.last %}, {% endif %}{% endfor %}
{% endmacro %}

{% macro choose(flag) %}
{% if flag %}yes as (select 1 as id){% else %}no as (select 0 as id){% endif %}
{% endmacro %}

{% macro transformed() %}{{ piece(3) | upper }}{% endmacro %}
"""


class CteTraceTest(unittest.TestCase):
    def compile(self, model, macros=MACROS, extra_macros=None):
        temporary = tempfile.TemporaryDirectory(prefix="dbt source mapping ")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "models").mkdir()
        (root / "macros").mkdir()
        (root / "dbt_project.yml").write_text("name: mapping\nversion: '1.0'\nprofile: mapping\n")
        (root / "profiles.yml").write_text(
            'mapping:\n  target: dev\n  outputs:\n    dev: {type: duckdb, path: ":memory:", threads: 1}\n')
        model_file, macro_file = root / "models/report.sql", root / "macros/helpers.sql"
        model_file.write_text(model, encoding="utf-8")
        macro_file.write_text(macros, encoding="utf-8")
        if extra_macros:
            (root / "macros/external.sql").write_text(extra_macros, encoding="utf-8")
        sources = [{"package": "mapping", "relative": str(path.relative_to(root)),
                    "path": str(path), "text": path.read_text(encoding="utf-8")}
                   for path in [model_file, macro_file]]
        request = {"token": "test-compilation", "model": str(model_file),
                   "report": str(root / "report.json"), "sources": sources}
        (root / "request.json").write_text(json.dumps(request), encoding="utf-8")
        env = dict(os.environ, DBT_PROFILES_DIR=str(root), DBT_PARTIAL_PARSE="false",
                   PYTHONDONTWRITEBYTECODE="1", DBT_SEND_ANONYMOUS_USAGE_STATS="false")
        command = [sys.executable, "-c", "from dbt.cli.main import cli; cli()", "compile",
                   "--project-dir", str(root), "--select", "report", "--no-use-colors"]
        baseline = subprocess.run(command, env=env, text=True, capture_output=True, timeout=60)
        self.assertEqual(0, baseline.returncode, baseline.stdout + baseline.stderr)
        expected = (root / "target/compiled/mapping/models/report.sql").read_text(encoding="utf-8")
        env.update(PYTHONPATH=str(HOOK) + os.pathsep + env.get("PYTHONPATH", ""),
                   DBT_IDE_CTE_TRACE=str(root / "request.json"))
        traced = subprocess.run(command, env=env, text=True, capture_output=True, timeout=60)
        self.assertEqual(0, traced.returncode, traced.stdout + traced.stderr)
        report = json.loads((root / "report.json").read_text(encoding="utf-8"))
        self.assertNotIn("error", report)
        self.assertEqual("test-compilation", report["token"])
        self.assertEqual(expected, report["sql"], "Instrumentation must preserve dbt's real rendered SQL")
        return report, sources

    @staticmethod
    def locations(report, needle):
        start = len(report["sql"].split(needle, 1)[0].encode("utf-16-le")) // 2
        return [origin for span in report["spans"] if span["start"] <= start < span["end"]
                for origin in span["origins"]]

    def test_nested_macros_loops_and_unicode_keep_expansion_instances(self):
        report, sources = self.compile("{{ config(materialized='view') }}\nwith {{ packaging(2) }}\nselect * from piece1")
        first = self.locations(report, "piece0")
        second = self.locations(report, "piece1")
        self.assertTrue(first)
        self.assertTrue(second)
        macro = sources[1]["path"]
        self.assertIn((macro, 2), [(o["path"], o["line"]) for o in first])
        self.assertIn((sources[0]["path"], 1), [(o["path"], o["line"]) for o in first])
        self.assertNotEqual(first[0]["instance"], second[0]["instance"])
        self.assertEqual(3, len(first), "Preserve leaf, packaging call and model call provenance")
        self.assertNotIn(0, [o["line"] for o in first if o["path"] == sources[0]["path"]])

    def test_repeated_calls_and_conditionals_map_only_rendered_branches(self):
        report, sources = self.compile("with {{ piece(0) }}, {{ piece(1) }}, {{ choose(true) }} select * from yes")
        first, second = self.locations(report, "piece0"), self.locations(report, "piece1")
        self.assertNotEqual(first[-1]["instance"], second[-1]["instance"])
        self.assertNotIn("no as", report["sql"])
        self.assertIn(sources[1]["path"], [o["path"] for o in self.locations(report, "yes as")])

    def test_plain_sql_and_whitespace_control_have_correct_source_lines(self):
        report, sources = self.compile("-- header\nwith first as (select 1),\nsecond as (select * from first)\nselect * from second")
        self.assertEqual(1, self.locations(report, "first as")[0]["line"])
        self.assertEqual(2, self.locations(report, "second as")[0]["line"])
        trimmed, _ = self.compile("{{ config(materialized='view') -}}\nwith {{ packaging(1) }} select * from piece0")
        self.assertEqual(1, self.locations(trimmed, "piece0")[-1]["line"])

    def test_transformation_drops_inner_mapping_instead_of_guessing(self):
        report, sources = self.compile("with {{ transformed() }} select * from PIECE3")
        origins = self.locations(report, "PIECE3")
        self.assertTrue(origins)
        self.assertFalse(any(o["path"] == sources[1]["path"] and o["line"] == 2 for o in origins))
        self.assertEqual(sources[0]["path"], origins[-1]["path"])

    def test_unavailable_macro_source_still_maps_its_known_callsite(self):
        report, sources = self.compile("with {{ external_cte() }} select * from external_result", extra_macros=
            "{% macro external_cte() %}external_result as (select 1 as id){% endmacro %}")
        origins = self.locations(report, "external_result as")
        self.assertEqual([sources[0]["path"]], [o["path"] for o in origins])
        self.assertEqual(0, origins[0]["line"])


if __name__ == "__main__":
    unittest.main()
