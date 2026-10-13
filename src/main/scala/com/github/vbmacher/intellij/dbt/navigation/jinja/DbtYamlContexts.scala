package com.github.vbmacher.intellij.dbt.navigation.jinja

import com.github.vbmacher.intellij.dbt.project.{DbtRoot, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.{YAMLFile, YAMLScalar}

/** dbt renders these hosts with different contexts, including deferred hooks and descriptions. */
private[jinja] object DbtYamlContexts {
  private val resources = Set("models", "seeds", "snapshots", "analyses", "macros", "sources", "exposures", "groups",
    "metrics", "semantic_models", "saved_queries", "functions", "unit_tests", "checks")
  private val hooks = Set("pre-hook", "post-hook", "pre_hook", "post_hook", "on-run-start", "on-run-end", "loaded_at_query", "sql")

  def scalar(file: PsiFile, offset: Int): Option[YAMLScalar] = Option(file.findElementAt(offset)).flatMap(e =>
    Iterator.iterate(e)(_.getParent).takeWhile(_ != null).collectFirst { case s: YAMLScalar => s })

  private def context(file: PsiFile, root: DbtRoot, offset: Int): String = {
    if (!file.isInstanceOf[YAMLFile]) return "runtime"
    val keys = scalar(file, offset).map(DbtYaml.ancestors).getOrElse(Vector.empty).map(_.stripPrefix("+"))
    val relative = root.relative(file.getVirtualFile)
    val unsupported = keys.lastOption.exists {
      case "unit_tests" => !root.supports(8)
      case "semantic_models" | "metrics" => !root.supports(6)
      case "saved_queries" => !root.supports(7)
      case "functions" => !root.supports(11)
      case "checks" => root.engine != "v2"
      case _ => false
    }
    if (unsupported || keys.contains("vars") || relative.contains("dependencies.yml") || relative.contains("package-lock.yml") ||
      relative.contains("vars.yml") || relative == root.setting("run-config")) "none"
    else if (relative.contains(root.profilesFile) || relative.contains("packages.yml")) "profile"
    else if (relative.contains("selectors.yml")) if (keys.headOption.contains("default")) "project" else "none"
    else if (relative.contains("dbt_project.yml")) if (keys.exists(hooks)) "runtime" else "project"
    else if (!keys.lastOption.exists(resources)) "none"
    else if (keys.contains("meta")) "properties"
    else if (keys.exists(Set("tests", "data_tests")) && keys.contains("arguments") && !root.supports(10, 5)) "none"
    else if (keys.contains("description") && !keys.exists(Set("meta", "config"))) "description"
    else if (keys.exists(hooks) || keys.exists(Set("tests", "data_tests"))) "runtime"
    else if (keys.exists(Set("input", "depends_on", "relation", "filter", "where", "group_by", "order_by")) ||
      keys.headOption.contains("model") && keys.exists(Set("semantic_models", "unit_tests"))) "semantic"
    else "properties"
  }

  def blocks(file: PsiFile, root: DbtRoot): Vector[DbtSyntax.Block] = DbtSyntax.inFile(file)
    .filter(b => context(file, root, b.range.getStartOffset) != "none")

  def hostBlocks(file: PsiFile, blocks: Vector[DbtSyntax.Block], offset: Int): Vector[DbtSyntax.Block] =
    if (!file.isInstanceOf[YAMLFile]) blocks else scalar(file, offset).toVector.flatMap(s => blocks.filter(b => s.getTextRange.contains(b.range)))

  def allows(file: PsiFile, root: DbtRoot, offset: Int, name: String): Boolean = {
    val base = name.stripPrefix("builtins.")
    context(file, root, offset) match {
      case "runtime" => base != "doc"
      case "description" => Set("doc", "env_var", "var").contains(base)
      case "semantic" => Set("ref", "source", "function", "var", "env_var", "Dimension", "TimeDimension", "Entity", "Metric", "metric", "this").contains(base)
      case "profile" => Set("env_var", "var").contains(base)
      case "project" => base == "env_var" || base == "var" && root.supports(12) || Set("target", "dbt_version", "builtins").contains(base)
      case "properties" => Set("env_var", "var", "target", "dbt_version").contains(base)
      case _ => false
    }
  }

  def description(file: PsiFile, root: DbtRoot, offset: Int): Boolean = context(file, root, offset) == "description"

  def profile(file: PsiFile, root: DbtRoot): Boolean = root.isFile(file, root.profilesFile)

  def projectConfiguration(file: PsiFile, root: DbtRoot, offset: Int): Boolean =
    root.isFile(file, DbtWorkspace.ProjectFileName) && context(file, root, offset) == "project"
}
