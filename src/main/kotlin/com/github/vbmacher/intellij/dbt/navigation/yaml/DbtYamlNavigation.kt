package com.github.vbmacher.intellij.dbt.navigation.yaml

import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.yaml.psi.*

/** Resolves authored YAML property, package and configuration references using PSI ranges and the root's version gates. */
class DbtYamlNavigation : DbtReferenceProvider {
    override fun references(context: DbtNavigationContext): List<DbtNavigationReference> {
        val file = context.file;
        val w = context.workspace;
        val r = context.root
        if (file !is YAMLFile) return emptyList()
        val result = mutableListOf<DbtNavigationReference>()
        fun add(e: PsiElement, targets: Iterable<PsiElement>, name: String) {
            result += DbtNavigationReference(file, e.textRange, targets.toList().distinct(), name)
        }

        fun addKey(kv: YAMLKeyValue, targets: Iterable<PsiElement>, name: String) {
            kv.key?.let { add(it, targets, name) }
        }

        fun lookup(kinds: Set<String>, name: String): List<PsiElement> {
            val split = name.split('.', limit = 2); return w.declarations(
                kinds,
                split.last(),
                r,
                if (split.size == 2) split.first() else null
            ).map { it.element }
        }

        fun containingName(e: PsiElement): String? =
            generateSequence(e.parent) { it.parent }.filterIsInstance<YAMLMapping>()
                .firstNotNullOfOrNull { DbtYaml.string(it, "name") }

        fun test(name: String, element: PsiElement) {
            val defs = lookup(setOf("generic_test"), name);
            val builtin = name in setOf("unique", "not_null", "accepted_values", "relationships")
            add(
                element,
                if (defs.isNotEmpty()) defs else if (builtin) listOf(
                    DbtResolution.documentation(
                        file,
                        name,
                        "data-tests",
                        r
                    )
                ) else emptyList(),
                name
            )
        }
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(e: PsiElement) {
                val ancestry = DbtYaml.ancestors(e)
                if ("meta" !in ancestry && (r.supports(8) || "unit_tests" !in ancestry)) when {
                    e is YAMLScalar && "vars" !in ancestry -> {
                        val name = e.textValue
                        fun semantic(kind: String): List<PsiElement> =
                            DbtResolution.semantic(w, r, file, e.textOffset, kind, name).map { it.element }
                        if (!name.contains("{{")) when (val key = DbtYaml.key(e)) {
                            "package", "git", "local" -> if (file.name in setOf(
                                    "packages.yml",
                                    "dependencies.yml",
                                    "package-lock.yml"
                                )
                            ) {
                                val declaration = DbtYaml.parentMapping(e)
                                val projectName = if (key == "local") r.directory.findFileByRelativePath(name)
                                    ?.let { DbtYaml.psiFile(w.project, it.findChild(DbtWorkspace.ProjectFileName)) }
                                    ?.let(DbtYaml::mapping)?.let { DbtYaml.string(it, "name") }
                                else if (key == "git") declaration?.let { DbtYaml.string(it, "subdirectory") }
                                    ?: name.removeSuffix(".git").split('/').last() else name.split('/').last()
                                val installName =
                                    declaration?.let { DbtYaml.string(it, "name") } ?: projectName?.split('/')?.last()
                                val installed =
                                    w.roots.filter { w.packageOwner(it) == r && (projectName == it.name || installName == it.directory.name) }
                                add(e, installed.map { it.config.containingFile }, name)
                            }

                            "version", "revision" -> if (file.name in setOf("packages.yml", "dependencies.yml")) {
                                fun identity(m: YAMLMapping): String? =
                                    DbtYaml.string(m, "package") ?: DbtYaml.string(m, "git")

                                val declared = DbtYaml.parentMapping(e)?.let(::identity)
                                val entries =
                                    r.yaml("package-lock.yml")?.let { DbtYaml.items(it, "packages") }.orEmpty()
                                        .filter { declared != null && identity(it) == declared }
                                add(e, entries.mapNotNull { DbtYaml.keyValue(it, key) }, name)
                            }

                            "name" -> if (file.name == "dependencies.yml" && "projects" in ancestry) add(
                                e,
                                w.roots.filter { it.name == name && w.namespaceVisible(r, it, true) }
                                    .map { it.config.containingFile },
                                name
                            ) else {
                                w.symbols.filter { it.file == file && it.range == e.textRange && it.kind == "export" }
                                    .forEach { add(e, lookup(setOf("saved_query"), it.owner), name) }
                                w.symbols.filter { it.file == file && it.range == e.textRange && it.isProperty }
                                    .forEach { p ->
                                        add(
                                            e,
                                            w.declarations(setOf(p.kind), p.name, r, p.namespace)
                                                .filterNot { it.isProperty }.filter { it.version == p.version }
                                                .map { it.element },
                                            name
                                        )
                                    }
                                val semanticDeclaration = w.symbols.any {
                                    it.kind in setOf(
                                        "metric",
                                        "measure",
                                        "dimension",
                                        "entity"
                                    ) && it.file == file && it.range == e.textRange
                                }
                                if (ancestry.any {
                                        it in setOf(
                                            "measures",
                                            "metrics"
                                        )
                                    } && !semanticDeclaration && ("input_metrics" !in ancestry || r.supports(12))) add(
                                    e,
                                    lookup(setOf(if (ancestry.any {
                                            it in setOf(
                                                "measures",
                                                "base_measure",
                                                "conversion_measure"
                                            )
                                        }) "measure" else "metric"), name),
                                    name
                                )
                            }

                            "group", "+group" -> add(e, lookup(setOf("group"), name), name)
                            "model" -> if ("unit_tests" in ancestry) add(e, lookup(setOf("model"), name), name)
                            "fixture" -> {
                                val extensions = when (DbtYaml.parentMapping(e)?.let { DbtYaml.string(it, "format") }) {
                                    "csv" -> listOf("csv"); "sql" -> listOf("sql"); null -> listOf(
                                        "csv",
                                        "sql"
                                    ); else -> emptyList()
                                }
                                val targets = r.pathKinds.filter { it.first == "test" }
                                    .flatMap { (_, path) -> extensions.mapNotNull { r.psiFile("$path/fixtures/$name.$it") } }
                                add(
                                    e,
                                    targets.flatMap { f ->
                                        w.symbols.filter { it.kind == "fixture" && it.file == f }.map { it.element }
                                    },
                                    name
                                )
                            }

                            "materialized", "+materialized" -> add(
                                e,
                                DbtResolution.materializations(w, r, name).map { it.element },
                                name
                            )

                            "strategy" -> add(e, lookup(setOf("macro"), "snapshot_${name}_strategy"), name)
                            "standard_granularity_column" -> {
                                val owners =
                                    listOfNotNull(containingName(e)) + if (file.name == "dbt_project.yml") ancestry.filter {
                                        w.declarations(
                                            setOf("model"),
                                            it,
                                            r
                                        ).isNotEmpty()
                                    } else emptyList()
                                add(
                                    e,
                                    w.symbols.filter { it.kind == "column" && it.name == name && w.owner(it) == r && it.owner in owners }
                                        .map { it.element },
                                    name
                                )
                            }

                            "expr" -> if ("metrics" in ancestry) {
                                val metrics = w.enclosing("metric", file, e.textOffset)
                                val aliases =
                                    w.symbols.filter { it.kind == "metric_alias" && w.owner(it) == r && metrics.any { m -> m.name == it.owner } }
                                DbtYaml.decoded(e)?.let { (text, offset) -> DbtSyntax.tokens(text, offset) }.orEmpty()
                                    .forEach { token ->
                                        val defs =
                                            if (token.string) emptyList() else aliases.filter { it.name == token.image } + w.symbols.filter { s ->
                                                s.kind == "column" && s.name == token.image && w.owner(s) == r && metrics.any { it.owner.isNotEmpty() && it.owner == s.owner }
                                            }
                                        if (defs.isNotEmpty()) result += DbtNavigationReference(
                                            file,
                                            token.range,
                                            defs.map { it.element },
                                            token.image
                                        )
                                    }
                            } else if (DbtSyntax.isIdentifier(name)) {
                                val semantic = w.enclosing("semantic_model", file, e.textOffset)
                                val modelNames = semantic.flatMap { s ->
                                    if ("embedded" in s.attributes) listOf(s.owner) else s.mapping?.let {
                                        DbtYaml.string(
                                            it,
                                            "model"
                                        )
                                    }?.let { t ->
                                        DbtSyntax.blocks("{{$t}}").flatMap(DbtSyntax::calls)
                                            .flatMap { it.arguments.mapNotNull { a -> a.literal } }
                                    }.orEmpty()
                                }
                                add(
                                    e,
                                    w.symbols.filter { it.kind == "column" && it.name == name && it.namespace == r.name && it.owner in modelNames }
                                        .map { it.element },
                                    name
                                )
                            }

                            "defined_in" -> add(
                                e,
                                w.symbols.filter { it.isCode && it.namespace == r.name && w.root(it.file) == r && it.file.virtualFile.nameWithoutExtension == name }
                                    .map { it.element },
                                name
                            )

                            "primary_entity" -> add(e, semantic("entity"), name)
                            "agg_time_dimension" -> add(e, semantic("dimension"), name)
                            "measure", "base_measure", "conversion_measure" -> add(e, semantic("measure"), name)
                            "metric", "numerator", "denominator" -> add(e, lookup(setOf("metric"), name), name)
                            "input_metric", "base_metric", "conversion_metric" -> if (r.supports(12)) add(
                                e,
                                lookup(setOf("metric"), name),
                                name
                            )

                            "entity" -> if (r.supports(6) && "metrics" in ancestry) add(e, semantic("entity"), name)
                            "profile" -> if (file.name == "dbt_project.yml") add(e, listOfNotNull(r.profile), name)
                            "target" -> if (file.name == "profiles.yml") add(
                                e,
                                listOfNotNull(DbtYaml.parentMapping(e)?.let { DbtYaml.map(it, "outputs") }
                                    ?.let { DbtYaml.keyValue(it, name) }),
                                name
                            )

                            "field" -> if (ancestry.any { it in DbtSymbol.TestKeys }) {
                                val to = DbtYaml.parentMapping(e)?.let { DbtYaml.value(it, "to") } as? YAMLScalar
                                val refs = to?.let { t ->
                                    DbtSyntax.blocks(if (t.textValue.contains("{{")) t.textValue else "{{${t.textValue}}}")
                                        .flatMap(DbtSyntax::calls)
                                }.orEmpty()
                                    .filter { it.name in setOf("ref", "source", "builtins.ref", "builtins.source") }
                                val targets = refs.flatMap { DbtStaticCalls.targets(w, r, it, emptyMap()) }
                                    .filterIsInstance<DbtSymbolElement>().map { it.symbol }
                                val columns = if (targets.size != 1) emptyList() else w.symbols.filter { s ->
                                    s.kind == "column" && s.name == name && targets.any { t ->
                                        s.namespace == t.namespace && w.root(s.file) == w.root(t.file) && s.version == t.version && s.owner == t.relationName
                                    }
                                }
                                if ("arguments" !in ancestry || r.supports(10, 5)) add(
                                    e,
                                    columns.map { it.element },
                                    name
                                )
                            }

                            else -> if (ancestry.any {
                                    it in setOf(
                                        "metrics",
                                        "group_by",
                                        "dimensions",
                                        "entities"
                                    )
                                } && e.parent is YAMLSequenceItem) add(
                                e,
                                lookup(
                                    setOf(if ("metrics" in ancestry) "metric" else if ("entities" in ancestry) "entity" else "dimension"),
                                    name
                                ),
                                name
                            )
                        }
                        if (ancestry.any { it in DbtSymbol.TestKeys } && e.parent is YAMLSequenceItem) test(name, e)
                    }

                    e is YAMLKeyValue && file.name == "dbt_project.yml" && ancestry.lastOrNull() in DbtConfiguration.sections -> {
                        val key = e.keyText;
                        val kind = ancestry.lastOrNull()?.let(DbtConfiguration.sections::get)
                        val names = ancestry.dropLast(1).filterNot { it == r.name || it.startsWith('+') }
                        val targets = w.symbols.filter { s ->
                            kind == s.kind && w.owner(s) == r && (names.isEmpty() || s.name in names || r.relative(s.file.virtualFile)
                                ?.let { p -> names.all { it in p.split('/') } } == true)
                        }
                        val locations = if (key.startsWith('+')) targets.filter { it.isCode }
                            .flatMap { DbtConfiguration.locations(w, it.file, key.drop(1)) }
                            .filterNot { it == e } else DbtConfiguration.pathTargets(w, r, e)
                        addKey(e, locations, key)
                    }

                    e is YAMLKeyValue && "config" in ancestry -> {
                        val owners =
                            w.symbols.filter { it.file == file && it.isProperty && it.mapping?.textRange?.contains(e.textRange) == true }
                        val code = owners.flatMap { w.declarations(setOf(it.kind), it.name, r, it.namespace) }
                            .filter { it.isCode }
                        addKey(
                            e,
                            code.flatMap { DbtConfiguration.locations(w, it.file, e.keyText) }.filterNot { it == e },
                            e.keyText
                        )
                    }

                    e is YAMLKeyValue && ancestry.any { it in DbtSymbol.TestKeys } -> {
                        if (e.parent is YAMLMapping && e.parent.parent is YAMLSequenceItem) e.key?.let {
                            test(
                                e.keyText,
                                it
                            )
                        }
                        else if (("arguments" !in ancestry || r.supports(10, 5)) && e.keyText !in setOf(
                                "arguments",
                                "config",
                                "name",
                                "alias",
                                "where",
                                "severity",
                                "to",
                                "field",
                                "values"
                            )
                        ) {
                            val testName =
                                ancestry.find { n -> w.symbols.any { it.kind == "generic_test" && (it.name == n || it.namespace + "." + it.name == n) } }
                            testName?.let { n ->
                                addKey(
                                    e,
                                    w.symbols.filter { s ->
                                        s.kind == "parameter" && s.owner == n.split('.')
                                            .last() && s.name == e.keyText && lookup(
                                            setOf("generic_test"),
                                            n
                                        ).any { it.containingFile == s.file }
                                    }.map { it.element },
                                    e.keyText
                                )
                            }
                        }
                    }

                    e is YAMLKeyValue && "overrides" in ancestry && "macros" in ancestry -> addKey(
                        e,
                        lookup(setOf("macro"), e.keyText),
                        e.keyText
                    )

                    e is YAMLKeyValue && "overrides" in ancestry && "vars" in ancestry -> addKey(
                        e,
                        DbtResolution.variable(w, r, e.keyText),
                        e.keyText
                    )
                }
                super.visitElement(e)
            }
        })
        return result
    }
}
