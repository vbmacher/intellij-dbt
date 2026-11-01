package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonParser
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import com.intellij.usages.*
import com.intellij.usageView.UsageInfo

/**
 * Stable project/model/directory choice captured from a workspace read.
 * An empty [path] denotes all models of [root]; command preparation revalidates availability before execution.
 */
internal data class DbtRunSelection(val root: String, val path: String, val directory: Boolean, val label: String) {
    companion object {
        private fun models(w: DbtWorkspace): List<DbtSymbol> = w.symbols.filter { s ->
            s.kind == "model" && s.isCode && s.isEnabled &&
                    w.owner(s)?.let { !it.excluded(s.file.virtualFile) } == true
        }.distinctBy { it.file.virtualFile.path }

        fun available(w: DbtWorkspace): List<DbtRunSelection> {
            val sources = models(w)
            return w.roots.flatMap { root ->
                val project =
                    root.name + if (w.roots.count { it.name == root.name } > 1) " (${root.directory.path})" else ""
                val local = sources.filter { w.owner(it) == root }
                val folders = (listOf(root.directory) + local.flatMap { s ->
                    generateSequence(s.file.virtualFile.parent) { it.parent }
                        .takeWhile { VfsUtilCore.isAncestor(root.directory, it, false) }.toList()
                }).distinct()
                folders.map { folder ->
                    val relative = root.relative(folder)?.takeIf { it.isNotEmpty() } ?: "."
                    DbtRunSelection(root.directory.path, folder.path, true, "Directory: $project / $relative/")
                } + local.map { s ->
                    DbtRunSelection(
                        root.directory.path, s.file.virtualFile.path, false,
                        "Model: $project.${s.relationName}${
                            s.version?.let { " v$it" }.orEmpty()
                        } — ${root.relative(s.file.virtualFile) ?: s.file.name}"
                    )
                }
            }.distinctBy { it.path }.sortedWith(compareBy({ it.root }, { !it.directory }, { it.path }))
        }

        fun owner(w: DbtWorkspace, selection: DbtRunSelection): DbtRoot? =
            w.roots.find { it.directory.path == selection.root }

        fun all(w: DbtWorkspace, preferredRoot: String): DbtRunSelection? {
            val roots = w.roots.map(w::executionRoot).distinct()
            return (roots.find { it.directory.path == preferredRoot }
                ?: roots.find { it.config.project.basePath == it.directory.path }
                ?: roots.singleOrNull())?.let { DbtRunSelection(it.directory.path, "", true, "All models: ${it.name}") }
        }

        fun selectedModels(w: DbtWorkspace, selection: DbtRunSelection): List<DbtSymbol> = models(w).filter { s ->
            val owner = w.owner(s);
            val selected = owner(w, selection)
            owner != null && selected != null && w.executionRoot(owner) == w.executionRoot(selected) &&
                    (selection.path.isEmpty() || if (selection.directory) s.file.virtualFile.path.startsWith(selection.path + "/") else s.file.virtualFile.path == selection.path)
        }

        /** Expand folders to model FQNs so similarly named paths in other packages cannot join the run. */
        fun selectors(w: DbtWorkspace, selection: DbtRunSelection): List<String> =
            selectedModels(w, selection).mapNotNull { s ->
                w.owner(s)?.let { owner ->
                    val folders = owner.resourceFolders(s.file.virtualFile)?.second.orEmpty()
                    "fqn:" + (listOf(s.namespace) + folders + s.name + listOfNotNull(s.version?.let { "v$it" })).joinToString(
                        "."
                    )
                }
            }
    }
}

/** Exact CLI identity and local source location; test declarations may have no corresponding resource [symbol]. */
internal data class DbtCliResource(
    val id: String,
    val selector: String,
    val file: PsiFile,
    val range: TextRange,
    val symbol: DbtSymbol?
)

