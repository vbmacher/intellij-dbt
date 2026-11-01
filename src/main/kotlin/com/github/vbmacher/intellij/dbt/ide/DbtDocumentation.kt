package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.model.Pointer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.*
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.ide.documentation.DOCUMENTATION_TARGETS
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.HttpRequests
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.DUMMY_ATTRIBUTES_CUSTOMIZER
import org.intellij.markdown.parser.MarkdownParser
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable

/** Offset-based provider supports native modifier-free hover, even with plain-text SQL PSI. */
class DbtDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val targets = DbtNavigation.resolvedAt(file, offset).filter {
            it.kind in DbtResourceData.GraphKinds ||
                    it.kind in setOf("macro", "documentation") || it.kind in DbtSymbol.ColumnKinds
        }.distinctBy { it.id }
        return if (targets.size != 1) emptyList() else listOfNotNull(
            DbtDocumentation.target(
                file.project,
                targets.first()
            ) { DbtDocumentationTarget(file, offset, it) })
    }
}

internal sealed class DbtSymbolDocumentationTarget(val symbol: DbtSymbol) : DocumentationTarget {
    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(DbtDocumentation.displayName(symbol)).icon(symbol.element.getIcon(0)).presentation()

    override val navigatable: Navigatable get() = symbol.element
}

internal class DbtDocumentationTarget(private val file: PsiFile, private val offset: Int, resolved: DbtSymbol) :
    DbtSymbolDocumentationTarget(resolved) {
    override fun createPointer(): Pointer<out DocumentationTarget> =
        Pointer.fileRangePointer(file, TextRange(offset, offset)) { fresh, range ->
            DbtDocumentation.targetAt(
                fresh,
                range.startOffset
            )
        }

    override fun computeDocumentation(): DocumentationResult = DbtDocumentation.compute(file.project) {
        (DbtDocumentation.targetAt(
            file,
            offset
        ) as? DbtDocumentationTarget)?.let { DbtDocumentation.render(DbtWorkspace.get(file.project), it.symbol) }
    }
}

internal class DbtResourceDocumentationTarget(resource: DbtSymbol) : DbtSymbolDocumentationTarget(resource) {
    override fun createPointer(): Pointer<out DocumentationTarget> =
        Pointer.fileRangePointer(symbol.file, symbol.range) { file, _ ->
            DbtWorkspace.get(file.project).symbols.firstOrNull { it.id == symbol.id }
                ?.let(::DbtResourceDocumentationTarget)
        }

    override fun computeDocumentation(): DocumentationResult = DbtDocumentation.compute(symbol.file.project) {
        val w = DbtWorkspace.get(symbol.file.project)
        w.symbols.firstOrNull { it.id == symbol.id }?.let { DbtDocumentation.render(w, it) }
    }
}

