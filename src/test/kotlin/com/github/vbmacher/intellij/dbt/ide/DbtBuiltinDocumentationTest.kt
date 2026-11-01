@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.lang.documentation.impl.documentationTargets
import com.intellij.lang.documentation.ide.impl.DocumentationManager
import com.intellij.lang.documentation.ide.impl.DocumentationToolWindowManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.swing.JEditorPane
import com.intellij.platform.ide.documentation.DOCUMENTATION_TARGETS

class DbtBuiltinDocumentationTest : DbtTestCase() {
    private val requested = ConcurrentLinkedQueue<String>()
    private var autoUpdate = false
    private var skipPopup = false
    private val page =
        """<html><head><style>unwanted css</style></head><body><nav>Website menu</nav><article>
    <nav>Breadcrumbs</nav><div class="markdown"><h1>ref</h1><p>Returns a relation and records a model dependency.</p>
    <pre><code>{{ ref('orders') }}</code></pre><table><tr><th>Argument</th><td>model_name</td></tr></table>
    <a href="/reference/dbt-jinja-functions/source">source</a><h2 id="versions">Versions</h2>
    <button>Copy code</button><script>alert('unsafe')</script><img src="https://example.com/tracking.png">
    <a href="javascript:alert(1)">unsafe link</a></div><footer>Page feedback</footer></article></body></html>"""

    protected override fun setUp() {
        super.setUp()
        autoUpdate = DocumentationToolWindowManager.autoUpdate
        skipPopup = DocumentationManager.skipPopup
        DocumentationManager.skipPopup = true
        DocumentationToolWindowManager.autoUpdate = false
        project.replaceService(DbtBuiltinDocumentationSource::class.java, object : DbtBuiltinDocumentationSource {
            override fun html(url: String): String {
                assertFalse("Documentation must load off the EDT", ApplicationManager.getApplication().isDispatchThread)
                assertFalse(
                    "Network work must not hold a read lock",
                    ApplicationManager.getApplication().isReadAccessAllowed
                )
                requested.add(url)
                return DbtBuiltinDocumentationLoader.article(page, url)
            }
        }, getTestRootDisposable())
    }

    protected override fun tearDown() {
        try {
            ToolWindowManager.getInstance(project)
                .getToolWindow(DocumentationToolWindowManager.TOOL_WINDOW_ID)?.contentManager?.removeAllContents(true)
            DocumentationToolWindowManager.autoUpdate = autoUpdate
            DocumentationManager.skipPopup = skipPopup
        } finally {
            super.tearDown()
        }
    }

    private val targets: List<DocumentationTarget>
        get() {
            val file = myFixture.getFile()
            val offset = myFixture.getCaretOffset()
            return ReadAction.nonBlocking(Callable { documentationTargets(file, offset).toList() })
                .submit(AppExecutorUtil.getAppExecutorService()).get(10, TimeUnit.SECONDS)
        }

    private fun html(target: DocumentationTarget): String {
        val pointer = target!!.createPointer()
        return AppExecutorUtil.getAppExecutorService().submit(Callable { computeDocumentationBlocking(pointer)!!.html })
            .get(10, TimeUnit.SECONDS)
    }

    fun testNativeQuickDocumentationIncludesBuiltinNamesAndKeepsResourceArguments() {
        project()
        add("models/orders.sql", "select 1")
        for ((name, expression) in listOf(
            "ref" to "r<caret>ef('orders')",
            "source" to "sou<caret>rce('raw', 'events')",
            "var" to "v<caret>ar('value')",
            "env_var" to "env_<caret>var('NAME')",
            "run_query" to "run_<caret>query('select 1')"
        )) {
            configure("models/report_" + name + ".sql", "select {{ " + expression + " }}")
            assertEquals(name, 1, targets.size)
            assertEquals("dbt." + name, targets.first().computePresentation().presentableText)
            val content = html(targets.first())
            assertTrue(content, content.contains("Returns a relation"))
            assertTrue(requested.peek(), requested.any { it.contains("/" + name + "?version=1.10") })
        }
        configure("models/report_resource.sql", "select * from {{ ref('or<caret>ders') }}")
        assertEquals("analytics.orders", targets.first().computePresentation().presentableText)
        assertFalse(targets.first() is DbtBuiltinDocumentationTarget)
        configure("models/unrelated.sql", "sel<caret>ect 1")
        assertTrue(
            DbtDocumentationTargetProvider().documentationTargets(
                myFixture.getFile(),
                myFixture.getCaretOffset()
            ).isEmpty()
        )
    }

    fun testBuiltinPsiTargetsAndPointersWorkAndUserMacrosKeepSourceNavigation() {
        project()
        val file = configure("models/report.jinja", "{{ builtins.r<caret>ef('orders') }}")
        val element =
            DbtNavigation.at(file, myFixture.caretOffset).flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>()
                .first { it.symbol.kind == "documentation" }
        val target = DbtBuiltinPsiDocumentationTargetProvider().documentationTarget(element, file)
        assertNotNull(target)
        assertTrue(html(target!!).contains("model dependency"))
        assertNotNull(target!!.createPointer().dereference())
        add("macros/ref.sql", "{% macro ref(name) %}{{ builtins.ref(name) }}{% endmacro %}")
        configure("models/report.sql", "{{ r<caret>ef('orders') }}")
        assertTrue(targets.first() is DbtDocumentationTarget)
        assertEquals("macro", (declarationTargets.first() as DbtSymbolElement).symbol.kind)
    }

