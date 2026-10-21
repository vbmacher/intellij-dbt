package com.github.vbmacher.intellij.dbt.navigation.yaml

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider, DbtResolution, DbtStaticCalls}
import com.github.vbmacher.intellij.dbt.project.{DbtConfiguration, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.psi.{PsiElement, PsiRecursiveElementWalkingVisitor}
import org.jetbrains.yaml.psi._

import scala.collection.mutable.ArrayBuffer

/** dbt names in YAML properties, project configuration and package declarations. */
private[yaml] final class DbtYamlNavigation extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] = {
    val (file, w, r) = (context.file, context.workspace, context.root)
    if (!file.isInstanceOf[YAMLFile]) return Vector.empty
    val result = ArrayBuffer.empty[DbtNavigationReference]

    def add(e: PsiElement, targets: Iterable[PsiElement], name: String): Unit =
      result += new DbtNavigationReference(file, e.getTextRange, targets.toVector.distinct, name)

    def addKey(kv: YAMLKeyValue, targets: => Iterable[PsiElement], name: String): Unit = Option(kv.getKey).foreach(add(_, targets, name))

    def lookup(kinds: Set[String], name: String): Vector[PsiElement] = {
      val split = name.split("\\.", 2)
      w.declarations(kinds, split.last, r, if (split.length == 2) Some(split.head) else None).map(_.element)
    }

    def containingName(e: PsiElement): Option[String] = Iterator.iterate(e.getParent)(_.getParent)
      .takeWhile(_ != null).collect { case m: YAMLMapping => m }.flatMap(m => DbtYaml.string(m, "name")).toSeq.headOption

    def test(name: String, element: PsiElement): Unit = {
      val defs = lookup(Set("generic_test"), name)
      val builtin = Set("unique", "not_null", "accepted_values", "relationships").contains(name)
      add(element, if (defs.nonEmpty) defs else if (builtin) Vector(DbtResolution.documentation(file, name, "data-tests", r)) else Vector.empty, name)
    }

    file.accept(new PsiRecursiveElementWalkingVisitor {
      override def visitElement(e: PsiElement): Unit = {
        val ancestry = DbtYaml.ancestors(e)
        if (!ancestry.contains("meta") && (r.supports(8) || !ancestry.contains("unit_tests"))) e match {
          case scalar: YAMLScalar if !ancestry.contains("vars") =>
            val name = scalar.getTextValue

            def semantic(kind: String): Vector[PsiElement] = DbtResolution.semantic(w, r, file, scalar.getTextOffset, kind, name).map(_.element)

            if (!name.contains("{{")) DbtYaml.key(scalar) match {
              case key@("package" | "git" | "local") if Set("packages.yml", "dependencies.yml", "package-lock.yml").contains(file.getName) =>
                val declaration = DbtYaml.parentMapping(scalar)
                val projectName = if (key == "local") {
                  Option(r.directory.findFileByRelativePath(name)).flatMap(d => DbtYaml.psiFile(w.project, d.findChild(DbtWorkspace.ProjectFileName)))
                    .flatMap(DbtYaml.mapping).flatMap(DbtYaml.string(_, "name"))
                } else if (key == "git") declaration.flatMap(DbtYaml.string(_, "subdirectory"))
                  .orElse(Some(name.stripSuffix(".git").split("/").last)) else Some(name.split("/").last)
                val installName = declaration.flatMap(DbtYaml.string(_, "name")).orElse(projectName.map(_.split("/").last))
                val installed = w.roots.filter(p => w.packageOwner(p).contains(r) &&
                  (projectName.contains(p.name) || installName.contains(p.directory.getName)))
                add(scalar, installed.map(p => p.config.getContainingFile), name)
              case key@("version" | "revision") if Set("packages.yml", "dependencies.yml").contains(file.getName) =>
                def identity(m: YAMLMapping): Option[String] = DbtYaml.string(m, "package").orElse(DbtYaml.string(m, "git"))

                val declared = DbtYaml.parentMapping(scalar).flatMap(identity)
                val entries = r.yaml("package-lock.yml").toVector.flatMap(DbtYaml.items(_, "packages")).filter(m => declared.exists(identity(m).contains))
                add(scalar, entries.flatMap(DbtYaml.keyValue(_, key)), name)
              case "name" if file.getName == "dependencies.yml" && ancestry.contains("projects") =>
                add(scalar, w.roots.filter(p => p.name == name && w.namespaceVisible(r, p, qualified = true)).map(_.config.getContainingFile), name)
              case "name" =>
                val exports = w.symbols.filter(s => s.file == file && s.range == scalar.getTextRange && s.kind == "export")
                exports.foreach(e => add(scalar, lookup(Set("saved_query"), e.owner), name))
                val props = w.symbols.filter(s => s.file == file && s.range == scalar.getTextRange && s.isProperty)
                props.foreach { p =>
                  add(scalar, w.declarations(Set(p.kind), p.name, r, Some(p.namespace))
                    .filterNot(_.isProperty).filter(_.version == p.version).map(_.element), name)
                }
                val semanticDeclaration = w.symbols.exists(s => Set("metric", "measure", "dimension", "entity").contains(s.kind) &&
                  s.file == file && s.range == scalar.getTextRange)
                if (ancestry.exists(Set("measures", "metrics")) && !semanticDeclaration &&
                  (!ancestry.contains("input_metrics") || r.supports(12)))
                  add(scalar, lookup(Set(if (ancestry.exists(Set("measures", "base_measure", "conversion_measure"))) "measure" else "metric"), name), name)
              case "group" | "+group" => add(scalar, lookup(Set("group"), name), name)
              case "model" if ancestry.contains("unit_tests") => add(scalar, lookup(Set("model"), name), name)
              case "fixture" =>
                val extensions = DbtYaml.parentMapping(scalar).flatMap(DbtYaml.string(_, "format")) match {
                  case Some("csv") => Vector("csv")
                  case Some("sql") => Vector("sql")
                  case None => Vector("csv", "sql")
                  case _ => Vector.empty
                }
                val target = r.pathKinds.filter(_._1 == "test").flatMap { case (_, path) =>
                  extensions.flatMap(ext => r.psiFile(s"$path/fixtures/$name.$ext"))
                }
                add(scalar, target.flatMap(f => w.symbols.filter(s => s.kind == "fixture" && s.file == f).map(_.element)), name)
              case "materialized" | "+materialized" => add(scalar, DbtResolution.materializations(w, r, name).map(_.element), name)
              case "strategy" => add(scalar, lookup(Set("macro"), s"snapshot_${name}_strategy"), name)
              case "standard_granularity_column" =>
                val owners = containingName(scalar).toVector ++ (if (file.getName == "dbt_project.yml")
                  ancestry.filter(n => w.declarations(Set("model"), n, r).nonEmpty) else Vector.empty)
                add(scalar, w.symbols.filter(s => s.kind == "column" && s.name == name && w.owner(s).contains(r) && owners.contains(s.owner)).map(_.element), name)
              case "expr" if ancestry.contains("metrics") =>
                val metrics = w.enclosing("metric", file, scalar.getTextOffset)
                val aliases = w.symbols.filter(s => s.kind == "metric_alias" && w.owner(s).contains(r) && metrics.exists(_.name == s.owner))
                DbtYaml.decoded(scalar).toVector.flatMap { case (text, offset) => DbtSyntax.tokens(text, offset) }.foreach { token =>
                  val defs = if (token.string) Vector.empty else aliases.filter(_.name == token.image) ++
                    w.symbols.filter(s => s.kind == "column" && s.name == token.image && w.owner(s).contains(r) && metrics.exists(m => m.owner.nonEmpty && m.owner == s.owner))
                  if (defs.nonEmpty) result += new DbtNavigationReference(file, token.range, defs.map(_.element), token.image)
                }
              case "expr" if DbtSyntax.isIdentifier(name) =>
                val semantic = w.enclosing("semantic_model", file, scalar.getTextOffset)
                val modelNames = semantic.flatMap(s => if (s.attributes.contains("embedded")) Vector(s.owner) else s.mapping.toVector
                  .flatMap(m => DbtYaml.string(m, "model")).flatMap(t => DbtSyntax.blocks("{{" + t + "}}").flatMap(DbtSyntax.calls).flatMap(_.arguments.flatMap(_.literal))))
                add(scalar, w.symbols.filter(s => s.kind == "column" && s.name == name && s.namespace == r.name && modelNames.contains(s.owner)).map(_.element), name)
              case "defined_in" =>
                add(scalar, w.symbols.filter(s => s.isCode && s.namespace == r.name && w.root(s.file).contains(r) &&
                  s.file.getVirtualFile.getNameWithoutExtension == name).map(_.element), name)
              case "primary_entity" => add(scalar, semantic("entity"), name)
              case "agg_time_dimension" => add(scalar, semantic("dimension"), name)
              case "measure" | "base_measure" | "conversion_measure" => add(scalar, semantic("measure"), name)
              case "metric" | "numerator" | "denominator" => add(scalar, lookup(Set("metric"), name), name)
              case "input_metric" | "base_metric" | "conversion_metric" if r.supports(12) => add(scalar, lookup(Set("metric"), name), name)
              case "entity" if r.supports(6) && ancestry.contains("metrics") =>
                add(scalar, semantic("entity"), name)
              case "profile" if file.getName == "dbt_project.yml" => add(scalar, r.profile.toVector, name)
              case "target" if file.getName == "profiles.yml" => add(scalar, DbtYaml.parentMapping(scalar).toVector.flatMap(p =>
                DbtYaml.map(p, "outputs").flatMap(DbtYaml.keyValue(_, name))), name)
              case "field" if ancestry.exists(DbtSymbol.TestKeys) =>
                // The relationships target must supply a proved ref/source identity.
                val mapping = DbtYaml.parentMapping(scalar)
                val to = mapping.flatMap(DbtYaml.value(_, "to")).collect { case s: YAMLScalar => s }
                val refs = to.toVector.flatMap(t => DbtSyntax.blocks(if (t.getTextValue.contains("{{")) t.getTextValue else "{{" + t.getTextValue + "}}").flatMap(DbtSyntax.calls))
                  .filter(c => Set("ref", "source", "builtins.ref", "builtins.source").contains(c.name))
                val targets = refs.flatMap(c => DbtStaticCalls.targets(w, r, c, Map.empty)).collect { case e: DbtSymbolElement => e.symbol }
                val columns = if (targets.size != 1) Vector.empty else w.symbols.filter(s => s.kind == "column" && s.name == name &&
                  targets.exists(t => s.namespace == t.namespace && w.root(s.file) == w.root(t.file) && s.version == t.version &&
                    s.owner == t.relationName))
                if (!ancestry.contains("arguments") || r.supports(10, 5)) add(scalar, columns.map(_.element), name)
              case _ =>
                if (ancestry.exists(Set("metrics", "group_by", "dimensions", "entities")) && scalar.getParent.isInstanceOf[YAMLSequenceItem])
                  add(scalar, lookup(Set(if (ancestry.contains("metrics")) "metric" else if (ancestry.contains("entities")) "entity" else "dimension"), name), name)
            }
            if (ancestry.exists(DbtSymbol.TestKeys) && scalar.getParent.isInstanceOf[YAMLSequenceItem]) test(name, scalar)
          case kv: YAMLKeyValue if file.getName == "dbt_project.yml" && ancestry.lastOption.exists(DbtConfiguration.sections.contains) =>
            val key = kv.getKeyText
            val kind = ancestry.lastOption.flatMap(DbtConfiguration.sections.get)
            val names = ancestry.dropRight(1)
              .filterNot(n => n == r.name || n.startsWith("+"))
            val targets = w.symbols.filter(s => kind.contains(s.kind) && w.owner(s).contains(r) &&
              (names.isEmpty || names.contains(s.name) || r.relative(s.file.getVirtualFile).exists(p => names.forall(p.split("/").contains(_)))))
            val locations = if (key.startsWith("+")) targets.filter(_.isCode)
              .flatMap(s => DbtConfiguration.locations(w, s.file, key.drop(1))).filterNot(_ == kv)
            else DbtConfiguration.pathTargets(w, r, kv)
            addKey(kv, locations, key)
          case kv: YAMLKeyValue if ancestry.contains("config") =>
            val owners = w.symbols.filter(s => s.file == file && s.isProperty && s.mapping.exists(_.getTextRange.contains(kv.getTextRange)))
            val code = owners.flatMap(p => w.declarations(Set(p.kind), p.name, r, Some(p.namespace))).filter(_.isCode)
            addKey(kv, code.flatMap(s => DbtConfiguration.locations(w, s.file, kv.getKeyText)).filterNot(_ == kv), kv.getKeyText)
          case kv: YAMLKeyValue if ancestry.exists(DbtSymbol.TestKeys) =>
            if (kv.getParent.isInstanceOf[YAMLMapping] && kv.getParent.getParent.isInstanceOf[YAMLSequenceItem])
              Option(kv.getKey).foreach(e => test(kv.getKeyText, e))
            else if ((!ancestry.contains("arguments") || r.supports(10, 5)) &&
              !Set("arguments", "config", "name", "alias", "where", "severity", "to", "field", "values").contains(kv.getKeyText)) {
              val testName = ancestry.find(n => w.symbols.exists(s => s.kind == "generic_test" && (s.name == n || s.namespace + "." + s.name == n)))
              testName.foreach(n => addKey(kv, w.symbols.filter(s => s.kind == "parameter" && s.owner == n.split("\\.").last &&
                s.name == kv.getKeyText && lookup(Set("generic_test"), n).exists(d => d.getContainingFile == s.file)).map(_.element), kv.getKeyText))
            }
          case kv: YAMLKeyValue if ancestry.contains("overrides") && ancestry.contains("macros") =>
            addKey(kv, lookup(Set("macro"), kv.getKeyText), kv.getKeyText)
          case kv: YAMLKeyValue if ancestry.contains("overrides") && ancestry.contains("vars") =>
            addKey(kv, DbtResolution.variable(w, r, kv.getKeyText), kv.getKeyText)
          case _ => ()
        }
        super.visitElement(e)
      }
    })
    result.toVector
  }
}
