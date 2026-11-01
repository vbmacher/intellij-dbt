package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtDeclarationSource
import com.github.vbmacher.intellij.dbt.project.DbtResourceData
import com.github.vbmacher.intellij.dbt.project.DbtSymbol
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Ref
import com.intellij.testFramework.PlatformTestUtil
import org.jdom.Element
import org.junit.Assert.*

import java.nio.file.Files
import java.nio.file.Path

/** Native dbt Run configurations: command preparation, persisted overrides and the standard Run/Debug sessions. */
class DbtRunConfigurationTest : DbtTestCase() {
    private fun setup(): Pair<DbtRunConfiguration, Path> {
        project();
        add("models/orders.sql", "select 1 as id")
        val dir = tempDirectory("dbt native commands ")
        Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\n")
        val script = dir.resolve("dbt.py")
        Files.writeString(
            script,
            """import json,os,time,sys
print('native command output',flush=True)
if 'hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.01)
if 'show' in sys.argv: print(json.dumps({'show':[{'id':7}]}),flush=True)
"""
        )
        val config = runConfiguration("native command");
        config.action = "Run"
        val options = workspace.roots.first().runOptions
        options.projectRoot = dir.toString();
        options.notifications = false
        config.executable = "/usr/bin/python3";
        config.executableArguments = "\"" + script.toString() + "\" hold"
        return config to dir
    }

    private fun start(config: DbtRunConfiguration): DbtCommandConsole {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
        val runner = ProgramRunner.getRunner(executor.id, config)
        assertNotNull("The standard Run executor accepts ordinary dbt commands", runner)
        val result = config.getState(executor, environment).execute(executor, runner!!)!!
        val console = result.executionConsole.let { it as DbtCommandConsole }
        Disposer.register(getTestRootDisposable(), console)
        return console
    }

    fun testNativeCommandSessionStreamsBeforeExitAndStopDoesNotCancelLaterInvocations() {
        val (config, dir) = setup()
        val first = start(config)

        fun output(view: DbtCommandConsole): String = view.console.let { it as ConsoleViewImpl }.editor!!.document.text

        await("Ordinary command streams into its own native Run console") {
            output(first).contains("native command output")
        }
        assertFalse(first.process.isProcessTerminated)
        first.process.destroyProcess()
        await("Run Stop cancels the managed command") {
            first.process.isProcessTerminated && !getProject().getService(DbtRunner::class.java).isRunning
        }
        val second = start(config)
        await("Second invocation starts") {
            output(second).contains("native command output")
        }
        Disposer.dispose(first)
        assertTrue(
            "Disposing a completed session cannot stop the next command",
            getProject().getService(DbtRunner::class.java).isRunning
        )
        Files.writeString(dir.resolve("release"), "")
        await("Next invocation completes independently") {
            second.process.isProcessTerminated
        }
        assertEquals(0, second.process.exitCode!!.toInt())
    }

    fun testSavedInlinePreviewKeepsItsSourceScopeAndRunsThroughTheStandardProfile() {
        val (config, dir) = setup()
        config.action = "Preview";
        config.scope = workspace.symbols.find { it.isCode }!!.file.virtualFile.path
        config.inlineSql = "select 7 as id"
        val xml = Element("configuration");
        config.writeExternal(xml)
        val saved = runConfiguration("native command");
        saved.readExternal(xml)
        Files.writeString(dir.resolve("release"), "")
        assertEquals(DbtCommandScope.Inline("select 7 as id"), saved.prepare().getOrThrow().first.request.scope)
        val console = start(saved)
        await("Saved inline Preview completes") {
            console.process.isProcessTerminated
        }
        assertEquals(0, console.process.exitCode!!.toInt())
        assertEquals("select 7 as id", saved.inlineSql)
    }

