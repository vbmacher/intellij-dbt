package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import com.intellij.usages.{Usage, UsageInfo2UsageAdapter, UsageTarget, UsageView, UsageViewManager, UsageViewPresentation}
import com.intellij.usageView.UsageInfo
import scala.util.Try

/** Only stable paths and display text cross from workspace reads into the Runner. */
private[ide] final case class DbtRunSelection(root: String, path: String, directory: Boolean, label: String)

private[ide] object DbtRunSelection {
  private def models(w: DbtWorkspace): Vector[DbtSymbol] = w.symbols.filter(s => s.kind == "model" && s.isCode && s.isEnabled &&
    w.owner(s).exists(r => !r.excluded(s.file.getVirtualFile))).distinctBy(_.file.getVirtualFile.getPath)

  def available(w: DbtWorkspace): Vector[DbtRunSelection] = {
    val sources = models(w)
    w.roots.flatMap { root =>
      val project = root.name + (if (w.roots.count(_.name == root.name) > 1) s" (${root.directory.getPath})" else "")
      val local = sources.filter(s => w.owner(s).contains(root))
      val folders = (Vector(root.directory) ++ local.flatMap(s =>
        Iterator.iterate(s.file.getVirtualFile.getParent)(_.getParent)
          .takeWhile(f => f != null && VfsUtilCore.isAncestor(root.directory, f, false)).toVector)).distinct
      folders.map { folder =>
        val relative = root.relative(folder).filter(_.nonEmpty).getOrElse(".")
        DbtRunSelection(root.directory.getPath, folder.getPath, directory = true, s"Directory: $project / $relative/")
      } ++ local.map { s =>
        DbtRunSelection(root.directory.getPath, s.file.getVirtualFile.getPath, directory = false,
          s"Model: $project.${s.relationName}${s.version.map(" v" + _).getOrElse("")} — ${root.relative(s.file.getVirtualFile).getOrElse(s.file.getName)}")
      }
    }.distinctBy(_.path).sortBy(s => (s.root, !s.directory, s.path))
  }

  def owner(w: DbtWorkspace, selection: DbtRunSelection): Option[DbtRoot] =
    w.roots.find(_.directory.getPath == selection.root)

  def all(w: DbtWorkspace, preferredRoot: String): Option[DbtRunSelection] = {
    val roots = w.roots.map(w.executionRoot).distinct
    roots.find(_.directory.getPath == preferredRoot)
      .orElse(roots.find(r => Option(r.config.getProject.getBasePath).contains(r.directory.getPath)))
      .orElse(if (roots.size == 1) roots.headOption else None)
      .map(r => DbtRunSelection(r.directory.getPath, "", directory = true, s"All models: ${r.name}"))
  }

  def selectedModels(w: DbtWorkspace, selection: DbtRunSelection): Vector[DbtSymbol] = models(w).filter { s =>
    w.owner(s).exists(r => owner(w, selection).exists(selected => w.executionRoot(r) == w.executionRoot(selected))) &&
      (if (selection.path.isEmpty) true
       else if (selection.directory) s.file.getVirtualFile.getPath.startsWith(selection.path + "/")
       else s.file.getVirtualFile.getPath == selection.path)
  }

  /** Expand folders to model FQNs so similarly named paths in other packages cannot join the run. */
  def selectors(w: DbtWorkspace, selection: DbtRunSelection): Vector[String] = selectedModels(w, selection).flatMap { s =>
    w.owner(s).map { owner =>
      val folders = owner.resourceFolders(s.file.getVirtualFile).toVector.flatMap(_._2)
      "fqn:" + (Vector(s.namespace) ++ folders ++ Vector(s.name) ++ s.version.map("v" + _)).mkString(".")
    }
  }
}

private[ide] final case class DbtCliResource(id: String, selector: String, file: PsiFile, range: TextRange, symbol: Option[DbtSymbol])

