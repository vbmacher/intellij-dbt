package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.*
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.Module
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon
import org.jetbrains.yaml.psi.*

/**
 * A browsable test declaration with source provenance and, when known, an exact executable [selector].
 * An empty selector keeps an unresolved generated test visible without guessing its manifest identity.
 */
internal data class DbtTestDefinition(
    val id: String,
    val name: String,
    val kind: String,
    val group: String,
    val root: String,
    val file: VirtualFile,
    val offset: Int,
    val selector: String,
    val dependencies: List<String>,
    val fixtures: List<Pair<VirtualFile, Int>> = emptyList(),
    val relation: String = "",
    val provenance: String = "Source declaration",
    val packageName: String = "",
    val projectName: String = "",
    val description: String = ""
)

/**
 * Discovers source tests offline and enriches them with validated manifest identities when available.
 * [discover] retains project/package/version ownership and matches generated tests only to exact declarations.
 */
internal object DbtTestDiscovery {
    fun discover(w: DbtWorkspace, executionOptions: Map<String, DbtRunOptions> = emptyMap()): List<DbtTestDefinition> {
        val sourceDependencies by lazy {
            DbtLineage.edges(w).filter { !it.ambiguous && it.to.kind in DbtSymbol.RelationKinds }.groupBy { it.from.id }
                .mapValues { (_, edges) -> edges.map { it.to.uniqueId } }
        }
        val source = w.symbols.filter { s ->
            s.kind in setOf(
                "test",
                "unit_test"
            ) && !s.metadata && s.isEnabled && w.symbols.none { it.kind == "generic_test" && it.file == s.file }
        }.mapNotNull { s ->
            w.owner(s)?.let { owner ->
                val root = w.executionRoot(owner);
                val group = s.mapping?.let { DbtYaml.string(it, "model") } ?: "Data tests"
                val fixtures =
                    s.mapping?.let { m -> DbtYaml.items(m, "given") + listOfNotNull(DbtYaml.map(m, "expect")) }
                        .orEmpty().flatMap { m ->
                        DbtYaml.string(m, "fixture")?.let { name ->
                            w.symbols.filter { it.kind == "fixture" && it.name == name && w.owner(it) == owner }
                                .map { it.file.virtualFile to it.range.startOffset }
                        }.orEmpty()
                    }
                val folders = owner.resourceFolders(s.file.virtualFile)?.second.orEmpty();
                val fqn = listOf(s.namespace) + folders + s.name
                val dependencies = if (s.kind == "unit_test") w.declarations(
                    setOf("model"),
                    group.split('.').last(),
                    owner,
                    if (group.contains('.')) group.split('.').firstOrNull() else null
                ).map { it.uniqueId }
                else (DbtResourceData.get(w, s).dependencies + sourceDependencies[s.id].orEmpty()).distinct()
                DbtTestDefinition(
                    s.uniqueId,
                    s.presentableText,
                    if (s.kind == "unit_test") "Unit tests" else "Data tests",
                    group,
                    root.directory.path,
                    s.file.virtualFile,
                    s.range.startOffset,
                    if (s.kind == "unit_test") "fqn:" + (listOf(s.namespace) + folders + listOf(
                        group,
                        s.name
                    )).joinToString(".") + ",test_type:unit" else "fqn:" + fqn.joinToString("."),
                    dependencies,
                    fixtures,
                    packageName = owner.name,
                    projectName = root.name,
                    description = DbtResourceData.get(w, s).description
                )
            }
        }
        val attached = w.symbols.filter {
            it.mapping != null && (it.kind in DbtSymbol.RefKinds || it.kind in setOf(
                "column",
                "source_table"
            ))
        }.flatMap { s ->
            val owner = w.owner(s);
            val mapping = s.mapping
            if (owner == null || mapping == null) emptyList() else listOf(
                "tests",
                "data_tests"
            ).flatMap { k -> (DbtYaml.value(mapping, k) as? YAMLSequence)?.items.orEmpty() }.mapNotNull { item ->
                val declaration = when (val value = item.value) {
                    is YAMLScalar -> Triple(value.textValue, value.textOffset, null)
                    is YAMLMapping -> value.keyValues.firstOrNull()
                        ?.let { Triple(it.keyText, it.textOffset, it.value as? YAMLMapping) }

                    else -> null
                }
                declaration?.let { (name, offset, config) ->
                    val customName = config?.let { DbtYaml.string(it, "name") };
                    val resource = if (s.kind == "column") w.relations(s).firstOrNull() else s
                    DbtTestDefinition(
                        "declaration:" + s.id + ":" + offset,
                        (customName ?: name) + if (s.kind == "column") " (${s.name})" else "",
                        "Data tests",
                        resource?.presentableText ?: s.owner,
                        w.executionRoot(owner).directory.path,
                        s.file.virtualFile,
                        offset,
                        customName?.let { "fqn:${s.namespace}.$it" }.orEmpty(),
                        listOfNotNull(resource?.uniqueId),
                        packageName = owner.name,
                        projectName = w.executionRoot(owner).name
                    )
                }
            }
        }.distinctBy { listOf(it.root, it.file.path, it.offset, it.dependencies) }
        val declarations = source + attached
        val manifest = w.roots.map(w::executionRoot).distinct()
            .map { executionOptions[it.directory.path]?.let(it::withRunOptions) ?: it }.flatMap { root ->
            val top = root.setting("manifest")
                ?.let { DbtManifestReader.get(w.project).read(root, it, DbtManifestPolicy.TestDiscovery).getOrNull() }
            top?.resourcesIn("nodes", "unit_tests")?.map { it.data }?.filter { n ->
                DbtJson.string(n, "resource_type") in setOf(
                    "test",
                    "unit_test"
                ) && DbtJson.string(
                    DbtExecutionEvidence.obj(n, "config"),
                    "enabled"
                ) != "false"
            }?.mapNotNull { n ->
                ProgressManager.checkCanceled()
                val id = DbtJson.string(n, "unique_id");
                val name = DbtJson.string(n, "name");
                val pkg = DbtJson.string(n, "package_name");
                val path = DbtJson.string(n, "original_file_path")
                val deps = DbtExecutionEvidence.obj(
                    n,
                    "depends_on"
                )["nodes"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
                val fqn = n["fqn"]?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(".") { it.asString }.orEmpty()
                val matching = declarations.filter { t ->
                    t.root == root.directory.path && (t.id == id || t.file.path.endsWith("/$path") && t.dependencies.any { it in deps } &&
                            (t.name.takeWhile { it != ' ' }.split('.')
                                .last() == name || t.name.takeWhile { it != ' ' } == DbtJson.string(
                                DbtExecutionEvidence.obj(
                                    n,
                                    "test_metadata"
                                ), "name"
                            )) &&
                            (DbtJson.string(n, "column_name").isEmpty() || t.name.endsWith(
                                "(" + DbtJson.string(
                                    n,
                                    "column_name"
                                ) + ")"
                            )))
                }
                val owner = w.roots.find { it.name == pkg && w.executionRoot(it) == root }
                val ignored = owner?.let { r -> r.psiFile(path)?.let { r.excluded(it.virtualFile) } } == true
                val file = if (ignored) null else matching.singleOrNull()?.file ?: owner?.psiFile(path)?.virtualFile
                ?: root.setting("manifest")?.let(root::psiFile)?.virtualFile
                file?.let { f ->
                    val exact = matching.size == 1
                    DbtTestDefinition(
                        id,
                        "$pkg.$name",
                        if (DbtJson.string(n, "resource_type") == "unit_test") "Unit tests" else "Data tests",
                        deps.filter { it.startsWith("model.") }.joinToString(", ").ifEmpty { "Data tests" },
                        root.directory.path,
                        f,
                        if (exact) matching.first().offset else 0,
                        if (fqn.isNotEmpty()) "fqn:" + fqn + ",resource_type:" + DbtJson.string(
                            n,
                            "resource_type"
                        ) else "",
                        deps,
                        if (exact) matching.first().fixtures else emptyList(),
                        DbtJson.string(n, "relation_name"),
                        if (exact) "Source definition + manifest identity" else "Manifest identity; source range unavailable",
                        pkg,
                        root.name,
                        DbtJson.string(n, "description")
                    )
                }
            }.orEmpty()
        }
        val superseded = manifest.flatMap { t ->
            declarations.filter { it.root == t.root && it.file == t.file && it.offset == t.offset && it.dependencies.any { dep -> dep in t.dependencies } }
                .map { it.id }
        }.toSet()
        return (declarations.filterNot { it.id in superseded } + manifest).distinctBy { it.root to it.id }
            .sortedWith(compareBy({ it.root }, { it.group }, { it.kind }, { it.name }, { it.id }))
    }

    /** Builds an explicit bounded SELECT of a stored failure relation using the captured execution context. */
    fun preview(command: DbtCommand, relation: String, limit: Int): Result<DbtCommand> = dbtResult {
        require(
            relation.trim().isNotEmpty()
        ) { "No stored failing rows are available. Configure store_failures and run this test." }
        command.copy(
            request = DbtCommandRequest(
                DbtCommandAction.Preview,
                DbtCommandScope.Inline("select * from " + relation),
                limit = DbtCommandRequest.limit(limit)
            ), colors = false, sourceSql = "", previewSource = null
        )
    }

    /** Preserve the exact executable, environment, profiles and target from the original run. */
    fun rerun(
        command: DbtCommand,
        tests: List<DbtTestDefinition>,
        results: List<DbtExecutionResult>
    ): Result<DbtCommand> {
        val failed = results.filter { it.failed }.map { it.id }.toSet(); return select(
            command,
            tests.filter { it.id in failed })
    }

    /** Exact test scope replaces prior selectors while retaining target, profiles and execution flags. */
    fun select(command: DbtCommand, intended: List<DbtTestDefinition>): Result<DbtCommand> = dbtResult {
        require(intended.isNotEmpty()) { "No tests in this scope." }
        require(intended.none { it.root != command.executionRoot }) { "Choose tests from one execution project." }
        require(intended.none { it.selector.isEmpty() }) { "Exact test identities unavailable. Generate Docs for this project and target, then refresh tests." }
        command.copy(request = command.request.copy(scope = DbtCommandScope.Selected(intended.map { it.selector }
            .distinct()), exclude = emptyList(), indirectSelection = "empty"))
    }
}

/**
 * Adapts the shared runner to IntelliJ's native test tree and Stop/rerun actions.
 * Streamed records establish progress; completed artifact evidence determines final outcomes.
 * Reruns retain the captured execution context and require exact test selectors.
 */
internal class DbtTestConsole(private val configuration: DbtRunConfiguration, executor: Executor) {
    private val project = configuration.project;
    private val runner = project.getService(DbtRunner::class.java)
    private var observing = false;
    private var canceled = false;
    private var command: DbtCommand? = null
    private var definitions = emptyList<DbtTestDefinition>();
    private val started = mutableSetOf<String>();
    private val finished = mutableSetOf<String>()
    val process = DbtManagedProcess { p -> canceled = true; if (observing) runner.stop() else p.complete(130) }
    val properties = object : SMTRunnerConsoleProperties(configuration, "dbt", executor) {
        init {
            isIdBasedTestTree = true
        }

        override fun getTestLocator(): SMTestLocator = Locator
        override fun createRerunFailedTestsAction(console: ConsoleView): AbstractRerunFailedTestsAction {
            val action = object : AbstractRerunFailedTestsAction(console) {
                override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile {
                    val failed =
                        getFailedTests(project).filterIsInstance<SMTestProxy>().filter { it.isLeaf }.map { it.metainfo }
                            .toSet()
                    val selected = definitions.filter { it.id in failed };
                    val rerun = this@DbtTestConsole.configuration.clone() as DbtRunConfiguration
                    rerun.selectors = selected.map { it.selector }.distinct(); rerun.action =
                        "Test"; rerun.fullRefresh = false; rerun.exactTests = true; rerun.namedSelector = ""
                    if (selected.isEmpty() || selected.any { it.selector.isEmpty() } || failed.any { id -> selected.none { it.id == id } }) rerun.selectors =
                        emptyList()
                    rerun.captured = command?.let {
                        it.copy(
                            request = it.request.copy(
                                action = DbtCommandAction.Test,
                                fullRefresh = false
                            )
                        )
                    }
                    return object : MyRunProfile(rerun) {
                        override fun getModules(): Array<Module> = emptyArray()
                        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
                            rerun.getState(executor, environment)
                    }
                }
            }
            action.init(this)
            if (console is SMTRunnerConsoleView) action.setModel(console.resultsViewer)
            return action
        }
    }
    val view = SMTestRunnerConnectionUtil.createConsole(properties)