    fun testNativeDebuggerOpensWithoutQueryingAndUsesSourceBreakpointsAndFrames() {
        project();
        val file = configure(
            "models/inspect.sql",
            "with a as (\n  select 1\n), b as (select * from a)\nselect * from b<caret>"
        )
        val typeOf =
            com.intellij.xdebugger.XDebuggerUtil.getInstance().findBreakpointType(DbtCteBreakpointType::class.java)
        assertNotNull(typeOf)
        assertTrue(typeOf.canPutAt(file.virtualFile, 0, getProject()))
        assertTrue(typeOf.canPutAt(file.virtualFile, 2, getProject()))
        assertTrue(
            "Source lines may hold pending breakpoints before compilation",
            typeOf.canPutAt(file.virtualFile, 1, getProject())
        )
        assertTrue(
            "Lines without generated CTEs remain unresolved after compilation",
            typeOf.canPutAt(file.virtualFile, 3, getProject())
        )
        val existingEditors = com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.toSet()
        val frames = com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(workspace, file)!!.ctes
        val session = DbtCteDebugger.open(getProject(), file.virtualFile.path, frames).session
        var nativeUi: com.intellij.execution.ui.RunnerLayoutUi? = null
        session.runWhenUiReady { ui -> nativeUi = ui }
        try {
            await("Native debugger frontend finishes creating its tabs") {
                nativeUi != null
            }
            await("Native Debug session is suspended before any query") {
                session.isSuspended && session.currentPosition != null
            }
            assertFalse(getProject().getService(DbtRunner::class.java).isRunning)
            assertEquals(file.virtualFile, session.currentPosition!!.file)
            val process = session.debugProcess.let { it as DbtCteDebugProcess }
            assertEquals(listOf("a", "b"), process.controller.frames.map { it.name })
            assertTrue(process.controller.result == null)
            assertNotNull(nativeUi!!.component)
            assertNotNull("CTE rows are installed in the native Debug layout", nativeUi!!.findContent("dbt.cte.rows"))
            session.stepOut()
            await("Unsupported Step Out preserves the inspection position") {
                session.isSuspended
            }
            session.runToPosition(session.currentPosition!!, false)
            await("Unsupported Run to Cursor preserves the inspection position") {
                session.isSuspended
            }
            assertFalse(
                "Unsupported debugger actions execute no query",
                getProject().getService(DbtRunner::class.java).isRunning
            )
        } finally {
            session.stop()
            val descriptor = session.let { it as com.intellij.xdebugger.impl.XDebugSessionImpl }
                .getMockRunContentDescriptorIfInitialized()!!
            val removed = com.intellij.execution.ui.RunContentManager.getInstance(getProject()).removeRunContent(
                com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance(), descriptor
            )
            if (!removed) Disposer.dispose(descriptor)
            nativeUi?.let { ui -> Disposer.dispose(ui.contentManager) }
            Disposer.dispose(session.debugProcess.createConsole())
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val remaining =
                com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.filterNot { it in existingEditors }
            assertTrue(
                remaining.map { e -> e.document.text.take(120) }.joinToString(" | ", "Leaked editors: ", ""),
                remaining.isEmpty()
            )
        }
    }

    fun testModelGutterOffersCteDebugOnlyForSqlModels() {
        project()
        val sql = configure("models/inspect.sql", "with a as (select 1) select * from a<caret>")
        val contributor = DbtModelRunLineMarker()
        val info = contributor.getSlowInfo(sql.findElementAt(0)!!)!!
        assertNotNull(info)
        val debug = info.actions.filter { it.let { it is DbtInspectCtesAction } }
        assertEquals(1, debug.size)
        assertEquals("Debug dbt CTEs", debug.first().templatePresentation.text)
        assertTrue(
            "Standard Run actions remain available",
            info.actions.any { it.let { it is com.intellij.execution.lineMarker.ExecutorAction } })
        val python = configure("models/python_model.py", "def model(dbt, session):\n  return None<caret>")
        val pythonInfo = contributor.getSlowInfo(python.findElementAt(0)!!)!!
        assertNotNull(pythonInfo)
        assertFalse(pythonInfo.actions.any { it.let { it is DbtInspectCtesAction } })
        val test = configure("tests/check.sql", "select 1<caret>")
        assertNull(contributor.getSlowInfo(test.findElementAt(0)!!))
        assertFalse(DbtTestRunLineMarker().getSlowInfo(test.findElementAt(0)!!)!!.actions.any { it.let { it is DbtInspectCtesAction } })
    }

    fun testModelRunContextDoesNotDiscoverUnrelatedTests() {
        project()
        val model = add("models/orders.sql", "select 1 as id")
        val test = add("tests/check_orders.sql", "select * from {{ ref('orders') }}")
        var testMetadataReads = 0
        DbtDeclarationSource.EP.point.registerExtension(object : DbtDeclarationSource {
            override fun declarations(w: DbtWorkspace): List<DbtSymbol> = emptyList()
            override fun documentation(w: DbtWorkspace, symbol: DbtSymbol): DbtResourceData? {
                if (symbol.kind == "test") testMetadataReads += 1
                return null
            }
        }, getTestRootDisposable())
        val configuration = runConfiguration()
        val producer = DbtRunProducer()
        val element = model.findElementAt(0)!!
        val context = ConfigurationContext(element)
        assertTrue(DbtTestRunLineMarker.testsAt(element)?.isEmpty() != false)
        assertTrue(producer.setupConfigurationFromContext(configuration, context, Ref(element)))
        assertEquals("Run", configuration.action)
        assertEquals(model.virtualFile.path, configuration.scope)
        assertTrue(producer.isConfigurationFromContext(configuration, context))
        assertEquals("Opening a model Run menu must not load test metadata", 0, testMetadataReads)

        val testElement = test.findElementAt(0)!!
        assertTrue(
            producer.setupConfigurationFromContext(
                configuration,
                ConfigurationContext(testElement),
                Ref(testElement)
            )
        )
        assertEquals("Test", configuration.action)
        assertTrue(configuration.exactTests)
        assertEquals(listOf("fqn:analytics.check_orders"), configuration.selectors)
        assertTrue("Test contexts still discover exact test identities", testMetadataReads > 0)
    }

