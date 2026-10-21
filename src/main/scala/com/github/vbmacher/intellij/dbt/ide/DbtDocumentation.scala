package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.model.Pointer
import com.intellij.openapi.actionSystem.{ActionManager, ActionPlaces, ActionUiKind, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation._
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.ide.documentation.ActionsKt
import com.intellij.pom.Navigatable
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.HttpRequests
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.{URI, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Offset-based provider supports native modifier-free hover, even with plain-text SQL PSI. */
final class DbtDocumentationTargetProvider extends DocumentationTargetProvider {
  override def documentationTargets(file: PsiFile, offset: Int): java.util.List[_ <: DocumentationTarget] = {
    val targets = DbtNavigation.at(file, offset).flatMap(_.candidates).collect {
      case e: DbtSymbolElement if DbtResourceData.GraphKinds(e.symbol.kind) || Set("macro", "documentation")(e.symbol.kind) ||
        DbtHierarchy.ColumnKinds(e.symbol.kind) => e.symbol
    }.distinctBy(_.id)
    (if (targets.size != 1) Vector.empty
      else DbtDocumentation.target(file.getProject, targets.head)(new DbtDocumentationTarget(file, offset, _)).toVector).asJava
  }
}

/** Documentation of a dbt resource or macro. */
private[ide] sealed abstract class DbtSymbolDocumentationTarget(val symbol: DbtSymbol) extends DocumentationTarget {
  override def computePresentation(): TargetPresentation = TargetPresentation.builder(DbtDocumentation.displayName(symbol))
    .icon(symbol.element.getIcon(0)).presentation()

  override def getNavigatable: Navigatable = symbol.element
}

private[ide] final class DbtDocumentationTarget(file: PsiFile, offset: Int, resolved: DbtSymbol)
  extends DbtSymbolDocumentationTarget(resolved) {
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(file, new TextRange(offset, offset),
    (fresh: PsiFile, range: TextRange) => DbtDocumentation.targetAt(fresh, range.getStartOffset).orNull)

  override def computeDocumentation(): DocumentationResult = DbtDocumentation.compute(file.getProject)(
    DbtDocumentation.targetAt(file, offset).collect { case target: DbtDocumentationTarget =>
      DbtDocumentation.render(DbtWorkspace.get(file.getProject), target.symbol)
    })
}

/** Graph selections and declaration targets use stable resource identities, independently of editor references. */
private[ide] final class DbtResourceDocumentationTarget(resource: DbtSymbol) extends DbtSymbolDocumentationTarget(resource) {
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(symbol.file, symbol.range,
    (file: PsiFile, _: TextRange) => DbtWorkspace.get(file.getProject).symbols.find(s => s.id == symbol.id)
      .map(new DbtResourceDocumentationTarget(_)).orNull)

  override def computeDocumentation(): DocumentationResult = DbtDocumentation.compute(symbol.file.getProject) {
    val w = DbtWorkspace.get(symbol.file.getProject)
    w.symbols.find(_.id == symbol.id).map(DbtDocumentation.render(w, _))
  }
}

private[ide] object DbtDocumentation {
  /** The documentation target of a resolved symbol; `resource` documents resources and macros. */
  def target(project: Project, symbol: DbtSymbol)(resource: DbtSymbol => DocumentationTarget): Option[DocumentationTarget] =
    if (symbol.kind == "documentation") Some(new DbtBuiltinDocumentationTarget(symbol))
    else if (DbtHierarchy.ColumnKinds(symbol.kind)) columnTarget(DbtWorkspace.get(project), symbol)
    else Some(resource(symbol))

  /** The documentation target of the reference at an offset. */
  def targetAt(file: PsiFile, offset: Int): Option[DocumentationTarget] =
    new DbtDocumentationTargetProvider().documentationTargets(file, offset).asScala.headOption

  /** Renders documentation HTML in a cancellable read action, off the EDT. */
  def compute(project: Project)(html: => Option[String]): DocumentationResult = DocumentationResult.asyncDocumentation(() =>
    ReadAction.nonBlocking[DocumentationResult.Documentation](() => html.map(DocumentationResult.documentation).orNull)
      .expireWith(project).executeSynchronously())

  /** Opens Quick Documentation for a target. */
  def show(project: Project, target: DocumentationTarget): Unit = {
    val action = ActionManager.getInstance.getAction("QuickJavaDoc")
    val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
      .add(ActionsKt.DOCUMENTATION_TARGETS, java.util.List.of[DocumentationTarget](target)).build()
    ActionUtil.performAction(action, AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null))
  }

  def columnTarget(w: DbtWorkspace, column: DbtSymbol): Option[DocumentationTarget] = {
    val resource = w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind) && s.namespace == column.namespace &&
      s.relationName == column.owner && s.version == column.version && w.owner(s) == w.owner(column)).sortBy(s => (!s.isCode, s.metadata)).headOption
      .orElse(w.codeSymbols(column.file).find(_.kind == "model"))
    resource.map(s => new DbtColumnDocumentationTarget(s, column.name))
  }

  def show(project: Project, symbol: DbtSymbol): Unit = show(project, new DbtResourceDocumentationTarget(symbol))

  def displayName(s: DbtSymbol): String =
    s"${s.namespace}.${s.relationName}${s.version.map(" v" + _).getOrElse("")}${s.metadataSuffix}"

  /** Documentation follows resource and macro dependencies, not navigation to config keys or source groups. */
  def dependencies(w: DbtWorkspace, s: DbtSymbol): (Vector[String], Vector[String]) = {
    def documented(symbol: DbtSymbol): Boolean = DbtResourceData.GraphKinds(symbol.kind) || symbol.kind == "macro"
    val edges = DbtLineage.edges(w).filter(e => documented(e.from) && documented(e.to))
    def same(a: DbtSymbol): Boolean = w.owner(a) == w.owner(s) && a.uniqueId == s.uniqueId
    edges.filter(e => same(e.from)).map(e => displayName(e.to)).distinct ->
      edges.filter(e => same(e.to)).map(e => displayName(e.from)).distinct
  }

  def escape(text: String): String = text.flatMap {
    case '&' => "&amp;"
    case '<' => "&lt;"
    case '>' => "&gt;"
    case '"' => "&quot;"
    case '\'' => "&#39;"
    case c => c.toString
  }

  def render(w: DbtWorkspace, s: DbtSymbol): String = {
    val data = DbtResourceData.get(w, s)
    val (upstream, downstream) = dependencies(w, s)
    def e(value: String): String = escape(value)
    def list(label: String, items: Vector[String]): String =
      s"<h3>$label (${items.size})</h3>" + (if (items.isEmpty) "<p>None recorded</p>" else "<ul>" + items.map(x => s"<li>${e(x)}</li>").mkString + "</ul>")
    val relation = data.attributes.get("relation_name").orElse(data.attributes.get("identifier")).getOrElse("")
    val metadata = Vector("Package" -> s.namespace) ++ s.version.map("Version" -> _) ++
      Vector("Database" -> "database", "Schema" -> "schema", "Materialization" -> "materialized").flatMap { case (label, key) =>
        data.attributes.get(key).filter(_.nonEmpty).map(label -> _)
      } ++ Option(relation).filter(_.nonEmpty).map("Relation" -> _)
    val signature = if (s.kind == "macro") {
      val names = if (data.arguments.nonEmpty) data.arguments.map(_.name) else w.symbols.filter(p => p.kind == "parameter" && p.owner == s.name && p.file == s.file).map(_.name)
      s"<pre>${e(s.name + names.mkString("(", ", ", ")"))}</pre>" + data.arguments.map(a => s"<p>${e(a.name)}: ${e(a.dataType)} ${e(a.description)}</p>").mkString
    } else ""
    s"<div class='definition'><b>${e(displayName(s))}</b> — ${e(s.kind)}</div>" + signature +
      "<table>" + metadata.map { case (label, value) => s"<tr><td>$label</td><td>${e(value)}</td></tr>" }.mkString + "</table>" +
      s"<div class='content'>${e(data.description).replace("\n", "<br>")}</div>" +
      s"<p>Tags: ${e(data.tags.mkString(", "))}</p><h3>Columns (${data.columns.size})</h3>" +
      (if (data.columns.isEmpty) "<p>No columns documented</p>" else "<table>" + data.columns.map(c =>
        s"<tr><td><a href=\"dbt-column:${URLEncoder.encode(c.name, StandardCharsets.UTF_8)}\">${e(c.name)}</a></td><td>${e(c.dataType)}</td><td>${e(c.description)}</td></tr>").mkString + "</table>") +
      list("Upstream", upstream) + list("Downstream", downstream) +
      s"<h3>Tests (${data.tests.size})</h3>" + data.tests.map(t => s"<p>${e(t.name)} ${e(t.column)}</p>").mkString +
      "<h3>Airflow context</h3>" + DbtAirflow.context(w, s).map(t =>
        s"<p>${e(t.dag)} / ${e(t.task)} — ${e(t.schedule)}<br>Selection: ${e(t.selection)}<br>Exclude: ${e(t.exclude)}<br>${e(t.problems.mkString("; "))}</p>").mkString +
      s"<p>Source: ${e(s.file.getVirtualFile.getPath)}</p>"
  }
}

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
    (fresh: PsiFile, range: TextRange) =>
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
    case e: DbtSymbolElement => DbtDocumentation.target(e.getProject, e.symbol)(new DbtResourceDocumentationTarget(_)).orNull
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
  override def show(element: DbtSymbolElement): Unit = DbtDocumentation.show(project, new DbtBuiltinDocumentationTarget(element.symbol))
}

