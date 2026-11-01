package com.github.vbmacher.intellij.dbt.navigation.jinja

import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Resolves Jinja names within lexical and host-file rendering contexts without evaluating templates.
 * Local bindings and parameters retain their scope; static wrapper analysis is bounded and preserves ambiguous targets.
 */
class DbtJinjaNavigation : DbtReferenceProvider {
    override fun references(context: DbtNavigationContext): List<DbtNavigationReference> =
        FileReferences(context.file, context.workspace, context.root).collect()

    companion object {
        private const val StaticReference = "static reference"
        private val Resources = DbtSymbol.RefKinds
        private val BuiltinFunctions = setOf(
            "ref",
            "source",
            "var",
            "config",
            "doc",
            "env_var",
            "function",
            "return",
            "log",
            "print",
            "run_query",
            "statement",
            "load_result",
            "local_md5",
            "tojson",
            "fromjson",
            "toyaml",
            "fromyaml",
            "as_bool",
            "as_number",
            "as_native",
            "zip"
        )
        private val BuiltinNamespaces = setOf("adapter", "exceptions", "modules", "flags", "graph", "config")
        private val BuiltinFilters =
            setOf("as_bool", "as_number", "as_native", "tojson", "fromjson", "toyaml", "fromyaml")
        private val ContextVariables = setOf(
            "this",
            "model",
            "project_name",
            "adapter",
            "exceptions",
            "modules",
            "graph",
            "flags",
            "execute",
            "run_started_at",
            "invocation_id",
            "dbt_version",
            "thread_id",
            "schema",
            "schemas",
            "database",
            "selected_resources",
            "results",
            "sql",
            "compiled_code",
            "builtins"
        )

        private class FileReferences(val file: PsiFile, val workspace: DbtWorkspace, val r: DbtRoot) {
            private val refs = mutableListOf<DbtNavigationReference>()
            private val extension = file.virtualFile.extension
            private val blocks =
                if (file.name == "dependencies.yml" || extension == "csv" || extension == "py" && r.kind(file.virtualFile) == "model") emptyList() else DbtYamlContexts.blocks(
                    file,
                    r
                )
            private val locals = if (file is YAMLFile) blocks.groupBy {
                DbtYamlContexts.scalar(
                    file,
                    it.range.startOffset
                )
            }.values.flatMap { localReferences(file, it, workspace) } else localReferences(file, blocks, workspace)

            fun collect(): List<DbtNavigationReference> {
                refs += locals
                blocks.flatMap(DbtSyntax::calls)
                    .filter { DbtYamlContexts.allows(file, r, it.range.startOffset, it.name) }.forEach(::call)
                blocks.forEach(::tokens)
                return refs.toList()
            }

            private fun add(
                range: TextRange,
                targets: Iterable<PsiElement>,
                name: String,
                evidence: List<String> = emptyList(),
                missing: String? = null
            ) {
                if (range.startOffset >= 0 && range.endOffset <= file.textLength && range.length > 0) refs += DbtNavigationReference(
                    file,
                    range,
                    targets.toList().distinct(),
                    name,
                    if (name == StaticReference) listOf("Expression: " + range.substring(file.text)) else evidence,
                    missing
                )
            }

            private fun complete(call: DbtSyntax.Call): Boolean =
                call.block.range.substring(file.text).let { (it.endsWith("}}") || it.endsWith("%}")) && call.closed }

            private fun localAt(range: TextRange): Boolean = locals.any { it.rangeInElement.intersects(range) }
            private fun lookup(kinds: Set<String>, name: String): List<PsiElement> =
                workspace.declarations(kinds, name, r).map { it.element }

            private fun builtin(name: String, page: String): List<PsiElement> =
                DbtResolution.builtin(workspace, r, file, name, page)

            private fun first(call: DbtSyntax.Call, f: (String, TextRange) -> Unit) {
                call.literal(0)?.let { name -> call.argumentRange(0)?.let { f(name, it) } }
            }

            private fun nameArgument(call: DbtSyntax.Call): TextRange? =
                call.argumentRange(if (call.literal(1) != null) 1 else 0)

            private fun static(
                call: DbtSyntax.Call,
                constants: Map<String, List<String>>,
                arguments: List<DbtSyntax.Argument>
            ) {
                if (arguments.isEmpty()) return
                val targets = DbtStaticCalls.targets(workspace, r, call, constants)
                val missing = if (complete(call)) DbtStaticCalls.missing(workspace, r, call, constants) else null
                arguments.mapNotNull { it.range }.forEach { range ->
                    add(
                        range,
                        targets,
                        StaticReference,
                        missing = missing?.takeIf { it.first.range == range }?.second
                    )
                }
            }

            private fun dynamic(call: DbtSyntax.Call): List<DbtSyntax.Argument> =
                if (call.positional.any { it.literal == null }) call.positional else emptyList()

            private fun call(call: DbtSyntax.Call) {
                if (localAt(call.range)) return
                ProgressManager.checkCanceled()
                val constants = DbtStaticCalls.constants(
                    DbtYamlContexts.hostBlocks(file, blocks, call.range.startOffset),
                    call.block.range.startOffset
                )
                val macros = if (call.name.startsWith("builtins.")) emptyList() else workspace.macros(call, r)
                if (macros.isNotEmpty()) macroCall(call, macros, constants) else {
                    builtinCall(call, constants)
                    val base = call.name.removePrefix("builtins.")
                    if (base in BuiltinFunctions) add(call.range, builtin(base, base.replace('_', '-')), base)
                    else call.callee.first?.takeIf { it in BuiltinNamespaces }
                        ?.let { add(call.range, builtin(call.name, it), call.name) }
                }
            }

            private fun macroCall(call: DbtSyntax.Call, macros: List<DbtSymbol>, constants: Map<String, List<String>>) {
                add(call.range, macros.map { it.element }, call.name)
                macros.forEach { m ->
                    DbtStaticCalls.wrapperTargets(
                        workspace,
                        r,
                        m,
                        call,
                        constants,
                        onMissing = { a, message ->
                            if (complete(call) && macros.size == 1) a.range?.let {
                                add(
                                    it,
                                    emptyList(),
                                    "delegated reference",
                                    missing = message
                                )
                            }
                        }).forEach { (a, targets) ->
                        a.range?.let { x ->
                            add(
                                x,
                                targets,
                                "delegated reference",
                                listOf("Wrapper: ${m.namespace}.${m.name}", "Argument: " + x.substring(file.text))
                            )
                        }
                    }
                }
                call.keywords.forEach { (key, a) ->
                    a.keywordRange?.let {
                        add(
                            it,
                            workspace.symbols.filter { s -> s.kind == "parameter" && s.owner == call.callee.second && s.name == key && macros.any { it.namespace == s.namespace && it.file == s.file } }
                                .map { s -> s.element },
                            key
                        )
                    }
                }
            }

            private fun builtinCall(call: DbtSyntax.Call, constants: Map<String, List<String>>) {
                when (val base = call.name.removePrefix("builtins.")) {
                    "ref", "function" -> {
                        static(call, constants, call.positional.filter { it.literal == null })
                        val targets =
                            if (call.version != null && call.version?.value == null) emptyList() else DbtResolution.versioned(
                                workspace.definitions(call, if (base == "ref") Resources else setOf("function"), r),
                                call.version?.value
                            )
                        val missing =
                            if (base == "ref" && complete(call) && call.positional.all { it.literal != null }) DbtStaticCalls.missing(
                                workspace,
                                r,
                                call,
                                constants
                            )?.second else null
                        nameArgument(call)?.let { add(it, targets.map { s -> s.element }, base, missing = missing) }
                        if (call.literal(1) != null) call.argumentRange(0)?.let {
                            add(
                                it,
                                workspace.roots.filter { p -> call.literal(0) == p.name }
                                    .map { p -> p.config.containingFile },
                                "package"
                            )
                        }
                    }

                    "source" -> {
                        static(call, constants, dynamic(call))
                        first(call) { source, range ->
                            val sources = lookup(setOf("source"), source); add(
                            range,
                            sources,
                            source,
                            missing = if (complete(call) && call.literal(1) != null && sources.isEmpty()) "Unresolved source: '$source'" else null
                        )
                        }
                        call.literal(1)?.let { table ->
                            call.argumentRange(1)?.let { range ->
                                val targets =
                                    call.literal(0)?.let { DbtResolution.sourceTables(workspace, r, it, table) }
                                        .orEmpty().map { it.element }
                                val knownSource =
                                    call.literal(0)?.let { lookup(setOf("source"), it).isNotEmpty() } == true
                                add(
                                    range,
                                    targets,
                                    table,
                                    missing = if (complete(call) && knownSource && targets.isEmpty()) "Unresolved source table: '${
                                        call.literal(0).orEmpty()
                                    }.$table'" else null
                                )
                            }
                        }
                    }

                    "var" -> {
                        val offset = call.range.startOffset
                        call.positional.take(1).filter { it.literal == null }.forEach { a ->
                            a.range?.let {
                                add(
                                    it,
                                    DbtStaticCalls.value(a.tokens, constants)
                                        .flatMap { n -> variableTargets(workspace, file, r, offset, n) },
                                    StaticReference
                                )
                            }
                        }
                        first(call) { name, range ->
                            add(
                                range,
                                variableTargets(workspace, file, r, offset, name),
                                name
                            )
                        }
                    }

                    "Dimension", "TimeDimension", "Entity", "Metric" -> first(call) { name, range ->
                        add(
                            range,
                            DbtResolution.semantic(workspace, r, file, range.startOffset, base, name)
                                .map { it.element },
                            name
                        )
                        if (name.contains("__") && range.substring(file.text) == name) {
                            var start = range.startOffset
                            name.split("__").dropLast(1).forEach { entity ->
                                add(
                                    TextRange(start, start + entity.length),
                                    lookup(setOf("entity"), entity),
                                    entity
                                ); start += entity.length + 2
                            }
                        }
                    }

                    "metric" -> first(call) { name, range -> add(range, lookup(setOf("metric"), name), name) }
                    "doc" -> if (call.argumentRange(0)
                            ?.let { DbtYamlContexts.description(file, r, it.startOffset) } == true
                    ) {
                        static(call, constants, dynamic(call)); nameArgument(call)?.let {
                            add(
                                it,
                                workspace.definitions(call, setOf("doc"), r).map { s -> s.element },
                                "doc"
                            )
                        }
                    }

                    "env_var" -> first(call) { name, range ->
                        add(
                            range,
                            DbtEnvironment.resolve(workspace, r, name).map { it.element },
                            name
                        )
                    }

                    "config" -> call.keywords.forEach { (key, a) ->
                        a.keywordRange?.let { add(it, DbtResolution.configuration(workspace, file, key), key) }
                        a.literal?.let { name ->
                            a.range?.let { range ->
                                when (key) {
                                    "materialized" -> add(
                                        range,
                                        DbtResolution.materializations(workspace, r, name).map { it.element },
                                        name
                                    ); "group" -> add(range, lookup(setOf("group"), name), name); "strategy" -> add(
                                    range,
                                    lookup(setOf("macro"), "snapshot_${name}_strategy"),
                                    name
                                )
                                }
                            }
                        }
                    }

                    "config.get", "config.require", "dbt.config.get" -> first(call) { name, range ->
                        add(
                            range,
                            DbtResolution.configuration(workspace, file, name),
                            name
                        )
                    }

                    "adapter.dispatch" -> first(call) { name, range ->
                        add(
                            range,
                            DbtResolution.dispatch(workspace, r, name, call.literal(1)).map { it.element },
                            name
                        )
                    }

                    "load_result" -> first(call) { name, range ->
                        val statements =
                            blocks.flatMap(DbtSyntax::calls).filter { it.name == "statement" && it.literal(0) == name }
                        add(
                            range,
                            statements.mapNotNull { it.argumentRange(0) }
                                .map { DbtSymbol("result", name, r.name, file, it).element },
                            name
                        )
                    }

                    "info_schema" -> if (DbtInfoSchema.enabled(
                            r,
                            file
                        )
                    ) first(call) { name, range ->
                        if (name in DbtInfoSchema.views) add(
                            range,
                            DbtInfoSchema.view(file, r, name),
                            name
                        )
                    }
                }
            }

            private fun tokens(b: DbtSyntax.Block) {
                b.tokens.windowed(3).forEach { (a, dot, field) ->
                    if (dot.image == "." && !a.string && !field.string && a.image == "target" && !localAt(a.range) && DbtYamlContexts.allows(
                            file,
                            r,
                            a.range.startOffset,
                            "target"
                        )
                    ) add(field.range, r.target(field.image), field.image)
                }
                b.tokens.windowed(2).forEach { (pipe, filter) ->
                    if (pipe.image == "|" && !filter.string && filter.image in BuiltinFilters) add(
                        filter.range,
                        builtin(filter.image, filter.image.replace('_', '-')),
                        filter.image
                    )
                }
                val attributes = b.tokens.windowed(2).filter { it[0].image == "." }.map { it[1].range }.toSet()
                b.tokens.filter { !it.string && it.range !in attributes && it.image in ContextVariables }
                    .filterNot { t -> localAt(t.range) || refs.any { it.rangeInElement.contains(t.range) } }
                    .filter { DbtYamlContexts.allows(file, r, it.range.startOffset, it.image) }
                    .forEach { add(it.range, contextTargets(it), it.image) }
            }

            private fun contextTargets(t: DbtSyntax.Token): List<PsiElement> = when (t.image) {
                "this", "model" -> {
                    val tested = workspace.enclosing("unit_test", file, t.range.startOffset)
                        .mapNotNull { it.mapping?.let { m -> DbtYaml.string(m, "model") } }; workspace.codeSymbols(file)
                        .map { it.element } + tested.flatMap { lookup(setOf("model"), it) }
                }

                "project_name" -> listOfNotNull(DbtYaml.keyValue(r.config, "name"))
                "results", "schemas", "selected_resources" -> builtin(t.image, "on-run-end-context")
                else -> builtin(t.image, t.image.replace('_', '-'))
            }
        }

