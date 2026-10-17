package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import scala.collection.mutable.ArrayBuffer

/** A bounded proof of literal values; all unsupported operations return unknown. */
private[navigation] object DbtStaticCalls {
  def value(tokens: Vector[DbtSyntax.Token], constants: Map[String, Vector[String]]): Vector[String] = {
    def atom(ts: Vector[DbtSyntax.Token]): Vector[String] = ts match {
      case Vector(t) if t.string => Vector(t.image)
      case Vector(t) if !t.string => constants.getOrElse(t.image, Vector.empty)
      case xs if xs.headOption.exists(_.image == "[") && xs.lastOption.exists(_.image == "]") =>
        val items = xs.drop(1).dropRight(1).filterNot(t => !t.string && t.image == ",")
        if (items.size <= 16 && items.forall(_.string)) items.map(_.image) else Vector.empty
      case xs if xs.headOption.exists(_.image == "(") && xs.lastOption.exists(_.image == ")") => value(xs.drop(1).dropRight(1), constants)
      case _ => Vector.empty
    }

    val operators = tokens.zipWithIndex.collect { case (t, i) if !t.string && Set("~", "+").contains(t.image) => i }
    if (operators.isEmpty) atom(tokens)
    else {
      val boundaries = Vector(-1) ++ operators ++ Vector(tokens.size)
      boundaries.sliding(2).collect { case Vector(a, b) => atom(tokens.slice(a + 1, b)) }.foldLeft(Vector("")) { (left, right) =>
        if (left.size * right.size > 16) Vector.empty else for (a <- left; b <- right) yield a + b
      }
    }
  }

  def constants(blocks: Vector[DbtSyntax.Block], before: Int): Map[String, Vector[String]] = {
    var scopes = List(Map.empty[String, Vector[String]])
    var captures = List.empty[String]
    blocks.takeWhile(_.range.getStartOffset < before).foreach { b =>
      val ts = b.tokens
      val tag = b.tagName.getOrElse("")
      if (DbtSyntax.ScopeTags(tag)) {
        val outer = scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
        val bindings = if (tag == "for") {
          val targets = ts.drop(1).takeWhile(_.image != "in").filterNot(_.image == ",")
          targets.map(t => t.image -> (if (targets.size == 1) value(ts.dropWhile(_.image != "in").drop(1), outer) else Vector.empty[String])).toMap
        } else if (Set("macro", "test", "call").contains(tag)) DbtSyntax.parameters(b).map(t => t.image -> Vector.empty[String]).toMap
        else if (tag == "with") {
          val assignments = DbtSyntax.assignedNames(ts)
          assignments.zipWithIndex.map { case (i, index) =>
            val end = assignments.lift(index + 1).map(_ - 1).getOrElse(ts.size)
            ts(i).image -> value(ts.slice(i + 2, end), outer)
          }.toMap
        } else Map.empty[String, Vector[String]]
        scopes = bindings :: scopes
      }
      if (DbtSyntax.ScopeEndTags(tag) && scopes.size > 1) scopes = scopes.tail
      if (tag == "endset" && captures.nonEmpty) {
        scopes = (scopes.head + (captures.head -> Vector.empty[String])) :: scopes.tail
        captures = captures.tail
      }
      if (tag == "set" && ts.lift(2).exists(_.image == "=")) {
        val env = scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
        val name = ts(1).image
        val next = value(ts.drop(3), env)
        // Conditional branches remain a finite set of candidates, never an assumed runtime branch.
        val preceding = blocks.takeWhile(_.range.getStartOffset < b.range.getStartOffset)
        val conditional = preceding.count(_.isTag("if")) > preceding.count(_.isTag("endif"))
        val values = if (conditional && (next.isEmpty || env.get(name).exists(_.isEmpty))) Vector.empty
        else if (conditional) (env.getOrElse(name, Vector.empty) ++ next).distinct else next
        scopes = (scopes.head + (name -> values)) :: scopes.tail
      } else if (tag == "set" && !ts.lift(2).exists(_.image == ".")) {
        if (!ts.exists(_.image == "=")) ts.lift(1).foreach(t => captures = t.image :: captures)
        else ts.drop(1).takeWhile(_.image != "=").filter(t => !t.string && DbtSyntax.isIdentifier(t.image))
          .foreach(t => scopes = (scopes.head + (t.image -> Vector.empty[String])) :: scopes.tail)
      }
    }
    scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
  }

  def targets(w: DbtWorkspace, root: DbtRoot, call: DbtSyntax.Call, env: Map[String, Vector[String]]): Vector[PsiElement] = {
    val args = call.positional.map(a => value(a.tokens, env))
    val base = call.name.stripPrefix("builtins.")
    if (!call.name.startsWith("builtins.") && w.declarations(Set("macro"), base, root).nonEmpty) return Vector.empty
    base match {
      case "ref" | "function" | "doc" =>
        val kinds = if (base == "ref") DbtSymbol.RefKinds else Set(base)
        val qualified = args.size >= 2
        val names = args.lift(if (qualified) 1 else 0).getOrElse(Vector.empty)
        val namespaces = if (qualified) args.head.map(Some(_)) else Vector(None)
        call.version match {
          case Some(None) => Vector.empty
          case version => (for (name <- names; ns <- namespaces;
                                s <- DbtResolution.versioned(w.declarations(kinds, name, root, ns), version.flatten)) yield s.element).distinct
        }
      case "source" if args.size == 2 =>
        (for (source <- args.head; table <- args(1); s <- DbtResolution.sourceTables(w, root, source, table)) yield s.element).distinct
      case "var" => args.headOption.toVector.flatten.flatMap(DbtResolution.variable(w, root, _))
      case _ => Vector.empty
    }
  }

  def wrapperTargets(w: DbtWorkspace, root: DbtRoot, macroSymbol: DbtSymbol, call: DbtSyntax.Call,
    constants: Map[String, Vector[String]], visited: Set[String] = Set.empty,
    onMissing: (DbtSyntax.Argument, String) => Unit = (_, _) => ()): Vector[(DbtSyntax.Argument, Vector[PsiElement])] = {
    if (visited(macroSymbol.id) || visited.size >= 8) return Vector.empty
    ProgressManager.checkCanceled()
    val blocks = DbtSyntax.inFile(macroSymbol.file)
    val start = blocks.indexWhere(b => b.tag && b.tokens.lift(1).exists(_.range == macroSymbol.range))
    if (start < 0) return Vector.empty
    val body = blocks.drop(start + 1).takeWhile(!_.isTag("endmacro"))
    val params = w.symbols.filter(s => s.kind == "parameter" && s.owner == macroSymbol.name && s.file == macroSymbol.file).sortBy(_.range.getStartOffset)
    val bindings = params.zipWithIndex.flatMap { case (p, i) =>
      call.arguments.find(_.keyword.contains(p.name)).orElse(call.positional.lift(i)).map(a => p.name -> (a, value(a.tokens, constants)))
    }.toMap
    val env = bindings.view.mapValues(_._2).toMap
    val result = ArrayBuffer.empty[(DbtSyntax.Argument, Vector[PsiElement])]
    body.flatMap(DbtSyntax.calls).foreach { delegated =>
      // An assignment before the delegated call overrides a parameter, including unknown runtime values.
      val assigned = this.constants(body, delegated.block.range.getStartOffset)
      val reassigned = assigned.keySet
      val local = env ++ assigned
      val targets = this.targets(w, root, delegated, local)
      if (targets.isEmpty) missing(w, root, delegated, local).foreach { case (argument, message) =>
        argument.tokens.filterNot(_.string).flatMap(t => bindings.get(t.image)).foreach { case (original, _) => onMissing(original, message) }
      }
      delegated.arguments.foreach { a =>
        a.tokens.filter(t => !t.string && !reassigned(t.image)).flatMap(t => bindings.get(t.image)).foreach { case (argument, _) =>
          if (targets.nonEmpty) result += argument -> targets
        }
      }
      if (targets.isEmpty) w.macros(delegated, root).foreach { next =>
        wrapperTargets(w, root, next, delegated, local, visited + macroSymbol.id, (arg, message) =>
          arg.tokens.flatMap(t => bindings.get(t.image)).foreach { case (original, _) => onMissing(original, message) }).foreach { case (arg, candidates) =>
          arg.tokens.flatMap(t => bindings.get(t.image)).foreach { case (original, _) => result += original -> candidates }
        }
      }
    }
    result.toVector
  }

  /** A proof of a single static missing target, using exactly the same bounded evaluator as navigation. */
  def missing(w: DbtWorkspace, root: DbtRoot, call: DbtSyntax.Call,
    constants: Map[String, Vector[String]]): Option[(DbtSyntax.Argument, String)] = {
    val args = call.positional.map(a => value(a.tokens, constants))
    val base = call.name.stripPrefix("builtins.")
    val literal = args.forall(_.size == 1)
    val qualified = args.size == 2
    val namespaceKnown = !qualified || base == "source" || args.headOption.flatMap(_.headOption).exists(ns =>
      w.roots.exists(_.name == ns) || w.symbols.exists(_.namespace == ns))
    if (!call.closed || !literal || !namespaceKnown || call.version.contains(None) ||
      !call.name.startsWith("builtins.") && w.macros(call, root).nonEmpty || targets(w, root, call, constants).nonEmpty) None
    else base match {
      case "ref" if args.size == 1 || args.size == 2 =>
        val index = if (qualified) 1 else 0
        call.positional.lift(index).map(_ -> (s"Unresolved ref: '${args(index).head}'" +
          call.version.flatten.map(" (version " + _ + ")").getOrElse("")))
      case "source" if args.size == 2 =>
        val source = args(0).head; val table = args(1).head
        if (w.declarations(Set("source"), source, root).isEmpty) call.positional.headOption.map(_ -> s"Unresolved source: '$source'")
        else call.positional.lift(1).map(_ -> s"Unresolved source table: '$source.$table'")
      case _ => None
    }
  }
}
