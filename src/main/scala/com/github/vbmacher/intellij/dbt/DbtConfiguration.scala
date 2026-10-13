package com.github.vbmacher.intellij.dbt

import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.yaml.psi.{YAMLKeyValue, YAMLMapping}

private[dbt] object DbtConfiguration {
  val sections: Map[String, String] = Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot", "analyses" -> "analysis",
    "tests" -> "test", "data_tests" -> "test", "sources" -> "source", "exposures" -> "exposure", "groups" -> "group",
    "metrics" -> "metric", "semantic-models" -> "semantic_model", "semantic_models" -> "semantic_model",
    "saved-queries" -> "saved_query", "saved_queries" -> "saved_query", "functions" -> "function", "checks" -> "check")

  def pathTargets(w: DbtWorkspace, root: DbtRoot, kv: YAMLKeyValue): Vector[PsiElement] = {
    val parts = DbtSyntax.yamlAncestors(kv).reverse
    val kind = parts.headOption.flatMap(sections.get)
    val namespace = parts.lift(1)
    val path = parts.drop(2)
    val roots = w.roots.filter(r => namespace.contains(r.name) && w.namespaceVisible(root, r, qualified = false))
    if (parts.size == 2) roots.map(_.config.getContainingFile)
    else roots.flatMap { r =>
      val symbols = w.symbols.filter(s => (kind.contains(s.kind) || kind.contains("source") && s.kind == "source_table") &&
        w.owner(s).contains(r) && path.lastOption.contains(s.name) && (if (s.kind == "source_table") path.dropRight(1).lastOption.contains(s.owner)
        else !s.attributes.contains("code") || r.relative(s.file.getVirtualFile).exists(relative => r.pathKinds.exists { case (k, base) =>
          k == s.kind && r.pathContains(base, relative) && relative.stripPrefix(base.stripSuffix("/") + "/").split("/").dropRight(1).toVector == path.dropRight(1)
        })))
      val folders = r.pathKinds.filter(p => kind.contains(p._1)).flatMap { case (_, base) =>
        Option(r.directory.findFileByRelativePath((Vector(base) ++ path).mkString("/"))).filter(_.isDirectory)
          .flatMap(d => Option(com.intellij.psi.PsiManager.getInstance(w.project).findDirectory(d)))
      }
      folders ++ symbols.filter(s => w.declarations(Set(s.kind), s.name, root, namespace).contains(s)).map(_.element)
    }.distinct
  }
  def propertyAttributes(w: DbtWorkspace, symbol: DbtSymbol): Map[String, String] = w.owner(symbol).toVector.flatMap { root =>
    val sectionNames = sections.collect { case (key, kind) if kind == symbol.kind || kind == "source" && symbol.kind == "source_table" => key }.toVector
    val path = if (symbol.kind == "source_table") Vector(symbol.owner, symbol.name) else if (symbol.kind == "source") Vector(symbol.name)
      else root.relative(symbol.file.getVirtualFile).toVector.flatMap { relative =>
        root.pathKinds.find { case (_, base) => root.pathContains(base, relative) }.toVector.flatMap { case (_, base) =>
          relative.stripPrefix(base.stripSuffix("/") + "/").split("/").toVector.dropRight(1) :+ symbol.name
        }
      }
    (Vector(root) ++ w.packageOwner(root)).flatMap { config =>
      val top = sectionNames.flatMap(DbtYaml.map(config.config, _))
      var current = top.flatMap(DbtYaml.map(_, root.name))
      var result = top ++ current
      path.foreach { part => current = current.flatMap(DbtYaml.map(_, part)); result ++= current }
      result
    }
  }.foldLeft(Map.empty[String, String])((attrs, mapping) => attrs ++ DbtYaml.attributes(mapping).map { case (k, v) => k.stripPrefix("+") -> v })
  private def levels(config: DbtRoot, resource: DbtRoot, file: PsiFile, name: String): Vector[YAMLMapping] = {
    val kind = resource.kind(file.getVirtualFile)
    val section = kind.map(k => if (k == "analysis") "analyses" else k + "s")
    val path = for (k <- kind; relative <- resource.relative(file.getVirtualFile); entry <- resource.pathKinds.find { case (kk, p) => kk == k && resource.pathContains(p, relative) })
      yield relative.stripPrefix(entry._2.stripSuffix("/") + "/").split("/").toVector.dropRight(1) :+ name
    val sections = section.flatMap(DbtYaml.map(config.config, _)).toVector
    val namespace = sections.flatMap(DbtYaml.map(_, resource.name))
    var result = sections ++ namespace
    var current = namespace
    path.toVector.flatten.foreach { part => current = current.flatMap(DbtYaml.map(_, part)); result ++= current }
    result
  }

  def inherited(w: DbtWorkspace, file: PsiFile, name: String): Map[String, String] = w.root(file).toVector.flatMap { resource =>
    (Vector(resource) ++ w.packageOwner(resource)).flatMap(config => levels(config, resource, file, name))
  }.foldLeft(Map.empty[String, String]) { (attrs, mapping) => attrs ++ DbtYaml.attributes(mapping).map { case (key, value) => key.stripPrefix("+") -> value } }

  def locations(w: DbtWorkspace, file: PsiFile, key: String): Vector[PsiElement] = {
    val root = w.root(file).toVector
    val symbols = w.symbols.filter(s => s.file == file && s.attributes.contains("code"))
    val inherited = root.flatMap { r =>
      val name = symbols.map(_.name).distinct.headOption.getOrElse(file.getVirtualFile.getNameWithoutExtension)
      (Vector(r) ++ w.packageOwner(r)).flatMap(config => levels(config, r, file, name))
        .flatMap(m => Vector(key, "+" + key).flatMap(k => Option(m.getKeyValueByKey(k))))
    }
    val props = symbols.flatMap(w.properties).flatMap(_.mapping.toVector)
    val patches = props.flatMap(m => DbtYaml.map(m, "config").toVector.flatMap(c => Option(c.getKeyValueByKey(key))))
    val inline = DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).filter(_.name == "config").flatMap { call =>
      call.arguments.filter(_.keyword.contains(key)).flatMap { argument =>
        argument.keywordRange.map(range => DbtSymbol("configuration", key, root.headOption.map(_.name).getOrElse("dbt"), file, range).element)
      }
    }
    (inherited ++ patches ++ inline).distinct
  }
}
