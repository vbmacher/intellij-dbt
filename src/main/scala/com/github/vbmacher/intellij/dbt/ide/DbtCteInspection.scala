package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtSqlAnalysis, DbtNavigation}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.{JBLabel, JBList, JBScrollPane}
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.{BorderLayout, FlowLayout}
import java.util.concurrent.Callable
import javax.swing.{DefaultListModel, JButton, JCheckBox, JPanel, JSplitPane}

private[ide] object DbtCteInspection {
  def at(w: DbtWorkspace, file: com.intellij.psi.PsiFile, analysis: DbtSqlAnalysis, offset: Int): Option[DbtCte] = {
    val resolved = DbtNavigation.at(file, offset).flatMap(_.candidates).collect {
      case element: DbtSymbolElement if element.symbol.kind == "cte" && element.getContainingFile == file => element
    }.flatMap(element => analysis.ctes.filter(c => c.name.equalsIgnoreCase(element.getName) &&
      c.range.getStartOffset == element.getTextRange.getStartOffset)).distinct
    if (resolved.size == 1) resolved.headOption
    else if (resolved.nonEmpty) None
    else analysis.ctes.filter(_.range.containsOffset(offset)).sortBy(_.range.getLength).headOption
  }

  /** Preserve authored template setup and comments preceding the SQL WITH keyword. */
  private def prefix(text: String, analysis: DbtSqlAnalysis, selected: Vector[DbtCte], target: DbtCte): Either[String, String] = {
    val first = analysis.ctes.filter(c => !analysis.ctes.exists(p => p.body.contains(c.range))).sortBy(_.range.getStartOffset).headOption
    first.toRight("The outer WITH clause is unavailable.").flatMap { initial =>
      val blocks = com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.blocks(text)
      DbtNavigation.withPrefix(text, initial.range.getStartOffset).toRight("Template setup before WITH cannot be extracted safely.").flatMap { prefix =>
        val omittedSetup = blocks.exists(b => b.tag && b.range.getStartOffset >= prefix.length &&
          b.range.getStartOffset < target.range.getEndOffset && !selected.exists(_.range.contains(b.range)))
        if (omittedSetup) Left("Template setup between CTE declarations cannot be omitted safely. Move it before WITH or preview the whole model.")
        else Right(prefix)
      }
    }
  }

  def sql(text: String, analysis: DbtSqlAnalysis, cte: DbtCte, count: Boolean = false): Either[String, String] = {
    if (analysis.issues.exists(_.startsWith("Explicit CTE column lists"))) return Left("Explicit CTE column lists cannot be inspected safely. Preview the complete model instead.")
    val selected = scala.collection.mutable.LinkedHashSet.empty[DbtCte]
    val visiting = scala.collection.mutable.Set.empty[DbtCte]
    def visit(current: DbtCte): Either[String, Unit] = {
      if (visiting(current)) Left("Recursive CTE inspection is unsupported.")
      else {
        visiting += current
        val parents = analysis.ctes.filter(p => p.body.contains(current.range))
        val result = current.dependencies.foldLeft[Either[String, Unit]](Right(())) { (previous, name) => previous.flatMap { _ =>
          val candidates = analysis.ctes.filter(c => c.name.equalsIgnoreCase(name) && c.range.getStartOffset < current.range.getStartOffset &&
            (analysis.ctes.filter(p => p.body.contains(c.range)).toSet.subsetOf(parents.toSet)))
          candidates.sortBy(_.range.getStartOffset).lastOption.toRight("A CTE dependency is unavailable: " + name).flatMap(visit)
        }}
        visiting -= current
        result.map(_ => { selected += current; () })
      }
    }
    if (analysis.issues.exists(_.toLowerCase.contains("recursive"))) Left("Recursive CTE inspection is unsupported.")
    else visit(cte).flatMap(_ => prefix(text, analysis, selected.toVector, cte)).map { setup =>
      val name = if (com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.isIdentifier(cte.name)) cte.name else "\"" + cte.name.replace("\"", "\"\"") + "\""
      setup + "with " + selected.toVector.map(_.range.substring(text)).mkString(",\n") +
        "\nselect " + (if (count) "count(*) as full_row_count" else "*") + " from " + name
    }
  }
  def context(w: DbtWorkspace, model: DbtSymbol): Option[DbtCteContext] = w.owner(model).map(w.executionRoot).map { root =>
    val manager = FileDocumentManager.getInstance()
    val document = Option(manager.getCachedDocument(model.file.getVirtualFile))
    val source = document.map(_.getText).getOrElse(model.file.getText)
    val execution = root.runOptions.values.map(value => String.valueOf(value)) ++ Vector(root.directory.getPath, root.executionDirectory.toString,
      DbtExecutionContext.activeTarget(root).getOrElse(""), DbtExecutionContext.profiles(root).directory.toString,
      root.config.getContainingFile.getText, root.settings.map(_.getText).getOrElse(""),
      root.psiFile(root.profilesFile).map(f => java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(f.getText.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).getOrElse(""))
    val dependencies = w.files.filter(f => w.root(f).exists(r => w.executionRoot(r) == root &&
      (r.resource(f) || f.getName == "dbt_project.yml" || f.getName == "profiles.yml" || f.getName == ".dbt-navigation.yml")))
      .map(f => f.getVirtualFile.getPath -> Option(manager.getCachedDocument(f.getVirtualFile)).map(_.getModificationStamp).getOrElse(f.getModificationStamp))
      .sortBy(_._1)
    DbtCteContext(source, document.map(_.getModificationStamp).getOrElse(model.file.getModificationStamp), execution, dependencies)
  }
  def command(w: DbtWorkspace, resource: DbtSymbol, sql: String): Either[String, DbtCommand] = for {
    owner <- w.owner(resource).toRight("Owning dbt project is unavailable.")
    _ <- Either.cond(w.packageOwner(owner).isEmpty, (), "Inline SQL previews cannot preserve installed-package context. Preview the complete model instead.")
    _ <- Either.cond(DbtNavigation.projectedColumns(sql).nonEmpty, (), "Inline preview requires one supported SELECT query without data or schema modification.")
    root = w.executionRoot(owner)
    _ <- Either.cond(root.supports(5), (), "CTE preview requires a CLI supporting dbt show --inline (Core 1.5+).")
    selected <- DbtRunSelection.available(w).find(_.path == resource.file.getVirtualFile.getPath).toRight("Choose a source model.")
    base <- DbtCommand.create(w, selected, "Preview", fullRefresh = false)
  } yield {
    val index = base.arguments.indexOf("--select", base.commandOffset + 1)
    val end = if (index < 0) base.arguments.size else
      base.arguments.indexWhere(_.startsWith("--"), index + 1) match { case -1 => base.arguments.size; case n => n }
    base.copy(arguments = base.arguments.take(if (index < 0) base.arguments.size else index) ++ Vector("--inline", sql) ++ base.arguments.drop(end))
  }
}

