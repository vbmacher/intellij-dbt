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

final class DbtCtePanelTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private final case class Fixture(panel: DbtCtePanel, file: com.intellij.psi.PsiFile, root: DbtRoot, directory: Path,
    runner: DbtRunner, events: ArrayBuffer[DbtRunnerEvent])
  private def components(component: Component): Vector[Component] = Vector(component) ++ (component match {
    case c: Container => c.getComponents.toVector.flatMap(components)
    case _ => Vector.empty
  })
  private def click(panel: DbtCtePanel, name: String): Unit = components(panel).collectFirst {
    case b: JButton if b.getText == name => b
  }.get.doClick()
  private def messages(panel: DbtCtePanel): String = components(panel).collect { case l: JLabel => l.getText }.mkString(" ")
  private def rows(panel: DbtCtePanel): Int = components(panel).collectFirst { case t: JTable => t.getRowCount }.getOrElse(0)
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
print(json.dumps({'show':[{'value':args[args.index('--inline')+1]}]}),flush=True)
""")
    assertTrue(executable.toFile.setExecutable(true))
    root.runOptions.projectRoot = dir.toString
    root.runOptions.executable = executable.toString
    root.runOptions.target = "dev"
    val runner = new DbtRunner(getProject)
    Disposer.register(getTestRootDisposable, runner)
    com.intellij.testFramework.ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, getTestRootDisposable)
    runner.console.getComponent
    val events = ArrayBuffer.empty[DbtRunnerEvent]
    runner.listen(getTestRootDisposable, event => events += event)
    val panel = new DbtCtePanel(getProject, file.getVirtualFile.getPath, DbtSqlAnalysis.get(w, file).get.ctes)
    Disposer.register(getTestRootDisposable, panel)
    Fixture(panel, file, root, dir, runner, events)
  }
  private def calls(f: Fixture): Vector[Vector[String]] =
    if (!Files.exists(f.directory.resolve("calls.jsonl"))) Vector.empty
    else Files.readAllLines(f.directory.resolve("calls.jsonl")).asScala.toVector
      .map(line => JsonParser.parseString(line).getAsJsonArray.asScala.map(_.getAsString).toVector)

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
    val list = components(f.panel).collectFirst { case list: JList[_] => list }.get
    assertEquals("changed", list.getModel.getElementAt(0))
  }
  def testRapidStepRequestsLaunchOnlyOneQueryAndCachedBackDoesNotExecute(): Unit = {
    val f = setup()
    WriteAction.run(() => { click(f.panel, "Step"); click(f.panel, "Step"); click(f.panel, "Step") })
    await("Only one prepared query completes") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    click(f.panel, "Step")
    await("Second frame completes") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    click(f.panel, "Step Back")
    assertEquals(2, calls(f).size)
    val table = components(f.panel).collectFirst { case t: JTable => t }.get
    assertTrue(String.valueOf(table.getValueAt(0, 0)).endsWith("from a"))
    assertFalse(String.valueOf(table.getValueAt(0, 0)).contains("b as"))
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
    val next = new DbtCtePanel(getProject, f.file.getVirtualFile.getPath, DbtSqlAnalysis.get(DbtWorkspace.get(getProject), f.file).get.ctes)
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
    val spinner = components(f.panel).collectFirst { case s: JSpinner => s }.get
    spinner.setValue(Int.box(100))
    assertEquals(1, calls(f).size)
    click(f.panel, "Run with limit")
    await("Explicit limit reruns the selected frame") { calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1 }
    assertEquals("100", calls(f).last(calls(f).last.indexOf("--limit") + 1))
    assertEquals(calls(f).head(calls(f).head.indexOf("--inline") + 1), calls(f).last(calls(f).last.indexOf("--inline") + 1))
  }
  def testContinueStopsAtTheSelectedBreakpoint(): Unit = {
    val f = setup()
    val list = components(f.panel).collectFirst { case l: JList[_] => l }.get
    list.setSelectedIndex(0)
    components(f.panel).collectFirst { case c: JCheckBox if c.getText == "Break at selected CTE" => c }.get.doClick()
    click(f.panel, "Continue to breakpoint")
    await("Continue stops after the breakpoint frame") { calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1 }
    flushReads()
    assertEquals(1, calls(f).size)
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

}