    fun testOfficialArticleUsesNativeHtmlWithoutWebsiteChromeOrActiveContent() {
        val content = DbtBuiltinDocumentationLoader.article(
            page,
            "https://docs.getdbt.com/reference/dbt-jinja-functions/ref?version=1.10"
        )
        assertTrue(content, content.contains("Returns a relation"))
        assertTrue(content, content.contains("<pre>"))
        assertTrue(content, content.contains("<table>"))
        assertTrue(content, content.contains("id=\"versions\""))
        assertTrue(content, content.contains("https://docs.getdbt.com/reference/dbt-jinja-functions/source"))
        for (unwanted in listOf(
            "Website menu",
            "Breadcrumbs",
            "Page feedback",
            "Copy code",
            "<script",
            "<style",
            "<img",
            "javascript:"
        ))
            assertFalse(content, content.contains(unwanted))
    }

    fun testDbtReferenceLinksRemainInternalAndRetainTheSelectedVersion() {
        project()
        configure("models/report.sql", "{{ r<caret>ef('orders') }}")
        val target = targets.first() as DbtBuiltinDocumentationTarget
        val handler = DbtBuiltinDocumentationLinkHandler()
        assertNotNull(handler.resolveLink(target, "/reference/dbt-jinja-functions/source"))
        assertEquals(
            "https://docs.getdbt.com/reference/dbt-jinja-functions/source?version=1.10",
            DbtBuiltinDocumentationLoader.referenceUrl(target.url, "/reference/dbt-jinja-functions/source")
        )
        assertEquals(target.url + "#versions", DbtBuiltinDocumentationLoader.referenceUrl(target.url, "#versions"))
        for (url in listOf(
            "javascript:alert(1)",
            "https://docs.getdbt.com.evil.test/ref",
            "https://example.com/",
            "http://docs.getdbt.com/ref"
        ))
            assertNull(handler.resolveLink(target, url))
    }

    fun testOnRunEndVariablesUseTheOfficialHyphenatedDocumentationPage() {
        val file = configure(
            "dbt_project.yml",
            "name: analytics\nconfig-version: 2\non-run-end: \"{{ res<caret>ults }} {{ schemas }} {{ selected_resources }}\"\n"
        )
        for (name in listOf("results", "schemas", "selected_resources")) {
            myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf(name) + 1)
            assertEquals(
                "https://docs.getdbt.com/reference/dbt-jinja-functions/on-run-end-context?version=1.10",
                (targets.first() as DbtBuiltinDocumentationTarget).url
            )
        }
    }

    fun testUnavailableDocumentationStaysInTheInternalPaneWithAnActionableMessage() {
        project.replaceService(DbtBuiltinDocumentationSource::class.java, object : DbtBuiltinDocumentationSource {
            override fun html(url: String): String = throw IOException("Offline <untrusted>")
        }, getTestRootDisposable())
        project()
        configure("models/report.sql", "{{ r<caret>ef('orders') }}")
        val content = html(targets.first())
        assertTrue(content, content.contains("Check your connection"))
        assertTrue(content, content.contains("Offline &lt;untrusted&gt;"))
    }

    fun testGoToDeclarationOnBuiltinOpensTheNativeDocumentationWindow() {
        project()
        configure("models/report.sql", "{{ r<caret>ef('orders') }}")
        val manager = ToolWindowManager.getInstance(getProject())

        fun displayed(text: String): Boolean =
            manager.getToolWindow(DocumentationToolWindowManager.TOOL_WINDOW_ID)?.contentManager?.contents?.any { content ->
                components(content.component).filterIsInstance<JEditorPane>()
                    .any { org.jsoup.Jsoup.parse(it.text).text().contains(text) }
            } == true

        myFixture.performEditorAction("GotoDeclaration")
        await("Builtin navigation displays native documentation") { displayed("Returns a relation") }
        assertEquals(
            "models/report.sql",
            myFixture.getFile().getVirtualFile().getPath().takeLast("models/report.sql".length)
        )
        assertTrue(requested.any { it.contains("/ref?version=1.10") })
        manager.getToolWindow(DocumentationToolWindowManager.TOOL_WINDOW_ID)!!.getContentManager()
            .removeAllContents(true)
        configure("models/source.sql", "{{ sou<caret>rce('raw', 'events') }}")
        // The light fixture has no showing editor component to position a popup against. Use the native tool-window preference.
        val skipPopup = DocumentationManager.skipPopup
        try {
            DocumentationManager.skipPopup = true
            val action = ActionManager.getInstance().getAction("QuickJavaDoc")
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.EDITOR, myFixture.getEditor()).add(CommonDataKeys.PSI_FILE, myFixture.getFile())
                .add(DOCUMENTATION_TARGETS, targets).build()
            val event = TestActionEvent.createTestEvent(action, context)
            ActionUtil.updateAction(action, event)
            assertTrue("Quick Documentation is enabled for the configured editor", event.getPresentation().isEnabled)
            ActionUtil.performAction(action, event)
            await("Ctrl+Q renders builtin documentation in the native tool window") {
                displayed("dbt.source") && displayed(
                    "Returns a relation"
                )
            }
            assertTrue(requested.any { it.contains("/source?version=1.10") })
        } finally {
            DocumentationManager.skipPopup = skipPopup
        }
    }
}