/** Immutable selection data: resolving and completing a selector never launches dbt. */
internal object DbtSelectionReview {
    /** Use the same exact test identities and declaration positions as the native test runner. */
    fun knownCliResources(w: DbtWorkspace, root: DbtRoot): List<DbtCliResource> {
        val resources = DbtSelection.resolve(w, root, "").resources.map {
            DbtCliResource(
                it.uniqueId,
                DbtSelection.selector(w, it),
                it.file,
                it.range,
                it
            )
        }
        val tests = DbtTestDiscovery.discover(w, mapOf(root.directory.path to root.runOptions))
            .filter { it.root == root.directory.path && it.selector.isNotEmpty() }.mapNotNull { t ->
                DbtYaml.psiFile(w.project, t.file)
                    ?.let { DbtCliResource(t.id, t.selector, it, TextRange(t.offset, t.offset), null) }
            }
        return (tests + resources).distinctBy { it.id }
    }

    fun expression(w: DbtWorkspace, root: DbtRoot, selected: DbtRunSelection?): String =
        root.runOptions.selection.trim().takeIf { it.isNotEmpty() }
            ?: selected?.takeIf { it.path.isNotEmpty() && it.path != root.directory.path }
                ?.let { DbtRunSelection.selectors(w, it).joinToString(" ") }.orEmpty()

    /** Expand named terms before passing compound selections or exclusions to the CLI. */
    fun cliTerms(w: DbtWorkspace, root: DbtRoot, text: String): Result<List<String>> {
        val terms = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val named = terms.flatMap { it.split(',') }
            .any { it.removePrefix("@").replaceFirst(Regex("^[0-9]*\\+"), "").startsWith("selector:") }
        if (!named) return Result.success(terms)
        val resolved = DbtSelection.resolve(w, root, text)
        return if (resolved.complete) Result.success(resolved.resources.map { DbtSelection.selector(w, it) }.distinct())
        else Result.failure(
            IllegalArgumentException(
                "Compound named-selector expressions must resolve completely before running dbt. " +
                        "Use a locally resolvable named selector, or ordinary CLI terms for explicit dbt ls resolution.\n" + resolved.problems.joinToString(
                    "\n"
                )
            )
        )
    }

    fun completions(w: DbtWorkspace, root: DbtRoot): List<String> {
        val resources = DbtSelection.resolve(w, root, "").resources
        val named =
            w.rootFile(root, "selectors.yml")?.let(DbtYaml::mapping)?.let { DbtYaml.items(it, "selectors") }.orEmpty()
                .mapNotNull { DbtYaml.string(it, "name") }.map { "selector:$it" }
        return (resources.map { DbtSelection.selector(w, it) } + resources.map { "package:${it.namespace}" } +
                resources.flatMap {
                    DbtResourceData.get(w, it).attributes["tags"]?.split(',').orEmpty()
                        .filter { tag -> tag.isNotEmpty() }.map { tag -> "tag:$tag" }
                } +
                named + listOf(
            "fqn:",
            "path:",
            "package:",
            "tag:",
            "group:",
            "source:",
            "selector:",
            "state:modified"
        ) +
                listOf(
                    "model",
                    "seed",
                    "snapshot",
                    "test",
                    "source",
                    "exposure"
                ).map { "resource_type:$it" }).distinct().sorted()
    }

    /**
     * Completes the term at [caret], preserving intersection/union text and graph-depth operators.
     * Each result contains the display candidate, replacement text and replacement caret offset.
     */
    fun complete(text: String, caret: Int, candidates: List<String>): List<Triple<String, String, Int>> {
        val position = caret.coerceIn(0, text.length)
        val start = (position - 1 downTo 0).firstOrNull { text[it].isWhitespace() || text[it] == ',' }?.plus(1) ?: 0
        val end = (position until text.length).firstOrNull { text[it].isWhitespace() || text[it] == ',' } ?: text.length
        val typed = text.substring(start, position)
        val prefix = Regex("^[0-9]*\\+").find(typed)?.value.orEmpty()
        val suffix = Regex("\\+[0-9]*$").find(text.substring(position, end))?.value.orEmpty()
        return candidates.filter { it.startsWith(typed.removePrefix(prefix)) }.take(200).map { candidate ->
            val replacement = prefix + candidate + suffix
            Triple(candidate, text.take(start) + replacement + text.drop(end), start + replacement.length)
        }
    }

    /** Reads unique IDs from dbt ls JSON records; blank output is an empty selection, malformed log-only output is an error. */
    fun cliResources(output: String): Result<List<String>> {
        val rows = output.lineSequence().filter { it.trim().startsWith("{") }.mapNotNull { line ->
            runCatching {
                JsonParser.parseString(line).asJsonObject["unique_id"]?.takeIf { it.isJsonPrimitive }?.asString
            }.getOrNull()
        }.distinct().toList()
        return if (rows.isNotEmpty()) Result.success(rows) else if (output.trim().isEmpty()) Result.success(emptyList())
        else Result.failure(IllegalArgumentException("dbt ls returned no resource records. Check raw process output; the selection may be empty or unsupported."))
    }
}

