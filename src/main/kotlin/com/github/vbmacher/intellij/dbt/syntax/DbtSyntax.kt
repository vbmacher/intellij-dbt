package com.github.vbmacher.intellij.dbt.syntax

import com.hubspot.jinjava.JinjavaConfig
import com.hubspot.jinjava.el.ext.ExtendedScanner
import com.hubspot.jinjava.tree.parse.ExpressionToken
import com.hubspot.jinjava.tree.parse.TagToken
import com.hubspot.jinjava.tree.parse.TokenScanner
import com.hubspot.jinjava.tree.parse.UnclosedToken
import jinjava.de.odysseus.el.tree.impl.Scanner.ScanException
import jinjava.de.odysseus.el.tree.impl.Scanner.Symbol
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/** Library tokens and decoded host offsets; never evaluates a template. */
object DbtSyntax {
    data class Token(val image: String, val range: TextRange, val string: Boolean = false)
    data class Block(val tokens: List<Token>, val range: TextRange, val tag: Boolean) {
        val tagName: String? get() = if (tag) tokens.firstOrNull()?.image else null
        fun isTag(name: String): Boolean = tagName == name
    }

    val ScopeTags = setOf("for", "macro", "test", "call", "with", "block")
    val ScopeEndTags = ScopeTags.map { "end$it" }.toSet()
    fun isIdentifier(text: String): Boolean = text.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))
    private val opening = setOf("(", "[", "{")
    private val closing = setOf(")", "]", "}")
    fun assignedNames(tokens: List<Token>): List<Int> {
        var depth = 0
        return tokens.indices.filter { i ->
            if (tokens[i].image in opening) depth++
            if (tokens[i].image in closing) depth--
            i > 0 && depth == 0 && tokens.getOrNull(i + 1)?.image == "="
        }
    }

    fun encloses(blocks: List<Block>, start: Int, endTag: String, range: TextRange): Boolean =
        start < range.startOffset && blocks.find { it.isTag(endTag) && it.range.startOffset > start }
            ?.let { it.range.endOffset > range.endOffset } == true

    fun tagged(blocks: List<Block>, tag: String, range: TextRange): Pair<Block, Block>? =
        blocks.find { it.isTag(tag) && it.range.contains(range) }?.let { start ->
            blocks.find { it.isTag("end$tag") && it.range.startOffset > start.range.startOffset }?.let { start to it }
        }

    /** Only single string, integer or boolean tokens are literals; expressions remain unresolved. */
    data class Argument(val tokens: List<Token>, val keyword: String?, val keywordRange: TextRange? = null) {
        val literal: String?
            get() = tokens.singleOrNull()
                ?.takeIf { it.string || it.image.matches(Regex("[0-9]+|true|false|True|False")) }?.image
        val range: TextRange?
            get() = tokens.firstOrNull()?.let { TextRange(it.range.startOffset, tokens.last().range.endOffset) }
    }

    /** A missing version argument is null; a present nonliteral argument is represented by `Version(null)`. */
    data class Version(val value: String?)
    /** Retains argument and expression ranges for navigation, including unfinished calls with [closed] set to false. */
    data class Call(
        val name: String, val range: TextRange, val arguments: List<Argument>, val block: Block,
        val expressionRange: TextRange, val closed: Boolean = true
    ) {
        val positional: List<Argument> get() = arguments.filter { it.keyword == null }
        fun literal(index: Int): String? = positional.getOrNull(index)?.literal
        fun argumentRange(index: Int): TextRange? = positional.getOrNull(index)?.range
        val keywords: List<Pair<String, Argument>> get() = arguments.mapNotNull { a -> a.keyword?.let { it to a } }
        val version: Version?
            get() = arguments.find { it.keyword in setOf("v", "version") }?.let { Version(it.literal) }
        val qualifiedName: Pair<String?, String>?
            get() = literal(1)?.let { literal(0) to it } ?: literal(0)?.let { null to it }
        val callee: Pair<String?, String>
            get() {
                val parts = name.split('.')
                return if (parts.size == 2) parts[0] to parts[1] else null to parts.last()
            }
    }

    private class Scanner(content: String) : ExtendedScanner("\${$content}")

    private val config = JinjavaConfig.newBuilder().withTrimBlocks(false).build()
    /** Maps expression offsets into the host file and retains tokens preceding an unfinished expression. */
    fun tokens(text: String, map: (Int) -> Int): List<Token> {
        val result = mutableListOf<Token>()
        val scanner = Scanner(text)
        try {
            var t = scanner.next()
            while (t.symbol != Symbol.EOF) {
                ProgressManager.checkCanceled()
                if (t.symbol != Symbol.START_EVAL_DYNAMIC && t.symbol != Symbol.END_EVAL) {
                    val start = scanner.position - 2
                    val quoted = t.symbol == Symbol.STRING
                    result += Token(
                        t.image,
                        TextRange(map(start + if (quoted) 1 else 0), map(start + t.size - if (quoted) 1 else 0)),
                        quoted
                    )
                }
                t = scanner.next()
            }
        } catch (_: ScanException) { /* Keep tokens preceding an unfinished expression. */
        }
        return result
    }

    fun blocks(text: String, map: (Int) -> Int = { it }): List<Block> {
        val result = mutableListOf<Block>()
        val scanner = TokenScanner(text.replace(Regex("\\{%[-+]"), "{% "), config)
        var offset = 0
        while (scanner.hasNext()) {
            ProgressManager.checkCanceled()
            val token = scanner.next()
            val end = minOf(text.length, offset + token.image.length)
            val image = text.substring(offset, end)
            val closed = token !is UnclosedToken
            if ((token is ExpressionToken || token is TagToken || token is UnclosedToken) && (image.startsWith("{{") || image.startsWith(
                    "{%"
                ))
            ) {
                val begin = offset + if (image.getOrNull(2) in setOf('-', '+')) 3 else 2
                val finish = end - if (closed) 2 else 0
                val contentEnd = if (closed && text.getOrNull(finish - 1) in setOf('-', '+')) finish - 1 else finish
                if (contentEnd >= begin) result += Block(
                    tokens(text.substring(begin, contentEnd)) { map(begin + it) },
                    TextRange(map(offset), map(end)),
                    image.startsWith("{%")
                )
            }
            offset = end
        }
        return result
    }

    private val bareExpressionKeys =
        setOf("input", "depends_on", "model", "relation", "to", "where", "filter", "group_by", "order_by")

    fun inFile(file: PsiFile): List<Block> {
        if (file !is YAMLFile) return blocks(file.text)
        val result = mutableListOf<Block>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is YAMLScalar && "vars" !in DbtYaml.ancestors(element)) DbtYaml.decoded(element)
                    ?.let { (decoded, map) ->
                        result += blocks(decoded, map)
                        if (!decoded.contains("{{") && DbtYaml.ancestors(element)
                                .any { it in bareExpressionKeys } && (decoded.contains('(') || decoded == "this")
                        )
                            result += Block(tokens(decoded, map), element.textRange, false)
                    }
                super.visitElement(element)
            }
        })
        return result
    }

    fun calls(block: Block): List<Call> {
        val ts = block.tokens
        if (block.tagName in setOf("macro", "test", "materialization")) return emptyList()
        return ts.indices.mapNotNull { i ->
            val t = ts[i]
            val prev = ts.getOrNull(i - 1)?.image
            val blocked = t.string || prev in setOf(
                ".",
                "|",
                "is"
            ) || (prev == "not" && ts.getOrNull(i - 2)?.image == "is") || (i == 1 && block.isTag("filter"))
            if (!blocked && ts.getOrNull(i + 1)?.image == "(" && isIdentifier(t.image)) {
                val begin = if (i >= 2 && ts[i - 1].image == ".") i - 2 else i
                parseCall(ts, begin, i, block)
            } else if (!t.string && prev == "." && i >= 2 && ts.getOrNull(i + 1)?.image == "(" && (i < 3 || ts[i - 3].image != ".")) parseCall(
                ts,
                i - 2,
                i,
                block
            )
            else null
        }
    }

    fun parameters(block: Block): List<Token> {
        val result = mutableListOf<Token>()
        val start = block.tokens.indexOfFirst { it.image == "(" }
        var depth = 0
        var expectingName = false
        if (start >= 0) for (token in block.tokens.drop(start)) {
            if (!token.string && token.image in opening) {
                depth++; if (depth == 1) expectingName = true
            } else if (!token.string && token.image in closing) {
                depth--; if (depth == 0) break
            } else if (depth == 1 && !token.string && token.image == ",") expectingName = true
            else if (depth == 1 && expectingName) {
                if (!token.string && isIdentifier(token.image)) result += token; expectingName = false
            }
        }
        return result
    }

    private fun parseCall(ts: List<Token>, begin: Int, callee: Int, block: Block): Call {
        val args = mutableListOf<Argument>()
        val current = mutableListOf<Token>()
        var depth = 0
        var i = callee + 2
        var finished = false
        fun flush() {
            if (current.isNotEmpty()) {
                val xs = current.toList()
                val named = xs.size >= 3 && xs[1].image == "="
                args += Argument(
                    if (named) xs.drop(2) else xs,
                    if (named) xs.first().image else null,
                    if (named) xs.first().range else null
                )
                current.clear()
            }
        }
        while (i < ts.size && !finished) {
            val t = ts[i]
            if (!t.string && t.image == ")" && depth == 0) {
                flush(); finished = true
            } else if (!t.string && t.image == "," && depth == 0) flush()
            else {
                current += t; if (!t.string && t.image in opening) depth++; if (!t.string && t.image in closing) depth--
            }
            i++
        }
        return Call(
            ts.subList(begin, callee + 1).joinToString("") { it.image },
            TextRange(ts[begin].range.startOffset, ts[callee].range.endOffset),
            args,
            block,
            TextRange(ts[begin].range.startOffset, ts[i - 1].range.endOffset),
            finished
        )
    }
}

