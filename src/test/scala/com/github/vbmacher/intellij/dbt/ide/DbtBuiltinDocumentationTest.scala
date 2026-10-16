package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.lang.documentation.impl.TargetsKt
import com.intellij.lang.documentation.ide.impl.DocumentationToolWindowManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.testFramework.{PlatformTestUtil, ServiceContainerUtil}
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert._
import java.awt.{Component, Container}
import java.io.IOException
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, TimeUnit}
import javax.swing.JEditorPane
import scala.jdk.CollectionConverters._

final class DbtBuiltinDocumentationTest extends DbtCodeInsightTestCase {
  private val requested = new ConcurrentLinkedQueue[String]()
  private val page = """<html><head><style>unwanted css</style></head><body><nav>Website menu</nav><article>
    <nav>Breadcrumbs</nav><div class="markdown"><h1>ref</h1><p>Returns a relation and records a model dependency.</p>
    <pre><code>{{ ref('orders') }}</code></pre><table><tr><th>Argument</th><td>model_name</td></tr></table>
    <a href="/reference/dbt-jinja-functions/source">source</a><h2 id="versions">Versions</h2>
    <button>Copy code</button><script>alert('unsafe')</script><img src="https://example.com/tracking.png">
    <a href="javascript:alert(1)">unsafe link</a></div><footer>Page feedback</footer></article></body></html>"""

  override protected def setUp(): Unit = {
    super.setUp()
    ServiceContainerUtil.replaceService(getProject, classOf[DbtBuiltinDocumentationSource], new DbtBuiltinDocumentationSource {
      override def html(url: String): String = {
        assertFalse("Documentation must load off the EDT", ApplicationManager.getApplication.isDispatchThread)
        assertFalse("Network work must not hold a read lock", ApplicationManager.getApplication.isReadAccessAllowed)
        requested.add(url)
        DbtBuiltinDocumentationLoader.article(page, url)
      }
    }, getTestRootDisposable)
  }

  private def targets: Vector[DocumentationTarget] = TargetsKt.documentationTargets(myFixture.getFile, myFixture.getCaretOffset).asScala.toVector

  private def html(target: DocumentationTarget): String = {
    val pointer = target.createPointer()
    AppExecutorUtil.getAppExecutorService.submit(new Callable[String] {
      override def call(): String = DbtDocumentationTestSupport.html(pointer)
    }).get(10, TimeUnit.SECONDS)
  }

  def testNativeQuickDocumentationIncludesBuiltinNamesAndKeepsResourceArguments(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    for ((name, expression) <- Vector("ref" -> "r<caret>ef('orders')", "source" -> "sou<caret>rce('raw', 'events')",
      "var" -> "v<caret>ar('value')", "env_var" -> "env_<caret>var('NAME')", "run_query" -> "run_<caret>query('select 1')")) {
      configure("models/report_" + name + ".sql", "select {{ " + expression + " }}")
      assertEquals(name, 1, targets.size)
      assertEquals("dbt." + name, targets.head.computePresentation().getPresentableText)
      val content = html(targets.head)
      assertTrue(content, content.contains("Returns a relation"))
      assertTrue(requested.peek(), requested.asScala.exists(_.contains("/" + name + "?version=1.10")))
    }
    configure("models/report_resource.sql", "select * from {{ ref('or<caret>ders') }}")
    assertEquals("analytics.orders", targets.head.computePresentation().getPresentableText)
    assertFalse(targets.head.isInstanceOf[DbtBuiltinDocumentationTarget])
    configure("models/unrelated.sql", "sel<caret>ect 1")
    assertTrue(new DbtDocumentationTargetProvider().documentationTargets(myFixture.getFile, myFixture.getCaretOffset).isEmpty)
  }

  def testBuiltinPsiTargetsAndPointersWorkAndUserMacrosKeepSourceNavigation(): Unit = {
    project()
    val file = configure("models/report.jinja", "{{ builtins.r<caret>ef('orders') }}")
    val element = DbtNavigation.at(file, myFixture.getCaretOffset).flatMap(_.candidates).collectFirst {
      case e: DbtSymbolElement if e.symbol.kind == "documentation" => e
    }.get
    val target = new DbtBuiltinPsiDocumentationTargetProvider().documentationTarget(element, file)
    assertNotNull(target)
    assertTrue(html(target).contains("model dependency"))
    assertNotNull(target.createPointer().dereference())
    add("macros/ref.sql", "{% macro ref(name) %}{{ builtins.ref(name) }}{% endmacro %}")
    configure("models/report.sql", "{{ r<caret>ef('orders') }}")
    assertTrue(targets.head.isInstanceOf[DbtDocumentationTarget])
    assertEquals("macro", declarationTargets.head.asInstanceOf[DbtSymbolElement].symbol.kind)
  }

