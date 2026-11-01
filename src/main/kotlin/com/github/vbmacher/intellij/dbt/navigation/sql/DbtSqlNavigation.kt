package com.github.vbmacher.intellij.dbt.navigation.sql

import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.*
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.parser.*
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.util.TablesNamesFinder

/**
 * SQL adapter preserving host offsets while masking supported Jinja fragments.
 * Analysis is bounded to 1,000,000 characters and a one-second parser timeout; unsupported or incomplete SQL
 * returns an unavailable analysis rather than executing dbt or guessing a complete schema.
 */
class DbtSqlNavigation : DbtReferenceProvider {
    override fun relationRanges(sql: String): List<TextRange> = Companion.relationRanges(sql)
    override fun projectedColumns(sql: String): Pair<List<String>, Boolean>? = Companion.projectedColumns(sql)
    override fun withPrefix(text: String, end: Int): String? = Companion.withPrefix(text, end)
    override fun sqlAnalysis(file: PsiFile, workspace: DbtWorkspace, completionOffset: Int?): Result<DbtSqlAnalysis>? =
        if (file.virtualFile?.extension == "sql") DbtSqlAnalyzer.analyze(file, workspace, completionOffset) else null

    override fun compiledCtes(sql: String): Result<DbtSqlAnalysis>? = Companion.compiledCtes(sql)
    override fun sqlSuppressionLayout(file: PsiFile): DbtSqlSuppressionLayout? =
        if (file.virtualFile?.extension == "sql") suppressionLayout(file) else null

    override fun references(context: DbtNavigationContext): List<DbtNavigationReference> =
        references(context.file, context.workspace, context.root, context.found)

