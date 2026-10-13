package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import org.jetbrains.yaml.psi.{YAMLKeyValue, YAMLMapping}

/** Resource configuration in `dbt_project.yml`: `<section>: <project>: <folder>...: <resource>` with `+`-prefixed keys. */
private[dbt] object DbtConfiguration {
  val sections: Map[String, String] = Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot", "analyses" -> "analysis",
    "tests" -> "test", "data_tests" -> "test", "sources" -> "source", "exposures" -> "exposure", "groups" -> "group",
    "metrics" -> "metric", "semantic-models" -> "semantic_model", "semantic_models" -> "semantic_model",
    "saved-queries" -> "saved_query", "saved_queries" -> "saved_query", "functions" -> "function", "checks" -> "check")

  /** Folders and resources configured by a key path of `dbt_project.yml`. */
  def pathTargets(w: DbtWorkspace, root: DbtRoot, kv: YAMLKeyValue): Vector[PsiElement] = {
    val parts = DbtYaml.ancestors(kv).reverse
    val kind = parts.headOption.flatMap(sections.get)
    val namespace = parts.lift(1)
    val path = parts.drop(2)
    val roots = w.roots.filter(r => namespace.contains(r.name) && w.namespaceVisible(root, r, qualified = false))
    if (parts.size == 2) roots.map(_.config.getContainingFile)
    else roots.flatMap { r =>
      val symbols = w.symbols.filter(s => (kind.contains(s.kind) || kind.contains("source") && s.kind == "source_table") &&
        w.owner(s).contains(r) && path.lastOption.contains(s.name) && (if (s.kind == "source_table") path.dropRight(1).lastOption.contains(s.owner)
        else !s.isCode || r.relative(s.file.getVirtualFile).exists(relative => r.pathKinds.exists { case (k, base) =>
          k == s.kind && r.folders(base, relative).contains(path.dropRight(1))
        })))
      val folders = r.pathKinds.filter(p => kind.contains(p._1)).flatMap { case (_, base) =>
        Option(r.directory.findFileByRelativePath((base +: path).mkString("/"))).filter(_.isDirectory)
          .flatMap(d => Option(PsiManager.getInstance(w.project).findDirectory(d)))
      }
      folders ++ symbols.filter(s => w.declarations(Set(s.kind), s.name, root, namespace).contains(s)).map(_.element)
    }.distinct
  }

  /** Configuration inherited by a property declaration from its project and the project installing it. */
  def propertyAttributes(w: DbtWorkspace, symbol: DbtSymbol): Map[String, String] = merge(w.owner(symbol).toVector.flatMap { root =>
    val sectionNames = sections.collect { case (key, kind) if kind == symbol.kind || kind == "source" && symbol.kind == "source_table" => key }.toVector
    val path = symbol.kind match {
      case "source_table" => Vector(symbol.owner, symbol.name)
      case "source" => Vector(symbol.name)
      case _ => root.resourceFolders(symbol.file.getVirtualFile).toVector.flatMap(_._2 :+ symbol.name)
    }
    configurationRoots(w, root).flatMap(config => walk(config, sectionNames, root.name, path))
  })

  /** Configuration inherited by a resource file from its project and the project installing it. */
  def inherited(w: DbtWorkspace, file: PsiFile, name: String): Map[String, String] = merge(w.root(file).toVector.flatMap(levels(w, _, file, name)))

  /** Every place configuring a key of the resources of a file: project levels, property patches and inline `config()` calls. */
  def locations(w: DbtWorkspace, file: PsiFile, key: String): Vector[PsiElement] = {
    val root = w.root(file).toVector
    val symbols = w.codeSymbols(file)
    val name = symbols.map(_.name).distinct.headOption.getOrElse(file.getVirtualFile.getNameWithoutExtension)
    val inherited = root.flatMap(levels(w, _, file, name)).flatMap(m => Vector(key, "+" + key).flatMap(DbtYaml.keyValue(m, _)))
    val patches = symbols.flatMap(w.properties).flatMap(_.mapping.toVector).flatMap(m => DbtYaml.map(m, "config").flatMap(DbtYaml.keyValue(_, key)))
    val inline = DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).filter(_.name == "config").flatMap { call =>
      call.arguments.filter(_.keyword.contains(key)).flatMap(_.keywordRange)
        .map(range => DbtSymbol("configuration", key, root.headOption.map(_.name).getOrElse("dbt"), file, range).element)
    }
    (inherited ++ patches ++ inline).distinct
  }

  private def configurationRoots(w: DbtWorkspace, resource: DbtRoot): Vector[DbtRoot] = resource +: w.packageOwner(resource).toVector

  private def levels(w: DbtWorkspace, resource: DbtRoot, file: PsiFile, name: String): Vector[YAMLMapping] =
    resource.resourceFolders(file.getVirtualFile).toVector.flatMap { case (kind, folders) =>
      val section = if (kind == "analysis") "analyses" else kind + "s"
      configurationRoots(w, resource).flatMap(config => walk(config, Vector(section), resource.name, folders :+ name))
    }

  /** Configuration mappings from the sections down a project path, outermost first. */
  private def walk(config: DbtRoot, sectionNames: Vector[String], namespace: String, path: Vector[String]): Vector[YAMLMapping] = {
    val top = sectionNames.flatMap(DbtYaml.map(config.config, _))
    top ++ path.scanLeft(top.flatMap(DbtYaml.map(_, namespace)))((current, part) => current.flatMap(DbtYaml.map(_, part))).flatten
  }

  /** Inner levels override outer ones; `+` prefixes are only a syntax marker. */
  private def merge(mappings: Vector[YAMLMapping]): Map[String, String] = mappings.foldLeft(Map.empty[String, String]) { (attrs, mapping) =>
    attrs ++ DbtYaml.attributes(mapping).map { case (key, value) => key.stripPrefix("+") -> value }
  }
}