/** Builds sanitized documentation from workspace evidence; local rendering and explicit reference-page loading stay separate. */
internal object DbtDocumentation {
    fun markdown(text: String): String {
        val flavour = CommonMarkFlavourDescriptor(true, false)
        val tree = MarkdownParser(
            flavour,
            false
        ) { com.intellij.openapi.progress.ProgressManager.checkCanceled() }.buildMarkdownTreeFromString(text)
        val renderer = HtmlGenerator.DefaultTagRenderer(DUMMY_ATTRIBUTES_CUSTOMIZER, false)
        val html = HtmlGenerator(text, tree, flavour, false).generateHtml(renderer)
        val allowed = Safelist.basic().addTags("h1", "h2", "h3", "h4", "h5", "h6", "pre", "hr")
            .removeProtocols("a", "href", "ftp", "mailto")
        return Jsoup.clean(html, "", allowed, org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
    }

    fun target(
        project: Project,
        symbol: DbtSymbol,
        resource: (DbtSymbol) -> DocumentationTarget
    ): DocumentationTarget? =
        if (symbol.kind == "documentation") DbtBuiltinDocumentationTarget(symbol)
        else if (symbol.kind in DbtSymbol.ColumnKinds) columnTarget(DbtWorkspace.get(project), symbol) else resource(
            symbol
        )

    fun targetAt(file: PsiFile, offset: Int): DocumentationTarget? =
        DbtDocumentationTargetProvider().documentationTargets(file, offset).firstOrNull()

    /** Computes local HTML in an expiring background read action rather than holding a read lock on the EDT. */
    fun compute(project: Project, html: () -> String?): DocumentationResult = DocumentationResult.asyncDocumentation {
        ReadAction.nonBlocking(Callable { html()?.let(DocumentationResult::documentation) }).expireWith(project)
            .executeSynchronously()
    }

    fun show(project: Project, target: DocumentationTarget) {
        val action = ActionManager.getInstance().getAction("QuickJavaDoc")
        val context =
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(DOCUMENTATION_TARGETS, listOf(target))
                .build()
        ActionUtil.performAction(
            action,
            AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        )
    }

    fun columnTarget(w: DbtWorkspace, column: DbtSymbol): DocumentationTarget? =
        (w.relations(column).sortedWith(compareBy<DbtSymbol> { !it.isCode }.thenBy { it.metadata }).firstOrNull()
            ?: w.model(column.file))?.let { DbtColumnDocumentationTarget(it, column.name) }

    fun show(project: Project, symbol: DbtSymbol) = show(project, DbtResourceDocumentationTarget(symbol))
    fun displayName(s: DbtSymbol) =
        "${s.namespace}.${s.relationName}${s.version?.let { " v$it" } ?: ""}${s.metadataSuffix}"

    fun dependencies(w: DbtWorkspace, s: DbtSymbol): Pair<List<String>, List<String>> {
        fun documented(symbol: DbtSymbol) = symbol.kind in DbtResourceData.GraphKinds || symbol.kind == "macro"
        val edges = DbtLineage.edges(w).filter { documented(it.from) && documented(it.to) }
        fun same(a: DbtSymbol) = w.owner(a) == w.owner(s) && a.uniqueId == s.uniqueId
        return edges.filter { same(it.from) }.map { displayName(it.to) }.distinct() to edges.filter { same(it.to) }
            .map { displayName(it.from) }.distinct()
    }

    fun escape(text: String) = buildString {
        text.forEach {
            append(
                when (it) {
                    '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString()
                }
            )
        }
    }

    fun render(w: DbtWorkspace, s: DbtSymbol, linkTestSources: Boolean = false): String {
        val data = DbtResourceData.get(w, s)
        val (upstream, downstream) = dependencies(w, s)
        fun e(value: String) = escape(value)
        fun list(label: String, items: List<String>) = "<h3>$label (${items.size})</h3>" +
                if (items.isEmpty()) "<p>None recorded</p>" else "<ul>" + items.joinToString("") { "<li>${e(it)}</li>" } + "</ul>"

        val relation = data.attributes["relation_name"] ?: data.attributes["identifier"].orEmpty()
        val metadata = listOf("Package" to s.namespace) + listOfNotNull(s.version?.let { "Version" to it }) +
                listOf(
                    "Database" to "database",
                    "Schema" to "schema",
                    "Materialization" to "materialized"
                ).mapNotNull { (label, key) -> data.attributes[key]?.takeIf { it.isNotEmpty() }?.let { label to it } } +
                listOfNotNull(relation.takeIf { it.isNotEmpty() }?.let { "Relation" to it })
        val signature = if (s.kind == "macro") {
            val names =
                if (data.arguments.isNotEmpty()) data.arguments.map { it.name } else w.symbols.filter { it.kind == "parameter" && it.owner == s.name && it.file == s.file }
                    .map { it.name }
            "<pre>${e(s.name + names.joinToString(", ", "(", ")"))}</pre>" + data.arguments.joinToString("") {
                "<p>${
                    e(
                        it.name
                    )
                }: ${e(it.dataType)}</p>${markdown(it.description)}"
            }
        } else ""
        val airflow = DbtAirflow.context(w, s)
        return "<div class='definition'><b>${e(displayName(s))}</b> — ${e(s.kind)}</div>" + signature +
                "<table>" + metadata.joinToString("") { (label, value) -> "<tr><td>$label</td><td>${e(value)}</td></tr>" } + "</table>" +
                "<div class='content'>${markdown(data.description)}</div>" + "<p>Tags: ${e(data.tags.joinToString(", "))}</p><h3>Columns (${data.columns.size})</h3>" +
                (if (data.columns.isEmpty()) "<p>No columns documented</p>" else "<table>" + data.columns.joinToString("") { c ->
                    "<tr><td><a href=\"dbt-column:${
                        URLEncoder.encode(
                            c.name,
                            StandardCharsets.UTF_8
                        )
                    }\">${e(c.name)}</a></td><td>${e(c.dataType)}</td><td>${markdown(c.description)}</td></tr>"
                } + "</table>") +
                list("Upstream", upstream) + list(
            "Downstream",
            downstream
        ) + "<h3>Tests (${data.tests.size})</h3>" + data.tests.joinToString("") { t ->
            val name = t.sourceId?.takeIf { linkTestSources }
                ?.let { "<a href=\"dbt-test:${URLEncoder.encode(it, StandardCharsets.UTF_8)}\">${e(t.name)}</a>" } ?: e(
                t.name
            )
            "<p>$name ${e(t.column)}</p>"
        } + (if (airflow.isEmpty()) "" else "<h3>Airflow context</h3>" + airflow.joinToString("") { t ->
            "<p>${e(t.dag)} / ${e(t.task)} — ${e(t.schedule)}<br>Selection: ${e(t.selection)}<br>Exclude: ${e(t.exclude)}<br>${
                e(
                    t.problems.joinToString("; ")
                )
            }</p>"
        }) +
                "<p>Source: ${e(s.file.virtualFile.path)}</p>"
    }
}