final class DbtColumnDocumentationLinkHandler extends DocumentationLinkHandler {
  override def resolveLink(target: DocumentationTarget, url: String): LinkResolveResult = {
    val resource = target match {
      case doc: DbtSymbolDocumentationTarget => Some(doc.symbol)
      case _ => None
    }
    if (!url.startsWith("dbt-column:")) null else resource.flatMap { symbol =>
      Try(URLDecoder.decode(url.stripPrefix("dbt-column:"), StandardCharsets.UTF_8)).toOption
        .filter(name => DbtResourceData.get(DbtWorkspace.get(symbol.file.getProject), symbol).columns.exists(_.name == name))
        .map(name => LinkResolveResult.resolvedTarget(new DbtColumnDocumentationTarget(symbol, name)))
    }.orNull
  }
}
private[ide] final class DbtColumnDocumentationTarget(resource: DbtSymbol, name: String) extends DocumentationTarget {
  private def current(w: DbtWorkspace): Option[DbtSymbol] = w.symbols.find(s => s.file == resource.file && s.sameResource(resource))
  private def column(w: DbtWorkspace, model: DbtSymbol): Option[DbtSymbol] = w.columns(model).find(_.name == name).orElse(
    DbtSqlAnalysis.get(w, model.file).flatMap(_.columns.find(_.name == name)).map(c =>
      DbtSymbol("sql_projection", name, model.namespace, model.file, c.range, owner = model.name)))
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(resource.file, resource.range,
    (file: PsiFile, range: TextRange) => current(DbtWorkspace.get(file.getProject))
      .map(s => new DbtColumnDocumentationTarget(s, name)).orNull)
  override def computePresentation(): TargetPresentation = TargetPresentation.builder(resource.name + "." + name).presentation()
  override def getNavigatable: Navigatable = {
    val w = DbtWorkspace.get(resource.file.getProject)
    current(w).flatMap(column(w, _)).map(_.element).orNull
  }
  override def computeDocumentation(): DocumentationResult = DbtDocumentation.compute(resource.file.getProject) {
    val w = DbtWorkspace.get(resource.file.getProject)
    current(w).map { model =>
      def e(text: String): String = DbtDocumentation.escape(text)
      val data = DbtResourceData.get(w, model).columns.find(_.name == name)
      def related(downstream: Boolean): String = column(w, model).toVector.flatMap(DbtHierarchy.related(w, _, downstream)).map {
        case (symbol, kind) => s"<li>${e(symbol.namespace)}.${e(symbol.owner)}.${e(symbol.name)} — ${e(kind)}</li>"
      }.mkString("<ul>", "", "</ul>")
      val origin = DbtPropertiesEditor.origins(w, model, name)._1
      s"<div class='definition'><b>${e(model.name)}.${e(name)}</b></div><p>${e(origin)}</p>" +
        data.map(c => s"<p>${e(c.dataType)}</p><p>${e(c.description)}</p>").getOrElse("") +
        "<h3>Upstream dependencies</h3>" + related(false) + "<h3>Downstream consumers</h3>" + related(true)
    }
  }
}