/** Immutable selection data: resolving and completing a selector never launches dbt. */
private[ide] object DbtSelectionReview {
  /** Use the same exact test identities and declaration positions as the native test runner. */
  def knownCliResources(w: DbtWorkspace, root: DbtRoot): Vector[DbtCliResource] = {
    val resources = DbtSelection.resolve(w, root, "").resources.map(s =>
      DbtCliResource(s.uniqueId, DbtSelection.selector(w, s), s.file, s.range, Some(s)))
    val tests = DbtTestDiscovery.discover(w, Map(root.directory.getPath -> root.runOptions))
      .filter(t => t.root == root.directory.getPath && t.selector.nonEmpty).flatMap { t =>
        DbtYaml.psiFile(w.project, t.file).map(f =>
          DbtCliResource(t.id, t.selector, f, new TextRange(t.offset, t.offset), None))
      }
    (tests ++ resources).distinctBy(_.id)
  }

  def expression(w: DbtWorkspace, root: DbtRoot, selected: Option[DbtRunSelection]): String =
    Option(root.runOptions.selection.trim).filter(_.nonEmpty).getOrElse(selected
      .filter(s => s.path.nonEmpty && s.path != root.directory.getPath)
      .map(DbtRunSelection.selectors(w, _).mkString(" ")).getOrElse(""))

  /** Expand named terms before passing compound selections or exclusions to the CLI. */
  def cliTerms(w: DbtWorkspace, root: DbtRoot, text: String): Either[String, Vector[String]] = {
    val terms = text.trim.split("\\s+").toVector.filter(_.nonEmpty)
    val named = terms.flatMap(_.split(",", -1)).exists(term =>
      term.stripPrefix("@").replaceFirst("^[0-9]*\\+", "").startsWith("selector:"))
    if (!named) Right(terms)
    else {
      val resolved = DbtSelection.resolve(w, root, text)
      if (resolved.complete) Right(resolved.resources.map(DbtSelection.selector(w, _)).distinct)
      else Left("Compound named-selector expressions must resolve completely before running dbt. " +
        "Use a locally resolvable named selector, or ordinary CLI terms for explicit dbt ls resolution.\n" +
        resolved.problems.mkString("\n"))
    }
  }

  def completions(w: DbtWorkspace, root: DbtRoot): Vector[String] = {
    val resources = DbtSelection.resolve(w, root, "").resources
    val named = w.rootFile(root, "selectors.yml").flatMap(DbtYaml.mapping).toVector
      .flatMap(DbtYaml.items(_, "selectors")).flatMap(DbtYaml.string(_, "name")).map("selector:" + _)
    (resources.map(DbtSelection.selector(w, _)) ++ resources.map(s => "package:" + s.namespace) ++
      resources.flatMap(s => DbtResourceData.get(w, s).attributes.get("tags").toVector.flatMap(_.split(",")).filter(_.nonEmpty).map("tag:" + _)) ++
      named ++ Vector("fqn:", "path:", "package:", "tag:", "group:", "source:", "selector:", "state:modified") ++
      Vector("model", "seed", "snapshot", "test", "source", "exposure").map("resource_type:" + _)).distinct.sorted
  }

  // Complete the current intersection/union term while retaining its graph-depth operators.
  def complete(text: String, caret: Int, candidates: Vector[String]): Vector[(String, String, Int)] = {
    val position = caret.max(0).min(text.length)
    val start = text.lastIndexWhere(c => c.isWhitespace || c == ',', position - 1) + 1
    val end = text.indexWhere(c => c.isWhitespace || c == ',', position) match {
      case -1 => text.length
      case i => i
    }
    val typed = text.substring(start, position)
    val prefix = "^[0-9]*\\+".r.findFirstIn(typed).getOrElse("")
    val suffix = "\\+[0-9]*$".r.findFirstIn(text.substring(position, end)).getOrElse("")
    candidates.filter(_.startsWith(typed.stripPrefix(prefix))).take(200).map { candidate =>
      val replacement = prefix + candidate + suffix
      (candidate, text.take(start) + replacement + text.drop(end), start + replacement.length)
    }
  }

  def cliResources(output: String): Either[String, Vector[String]] = {
    val rows = output.linesIterator.filter(_.trim.startsWith("{")).flatMap(line => Try {
      val json = JsonParser.parseString(line).getAsJsonObject
      Option(json.get("unique_id")).filter(_.isJsonPrimitive).map(_.getAsString)
    }.toOption.flatten).toVector.distinct
    if (rows.nonEmpty) Right(rows)
    else if (output.trim.isEmpty) Right(Vector.empty)
    else Left("dbt ls returned no resource records. Check raw process output; the selection may be empty or unsupported.")
  }
}

