package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.google.gson.JsonParser
import com.github.vbmacher.intellij.dbt.project.DbtArtifactContext
import org.junit.Assert._
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Contract: features/workspace.md and query-preview.md: managed execution, cancellation and captured context. */
final class DbtRunnerProcessTest extends BasePlatformTestCase {
  private def fake(directory: Path): Path = {
    val executable = directory.resolve("fake dbt")
    Files.writeString(executable, """#!/usr/bin/python3
import json,os,sys,time
with open('invocation.json','w') as f: json.dump({'args':sys.argv[1:],'cwd':os.getcwd(),'target':os.environ.get('DBT_TARGET_PATH'),'profiles':os.environ.get('DBT_PROFILES_DIR'),'pid':os.getpid()},f)
print('\x1b[31mstreamed output\x1b[0m',flush=True)
if '--hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.02)
if '--write-manifest' in sys.argv:
 os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
 with open(os.path.join(os.environ['DBT_TARGET_PATH'],'manifest.json'),'w') as f:
  json.dump({'target':sys.argv[sys.argv.index('--target')+1],'generated_at':time.time_ns()},f)
if 'ls' in sys.argv: print(json.dumps({'unique_id':'model.analytics.orders'}),flush=True)
if 'show' in sys.argv: print(json.dumps({'preview':json.dumps([{'id':1,'name':None}])}),flush=True)
sys.exit(7 if '--fail' in sys.argv else 0)
""")
    executable.toFile.setExecutable(true); executable
  }
  private def await(message: String)(condition: => Boolean): Unit = PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 15)
  private def setup(): (DbtRunner, Path, Path, ArrayBuffer[DbtRunnerEvent]) = {
    val directory = Files.createTempDirectory("dbt runner with spaces ")
    val runner = new DbtRunner(getProject)
    Disposer.register(getTestRootDisposable, runner)
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(directory.toFile))
    runner.console.getComponent
    val events = ArrayBuffer.empty[DbtRunnerEvent]
    runner.listen(getTestRootDisposable, e => events += e)
    (runner, directory, fake(directory), events)
  }
  private def command(directory: Path, executable: Path, args: Vector[String]): DbtCommand = {
    val action = args.head match {
      case "show" => DbtCommandAction.Preview
      case "compile" => DbtCommandAction.Compile
      case "ls" => DbtCommandAction.ResolveSelection
      case _ => DbtCommandAction.Run
    }
    def option(name: String): Option[String] = args.sliding(2).find(_.headOption.contains(name)).flatMap(_.lift(1))
    val options = Set("--inline", "--target", "--output", "--limit")
    val extra = args.indices.drop(1).filter(i => !options(args(i)) && !options(args(i - 1))).map(args).toVector
    val scope = option("--inline").map(DbtCommandScope.Inline).getOrElse(DbtCommandScope.All)
    DbtCommand(Vector(executable.toString), DbtCommandRequest(action, scope, extraArguments = extra), directory.toString,
      Map("DBT_TARGET_PATH" -> directory.resolve("artifacts with spaces").toString,
        "DBT_PROFILES_DIR" -> directory.resolve("profiles with spaces").toString),
      option("--target").getOrElse("dev"), notifySystem = false, directory.resolve("target").toString, Vector(directory.toString, "dev", "configuration"))
  }

  def testProcessStreamsAnsiBeforeExitAndKeepsArgumentVectorCwdAndEnvironment(): Unit = {
    val (runner, directory, executable, events) = setup()
    val sql = "select 'path with spaces'\nfrom db.schema.orders"
    assertTrue(runner.start(command(directory, executable, Vector("show", "--inline", sql, "--target", "dev", "--hold"))))
    assertFalse("A second toolbar/regeneration command must not start", runner.start(command(directory, executable, Vector("run"))))
    await("Output streams before completion") { runner.console.asInstanceOf[ConsoleViewImpl].getEditor.getDocument.getText.contains("streamed output") }
    assertTrue(runner.isRunning)
    val text = runner.console.asInstanceOf[ConsoleViewImpl].getEditor.getDocument.getText
    assertFalse(text.contains("\u001b[31m"))
    val invocation = JsonParser.parseString(Files.readString(directory.resolve("invocation.json"))).getAsJsonObject
    assertEquals(Vector("show", "--inline", sql, "--limit", "10", "--output", "json", "--hold", "--target", "dev", "--use-colors"), invocation.getAsJsonArray("args").asScala.map(_.getAsString).toVector)
    assertEquals(directory.toString, invocation.get("cwd").getAsString)
    assertTrue(invocation.get("profiles").getAsString.endsWith("profiles with spaces"))
    assertTrue(invocation.get("target").getAsString.endsWith("artifacts with spaces"))
    Files.writeString(directory.resolve("release"), "")
    await("Command completes") { !runner.isRunning }
    assertTrue(events.last.message.contains("exit 0")); assertTrue(events.last.preview.exists(_.contains("null")))
  }

  def testFailureAndLaunchErrorReturnToIdleWithUsefulMessages(): Unit = {
    val (runner, directory, executable, events) = setup()
    assertTrue(runner.start(command(directory, executable, Vector("run", "--fail"))))
    await("Failed command returns idle") { !runner.isRunning }
    assertTrue(events.last.message.contains("exit 7")); assertFalse(events.last.refresh)
    assertTrue(runner.start(command(directory, directory.resolve("missing-dbt"), Vector("run"))))
    await("Launch failure returns idle") { !runner.isRunning }
    assertTrue(events.last.message.contains("Cannot launch dbt"))
  }

  def testStopAndDisposalTerminateProcessAndNeverReportSuccess(): Unit = {
    val (runner, directory, executable, events) = setup()
    assertTrue(runner.start(command(directory, executable, Vector("run", "--hold"))))
    await("Fake dbt started") { Files.exists(directory.resolve("invocation.json")) }
    runner.stop()
    await("Cancellation restores idle controls") { !runner.isRunning }
    assertEquals("Run cancelled", events.last.message); assertFalse(events.last.refresh)
    Files.delete(directory.resolve("invocation.json"))
    assertTrue(runner.start(command(directory, executable, Vector("run", "--hold"))))
    await("Second fake dbt started") { Files.exists(directory.resolve("invocation.json")) }
    val pid = JsonParser.parseString(Files.readString(directory.resolve("invocation.json"))).getAsJsonObject.get("pid").getAsLong
    Disposer.dispose(runner)
    await("Disposal terminates the process") { !ProcessHandle.of(pid).map[java.lang.Boolean](p => Boolean.box(p.isAlive)).orElse(false).booleanValue() }
  }
  def testNativeNotificationPreferenceAndCancellationUsePlatformApi(): Unit = {
    val delivered = ArrayBuffer.empty[String]
    val system = new com.intellij.ui.SystemNotifications {
      override def notify(name: String, title: String, text: String): Unit = { delivered += text; () }
    }
    val (_, directory, executable, _) = setup()
    val enabled = command(directory, executable, Vector("run")).copy(notifySystem = true)
    DbtNotifications.publish(getProject, enabled, "Run finished (exit 0)", success = true, wasCancelled = false, system)
    assertTrue(delivered.last.contains("exit 0"))
    DbtNotifications.publish(getProject, enabled, "Run finished (exit 7)", success = false, wasCancelled = false, system)
    assertTrue(delivered.last.contains("exit 7"))
    DbtNotifications.publish(getProject, enabled, "Run cancelled", success = false, wasCancelled = true, system)
    assertEquals("Run cancelled", delivered.last)
    val count = delivered.size
    DbtNotifications.publish(getProject, enabled.copy(notifySystem = false), "Run finished (exit 0)", success = true, wasCancelled = false, system)
    assertEquals(count, delivered.size)
  }

  def testSuccessfulNoWriteCommandNeverCertifiesOldManifestForAnotherTarget(): Unit = {
    val (runner, directory, executable, _) = setup()
    val artifact = Files.createDirectories(directory.resolve("target"))
    Files.writeString(artifact.resolve("manifest.json"), "{\"target\":\"dev\"}")
    DbtArtifactContext.record(artifact.toString, command(directory, executable, Vector("run")).artifactContext)
    val marker = artifact.resolve(".dbt-workspace-context")
    val before = Files.readString(marker)
    val run = command(directory, executable, Vector("compile", "--target", "prod")).copy(target = "prod", artifactContext = Vector(directory.toString, "prod", "configuration"))
    assertTrue(runner.start(run))
    await("No-write command completes") { !runner.isRunning }
    assertEquals("The old target must retain its provenance", before, Files.readString(marker))
    Files.delete(marker)
    assertTrue(runner.start(run))
    await("Unverified no-write command completes") { !runner.isRunning }
    assertFalse("Unchanged artifacts must remain unverified", Files.exists(marker))
  }

  def testOnlySuccessfulManifestWritesUpdateArtifactProvenance(): Unit = {
    val (runner, directory, executable, _) = setup()
    val artifact = Files.createDirectories(directory.resolve("target"))
    Files.writeString(artifact.resolve("manifest.json"), "{\"target\":\"dev\"}")
    DbtArtifactContext.record(artifact.toString, command(directory, executable, Vector("run")).artifactContext)
    val marker = artifact.resolve(".dbt-workspace-context")
    val before = Files.readString(marker)
    val run = command(directory, executable, Vector("compile", "--target", "prod", "--write-manifest"))
      .copy(target = "prod", environment = Map("DBT_TARGET_PATH" -> artifact.toString),
        artifactContext = Vector(directory.toString, "prod", "configuration"))
    assertTrue(runner.start(run.copy(request = run.request.copy(extraArguments = run.request.extraArguments :+ "--fail"))))
    await("Failed artifact write completes") { !runner.isRunning }
    assertEquals("A failed command must not certify its manifest", before, Files.readString(marker))
    assertTrue(runner.start(run))
    await("Successful artifact write completes") { !runner.isRunning }
    val recorded = Files.readAllLines(marker).asScala.toVector
    assertEquals(Vector(directory.toString, "prod"), recorded.take(2))
    assertEquals(DbtArtifactContext.fingerprint(artifact.toString), recorded.lastOption)
    assertEquals("prod", JsonParser.parseString(Files.readString(artifact.resolve("manifest.json"))).getAsJsonObject.get("target").getAsString)
  }

  def testExplicitSelectorRefreshSharesSingleProcessStopAndCapturedOutput(): Unit = {
    val (runner, directory, executable, events) = setup()
    val ls = command(directory, executable, Vector("ls", "--output", "json", "--hold"))
    assertTrue(runner.start(ls))
    assertFalse(runner.start(command(directory, executable, Vector("run"))))
    await("Selector process starts") { Files.exists(directory.resolve("invocation.json")) }
    runner.stop()
    await("Selector cancellation restores idle") { !runner.isRunning }
    assertTrue(events.last.selectionOutput.isEmpty)
    assertTrue(events.last.message.contains("cancelled"))
    assertTrue(runner.start(ls.copy(request = ls.request.copy(extraArguments = ls.request.extraArguments.filterNot(_ == "--hold")))))
    await("Selector refresh finishes") { !runner.isRunning }
    assertEquals(Right(Vector("model.analytics.orders")), DbtSelectionReview.cliResources(events.last.selectionOutput.get))
    assertFalse(events.last.refresh)
    assertTrue(events.last.result.isEmpty)
  }

}