    companion object {
        private val ProjectionCache = Key.create<CachedValue<List<Pair<String, TextRange>>>>("dbt.sql.projections")
        private val ControlTags = setOf("for", "if", "macro", "test", "snapshot", "materialization")
        private val InfoSchemaKinds = mapOf(
            "models" to "model",
            "seeds" to "seed",
            "snapshots" to "snapshot",
            "functions" to "function",
            "analyses" to "analysis",
            "checks" to "check"
        )
        private val Directive =
            Regex("(?s)--\\s*(file:\\s*)?noinspection\\s+([A-Za-z_0-9.-]+(?:\\s*,\\s*[A-Za-z_0-9.-]+)*)(?:\\s.*)?")

        private fun sqlFile(file: PsiFile): Boolean = file.virtualFile.extension == "sql" && file.textLength <= 1000000
        fun <A> all(xs: Collection<A>?): List<A> = xs?.toList().orEmpty()
        fun offset(text: String, t: Token, end: Boolean = false): Int {
            val line = if (end) t.endLine else t.beginLine;
            val column = if (end) t.endColumn else t.beginColumn
            var lineStart = 0; repeat((line - 1).coerceAtLeast(0)) { lineStart = text.indexOf('\n', lineStart) + 1 }
            return minOf(text.length, lineStart + column - if (end) 0 else 1)
        }

        fun range(text: String, t: Token): TextRange = TextRange(offset(text, t), offset(text, t, true))
        fun parse(sql: String): Statement? = parseResult(sql).getOrNull()
        /** Requires a SELECT with top-level CTEs; recursive, modifying and explicit-column-list CTEs return failures. */
        fun compiledCtes(sql: String): Result<DbtSqlAnalysis> {
            fun fail(message: String): Result<DbtSqlAnalysis> = Result.failure(IllegalArgumentException(message))
            if (sql.length > 1000000) return fail("Compiled SQL exceeds 1,000,000 characters.")
            val parsed = parseResult(sql); if (parsed.isFailure) return Result.failure(parsed.exceptionOrNull()!!)
            val select = parsed.getOrThrow() as? Select
                ?: return fail("Compiled CTE inspection requires one supported SELECT query.")
            if (projectedColumns(sql) == null) return fail("Compiled CTE inspection requires one supported SELECT query.")
            val items = all(select.withItemsList);
            val names = items.map { it.unquotedAliasName }
            if (items.any { it.isRecursive }) return fail("Recursive CTE inspection is unsupported.")
            if (items.any { it.parenthesedStatement !is ParenthesedSelect }) return fail("Data-modifying CTEs cannot be inspected.")
            if (items.any { all(it.withItemList).isNotEmpty() }) return fail("Explicit CTE column lists cannot be inspected.")
            val frames = items.mapIndexed { index, item ->
                val body = item.parenthesedStatement as ParenthesedSelect;
                val bodyTokens = nodeTokens(body);
                val innerTokens = nodeTokens(body.select)
                val before = offset(sql, bodyTokens.first());
                val name =
                    tokens(sql).filter { offset(sql, it) < before }.last { it.image.equals(item.aliasName, true) }
                val referenced = TablesNamesFinder<Void>().getTables(body.select as Statement)
                val range = TextRange(offset(sql, name), offset(sql, bodyTokens.last(), true));
                val inner = TextRange(offset(sql, innerTokens.first()), offset(sql, innerTokens.last(), true))
                DbtCte(
                    item.unquotedAliasName,
                    range,
                    inner,
                    names.take(index).filter { n ->
                        referenced.any {
                            it.equals(n, true) || it.equals(
                                "\"" + n.replace(
                                    "\"",
                                    "\"\""
                                ) + "\"", true
                            )
                        }
                    },
                    inner.substring(sql)
                )
            }
            return if (frames.isEmpty()) fail("The compiled model has no top-level CTEs to inspect.") else Result.success(
                DbtSqlAnalysis(emptyList(), emptyList(), frames, emptyList(), emptyList())
            )
        }

        fun parseResult(sql: String): Result<Statement> = try {
            Result.success(CCJSqlParserUtil.parse(sql) { p ->
                // Retain offsets while skipping JSqlParser's blank-line statement separators.
                val manager = object : CCJSqlParserTokenManager(SimpleCharStream(StringProvider(sql))) {
                    override fun getNextToken(): Token {
                        var token = super.getNextToken()
                        while (token.kind == CCJSqlParserConstants.ST_SEMICOLON && token.image.all { it.isWhitespace() }) token =
                            super.getNextToken()
                        return token
                    }
                }
                manager.configuration = p.token_source.configuration; p.token_source = manager; p.withTimeOut(1000)
            })
        } catch (error: JSQLParserException) {
            val causes = generateSequence(error as Throwable) { it.cause }.toList()
            val message =
                if (causes.any { it is java.util.concurrent.TimeoutException }) "SQL analysis exceeded the 1-second parsing limit." else {
                    val position = causes.filterIsInstance<ParseException>()
                        .firstOrNull()?.currentToken?.next?.takeIf { it.beginLine > 0 && it.beginColumn > 0 }
                        ?.let { "\nThe parser stopped at line ${it.beginLine}, column ${it.beginColumn}." }.orEmpty()
                    "The plugin could not parse this model's SQL.$position\nThe SQL may be incomplete or use syntax the plugin does not support."
                }
            Result.failure(IllegalArgumentException(message))
        }

        fun itemName(item: SelectItem<*>): String? =
            item.alias?.unquotedName ?: (item.expression as? Column)?.unquotedColumnName

        fun aliasToken(node: ASTNodeAccess, alias: String): Token? =
            nodeTokens(node).asReversed().find { it.image.replace("\"", "").equals(alias, true) }

        private fun blank(chars: CharArray, range: TextRange) {
            for (i in range.startOffset until range.endOffset) if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' '
        }

        fun marker(index: Int): String = "_dbt$index"
        fun masked(text: String, blocks: List<DbtSyntax.Block>): String? {
            if (blocks.any { it.tagName in ControlTags }) return null
            val chars = text.toCharArray()
            blocks.forEachIndexed { i, b ->
                val start = b.range.startOffset; blank(chars, b.range)
                val configuration = DbtSyntax.calls(b).any {
                    it.name in setOf(
                        "config",
                        "builtins.config"
                    ) && it.closed && b.tokens.firstOrNull()?.range?.startOffset == it.range.startOffset && b.tokens.lastOrNull()?.range?.endOffset == it.expressionRange.endOffset
                }
                if (!b.tag && !configuration) marker(i).forEachIndexed { n, c ->
                    if (start + n < b.range.endOffset) chars[start + n] = c
                }
            }
            return String(chars)
        }

        fun withPrefix(text: String, end: Int): String? {
            if (end < 0 || end > text.length || end > 1000000) return null
            val before = text.substring(0, end);
            val chars = before.toCharArray()
            DbtSyntax.blocks(text).filter { it.range.endOffset <= end }.forEach { blank(chars, it.range) }
            return tokens(String(chars)).lastOrNull()?.takeIf { it.image.equals("with", true) }
                ?.let { text.substring(0, offset(before, it)) }
        }

        fun relationRanges(sql: String): List<TextRange> {
            fun parsed(query: String): List<TextRange>? {
                val result = mutableListOf<TextRange>()
                val visitor = object : TablesNamesFinder<Void>() {
                    override fun <S> visit(table: Table, context: S): Void? {
                        ProgressManager.checkCanceled()
                        table.astNode?.let { node ->
                            val first = node.jjtGetFirstToken();
                            var last = first;
                            var remaining = table.nameParts.size * 2 - 2
                            while (remaining > 0 && last.next != null) {
                                last = last.next; remaining--
                            }
                            result += TextRange(offset(query, first), offset(query, last, true))
                        }
                        return super.visit(table, context)
                    }
                }
                return try {
                    CCJSqlParserUtil.parseStatements(query) { it.withTimeOut(1000) }
                        .forEach { visitor.getTables(it) }; result.distinct()
                } catch (_: JSQLParserException) {
                    null
                }
            }
            return listOf(
                "",
                "select ",
                "select * "
            ).firstNotNullOfOrNull { prefix ->
                parsed(prefix + sql)?.filter { it.startOffset >= prefix.length }?.map { it.shiftRight(-prefix.length) }
            }.orEmpty()
        }

        fun suppressionLayout(file: PsiFile): DbtSqlSuppressionLayout {
            val text = file.text;
            val blocks = DbtSyntax.inFile(file);
            val chars = text.toCharArray()
            (blocks.map { it.range } + Regex("(?s)\\{#.*?#\\}").findAll(text)
                .map { TextRange(it.range.first, it.range.last + 1) }).forEach { blank(chars, it) }
            val stream = SimpleCharStream(StringProvider(String(chars))); stream.setTabSize(1);
            val lexer = CCJSqlParserTokenManager(stream)
            val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
            fun span(token: Token): TextRange = TextRange(
                minOf(text.length, (starts.getOrNull(token.beginLine - 1) ?: 0) + token.beginColumn - 1),
                minOf(text.length, (starts.getOrNull(token.endLine - 1) ?: 0) + token.endColumn)
            )

            val ts = mutableListOf<Pair<String, TextRange>>();
            val comments = mutableListOf<DbtSqlSuppressionComment>()
            try {
                var done = false
                while (!done) {
                    ProgressManager.checkCanceled();
                    val token = lexer.nextToken;
                    var comment = token.specialToken
                    while (comment != null) {
                        Directive.matchEntire(comment.image.trim())?.let {
                            comments += DbtSqlSuppressionComment(
                                span(comment!!),
                                it.groupValues[2],
                                it.groups[1] != null
                            )
                        }; comment = comment.specialToken
                    }
                    done = token.kind == 0; if (!done) ts += token.image to span(token)
                }
            } catch (_: TokenMgrException) { /* Keep boundaries preceding unfinished SQL. */
            }
            val ends = ts.filter { it.first == ";" }.map { it.second.endOffset } + text.length
            val statements = (listOf(0) + ends).windowed(2).filter { (start, end) -> end > start }.map { (start, end) ->
                val first = ts.map { it.second.startOffset }.find { it >= start && it < end };
                val template = blocks.map { it.range.startOffset }.find { it >= start && it < end }
                DbtSqlSuppressionStatement(TextRange(start, end), listOfNotNull(first, template).minOrNull() ?: end)
            }
            return DbtSqlSuppressionLayout(statements, comments)
        }

        private class ProjectionSafety {
            fun safeFrom(item: FromItem?): Boolean = when (item) {
                is ParenthesedSelect -> safe(item.select); is ParenthesedFromItem -> safeFrom(item.fromItem) && all(item.joins).all {
                    safeFrom(
                        it.fromItem
                    )
                }; else -> true
            }

            fun safe(s: Select): Boolean =
                all(s.withItemsList).all { (it.parenthesedStatement as? ParenthesedSelect)?.let { p -> safe(p.select) } == true } && when (s) {
                    is ParenthesedSelect -> safe(s.select)
                    is SetOperationList -> all(s.selects).all(::safe)
                    is PlainSelect -> s.intoTables.isNullOrEmpty() && s.intoTempTable == null && s.mySqlSelectIntoClause == null && safeFrom(
                        s.fromItem
                    ) && all(s.joins).all { safeFrom(it.fromItem) }

                    else -> false
                }
        }

        private fun selectedColumns(s: Select): Pair<List<String>, Boolean> = when (s) {
            is ParenthesedSelect -> selectedColumns(s.select)
            is SetOperationList -> all(s.selects).firstOrNull()?.let(::selectedColumns)
                ?: (emptyList<String>() to false)

            is PlainSelect -> {
                val items = all(s.selectItems);
                val names = items.mapNotNull(::itemName)
                    .filterNot { it.startsWith("_dbt") }; names to (names.size == items.size)
            }

            else -> emptyList<String>() to false
        }

        fun projectedColumns(sql: String): Pair<List<String>, Boolean>? {
            if (sql.length > 1000000) return null
            val query = masked(sql, DbtSyntax.blocks(sql)) ?: return null
            val single = runCatching {
                val parsed = tokens(query); parsed.withIndex().none { (i, t) -> t.image == ";" && i != parsed.size - 1 }
            }.getOrDefault(false)
            if (!single) return null
            val select = parse(query) as? Select ?: return null
            return if (ProjectionSafety().safe(select)) selectedColumns(select) else null
        }

        fun projections(resource: DbtSymbol): List<DbtSymbol> {
            val file = resource.file; if (!sqlFile(file)) return emptyList()
            val ranges = CachedValuesManager.getManager(file.project).getCachedValue(
                file,
                ProjectionCache,
                { CachedValueProvider.Result.create(projectionRanges(file), file) },
                false
            )
            return ranges.map { (name, range) ->
                DbtSymbol(
                    "column",
                    name,
                    resource.namespace,
                    file,
                    range,
                    resource.name,
                    resource.version,
                    mapOf("projection" to "true")
                )
            }
        }

        private fun projectionRanges(file: PsiFile): List<Pair<String, TextRange>> {
            val source = file.text;
            val select = masked(source, DbtSyntax.blocks(source))?.let(::parse) as? PlainSelect ?: return emptyList()
            return all(select.selectItems).mapNotNull { item ->
                val name = itemName(item)?.takeUnless { it.startsWith("_dbt") };
                val t = item.astNode?.jjtGetLastToken(); if (name != null && t != null) name to range(
                source,
                t
            ) else null
            }
        }

        private data class Relation(
            val target: List<PsiElement>,
            val columns: Map<String, List<PsiElement>>,
            val infoView: String? = null
        )

        fun references(
            file: PsiFile,
            w: DbtWorkspace,
            root: DbtRoot,
            jinja: List<DbtNavigationReference>
        ): List<DbtNavigationReference> {
            if (!sqlFile(file)) return emptyList()
            val text = file.text;
            val blocks = DbtSyntax.blocks(text);
            val sql = masked(text, blocks) ?: return emptyList();
            val parsed = parse(sql) ?: return emptyList()
            val templateRelations = blocks.withIndex().filter { !it.value.tag }
                .associate { (i, b) -> marker(i) to templateRelation(file, w, root, b, jinja) }
            val refs = mutableListOf<DbtNavigationReference>();
            val allTokens = tokens(sql)
            fun declaration(name: String, near: Int, before: Boolean): Token? {
                val matches = allTokens.filter {
                    it.image.replace("\"", "").replace("`", "").equals(name, true)
                }; return if (before) matches.lastOrNull { offset(text, it) < near } else matches.find {
                    offset(
                        text,
                        it
                    ) >= near
                }
            }

            fun symbol(kind: String, name: String, t: Token): PsiElement =
                DbtSymbol(kind, name, root.name, file, range(text, t)).element

            fun add(t: Token, targets: List<PsiElement>) {
                val rr = range(
                    text,
                    t
                ); if (blocks.none { it.range.intersects(rr) } && targets.isNotEmpty()) refs += DbtNavigationReference(
                    file,
                    rr,
                    targets.distinct(),
                    t.image
                )
            }

            fun select(s: Select, inherited: Map<String, Relation>): Map<String, List<PsiElement>> {
                ProgressManager.checkCanceled();
                var ctes = inherited
                all(s.withItemsList).forEach { item ->
                    (item.parenthesedStatement as? ParenthesedSelect)?.let { body ->
                        val explicitColumns = all(item.withItemList)
                        val first = (explicitColumns.firstOrNull()?.let { nodeTokens(it).firstOrNull() } ?: nodeTokens(
                            body
                        ).firstOrNull())?.let { offset(text, it) } ?: 0
                        val target = listOfNotNull(declaration(item.unquotedAliasName, first, true)?.let {
                            symbol(
                                "cte",
                                item.unquotedAliasName,
                                it
                            )
                        })
                        val columns = select(body.select, ctes); ctes =
                        ctes + (item.unquotedAliasName.lowercase() to Relation(
                            target,
                            if (explicitColumns.isNotEmpty()) emptyMap() else columns
                        ))
                    }
                }
                return when (s) {
                    is ParenthesedSelect -> select(s.select, ctes)
                    is SetOperationList -> all(s.selects).firstOrNull()?.let { select(it, ctes) }.orEmpty()
                    is PlainSelect -> {
                        var relations = emptyMap<String, Relation>()
                        fun from(f: FromItem?) {
                            if (f == null) return
                            val relation = when (f) {
                                is Table -> {
                                    val base =
                                        templateRelations[f.name] ?: ctes[f.unquotedName.lowercase()] ?: Relation(
                                            emptyList(),
                                            emptyMap()
                                        ); nodeTokens(f).firstOrNull()?.let { add(it, base.target) }; base
                                }; is ParenthesedSelect -> Relation(
                                    emptyList(),
                                    select(f.select, ctes)
                                ); else -> Relation(emptyList(), emptyMap())
                            }
                            val alias = f.alias;
                            val key = alias?.unquotedName ?: (f as? Table)?.unquotedName
                            val aliasTarget = if (alias != null && f is ASTNodeAccess) listOfNotNull(
                                aliasToken(
                                    f,
                                    alias.unquotedName
                                )?.let { symbol("sql_alias", alias.unquotedName, it) }) else emptyList()
                            if (key != null) relations =
                                relations + (key.lowercase() to relation.copy(target = aliasTarget.ifEmpty { relation.target }))
                        }
                        from(s.fromItem); all(s.joins).forEach { from(it.fromItem) };
                        val projections = mutableMapOf<String, List<PsiElement>>()
                        fun columnTargets(c: Column): List<PsiElement> {
                            val name = c.unquotedColumnName.lowercase();
                            val table = c.table?.takeIf { it.name != null }
                            return if (table != null) relations[table.unquotedName.lowercase()]?.columns?.get(name)
                                .orEmpty() else {
                                val candidates =
                                    relations.values.mapNotNull { it.columns[name] }; if (candidates.size == 1) candidates.first() else emptyList()
                            }
                        }

                        fun expression(e: Expression?, aliases: Boolean = false) {
                            e?.accept(object : ExpressionVisitorAdapter<Void>() {
                                override fun <S> visit(equals: EqualsTo, context: S): Void? {
                                    val column = equals.leftExpression as? Column;
                                    val value = equals.rightExpression as? StringValue
                                    if (column?.unquotedColumnName == "name" && value != null) {
                                        val relation = column.table?.takeIf { it.name != null }
                                            ?.let { relations[it.unquotedName.lowercase()] }
                                            ?: if (relations.size == 1) relations.values.firstOrNull() else null
                                        val kind = relation?.infoView?.let(InfoSchemaKinds::get);
                                        val targets = kind?.let {
                                            w.declarations(setOf(it), value.value, root).filterNot { it.isProperty }
                                                .map { it.element }
                                        }.orEmpty()
                                        nodeTokens(value).lastOrNull()?.let { t ->
                                            if (targets.isNotEmpty()) {
                                                val rr = range(text, t); refs += DbtNavigationReference(
                                                    file,
                                                    TextRange(rr.startOffset + 1, rr.endOffset - 1),
                                                    targets,
                                                    value.value
                                                )
                                            }
                                        }
                                    }
                                    return super.visit(equals, context)
                                }

                                override fun <S> visit(c: Column, context: S): Void? {
                                    val ts = nodeTokens(c);
                                    val targets =
                                        if (aliases && c.table?.name == null) projections[c.unquotedColumnName.lowercase()]
                                            ?: columnTargets(c) else columnTargets(c)
                                    ts.lastOrNull()?.let { add(it, targets) }
                                    if (ts.size >= 3) ts.firstOrNull()
                                        ?.let { add(it, relations[c.unquotedTableName.lowercase()]?.target.orEmpty()) }
                                    return null
                                }
                            }, null)
                        }
                        all(s.selectItems).forEach { item ->
                            expression(item.expression as Expression)
                            val target = item.alias?.let { a ->
                                listOfNotNull(
                                    aliasToken(
                                        item,
                                        a.unquotedName
                                    )?.let { symbol("sql_projection", a.unquotedName, it) })
                            } ?: (item.expression as? Column)?.let(::columnTargets).orEmpty()
                            itemName(item)?.let { projections[it.lowercase()] = target }
                        }
                        expression(s.where); expression(s.having); all(s.joins).forEach { j ->
                            all(j.onExpressions).forEach {
                                expression(
                                    it
                                )
                            }
                        }; all(s.orderByElements).forEach { expression(it.expression, true) }
                        projections.toMap()
                    }

                    else -> emptyMap()
                }
            }
            if (parsed is Select) select(parsed, emptyMap())
            return refs
        }

        private fun templateRelation(
            file: PsiFile,
            w: DbtWorkspace,
            root: DbtRoot,
            b: DbtSyntax.Block,
            jinja: List<DbtNavigationReference>
        ): Relation {
            val info = if (DbtInfoSchema.enabled(root, file)) DbtSyntax.calls(b)
                .find { it.name == "info_schema" }?.arguments?.firstOrNull()?.literal?.takeIf { it in DbtInfoSchema.views } else null
            if (info != null) return Relation(
                listOf(DbtResolution.documentation(file, info, "info-schema", root)),
                DbtInfoSchema.columns(file, root, info),
                info
            )
            val candidates = jinja.filter { b.range.contains(it.rangeInElement) }.flatMap { it.candidates }
                .filterIsInstance<DbtSymbolElement>().filter { it.symbol.kind in DbtSymbol.RelationKinds }
                .map { it.symbol }.distinct()
            val columns = candidates.flatMap(w::columns).groupBy { it.name.lowercase() }
                .mapValues { it.value.map { s -> s.element as PsiElement } }
            return Relation(candidates.map { it.element }, columns)
        }

        fun nodeTokens(node: ASTNodeAccess): List<Token> {
            val n = node.astNode ?: return emptyList();
            val last = n.jjtGetLastToken();
            val result = mutableListOf<Token>();
            var t: Token? = n.jjtGetFirstToken()
            while (t != null && result.lastOrNull() != last) {
                result += t; t = t.next
            }; return result
        }

        fun tokens(sql: String): List<Token> {
            val manager =
                CCJSqlParserTokenManager(SimpleCharStream(StringProvider(sql))); return generateSequence { manager.nextToken }.takeWhile { it.kind != 0 }
                .toList()
        }
    }
}

