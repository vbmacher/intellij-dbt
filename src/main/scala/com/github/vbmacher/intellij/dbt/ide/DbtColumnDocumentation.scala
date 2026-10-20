package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.platform.backend.documentation._
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.openapi.util.TextRange

final class DbtColumnDocumentationLinkHandler extends DocumentationLinkHandler {
  override def resolveLink(target: DocumentationTarget, url: String): LinkResolveResult = {
    val resource = target match {
      case doc: DbtResourceDocumentationTarget => Some(doc.symbol)
      case doc: DbtDocumentationTarget => Some(doc.symbolValue)
      case _ => None
    }
    if (!url.startsWith("dbt-column:")) null else resource.flatMap { symbol =>
      scala.util.Try(java.net.URLDecoder.decode(url.stripPrefix("dbt-column:"), java.nio.charset.StandardCharsets.UTF_8)).toOption
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
  override def getNavigatable: com.intellij.pom.Navigatable = {
    val w = DbtWorkspace.get(resource.file.getProject)
    current(w).flatMap(column(w, _)).map(_.element).orNull
  }
  override def computeDocumentation(): DocumentationResult = DocumentationResult.asyncDocumentation(() =>
    ReadAction.nonBlocking[DocumentationResult.Documentation](() => {
      val w = DbtWorkspace.get(resource.file.getProject)
      current(w).map { model =>
        def e(text: String): String = DbtDocumentation.escape(text)
        val data = DbtResourceData.get(w, model).columns.find(_.name == name)
        def related(downstream: Boolean): String = column(w, model).toVector.flatMap(DbtHierarchy.related(w, _, downstream)).map {
          case (symbol, kind) => s"<li>${e(symbol.namespace)}.${e(symbol.owner)}.${e(symbol.name)} — ${e(kind)}</li>"
        }.mkString("<ul>", "", "</ul>")
        val origin = DbtPropertiesEditor.origins(w, model, name)._1
        DocumentationResult.documentation(s"<div class='definition'><b>${e(model.name)}.${e(name)}</b></div><p>${e(origin)}</p>" +
          data.map(c => s"<p>${e(c.dataType)}</p><p>${e(c.description)}</p>").getOrElse("") +
          "<h3>Upstream dependencies</h3>" + related(false) + "<h3>Downstream consumers</h3>" + related(true))
      }.orNull
    }).expireWith(resource.file.getProject).executeSynchronously())
}
