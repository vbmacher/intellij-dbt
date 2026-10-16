package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtDocumentationNavigation, DbtSymbol, DbtSymbolElement}
import com.intellij.lang.documentation.ide.impl.DocumentationToolWindowManager
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation._
import com.intellij.platform.backend.documentation.impl.{DocumentationRequest, ImplKt}
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.HttpRequests
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.URI
import scala.util.Try

private[ide] trait DbtBuiltinDocumentationSource {
  def html(url: String): String
}

/** Network work happens only in the documentation target's asynchronous computation. */
final class DbtBuiltinDocumentationLoader extends DbtBuiltinDocumentationSource {
  private val pages = ContainerUtil.createConcurrentSoftValueMap[String, String]()
  override def html(url: String): String = pages.computeIfAbsent(url.takeWhile(_ != '#'), address => {
    val page = HttpRequests.request(address).productNameAsUserAgent().connectTimeout(5000).readTimeout(5000).readString()
    DbtBuiltinDocumentationLoader.article(page, address)
  })
}

private[ide] object DbtBuiltinDocumentationLoader {
  def article(page: String, url: String): String = {
    val document = Jsoup.parse(page, url)
    val article = Option(document.selectFirst("article .markdown")).orElse(Option(document.selectFirst("article")))
      .getOrElse(throw new IOException("The dbt reference page did not contain an article."))
    article.select("script, style, nav, button, svg, iframe, form, input, .hash-link").remove()
    val tags = Safelist.basic().addTags("h1", "h2", "h3", "h4", "table", "thead", "tbody", "tr", "th", "td", "pre", "hr")
      .addAttributes(":all", "id")
    Jsoup.clean(article.html(), url, tags)
  }

  def referenceUrl(base: String, link: String): Option[String] = Try(new URI(base).resolve(link)).toOption
    .filter(u => u.getScheme == "https" && u.getHost == "docs.getdbt.com" && u.getUserInfo == null)
    .map { uri =>
      val version = Option(new URI(base).getRawQuery).filter(_.startsWith("version="))
      if (uri.getRawQuery == null && version.nonEmpty)
        new URI(uri.getScheme, uri.getAuthority, uri.getPath, version.orNull, uri.getFragment).toString
      else uri.toString
    }
}

private[ide] final class DbtBuiltinDocumentationTarget(val symbol: DbtSymbol) extends DocumentationTarget {
  val url: String = symbol.attributes("url")
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(symbol.file, symbol.range,
    (fresh: PsiFile, range: com.intellij.openapi.util.TextRange) =>
      new DbtBuiltinDocumentationTarget(symbol.copy(file = fresh, range = range)))
  override def computePresentation(): TargetPresentation = TargetPresentation.builder(s"dbt.${symbol.name}")
    .icon(symbol.element.getIcon(0)).presentation()
  override def computeDocumentation(): DocumentationResult = DocumentationResult.asyncDocumentation(() => {
    val title = s"<div class='definition'><b>dbt.${DbtDocumentation.escape(symbol.name)}</b></div>"
    val content = try symbol.file.getProject.getService(classOf[DbtBuiltinDocumentationSource]).html(url)
      catch { case error: IOException =>
        "<p>Unable to load the dbt reference documentation. Check your connection and invoke Quick Documentation again.</p><p>" +
          DbtDocumentation.escape(Option(error.getMessage).getOrElse("Network error")) + "</p>"
      }
    val result = DocumentationResult.documentation(title + "<div class='content'>" + content + "</div>").externalUrl(url)
    Option(new URI(url).getFragment).fold(result)(result.anchor)
  })
}

final class DbtBuiltinPsiDocumentationTargetProvider extends PsiDocumentationTargetProvider {
  override def documentationTarget(element: PsiElement, originalElement: PsiElement): DocumentationTarget = element match {
    case e: DbtSymbolElement if e.symbol.kind == "documentation" => new DbtBuiltinDocumentationTarget(e.symbol)
    case _ => null
  }
}

final class DbtBuiltinDocumentationLinkHandler extends DocumentationLinkHandler {
  override def resolveLink(target: DocumentationTarget, url: String): LinkResolveResult = target match {
    case doc: DbtBuiltinDocumentationTarget => DbtBuiltinDocumentationLoader.referenceUrl(doc.url, url).map { address =>
      val name = Option(new URI(address).getPath).getOrElse("").split('/').lastOption.getOrElse(doc.symbol.name)
      LinkResolveResult.resolvedTarget(new DbtBuiltinDocumentationTarget(doc.symbol.copy(name = name,
        attributes = doc.symbol.attributes.updated("url", address))))
    }.orNull
    case _ => null
  }
}

final class DbtBuiltinDocumentationNavigation(project: Project) extends DbtDocumentationNavigation {
  override def show(element: DbtSymbolElement): Unit = {
    val request = ReadAction.computeBlocking[DocumentationRequest, RuntimeException](() =>
      ImplKt.documentationRequest(new DbtBuiltinDocumentationTarget(element.symbol)))
    DocumentationToolWindowManager.Companion.getInstance(project).showInToolWindow(java.util.List.of(request))
  }
}