    init {
        view.attachToProcess(process); process.startNotify()
        runner.listen(view) { event ->
            if (observing) {
                event.execution?.let(::update)
                if (!event.running) {
                    observing = false
                    if (event.execution == null || event.exitCode?.let { it != 0 } == true && !canceled) message(
                        "message",
                        "text" to event.message,
                        "status" to "ERROR"
                    )
                    process.complete(event.exitCode ?: if (canceled) 130 else 1)
                }
            }
        }
        Disposer.register(
            view,
            com.intellij.openapi.Disposable { if (!process.isProcessTerminated) process.destroyProcess() })
    }

    private fun message(kind: String, vararg fields: Pair<String, String>) {
        val text =
            "\n##teamcity[" + kind + fields.joinToString("") { (key, value) -> " $key='${escape(value)}'" } + "]\n"
        process.notifyTextAvailable(text, ProcessOutputTypes.STDOUT)
    }

    fun start(value: DbtCommand, tests: List<DbtTestDefinition>) {
        if (!canceled && !process.isProcessTerminated) {
            command = value; definitions = tests; view.print(
                value.commandLine + "\n",
                ConsoleViewContentType.SYSTEM_OUTPUT
            ); observing = true
            if (!runner.start(value, process::output)) {
                observing = false; fail("Another dbt command is running. Stop it before starting tests.")
            }
        }
    }

