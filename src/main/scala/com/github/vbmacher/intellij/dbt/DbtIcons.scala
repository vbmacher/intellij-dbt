package com.github.vbmacher.intellij.dbt

import com.intellij.icons.AllIcons
import javax.swing.Icon

/** Shared by every presentation of a dbt declaration. */
private[dbt] object DbtIcons {
  def forKind(kind: String): Icon = kind match {
    case "model" => AllIcons.Nodes.ModelClass
    case "source" => AllIcons.Nodes.DataSchema
    case "source_table" => AllIcons.Nodes.DataTables
    case "seed" => AllIcons.FileTypes.Csv
    case "fixture" => AllIcons.FileTypes.Text
    case "snapshot" => AllIcons.General.History
    case "analysis" | "saved_query" => AllIcons.FileTypes.Sql
    case "macro" | "builtin" => AllIcons.Nodes.Method
    case "function" => AllIcons.Nodes.Function
    case "test" | "generic_test" => AllIcons.Nodes.Test
    case "unit_test" => AllIcons.Nodes.TestGroup
    case "check" => AllIcons.General.InspectionsOK
    case "doc" | "documentation" => AllIcons.FileTypes.Markdown
    case "semantic_model" => AllIcons.Nodes.Models
    case "metric" | "measure" => AllIcons.Nodes.Field
    case "metric_alias" => AllIcons.Nodes.Alias
    case "dimension" => AllIcons.Nodes.Type
    case "entity" => AllIcons.Nodes.Class
    case "column" | "sql_projection" | "information_schema_column" => AllIcons.Nodes.DataColumn
    case "information_schema_view" => AllIcons.Nodes.DataTables
    case "exposure" | "export" => AllIcons.Nodes.Deploy
    case "group" => AllIcons.Nodes.ModuleGroup
    case "package" | "project" => AllIcons.Nodes.Package
    case "var" | "variable" | "local" | "environment" => AllIcons.Nodes.Variable
    case "parameter" | "argument" => AllIcons.Nodes.Parameter
    case "config" | "configuration" | "profile" | "target" | "env" => AllIcons.FileTypes.Config
    case "namespace attribute" => AllIcons.Nodes.Property
    case "result" => AllIcons.Nodes.Record
    case "selector" => AllIcons.General.Filter
    case "cte" => AllIcons.Nodes.DataTables
    case "sql_alias" => AllIcons.Nodes.Alias
    case "materialization" => AllIcons.Nodes.Artifact
    case _ => AllIcons.Nodes.Property
  }
}
