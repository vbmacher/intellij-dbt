package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiNamedElement, SmartPointerManager}
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.util.IncorrectOperationException
import org.jetbrains.yaml.psi.{YAMLMapping, YAMLSequence}

import javax.swing.Icon
import scala.jdk.CollectionConverters._

/**
 * A dbt declaration. Kinds and attributes follow dbt names; notable attributes are `code` (the resource file itself),
 * `property` (a YAML properties entry), and `stale` (metadata older than the sources).
 */
private[dbt] object DbtSymbol {
  /** Kinds addressable by `ref()`. */
  val RefKinds: Set[String] = Set("model", "seed", "snapshot")
  /** Kinds materialized as warehouse relations. */
  val RelationKinds: Set[String] = RefKinds + "source_table"
}

private[dbt] final case class DbtSymbol(kind: String, name: String, namespace: String, file: PsiFile,
  range: TextRange, owner: String = "", version: Option[String] = None,
  attributes: Map[String, String] = Map.empty, mapping: Option[YAMLMapping] = None, metadata: Boolean = false) {
  def id: String = s"${file.getVirtualFile.getPath}:${range.getStartOffset}:$kind:$name:$owner:${version.getOrElse("")}"

  lazy val element: DbtSymbolElement = new DbtSymbolElement(this)

  def isCode: Boolean = attributes.contains("code")

  def isProperty: Boolean = attributes.contains("property")

  /** A properties entry dedicated to attached data tests, without other resource or column metadata. */
  def isTestOnlyProperty: Boolean = isProperty && DbtSymbol.RefKinds(kind) && mapping.exists { m =>
    val testKeys = Set("data_tests", "tests")
    def onlyKeys(mapping: YAMLMapping, keys: Set[String]): Boolean =
      mapping.getKeyValues.asScala.forall(k => keys(k.getKeyText))
    def hasTests(mapping: YAMLMapping): Boolean = testKeys.exists(key => DbtYaml.value(mapping, key).exists {
      case sequence: YAMLSequence => !sequence.getItems.isEmpty
      case _ => false
    })
    val columns = DbtYaml.items(m, "columns")
    onlyKeys(m, testKeys ++ Set("name", "columns")) &&
      columns.forall(c => onlyKeys(c, testKeys + "name") && hasTests(c)) && (hasTests(m) || columns.nonEmpty)
  }

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
    kind == other.kind && namespace == other.namespace && name == other.name && version == other.version &&
      (!Set("source_table", "column")(kind) || owner == other.owner)

  def metadataSuffix: String = if (isStale) " (stale metadata)" else if (metadata) " (metadata)" else ""

  def presentableText: String = s"$namespace.$name${version.map(" v" + _).getOrElse("")}$metadataSuffix"
}

private[project] object DbtSymbolLanguage {
  val Instance: Language = new Language("dbt-symbol") {}
}

