package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.util.TextRange
import org.jetbrains.yaml.psi.YAMLScalar

import java.util.regex.{Matcher, Pattern}
import scala.jdk.CollectionConverters._

/** Environment variables declared by `.env` files and the `env` section of a run configuration. */
private[dbt] object DbtEnvironment {
  private val Assignment = """\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*)""".r
  private val DoubleQuoted = """"((?:\\.|[^"\\])*)"\s*(?:#.*)?""".r
  private val SingleQuoted = """'([^']*)'\s*(?:#.*)?""".r
  private val Escape = """\\([nrt"\\])""".r

  /** Single-line dotenv assignments; values are data, never shell expressions. */
  private[dbt] def parse(text: String): Map[String, String] = text.stripPrefix("\ufeff").linesIterator.flatMap {
    case Assignment(name, raw) =>
      val value = raw.trim match {
        case DoubleQuoted(value) => Some(Escape.replaceAllIn(value, m => Matcher.quoteReplacement(m.group(1) match {
          case "n" => "\n"
          case "r" => "\r"
          case "t" => "\t"
          case other => other
        })))
        case SingleQuoted(value) => Some(value)
        case value if !value.startsWith("\"") && !value.startsWith("'") =>
          Some(if (value.startsWith("#")) "" else value.replaceFirst("\\s+#.*$", "").trim)
        case _ => None
      }
      value.map(name -> _)
    case _ => None
  }.toMap

  def values(r: DbtRoot): Map[String, String] = {
    val files = r.envFiles.foldLeft(Map.empty[String, String])((values, file) => values ++ parse(file.getText))
    val overrides = r.runConfiguration.flatMap(DbtYaml.map(_, "env")).toVector.flatMap(_.getKeyValues.asScala)
      .flatMap(k => Option(k.getValue).collect { case scalar: YAMLScalar => k.getKeyText -> scalar.getTextValue })
    files ++ overrides
  }

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