private[ide] object DbtSelectionResults {
  private def show(project: Project, label: String, resources: Vector[(PsiFile, TextRange)], problems: Vector[String]): UsageView = {
    val presentation = new UsageViewPresentation
    presentation.setTabText(label); presentation.setTabName(label)
    presentation.setDynamicUsagesString("resources"); presentation.setOpenInNewTab(true)
    presentation.setScopeText(if (problems.isEmpty) "Matched resources" else "Incomplete: " + problems.mkString("; "))
    val usages = resources.map { case (file, range) => new UsageInfo2UsageAdapter(new UsageInfo(file,
      range.getStartOffset, range.getEndOffset)): Usage }.toArray
    val view = UsageViewManager.getInstance(project).showUsages(Array.empty[UsageTarget], usages, presentation)
    if (problems.nonEmpty) Messages.showWarningDialog(project, problems.mkString("\n"), label + " — incomplete")
    view
  }
  def review(configuration: DbtRunConfiguration): Unit = {
    val project = configuration.getProject
    DbtUi.readLater(project, smartIn = Some(project)) {
      val w = DbtWorkspace.get(project)
      configuration.configuredRoot(w).map { root =>
        val selection = if (configuration.scope.isEmpty) DbtRunSelection.all(w, root.directory.getPath)
          else DbtRunSelection.available(w).find(_.path == configuration.scope)
        val expression = DbtSelectionReview.expression(w, root, selection)
        val result = DbtSelection.resolve(w, root, expression, root.runOptions.exclude)
        (result.resources, result.problems, configuration.prepare().map(_._1))
      }
    } {
      case Left(error) => Messages.showErrorDialog(project, error, "dbt selection")
      case Right((resources, problems, command)) =>
        val view = show(project, "dbt local selection · " + configuration.target, resources.map(s => s.file -> s.range), problems)
        view.addButtonToLowerPane(() => DbtLineageSelection.show(project, configuration.root,
              if (configuration.namedSelector.nonEmpty) "selector:" + configuration.namedSelector
              else if (configuration.selectors.nonEmpty) configuration.selectors.mkString(" ")
              else command.toOption.map(_.request.scope).collect { case DbtCommandScope.Selected(terms) => terms.mkString(" ") }.getOrElse(""),
              configuration.exclude.mkString(" ")), "Show in Lineage", false)
        command.foreach { value =>
          view.addButtonToLowerPane(() => DbtUi.copy(value.commandLine), "Copy command", true)
        }
    }
  }
  def showCli(configuration: DbtRunConfiguration, raw: String): Unit = {
    val project = configuration.getProject
    DbtUi.readLater(project) {
      for {
        ids <- DbtSelectionReview.cliResources(raw)
        root <- configuration.configuredRoot(DbtWorkspace.get(project))
      } yield {
        val known = DbtSelectionReview.knownCliResources(DbtWorkspace.get(project), root)
        (known.filter(s => ids.contains(s.id)), ids.filterNot(id => known.exists(_.id == id)), ids)
      }
    } {
      case Left(error) => Messages.showWarningDialog(project, error, "dbt CLI selection")
      case Right((resources, unknown, ids)) =>
        if (unknown.isEmpty) configuration.selectionResolved.foreach(_(ids))
        show(project, "dbt CLI selection · " + configuration.target, resources.map(s => s.file -> s.range),
          unknown.map("CLI resource unavailable locally; execution blocked: " + _))
    }
  }
}
