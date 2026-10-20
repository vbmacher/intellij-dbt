package com.github.vbmacher.intellij.dbt.project

/** A logical resource within its owning project. DbtSymbol.id identifies an individual declaration instead. */
private[dbt] object DbtResourceIdentity {
  def key(workspace: DbtWorkspace, symbol: DbtSymbol): String =
    workspace.owner(symbol).map(_.directory.getPath).getOrElse("") + "|" + symbol.uniqueId
}
