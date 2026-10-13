package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import scala.collection.mutable.ArrayBuffer

/** A bounded proof of literal values; all unsupported operations return unknown. */
private[dbt] object DbtStaticCalls {
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
      val tag = if (b.tag) ts.headOption.map(_.image).getOrElse("") else ""
      if (Set("for", "macro", "test", "call", "with", "block").contains(tag)) {
        val outer = scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
        val bindings = if (tag == "for") {
          val targets = ts.drop(1).takeWhile(_.image != "in").filterNot(_.image == ",")
          targets.map(t => t.image -> (if (targets.size == 1) value(ts.dropWhile(_.image != "in").drop(1), outer) else Vector.empty[String])).toMap
        } else if (Set("macro", "test", "call").contains(tag)) DbtSyntax.parameters(b).map(t => t.image -> Vector.empty[String]).toMap
        else if (tag == "with") {
          var depth = 0
          val assignments = ts.indices.filter { i =>
            if (Set("(", "[", "{").contains(ts(i).image)) depth += 1
            if (Set(")", "]", "}").contains(ts(i).image)) depth -= 1
            depth == 0 && ts.lift(i + 1).exists(_.image == "=")
          }
          assignments.zipWithIndex.map { case (i, index) =>
            val end = assignments.lift(index + 1).map(_ - 1).getOrElse(ts.size)
            ts(i).image -> value(ts.slice(i + 2, end), outer)
          }.toMap
        } else Map.empty[String, Vector[String]]
        scopes = bindings :: scopes
      }
      if (Set("endfor", "endmacro", "endtest", "endcall", "endwith", "endblock").contains(tag) && scopes.size > 1) scopes = scopes.tail
      if (tag == "endset" && captures.nonEmpty) {
        scopes = (scopes.head + (captures.head -> Vector.empty[String])) :: scopes.tail
        captures = captures.tail
      }
      if (tag == "set" && ts.lift(2).exists(_.image == "=")) {
        val env = scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
        val name = ts(1).image
        val next = value(ts.drop(3), env)
        // Conditional branches remain a finite set of candidates, never an assumed runtime branch.
        val conditional = blocks.takeWhile(_.range.getStartOffset < b.range.getStartOffset).count(x => x.tag && x.tokens.headOption.exists(_.image == "if")) >
          blocks.takeWhile(_.range.getStartOffset < b.range.getStartOffset).count(x => x.tag && x.tokens.headOption.exists(_.image == "endif"))
        val values = if (conditional && (next.isEmpty || env.get(name).exists(_.isEmpty))) Vector.empty
        else if (conditional) (env.getOrElse(name, Vector.empty) ++ next).distinct else next
        scopes = (scopes.head + (name -> values)) :: scopes.tail
      } else if (tag == "set" && !ts.lift(2).exists(_.image == ".")) {
        if (!ts.exists(_.image == "=")) ts.lift(1).foreach(t => captures = t.image :: captures)
        else ts.drop(1).takeWhile(_.image != "=").filter(t => !t.string && t.image.matches("[A-Za-z_][A-Za-z_0-9]*"))
          .foreach(t => scopes = (scopes.head + (t.image -> Vector.empty[String])) :: scopes.tail)
      }
    }
    scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
  }

  def targets(w: DbtWorkspace, root: DbtRoot, call: DbtSyntax.Call, env: Map[String, Vector[String]]): Vector[PsiElement] = {
    val args = call.arguments.filter(_.keyword.isEmpty).map(a => value(a.tokens, env))
    val base = call.name.stripPrefix("builtins.")
    if (!call.name.startsWith("builtins.") && w.declarations(Set("macro"), base, root).nonEmpty) return Vector.empty
    base match {
      case "ref" | "function" | "doc" =>
        val kinds = if (base == "ref") Set("model", "seed", "snapshot") else Set(base)
        val qualified = args.size >= 2
        val names = args.lift(if (qualified) 1 else 0).getOrElse(Vector.empty)
        val namespaces = if (qualified) args.head.map(Some(_)) else Vector(None)
        val version = call.arguments.find(a => a.keyword.exists(Set("v", "version"))).flatMap(_.literal)
        (for (name <- names; ns <- namespaces; defs = w.declarations(kinds, name, root, ns);
              latest = defs.flatMap(_.attributes.get("latest_version")).distinct;
              selected = version.orElse(if (latest.size == 1) latest.headOption else None);
              s <- defs if selected.fold(s.version.isEmpty)(v => s.version.contains(v))) yield s.element).distinct
      case "source" if args.size == 2 =>
        (for (source <- args.head; table <- args(1); s <- DbtNavigation.sourceTables(w, root, source, table)) yield s.element).distinct
      case "var" => args.headOption.toVector.flatten.flatMap(DbtNavigation.variable(w, root, _))
      case _ => Vector.empty
    }
  }

  def wrapperTargets(w: DbtWorkspace, root: DbtRoot, macroSymbol: DbtSymbol, call: DbtSyntax.Call,
    constants: Map[String, Vector[String]], visited: Set[String] = Set.empty): Vector[(DbtSyntax.Argument, Vector[PsiElement])] = {
    if (visited(macroSymbol.id) || visited.size >= 8) return Vector.empty
    ProgressManager.checkCanceled()
    val blocks = DbtSyntax.inFile(macroSymbol.file)
    val start = blocks.indexWhere(b => b.tag && b.tokens.lift(1).exists(_.range == macroSymbol.range))
    if (start < 0) return Vector.empty
    val body = blocks.drop(start + 1).takeWhile(b => !(b.tag && b.tokens.headOption.exists(_.image == "endmacro")))
    val params = w.symbols.filter(s => s.kind == "parameter" && s.owner == macroSymbol.name && s.file == macroSymbol.file).sortBy(_.range.getStartOffset)
    val bindings = params.zipWithIndex.flatMap { case (p, i) =>
      call.arguments.find(_.keyword.contains(p.name)).orElse(call.arguments.filter(_.keyword.isEmpty).lift(i)).map(a => p.name -> (a, value(a.tokens, constants)))
    }.toMap
    val env = bindings.view.mapValues(_._2).toMap
    val result = ArrayBuffer.empty[(DbtSyntax.Argument, Vector[PsiElement])]
    body.flatMap(DbtSyntax.calls).foreach { delegated =>
      val reassigned = this.constants(body, delegated.block.range.getStartOffset).keySet
      val local = constantsForBody(body, delegated, env)
      val targets = this.targets(w, root, delegated, local)
      delegated.arguments.foreach { a =>
        a.tokens.filter(t => !t.string && !reassigned(t.image)).flatMap(t => bindings.get(t.image)).foreach { case (argument, _) =>
          if (targets.nonEmpty) result += argument -> targets
        }
      }
      val parts = delegated.name.split("\\.")
      if (targets.isEmpty) w.declarations(Set("macro"), parts.last, root, if (parts.length == 2) Some(parts.head) else None).foreach { next =>
        wrapperTargets(w, root, next, delegated, local, visited + macroSymbol.id).foreach { case (arg, candidates) =>
          arg.tokens.flatMap(t => bindings.get(t.image)).foreach { case (original, _) => result += original -> candidates }
        }
      }
    }
    result.toVector
  }
  private def constantsForBody(body: Vector[DbtSyntax.Block], call: DbtSyntax.Call, parameters: Map[String, Vector[String]]): Map[String, Vector[String]] = {
    // An assignment before the delegated call overrides a parameter, including unknown runtime values.
    val assignments = constants(body, call.block.range.getStartOffset)
    parameters ++ assignments
  }
}
