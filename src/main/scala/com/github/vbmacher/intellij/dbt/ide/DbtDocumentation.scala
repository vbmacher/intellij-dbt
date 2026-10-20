package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.{DbtResourceData, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.{DocumentationResult, DocumentationTarget, DocumentationTargetProvider, DocumentationLinkHandler, LinkResolveResult}
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import scala.jdk.CollectionConverters._

/** Offset-based provider supports native modifier-free hover, even with plain-text SQL PSI. */
final class DbtDocumentationTargetProvider extends DocumentationTargetProvider {
  override def documentationTargets(file: PsiFile, offset: Int): java.util.List[_ <: DocumentationTarget] = {
    val targets = DbtNavigation.at(file, offset).flatMap(_.candidates).collect {
      case e: DbtSymbolElement if DbtResourceData.GraphKinds(e.symbol.kind) || Set("macro", "documentation", "column", "sql_projection")(e.symbol.kind) => e.symbol
    }.distinctBy(_.id)
    (if (targets.size != 1) Vector.empty else if (targets.head.kind == "documentation")
      Vector(new DbtBuiltinDocumentationTarget(targets.head)) else if (Set("column", "sql_projection")(targets.head.kind))
      DbtDocumentation.columnTarget(DbtWorkspace.get(file.getProject), targets.head).toVector
      else Vector(new DbtDocumentationTarget(file, offset, targets.head))).asJava
  }
}

private[ide] final class DbtDocumentationTarget(file: PsiFile, offset: Int, symbol: DbtSymbol) extends DocumentationTarget {
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(file, new TextRange(offset, offset),
    (fresh: PsiFile, range: TextRange) => new DbtDocumentationTargetProvider().documentationTargets(fresh, range.getStartOffset)
      .asScala.headOption.orNull)

  override def computePresentation(): TargetPresentation = TargetPresentation.builder(DbtDocumentation.displayName(symbol))
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
  def columnTarget(w: DbtWorkspace, column: DbtSymbol): Option[DocumentationTarget] = {
    val resource = w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind) && s.namespace == column.namespace &&
      s.relationName == column.owner && s.version == column.version && w.owner(s) == w.owner(column)).sortBy(s => (!s.isCode, s.metadata)).headOption
      .orElse(w.codeSymbols(column.file).find(_.kind == "model"))
    resource.map(s => new DbtColumnDocumentationTarget(s, column.name))
  }

  def show(project: com.intellij.openapi.project.Project, symbol: DbtSymbol): Unit = {
    val action = com.intellij.openapi.actionSystem.ActionManager.getInstance.getAction("QuickJavaDoc")
    val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
      .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
      .add(com.intellij.platform.ide.documentation.ActionsKt.DOCUMENTATION_TARGETS,
        java.util.List.of[DocumentationTarget](new DbtResourceDocumentationTarget(symbol))).build()
    val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(action, context, null,
      com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
    com.intellij.openapi.actionSystem.ex.ActionUtil.performAction(action, event)
  }

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
        s"<tr><td><a href=\"dbt-column:${java.net.URLEncoder.encode(c.name, java.nio.charset.StandardCharsets.UTF_8)}\">${e(c.name)}</a></td><td>${e(c.dataType)}</td><td>${e(c.description)}</td></tr>").mkString + "</table>") +
      list("Upstream", upstream) + list("Downstream", downstream) +
      s"<h3>Tests (${data.tests.size})</h3>" + data.tests.map(t => s"<p>${e(t.name)} ${e(t.column)}</p>").mkString +
      "<h3>Airflow context</h3>" + DbtAirflow.context(w, s).map(t =>
        s"<p>${e(t.dag)} / ${e(t.task)} — ${e(t.schedule)}<br>Selection: ${e(t.selection)}<br>Exclude: ${e(t.exclude)}<br>${e(t.problems.mkString("; "))}</p>").mkString +
      s"<p>Source: ${e(s.file.getVirtualFile.getPath)}</p>"
  }
}

/** Graph selections and declaration targets use stable resource identities, independently of editor references. */
private[ide] final class DbtResourceDocumentationTarget(val symbol: DbtSymbol) extends DocumentationTarget {
  override def createPointer(): Pointer[_ <: DocumentationTarget] = Pointer.fileRangePointer(symbol.file, symbol.range,
    (file: PsiFile, range: TextRange) => DbtWorkspace.get(file.getProject).symbols.find(s => s.id == symbol.id)
      .map(new DbtResourceDocumentationTarget(_)).orNull)
  override def computePresentation(): TargetPresentation = TargetPresentation.builder(DbtDocumentation.displayName(symbol))
    .icon(symbol.element.getIcon(0)).presentation()
  override def getNavigatable: com.intellij.pom.Navigatable = symbol.element
  override def computeDocumentation(): DocumentationResult = DocumentationResult.asyncDocumentation(() =>
    ReadAction.nonBlocking[DocumentationResult.Documentation](() => {
      val w = DbtWorkspace.get(symbol.file.getProject)
      w.symbols.find(_.id == symbol.id).map(s => DocumentationResult.documentation(DbtDocumentation.render(w, s))).orNull
    }).expireWith(symbol.file.getProject).executeSynchronously())
}
