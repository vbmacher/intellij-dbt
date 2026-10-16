package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtConfiguration, DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.yaml.psi.{YAMLKeyValue, YAMLMapping}

/** dbt's resolution rules shared by the languages hosting references. */
private[navigation] object DbtResolution {
  /** Versioned declarations: the requested version, or the single latest version, or the unversioned ones. */
  def versioned(defs: Vector[DbtSymbol], requested: Option[String]): Vector[DbtSymbol] = {
    val latest = defs.flatMap(_.attributes.get("latest_version")).distinct
    val selected = requested.orElse(if (latest.size == 1) latest.headOption else None)
    defs.filter(s => selected.fold(s.version.isEmpty)(v => s.version.contains(v)))
  }

  private def varsIn(m: YAMLMapping, project: String, name: String): Option[YAMLKeyValue] =
    DbtYaml.map(m, project).flatMap(DbtYaml.keyValue(_, name)).orElse(DbtYaml.keyValue(m, name))

  def overriddenVariable(r: DbtRoot, name: String): Option[YAMLKeyValue] =
    r.runConfiguration.flatMap(DbtYaml.map(_, "vars")).flatMap(DbtYaml.keyValue(_, name))

  /** A project variable as seen by a project, honouring run configuration overrides and `vars.yml`. */
  def variable(w: DbtWorkspace, r: DbtRoot, name: String): Vector[PsiElement] = {
    val consumer = w.packageOwner(r).getOrElse(r)
    val external = if (consumer.supports(12)) w.rootFile(consumer, "vars.yml").flatMap(DbtYaml.mapping).flatMap(DbtYaml.map(_, "vars")) else None
    val original = DbtYaml.map(consumer.config, "vars")
    def nonEmpty(m: Option[YAMLMapping]): Boolean = m.exists(_.getKeyValues.size() > 0)
    overriddenVariable(consumer, name) match {
      case Some(definition) => Vector(definition)
      case None if nonEmpty(external) && nonEmpty(original) => Vector.empty
      case None => external.orElse(original).flatMap(varsIn(_, r.name, name))
        .orElse(if (consumer != r) DbtYaml.map(r.config, "vars").flatMap(varsIn(_, r.name, name)) else None).toVector
    }
  }

  def configuration(w: DbtWorkspace, file: PsiFile, key: String): Vector[PsiElement] = {
    val generators = Map("schema" -> "generate_schema_name", "alias" -> "generate_alias_name", "database" -> "generate_database_name")
    DbtConfiguration.locations(w, file, key) ++ w.root(file).toVector.flatMap(r => generators.get(key).toVector
      .flatMap(n => w.declarations(Set("macro"), n, r).map(_.element)))
  }

  def sourceTables(w: DbtWorkspace, root: DbtRoot, source: String, table: String): Vector[DbtSymbol] = {
    val parents = w.declarations(Set("source"), source, root)
    w.symbols.filter(s => s.kind == "source_table" && s.name == table && s.owner == source &&
      s.isEnabled && parents.exists(p => p.namespace == s.namespace &&
        w.owner(p) == w.owner(s) && (p.file == s.file && p.mapping.exists(_.getTextRange.contains(s.range)) || s.metadata)))
  }

  /** Local sources selected for a builtin, or its documentation. */
  def builtin(w: DbtWorkspace, root: DbtRoot, host: PsiFile, name: String, page: String): Vector[PsiElement] = {
    val targets = w.symbols.filter(s => s.kind == "builtin" && s.name == name &&
      s.attributes.get("builtin-root").contains(root.directory.getPath)).map(_.element)
    if (targets.nonEmpty) targets else Vector(documentation(host, name, page, root))
  }

  /** Materializations of the configured adapter, falling back to more generic adapters. */
  def materializations(w: DbtWorkspace, r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val declarations = w.declarations(Set("materialization"), name, r)
    if (r.adapter.isEmpty) declarations
    else r.adapterFallbacks.iterator.map(a => declarations.filter(_.attributes.get("adapter").contains(a))).find(_.nonEmpty).getOrElse(Vector.empty)
  }

  /** Adapter implementations of a dispatched macro, following the configured search order. */
  def dispatch(w: DbtWorkspace, r: DbtRoot, name: String, namespace: Option[String]): Vector[DbtSymbol] = {
    val order = DbtYaml.items(r.config, "dispatch").find(m => DbtYaml.string(m, "macro_namespace") == namespace)
      .map(DbtYaml.strings(_, "search_order")).getOrElse((Vector(r.name) ++ namespace).distinct)
    if (r.adapter.isEmpty) w.symbols.filter(s => s.kind == "macro" && s.name.endsWith("__" + name) && order.contains(s.namespace))
    else order.iterator.flatMap(ns => r.adapterFallbacks.iterator.map(p => w.declarations(Set("macro"), s"${p}__$name", r, Some(ns))))
      .find(_.nonEmpty).getOrElse(Vector.empty)
  }

  def documentation(file: PsiFile, name: String, page: String, r: DbtRoot): PsiElement = {
    val path = page match {
      case "info-schema" => "reference/info-schema"
      case "data-tests" => "reference/resource-properties/data-tests"
      case "function" => "docs/build/udfs"
      case "statement" | "load-result" => "reference/dbt-jinja-functions/statement-blocks"
      case "on-run-end-context" => "reference/dbt-jinja-functions/on-run-end-context"
      case _ => "reference/dbt-jinja-functions/" + page.replace('-', '_')
    }
    DbtSymbol("documentation", name, "dbt", file, new TextRange(0, 0), attributes = Map("url" ->
      s"https://docs.getdbt.com/$path?version=${if (page == "info-schema") "2" else r.version}"), metadata = true).element
  }
}