        private fun variableTargets(
            w: DbtWorkspace,
            file: PsiFile,
            root: DbtRoot,
            offset: Int,
            name: String
        ): List<PsiElement> {
            val overridden = listOfNotNull(DbtResolution.overriddenVariable(w, root, name))
            return if (DbtYamlContexts.profile(file, root) || root.isFile(file, "packages.yml")) overridden
            else if (DbtYamlContexts.projectConfiguration(
                    file,
                    root,
                    offset
                ) && overridden.isEmpty() && (!root.supports(12) || w.rootFile(root, "vars.yml") == null)
            ) emptyList()
            else variableRoots(w, file, root, offset).flatMap { DbtResolution.variable(w, it, name) }.distinct()
        }

        private fun variableRoots(w: DbtWorkspace, file: PsiFile, fallback: DbtRoot, offset: Int): List<DbtRoot> {
            if (fallback.kind(file.virtualFile) != "macro") return listOf(fallback)
            val blocks = DbtSyntax.inFile(file)
            val macros = w.symbols.filter {
                it.kind == "macro" && it.file == file && DbtSyntax.encloses(
                    blocks,
                    it.range.startOffset,
                    "endmacro",
                    TextRange(offset, offset)
                )
            }
            if (macros.isEmpty()) return listOf(fallback)
            val calls = w.files.flatMap { caller ->
                ProgressManager.checkCanceled()
                w.root(caller)?.takeIf { it.resource(caller) }?.let { root ->
                    val bs = DbtYamlContexts.blocks(caller, root);
                    val local = localReferences(caller, bs, w);
                    val declarations = w.symbols.filter { it.kind == "macro" && it.file == caller }
                    bs.flatMap(DbtSyntax::calls).filter { c ->
                        DbtYamlContexts.allows(
                            caller,
                            root,
                            c.range.startOffset,
                            c.name
                        ) && local.none { it.rangeInElement.intersects(c.range) }
                    }.flatMap { call ->
                        val owner =
                            declarations.find { DbtSyntax.encloses(bs, it.range.startOffset, "endmacro", call.range) }
                        w.macros(call, root).map { Triple(it.id, owner?.id, root) }
                    }
                }.orEmpty()
            }
            var frontier = macros.map { it.id }.toSet();
            val seen = mutableSetOf<String>();
            val found = mutableListOf<DbtRoot>();
            var depth = 0
            while (frontier.isNotEmpty() && depth < 32) {
                ProgressManager.checkCanceled();
                val incoming = calls.filter { it.first in frontier }
                incoming.filter { it.second == null }.forEach { found += it.third }
                frontier = incoming.mapNotNull { it.second }.filter(seen::add).toSet(); depth++
            }
            val callers = found.distinct()
            return if (frontier.isNotEmpty()) emptyList() else callers.ifEmpty { listOf(fallback) }
        }

