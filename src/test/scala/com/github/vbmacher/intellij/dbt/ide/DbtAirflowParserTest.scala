package com.github.vbmacher.intellij.dbt.ide

import org.junit.Assert._
import org.junit.Test

final class DbtAirflowParserTest {
  private def parse(source: String) = DbtAirflowParser.parse("/dags/example.py", source)

  @Test def readsConfigObjectsAndIgnoresFunctionDefaultsAndNone(): Unit = {
    val pipelines = parse("""
def helper(dbt_selector=None, dbt_exclude=None): pass
def get():
    return [
        DbtTransformationConfig(name="dbt_gtm", schedule="57 9 * * *",
            dbt_selector="tag:gtm_1000WIB", dbt_exclude="tag:slow tag:flaky",
            dbt_indirect_selection=DbtIndirectSelection.CAUTIOUS),
        DbtTransformationConfig(name="noop", dbt_selector=None),
    ]
""")
    assertEquals(1, pipelines.size)
    val p = pipelines.head
    assertEquals("dbt_gtm", p.name); assertEquals("57 9 * * *", p.schedule)
    assertEquals("tag:gtm_1000WIB", p.sites.head.select)
    assertEquals("tag:slow tag:flaky", p.sites.head.exclude)
    assertEquals("DbtIndirectSelection.CAUTIOUS", p.sites.head.indirect)
  }

  @Test def evaluatesConstantsConcatenationListsJoinsAndAdjacentStrings(): Unit = {
    val p = parse("""
BASE = ["tag:a", "tag:b"]
NAME = "night" + "ly"
X = Cfg(name=NAME, dbt_selector=" ".join(BASE + ["m1+"]), dbt_exclude=",".join(["tag:x", "tag:y"]))
Y = Cfg(name="other", dbt_selector=("tag:" "finance"))
""")
    assertEquals("nightly", p.head.name)
    assertEquals("tag:a tag:b m1+", p.head.sites.head.select)
    assertEquals("tag:x,tag:y", p.head.sites.head.exclude)
    assertEquals("tag:finance", p(1).sites.head.select)
  }

  @Test def nestedJobsInheritExcludesScheduleAndPipelineName(): Unit = {
    val p = parse("""
dag_definitions = [
  {"dag_name_suffix": "hourly", "schedule": "0 * * * *", "excludes": ["tag:skip"],
   "dbt_jobs": [{"selector": "tag:hourly"}, {"name": "j2", "selector": "1+m2"}]},
]
""").head
    assertEquals("hourly", p.name); assertEquals("0 * * * *", p.schedule)
    assertEquals(Vector("tag:hourly", "1+m2"), p.sites.map(_.select))
    assertEquals(Vector("tag:skip", "tag:skip"), p.sites.map(_.exclude))
    assertEquals("j2", p.sites(1).task)
  }

  @Test def shellFlagsNamedSelectorsAndSourceLocations(): Unit = {
    val source = """with DAG(dag_id="daily", schedule_interval="@daily"):
    task = BashOperator(task_id="build", bash_command="dbt build --select tag:a +m1 --exclude m2 --target prod")
"""
    val p = parse(source).head
    assertEquals("daily", p.name); assertEquals("@daily", p.schedule)
    assertEquals("build", p.sites.head.task)
    assertEquals("tag:a +m1", p.sites.head.select); assertEquals("m2", p.sites.head.exclude)
    assertEquals(2, p.sites.head.line)
    assertTrue(source.substring(p.sites.head.offset).startsWith("\"dbt build"))
    assertEquals(Some(("", "", "nightly")), DbtAirflowParser.shell("dbt run --selector nightly"))
    assertEquals(Some(("tag:a", "", "")), DbtAirflowParser.shell("dbt ls -s tag:a"))
    assertEquals(Some(("tag:a tag:b", "tag:c", "")), DbtAirflowParser.shell("dbt run --models='tag:a tag:b' --exclude=tag:c"))
    assertTrue(DbtAirflowParser.shell("echo hi").isEmpty)
    assertEquals("nightly", parse("Cfg(dbt_selector_name='nightly')").head.sites.head.named)
  }

  @Test def dynamicValuesNeverBecomeAllModelsAndCommentsAreIgnored(): Unit = {
    val p = parse("""
# Cfg(dbt_selector="must_not_exist")
X = Cfg(name="n", dbt_selector=f"tag:{team}")
Y = Cfg(name="exclude", dbt_select="orders", dbt_exclude=get_excludes())
Z = Cfg(name="named", selector_name=read_name())
""")
    assertEquals(3, p.size)
    assertTrue(p.forall(_.sites.head.dynamic))
    assertEquals("", p.head.sites.head.select)
    assertEquals("orders", p(1).sites.head.select)
    assertTrue(parse("def helper(select='orders'): pass\nCfg(select=None, exclude=dynamic_value)").isEmpty)
    assertTrue(parse("SELECTION = 'tag:' + team\nCfg(select=SELECTION)").head.sites.head.dynamic)
  }

  @Test def discoversIngestionTargetsWithoutTreatingReadersAsWriters(): Unit = {
    val source = """
T = [{"name": "tia", "bq_dataset": "raw", "bq_table": "Orders"},
     {"bq_table": "submitted", "load": {"full_table_schema_reference": f"{PROJECT}.raw.submitted"}},
     {"name": "partition_check", "dataset": "raw", "table": "ignore_me"},
     {"bq_dataset": ds, "bq_table": f"table_{n}"}]
@dag(dag_id=get_dag_id(__file__), schedule="@hourly")
def ingest(): pass
"""
    val p = parse(source).head
    assertEquals("example", p.name); assertEquals("@hourly", p.schedule)
    assertEquals(Set((Some("raw"), "Orders"), (None, "submitted"), (Some("raw"), "submitted")),
      p.ingests.map(r => r.dataset -> r.table).toSet)
    assertTrue(p.sites.isEmpty)
    assertTrue(parse("X={'dataset':'d','table':'t'}").isEmpty)
  }
}