  def testOfficialArticleUsesNativeHtmlWithoutWebsiteChromeOrActiveContent(): Unit = {
    val content = DbtBuiltinDocumentationLoader.article(page, "https://docs.getdbt.com/reference/dbt-jinja-functions/ref?version=1.10")
    assertTrue(content, content.contains("Returns a relation"))
    assertTrue(content, content.contains("<pre>"))
    assertTrue(content, content.contains("<table>"))
    assertTrue(content, content.contains("id=\"versions\""))
    assertTrue(content, content.contains("https://docs.getdbt.com/reference/dbt-jinja-functions/source"))
    for (unwanted <- Vector("Website menu", "Breadcrumbs", "Page feedback", "Copy code", "<script", "<style", "<img", "javascript:"))
      assertFalse(content, content.contains(unwanted))
  }

  def testDbtReferenceLinksRemainInternalAndRetainTheSelectedVersion(): Unit = {
    project()
    configure("models/report.sql", "{{ r<caret>ef('orders') }}")
    val target = targets.head.asInstanceOf[DbtBuiltinDocumentationTarget]
    val handler = new DbtBuiltinDocumentationLinkHandler
    assertNotNull(handler.resolveLink(target, "/reference/dbt-jinja-functions/source"))
    assertEquals(Some("https://docs.getdbt.com/reference/dbt-jinja-functions/source?version=1.10"),
      DbtBuiltinDocumentationLoader.referenceUrl(target.url, "/reference/dbt-jinja-functions/source"))
    assertEquals(Some(target.url + "#versions"), DbtBuiltinDocumentationLoader.referenceUrl(target.url, "#versions"))
    for (url <- Vector("javascript:alert(1)", "https://docs.getdbt.com.evil.test/ref", "https://example.com/", "http://docs.getdbt.com/ref"))
      assertNull(handler.resolveLink(target, url))
  }

  def testUnavailableDocumentationStaysInTheInternalPaneWithAnActionableMessage(): Unit = {
    ServiceContainerUtil.replaceService(getProject, classOf[DbtBuiltinDocumentationSource], new DbtBuiltinDocumentationSource {
      override def html(url: String): String = throw new IOException("Offline <untrusted>")
    }, getTestRootDisposable)
    project()
    configure("models/report.sql", "{{ r<caret>ef('orders') }}")
    val content = html(targets.head)
    assertTrue(content, content.contains("Check your connection"))
    assertTrue(content, content.contains("Offline &lt;untrusted&gt;"))
  }

  def testGoToDeclarationOnBuiltinOpensTheNativeDocumentationWindow(): Unit = {
    project()
    configure("models/report.sql", "{{ r<caret>ef('orders') }}")
    val manager = ToolWindowManager.getInstance(getProject)
    def children(component: Component): Vector[Component] = Vector(component) ++ (component match {
      case container: Container => container.getComponents.toVector.flatMap(children)
      case _ => Vector.empty
    })
    def displayed(text: String): Boolean =
      Option(manager.getToolWindow(DocumentationToolWindowManager.TOOL_WINDOW_ID)).exists(window =>
        window.getContentManager.getContents.exists(content => children(content.getComponent).exists {
          case pane: JEditorPane => pane.getText.contains(text)
          case _ => false
        }))
    myFixture.performEditorAction("GotoDeclaration")
    PlatformTestUtil.waitWithEventsDispatching("Builtin navigation displays native documentation", () => displayed("Returns a relation"), 15)
    assertEquals("models/report.sql", myFixture.getFile.getVirtualFile.getPath.takeRight("models/report.sql".length))
    assertTrue(requested.asScala.exists(_.contains("/ref?version=1.10")))
    configure("models/source.sql", "{{ sou<caret>rce('raw', 'events') }}")
    myFixture.performEditorAction("QuickJavaDoc")
    PlatformTestUtil.waitWithEventsDispatching("Ctrl+Q requests builtin documentation through the native API", () =>
      requested.asScala.exists(_.contains("/source?version=1.10")), 15)
  }
}