        private fun localReferences(
            file: PsiFile,
            blocks: List<DbtSyntax.Block>,
            workspace: DbtWorkspace
        ): List<DbtNavigationReference> {
            val result = mutableListOf<DbtNavigationReference>();
            var scopes = listOf(emptyMap<String, DbtSymbol>());
            var captures = emptyList<DbtSyntax.Token>()
            fun bind(t: DbtSyntax.Token) {
                if (!t.string && DbtSyntax.isIdentifier(t.image)) {
                    val parameter =
                        workspace.symbols.find { it.file == file && it.kind == "parameter" && it.range == t.range }
                    scopes = listOf(
                        scopes.first() + (t.image to (parameter ?: DbtSymbol(
                            "local",
                            t.image,
                            "jinja",
                            file,
                            t.range
                        )))
                    ) + scopes.drop(1)
                }
            }

            fun reference(range: TextRange, name: String) {
                scopes.firstNotNullOfOrNull { it[name] }
                    ?.let { result += DbtNavigationReference(file, range, listOf(it.element), name) }
            }
            blocks.forEach { b ->
                val ts = b.tokens;
                val tag = b.tagName.orEmpty()
                if (tag in DbtSyntax.ScopeEndTags && scopes.size > 1) scopes = scopes.drop(1)
                if (tag == "endset" && captures.isNotEmpty()) {
                    bind(captures.first()); captures = captures.drop(1)
                }
                val attributeAssignment = tag == "set" && ts.getOrNull(2)?.image == "."
                val setTargets = if (tag == "set" && !attributeAssignment) ts.drop(1)
                    .takeWhile { it.image != "=" && it.image != "|" }
                    .filter { !it.string && DbtSyntax.isIdentifier(it.image) } else emptyList()
                if (tag in DbtSyntax.ScopeTags) scopes = listOf(emptyMap<String, DbtSymbol>()) + scopes
                val withTargets = if (tag == "with") DbtSyntax.assignedNames(ts).map { ts[it] } else emptyList()
                val loopTargets = if (tag == "for") ts.drop(1).takeWhile { it.image != "in" }
                    .filter { it.image != "," } else emptyList()
                val callParameters = if (tag == "call") DbtSyntax.parameters(b) else emptyList();
                val parameters = if (tag in setOf("macro", "test")) DbtSyntax.parameters(b) else emptyList()
                parameters.forEach(::bind)
                val declarationRanges =
                    (parameters + setTargets + withTargets + loopTargets + callParameters).map { it.range }.toSet()
                ts.forEachIndexed { i, t ->
                    val isSetTarget = tag == "set" && i == 1
                    if (!t.string && t.range !in declarationRanges && !isSetTarget && ts.getOrNull(i - 1)?.image != "." && ts.getOrNull(
                            i + 1
                        )?.image != "="
                    ) reference(t.range, t.image)
                }
                loopTargets.forEach(::bind); callParameters.forEach(::bind); withTargets.forEach(::bind)
                if (tag == "set") {
                    val attribute = ts.getOrNull(2)?.image == "." && ts.getOrNull(3) != null
                    if (!attribute && ts.any { it.image == "=" }) setTargets.forEach(::bind) else if (!attribute) setTargets.firstOrNull()
                        ?.let { captures = listOf(it) + captures }
                    if (attribute) {
                        val name = ts[1].image + "." + ts[3].image;
                        val field = DbtSymbol("namespace attribute", name, "jinja", file, ts[3].range)
                        val owner = scopes.indexOfFirst { ts[1].image in it }
                        if (owner >= 0 && scopes.none { name in it }) scopes =
                            scopes.mapIndexed { i, scope -> if (i == owner) scope + (name to field) else scope }
                    }
                    if (ts.getOrNull(3)?.image == "namespace") ts.drop(5).windowed(2).forEach { (field, eq) ->
                        if (eq.image == "=") {
                            val name = ts[1].image + "." + field.image; scopes = listOf(
                                scopes.first() + (name to DbtSymbol(
                                    "namespace attribute",
                                    name,
                                    "jinja",
                                    file,
                                    field.range
                                ))
                            ) + scopes.drop(1)
                        }
                    }
                }
                ts.windowed(3).forEach { (namespace, dot, field) ->
                    if (dot.image == ".") reference(
                        field.range,
                        namespace.image + "." + field.image
                    )
                }
            }
            return result
        }
    }
}

