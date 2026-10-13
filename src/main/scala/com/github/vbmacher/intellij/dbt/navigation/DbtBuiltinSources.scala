package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.{DbtPython, DbtSyntax, DbtYaml}
import org.jetbrains.yaml.psi.YAMLScalar

import scala.jdk.CollectionConverters._

/** Only explicitly selected local source files can replace builtin documentation targets. */
private[navigation] final class DbtBuiltinSources extends DbtDeclarationSource {
  override def declarations(w: DbtWorkspace): Vector[DbtSymbol] = w.roots.flatMap { root =>
    root.settings.flatMap(DbtYaml.map(_, "builtin-sources")).toVector.flatMap(_.getKeyValues.asScala).flatMap { entry =>
      val name = entry.getKeyText
      Option(entry.getValue).collect { case s: YAMLScalar => s.getTextValue }.toVector.flatMap(root.psiFile).flatMap { file =>
          val identifier = name.split("\\.").last
          val ranges = if (file.getVirtualFile.getExtension == "py") DbtPython.declarations(file, identifier).map(_.getTextRange)
            else if (file.getVirtualFile.getExtension == "sql") DbtSyntax.inFile(file)
              .filter(_.isTag("macro"))
              .flatMap(_.tokens.lift(1)).filter(_.image == identifier).map(_.range)
            else Vector.empty
          ranges.map(range => DbtSymbol("builtin", name, root.name, file, range, attributes = Map("builtin-root" -> root.directory.getPath)))
        }
    }
  }
}
