package com.github.vbmacher.intellij.dbt.project

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.{PsiElement, PsiFile, PsiNamedElement, SmartPointerManager}
import org.jetbrains.yaml.psi.YAMLMapping

import javax.swing.Icon

/**
 * A dbt declaration. Kinds and attributes follow dbt names; notable attributes are `code` (the resource file itself),
 * `property` (a YAML properties entry), and `stale` (metadata older than the sources).
 */
private[dbt] final case class DbtSymbol(kind: String, name: String, namespace: String, file: PsiFile,
  range: TextRange, owner: String = "", version: Option[String] = None,
  attributes: Map[String, String] = Map.empty, mapping: Option[YAMLMapping] = None, metadata: Boolean = false) {
  def id: String = s"${file.getVirtualFile.getPath}:${range.getStartOffset}:$kind:$name:$owner:${version.getOrElse("")}"

  lazy val element: DbtSymbolElement = new DbtSymbolElement(this)

  def isCode: Boolean = attributes.contains("code")

  def isProperty: Boolean = attributes.contains("property")

  def isStale: Boolean = attributes.get("stale").contains("true")

  def isEnabled: Boolean = !attributes.get("enabled").exists(_.equalsIgnoreCase("false"))

  /** The identity of the resource in dbt artifacts. */
  def uniqueId: String = attributes.getOrElse("unique_id", {
    val resource = if (kind == "source_table") "source" else kind
    s"$resource.$namespace.$relationName${version.map(".v" + _).getOrElse("")}"
  })

  /** The owner name of this relation's columns. */
  def relationName: String = if (kind == "source_table") s"$owner.$name" else name

  def sameResource(other: DbtSymbol): Boolean =
    kind == other.kind && namespace == other.namespace && name == other.name && version == other.version

  def metadataSuffix: String = if (isStale) " (stale metadata)" else if (metadata) " (metadata)" else ""

  def presentableText: String = s"$namespace.$name${version.map(" v" + _).getOrElse("")}$metadataSuffix"
}

private[project] object DbtSymbolLanguage extends Language("dbt-symbol")

/** A source declaration with a stable identity, including declarations in plain-text Community files. */
final class DbtSymbolElement private[project] (val symbol: DbtSymbol)
  extends LightElement(symbol.file.getManager, DbtSymbolLanguage) with PsiNamedElement {
  private val pointer = SmartPointerManager.getInstance(symbol.file.getProject)
    .createSmartPsiFileRangePointer(symbol.file, symbol.range)

  override def getName: String = symbol.name

  override def getIcon(flags: Int): Icon = DbtSymbolElement.icon(symbol.kind)

  override def getPresentation: ItemPresentation = new ItemPresentation {
    override def getPresentableText: String = symbol.presentableText

    override def getLocationString: String = symbol.file.getName

    override def getIcon(unused: Boolean): Icon = DbtSymbolElement.icon(symbol.kind)
  }

  override def setName(name: String): PsiElement = throw new UnsupportedOperationException("dbt rename is not supported")

  override def getContainingFile: PsiFile = symbol.file

  override def getTextRange: TextRange =
    Option(pointer.getRange).map(r => new TextRange(r.getStartOffset, r.getEndOffset)).getOrElse(TextRange.EMPTY_RANGE)

  override def getTextOffset: Int = getTextRange.getStartOffset

  override def getText: String = symbol.name

  override def getParent: PsiElement = symbol.file

  override def isValid: Boolean = symbol.file.isValid && pointer.getRange != null

  override def isWritable: Boolean = false

  override def navigate(requestFocus: Boolean): Unit = symbol.attributes.get("url") match {
    case Some(url) => BrowserUtil.browse(url)
    case None => new OpenFileDescriptor(getProject, symbol.file.getVirtualFile, getTextOffset).navigate(requestFocus)
  }

  override def canNavigate: Boolean = isValid

  override def canNavigateToSource: Boolean = isValid && !symbol.metadata

  override def toString: String = s"dbt ${symbol.kind}: ${symbol.namespace}.${symbol.name}${symbol.metadataSuffix}"

  override def equals(other: Any): Boolean = other match {
    case e: DbtSymbolElement => symbol.id == e.symbol.id
    case _ => false
  }

  override def hashCode(): Int = symbol.id.hashCode
}

private[project] object DbtSymbolElement {
  /** Shared by every presentation of a dbt declaration. */
  def icon(kind: String): Icon = kind match {
    case "model" => AllIcons.Nodes.ModelClass
    case "source" => AllIcons.Nodes.DataSchema
    case "source_table" | "information_schema_view" | "cte" => AllIcons.Nodes.DataTables
    case "seed" => AllIcons.FileTypes.Csv
    case "fixture" => AllIcons.FileTypes.Text
    case "snapshot" => AllIcons.General.History
    case "analysis" | "saved_query" => AllIcons.FileTypes.Sql
    case "macro" | "builtin" => AllIcons.Nodes.Method
    case "function" => AllIcons.Nodes.Function
    case "test" | "generic_test" => AllIcons.Nodes.Test
    case "unit_test" => AllIcons.Nodes.TestGroup
    case "check" => AllIcons.General.InspectionsOK
    case "doc" | "documentation" => AllIcons.FileTypes.Markdown
    case "semantic_model" => AllIcons.Nodes.Models
    case "metric" | "measure" => AllIcons.Nodes.Field
    case "metric_alias" | "sql_alias" => AllIcons.Nodes.Alias
    case "dimension" => AllIcons.Nodes.Type
    case "entity" => AllIcons.Nodes.Class
    case "column" | "sql_projection" | "information_schema_column" => AllIcons.Nodes.DataColumn
    case "exposure" | "export" => AllIcons.Nodes.Deploy
    case "group" => AllIcons.Nodes.ModuleGroup
    case "package" | "project" => AllIcons.Nodes.Package
    case "var" | "variable" | "local" | "environment" => AllIcons.Nodes.Variable
    case "parameter" | "argument" => AllIcons.Nodes.Parameter
    case "config" | "configuration" | "profile" | "target" | "env" => AllIcons.FileTypes.Config
    case "result" => AllIcons.Nodes.Record
    case "selector" => AllIcons.General.Filter
    case "materialization" => AllIcons.Nodes.Artifact
    case _ => AllIcons.Nodes.Property
  }
}