private object DbtYamlContexts {
    private val hooks =
        setOf("pre-hook", "post-hook", "pre_hook", "post_hook", "on-run-start", "on-run-end", "loaded_at_query", "sql")

    fun scalar(file: PsiFile, offset: Int): YAMLScalar? =
        generateSequence(file.findElementAt(offset)) { it.parent }.filterIsInstance<YAMLScalar>().firstOrNull()

    private fun context(file: PsiFile, root: DbtRoot, offset: Int): String {
        if (file !is YAMLFile) return "runtime"
        val keys = scalar(file, offset)?.let(DbtYaml::ancestors).orEmpty().map { it.removePrefix("+") };
        val relative = root.relative(file.virtualFile)
        val unsupported =
            keys.lastOrNull()?.let(DbtDeclarations.ResourceKeys::get)?.let { !root.supportsKind(it) } == true
        return when {
            unsupported || "vars" in keys || relative in setOf(
                "dependencies.yml",
                "package-lock.yml",
                "vars.yml"
            ) -> "none"

            relative == root.profilesFile || relative == "packages.yml" -> "profile"
            relative == "selectors.yml" -> if (keys.firstOrNull() == "default") "project" else "none"
            relative == "dbt_project.yml" -> if (keys.any { it in hooks }) "runtime" else "project"
            keys.lastOrNull() !in DbtDeclarations.ResourceKeys -> "none"
            "meta" in keys -> "properties"
            keys.any { it in DbtSymbol.TestKeys } && "arguments" in keys && !root.supports(10, 5) -> "none"
            "description" in keys && keys.none { it in setOf("meta", "config") } -> "description"
            keys.any { it in hooks || it in DbtSymbol.TestKeys } -> "runtime"
            keys.any {
                it in setOf(
                    "input",
                    "depends_on",
                    "relation",
                    "filter",
                    "where",
                    "group_by",
                    "order_by"
                )
            } || keys.firstOrNull() == "model" && keys.any {
                it in setOf(
                    "semantic_models",
                    "unit_tests"
                )
            } -> "semantic"

            else -> "properties"
        }
    }

