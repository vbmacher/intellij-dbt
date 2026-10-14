package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.{DbtResourceData, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.{DocumentationResult, DocumentationTarget, DocumentationTargetProvider}
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import scala.jdk.CollectionConverters._

/** Offset-based provider supports native modifier-free hover, even with plain-text SQL PSI. */
final class DbtDocumentationTargetProvider extends DocumentationTargetProvider {
  override def documentationTargets(file: PsiFile, offset: Int): java.util.List[_ <: DocumentationTarget] = {
    val targets = DbtNavigation.at(file, offset).flatMap(_.candidates).collect {
      case e: DbtSymbolElement if DbtResourceData.GraphKinds(e.symbol.kind) || e.symbol.kind == "macro" => e.symbol
    }.distinctBy(_.id)
    (if (targets.size == 1) Vector(new DbtDocumentationTarget(file, offset, targets.head)) else Vector.empty).asJava
  }
}

private[ide] final class DbtDocumentationTarget(file: PsiFile, offset: Int, symbol: DbtSymbol) extends DocumentationTarget {
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(file, new TextRange(offset, offset),
    (fresh: PsiFile, range: TextRange) => new DbtDocumentationTargetProvider().documentationTargets(fresh, range.getStartOffset)
      .asScala.headOption.orNull)

  override def computePresentation(): TargetPresentation = TargetPresentation.builder(symbol.presentableText)
    .icon(symbol.element.getIcon(0)).presentation()

  override def getNavigatable: com.intellij.pom.Navigatable = symbol.element

  override def computeDocumentation(): DocumentationResult = DocumentationResult.asyncDocumentation(() =>
    ReadAction.nonBlocking[DocumentationResult.Documentation](() => {
      val fresh = new DbtDocumentationTargetProvider().documentationTargets(file, offset).asScala.headOption
        .collect { case target: DbtDocumentationTarget => target.symbolValue }
      fresh.map(s => DocumentationResult.documentation(DbtDocumentation.render(DbtWorkspace.get(file.getProject), s))).orNull
    }).expireWith(file.getProject).executeSynchronously())

  private[ide] def symbolValue: DbtSymbol = symbol
}

private[ide] object DbtDocumentation {
  def escape(text: String): String = text.flatMap {
    case '&' => "&amp;"
    case '<' => "&lt;"
    case '>' => "&gt;"
    case '"' => "&quot;"
    case '\'' => "&#39;"
    case c => c.toString
  }

  def render(w: DbtWorkspace, s: DbtSymbol, bounded: Boolean = true): String = {
    val data = DbtResourceData.get(w, s)
    val edges = DbtLineage.edges(w)
    def same(a: DbtSymbol): Boolean = w.owner(a) == w.owner(s) && a.uniqueId == s.uniqueId
    val upstream = edges.filter(e => same(e.from)).map(_.to.presentableText).distinct
    val downstream = edges.filter(e => same(e.to)).map(_.from.presentableText).distinct
    def e(value: String): String = escape(value)
    def list(label: String, items: Vector[String], limit: Int): String =
      s"<h3>$label (${items.size})</h3>" + (if (items.isEmpty) "<p>None recorded</p>" else "<ul>" + items.take(limit).map(x => s"<li>${e(x)}</li>").mkString + "</ul>") +
        (if (items.size > limit) s"<p>${items.size - limit} more; open the Lineage documentation sidebar.</p>" else "")
    val columnLimit = if (bounded) 15 else Int.MaxValue
    val dependencyLimit = if (bounded) 8 else Int.MaxValue
    val relation = data.attributes.get("relation_name").orElse(data.attributes.get("identifier")).getOrElse("")
    val signature = if (s.kind == "macro") {
      val names = if (data.arguments.nonEmpty) data.arguments.map(_.name) else w.symbols.filter(p => p.kind == "parameter" && p.owner == s.name && p.file == s.file).map(_.name)
      s"<pre>${e(s.name + names.mkString("(", ", ", ")"))}</pre>" + data.arguments.map(a => s"<p>${e(a.name)}: ${e(a.dataType)} ${e(a.description)}</p>").mkString
    } else ""
    s"<div class='definition'><b>${e(s.presentableText)}</b> — ${e(s.kind)}</div>" + signature +
      s"<p>${e(data.attributes.getOrElse("materialized", ""))} ${e(relation)}</p><div class='content'>${e(data.description).replace("\n", "<br>")}</div>" +
      s"<p>Tags: ${e(data.tags.mkString(", "))}</p><h3>Columns (${data.columns.size})</h3>" +
      (if (data.columns.isEmpty) "<p>No columns documented</p>" else "<table>" + data.columns.take(columnLimit).map(c =>
        s"<tr><td><b>${e(c.name)}</b></td><td>${e(c.dataType)}</td><td>${e(c.description)}</td></tr>").mkString + "</table>") +
      (if (data.columns.size > columnLimit) s"<p>${data.columns.size - columnLimit} more columns; open the Lineage documentation sidebar.</p>" else "") +
      list("Upstream", upstream, dependencyLimit) + list("Downstream", downstream, dependencyLimit) +
      s"<p>Source: ${e(s.file.getVirtualFile.getPath)}</p>"
  }
}
