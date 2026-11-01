package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtArtifactContext
import com.google.gson.JsonParser
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import org.junit.Assert.*

import java.nio.file.Files
import java.nio.file.Path

/**
 * Contract: features/workspace.md and query-preview.md: managed execution, cancellation, captured context,
 * and readable console output of dbt's structured log.
 */
class DbtRunnerProcessTest : DbtTestCase() {
    private fun fake(directory: Path): Path =
        fakeTool(
            directory.resolve("fake dbt"),
            """#!/usr/bin/python3
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
"""
        )

    private data class Setup(
        val runner: DbtRunner,
        val directory: Path,
        val executable: Path,
        val events: MutableList<DbtRunnerEvent>
    )

    private fun setup(): Setup {
        val directory = tempDirectory("dbt runner with spaces ")
        val runner = newRunner()
        val events = mutableListOf<DbtRunnerEvent>()
        runner.listen(testRootDisposable) { e -> events += e }
        return Setup(runner, directory, fake(directory), events)
    }

    private fun command(directory: Path, executable: Path, args: List<String>): DbtCommand {
        val action = when (args.first()) {
            "show" -> DbtCommandAction.Preview
            "compile" -> DbtCommandAction.Compile
            "ls" -> DbtCommandAction.ResolveSelection
            else -> DbtCommandAction.Run
        }

        fun option(name: String): String? = args.windowed(2).find { it.firstOrNull() == name }?.getOrNull(1)

        val options = setOf("--inline", "--target", "--output", "--limit")
        val extra = args.indices.drop(1).filter { i -> args[i] !in options && args[i - 1] !in options }.map { args[it] }
        val scope = option("--inline")?.let { DbtCommandScope.Inline(it) } ?: DbtCommandScope.All
        return DbtCommand(
            listOf(executable.toString()),
            DbtCommandRequest(action, scope, extraArguments = extra),
            directory.toString(),
            mapOf(
                "DBT_TARGET_PATH" to directory.resolve("artifacts with spaces").toString(),
                "DBT_PROFILES_DIR" to directory.resolve("profiles with spaces").toString()
            ),
            option("--target") ?: "dev",
            notifySystem = false,
            artifactDirectory = directory.resolve("target").toString(),
            artifactContext = listOf(directory.toString(), "dev", "configuration")
        )
    }

    fun testProcessStreamsAnsiBeforeExitAndKeepsArgumentVectorCwdAndEnvironment() {
        val (runner, directory, executable, events) = setup()
        val sql = "select 'path with spaces'\nfrom db.schema.orders"
        val console =
            com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance().createBuilder(getProject()).console
        Disposer.register(testRootDisposable, console);
        console.component
        assertTrue(
            runner.start(
                command(directory, executable, listOf("show", "--inline", sql, "--target", "dev", "--hold")),
                { text, kind ->
                    console.print(
                        text,
                        com.intellij.execution.ui.ConsoleViewContentType.getConsoleViewType(kind)
                    )
                })
        )
        assertFalse(
            "A second toolbar/regeneration command must not start",
            runner.start(command(directory, executable, listOf("run")))
        )
        await("Output streams before completion") {
            (console as ConsoleViewImpl).editor!!.document.text.contains("streamed output")
        }
        assertTrue(runner.isRunning)
        val text = (console as ConsoleViewImpl).editor!!.document.text
        assertFalse(text.contains("\u001b[31m"))
        val invocation = JsonParser.parseString(Files.readString(directory.resolve("invocation.json"))).asJsonObject
        assertEquals(
            listOf(
                "show",
                "--inline",
                sql,
                "--limit",
                "10",
                "--output",
                "json",
                "--hold",
                "--target",
                "dev",
                "--use-colors"
            ), invocation.getAsJsonArray("args").map { it.asString }.toList()
        )
        assertEquals(directory.toString(), invocation.get("cwd").asString)
        assertTrue(invocation.get("profiles").asString.endsWith("profiles with spaces"))
        assertTrue(invocation.get("target").asString.endsWith("artifacts with spaces"))
        Files.writeString(directory.resolve("release"), "")
        await("Command completes") {
            !runner.isRunning
        }
        assertTrue(events.last().message.contains("exit 0"));
        assertTrue(events.last().preview?.contains("null") == true)
    }

