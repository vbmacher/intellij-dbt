package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.util.TextRange

import java.util.regex.Pattern
import scala.jdk.CollectionConverters._

/** Environment variables declared by `.env` files and the `env` section of a run configuration. */
private[dbt] object DbtEnvironment {
  private def overrides(r: DbtRoot): Vector[DbtSymbol] =
    r.runConfiguration.flatMap(DbtYaml.map(_, "env")).toVector.flatMap(_.getKeyValues.asScala)
      .flatMap(k => Option(k.getKey).map(e => DbtSymbol("environment", k.getKeyText, r.name, e.getContainingFile, e.getTextRange)))

  private def files(r: DbtRoot, namePattern: String): Vector[DbtSymbol] = r.envFiles.flatMap { file =>
    s"(?m)^\\s*(?:export\\s+)?($namePattern)\\s*=".r.findAllMatchIn(file.getText)
      .map(m => DbtSymbol("environment", m.group(1), r.name, file, new TextRange(m.start(1), m.end(1))))
  }

  /** Run configuration overrides of an environment variable take precedence over `.env` files. */
  def apply(r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val found = overrides(r).filter(_.name == name).take(1)
    if (found.nonEmpty) found else files(r, Pattern.quote(name))
  }

  def declarations(r: DbtRoot): Vector[DbtSymbol] = overrides(r) ++ files(r, "[A-Za-z_][A-Za-z_0-9]*")
}

private[project] final class DbtEnvironmentSource extends DbtDeclarationSource {
  override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap(DbtEnvironment.declarations)
}