/** A source declaration with a stable identity, including declarations in plain-text Community files. */
final class DbtSymbolElement private[project] (val symbol: DbtSymbol)
  extends LightElement(symbol.file.getManager, DbtSymbolLanguage.Instance) with PsiNamedElement {
  private val pointer = SmartPointerManager.getInstance(symbol.file.getProject)
    .createSmartPsiFileRangePointer(symbol.file, symbol.range)
  private var deletion = Option.empty[com.intellij.psi.SmartPsiFileRange]
  private var insertOutputAlias = false
  private[dbt] def prepareOutputAliasRename(): Unit = { insertOutputAlias = true }
  private[dbt] def prepareSourceDeletion(range: TextRange): Unit =
    deletion = Some(SmartPointerManager.getInstance(getProject).createSmartPsiFileRangePointer(symbol.file, range))

  override def getName: String = symbol.name

  override def getIcon(flags: Int): Icon = DbtSymbolElement.icon(symbol.kind)

  override def getPresentation: ItemPresentation = new ItemPresentation {
    override def getPresentableText: String = symbol.presentableText

    override def getLocationString: String = symbol.file.getName

    override def getIcon(unused: Boolean): Icon = DbtSymbolElement.icon(symbol.kind)
  }

  override def setName(name: String): PsiElement = {
    if (!isWritable || !DbtSyntax.isIdentifier(name)) throw new IncorrectOperationException("Choose a writable source definition and a valid dbt identifier.")
    if (symbol.isCode) {
      val extension = symbol.file.getVirtualFile.getExtension
      if (!symbol.attributes.contains("defined_in")) symbol.file.setName(name + symbol.version.map("_v" + _).getOrElse("") + "." + extension)
    } else {
      val document = PsiDocumentManager.getInstance(getProject).getDocument(symbol.file)
      if (insertOutputAlias || symbol.attributes.contains("insert-output-alias")) document.insertString(getTextRange.getEndOffset, " as " + name)
      else {
        val existing = document.getText(getTextRange)
        val quote = existing.headOption.filter(c => Set('"', '\'', '`')(c) && existing.lastOption.contains(c))
        document.replaceString(getTextRange.getStartOffset, getTextRange.getEndOffset, quote.fold(name)(q => s"$q$name$q"))
      }
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    }
    this
  }

  override def delete(): Unit = {
    if (!isWritable) throw new IncorrectOperationException("Choose a writable source definition.")
    if (deletion.nonEmpty) deletion.flatMap(p => Option(p.getRange)).foreach { range =>
      val document = PsiDocumentManager.getInstance(getProject).getDocument(symbol.file)
      document.deleteString(range.getStartOffset, range.getEndOffset)
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    }
    else if (symbol.isCode) symbol.file.delete()
    else if (symbol.isProperty || symbol.kind == "column" && symbol.mapping.nonEmpty) symbol.mapping.foreach(m => m.getParent.delete())
    else if (symbol.kind == "macro") {
      val blocks = DbtSyntax.inFile(symbol.file)
      val start = blocks.find(b => b.isTag("macro") && b.range.contains(getTextRange))
      val end = start.flatMap(s => blocks.find(b => b.isTag("endmacro") && b.range.getStartOffset > s.range.getStartOffset))
      (for (a <- start; b <- end) yield (a, b)).foreach { case (a, b) =>
        val document = PsiDocumentManager.getInstance(getProject).getDocument(symbol.file)
        document.deleteString(a.range.getStartOffset, b.range.getEndOffset)
        PsiDocumentManager.getInstance(getProject).commitDocument(document)
      }
    } else throw new IncorrectOperationException("Safe deletion of this SQL definition requires an explicit projection edit.")
  }

  override def getContainingFile: PsiFile = symbol.file

  override def getTextRange: TextRange =
    Option(pointer.getRange).map(r => new TextRange(r.getStartOffset, r.getEndOffset)).getOrElse(TextRange.EMPTY_RANGE)

  override def getTextOffset: Int = getTextRange.getStartOffset

  override def getText: String = symbol.name

  override def getParent: PsiElement = symbol.file

  override def isValid: Boolean = symbol.file.isValid && pointer.getRange != null

  override def isWritable: Boolean = !symbol.metadata && symbol.file.isWritable &&
    DbtWorkspace.get(getProject).owner(symbol).exists(r => DbtWorkspace.get(getProject).packageOwner(r).isEmpty)

  override def navigate(requestFocus: Boolean): Unit =
    if (symbol.kind == "documentation") getProject.getService(classOf[DbtDocumentationNavigation]).show(this)
    else new OpenFileDescriptor(getProject, symbol.file.getVirtualFile, getTextOffset).navigate(requestFocus)

  override def canNavigate: Boolean = isValid

  override def canNavigateToSource: Boolean = isValid && !symbol.metadata

  override def toString: String = s"dbt ${symbol.kind}: ${symbol.namespace}.${symbol.name}${symbol.metadataSuffix}"

  override def equals(other: Any): Boolean = other match {
    case e: DbtSymbolElement => symbol.id == e.symbol.id
    case _ => false
  }

  override def hashCode(): Int = symbol.id.hashCode
}

/** The IDE supplies the presentation of builtin reference documentation. */
private[dbt] trait DbtDocumentationNavigation {
  def show(element: DbtSymbolElement): Unit
}

private[dbt] object DbtSymbolElement {
  /** Shared by every presentation of a dbt declaration. */
  def icon(kind: String): Icon = kind match {
    case "model" | "source" | "source_table" | "information_schema_view" | "cte" => DbtIcons.Table
    case "seed" => DbtIcons.Seed
    case "snapshot" => DbtIcons.Snapshot
    case "analysis" | "saved_query" => DbtIcons.Query
    case "macro" => DbtIcons.Macro
    case "builtin" => DbtIcons.Jinja
    case "function" => DbtIcons.Function
    case "test" | "generic_test" | "unit_test" | "fixture" => DbtIcons.Test
    case "check" => DbtIcons.Check
    case "doc" | "documentation" => DbtIcons.Doc
    case "semantic_model" => DbtIcons.SemanticModel
    case "metric" | "measure" => DbtIcons.Metric
    case "metric_alias" | "sql_alias" => DbtIcons.Alias
    case "dimension" => DbtIcons.Dimension
    case "entity" => DbtIcons.Entity
    case "column" | "sql_projection" | "information_schema_column" => DbtIcons.Column
    case "exposure" | "export" => DbtIcons.Export
    case "group" => DbtIcons.Group
    case "package" | "project" => DbtIcons.Package
    case "var" | "variable" | "local" | "environment" => DbtIcons.Variable
    case "parameter" | "argument" => DbtIcons.Parameter
    case "config" | "configuration" | "profile" | "target" | "env" => DbtIcons.Config
    case "result" => DbtIcons.Result
    case "selector" => DbtIcons.Selector
    case "materialization" => DbtIcons.Materialization
    case _ => DbtIcons.Property
  }
}