    fun testFailureAndLaunchErrorReturnToIdleWithUsefulMessages() {
        val (runner, directory, executable, events) = setup()
        assertTrue(runner.start(command(directory, executable, listOf("run", "--fail"))))
        await("Failed command returns idle") {
            !runner.isRunning
        }
        assertTrue(events.last().message.contains("exit 7"));
        assertFalse(events.last().refresh)
        assertTrue(runner.start(command(directory, directory.resolve("missing-dbt"), listOf("run"))))
        await("Launch failure returns idle") {
            !runner.isRunning
        }
        assertTrue(events.last().message.contains("Cannot launch dbt"))
    }

    fun testStopAndDisposalTerminateProcessAndNeverReportSuccess() {
        val (runner, directory, executable, events) = setup()
        assertTrue(runner.start(command(directory, executable, listOf("run", "--hold"))))
        await("Fake dbt started") {
            Files.exists(directory.resolve("invocation.json"))
        }
        runner.stop()
        await("Cancellation restores idle controls") {
            !runner.isRunning
        }
        assertEquals("Run cancelled", events.last().message);
        assertFalse(events.last().refresh)
        Files.delete(directory.resolve("invocation.json"))
        assertTrue(runner.start(command(directory, executable, listOf("run", "--hold"))))
        await("Second fake dbt started") {
            Files.exists(directory.resolve("invocation.json"))
        }
        val pid =
            JsonParser.parseString(Files.readString(directory.resolve("invocation.json"))).asJsonObject.get("pid").asLong
        Disposer.dispose(runner)
        await("Disposal terminates the process") {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false
        }
    }

    fun testNativeNotificationPreferenceAndCancellationUsePlatformApi() {
        val delivered = mutableListOf<String>()
        val system = object : com.intellij.ui.SystemNotifications() {
            override fun notify(name: String, title: String, text: String) {
                delivered += text;
            }
        }
        val (_, directory, executable, _) = setup()
        val enabled = command(directory, executable, listOf("run")).copy(notifySystem = true)
        DbtNotifications.publish(
            getProject(),
            enabled,
            "Run finished (exit 0)",
            success = true,
            wasCancelled = false,
            system
        )
        assertTrue(delivered.last().contains("exit 0"))
        DbtNotifications.publish(
            getProject(),
            enabled,
            "Run finished (exit 7)",
            success = false,
            wasCancelled = false,
            system
        )
        assertTrue(delivered.last().contains("exit 7"))
        DbtNotifications.publish(getProject(), enabled, "Run cancelled", success = false, wasCancelled = true, system)
        assertEquals("Run cancelled", delivered.last())
        val count = delivered.size
        DbtNotifications.publish(
            getProject(),
            enabled.copy(notifySystem = false),
            "Run finished (exit 0)",
            success = true,
            wasCancelled = false,
            system
        )
        assertEquals(count, delivered.size)
    }

    fun testSuccessfulNoWriteCommandNeverCertifiesOldManifestForAnotherTarget() {
        val (runner, directory, executable, _) = setup()
        val artifact = Files.createDirectories(directory.resolve("target"))
        Files.writeString(artifact.resolve("manifest.json"), "{\"target\":\"dev\"}")
        DbtArtifactContext.record(artifact.toString(), command(directory, executable, listOf("run")).artifactContext)
        val marker = artifact.resolve(".dbt-workspace-context")
        val before = Files.readString(marker)
        val run = command(directory, executable, listOf("compile", "--target", "prod")).copy(
            target = "prod",
            artifactContext = listOf(directory.toString(), "prod", "configuration")
        )
        assertTrue(runner.start(run))
        await("No-write command completes") {
            !runner.isRunning
        }
        assertEquals("The old target must retain its provenance", before, Files.readString(marker))
        Files.delete(marker)
        assertTrue(runner.start(run))
        await("Unverified no-write command completes") {
            !runner.isRunning
        }
        assertFalse("Unchanged artifacts must remain unverified", Files.exists(marker))
    }

