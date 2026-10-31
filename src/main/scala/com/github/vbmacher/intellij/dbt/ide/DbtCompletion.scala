package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtResolution, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.codeInsight.completion._
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.parameterInfo._
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.{PsiElement, PsiFile}

private[ide] final case class DbtSuggestion(text: String, detail: String, kind: String = "", description: String = "")

private[ide] final case class DbtMacroSignature(name: String, parameters: Vector[(String, String)])

private[ide] object DbtCompletion {
  val ConfigKeys: Vector[String] = Vector("materialized", "alias", "schema", "database", "tags", "enabled", "unique_key",
    "incremental_strategy", "on_schema_change", "contract", "persist_docs", "pre_hook", "post_hook")
  /** The qualifier of a dotted name being typed at the end of a prefix. */
  private val Qualifier = "([A-Za-z_][A-Za-z_0-9]*)\\.[A-Za-z_0-9]*$".r

  def callAt(file: PsiFile, offset: Int): Option[DbtSyntax.Call] = DbtSyntax.inFile(file)
    .filter(b => b.range.getStartOffset <= offset && b.range.getEndOffset >= offset).flatMap(DbtSyntax.calls)
    .filter(c => c.range.getEndOffset < offset && (c.expressionRange.getEndOffset >= offset || !c.closed))
    .sortBy(_.expressionRange.getLength).headOption

  def suggestions(file: PsiFile, offset: Int): Vector[DbtSuggestion] = {
    if (!file.isPhysical || !DbtWorkspace.rootForFile(file.getProject, file.getVirtualFile).exists(r =>
      !r.excluded(file.getVirtualFile) && (r.resource(file) || file.getName == "selectors.yml"))) return Vector.empty
    val w = DbtWorkspace.get(file.getProject)
    val root = w.root(file).filter(r => !r.excluded(file.getVirtualFile) && (r.resource(file) || file.getName == "selectors.yml"))
      .getOrElse(return Vector.empty)

    def resources(kinds: Set[String], namespace: Option[String] = None): Vector[DbtSymbol] =
      w.symbols.filter(s => kinds(s.kind)).map(_.name).distinct.flatMap { name =>
        ProgressManager.checkCanceled(); w.declarations(kinds, name, root, namespace)
      }.distinctBy(s => (s.uniqueId, w.owner(s)))

    def suggestion(s: DbtSymbol): DbtSuggestion = DbtSuggestion(s.name,
      s.kind + " · " + s.namespace + s.version.map(" v" + _).getOrElse(""), s.kind, s.attributes.getOrElse("description", ""))

    val blocks = DbtSyntax.inFile(file)
    val block = blocks.find(b => b.range.getStartOffset <= offset && b.range.getEndOffset >= offset)
    val call = callAt(file, offset)
    val argument = call.flatMap(c => c.arguments.zipWithIndex.find { case (a, _) => a.tokens.exists(t =>
      t.range.getStartOffset <= offset && t.range.getEndOffset >= offset)
    })
    val unfinishedQuote = call.exists { c =>
      var quote = Option.empty[Char];
      var escaped = false
      file.getText.substring(c.range.getEndOffset, offset).foreach { char =>
        if (escaped) escaped = false
        else if (char == '\\') escaped = true
        else if (quote.contains(char)) quote = None
        else if (quote.isEmpty && (char == '\'' || char == '"')) quote = Some(char)
      }
      quote.nonEmpty
    }
    val quoted = argument.exists(_._1.tokens.exists(_.string)) || unfinishedQuote
    if (quoted) call.toVector.flatMap { c =>
      val index = argument.map(_._2).getOrElse(c.arguments.size)
      c.name match {
        case "ref" | "builtins.ref" =>
          val namespace = if (index == 1) c.literal(0) else None
          lazy val defs = resources(DbtSymbol.RefKinds, namespace)
          if (argument.exists(_._1.keyword.exists(Set("v", "version")))) {
            c.qualifiedName.toVector.flatMap { case (packageName, name) => resources(DbtSymbol.RefKinds, packageName).filter(_.name == name)
              .flatMap(_.version).distinct.map(v => DbtSuggestion(v, "model version", "model"))
            }
          } else if (index == 0 && c.positional.size >= 2) {
            w.symbols.filter(s => DbtSymbol.RefKinds(s.kind)).groupBy(_.namespace).toVector.sortBy(_._1).collect {
              case (packageName, symbols) if symbols.exists(s => w.declarations(Set(s.kind), s.name, root, Some(packageName)).nonEmpty) =>
                DbtSuggestion(packageName, "package", "package")
            }
          }
          else if (c.version.contains(None)) Vector.empty
          else defs.groupBy(_.name).toVector.sortBy(_._1).flatMap { case (_, candidates) => DbtResolution.versioned(candidates, c.version.flatten).map(suggestion) }
        case "source" | "builtins.source" if index == 0 => resources(Set("source")).map(suggestion)
        case "source" | "builtins.source" => c.literal(0).toVector.flatMap { source =>
          w.symbols.filter(s => s.kind == "source_table" && s.owner == source).map(_.name).distinct
            .flatMap(DbtResolution.sourceTables(w, root, source, _)).map(suggestion)
        }
        case "doc" => resources(Set("doc"), if (index == 1) c.literal(0) else None).map(suggestion)
        case "var" => resources(Set("var")).map(suggestion)
        case "config" => ConfigKeys.map(k => DbtSuggestion(k, "configuration", "config"))
        case "adapter.dispatch" => resources(Set("macro"), c.literal(1)).map(suggestion).map(s => s.copy(text = s.text.split("__", 2).last))
        case _ => Vector.empty
      }
    }.distinctBy(s => (s.text, s.detail))
    else if (block.nonEmpty) {
      val prefix = file.getText.substring(block.get.range.getStartOffset, offset)
      val namespace = Qualifier.findFirstMatchIn(prefix).map(_.group(1))
      val named = call.toVector.flatMap(c => w.macros(c, root)).flatMap(m => parameters(w, m).map { case (name, display) =>
        DbtSuggestion(name + "=", display, "parameter")
      })
      val config = if (call.exists(_.name == "config")) ConfigKeys.map(k => DbtSuggestion(k + "=", "configuration", "config")) else Vector.empty
      val builtins = if (namespace.isEmpty && call.isEmpty) Vector("ref", "source", "doc", "var", "config").map(k => DbtSuggestion(k, "dbt", "builtin")) else Vector.empty
      (named ++ config ++ builtins ++ resources(Set("macro"), namespace).map(suggestion)).distinctBy(_.text)
    } else if (file.getName == "selectors.yml") {
      (w.symbols.filter(s => w.owner(s).exists(r => w.namespaceVisible(root, r, qualified = true))).flatMap { s =>
        Vector(DbtSuggestion("package:" + s.namespace, "selector", "selector")) ++
          s.attributes.get("tags").toVector.flatMap(_.split(",")).map(tag => DbtSuggestion("tag:" + tag, "selector", "selector")) ++
          (if (s.kind == "selector") Vector(DbtSuggestion("selector:" + s.name, "named selector", "selector")) else Vector.empty)
      } ++ resources(DbtSymbol.RefKinds).map(s => DbtSuggestion(s.name, "resource selector", s.kind))).distinctBy(_.text)
    } else if (file.getVirtualFile.getExtension == "sql") {
      val prefix = file.getText.substring(0, offset)
      val qualifier = Qualifier.findFirstMatchIn(prefix).map(_.group(1))
      DbtSqlAnalysis.forCompletion(w, file, offset).flatMap(_.scope(offset)).toVector.flatMap(_.relations)
        .filter { case (alias, _) => qualifier.forall(_.equalsIgnoreCase(alias)) }.flatMap { case (alias, columns) =>
          columns.map(c => DbtSuggestion(c.name, c.attributes.getOrElse("data_type", "column") + " · " + alias,
            "column", c.attributes.getOrElse("description", "")))
        }.distinctBy(s => (s.text, s.detail))
    } else Vector.empty
  }

  def parameters(w: DbtWorkspace, macroSymbol: DbtSymbol): Vector[(String, String)] = {
    val block = DbtSyntax.inFile(macroSymbol.file).find(b => b.isTag("macro") && b.range.contains(macroSymbol.range))
    val documented = DbtResourceData.get(w, macroSymbol).arguments.map(a => a.name -> a).toMap
    block.toVector.flatMap { b =>
      val names = DbtSyntax.parameters(b)
      names.map { token =>
        val start = b.tokens.indexOf(token)
        val remaining = b.tokens.drop(start + 1)
        var depth = 0
        val default = remaining.takeWhile { t =>
          val stop = depth == 0 && Set(",", ")")(t.image) && !t.string
          if (!t.string && Set("(", "[", "{")(t.image)) depth += 1
          if (!t.string && Set(")", "]", "}")(t.image)) depth -= 1
          !stop
        }
        val display = token.image + default.headOption.filter(_.image == "=").map(_ => default.map(t => if (t.string) "'" + t.image + "'" else t.image).mkString).getOrElse("") +
          documented.get(token.image).filter(_.dataType.nonEmpty).map(a => ": " + a.dataType).getOrElse("")
        token.image -> display
      }
    }
  }
}

