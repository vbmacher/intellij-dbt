package com.github.vbmacher.intellij.dbt.ide

private[ide] sealed abstract class DbtCommandAction(val label: String, val command: Vector[String],
  val usesState: Boolean = true, val structured: Boolean = false) extends Product with Serializable

private[ide] object DbtCommandAction {
  case object Run extends DbtCommandAction("Run", Vector("run"), structured = true)
  case object Build extends DbtCommandAction("Build", Vector("build"), structured = true)
  case object Test extends DbtCommandAction("Test", Vector("test"), structured = true)
  case object Compile extends DbtCommandAction("Compile", Vector("compile"))
  case object Preview extends DbtCommandAction("Preview", Vector("show"))
  case object GenerateDocs extends DbtCommandAction("Generate Docs", Vector("docs", "generate"), usesState = false)
  case object SourceFreshness extends DbtCommandAction("Source Freshness", Vector("source", "freshness"), usesState = false, structured = true)
  case object ResolveSelection extends DbtCommandAction("Resolve Selection", Vector("ls", "--quiet", "--output", "json"), usesState = false)

  val toolbar: Vector[DbtCommandAction] = Vector(Run, Build, Test, Compile, Preview, GenerateDocs, SourceFreshness)
  def fromLabel(label: String): Option[DbtCommandAction] = (toolbar :+ ResolveSelection).find(_.label == label)
}

private[ide] sealed abstract class DbtCommandScope
private[ide] object DbtCommandScope {
  case object All extends DbtCommandScope
  final case class Selected(terms: Vector[String]) extends DbtCommandScope
  final case class Named(name: String) extends DbtCommandScope
  final case class Inline(sql: String) extends DbtCommandScope
}

/** Resolved command inputs. Rendering never reads settings or rewrites an existing argument vector. */
private[ide] final case class DbtCommandRequest(action: DbtCommandAction, scope: DbtCommandScope = DbtCommandScope.All,
  exclude: Vector[String] = Vector.empty, fullRefresh: Boolean = false, limit: Int = 10,
  state: Option[String] = None, defer: Boolean = false, favorState: Boolean = false,
  indirectSelection: Option[String] = None, debugSql: Boolean = false, extraArguments: Vector[String] = Vector.empty) {
  def arguments: Vector[String] = {
    val selection = scope match {
      case DbtCommandScope.All => Vector.empty
      case DbtCommandScope.Selected(terms) => Vector("--select") ++ terms
      case DbtCommandScope.Named(name) => Vector("--selector", name)
      case DbtCommandScope.Inline(sql) => Vector("--inline", sql)
    }
    action.command ++ (if (action.structured) Vector("--log-format", "json") else Vector.empty) ++ selection ++
      (if (exclude.nonEmpty) Vector("--exclude") ++ exclude else Vector.empty) ++
      (if (fullRefresh) Vector("--full-refresh") else Vector.empty) ++
      (if (action == DbtCommandAction.Preview) Vector("--limit", limit.max(1).min(1000).toString, "--output", "json") else Vector.empty) ++
      (if (debugSql) Vector("--log-format", "json", "--log-level", "debug") else Vector.empty) ++
      state.toVector.flatMap(path => Vector("--state", path)) ++
      (if (defer) Vector("--defer") else Vector.empty) ++ (if (favorState) Vector("--favor-state") else Vector.empty) ++
      indirectSelection.toVector.flatMap(value => Vector("--indirect-selection", value)) ++ extraArguments
  }
}