    fun testOnlySuccessfulManifestWritesUpdateArtifactProvenance() {
        val (runner, directory, executable, _) = setup()
        val artifact = Files.createDirectories(directory.resolve("target"))
        Files.writeString(artifact.resolve("manifest.json"), "{\"target\":\"dev\"}")
        DbtArtifactContext.record(artifact.toString(), command(directory, executable, listOf("run")).artifactContext)
        val marker = artifact.resolve(".dbt-workspace-context")
        val before = Files.readString(marker)
        val run = command(directory, executable, listOf("compile", "--target", "prod", "--write-manifest"))
            .copy(
                target = "prod", environment = mapOf("DBT_TARGET_PATH" to artifact.toString()),
                artifactContext = listOf(directory.toString(), "prod", "configuration")
            )
        assertTrue(runner.start(run.copy(request = run.request.copy(extraArguments = run.request.extraArguments + "--fail"))))
        await("Failed artifact write completes") {
            !runner.isRunning
        }
        assertEquals("A failed command must not certify its manifest", before, Files.readString(marker))
        assertTrue(runner.start(run))
        await("Successful artifact write completes") {
            !runner.isRunning
        }
        val recorded = Files.readAllLines(marker).toList()
        assertEquals(listOf(directory.toString(), "prod"), recorded.take(2))
        assertEquals(DbtArtifactContext.fingerprint(artifact.toString()), recorded.lastOrNull())
        assertEquals(
            "prod",
            JsonParser.parseString(Files.readString(artifact.resolve("manifest.json"))).asJsonObject.get("target").asString
        )
    }

    fun testExplicitSelectorRefreshSharesSingleProcessStopAndCapturedOutput() {
        val (runner, directory, executable, events) = setup()
        val ls = command(directory, executable, listOf("ls", "--output", "json", "--hold"))
        assertTrue(runner.start(ls))
        assertFalse(runner.start(command(directory, executable, listOf("run"))))
        await("Selector process starts") {
            Files.exists(directory.resolve("invocation.json"))
        }
        runner.stop()
        await("Selector cancellation restores idle") {
            !runner.isRunning
        }
        assertTrue(events.last().selectionOutput == null)
        assertTrue(events.last().message.contains("cancelled"))
        assertTrue(runner.start(ls.copy(request = ls.request.copy(extraArguments = ls.request.extraArguments.filterNot { it == "--hold" }))))
        await("Selector refresh finishes") {
            !runner.isRunning
        }
        assertEquals(
            Result.success(listOf("model.analytics.orders")),
            DbtSelectionReview.cliResources(events.last().selectionOutput!!)
        )
        assertFalse(events.last().refresh)
        assertTrue(events.last().result == null)
    }

    private val chunks = mutableListOf<Pair<String, Key<*>>>()

    private fun output(): DbtConsoleOutput = DbtConsoleOutput { text, kind -> chunks += text to kind }

    private val text: String get() = chunks.joinToString("") { it.first }

    fun testDbtMessagesShowTimeLevelMultilineDetailsAndBlankSeparators() {
        val display = output()
        display.textAvailable(
            """{"data":{"unused":"metadata"},"info":{"msg":"Running with dbt=1.12.5","level":"info","ts":"2026-10-20T12:34:56.123Z"}}
{"data":{},"info":{"msg":"Unexpected rows\nexpected: 0\nactual: 3","level":"warn","ts":"2026-10-20T12:34:57Z"}}
{"data":{"msg":""},"info":{"msg":"","level":"info"}}
{"data":{},"info":{"msg":"Database error","level":"error"}}
""", ProcessOutputTypes.STDOUT
        )
        assertEquals(
            "12:34:56  Running with dbt=1.12.5\n12:34:57  [WARN] Unexpected rows\nexpected: 0\nactual: 3\n\n[ERROR] Database error\n",
            text
        )
    }