    fun blocks(file: PsiFile, root: DbtRoot): List<DbtSyntax.Block> =
        DbtSyntax.inFile(file).filter { context(file, root, it.range.startOffset) != "none" }

    fun hostBlocks(file: PsiFile, blocks: List<DbtSyntax.Block>, offset: Int): List<DbtSyntax.Block> =
        if (file !is YAMLFile) blocks else scalar(
            file,
            offset
        )?.let { s -> blocks.filter { s.textRange.contains(it.range) } }.orEmpty()

    fun allows(file: PsiFile, root: DbtRoot, offset: Int, name: String): Boolean {
        val base = name.removePrefix("builtins.")
        return when (context(file, root, offset)) {
            "runtime" -> base != "doc"
            "description" -> base in setOf("doc", "env_var", "var")
            "semantic" -> base in setOf(
                "ref",
                "source",
                "function",
                "var",
                "env_var",
                "Dimension",
                "TimeDimension",
                "Entity",
                "Metric",
                "metric",
                "this"
            )

            "profile" -> base in setOf("env_var", "var")
            "project" -> base == "env_var" || base == "var" && root.supports(12) || base in setOf(
                "target",
                "dbt_version",
                "builtins"
            )

            "properties" -> base in setOf("env_var", "var", "target", "dbt_version")
            else -> false
        }
    }

    fun description(file: PsiFile, root: DbtRoot, offset: Int): Boolean = context(file, root, offset) == "description"
    fun profile(file: PsiFile, root: DbtRoot): Boolean = root.isFile(file, root.profilesFile)
    fun projectConfiguration(file: PsiFile, root: DbtRoot, offset: Int): Boolean =
        root.isFile(file, DbtWorkspace.ProjectFileName) && context(file, root, offset) == "project"
}