/** Host spans only, not a SQL grammar. Keeps edits out of comments and literals. */
/** Lexical SQL spans used to distinguish identifiers from comments and literals without requiring a complete SQL parse. */
object DbtSqlText {
    data class Token(
        val text: String,
        val range: TextRange,
        val protectedText: Boolean = false,
        val identifier: Boolean = false
    )

    private val DollarQuote = Regex("\\$[A-Za-z_0-9]*\\$")
    fun tokens(text: String): List<Token> {
        val result = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            ProgressManager.checkCanceled()
            val start = i
            var protectedText = false
            var identifier = false
            if (text.startsWith("{{", i) || text.startsWith("{%", i) || text.startsWith("{#", i)) {
                val endTag = when (text[i + 1]) {
                    '{' -> "}}"; '%' -> "%}"; else -> "#}"
                }
                val end = text.indexOf(endTag, i + 2); i = if (end < 0) text.length else end + 2
            } else if (text.startsWith("--", i)) {
                protectedText = true; i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
            } else if (text.startsWith("/*", i)) {
                protectedText = true;
                var depth = 1; i += 2
                while (i < text.length && depth > 0) when {
                    text.startsWith("/*", i) -> {
                        depth++; i += 2
                    }

                    text.startsWith("*/", i) -> {
                        depth--; i += 2
                    }

                    else -> i++
                }
            } else if (text[i] in setOf('\'', '"', '`', '[')) {
                val quote = text[i];
                val end = if (quote == '[') ']' else quote
                protectedText = quote == '\''; identifier = !protectedText; i++
                var done = false
                while (i < text.length && !done) {
                    if (text[i] == end) {
                        if (i + 1 < text.length && text[i + 1] == end) i += 2 else {
                            i++; done = true
                        }
                    } else if (text[i] == '\\' && i + 1 < text.length) i += 2 else i++
                }
            } else if (text[i] == '$' && DollarQuote.find(text, i)?.range?.first == i) {
                protectedText = true;
                val delimiter = DollarQuote.find(text, i)!!.value
                val end = text.indexOf(delimiter, i + delimiter.length); i =
                    if (end < 0) text.length else end + delimiter.length
            } else if (text[i].isLetterOrDigit() || text[i] == '_') {
                identifier = true; i++; while (i < text.length && (text[i].isLetterOrDigit() || text[i] in setOf(
                        '_',
                        '$'
                    ))
                ) i++
            } else if (text[i].isWhitespace()) {
                i++; while (i < text.length && text[i].isWhitespace()) i++
            } else i++
            result += Token(text.substring(start, i), TextRange(start, i), protectedText, identifier)
        }
        return result
    }

    fun inactive(text: String, offset: Int): Boolean =
        tokens(text).any { it.protectedText && it.range.containsOffset(offset) }
}
