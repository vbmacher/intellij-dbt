package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiRecursiveElementWalkingVisitor}
import org.jetbrains.yaml.psi._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

private[dbt] object DbtYamlNavigation {
  def references(file: PsiFile, w: DbtWorkspace, r: DbtRoot): Vector[DbtNavigationReference] = {
    if (!file.isInstanceOf[YAMLFile]) return Vector.empty
    val result = ArrayBuffer.empty[DbtNavigationReference]

    def add(e: PsiElement, targets: Iterable[PsiElement], name: String): Unit =
      result += new DbtNavigationReference(file, e.getTextRange, targets.toVector.distinct, name)

    def lookup(kinds: Set[String], name: String): Vector[PsiElement] = {
      val split = name.split("\\.", 2)
      w.declarations(kinds, split.last, r, if (split.length == 2) Some(split.head) else None).map(_.element)
    }

    def parentMapping(e: PsiElement): Option[YAMLMapping] = Iterator.iterate(e.getParent)(_.getParent)
      .takeWhile(_ != null).collectFirst { case m: YAMLMapping => m }

    def containingName(e: PsiElement): Option[String] = Iterator.iterate(e.getParent)(_.getParent)
      .takeWhile(_ != null).collect { case m: YAMLMapping => m }.flatMap(m => DbtYaml.string(m, "name")).toSeq.headOption

    def test(name: String, element: PsiElement): Unit = {
      val defs = lookup(Set("generic_test"), name)
      val builtin = Set("unique", "not_null", "accepted_values", "relationships").contains(name)
      add(element, if (defs.nonEmpty) defs else if (builtin) Vector(DbtNavigation.documentation(file, name, "data-tests", r)) else Vector.empty, name)
    }

    file.accept(new PsiRecursiveElementWalkingVisitor {
      override def visitElement(e: PsiElement): Unit = {
        val ancestry = DbtSyntax.yamlAncestors(e)
        if (ancestry.contains("meta")) { super.visitElement(e); return }
        if (ancestry.contains("unit_tests") && !r.supports(8)) { super.visitElement(e); return }
        e match {
          case scalar: YAMLScalar if !ancestry.contains("vars") =>
            val name = scalar.getTextValue
            val key = scalar.getParent match {
              case kv: YAMLKeyValue => kv.getKeyText;
              case _ => ""
            }
            if (!name.contains("{{")) key match {
              case "package" | "git" | "local" if Set("packages.yml", "dependencies.yml", "package-lock.yml").contains(file.getName) =>
                val declaration = parentMapping(scalar)
                val projectName = if (key == "local") {
                  Option(r.directory.findFileByRelativePath(name)).flatMap(d => Option(d.findChild("dbt_project.yml")))
                    .flatMap(v => Option(com.intellij.psi.PsiManager.getInstance(w.project).findFile(v))).flatMap(DbtYaml.mapping).flatMap(DbtYaml.string(_, "name"))
                } else if (key == "git") declaration.flatMap(DbtYaml.string(_, "subdirectory"))
                  .orElse(Some(name.stripSuffix(".git").split("/").last)) else Some(name.split("/").last)
                val installName = declaration.flatMap(DbtYaml.string(_, "name")).orElse(projectName.map(_.split("/").last))
                val installed = w.roots.filter(p => w.packageOwner(p).contains(r) &&
                  (projectName.contains(p.name) || installName.contains(p.directory.getName)))
                add(scalar, installed.map(p => p.config.getContainingFile), name)
              case "version" | "revision" if Set("packages.yml", "dependencies.yml").contains(file.getName) =>
                val declaration = parentMapping(scalar)
                val identity = declaration.flatMap(m => DbtYaml.string(m, "package").orElse(DbtYaml.string(m, "git")))
                val lock = Option(r.directory.findChild("package-lock.yml")).flatMap(v => Option(com.intellij.psi.PsiManager.getInstance(w.project).findFile(v))).flatMap(DbtYaml.mapping)
                val entries = lock.toVector.flatMap(DbtYaml.items(_, "packages")).filter(m => identity.exists(i => DbtYaml.string(m, "package").orElse(DbtYaml.string(m, "git")).contains(i)))
                add(scalar, entries.flatMap(m => Option(m.getKeyValueByKey(key))), name)
              case "name" if file.getName == "dependencies.yml" && ancestry.contains("projects") =>
                add(scalar, w.roots.filter(p => p.name == name && w.namespaceVisible(r, p, qualified = true)).map(_.config.getContainingFile), name)
              case "name" =>
                val exports = w.symbols.filter(s => s.file == file && s.range == scalar.getTextRange && s.kind == "export")
                exports.foreach(e => add(scalar, lookup(Set("saved_query"), e.owner), name))
                val props = w.symbols.filter(s => s.file == file && s.range == scalar.getTextRange && s.attributes.contains("property"))
                props.foreach { p =>
                  add(scalar, w.declarations(Set(p.kind), p.name, r, Some(p.namespace))
                    .filterNot(_.attributes.contains("property")).filter(_.version == p.version).map(_.element), name)
                }
                val semanticDeclaration = w.symbols.exists(s => Set("metric", "measure", "dimension", "entity").contains(s.kind) &&
                  s.file == file && s.range == scalar.getTextRange)
                if (ancestry.exists(Set("measures", "metrics")) && !semanticDeclaration &&
                  (!ancestry.contains("input_metrics") || r.supports(12)))
                  add(scalar, lookup(Set(if (ancestry.exists(Set("measures", "base_measure", "conversion_measure"))) "measure" else "metric"), name), name)
              case "group" | "+group" => add(scalar, lookup(Set("group"), name), name)
              case "model" if ancestry.contains("unit_tests") => add(scalar, lookup(Set("model"), name), name)
              case "fixture" =>
                val extensions = parentMapping(scalar).flatMap(DbtYaml.string(_, "format")) match {
                  case Some("csv") => Vector("csv")
                  case Some("sql") => Vector("sql")
                  case None => Vector("csv", "sql")
                  case _ => Vector.empty
                }
                val target = r.pathKinds.filter(_._1 == "test").flatMap { case (_, path) =>
                  extensions.flatMap(ext => Option(r.directory.findFileByRelativePath(s"$path/fixtures/$name.$ext")))
                }.flatMap(v => Option(com.intellij.psi.PsiManager.getInstance(w.project).findFile(v)))
                add(scalar, target.flatMap(f => w.symbols.filter(s => s.kind == "fixture" && s.file == f).map(_.element)), name)
              case "materialized" | "+materialized" => add(scalar, DbtNavigation.materializations(w, r, name).map(_.element), name)
              case "strategy" => add(scalar, lookup(Set("macro"), s"snapshot_${name}_strategy"), name)
              case "standard_granularity_column" =>
                val owners = containingName(scalar).toVector ++ (if (file.getName == "dbt_project.yml")
                  ancestry.filter(n => w.declarations(Set("model"), n, r).nonEmpty) else Vector.empty)
                add(scalar, w.symbols.filter(s => s.kind == "column" && s.name == name && w.owner(s).contains(r) && owners.contains(s.owner)).map(_.element), name)
              case "expr" if ancestry.contains("metrics") =>
                val metrics = w.symbols.filter(s => s.kind == "metric" && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(scalar.getTextOffset)))
                val aliases = w.symbols.filter(s => s.kind == "metric_alias" && w.owner(s).contains(r) && metrics.exists(_.name == s.owner))
                val escaper = scalar.createLiteralTextEscaper()
                val host = new TextRange(0, scalar.getTextLength)
                val decoded = new java.lang.StringBuilder
                if (escaper.decode(host, decoded)) DbtSyntax.tokens(decoded.toString, i => scalar.getTextOffset + escaper.getOffsetInHost(i, host)).foreach { token =>
                  val defs = if (token.string) Vector.empty else aliases.filter(_.name == token.image) ++
                    w.symbols.filter(s => s.kind == "column" && s.name == token.image && w.owner(s).contains(r) && metrics.exists(m => m.owner.nonEmpty && m.owner == s.owner))
                  if (defs.nonEmpty) result += new DbtNavigationReference(file, token.range, defs.map(_.element), token.image)
                }
              case "expr" if name.matches("[A-Za-z_][A-Za-z_0-9]*") =>
                val semantic = w.symbols.filter(s => s.kind == "semantic_model" && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(scalar.getTextOffset)))
                val modelNames = semantic.flatMap(s => if (s.attributes.contains("embedded")) Vector(s.owner) else s.mapping.toVector
                  .flatMap(m => DbtYaml.string(m, "model")).flatMap(t => DbtSyntax.blocks("{{" + t + "}}").flatMap(DbtSyntax.calls).flatMap(_.arguments.flatMap(_.literal))))
                add(scalar, w.symbols.filter(s => s.kind == "column" && s.name == name && s.namespace == r.name && modelNames.contains(s.owner)).map(_.element), name)
              case "defined_in" =>
                add(scalar, w.symbols.filter(s => s.attributes.contains("code") && s.namespace == r.name && w.root(s.file).contains(r) &&
                  s.file.getVirtualFile.getNameWithoutExtension == name).map(_.element), name)
              case "primary_entity" => add(scalar, DbtSemantics.targets(w, r, file, scalar.getTextOffset, "entity", name).map(_.element), name)
              case "agg_time_dimension" => add(scalar, DbtSemantics.targets(w, r, file, scalar.getTextOffset, "dimension", name).map(_.element), name)
              case "measure" | "base_measure" | "conversion_measure" => add(scalar, DbtSemantics.targets(w, r, file, scalar.getTextOffset, "measure", name).map(_.element), name)
              case "metric" | "numerator" | "denominator" => add(scalar, lookup(Set("metric"), name), name)
              case "input_metric" | "base_metric" | "conversion_metric" if r.supports(12) => add(scalar, lookup(Set("metric"), name), name)
              case "entity" if r.supports(6) && ancestry.contains("metrics") =>
                add(scalar, DbtSemantics.targets(w, r, file, scalar.getTextOffset, "entity", name).map(_.element), name)
              case "profile" if file.getName == "dbt_project.yml" => add(scalar, DbtNavigation.profile(w, r).toVector, name)
              case "target" if file.getName == "profiles.yml" => add(scalar, parentMapping(scalar).toVector.flatMap(p =>
                DbtYaml.map(p, "outputs").flatMap(m => Option(m.getKeyValueByKey(name)))), name)
              case "field" if ancestry.exists(Set("tests", "data_tests")) =>
                // The relationships target must supply a proved ref/source identity.
                val mapping = parentMapping(scalar)
                val to = mapping.flatMap(DbtYaml.value(_, "to")).collect { case s: YAMLScalar => s }
                val refs = to.toVector.flatMap(t => DbtSyntax.blocks(if (t.getTextValue.contains("{{")) t.getTextValue else "{{" + t.getTextValue + "}}").flatMap(DbtSyntax.calls))
                  .filter(c => Set("ref", "source", "builtins.ref", "builtins.source").contains(c.name))
                val targets = refs.flatMap(c => DbtStaticCalls.targets(w, r, c, Map.empty)).collect { case e: DbtSymbolElement => e.symbol }
                val columns = if (targets.size != 1) Vector.empty else w.symbols.filter(s => s.kind == "column" && s.name == name &&
                  targets.exists(t => s.namespace == t.namespace && w.root(s.file) == w.root(t.file) && s.version == t.version &&
                    s.owner == (if (t.kind == "source_table") t.owner + "." + t.name else t.name)))
                if (!ancestry.contains("arguments") || r.supports(10, 5)) add(scalar, columns.map(_.element), name)
              case _ =>
                if (ancestry.exists(Set("metrics", "group_by", "dimensions", "entities")) && scalar.getParent.isInstanceOf[YAMLSequenceItem])
                  add(scalar, lookup(Set(if (ancestry.contains("metrics")) "metric" else if (ancestry.contains("entities")) "entity" else "dimension"), name), name)
            }
            if (ancestry.exists(Set("tests", "data_tests")) && scalar.getParent.isInstanceOf[YAMLSequenceItem]) test(name, scalar)
          case kv: YAMLKeyValue if file.getName == "dbt_project.yml" && ancestry.lastOption.exists(DbtConfiguration.sections.contains) =>
            val key = kv.getKeyText
            val kind = ancestry.lastOption.flatMap(DbtConfiguration.sections.get)
            val names = ancestry.dropRight(1)
              .filterNot(n => n == r.name || n.startsWith("+"))
            val targets = w.symbols.filter(s => kind.contains(s.kind) && w.owner(s).contains(r) &&
              (names.isEmpty || names.contains(s.name) || r.relative(s.file.getVirtualFile).exists(p => names.forall(p.split("/").contains(_)))))
            val locations = if (key.startsWith("+")) targets.filter(_.attributes.contains("code"))
              .flatMap(s => DbtConfiguration.locations(w, s.file, key.drop(1))).filterNot(_ == kv)
            else DbtConfiguration.pathTargets(w, r, kv)
            Option(kv.getKey).foreach(e => add(e, locations, key))
          case kv: YAMLKeyValue if ancestry.contains("config") =>
            val owners = w.symbols.filter(s => s.file == file && s.attributes.contains("property") && s.mapping.exists(_.getTextRange.contains(kv.getTextRange)))
            val code = owners.flatMap(p => w.declarations(Set(p.kind), p.name, r, Some(p.namespace))).filter(_.attributes.contains("code"))
            Option(kv.getKey).foreach(e => add(e, code.flatMap(s => DbtConfiguration.locations(w, s.file, kv.getKeyText)).filterNot(_ == kv), kv.getKeyText))
          case kv: YAMLKeyValue if ancestry.exists(Set("tests", "data_tests")) =>
            if (kv.getParent.isInstanceOf[YAMLMapping] && kv.getParent.getParent.isInstanceOf[YAMLSequenceItem])
              Option(kv.getKey).foreach(e => test(kv.getKeyText, e))
            else if ((!ancestry.contains("arguments") || r.supports(10, 5)) &&
              !Set("arguments", "config", "name", "alias", "where", "severity", "to", "field", "values").contains(kv.getKeyText)) {
              val testName = ancestry.find(n => w.symbols.exists(s => s.kind == "generic_test" && (s.name == n || s.namespace + "." + s.name == n)))
              testName.foreach(n => Option(kv.getKey).foreach(e => add(e, w.symbols.filter(s => s.kind == "parameter" &&
                s.owner == n.split("\\.").last && s.name == kv.getKeyText && lookup(Set("generic_test"), n).exists(d => d.getContainingFile == s.file)).map(_.element), kv.getKeyText)))
            }
          case kv: YAMLKeyValue if ancestry.contains("overrides") && ancestry.contains("macros") =>
            Option(kv.getKey).foreach(e => add(e, lookup(Set("macro"), kv.getKeyText), kv.getKeyText))
          case kv: YAMLKeyValue if ancestry.contains("overrides") && ancestry.contains("vars") =>
            Option(kv.getKey).foreach(e => add(e, DbtNavigation.variable(w, r, kv.getKeyText), kv.getKeyText))
          case _ => ()
        }
        super.visitElement(e)
      }
    })
    result.toVector
  }
}