    fun testSplitJsonAndIndependentStreamsFlushFinalRecordWithoutNewline() {
        val display = output()
        val record = """{"data":{},"info":{"msg":"PASS orders","level":"info"}}"""
        record.dropLast(1).forEach { c -> display.textAvailable(c.toString(), ProcessOutputTypes.STDOUT) }
        assertEquals("", text)
        display.textAvailable("stderr diagnostic\n", ProcessOutputTypes.STDERR)
        display.textAvailable(record.takeLast(1), ProcessOutputTypes.STDOUT)
        display.finish()
        assertEquals("stderr diagnostic\nPASS orders\n", text)
        assertTrue(ProcessOutputType.isStderr(chunks.first().second))
        assertTrue(ProcessOutputType.isStdout(chunks.last().second))
        display.finish()
        assertEquals(2, chunks.size)
    }

    fun testPlainTextStreamsImmediatelyAndOtherJsonAndMalformedRecordsRemainVisible() {
        val display = output()
        display.textAvailable("Connecting...", ProcessOutputTypes.STDOUT)
        assertEquals("Connecting...", text)
        val raw =
            """
{"preview":"[{\"id\":1}]"}
{"unique_id":"model.analytics.orders"}
{"data":{},"info":{"msg":null}}
{"data":{},"info":{"msg":42}}
{"data":{},"info":{"msg":{"unexpected":true}}}
{"data":{},"info":"unexpected"}
{"data":{},"info":{"msg":"truncated
"""
        display.textAvailable(raw, ProcessOutputTypes.STDOUT)
        display.textAvailable("{unfinished", ProcessOutputTypes.STDERR)
        display.finish()
        assertEquals("Connecting..." + raw + "{unfinished", text)
    }

    fun testAnsiInJsonMessagesAndPlainOutputProducesColoredTextWithoutEscapeCodes() {
        val display = output()
        display.textAvailable(
            "{\"data\":{},\"info\":{\"msg\":\"\\u001b[32mPASS\\u001b[0m orders\"}}\n",
            ProcessOutputTypes.STDOUT
        )
        display.textAvailable("\u001b[31", ProcessOutputTypes.STDERR)
        display.textAvailable("mFailure\u001b[0m\n", ProcessOutputTypes.STDERR)
        assertEquals("PASS orders\nFailure\n", text)
        val pass = chunks.first { it.first == "PASS" }
        val failure = chunks.first { it.first == "Failure" }
        assertTrue(ProcessOutputType.isStdout(pass.second))
        assertTrue(ProcessOutputType.isStderr(failure.second))
        assertNotEquals(
            ConsoleViewContentType.NORMAL_OUTPUT,
            ConsoleViewContentType.getConsoleViewType(pass.second)
        )
        assertNotEquals(
            ConsoleViewContentType.ERROR_OUTPUT,
            ConsoleViewContentType.getConsoleViewType(failure.second)
        )
    }

    fun testOversizedUnterminatedRecordDoesNotHideOutput() {
        val display = output()
        val raw = "{" + "x".repeat(1000001)
        display.textAvailable(raw, ProcessOutputTypes.STDOUT)
        assertEquals(raw, text)
        display.finish()
        assertEquals(raw, text)
    }

    fun testJsonEmbeddedInPlainScriptOutputIsNotReformattedAcrossChunks() {
        val display = output()
        val record = """{"data":{},"info":{"msg":"diagnostic payload"}}"""
        display.textAvailable("Script payload: ", ProcessOutputTypes.STDOUT)
        display.textAvailable(record + "\n", ProcessOutputTypes.STDOUT)
        assertEquals("Script payload: " + record + "\n", text)
        display.textAvailable(record + "\n", ProcessOutputTypes.STDOUT)
        assertEquals("Script payload: " + record + "\ndiagnostic payload\n", text)
    }
}