class DbtFunctionParameters : DbtReferenceProvider {
    override fun references(context: DbtNavigationContext): List<DbtNavigationReference> {
        val file = context.file;
        val w = context.workspace
        if (file.virtualFile.extension != "sql" || file.text.contains("{{")) return emptyList()
        val functions =
            w.codeSymbols(file).filter { it.kind == "function" }; if (functions.isEmpty()) return emptyList()
        val params = functions.flatMap { f ->
            w.symbols.filter {
                it.kind == "parameter" && it.owner == f.name && it.namespace == f.namespace && w.root(it.file) == w.root(
                    f.file
                ) && it.attributes["overload"] == f.attributes["overload"]
            }
        }
        val text = file.text;
        val expression = try {
            CCJSqlParserUtil.parseExpression(text)
        } catch (_: JSQLParserException) {
            return emptyList()
        };
        val result = mutableListOf<DbtNavigationReference>()
        expression.accept(object : ExpressionVisitorAdapter<Void>() {
            override fun <S> visit(column: Column, context: S): Void? {
                column.astNode?.let { n ->
                    val matches =
                        params.filter { it.name == column.unquotedColumnName }; if (matches.isNotEmpty() && column.table?.name == null) result += DbtNavigationReference(
                    file,
                    DbtSqlNavigation.range(text, n.jjtGetLastToken()),
                    matches.map { it.element },
                    column.unquotedColumnName
                )
                }; return null
            }
        }, null)
        return result
    }
}

