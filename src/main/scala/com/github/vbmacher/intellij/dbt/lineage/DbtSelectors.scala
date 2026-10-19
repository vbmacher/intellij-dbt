package com.github.vbmacher.intellij.dbt.lineage

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider}
import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiRecursiveElementWalkingVisitor}
import org.jetbrains.yaml.psi._

import java.nio.file.{FileSystems, Paths}
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/** Node selection expressions of `selectors.yml` and the configured selection files. */
private[lineage] final class DbtSelectorNavigation extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] =
    DbtSelectors.references(context.file, context.workspace, context.root, context.includeSelections)
}

/** dbt node selection, including graph operators. */
private[dbt] object DbtSelectors {
  private val SelectableKinds = Set("model", "seed", "snapshot", "source_table", "test", "generic_test", "analysis", "exposure", "metric",
    "saved_query", "function", "check", "group", "unit_test", "semantic_model")
  private val MethodKeys = Set("fqn", "path", "package", "source", "tag", "group", "exposure", "metric", "saved_query", "test", "function",
    "check", "resource_type")

  def selectable(symbol: DbtSymbol): Boolean = SelectableKinds(symbol.kind) && symbol.isEnabled

  private def glob(pattern: String, value: String): Boolean =
    Try(FileSystems.getDefault.getPathMatcher("glob:" + pattern).matches(Paths.get(value))).getOrElse(false)

  def select(w: DbtWorkspace, root: DbtRoot, expression: String, includeGraph: Boolean): Vector[PsiElement] = {
    val prefix = "^[0-9]*\\+".r.findFirstIn(expression)
    val suffix = "\\+[0-9]*$".r.findFirstIn(expression)
    val core = expression.drop(prefix.map(_.length).getOrElse(0)).dropRight(suffix.map(_.length).getOrElse(0))
    val split = core.split(":", 2)
    val method = if (split.length == 2) split.head else "fqn"
    val value = split.last
    if (method == "selector") return if (root.supports(12)) w.symbols.filter(s => s.kind == "selector" &&
      s.name == value && w.root(s.file).contains(root)).map(_.element) else Vector.empty
    val candidates = w.symbols.filter(s => selectable(s) &&
      w.declarations(Set(s.kind), s.name, root, Some(s.namespace)).contains(s))
    val selected = candidates.filter { s => method match {
      case "fqn" =>
        val relative = w.root(s.file).flatMap(_.relative(s.file.getVirtualFile)).map(_.stripSuffix("." + s.file.getVirtualFile.getExtension).replace('/', '.')).getOrElse("")
        val folders = w.owner(s).flatMap(_.resourceFolders(s.file.getVirtualFile)).map(_._2).getOrElse(Vector.empty)
        val identity = (Vector(s.namespace) ++ folders ++ Vector(s.name) ++ s.version.map("v" + _)).mkString(".")
        glob(value, identity) || glob(value, s.name) || glob(value, s.namespace + "." + s.name) || glob(value, s.namespace + "." + relative.stripPrefix("models."))
      case "path" => w.root(s.file).flatMap(_.relative(s.file.getVirtualFile)).exists(p => glob(value, p) || p.startsWith(value.stripSuffix("/") + "/"))
      case "package" => glob(value, s.namespace)
      case "tag" => s.attributes.get("tags").exists(_.split(",").exists(glob(value, _)))
      case "source" => s.kind == "source_table" && (glob(value, s.owner + "." + s.name) || glob(value, s.owner))
      case "resource_type" => s.kind == value
      case "group" => s.kind == "group" && glob(value, s.name) || s.attributes.get("group").exists(glob(value, _))
      case "exposure" | "metric" | "saved_query" | "function" | "check" | "test" => s.kind == method && glob(value, s.name)
      case _ => false
    }}.distinct
    val expanded = if (includeGraph && (prefix.nonEmpty || suffix.nonEmpty)) {
      val edges = DbtLineage.edges(w)
      val parents = prefix.toVector.flatMap(p => selected.flatMap(s => DbtLineage.traverse(edges, s, downstream = false, p.dropRight(1).toIntOption.getOrElse(Int.MaxValue))))
      val children = suffix.toVector.flatMap(p => selected.flatMap(s => DbtLineage.traverse(edges, s, downstream = true, p.drop(1).toIntOption.getOrElse(Int.MaxValue))))
      selected ++ parents ++ children
    } else selected
    expanded.filter(selectable).map(_.element).distinct
  }

  private[lineage] def references(file: PsiFile, w: DbtWorkspace, root: DbtRoot, includeGraph: Boolean): Vector[DbtNavigationReference] = {
    val selectionFile = root.isSelectionFile(file)
    if (file.getName != "selectors.yml" && !selectionFile) return Vector.empty
    val selectorFile = if (file.getName == "selectors.yml") Some(file) else root.psiFile("selectors.yml")
    val result = ArrayBuffer.empty[DbtNavigationReference]
    file.accept(new PsiRecursiveElementWalkingVisitor {
      override def visitElement(e: PsiElement): Unit = {
        e match {
          case scalar: YAMLScalar =>
            val ancestors = DbtYaml.ancestors(scalar)
            val key = DbtYaml.key(scalar)
            val method = DbtYaml.parentMapping(scalar).flatMap(DbtYaml.string(_, "method"))
            val selectionContext = (if (selectionFile) ancestors.exists(Set("select", "exclude", "selector"))
              else ancestors.contains("definition")) && !ancestors.exists(Set("vars", "env", "description"))
            val named = selectionContext && (key == "selector" || (key == "value" && method.contains("selector")))
            val expression = if (!selectionContext) None else if (selectionFile && Set("select", "exclude").contains(key)) Some(scalar.getTextValue)
              else if (key == "value") method.map(_ + ":" + scalar.getTextValue)
              else if (MethodKeys(key)) Some(key + ":" + scalar.getTextValue)
              else if (key == "definition" || scalar.getParent.isInstanceOf[YAMLSequenceItem]) Some(scalar.getTextValue) else None
            lazy val targets = if (named) w.symbols.filter(s => s.kind == "selector" && selectorFile.contains(s.file) && s.name == scalar.getTextValue).map(_.element)
              else expression.toVector.flatMap(select(w, root, _, includeGraph))
            if (selectionContext && expression.contains(scalar.getTextValue) && !named) {
              DbtYaml.decoded(scalar).foreach { case (text, offset) => "[^\\s,]+".r.findAllMatchIn(text).foreach { term =>
                val range = new TextRange(offset(term.start), offset(term.end))
                result += new DbtNavigationReference(file, range, select(w, root, term.matched, includeGraph), term.matched)
              }}
            } else if (named || expression.nonEmpty) result += new DbtNavigationReference(file, scalar.getTextRange, targets, scalar.getTextValue)
          case _ => ()
        }
        super.visitElement(e)
      }
    })
    result.toVector
  }
}
