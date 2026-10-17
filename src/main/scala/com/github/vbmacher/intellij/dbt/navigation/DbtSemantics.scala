package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtSymbol, DbtWorkspace}
import com.intellij.psi.PsiFile

/** Semantic layer names, including `entity__dimension` join paths. */
private[navigation] object DbtSemantics {
  def targets(w: DbtWorkspace, root: DbtRoot, file: PsiFile, offset: Int, kind: String, name: String): Vector[DbtSymbol] = {
    val resourceKind = Map("Dimension" -> "dimension", "TimeDimension" -> "dimension", "Entity" -> "entity", "Metric" -> "metric").getOrElse(kind, kind)
    val path = name.split("__").toVector
    val field = path.last
    val scope = w.enclosing("semantic_model", file, offset)
    val candidates = w.symbols.filter(s => s.kind == resourceKind && s.name == field && s.namespace == root.name && w.root(s.file).contains(root))
    if (path.size == 1 && scope.nonEmpty && resourceKind != "metric") candidates.filter(s => scope.exists(_.name == s.owner))
    else if (path.size > 1) {
      val entities = w.symbols.filter(s => s.kind == "entity" && s.namespace == root.name && w.root(s.file).contains(root))
      var owners = Set.empty[String]
      var known = true
      path.dropRight(1).foreach { name =>
        val references = entities.filter(e => e.name == name && (owners.isEmpty || owners(e.owner)))
        val joins = entities.filter(e => e.name == name && e.attributes.get("type").exists(Set("primary", "unique", "natural")))
        if (references.isEmpty || joins.isEmpty) known = false
        owners = joins.map(_.owner).toSet
      }
      if (known) candidates.filter(s => owners(s.owner)) else Vector.empty
    } else candidates
  }
}