    fun testModelRunContextKeepsPackageOwnershipAndRejectsUnavailableModels() {
        project()
        val local = add("models/orders.sql", "select 1")
        val python = add("models/python_model.py", "def model(dbt, session):\n  return None")
        add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
        val packaged = add("dbt_packages/util/models/orders.sql", "select 2")
        val disabled = add("models/disabled.sql", "{{ config(enabled=false) }} select 3")
        val generated = add("target/generated.sql", "select 4")
        val macroFile = add("macros/example.sql", "{% macro example() %}select 5{% endmacro %}")
        val producer = DbtRunProducer()
        for (file in listOf(local, python, packaged, disabled, generated, macroFile)) {
            val configuration = runConfiguration()
            val element = file.findElementAt(0)!!
            val context = ConfigurationContext(element)
            val available = producer.setupConfigurationFromContext(configuration, context, Ref(element))
            assertEquals(file.name, listOf(local, python, packaged).contains(file), available)
            if (available) {
                assertEquals("Package models execute in the consuming project", root.directory.path, configuration.root)
                assertEquals(file.virtualFile.path, configuration.scope)
                assertTrue(producer.isConfigurationFromContext(configuration, context))
                configuration.scope = generated.virtualFile.path
                assertFalse(producer.isConfigurationFromContext(configuration, context))
            }
        }
    }

