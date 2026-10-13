package com.github.vbmacher.intellij.dbt

import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.yaml.psi.YAMLMapping

private[dbt] object DbtConfiguration {
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
