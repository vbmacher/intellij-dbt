package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.psi.{PsiElement, PsiFile}

/** Parse-time v1 view definitions published by dbt v2; no metadata queries are executed. */
private[navigation] object DbtInfoSchema {
  private val common = Set("unique_id", "name", "resource_type", "package_name", "original_file_path", "fqn", "alias", "description",
    "node_language", "database_name", "schema_name", "relation_name", "identifier", "enabled", "materialized", "config", "access",
    "group", "contract_enforced", "version", "latest_version", "deprecation_date", "primary_key", "properties_yml_file_path", "tags", "meta", "ingested_at")
  val views: Map[String, Set[String]] = Map(
    "models" -> common, "seeds" -> common, "snapshots" -> (common + "raw_code"), "functions" -> (common + "raw_code"),
    "analyses" -> (common + "raw_code"), "hooks" -> (common + "raw_code"), "checks" -> (common + "raw_code"),
    "sources" -> (common ++ Set("source_name", "source_description", "loader", "loaded_at_field")),
    "data_tests" -> (common ++ Set("test_name", "test_definition_package", "arguments", "column_name", "node_unique_id", "severity", "warn_if", "error_if", "fail_calc", "store_failures", "store_failures_as")),
    "unit_tests" -> Set("unique_id", "name", "model", "description", "package_name", "original_file_path", "fqn", "given", "expect", "overrides", "versions", "ingested_at"),
    "node_columns" -> Set("node_unique_id", "column_name", "data_type_declared", "description", "tags", "ingested_at"),
    "dag_nodes" -> Set("unique_id", "resource_type", "ingested_at")
  )

  def enabled(root: DbtRoot, file: PsiFile): Boolean = root.engine == "v2" && root.kind(file.getVirtualFile).contains("check") &&
    !DbtWorkspace.get(file.getProject).symbols.exists(s => s.file == file && s.kind == "check" && !s.isEnabled) &&
    DbtYaml.map(root.config, "info_schema").flatMap(DbtYaml.string(_, "version")).forall(_ == "1")

  def columns(file: PsiFile, root: DbtRoot, view: String): Map[String, Vector[PsiElement]] =
    views.getOrElse(view, Set.empty).map(n => n -> metadata(file, root, "information_schema_column", n, view)).toMap

  def view(file: PsiFile, root: DbtRoot, name: String): Vector[PsiElement] = metadata(file, root, "information_schema_view", name)

  /** Declarations read from Information Schema artifacts, or the documentation when none were read. */
  private def metadata(file: PsiFile, root: DbtRoot, kind: String, name: String, owner: String = ""): Vector[PsiElement] = {
    val w = DbtWorkspace.get(file.getProject)
    val found = w.symbols.filter(s => s.kind == kind && s.namespace == root.name && w.owner(s).contains(root) && s.owner == owner && s.name == name)
    if (found.nonEmpty) found.map(_.element)
    else Vector(DbtResolution.documentation(file, if (owner.isEmpty) name else s"$owner.$name", "info-schema", root))
  }
}
