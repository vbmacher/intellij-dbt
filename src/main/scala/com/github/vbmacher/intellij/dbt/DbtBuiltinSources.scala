package com.github.vbmacher.intellij.dbt

import com.intellij.psi.PsiManager
import scala.jdk.CollectionConverters._

/** Only explicitly selected local source files can replace builtin documentation targets. */
private[dbt] object DbtBuiltinSources {
  def symbols(w: DbtWorkspace): Vector[DbtSymbol] = w.roots.flatMap { root =>
    root.settings.flatMap(DbtYaml.map(_, "builtin-sources")).toVector.flatMap(_.getKeyValues.asScala).flatMap { entry =>
      val name = entry.getKeyText
      DbtYaml.string(entry.getParent.asInstanceOf[org.jetbrains.yaml.psi.YAMLMapping], name).toVector
        .flatMap(path => Option(root.directory.findFileByRelativePath(path))).flatMap(v => Option(PsiManager.getInstance(w.project).findFile(v))).flatMap { file =>
          val identifier = name.split("\\.").last
          val ranges = if (file.getVirtualFile.getExtension == "py") DbtPythonNavigation.declarations(file, identifier).map(_.getTextRange)
            else if (file.getVirtualFile.getExtension == "sql") DbtSyntax.inFile(file)
              .filter(b => b.tag && b.tokens.headOption.exists(_.image == "macro"))
              .flatMap(_.tokens.lift(1)).filter(_.image == identifier).map(_.range)
            else Vector.empty
          ranges.map(range => DbtSymbol("builtin", name, root.name, file, range, attributes = Map("builtin-root" -> root.directory.getPath)))
        }
    }
  }
}