/** Contributes authored SQL projection declarations and conservative column provenance; incomplete schemas remain explicitly incomplete. */
class DbtSqlProjections : DbtDeclarationSource {
    override fun declarations(w: DbtWorkspace): List<DbtSymbol> {
        val resources = w.symbols.filter { it.kind == "model" && it.isCode };
        val declared = w.symbols.filter { it.kind == "column" }
            .map { listOf(it.namespace, w.root(it.file), it.owner, it.name, it.version) }.toSet()
        return resources.flatMap(DbtSqlNavigation::projections)
            .filterNot { listOf(it.namespace, w.root(it.file), it.owner, it.name, it.version) in declared }
    }
}

/** Conservative projection analysis; dialect syntax is parsed exclusively by JSqlParser. */
private object DbtSqlAnalyzer {
    private data class Relation(
        val columns: List<DbtSymbol>,
        val known: Boolean,
        val cte: Pair<String, TextRange>? = null
    )

    private const val ModifierProblem =
        "Wildcard EXCEPT, EXCLUDE or REPLACE modifiers cannot be expanded safely. Write the required columns explicitly, or remove the modifier before expanding."

    fun analyze(file: PsiFile, w: DbtWorkspace, completionOffset: Int?): Result<DbtSqlAnalysis> {
        if (file.textLength > 1000000) return Result.failure(IllegalArgumentException("This file exceeds the SQL analysis limit of 1,000,000 characters."))
        val root =
            w.root(file)?.takeIf { it.resource(file) && !it.excluded(file.virtualFile) } ?: return Result.failure(
                IllegalArgumentException("This file is outside the dbt project's supported SQL resource paths or is excluded.")
            )
        val text = file.text;
        val blocks = DbtSyntax.inFile(file)
        val sql = DbtSqlNavigation.masked(text, blocks) ?: return Result.success(
            DbtSqlAnalysis(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf("Dynamic Jinja control flow; column analysis unavailable.")
            )
        )

        fun span(t: Token): TextRange = DbtSqlNavigation.range(text, t)
        val attempt = DbtSqlNavigation.parseResult(sql)
        val parsed = attempt.getOrNull() ?: completionOffset?.takeIf { it > 0 && it <= sql.length }
            ?.let { i -> DbtSqlNavigation.parse(sql.replaceRange(i - 1, i, if (sql[i - 1] == '.') " " else "*")) }
        if (parsed == null) {
            val unsupported = runCatching { DbtSqlNavigation.tokens(sql) }.getOrNull().orEmpty().windowed(2)
                .flatMap { (star, modifier) ->
                    if (star.image != "*" || modifier.image.uppercase(java.util.Locale.ROOT) !in setOf(
                            "EXCEPT",
                            "EXCLUDE",
                            "REPLACE"
                        )
                    ) emptyList() else {
                        ProgressManager.checkCanceled();
                        val end = DbtSqlNavigation.offset(text, star, true)
                        val select = DbtSqlNavigation.parse(sql.substring(0, end)) as? PlainSelect
                        val item = select?.let { DbtSqlNavigation.all(it.selectItems).lastOrNull() }
                        val expression = item?.expression as? AllColumns;
                        val ts = expression?.let(DbtSqlNavigation::nodeTokens).orEmpty()
                        listOfNotNull(
                            ts.firstOrNull()?.takeIf {
                                ts.lastOrNull()?.let { DbtSqlNavigation.offset(text, it, true) == end } == true
                            }?.let {
                                DbtSqlProblem(
                                    TextRange(DbtSqlNavigation.offset(text, it), end),
                                    ModifierProblem
                                )
                            })
                    }
                }
            return if (unsupported.isNotEmpty()) Result.success(
                DbtSqlAnalysis(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf("Wildcard modifier is unsupported by SQL analysis."),
                    wildcardProblems = unsupported
                )
            ) else Result.failure(
                IllegalArgumentException(
                    attempt.exceptionOrNull()?.message ?: "The plugin could not parse this model's SQL."
                )
            )
        }
        val references = DbtNavigation.inFile(file, false);
        val issues = mutableListOf<String>();
        val allTokens = DbtSqlNavigation.tokens(sql)
        if (allTokens.windowed(2)
                .any { (a, b) -> a.image.equals("with", true) && b.image.equals("recursive", true) }
        ) issues += "Recursive CTE analysis is partial; query inspection is unsupported."
        val scopes = mutableListOf<DbtSqlScope>();
        val ctes = mutableListOf<DbtCte>();
        val cteReferences = mutableListOf<Pair<String, TextRange>>()
        val wildcards = mutableListOf<Pair<TextRange, List<String>>>();
        val wildcardProblems = mutableListOf<DbtSqlProblem>();
        val intermediate = mutableListOf<DbtSqlColumn>();
        val problems = mutableListOf<DbtSqlProblem>();
        val deletions = mutableListOf<Pair<TextRange, TextRange>>();
        val declarations = mutableListOf<DbtSymbol>()
        fun range(node: ASTNodeAccess): TextRange {
            val ts = DbtSqlNavigation.nodeTokens(node); return if (ts.isEmpty()) TextRange.EMPTY_RANGE else TextRange(
                DbtSqlNavigation.offset(text, ts.first()),
                DbtSqlNavigation.offset(text, ts.last(), true)
            )
        }

        fun nameRange(node: ASTNodeAccess): TextRange =
            DbtSqlNavigation.nodeTokens(node).lastOrNull()?.let(::span) ?: TextRange.EMPTY_RANGE

        fun removable(item: TextRange, allowSoleWith: Boolean): TextRange? {
            val before = allTokens.lastOrNull { DbtSqlNavigation.offset(text, it, true) <= item.startOffset };
            val after = allTokens.find { DbtSqlNavigation.offset(text, it) >= item.endOffset }
            return if (after?.image == ",") TextRange(
                item.startOffset,
                DbtSqlNavigation.offset(text, after, true)
            ) else if (before?.let {
                    it.image == "," || allowSoleWith && it.image.equals(
                        "with",
                        true
                    )
                } == true) TextRange(DbtSqlNavigation.offset(text, before), item.endOffset) else null
        }

        fun external(table: Table): Relation {
            val candidates = blocks.withIndex().find { (i, _) -> table.name == DbtSqlNavigation.marker(i) }
                ?.let { (_, b) ->
                    references.filter { b.range.contains(it.rangeInElement) }.flatMap { it.candidates }
                        .filterIsInstance<DbtSymbolElement>().filter { it.symbol.kind in DbtSymbol.RelationKinds }
                        .map { it.symbol }
                }.orEmpty().distinctBy { it.uniqueId }
            if (candidates.size != 1) return Relation(emptyList(), false)
            val model = candidates.first();
            val declared = w.columns(model)
            if (!model.isCode) return Relation(declared, declared.isNotEmpty() && !model.isStale)
            val analysis = DbtSqlAnalysis.get(w, model.file)
            val projected = analysis?.columns.orEmpty().map { c ->
                val metadata = declared.find { it.name == c.name }?.attributes.orEmpty()
                DbtSymbol(
                    "column",
                    c.name,
                    model.namespace,
                    model.file,
                    c.range,
                    model.relationName,
                    model.version,
                    metadata + (c.dataType?.let { mapOf("data_type" to it) } ?: emptyMap()) + ("projection" to "true"))
            }
            return Relation(
                projected,
                projected.isNotEmpty() && !model.isStale && analysis?.let { it.issues.isEmpty() && it.columns.all { c -> c.complete } } == true)
        }

        fun local(columns: List<DbtSqlColumn>): Relation {
            intermediate += columns; return Relation(columns.map {
                DbtSymbol(
                    "sql_projection",
                    it.name,
                    root.name,
                    file,
                    it.range
                )
            }, columns.isNotEmpty() && columns.all { it.complete })
        }

        fun select(s: Select, inherited: Map<String, Relation>): List<DbtSqlColumn> {
            ProgressManager.checkCanceled();
            var visible = inherited
            DbtSqlNavigation.all(s.withItemsList).forEach { item ->
                if (item.parenthesedStatement !is ParenthesedSelect) issues += "Data-modifying CTEs are unsupported."
                (item.parenthesedStatement as? ParenthesedSelect)?.let { body ->
                    val name = item.unquotedAliasName;
                    val explicitColumns = DbtSqlNavigation.all(item.withItemList);
                    val referencesStart = cteReferences.size
                    val bodyColumns = select(body.select, visible)
                    if (explicitColumns.isNotEmpty()) issues += "Explicit CTE column lists are not supported by column analysis or refactoring."
                    val columns = if (explicitColumns.isNotEmpty()) emptyList() else bodyColumns
                    val bodyRange = range(body.select);
                    val before = explicitColumns.firstOrNull()?.let { range(it).startOffset } ?: range(body).startOffset
                    val nameToken = allTokens.filter { DbtSqlNavigation.offset(text, it) < before }
                        .lastOrNull { it.image.replace("\"", "").equals(name, true) }
                    val itemRange =
                        TextRange(nameToken?.let { DbtSqlNavigation.offset(text, it) } ?: range(body).startOffset,
                            range(body).endOffset)
                    val dependencies = cteReferences.drop(referencesStart)
                        .filterNot { (_, declaration) -> bodyRange.contains(declaration) }.map { it.first }.distinct()
                    visible = visible + (name.lowercase() to local(columns).copy(cte = name to itemRange))
                    ctes += DbtCte(name, itemRange, bodyRange, dependencies, bodyRange.substring(text))
                    nameToken?.let { t ->
                        declarations += DbtSymbol("cte", name, root.name, file, span(t)); removable(
                        itemRange,
                        true
                    )?.let { deletions += span(t) to it }
                    }
                }
            }
            return when (s) {
                is ParenthesedSelect -> select(s.select, visible)
                is SetOperationList -> {
                    val branches = DbtSqlNavigation.all(s.selects).map { select(it, visible) }
                    branches.firstOrNull().orEmpty().mapIndexed { index, column ->
                        val peers = branches.mapNotNull { it.getOrNull(index) }; column.copy(
                        inputs = peers.flatMap { it.inputs }.distinct(),
                        direct = false,
                        complete = peers.size == branches.size && peers.all { it.complete },
                        expression = "set operation"
                    )
                    }
                }

                is PlainSelect -> {
                    val from = listOfNotNull(s.fromItem) + DbtSqlNavigation.all(s.joins).map { it.fromItem }
                    val relations = from.map { f ->
                        val relation = when (f) {
                            is Table -> (visible[f.unquotedName.lowercase()] ?: external(f)).also {
                                it.cte?.let(
                                    cteReferences::add
                                )
                            }; is ParenthesedSelect -> local(select(f.select, visible)); else -> Relation(
                                emptyList(),
                                false
                            )
                        }
                        val alias = f.alias?.unquotedName ?: (f as? Table)?.unquotedName.orEmpty()
                        f.alias?.let { a ->
                            if (f is ASTNodeAccess) DbtSqlNavigation.aliasToken(f, a.unquotedName)?.let { token ->
                                val aliasRange = span(token); declarations += DbtSymbol(
                                "sql_alias",
                                a.unquotedName,
                                root.name,
                                file,
                                aliasRange
                            )
                                val previous = allTokens.lastOrNull {
                                    DbtSqlNavigation.offset(
                                        text,
                                        it,
                                        true
                                    ) <= aliasRange.startOffset
                                }
                                val start = previous?.takeIf { it.image.equals("as", true) }
                                    ?.let { DbtSqlNavigation.offset(text, it) } ?: aliasRange.startOffset
                                deletions += aliasRange to TextRange(start, aliasRange.endOffset)
                            }
                        }
                        alias to relation
                    }
                    val complete = relations.all { it.second.known }; scopes += DbtSqlScope(
                        range(s),
                        relations.map { (a, r) -> a to r.columns },
                        complete
                    )
                    fun resolve(c: Column, report: Boolean = true): List<DbtSymbol> {
                        val qualifier = c.table?.name
                        val candidates = relations.filter { (a, _) -> qualifier == null || qualifier.equals(a, true) }
                            .flatMap { it.second.columns }.filter { it.name.equals(c.unquotedColumnName, true) }
                        if (report && candidates.isEmpty() && complete && !c.unquotedColumnName.startsWith("_dbt")) problems += DbtSqlProblem(
                            nameRange(c),
                            "Unknown column '${c.unquotedColumnName}' in this SQL scope."
                        )
                        return if (candidates.size == 1) candidates else emptyList()
                    }

                    fun quoted(c: DbtSymbol): String {
                        val raw =
                            c.range.substring(c.file.text); return if (raw.startsWith('"') || raw.startsWith('`')) raw else if (DbtSyntax.isIdentifier(
                                c.name
                            )
                        ) c.name else "\"" + c.name.replace("\"", "\"\"") + "\""
                    }

                    fun qualifiedAlias(alias: String): String =
                        from.find { it.alias?.unquotedName?.equals(alias, true) == true }?.alias?.name ?: alias

                    val output = DbtSqlNavigation.all(s.selectItems).flatMap { item ->
                        val expression = item.expression as Expression
                        val isStar = expression is AllColumns
                        if (isStar) {
                            val qualifier = (expression as? AllTableColumns)?.table?.unquotedName
                            val chosen = relations.filter { (a, _) -> qualifier == null || qualifier.equals(a, true) };
                            val columns = chosen.flatMap { it.second.columns }
                            val plain = expression.toString().trim().matches(Regex("(?s)([^ ]+\\.)?\\*"))
                            val safe =
                                chosen.isNotEmpty() && chosen.all { it.second.known } && columns.isNotEmpty() && (qualifier == null || chosen.size == 1) && !(relations.size > 1 && chosen.any {
                                    it.first.startsWith("_dbt")
                                }) && plain
                            if (safe) wildcards += range(expression) to chosen.flatMap { (a, r) ->
                                r.columns.map {
                                    (if (qualifier != null || relations.size > 1) qualifiedAlias(
                                        a
                                    ) + "." else "") + quoted(it)
                                }
                            }
                            else {
                                issues += "Wildcard has an unknown schema or unsupported modifiers."
                                val reason = when {
                                    !plain -> ModifierProblem; chosen.isEmpty() || qualifier != null && chosen.size != 1 -> "No unique relation matches this wildcard. Use a declared relation alias, or write the required columns explicitly."; relations.size > 1 && chosen.any {
                                        it.first.startsWith(
                                            "_dbt"
                                        )
                                    } -> "This join needs explicit relation aliases before its wildcard can be expanded. Add an alias to each relation and use alias.*."; else -> "A complete column list is unavailable for this relation. Add or update its model/source column declarations, or generate current dbt documentation for this project and target. You can also write the required columns explicitly."
                                }
                                wildcardProblems += DbtSqlProblem(range(expression), reason)
                            }
                            columns.map {
                                DbtSqlColumn(
                                    it.name,
                                    range(expression),
                                    listOf(it),
                                    true,
                                    expression.toString(),
                                    safe
                                )
                            }
                        } else {
                            val inputs = mutableListOf<DbtSymbol>();
                            var resolved = true
                            expression.accept(object : ExpressionVisitorAdapter<Void>() {
                                override fun <S> visit(c: Column, context: S): Void? {
                                    val targets = resolve(c); if (targets.isEmpty()) resolved =
                                        false; inputs += targets; return null
                                }
                            }, null)
                            val dataType = when (expression) {
                                is Column -> resolve(expression).firstOrNull()?.attributes?.get("data_type"); is CastExpression -> expression.colDataType?.dataType; is StringValue -> "string"; else -> null
                            }
                            val name = DbtSqlNavigation.itemName(item)
                                ?.takeUnless { item.alias == null && blocks.any { it.range.intersects(nameRange(item)) } }
                            listOfNotNull(name?.let {
                                DbtSqlColumn(
                                    it,
                                    nameRange(item),
                                    inputs.distinct(),
                                    expression is Column,
                                    range(expression).substring(text),
                                    resolved && complete,
                                    dataType,
                                    item.alias != null
                                )
                            })
                        }
                    }
                    output.filter { it.outputAlias }
                        .forEach { declarations += DbtSymbol("sql_projection", it.name, root.name, file, it.range) }
                    DbtSqlNavigation.all(s.selectItems)
                        .forEach { item -> removable(range(item), false)?.let { deletions += nameRange(item) to it } }
                    val contextInputs = mutableListOf<Pair<DbtSymbol, String>>()
                    fun context(expression: Expression?, kind: String) {
                        expression?.accept(object : ExpressionVisitorAdapter<Void>() {
                            override fun <S> visit(c: Column, ignored: S): Void? {
                                val inputs = resolve(c, false);
                                val aliases = if (inputs.isEmpty() && c.table?.name == null) output.filter {
                                    it.name.equals(
                                        c.unquotedColumnName,
                                        true
                                    )
                                }.flatMap { it.inputs } else emptyList()
                                contextInputs += (inputs + aliases).map { it to kind }; return null
                            }
                        }, null)
                    }
                    context(s.where, "filter"); context(s.having, "filter"); DbtSqlNavigation.all(s.joins)
                        .forEach { j -> DbtSqlNavigation.all(j.onExpressions).forEach { context(it, "join") } }
                    output.map { it.copy(contextInputs = contextInputs.distinct()) }
                }

                else -> {
                    issues += "Unsupported SQL query shape."; emptyList()
                }
            }
        }
        if (parsed !is Select) return Result.failure(IllegalArgumentException("SQL analysis requires a SELECT query. This file contains a different SQL statement."))
        val columns = select(parsed, emptyMap())
        return Result.success(
            DbtSqlAnalysis(
                columns,
                scopes,
                ctes,
                wildcards,
                issues.distinct() + if (scopes.any { !it.complete }) listOf("Some relation schemas are unavailable.") else emptyList(),
                intermediate,
                problems.distinct(),
                deletions,
                declarations,
                wildcardProblems
            )
        )
    }
}
