package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.JsonParser
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import java.awt.{Component, Container}
import java.nio.file.{Files, Path}
import javax.swing.{JButton, JCheckBox, JLabel, JList, JSpinner, JTable}
import org.junit.Assert._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

final class DbtCteControllerTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private final case class Fixture(panel: DbtCteController, file: com.intellij.psi.PsiFile, root: DbtRoot, directory: Path,
    runner: DbtRunner, events: ArrayBuffer[DbtRunnerEvent])
  private def components(component: Component): Vector[Component] = Vector(component) ++ (component match {
    case c: Container => c.getComponents.toVector.flatMap(components)
    case _ => Vector.empty
  })
  private var breakpoints = Set.empty[String]
  private def click(panel: DbtCteController, name: String): Unit = name match {
    case "Step" => panel.step()
    case "Stop" => panel.stop()
    case "Restart" => panel.restart()
    case "Step Back" => panel.back()
    case "Count all rows" => panel.count()
    case "Continue to breakpoint" => panel.resume()
  }
  private def messages(panel: DbtCteController): String = panel.message
  private def rows(panel: DbtCteController): Int = panel.result.map(_.rows.size).getOrElse(0)
  private def controller(path: String, frames: Vector[com.github.vbmacher.intellij.dbt.navigation.DbtCte]): DbtCteController =
    new DbtCteController(getProject, path, frames, () => (), (_, _) => (), c => breakpoints(c.name))
  private def await(message: String)(condition: => Boolean): Unit = PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 15)
  private def flushReads(): Unit = {
    com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
  }
  private def setup(sql: String = "with a as (select 1 as id), b as (select id from a) select * from b"): Fixture = {
    project()
    val file = configure("models/report.sql", sql + "<caret>")
    val w = DbtWorkspace.get(getProject)
    val root = w.root(file).get
    val dir = Files.createTempDirectory("dbt cte session with spaces ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(dir.toFile))
    Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\n")
    val executable = dir.resolve("fake dbt")
    Files.writeString(executable, """#!/usr/bin/python3
import json,os,sys,time
args=sys.argv[1:]
with open('calls.jsonl','a') as f: f.write(json.dumps(args)+'\n')
while os.path.exists('hold'): time.sleep(.01)
if os.path.exists('evidence'):
 def emit(name,data,level='debug',message='',stream=sys.stdout):
  data['node_info']={'unique_id':'sql_operation.analytics.inline_query','node_status':'executing'}
  print(json.dumps({'info':{'name':name,'invocation_id':'cte-preview-run','level':level,'msg':message},'data':data}),file=stream,flush=True)
 query=args[args.index('--inline')+1]
 sql='/* adapter query comment */\nselect * from (\n'+query+'\n) as preview limit '+args[args.index('--limit')+1]
 emit('MainReportVersion',{})
 emit('SQLQuery',{'sql':sql})
 if os.path.exists('fail'):
  before=sql[:sql.index('missing')]
  message='SQL compilation error: line '+str(before.count('\n')+1)+' at position '+str(len(before.rsplit('\n',1)[-1]))
  emit('CatchableExceptionOnRun',{},message=message,stream=sys.stderr)
  print('raw stderr retained',file=sys.stderr,flush=True)
  sys.exit(1)
 emit('ShowNode',{'unique_id':'sql_operation.analytics.inline_query'},level='info')
if os.path.exists('fail'):
 print('query failed at CTE',file=sys.stderr,flush=True)
 sys.exit(1)
print(json.dumps({'show':[{'value':args[args.index('--inline')+1]}]}),flush=True)
""")
    assertTrue(executable.toFile.setExecutable(true))
    root.runOptions.projectRoot = dir.toString
    root.runOptions.executable = executable.toString
    root.runOptions.target = "dev"
    val runner = new DbtRunner(getProject)
    Disposer.register(getTestRootDisposable, runner)
    com.intellij.testFramework.ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, getTestRootDisposable)
    val events = ArrayBuffer.empty[DbtRunnerEvent]
    runner.listen(getTestRootDisposable, event => events += event)
    val panel = controller(file.getVirtualFile.getPath, DbtSqlAnalysis.get(w, file).get.ctes)
    Disposer.register(getTestRootDisposable, panel)
    Fixture(panel, file, root, dir, runner, events)
  }
  private def calls(f: Fixture): Vector[Vector[String]] =
    if (!Files.exists(f.directory.resolve("calls.jsonl"))) Vector.empty
    else Files.readAllLines(f.directory.resolve("calls.jsonl")).asScala.toVector
      .map(line => JsonParser.parseString(line).getAsJsonArray.asScala.map(_.getAsString).toVector)

  def testManagedPreviewPreservesAdapterSqlAndStderrAndNavigatesItsAuthoredCteError(): Unit = {
    val source = "with orders as (select missing as id) select * from orders"
    val f = setup(source)
    Files.writeString(f.directory.resolve("evidence"), "")
    Files.writeString(f.directory.resolve("fail"), "")
    click(f.panel, "Step")
    await("Failed process publishes preview evidence without a result") {
      f.events.exists(e => !e.running && e.previewEvidence.nonEmpty) && !f.runner.isRunning
    }
    val failed = f.events.reverse.find(e => !e.running && e.previewEvidence.nonEmpty).get
    assertTrue(failed.result.isEmpty)
    assertFalse("A completed failed frame must not retain a running grid label", messages(f.panel).contains("Query running"))
    val report = failed.previewEvidence.get
    f.panel.choose(f.panel.selected)
    assertEquals("Selecting the current native frame preserves failure navigation", Some(report), f.panel.evidence)
    assertTrue(report.raw.contains("raw stderr retained"))
    assertTrue(report.adapterSql.get.startsWith("/* adapter query comment */"))
    assertEquals(source.indexOf("missing"), report.location.get.offset)
    val reportPanel = new DbtPreviewEvidencePanel(getProject)
    reportPanel.showEvidence(f.panel.evidence)
    val navigate = components(reportPanel).collectFirst { case b: JButton if b.getText == "Go to CTE error" => b }.get
    assertTrue(navigate.isEnabled)
    navigate.doClick()
    assertEquals(source.indexOf("missing"), com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject)
      .getSelectedTextEditor.getCaretModel.getOffset)
    assertEquals(1, calls(f).size)
    click(f.panel, "Restart")
    reportPanel.showEvidence(f.panel.evidence); assertFalse(navigate.isEnabled)
    Files.delete(f.directory.resolve("fail"))
    click(f.panel, "Step")
    await("Successful process retains its adapter-wrapped SQL") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    val result = f.events.reverseIterator.flatMap(_.result).next()
    assertTrue(result.sql.startsWith("/* adapter query comment */"))
    assertTrue(result.sql.contains(") as preview limit " + f.root.runOptions.previewLimit))
    assertEquals(calls(f).last(calls(f).last.indexOf("--inline") + 1), f.panel.evidence.get.submitted)
    assertTrue(calls(f).last.sliding(2).exists(_ == Vector("--log-format", "json")))
    assertTrue(calls(f).last.sliding(2).exists(_ == Vector("--log-level", "debug")))
    reportPanel.showEvidence(f.panel.evidence); assertFalse(navigate.isEnabled)
  }

  def testStopDuringPreparationNeverLaunchesAQueryAndRestartRequiresExplicitStep(): Unit = {
    val f = setup()
    WriteAction.run(() => { click(f.panel, "Step"); click(f.panel, "Stop") })
    flushReads()
    assertEquals(Vector.empty, calls(f))
    assertFalse(f.runner.isRunning)
    assertTrue(messages(f.panel).contains("Inspection stopped"))
    click(f.panel, "Restart"); click(f.panel, "Step")
    await("Restarted explicit step completes") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertTrue(calls(f).head.contains("dev"))
  }
  def testSourceAndTargetChangesDuringPreparationDiscardCommands(): Unit = {
    val f = setup()
    WriteAction.run(() => {
      click(f.panel, "Step")
      myFixture.getEditor.getDocument.setText("with changed as (select 9 as id) select * from changed")
      com.intellij.psi.PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    })
    flushReads(); assertEquals(Vector.empty, calls(f))
    WriteAction.run(() => {
      click(f.panel, "Step")
      f.root.runOptions.target = "prod"
      getProject.getMessageBus.syncPublisher(DbtProjectSettings.Changed).settingsChanged()
    })
    flushReads(); assertEquals(Vector.empty, calls(f))
    click(f.panel, "Step")
    await("Fresh source and target are queried") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertTrue(calls(f).head.contains("prod"))
    assertTrue(calls(f).head(calls(f).head.indexOf("--inline") + 1).contains("with changed"))
    assertEquals("changed", f.panel.frames.head.name)
  }
  def testRapidStepRequestsLaunchOnlyOneQueryAndCachedBackDoesNotExecute(): Unit = {
    val f = setup()
    WriteAction.run(() => { click(f.panel, "Step"); click(f.panel, "Step"); click(f.panel, "Step") })
    await("Only one prepared query completes") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    click(f.panel, "Step")
    await("Second frame completes") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    click(f.panel, "Step Back")
    assertEquals(2, calls(f).size)
    assertTrue(f.panel.result.get.value(0, 0).toString.endsWith("from a"))
    assertFalse(f.panel.result.get.value(0, 0).toString.contains("b as"))
  }
  def testUnrelatedCommandCompletionsCannotPopulateTheSessionAndEditsCancelItsOwnQuery(): Unit = {
    val f = setup()
    Files.writeString(f.directory.resolve("hold"), "")
    click(f.panel, "Step")
    await("Session query starts") { calls(f).nonEmpty && f.runner.isRunning }
    val command = f.events.flatMap(_.command).last
    val unrelated = DbtQueryResult.parse("{\"show\":[{\"wrong\":999}]}", "dev", 20, 1, "select wrong").toOption.get
    f.panel.onRunnerEvent(DbtRunnerEvent(running = false, "unrelated completion", result = Some(unrelated), command = Some(command.copy())))
    assertEquals(0, rows(f.panel))
    edit(f.file, "with changed as (select 2 as id) select * from changed")
    await("Editing cancels the active session command") { !f.runner.isRunning }
    Files.deleteIfExists(f.directory.resolve("hold"))
    assertEquals(0, rows(f.panel))
    assertTrue(messages(f.panel).contains("changed"))
  }
  def testDisposalDuringPreparationAndActiveQueryPreventsResultsAndFurtherQueries(): Unit = {
    val f = setup()
    WriteAction.run(() => { click(f.panel, "Step"); Disposer.dispose(f.panel) })
    flushReads(); assertEquals(Vector.empty, calls(f))
    val next = controller(f.file.getVirtualFile.getPath, DbtSqlAnalysis.get(DbtWorkspace.get(getProject), f.file).get.ctes)
    Disposer.register(getTestRootDisposable, next)
    Files.writeString(f.directory.resolve("hold"), "")
    click(next, "Step")
    await("Second panel query starts") { f.runner.isRunning && calls(f).nonEmpty }
    Disposer.dispose(next)
    await("Disposal cancels only the panel-owned active command") { !f.runner.isRunning }
    assertEquals(0, rows(next))
  }
  def testGridLimitRerunsTheSelectedFrameOnlyAfterExplicitClick(): Unit = {
    val f = setup()
    click(f.panel, "Step")
    await("First frame preview completes") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertEquals(1, calls(f).size)
    f.panel.rerun(100)
    await("Explicit limit reruns the selected frame") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertEquals("100", calls(f).last(calls(f).last.indexOf("--limit") + 1))
    assertEquals(calls(f).head(calls(f).head.indexOf("--inline") + 1), calls(f).last(calls(f).last.indexOf("--inline") + 1))
  }
  def testContinueStopsAtTheSelectedBreakpoint(): Unit = {
    val f = setup()
    breakpoints = Set("a")
    click(f.panel, "Continue to breakpoint")
    await("Continue stops after the breakpoint frame") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    flushReads()
    assertEquals(1, calls(f).size)
  }

  def testStepIntoRefAtTheEndOfACteOpensTheExactReferencedModelWithoutQuerying(): Unit = {
    val f = setup("with orders as (select * from {{ ref('upstream') }}) select * from orders")
    add("models/upstream.sql", "with base as (select 1 as id) select * from base")
    val next = f.panel.referencedModel().get
    assertTrue(next._1.endsWith("/upstream.sql"))
    assertTrue(calls(f).isEmpty)
    val nested = controller(next._1, next._2)
    Disposer.register(getTestRootDisposable, nested)
    assertEquals(Vector("base"), nested.frames.map(_.name))
    nested.step()
    await("Only explicit nested Step queries") { calls(f).size == 1 && !f.runner.isRunning && rows(nested) == 1 }
    assertEquals("with base as (select 1 as id)\nselect * from base", calls(f).head(calls(f).head.indexOf("--inline") + 1))
  }

  def testQueryFailureStopsContinueAndRestartRetriesTheFirstFrameWithoutOldResults(): Unit = {
    val f = setup()
    Files.writeString(f.directory.resolve("fail"), "")
    click(f.panel, "Continue to breakpoint")
    await("A failed frame finishes") { calls(f).size == 1 && !f.runner.isRunning }
    flushReads()
    assertEquals("Continue must stop after a query error", 1, calls(f).size)
    assertEquals(0, rows(f.panel))
    assertTrue(messages(f.panel), messages(f.panel).contains("exit 1"))
    Files.delete(f.directory.resolve("fail"))
    click(f.panel, "Restart")
    assertEquals(1, calls(f).size)
    click(f.panel, "Step")
    await("Restart explicitly retries the first frame") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertEquals(calls(f).head(calls(f).head.indexOf("--inline") + 1), calls(f).last(calls(f).last.indexOf("--inline") + 1))
  }

  def testCountingAFrameDoesNotReplaceItsCachedPreviewOrAdvanceTheStack(): Unit = {
    val f = setup()
    click(f.panel, "Step")
    await("First CTE preview completes") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    click(f.panel, "Count all rows")
    await("Explicit complete count finishes") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertTrue(calls(f).last(calls(f).last.indexOf("--inline") + 1).endsWith("select count(*) as full_row_count from a"))
    click(f.panel, "Step")
    await("Step still advances to the second CTE") { calls(f).size == 3 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertTrue(calls(f).last(calls(f).last.indexOf("--inline") + 1).endsWith("select * from b"))
    click(f.panel, "Step Back")
    assertEquals(3, calls(f).size)
    assertTrue(f.panel.result.get.value(0, 0).toString.endsWith("select * from a"))
    assertFalse(f.panel.result.get.value(0, 0).toString.contains("count(*)"))
  }

  def testPlanningAtFinalSelectCteReferenceUsesItsExactDefinitionAndDependencies(): Unit = {
    val f = setup()
    val w = DbtWorkspace.get(getProject)
    val analysis = DbtSqlAnalysis.get(w, f.file).get
    val cte = DbtCteInspection.at(w, f.file, analysis, f.file.getText.lastIndexOf("b")).get
    assertEquals("b", cte.name)
    val sql = DbtCteInspection.sql(f.file.getText, analysis, cte).toOption.get
    assertEquals("with a as (select 1 as id),\nb as (select id from a)\nselect * from b", sql)
    val model = w.codeSymbols(f.file).find(_.kind == "model").get
    val command = DbtCteInspection.command(w, model, sql).toOption.get
    assertEquals(sql, command.arguments(command.arguments.indexOf("--inline") + 1))
    assertFalse(command.arguments.contains("--select"))
  }
  def testLeadingSetAndConfigSetupSurviveCteExtractionWithoutOmittingUnusedSql(): Unit = {
    val setupSql = "{% set chosen = 'orders' %}\n{{ config(materialized='view') }}\n-- preserve setup\n"
    val f = setup(setupSql + "with unused as (select 0), chosen_cte as (select * from {{ ref(chosen) }}) select * from chosen_cte")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), f.file).get
    val query = DbtCteInspection.sql(f.file.getText, analysis, analysis.ctes.find(_.name == "chosen_cte").get).toOption.get
    assertTrue(query.startsWith(setupSql + "with chosen_cte"))
    assertTrue(query.contains("{{ ref(chosen) }}")); assertFalse(query.contains("unused as"))
  }
  def testTemplateSetupBetweenCteDeclarationsIsRejectedInsteadOfSilentlyLost(): Unit = {
    val f = setup("with a as (select 1), {% set chosen = 'orders' %} b as (select * from {{ ref(chosen) }}) select * from b")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), f.file).get
    val query = DbtCteInspection.sql(f.file.getText, analysis, analysis.ctes.find(_.name == "b").get)
    assertTrue(query.left.toOption.get.contains("Template setup between CTE"))
    assertTrue(calls(f).isEmpty)
  }

  def testPreviewSelectedSqlReportsABusyRunner(): Unit = {
    val f = setup()
    Files.writeString(f.directory.resolve("hold"), "")
    click(f.panel, "Step")
    await("The held CTE query occupies the runner") { calls(f).size == 1 && f.runner.isRunning }
    val factory = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val config = new DbtRunConfiguration(getProject, factory, "preview")
    val console = new DbtCommandConsole(config)
    Disposer.register(getTestRootDisposable, console)
    val command = DbtCteInspection.command(DbtWorkspace.get(getProject), DbtWorkspace.get(getProject).codeSymbols(f.file).head, f.file.getText).toOption.get
    console.start(command)
    assertTrue(console.process.isProcessTerminated)
    assertEquals(1, console.process.getExitCode.intValue())
    assertTrue("Rejected previews must not stop the running inspector", f.runner.isRunning)
    Files.delete(f.directory.resolve("hold"))
    await("Held query finishes") { !f.runner.isRunning }
    assertEquals(1, calls(f).size)
  }

}
