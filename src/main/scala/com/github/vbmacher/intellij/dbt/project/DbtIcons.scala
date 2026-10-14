package com.github.vbmacher.intellij.dbt.project

import com.intellij.openapi.util.IconLoader

import javax.swing.Icon

/** Shared dbt mark with color-coded resource symbols; IntelliJ selects the dark SVG variant. */
private[dbt] object DbtIcons {
  private def load(name: String): Icon = IconLoader.getIcon(s"/icons/dbt/$name.svg", getClass)

  lazy val Table: Icon = load("table")
  lazy val Seed: Icon = load("seed")
  lazy val Snapshot: Icon = load("snapshot")
  lazy val Query: Icon = load("query")
  lazy val Macro: Icon = load("macro")
  lazy val Jinja: Icon = load("jinja")
  lazy val Function: Icon = load("function")
  lazy val Test: Icon = load("test")
  lazy val Check: Icon = load("check")
  lazy val Doc: Icon = load("doc")
  lazy val SemanticModel: Icon = load("semantic-model")
  lazy val Metric: Icon = load("metric")
  lazy val Alias: Icon = load("alias")
  lazy val Dimension: Icon = load("dimension")
  lazy val Entity: Icon = load("entity")
  lazy val Column: Icon = load("column")
  lazy val Export: Icon = load("export")
  lazy val Group: Icon = load("group")
  lazy val Package: Icon = load("package")
  lazy val Variable: Icon = load("variable")
  lazy val Parameter: Icon = load("parameter")
  lazy val Config: Icon = load("config")
  lazy val Property: Icon = load("property")
  lazy val Result: Icon = load("result")
  lazy val Selector: Icon = load("selector")
  lazy val Materialization: Icon = load("materialization")
  lazy val Gutter: Icon = load("gutter")
}