internal object DbtSelectionResults {
    private fun show(
        project: Project,
        label: String,
        resources: List<Pair<PsiFile, TextRange>>,
        problems: List<String>
    ): UsageView {
        val presentation = UsageViewPresentation().apply {
            tabText = label; tabName = label; setDynamicUsagesString("resources"); isOpenInNewTab = true
            scopeText = if (problems.isEmpty()) "Matched resources" else "Incomplete: " + problems.joinToString("; ")
        }
        val usages = resources.map { (file, range) ->
            UsageInfo2UsageAdapter(
                UsageInfo(
                    file,
                    range.startOffset,
                    range.endOffset
                )
            ) as Usage
        }.toTypedArray()
        val view = UsageViewManager.getInstance(project).showUsages(emptyArray<UsageTarget>(), usages, presentation)
        if (problems.isNotEmpty()) {
            val warning = DbtUi.textArea("Incomplete selection\n" + problems.joinToString("\n"))
            warning.accessibleContext.accessibleName = "Selection warnings"; warning.rows =
                minOf(4, problems.size + 1); warning.columns = 70
            view.setAdditionalComponent(com.intellij.ui.components.JBScrollPane(warning))
        }
        return view
    }

    fun review(configuration: DbtRunConfiguration) {
        val project = configuration.project
        DbtUi.readLater(project, smartIn = project, read = {
            val w = DbtWorkspace.get(project)
            configuration.configuredRoot(w).map { root ->
                val selection = if (configuration.scope.isEmpty()) DbtRunSelection.all(
                    w,
                    root.directory.path
                ) else DbtRunSelection.available(w).find { it.path == configuration.scope }
                val expression = DbtSelectionReview.expression(w, root, selection)
                val result = DbtSelection.resolve(w, root, expression, root.runOptions.exclude)
                Triple(result.resources, result.problems, configuration.prepare().map { it.first })
            }
        }) { result ->
            result.fold({ (resources, problems, command) ->
                val view = show(
                    project,
                    "dbt local selection · " + configuration.target,
                    resources.map { it.file to it.range },
                    problems
                )
                view.addButtonToLowerPane({
                    DbtLineageSelection.show(
                        project, configuration.root,
                        if (configuration.namedSelector.isNotEmpty()) "selector:" + configuration.namedSelector
                        else if (configuration.selectors.isNotEmpty()) configuration.selectors.joinToString(" ")
                        else (command.getOrNull()?.request?.scope as? DbtCommandScope.Selected)?.terms?.joinToString(" ")
                            .orEmpty(),
                        configuration.exclude.joinToString(" ")
                    )
                }, "Show in Lineage", false)
                command.onSuccess { value ->
                    view.addButtonToLowerPane(
                        { DbtUi.copy(value.commandLine) },
                        "Copy command",
                        true
                    )
                }
            }, { DbtNotifications.show(project, "dbt selection", it.message.orEmpty(), NotificationType.ERROR) })
        }
    }

    fun showCli(configuration: DbtRunConfiguration, raw: String) {
        val project = configuration.project
        DbtUi.readLater(project, read = {
            DbtSelectionReview.cliResources(raw).mapCatching { ids ->
                val root = configuration.configuredRoot(DbtWorkspace.get(project)).getOrThrow()
                val known = DbtSelectionReview.knownCliResources(DbtWorkspace.get(project), root)
                Triple(known.filter { it.id in ids }, ids.filterNot { id -> known.any { it.id == id } }, ids)
            }
        }) { result ->
            result.fold({ (resources, unknown, ids) ->
                if (unknown.isEmpty()) configuration.selectionResolved?.invoke(ids)
                show(
                    project, "dbt CLI selection · " + configuration.target, resources.map { it.file to it.range },
                    unknown.map { "CLI resource unavailable locally; execution blocked: $it" })
            }, { DbtNotifications.show(project, "dbt CLI selection", it.message.orEmpty()) })
        }
    }
}