interface DbtBuiltinDocumentationSource {
    fun html(url: String): String
}

/** Loads reference pages only for asynchronous documentation requests and keeps the sanitized article in a soft cache. */
class DbtBuiltinDocumentationLoader : DbtBuiltinDocumentationSource {
    private val pages = ContainerUtil.createConcurrentSoftValueMap<String, String>()
    override fun html(url: String): String = pages.computeIfAbsent(url.takeWhile { it != '#' }) { address ->
        val page =
            HttpRequests.request(address).productNameAsUserAgent().connectTimeout(5000).readTimeout(5000).readString()
        article(page, address)
    }

    companion object {
        fun article(page: String, url: String): String {
            val document = Jsoup.parse(page, url)
            val article = document.selectFirst("article .markdown") ?: document.selectFirst("article")
            ?: throw IOException("The dbt reference page did not contain an article.")
            article.select("script, style, nav, button, svg, iframe, form, input, .hash-link").remove()
            val tags = Safelist.basic()
                .addTags("h1", "h2", "h3", "h4", "table", "thead", "tbody", "tr", "th", "td", "pre", "hr")
                .addAttributes(":all", "id")
            return Jsoup.clean(article.html(), url, tags)
        }

        fun referenceUrl(base: String, link: String): String? = try {
            val uri = URI(base).resolve(link)
            if (uri.scheme != "https" || uri.host != "docs.getdbt.com" || uri.userInfo != null) null else {
                val version = URI(base).rawQuery?.takeIf { it.startsWith("version=") }
                if (uri.rawQuery == null && version != null) URI(
                    uri.scheme,
                    uri.authority,
                    uri.path,
                    version,
                    uri.fragment
                ).toString() else uri.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal class DbtBuiltinDocumentationTarget(val symbol: DbtSymbol) : DocumentationTarget {
    val url = symbol.attributes.getValue("url")
    override fun createPointer(): Pointer<out DocumentationTarget> =
        Pointer.fileRangePointer(symbol.file, symbol.range) { fresh, range ->
            DbtBuiltinDocumentationTarget(
                symbol.copy(
                    file = fresh,
                    range = range
                )
            )
        }

    override fun computePresentation() =
        TargetPresentation.builder("dbt.${symbol.name}").icon(symbol.element.getIcon(0)).presentation()

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.asyncDocumentation {
        val title = "<div class='definition'><b>dbt.${DbtDocumentation.escape(symbol.name)}</b></div>"
        val content = try {
            symbol.file.project.getService(DbtBuiltinDocumentationSource::class.java).html(url)
        } catch (error: IOException) {
            "<p>Unable to load the dbt reference documentation. Check your connection and invoke Quick Documentation again.</p><p>" + DbtDocumentation.escape(
                error.message ?: "Network error"
            ) + "</p>"
        }
        val result =
            DocumentationResult.documentation(title + "<div class='content'>" + content + "</div>").externalUrl(url)
        URI(url).fragment?.let(result::anchor) ?: result
    }
}

class DbtBuiltinPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? =
        (element as? DbtSymbolElement)?.let {
            DbtDocumentation.target(
                it.project,
                it.symbol,
                ::DbtResourceDocumentationTarget
            )
        }
}

/** Keeps official HTTPS reference links in Quick Documentation and retains the selected dbt documentation version. */
class DbtBuiltinDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val doc = target as? DbtBuiltinDocumentationTarget ?: return null
        val address = DbtBuiltinDocumentationLoader.referenceUrl(doc.url, url) ?: return null
        val name = URI(address).path.orEmpty().trimEnd('/').substringAfterLast('/').ifEmpty { doc.symbol.name }
        return LinkResolveResult.resolvedTarget(
            DbtBuiltinDocumentationTarget(
                doc.symbol.copy(
                    name = name,
                    attributes = doc.symbol.attributes + ("url" to address)
                )
            )
        )
    }
}

class DbtBuiltinDocumentationNavigation(private val project: Project) : DbtDocumentationNavigation {
    override fun show(element: DbtSymbolElement) =
        DbtDocumentation.show(project, DbtBuiltinDocumentationTarget(element.symbol))
}

class DbtColumnDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val symbol = (target as? DbtSymbolDocumentationTarget)?.symbol ?: return null
        if (!url.startsWith("dbt-column:")) return null
        val name = try {
            URLDecoder.decode(url.removePrefix("dbt-column:"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (DbtResourceData.get(DbtWorkspace.get(symbol.file.project), symbol).columns.any { it.name == name })
            LinkResolveResult.resolvedTarget(DbtColumnDocumentationTarget(symbol, name)) else null
    }
}

internal class DbtColumnDocumentationTarget(private val resource: DbtSymbol, private val name: String) :
    DocumentationTarget {
    private fun current(w: DbtWorkspace) =
        w.symbols.firstOrNull { it.file == resource.file && it.sameResource(resource) }

    private fun column(w: DbtWorkspace, model: DbtSymbol): DbtSymbol? = w.columns(model).firstOrNull { it.name == name }
        ?: DbtSqlAnalysis.get(w, model.file)?.columns?.firstOrNull { it.name == name }
            ?.let { DbtSymbol("sql_projection", name, model.namespace, model.file, it.range, owner = model.name) }

    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.fileRangePointer(
        resource.file,
        resource.range
    ) { file, _ -> current(DbtWorkspace.get(file.project))?.let { DbtColumnDocumentationTarget(it, name) } }

    override fun computePresentation() = TargetPresentation.builder(resource.name + "." + name).presentation()
    override val navigatable: Navigatable?
        get() {
            val w = DbtWorkspace.get(resource.file.project)
            return current(w)?.let { column(w, it) }?.element
        }

    override fun computeDocumentation() =
        DbtDocumentation.compute(resource.file.project) { render(DbtWorkspace.get(resource.file.project)) }

    fun render(w: DbtWorkspace): String? = current(w)?.let { model ->
        fun e(text: String) = DbtDocumentation.escape(text)
        val data = DbtResourceData.get(w, model).columns.firstOrNull { it.name == name }
        fun related(downstream: Boolean): String =
            (column(w, model)?.let { DbtHierarchy.related(w, it, downstream) } ?: emptyList()).joinToString(
                "",
                "<ul>",
                "</ul>"
            ) { (symbol, kind) ->
                "<li>${e(symbol.namespace)}.${e(symbol.owner)}.${e(symbol.name)} — ${e(kind)}</li>"
            }

        val origin = DbtPropertiesEditor.origins(w, model, name).first
        "<div class='definition'><b>${e(model.name)}.${e(name)}</b></div><p>${e(origin)}</p>" +
                (data?.let { "<p>${e(it.dataType)}</p>${DbtDocumentation.markdown(it.description)}" } ?: "") +
                "<h3>Upstream dependencies</h3>" + related(false) + "<h3>Downstream consumers</h3>" + related(true)
    }
}
