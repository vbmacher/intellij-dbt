package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import org.jdom.Element
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DbtNativeTestRunnerTest : DbtTestCase() {
    private fun configuration(): DbtRunConfiguration {
        val factory =
            ConfigurationTypeUtil.findConfigurationType(DbtConfigurationType::class.java).configurationFactories.first()
        val config = factory.createTemplateConfiguration(getProject()).let { it as DbtRunConfiguration }
        config.action = "Test"
        config.root = workspace.roots.first().directory.path
        return config
    }

    private fun setup(mode: String = ""): Triple<DbtRunConfiguration, DbtCommand, Path> {
        project();
        add("tests/check_orders.sql", "select 1")
        val directory = tempDirectory("dbt native tests ")
        Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
        val script = directory.resolve("external dbt.py")
        Files.writeString(
            script,
            """import json,os,sys,time,datetime
with open('invocation.json','w') as f: json.dump(sys.argv[1:],f)
print('native raw output',flush=True)
print(json.dumps({'info':{'invocation_id':'native-run','msg':'\x1b[32mSTART check_orders\x1b[0m','level':'info','ts':'2026-10-20T12:34:56Z'},'data':{'node_info':{'unique_id':'test.analytics.check_orders','node_status':'started'}}}),flush=True)
if '--hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.01)
os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
with open(os.path.join(os.environ['DBT_TARGET_PATH'],'run_results.json'),'w') as f:
 json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/run-results/v6.json','invocation_id':'native-run','generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()},'args':{'target':'dev'},'results':[{'unique_id':'test.analytics.check_orders','status':'fail' if '--fail' in sys.argv else 'pass','message':'3 unexpected rows','failures':3}]},f)
sys.exit(1 if '--fail' in sys.argv else 0)
"""
        )
        val w = workspace;
        val root = w.roots.first()
        root.runOptions.executable = "/usr/bin/python3"
        root.runOptions.executableArguments = "\"" + script.toString() + "\""
        root.runOptions.projectRoot = directory.toString()
        root.runOptions.target = "dev";
        root.runOptions.notifications = false
        DbtProjectSettings.get(getProject()).changed()
        val updated = workspace
        val command =
            DbtCommand.create(updated, DbtRunSelection.all(updated, root.directory.path)!!, "Test", fullRefresh = false)
                .getOrThrow()
        val config = configuration()
        val captured =
            command.copy(request = command.request.copy(extraArguments = mode.split(" ").filter { it.isNotEmpty() }
                .toList()))
        config.captured = (captured)
        return Triple(config, captured, directory)
    }

    private fun tests(view: SMTRunnerConsoleView): List<SMTestProxy> =
        view.resultsViewer.testsRootNode.allTests.filterNot { it.isSuite }.toList()

    private fun session(config: DbtRunConfiguration, command: DbtCommand): DbtTestConsole {
        val session = DbtTestConsole(config, DefaultRunExecutor.getRunExecutorInstance())
        Disposer.register(getTestRootDisposable(), session.view)
        session.start(command, DbtTestDiscovery.discover(workspace))
        return session
    }

    fun testStandardRunProfileProvidesNativeConsoleAndManagedProcessWithExternalTool() {
        val (config, command, directory) = setup()
        val nativeEvents = mutableListOf<String>()
        getProject().messageBus.connect(getTestRootDisposable()).subscribe(
            com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener.TEST_STATUS,
            object : com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter() {
                override fun onTestFinished(test: SMTestProxy) {
                    nativeEvents += test.metainfo.orEmpty();
                }
            })
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val programRunner = ProgramRunner.getRunner(executor.id, config)
        assertNotNull("The IDE's standard Run action must accept dbt tests", programRunner)
        val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
        val result = config.getState(executor, environment).execute(executor, programRunner!!)!!
        assertTrue(
            "The native Run toolbar exposes failed-test reruns",
            result.let { it as com.intellij.execution.DefaultExecutionResult }
                .restartActions.any { it.let { it is com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction } })
        val view = result.executionConsole.let { it as SMTRunnerConsoleView }
        Disposer.register(getTestRootDisposable(), view)
        await("Standard Run profile completes") {
            result.processHandler!!.isProcessTerminated && tests(view).any { it.isPassed }
        }
        assertEquals(0, result.processHandler!!.exitCode!!.toInt())
        assertTrue(tests(view).first().isPassed)
        assertTrue(
            "Native framework listeners receive dbt results",
            nativeEvents.contains("test.analytics.check_orders")
        )
        assertEquals(
            command.arguments.drop(2),
            com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("invocation.json"))).asJsonArray.map { it.asString }
                .toList()
        )
        val location = tests(view).first().getLocation(getProject(), GlobalSearchScope.projectScope(getProject()))
        assertEquals("check_orders.sql", location!!.psiElement.containingFile.name)
    }

    fun testNativeStopCancelsAndFailureRerunRetainsCapturedContextAndExactIdentity() {
        val (config, command, directory) = setup("--hold --fail")
        val first = session(config, command)
        await("Test streams before process exit") {
            tests(first.view).any { it.isInProgress }
        }
        first.process.destroyProcess()
        await("Native Stop terminates the managed process") {
            first.process.isProcessTerminated && tests(first.view).any { it.wasTerminated() }
        }
        assertTrue(tests(first.view).first().wasTerminated())
        assertFalse(getProject().getService(DbtRunner::class.java).isRunning)
        val failedCommand = command.copy(request = command.request.copy(extraArguments = listOf("--fail")))
        val second = session(config, failedCommand)
        await("Failure appears in native results") {
            second.process.isProcessTerminated && tests(second.view).any { it.isDefect }
        }
        val proxy = tests(second.view).first()
        assertTrue(proxy.isDefect);
        assertTrue(proxy.errorMessage!!.contains("unexpected rows"))
        val action = second.properties.createRerunFailedTestsAction(second.view)!!
        assertNotNull("Use IntelliJ's standard failed-test action", action)
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
        val method = action.javaClass.getDeclaredMethod(
            "getRunProfile",
            com.intellij.execution.runners.ExecutionEnvironment::class.java
        )
        method.setAccessible(true)
        val profile = method.invoke(action, environment)
        val rerunConfig =
            profile.javaClass.getMethod("getPeer").apply { isAccessible = true }.invoke(profile) as DbtRunConfiguration
        val rerun = rerunConfig.prepare().getOrThrow().first
        assertEquals(failedCommand.prefix, rerun.prefix)
        assertEquals(failedCommand.target, rerun.target)
        assertEquals(failedCommand.environment, rerun.environment)
        assertEquals(("empty"), rerun.request.indirectSelection)
        assertFalse(rerun.request.scope == DbtCommandScope.All)
    }

    fun testBuildConsoleDisplaysReadableColoredMessagesAndRetainsNativeTestResults() {
        val (config, command, _) = setup()
        config.action = "Build"
        val console = session(config, command.copy(request = command.request.copy(action = DbtCommandAction.Build)))
        await("Build test result completes") {
            console.process.isProcessTerminated && tests(console.view).any { it.isPassed }
        }
        console.view.printer.updateOnTestSelected(console.view.resultsViewer.testsRootNode)
        val view = console.view.console.let { it as com.intellij.execution.impl.ConsoleViewImpl }
        await("Readable dbt output reaches the native console") {
            view.editor!!.document.text.contains("12:34:56  START check_orders")
        }
        val text = view.editor!!.document.text
        assertTrue(text.contains("native raw output"))
        assertFalse(text.contains("invocation_id"))
        assertFalse(text.contains("\u001b"))
        assertFalse(text.contains("\\u001b"))
        assertEquals("test.analytics.check_orders", tests(console.view).first().metainfo)
    }

    fun testNativeResultStatesAndMissingEvidenceNeverBecomeSuccess() {
        val (config, command, _) = setup("--hold")
        val console = session(config, command)
        await("Test starts") {
            tests(console.view).any { it.isInProgress }
        }
        val results = listOf(
            "pass" to "passed",
            "fail" to "failed",
            "warn" to "warning",
            "error" to "error",
            "skip" to "skipped",
            "cancel" to "canceled"
        )
            .map { (name, state) ->
                DbtExecutionResult(
                    "test.analytics." + name,
                    state,
                    "detail: " + name + " 'quoted' [row] | value\nsecond line"
                )
            }
        console.update(
            DbtExecutionRun(
                command.artifactContext,
                "dev",
                "native-run",
                Instant.now().toString(),
                "",
                results,
                active = false
            )
        )
        await("Native events reach the tree") {
            tests(console.view).any { p -> p.name == "test.analytics.pass" && p.isPassed }
        }
        val nodes = tests(console.view).map { p -> p.name to p }.toMap()
        assertTrue(nodes.getValue("test.analytics.pass").isPassed)
        assertTrue(nodes.getValue("test.analytics.fail").isDefect)
        assertEquals(
            "detail: fail 'quoted' [row] | value\nsecond line",
            nodes.getValue("test.analytics.fail").errorMessage
        )
        assertNotEquals(
            nodes.getValue("test.analytics.fail").magnitudeInfo,
            nodes.getValue("test.analytics.error").magnitudeInfo
        )
        assertTrue(nodes.getValue("test.analytics.warn").isIgnored)
        assertTrue(nodes.getValue("test.analytics.skip").isIgnored)
        assertTrue(
            "Unreported tests must not appear as passed",
            tests(console.view).find { it.name == "analytics.check_orders" }!!.isInProgress
        )
        console.update(
            DbtExecutionRun(
                command.artifactContext,
                "dev",
                "wrong-invocation",
                Instant.now().toString(),
                "",
                listOf(DbtExecutionResult("test.analytics.unverified", "passed", "streamed success")),
                problem = "Artifact invocation does not match the active run"
            )
        )
        await("Unverified streamed success is visible without passing") {
            tests(console.view).any { p -> p.name == "test.analytics.unverified" && p.isInProgress }
        }
        assertFalse(tests(console.view).find { it.name == "test.analytics.unverified" }!!.isPassed)
        console.process.destroyProcess()
        await("Held process stopped") {
            console.process.isProcessTerminated && tests(console.view).any { p -> p.name == "test.analytics.cancel" && p.wasTerminated() }
        }
    }

    fun testRunConfigurationRoundTripKeepsScopeAndBuildIntent() {
        val (config, _, _) = setup()
        config.captured = null
        config.action = "Build";
        config.selectors = listOf("fqn:analytics.check_orders")
        config.exclude = listOf("tag:slow");
        config.fullRefresh = true
        val xml = Element("configuration");
        config.writeExternal(xml)
        val restored = configuration();
        restored.readExternal(xml)
        assertEquals(config.root, restored.root)
        assertEquals(config.action, restored.action)
        assertEquals(config.selectors, restored.selectors)
        assertEquals(config.exclude, restored.exclude)
        assertTrue(restored.fullRefresh)
        val prepared = restored.prepare().getOrThrow().first
        assertEquals("Build", prepared.action)
        assertEquals(DbtCommandScope.Selected(config.selectors), prepared.request.scope)
        restored.action = "Test";
        restored.exactTests = true;
        restored.selectors = listOf("missing")
        assertTrue("An exact rerun must never widen to the project", restored.prepare().isFailure)
    }

    fun testGutterHasNativeRunActionsAndShowsPassFailAndSourceInvalidation() {
        val (config, command, _) = setup()
        val file = workspace.symbols.find { it.kind == "test" }!!.file
        open(file, 0)
        val definition = DbtTestRunLineMarker.testsAt(file.findElementAt(0))!!
        assertEquals("not run", DbtTestRunLineMarker.status(file, definition))
        val contributor = DbtTestRunLineMarker()
        assertNotNull(contributor.getSlowInfo(file.findElementAt(0)!!))
        assertTrue(myFixture.findAllGutters().any { g -> g.tooltipText?.contains("dbt tests:") == true })
        val passed = session(config, command)
        await("Passing test completes") {
            passed.process.isProcessTerminated && tests(passed.view).any { it.isPassed }
        }
        assertEquals("passed", DbtTestRunLineMarker.status(file, definition))
        val failed = session(config, command.copy(request = command.request.copy(extraArguments = listOf("--fail"))))
        await("Failing test completes") {
            failed.process.isProcessTerminated && tests(failed.view).any { it.isDefect }
        }
        assertEquals("failed", DbtTestRunLineMarker.status(file, definition))
        edit(file, "select 2")
        assertEquals("not run", DbtTestRunLineMarker.status(file, definition))
    }

    fun testYamlUnitAndGenericTestGuttersUseExactDeclarationLines() {
        project();
        add("models/orders.sql", "select 1 as id")
        val file = configure(
            "models/schema.yml",
            "models:\n- name: orders\n  columns:\n  - name: id\n    data_tests: [unique, not_null]\nunit_tests:\n- name: <caret>example\n  model: orders\n  given: []\n  expect: {rows: []}\n"
        )
        val unit = DbtTestRunLineMarker.testsAt(file.findElementAt(myFixture.caretOffset))!!
        assertEquals(1, unit.size);
        assertEquals("Unit tests", unit.first().kind)
        val generic = DbtTestRunLineMarker.testsAt(file.findElementAt(file.text.indexOf("unique")))!!
        assertEquals(2, generic.size)
        assertTrue("Generic hashes require manifest identities", generic.first().selector.isEmpty())
        assertEquals(
            "Tests sharing a declaration line use one Run gutter",
            2,
            myFixture.findAllGutters().count { g -> g.tooltipText?.contains("dbt tests:") == true })
    }
}