final class DbtCompletionContributor extends CompletionContributor {
  override def fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet): Unit = {
    val file = parameters.getOriginalFile
    val offset = parameters.getOffset
    DbtCompletion.suggestions(file, offset).foreach { s =>
      result.addElement(LookupElementBuilder.create(s.text).withTypeText(s.detail, true)
        .withTailText(if (s.description.isEmpty) "" else "  " + s.description, true).withIcon(DbtSymbolElement.icon(s.kind)))
    }
  }
}

final class DbtParameterInfoHandler extends ParameterInfoHandler[PsiElement, DbtMacroSignature] {
  override def findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement = {
    val file = context.getFile
    DbtCompletion.callAt(file, context.getOffset).flatMap { call =>
      val w = DbtWorkspace.get(file.getProject)
      w.root(file).map(r => w.macros(call, r)).filter(_.size == 1).map { macros =>
        val m = macros.head
        val signature = DbtMacroSignature(m.name, DbtCompletion.parameters(w, m))
        context.setItemsToShow(Array[AnyRef](signature));
        file: PsiElement
      }
    }.orNull
  }

  override def showParameterInfo(element: PsiElement, context: CreateParameterInfoContext): Unit =
    DbtCompletion.callAt(context.getFile, context.getOffset).foreach(c => context.showHint(element, c.range.getEndOffset, this))

