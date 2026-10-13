package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import scala.collection.mutable.ArrayBuffer

/** A bounded proof of literal values; all unsupported operations return unknown. */
private[dbt] object DbtStaticCalls {
  def value(tokens: Vector[DbtSyntax.Token], constants: Map[String, Vector[String]]): Vector[String] = {
    def atom(ts: Vector[DbtSyntax.Token]): Vector[String] = ts match {
      case Vector(t) if t.string || t.image.matches("[0-9]+|true|false") => Vector(t.image)
      case Vector(t) => constants.getOrElse(t.image, Vector.empty)
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
      }.toVector
    }
  }

  def constants(blocks: Vector[DbtSyntax.Block], before: Int): Map[String, Vector[String]] = {
    var scopes = List(Map.empty[String, Vector[String]])
    blocks.takeWhile(_.range.getStartOffset < before).foreach { b =>
      val ts = b.tokens
      val tag = if (b.tag) ts.headOption.map(_.image).getOrElse("") else ""
      if (Set("for", "macro", "test", "call", "with", "block").contains(tag)) {
        val outer = scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
        val bindings = if (tag == "for" && ts.lift(2).exists(_.image == "in")) ts.lift(1).map(t =>
          t.image -> value(ts.drop(3), outer)).toMap else Map.empty[String, Vector[String]]
        scopes = bindings :: scopes
      }
      if (Set("endfor", "endmacro", "endtest", "endcall", "endwith", "endblock").contains(tag) && scopes.size > 1) scopes = scopes.tail
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
      }
    }
    scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
  }

  def targets(w: DbtWorkspace, root: DbtRoot, call: DbtSyntax.Call, env: Map[String, Vector[String]]): Vector[PsiElement] = {
    val args = call.arguments.filter(_.keyword.isEmpty).map(a => value(a.tokens, env))
    val base = call.name.stripPrefix("builtins.")
    base match {
      case "ref" | "function" | "doc" =>
        val kinds = if (base == "ref") Set("model", "seed", "snapshot") else Set(base)
        val qualified = args.size >= 2
        val names = args.lift(if (qualified) 1 else 0).getOrElse(Vector.empty)
        val namespaces = if (qualified) args.head.map(Some(_)) else Vector(None)
        val version = call.arguments.find(a => a.keyword.exists(Set("v", "version"))).flatMap(_.literal)
        (for (name <- names; ns <- namespaces; s <- w.declarations(kinds, name, root, ns)
          if version.fold(s.version.isEmpty)(v => s.version.contains(v))) yield s.element).distinct
      case "source" if args.size == 2 => w.symbols.filter(s => s.kind == "source_table" && args.head.contains(s.owner) && args(1).contains(s.name) &&
        w.root(s.file).exists(r => w.namespaceVisible(root, r, qualified = false))).map(_.element)
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
      val targets = this.targets(w, root, delegated, env)
      delegated.arguments.foreach { a => a.tokens.filter(t => !t.string).flatMap(t => bindings.get(t.image)).foreach { case (argument, _) =>
        if (targets.nonEmpty) result += argument -> targets
      }}
      if (targets.isEmpty) w.declarations(Set("macro"), delegated.name, root).foreach { next =>
        wrapperTargets(w, root, next, delegated, env, visited + macroSymbol.id).foreach { case (arg, candidates) =>
          arg.tokens.flatMap(t => bindings.get(t.image)).foreach { case (original, _) => result += original -> candidates }
        }
      }
    }
    result.toVector
  }
}
