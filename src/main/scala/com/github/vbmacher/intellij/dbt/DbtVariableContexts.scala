package com.github.vbmacher.intellij.dbt

import com.intellij.psi.PsiFile

/** A package macro uses the namespace of the node that ultimately calls it. */
private[dbt] object DbtVariableContexts {
  def roots(w: DbtWorkspace, file: PsiFile, fallback: DbtRoot): Vector[DbtRoot] = {
    if (!fallback.kind(file.getVirtualFile).contains("macro")) return Vector(fallback)
    val macros = w.symbols.filter(s => s.kind == "macro" && s.file == file)
    if (macros.isEmpty) return Vector(fallback)
    val callers = w.files.filter(f => w.symbols.exists(s => s.file == f && s.attributes.contains("code") && s.kind != "macro"))
      .flatMap { caller => w.root(caller).toVector.filter { root =>
        DbtSyntax.inFile(caller).flatMap(DbtSyntax.calls).exists { call =>
          val parts = call.name.split("\\.")
          w.declarations(Set("macro"), parts.last, root, if (parts.length == 2) Some(parts.head) else None)
            .exists(m => macros.exists(_.id == m.id))
        }
      }}.distinct
    if (callers.nonEmpty) callers else Vector(fallback)
  }
}
