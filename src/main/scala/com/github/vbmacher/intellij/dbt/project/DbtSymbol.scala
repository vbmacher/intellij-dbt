package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.{IconLoader, TextRange}
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiNamedElement, SmartPointerManager}
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
  /** Kinds of relation columns: declared properties and SQL projections; their `owner` is the relation name. */
  val ColumnKinds: Set[String] = Set("column", "sql_projection")
  /** Property keys listing the data tests of a resource or column; `tests` is the pre-1.8 spelling. */
  val TestKeys: Set[String] = Set("tests", "data_tests")
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
    def onlyKeys(mapping: YAMLMapping, keys: Set[String]): Boolean =
      mapping.getKeyValues.asScala.forall(k => keys(k.getKeyText))

    def hasTests(mapping: YAMLMapping): Boolean = DbtSymbol.TestKeys.exists(key => DbtYaml.value(mapping, key).exists {
      case sequence: YAMLSequence => !sequence.getItems.isEmpty
      case _ => false
    })

    val columns = DbtYaml.items(m, "columns")
    onlyKeys(m, DbtSymbol.TestKeys ++ Set("name", "columns")) &&
      columns.forall(c => onlyKeys(c, DbtSymbol.TestKeys + "name") && hasTests(c)) && (hasTests(m) || columns.nonEmpty)
  }

  def isStale: Boolean = attributes.get("stale").contains("true")

  def isEnabled: Boolean = !attributes.get("enabled").exists(_.equalsIgnoreCase("false"))

  /** The identity of the resource in dbt artifacts. */
  def uniqueId: String = attributes.getOrElse("unique_id", {
    val resource = if (kind == "source_table") "source" else kind
    val qualified = if (kind == "unit_test") mapping.flatMap(DbtYaml.string(_, "model")).map(_ + "." + name).getOrElse(relationName) else relationName
    s"$resource.$namespace.$qualified${version.map(".v" + _).getOrElse("")}"
  })

  /** The owner name of this relation's columns. */
  def relationName: String = if (kind == "source_table") s"$owner.$name" else name

  def sameResource(other: DbtSymbol): Boolean =
    kind == other.kind && namespace == other.namespace && name == other.name && version == other.version &&
      (!Set("source_table", "column")(kind) || owner == other.owner)

  def metadataSuffix: String = if (isStale) " (stale metadata)" else if (metadata) " (metadata)" else ""

  def presentableText: String = s"$namespace.$name${version.map(" v" + _).getOrElse("")}$metadataSuffix"
}

/** A source declaration with a stable identity, including declarations in plain-text Community files. */
final class DbtSymbolElement private[project](val symbol: DbtSymbol)
  extends LightElement(symbol.file.getManager, DbtSymbolElement.Language) with PsiNamedElement {
  private val pointer = SmartPointerManager.getInstance(symbol.file.getProject)
    .createSmartPsiFileRangePointer(symbol.file, symbol.range)
  private var deletion = Option.empty[com.intellij.psi.SmartPsiFileRange]
  private var insertOutputAlias = false

  private[dbt] def prepareOutputAliasRename(): Unit = {
    insertOutputAlias = true
  }

  private[dbt] def prepareSourceDeletion(range: TextRange): Unit =
    deletion = Some(SmartPointerManager.getInstance(getProject).createSmartPsiFileRangePointer(symbol.file, range))

  private def edit(change: Document => Unit): Unit = {
    val documents = PsiDocumentManager.getInstance(getProject)
    val document = documents.getDocument(symbol.file)
    change(document)
    documents.commitDocument(document)
  }

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
    } else edit { document =>
      if (insertOutputAlias || symbol.attributes.contains("insert-output-alias")) document.insertString(getTextRange.getEndOffset, " as " + name)
      else {
        val existing = document.getText(getTextRange)
        document.replaceString(getTextRange.getStartOffset, getTextRange.getEndOffset,
          DbtSymbolElement.quote(existing).fold(name)(q => s"$q$name$q"))
      }
    }
    this
  }

  override def delete(): Unit = {
    if (!isWritable) throw new IncorrectOperationException("Choose a writable source definition.")
    if (deletion.nonEmpty) deletion.flatMap(p => Option(p.getRange))
      .foreach(range => edit(_.deleteString(range.getStartOffset, range.getEndOffset)))
    else if (symbol.isCode) symbol.file.delete()
    else if (symbol.isProperty || symbol.kind == "column" && symbol.mapping.nonEmpty) symbol.mapping.foreach(m => m.getParent.delete())
    else if (symbol.kind == "macro") {
      DbtSyntax.tagged(DbtSyntax.inFile(symbol.file), "macro", getTextRange)
        .foreach { case (a, b) => edit(_.deleteString(a.range.getStartOffset, b.range.getEndOffset)) }
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
  /** The quote character enclosing the source text of an identifier, if any. */
  def quote(text: String): Option[Char] = text.headOption.filter(c => Set('"', '\'', '`')(c) && text.lastOption.contains(c))

  private val Language: Language = new Language("dbt-symbol") {}

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

/** Shared dbt mark with color-coded resource symbols; IntelliJ selects the dark SVG variant. */
private[dbt] object DbtIcons {
  private def load(name: String): Icon = IconLoader.getIcon(s"/icons/dbt/$name.svg", getClass)

  lazy val Table: Icon = load("table")
  lazy val Seed: Icon = load("seed")
  lazy val Snapshot: Icon = load("snapshot")
  lazy val Query: Icon = load("query")
  lazy val Macro: Icon = load("macro")
  lazy val Jinja: Icon = load("jinja")
  lazy val Function: Icon = load("function")
  lazy val Test: Icon = load("test")
  lazy val Check: Icon = load("check")
  lazy val Doc: Icon = load("doc")
  lazy val SemanticModel: Icon = load("semantic-model")
  lazy val Metric: Icon = load("metric")
  lazy val Alias: Icon = load("alias")
  lazy val Dimension: Icon = load("dimension")
  lazy val Entity: Icon = load("entity")
  lazy val Column: Icon = load("column")
  lazy val Export: Icon = load("export")
  lazy val Group: Icon = load("group")
  lazy val Package: Icon = load("package")
  lazy val Variable: Icon = load("variable")
  lazy val Parameter: Icon = load("parameter")
  lazy val Config: Icon = load("config")
  lazy val Property: Icon = load("property")
  lazy val Result: Icon = load("result")
  lazy val Selector: Icon = load("selector")
  lazy val Materialization: Icon = load("materialization")
  lazy val Gutter: Icon = load("gutter")
}