    fun testEveryCommandPreparesWithoutChangingProjectDefaultsAndRoundTripsOverrides() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}, prod: {type: postgres}}}\n")
        val model = add("models/orders.sql", "{{ config(materialized='incremental') }} select 1 as id")
        val w = workspace;
        val root = w.roots.first()
        root.runOptions.target = "dev"
        val config = runConfiguration()
        config.target = "prod";
        config.executable = "/usr/bin/python3";
        config.executableArguments = "'wrapper with spaces.py'"
        for (action in DbtCommand.Actions + "Resolve Selection") {
            config.action = action
            config.scope = if (action == "Preview") model.virtualFile.path else ""
            config.checkConfiguration()
            val command = config.prepare()
                .fold({ it.first }, { error -> fail(action + ": " + error); throw AssertionError(error) })
            assertEquals(action, command.action); assertEquals("prod", command.target)
            assertEquals(listOf("/usr/bin/python3", "wrapper with spaces.py"), command.prefix)
            assertEquals("dev", root.runOptions.target)
            assertEquals("dbt", root.runOptions.executable)
        }
        config.action = "Build";
        config.scope = model.virtualFile.path;
        config.fullRefresh = true
        val xml = Element("configuration");
        config.writeExternal(xml)
        val restored = runConfiguration();
        restored.readExternal(xml)
        assertEquals(config.scope, restored.scope);
        assertEquals(config.target, restored.target)
        assertEquals(config.executableArguments, restored.executableArguments)
        assertTrue(restored.prepare().getOrThrow().first.request.fullRefresh)
    }

    fun testStoredConfigurationsHaveIndependentScopesAndGraphFilteringCannotRetargetThem() {
        project();
        val one = add("models/one.sql", "select 1");
        val two = add("models/two.sql", "select 2")
        val first = runConfiguration();
        first.action = "Run";
        first.scope = one.virtualFile.path
        val second = runConfiguration();
        second.action = "Compile";
        second.scope = two.virtualFile.path
        val before = first.prepare().getOrThrow().first
        val panel = DbtToolWindowPanel(getProject());
        Disposer.register(getTestRootDisposable(), panel)
        panel.handleMessage("""{"type":"filter","id":"two","value":""}""")
        assertEquals(before.arguments, first.prepare().getOrThrow().first.arguments)
        assertTrue(second.prepare().getOrThrow().first.arguments.contains("fqn:analytics.two"))
        assertFalse(second.prepare().getOrThrow().first.arguments.contains("fqn:analytics.one"))
    }

    fun testCliSelectionBecomesUnusableAfterSourceOrExecutionSettingsChange() {
        project();
        val file = add("models/orders.sql", "select 1")
        val config = runConfiguration();
        config.action = "Run";
        config.selectors = listOf("runtime:unknown")
        assertTrue(config.prepare().isFailure)
        config.cliSelection = (config.sourceKey(workspace) to listOf("model.analytics.orders"))
        assertTrue(config.prepare().isSuccess)
        edit(file, "select 2")
        assertTrue("Stale CLI resolution cannot execute", config.prepare().isFailure)
        config.cliSelection = (config.sourceKey(workspace) to listOf("model.analytics.orders"))
        config.target = "prod"
        assertTrue(config.prepare().isFailure)
        config.cliSelection = (config.sourceKey(workspace) to listOf("missing.resource"))
        assertTrue("Unknown CLI identities must never widen execution", config.prepare().isFailure)
    }

    fun testRunEditorExplainsPrecedenceAndDropsInactiveCommandFields() {
        project()
        val model = add("models/orders.sql", "select 1 as id")
        val config = runConfiguration()
        config.action = "Preview"
        config.scope = model.virtualFile.path
        config.inlineSql = "select 7"
        config.completed = ({ _ -> })
        val editor = DbtRunEditor(getProject())
        try {
            val ui = editor.component
            editor.resetFrom(config)
            fun <A> field(label: String): A =
                components(ui).filterIsInstance<javax.swing.JLabel>().first { it.text == label }.labelFor as A

            val command = field<javax.swing.JComboBox<String>>("Command:")
            val scope = field<com.intellij.openapi.ui.TextFieldWithBrowseButton>("Scope:")
            val selection = field<javax.swing.JTextField>("Selection:")
            val named = field<javax.swing.JTextField>("Named selector (overrides Selection):")
            assertFalse(selection.isEnabled)
            command.setSelectedItem("Run")
            assertTrue(selection.isEnabled)
            selection.setText("orders+")
            assertFalse(scope.isEnabled)
            named.setText("nightly")
            assertFalse(selection.isEnabled)
            assertTrue(components(ui).filterIsInstance<javax.swing.JLabel>().map { it.text }
                .contains("Effective scope: Named selector: nightly"))
            editor.applyTo(config)
            assertEquals("", config.inlineSql)
            assertTrue(config.completed == null)
            assertEquals(emptyList<String>(), config.selectors)
            assertEquals("nightly", config.namedSelector)
            command.setSelectedItem("Source Freshness")
            editor.applyTo(config)
            assertEquals("", config.scope)
            assertEquals("", config.namedSelector)
            assertEquals(DbtCommandScope.All, config.prepare().getOrThrow().first.request.scope)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testSwitchingAnExactTestConfigurationToAWholeProjectCommandDropsTestIdentity() {
        project()
        add("models/orders.sql", "select 1")
        val config = runConfiguration()
        config.action = "Test"
        config.exactTests = true
        config.selectors = listOf("fqn:analytics.check_orders")
        val editor = DbtRunEditor(getProject())
        try {
            val ui = editor.component
            editor.resetFrom(config)
            val command =
                components(ui).filterIsInstance<javax.swing.JComboBox<*>>().first() as javax.swing.JComboBox<String>
            command.setSelectedItem("Source Freshness")
            editor.applyTo(config)
            assertFalse(config.exactTests)
            assertTrue(config.selectors.isEmpty())
            assertEquals(DbtCommandScope.All, config.prepare().getOrThrow().first.request.scope)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testSuccessfulCompletionCallbackDoesNotRunAfterFailure() {
        val (config, directory) = setup()
        config.action = "Compile"
        var completed = emptyList<DbtCommand>()
        config.completed = { command -> completed = completed + command }
        Files.writeString(directory.resolve("release"), "")
        val successful = start(config)
        config.completed = null
        await("Successful compilation invokes its completion action") { successful.process.isProcessTerminated && completed.isNotEmpty() }
        assertEquals(listOf("Compile"), completed.map { it.action })
        val failed = runConfiguration("failed compilation")
        failed.action = "Compile"
        failed.executable = "/usr/bin/python3"
        failed.executableArguments = "-c 'import sys; sys.exit(1)'"
        failed.completed = { command -> completed = completed + command }
        val failure = start(failed)
        await("Failed compilation completes without opening a result") { failure.process.isProcessTerminated }
        assertEquals(1, failure.process.exitCode!!.toInt())
        assertEquals(1, completed.size)
    }

    fun testPreviewRejectsFoldersAndFreshnessRemainsProjectWide() {
        project();
        val model = add("models/orders.sql", "select 1")
        val config = runConfiguration();
        config.action = "Preview"
        assertTrue(config.prepare().isFailure)
        config.scope = model.virtualFile.path
        assertTrue(config.prepare().isSuccess)
        config.action = "Source Freshness"
        assertTrue(config.prepare().isFailure)
        config.scope = ""
        assertEquals(DbtCommandScope.All, config.prepare().getOrThrow().first.request.scope)
    }
}