    fun showEvidence(execution: DbtExecutionRun, tests: List<DbtTestDefinition>) {
        definitions = tests; view.print(
            "Target: ${execution.target} · Run: ${execution.invocation} · ${execution.timestamp}\n${execution.artifact}\n",
            ConsoleViewContentType.SYSTEM_OUTPUT
        )
        val compatible = if (execution.problem.isEmpty()) execution else execution.copy(results = emptyList())
        compatible.results.filterNot { it.test }
            .forEach { message("message", "text" to "${it.id}: ${it.status}\n${it.message}") }
        update(compatible.copy(active = false)); process.complete(if (execution.problem.isNotEmpty() || execution.results.any { it.failed }) 1 else 0)
    }

    fun fail(problem: String) {
        message("message", "text" to problem, "status" to "ERROR"); process.complete(1)
    }

    fun update(execution: DbtExecutionRun) {
        execution.results.filter { it.test }.forEach { result ->
            val test = definitions.find { it.id == result.id };
            val name = test?.name ?: result.id
            if (result.id !in started) {
                message(
                    "testStarted",
                    "name" to name,
                    "nodeId" to result.id,
                    "parentNodeId" to "0",
                    "locationHint" to test?.let(::url).orEmpty(),
                    "metainfo" to result.id
                ); started.add(result.id)
            }
            if (!execution.active && result.id !in finished && result.status !in setOf(
                    "running",
                    "unknown",
                    "canceled"
                ) && (result.status != "passed" || execution.problem.isEmpty())
            ) {
                val details =
                    result.message + "\nStatus: ${result.status}; failures: ${result.failures}\n" + if (result.relation.isNotEmpty()) "Stored failure relation: " + result.relation + "\n" else ""
                message("testStdOut", "name" to name, "nodeId" to result.id, "out" to details)
                when (result.status) {
                    "failed", "error" -> {
                        val fields = listOf(
                            "name" to name,
                            "nodeId" to result.id,
                            "message" to result.message,
                            "details" to details
                        ) + if (result.status == "error") listOf("error" to "true") else emptyList()
                        message("testFailed", *fields.toTypedArray())
                    }

                    "warning", "skipped" -> message(
                        "testIgnored",
                        "name" to name,
                        "nodeId" to result.id,
                        "message" to (result.status.uppercase() + ": " + result.message)
                    )
                }
                message(
                    "testFinished",
                    "name" to name,
                    "nodeId" to result.id,
                    "duration" to result.duration.toString()
                ); finished.add(result.id)
            }
        }
        if (!execution.active && execution.problem.isNotEmpty()) message(
            "message",
            "text" to execution.problem,
            "status" to "ERROR"
        )
    }

