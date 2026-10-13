package com.github.vbmacher.intellij.dbt

import com.intellij.psi.PsiFile
import scala.collection.mutable.ArrayBuffer

/** Lexical bindings over Jinjava tokens. No runtime values or macro execution. */
private[dbt] object DbtLocals {
  def references(file: PsiFile, blocks: Vector[DbtSyntax.Block], workspace: DbtWorkspace): Vector[DbtNavigationReference] = {
    val result = ArrayBuffer.empty[DbtNavigationReference]
    var scopes = List(Map.empty[String, DbtSymbol])
    var captures = List.empty[DbtSyntax.Token]
    def bind(t: DbtSyntax.Token): Unit = if (!t.string && t.image.matches("[A-Za-z_][A-Za-z_0-9]*")) {
      val parameter = workspace.symbols.find(s => s.file == file && s.kind == "parameter" && s.range == t.range)
      scopes = (scopes.head + (t.image -> parameter.getOrElse(DbtSymbol("local", t.image, "jinja", file, t.range)))) :: scopes.tail
    }
    blocks.foreach { b =>
      val ts = b.tokens
      val tag = if (b.tag) ts.headOption.map(_.image).getOrElse("") else ""
      if (Set("endfor", "endmacro", "endtest", "endcall", "endwith", "endblock").contains(tag) && scopes.size > 1) scopes = scopes.tail
      val declarationRanges = ArrayBuffer.empty[com.intellij.openapi.util.TextRange]
      def declaration(t: DbtSyntax.Token): Unit = { bind(t); declarationRanges += t.range }
      if (tag == "endset" && captures.nonEmpty) { bind(captures.head); captures = captures.tail }
      val attributeAssignment = tag == "set" && ts.lift(2).exists(_.image == ".")
      val setTargets = if (tag == "set" && !attributeAssignment) ts.drop(1).takeWhile(t => t.image != "=" && t.image != "|")
        .filter(t => !t.string && t.image.matches("[A-Za-z_][A-Za-z_0-9]*")) else Vector.empty
      declarationRanges ++= setTargets.map(_.range)
      if (Set("for", "macro", "test", "call", "with", "block").contains(tag)) scopes = Map.empty[String, DbtSymbol] :: scopes
      val withTargets = if (tag == "with") {
        var depth = 0
        ts.drop(1).zipWithIndex.collect {
          case (t, i) if {
            if (Set("(", "[", "{").contains(t.image)) depth += 1
            if (Set(")", "]", "}").contains(t.image)) depth -= 1
            depth == 0 && ts.lift(i + 2).exists(_.image == "=")
          } => t
        }
      } else Vector.empty
      declarationRanges ++= withTargets.map(_.range)
      val loopTargets = if (tag == "for") ts.drop(1).takeWhile(_.image != "in").filter(_.image != ",") else Vector.empty
      declarationRanges ++= loopTargets.map(_.range)
      val callParameters = if (tag == "call") DbtSyntax.parameters(b) else Vector.empty
      declarationRanges ++= callParameters.map(_.range)
      if (Set("macro", "test").contains(tag)) DbtSyntax.parameters(b).foreach(declaration)
      ts.zipWithIndex.foreach { case (t, i) =>
        val previous = ts.lift(i - 1).map(_.image)
        val isSetTarget = tag == "set" && i == 1
        if (!t.string && !declarationRanges.contains(t.range) && !isSetTarget && !previous.contains(".") &&
          !ts.lift(i + 1).exists(_.image == "=")) {
          scopes.iterator.flatMap(_.get(t.image)).take(1).foreach { s =>
            result += new DbtNavigationReference(file, t.range, Vector(s.element), t.image)
          }
        }
      }
      // A loop's iterable is evaluated in the outer scope before its targets are bound.
      loopTargets.foreach(bind)
      callParameters.foreach(bind)
      // All with-block values use the outer scope, including same-named bindings.
      withTargets.foreach(bind)
      // Assignment becomes visible after its RHS, preserving outer bindings in `set x = x`.
      if (tag == "set") {
        val attribute = ts.lift(2).exists(_.image == ".") && ts.lift(3).nonEmpty
        if (!attribute && ts.exists(_.image == "=")) setTargets.foreach(bind)
        else if (!attribute) setTargets.headOption.foreach(t => captures = t :: captures)
        if (attribute) {
          val name = ts(1).image + "." + ts(3).image
          val field = DbtSymbol("namespace attribute", name, "jinja", file, ts(3).range)
          // Mutating a namespace does not create a new namespace binding in a loop.
          val owner = scopes.indexWhere(_.contains(ts(1).image))
          if (owner >= 0 && !scopes.exists(_.contains(name))) scopes = scopes.updated(owner, scopes(owner) + (name -> field))
        }
        if (ts.lift(3).exists(_.image == "namespace")) ts.drop(5).sliding(2).foreach {
          case Vector(field, eq) if eq.image == "=" =>
            val name = ts(1).image + "." + field.image
            scopes = (scopes.head + (name -> DbtSymbol("namespace attribute", name, "jinja", file, field.range))) :: scopes.tail
          case _ => ()
        }
      }
      ts.sliding(3).foreach {
        case Vector(namespace, dot, field) if dot.image == "." =>
          val name = namespace.image + "." + field.image
          scopes.iterator.flatMap(_.get(name)).take(1).foreach(s => result += new DbtNavigationReference(file, field.range, Vector(s.element), name))
        case _ => ()
      }
    }
    result.toVector
  }
}
