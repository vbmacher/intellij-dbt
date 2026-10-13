package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

import scala.collection.mutable

/** A package macro uses the namespace of the node that ultimately calls it. */
private[dbt] object DbtVariableContexts {
  def roots(w: DbtWorkspace, file: PsiFile, fallback: DbtRoot, offset: Option[Int] = None): Vector[DbtRoot] = {
    if (!fallback.kind(file.getVirtualFile).contains("macro")) return Vector(fallback)
    val blocks = DbtSyntax.inFile(file)
    val macros = w.symbols.filter(s => s.kind == "macro" && s.file == file &&
      offset.forall(p => DbtSyntax.encloses(blocks, s.range.getStartOffset, "endmacro", new TextRange(p, p))))
    if (macros.isEmpty) return Vector(fallback)
    val calls = w.files.flatMap { caller =>
      ProgressManager.checkCanceled()
      w.root(caller).toVector.filter(_.resource(caller)).flatMap { root =>
        val bs = DbtYamlContexts.blocks(caller, root)
        val local = DbtLocals.references(caller, bs, w)
        val declarations = w.symbols.filter(s => s.kind == "macro" && s.file == caller)
        bs.flatMap(DbtSyntax.calls).filter(c => DbtYamlContexts.allows(caller, root, c.range.getStartOffset, c.name) &&
          !local.exists(_.getRangeInElement.intersects(c.range))).flatMap { call =>
          val owner = declarations.find(s => DbtSyntax.encloses(bs, s.range.getStartOffset, "endmacro", call.range))
          w.macros(call, root).map(target => (target.id, owner.map(_.id), root))
        }
      }
    }
    var frontier = macros.map(_.id).toSet
    val seen = mutable.Set.empty[String]
    val found = mutable.ArrayBuffer.empty[DbtRoot]
    var depth = 0
    while (frontier.nonEmpty && depth < 32) {
      ProgressManager.checkCanceled()
      val incoming = calls.filter(c => frontier(c._1))
      incoming.filter(_._2.isEmpty).foreach(c => found += c._3)
      frontier = incoming.flatMap(_._2).filter(seen.add).toSet
      depth += 1
    }
    val callers = found.toVector.distinct
    if (frontier.nonEmpty) Vector.empty else if (callers.nonEmpty) callers else Vector(fallback)
  }
}