    companion object {
        fun escape(value: String): String =
            value.replace("|", "||").replace("'", "|'").replace("\n", "|n").replace("\r", "|r").replace("[", "|[")
                .replace("]", "|]")

        fun url(test: DbtTestDefinition): String = "dbt-test://" + test.file.url + "#" + test.offset
        val Locator = object : SMTestLocator {
            override fun getLocation(
                protocol: String,
                path: String,
                project: Project,
                scope: GlobalSearchScope
            ): List<Location<*>> {
                val at = path.lastIndexOf('#'); if (protocol != "dbt-test" || at < 0) return emptyList()
                val file = VirtualFileManager.getInstance().findFileByUrl(path.substring(0, at)) ?: return emptyList()
                val psi = DbtYaml.psiFile(project, file) ?: return emptyList();
                val offset = path.substring(at + 1).toIntOrNull() ?: return emptyList()
                val element = psi.findElementAt(offset) ?: return emptyList(); return listOf(
                    PsiLocation.fromPsiElement(
                        element
                    )
                )
            }
        }
    }
}

class DbtTestRunLineMarker : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? = null
    override fun getSlowInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element is PsiFile) return null
        val tests = testsAt(element)?.takeIf { tests ->
            tests.minByOrNull { it.offset }?.let { test ->
                val file = element.containingFile;
                val offset =
                    if (test.offset == 0) firstCode(file) else test.offset; file.findElementAt(offset) == element
            } == true
        } ?: return null
        val state = status(element.containingFile, tests)
        val icon = when (state) {
            "passed" -> AllIcons.RunConfigurations.TestState.Green2; "failed", "error" -> AllIcons.RunConfigurations.TestState.Red2
            "warning", "skipped" -> AllIcons.RunConfigurations.TestState.Yellow2; else -> AllIcons.RunConfigurations.TestState.Run
        }
        return info(
            icon,
            "dbt tests: " + state + if (tests.any { it.selector.isEmpty() }) ". Exact identity unavailable; Generate Docs for this project and target." else ". Run tests"
        )
    }

    companion object {
        fun firstCode(file: PsiFile): Int = file.text.indexOfFirst { !it.isWhitespace() }
        fun info(icon: Icon, tooltip: String, vararg extraActions: AnAction): Info =
            Info(icon, ExecutorAction.getActions(0) + extraActions) { tooltip }

        private val Definitions = Key.create<CachedValue<List<DbtTestDefinition>>>("dbt.test.definitions")
        internal fun definitions(file: PsiFile): List<DbtTestDefinition> {
            val project = file.project
            return CachedValuesManager.getManager(project).getCachedValue(project, Definitions, {
                CachedValueProvider.Result.create(
                    DbtTestDiscovery.discover(DbtWorkspace.get(project)),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    DbtProjectSettings.get(project)
                )
            }, false)
        }

        internal fun testsAt(element: PsiElement?): List<DbtTestDefinition>? {
            val file =
                element?.containingFile?.takeIf { it.isPhysical && DbtWorkspace.get(it.project).model(it) == null }
                    ?: return null
            val tests = definitions(file).filter { it.file == file.virtualFile }
            if (element is PsiFile || tests.size == 1 && tests.first().offset == 0) return tests
            val document = file.viewProvider.document ?: return emptyList();
            val line = document.getLineNumber(element.textOffset)
            return tests.filter { it.offset < document.textLength && document.getLineNumber(it.offset) == line }
        }

        internal fun status(file: PsiFile, tests: List<DbtTestDefinition>): String {
            val w = DbtWorkspace.get(file.project)
            val runs = w.roots.filter { r -> tests.any { it.root == r.directory.path } }
                .flatMap(file.project.getService(DbtExecutionEvidence::class.java)::runs)
                .filter { it.problem.isEmpty() }
            val recorded = runs.flatMap { run -> run.results.map { it.id to if (run.active) "running" else it.status } }
                .distinctBy { it.first }.toMap()
            val states = tests.map { recorded[it.id] ?: "not run" }
            return listOf(
                "error",
                "failed",
                "running",
                "canceled",
                "not run",
                "warning",
                "skipped"
            ).find { it in states } ?: if (states.isNotEmpty()) "passed" else "not run"
        }
    }
}

class DbtModelRunLineMarker : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? = null
    override fun getSlowInfo(element: PsiElement): Info? {
        val file = element.containingFile
        if (element.firstChild != null || element is PsiFile || file == null || element.textOffset != DbtTestRunLineMarker.firstCode(
                file
            ) || DbtWorkspace.get(file.project).model(file) == null
        ) return null
        return if (file.name.endsWith(".sql")) {
            val debug = DbtInspectCtesAction(); debug.templatePresentation.text =
                "Debug dbt CTEs"; debug.templatePresentation.description =
                "Inspect CTE results with stepping and CTE breakpoints"; debug.templatePresentation.icon =
                AllIcons.Actions.StartDebugger
            DbtTestRunLineMarker.info(
                AllIcons.RunConfigurations.TestState.Run,
                "Run / Preview dbt model / Debug dbt CTEs",
                DbtPreviewModelAction(),
                debug
            )
        } else DbtTestRunLineMarker.info(AllIcons.RunConfigurations.TestState.Run, "Run dbt model")
    }
}