  override def findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement =
    if (DbtCompletion.callAt(context.getFile, context.getOffset).nonEmpty) context.getFile else null

  override def updateParameterInfo(element: PsiElement, context: UpdateParameterInfoContext): Unit =
    if (element == null) context.removeHint() else DbtCompletion.callAt(context.getFile, context.getOffset).foreach { call =>
      val signatures = context.getObjectsToView.toVector.collect { case s: DbtMacroSignature => s }
      val current = call.arguments.find(a => a.range.exists(r => r.getStartOffset <= context.getOffset && r.getEndOffset >= context.getOffset))
      val named = current.flatMap(_.keyword).flatMap(name => signatures.headOption.map(_.parameters.indexWhere(_._1 == name)))
      context.setCurrentParameter(named.getOrElse(call.arguments.count(_.range.exists(_.getEndOffset < context.getOffset))))
    }

  override def updateUI(signature: DbtMacroSignature, context: ParameterInfoUIContext): Unit = {
    val text = signature.name + signature.parameters.map(_._2).mkString("(", ", ", ")")
    val index = context.getCurrentParameterIndex
    val start = if (index >= 0 && index < signature.parameters.size) signature.name.length + 1 + signature.parameters.take(index).map(_._2.length + 2).sum else -1
    val end = if (start >= 0) start + signature.parameters(index)._2.length else -1
    context.setupUIComponentPresentation(text, start, end, false, false, false, context.getDefaultParameterColor)
  }
}

