package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLMapping
import scala.jdk.CollectionConverters._

private[dbt] final case class DbtColumnData(name: String, dataType: String = "", description: String = "", declarations: Vector[String] = Vector.empty)

private[dbt] final case class DbtTestData(name: String, column: String = "", source: Option[PsiElement] = None) {
  def sourceId: Option[String] = source.filter(_.isValid).map(s => s.getContainingFile.getVirtualFile.getPath + ":" + s.getTextOffset)
}

private[dbt] final case class DbtResourceData(description: String = "", columns: Vector[DbtColumnData] = Vector.empty,
  tests: Vector[DbtTestData] = Vector.empty, tags: Vector[String] = Vector.empty, attributes: Map[String, String] = Map.empty,
  rawSql: Option[String] = None, compiledSql: Option[String] = None, arguments: Vector[DbtColumnData] = Vector.empty,
  dependencies: Vector[String] = Vector.empty) {
  /** Enrichment fills absent source fields; a warehouse constraint or test result is never inferred. */
  def enrich(other: DbtResourceData): DbtResourceData = copy(
    description = if (description.nonEmpty) description else other.description,
    columns = columns.map(c => other.columns.find(_.name == c.name).fold(c)(o => c.copy(
      dataType = if (c.dataType.nonEmpty) c.dataType else o.dataType,
      description = if (c.description.nonEmpty) c.description else o.description))) ++ other.columns.filterNot(o => columns.exists(_.name == o.name)),
    tests = tests ++ other.tests.groupBy(t => (t.name, t.column)).toVector.sortBy(_._1).flatMap { case (key, declarations) =>
      declarations.drop(tests.count(t => (t.name, t.column) == key))
    }, tags = if (tags.nonEmpty) tags else other.tags,
    attributes = other.attributes ++ attributes, rawSql = rawSql.orElse(other.rawSql), compiledSql = compiledSql.orElse(other.compiledSql),
    arguments = if (arguments.nonEmpty) arguments else other.arguments, dependencies = (dependencies ++ other.dependencies).distinct)
}

private[dbt] object DbtResourceData {
  val GraphKinds: Set[String] = Set("model", "source_table", "seed", "snapshot", "exposure")

  def get(w: DbtWorkspace, s: DbtSymbol): DbtResourceData = w.resourceData(s) {
    ProgressManager.checkCanceled()
    val properties = if (s.isProperty || s.mapping.nonEmpty) Vector(s) else w.properties(s)
    val declared = w.columns(s).distinctBy(_.name)
    val columns = declared.map(c => DbtColumnData(c.name, c.attributes.getOrElse("data_type", ""), c.attributes.getOrElse("description", ""),
      c.mapping.toVector.flatMap(columnDeclarations(_, c.name))))
    val declaredTests = declared.flatMap(c => c.mapping.toVector.flatMap(testData(_, c.name)))
    val source = properties.flatMap(_.mapping).foldLeft(DbtResourceData(description = s.attributes.getOrElse("description", ""),
      columns = columns, tests = declaredTests, tags = s.attributes.get("tags").toVector.flatMap(_.split(",")).filter(_.nonEmpty), attributes = s.attributes,
      rawSql = if (s.isCode && Set("model", "snapshot")(s.kind) && s.file.getName.endsWith(".sql")) Some(s.file.getText) else None)) { (data, m) =>
      val columns = DbtYaml.items(m, "columns").flatMap(c => DbtYaml.string(c, "name").map(name =>
        DbtColumnData(name, DbtYaml.string(c, "data_type").getOrElse(""), DbtYaml.string(c, "description").getOrElse(""),
          columnDeclarations(c, name))))
      val args = DbtYaml.items(m, "arguments").flatMap(a => DbtYaml.string(a, "name").map(n =>
        DbtColumnData(n, DbtYaml.string(a, "type").getOrElse(""), DbtYaml.string(a, "description").getOrElse(""))))
      data.enrich(DbtResourceData(DbtYaml.string(m, "description").getOrElse(""), columns,
        testData(m, "") ++ DbtYaml.items(m, "columns").flatMap(c => testData(c, DbtYaml.string(c, "name").getOrElse(""))),
        DbtYaml.strings(m, "tags", DbtYaml.map(m, "config").toVector.flatMap(DbtYaml.strings(_, "tags"))), DbtYaml.attributes(m), arguments = args))
    }
    val enriched = DbtDeclarationSource.EP.getExtensionList.asScala.foldLeft(source) { (data, adapter) =>
      adapter.documentation(w, s).fold(data)(data.enrich)
    }

    def docBody(call: DbtSyntax.Call): Option[String] = {
      val bodies = w.owner(s).toVector.flatMap(r => w.definitions(call, Set("doc"), r)).map { d =>
        DbtSyntax.tagged(DbtSyntax.inFile(d.file), "docs", d.range)
          .fold("") { case (a, b) => d.file.getText.substring(a.range.getEndOffset, b.range.getStartOffset).trim }
      }
      if (bodies.size == 1) bodies.headOption else None
    }

    // Resolve complete string concatenations only; unsupported or unresolved expressions stay intact.
    def docs(text: String): String = DbtSyntax.blocks(text).filterNot(_.tag).reverse.foldLeft(text) { (result, block) =>
      val calls = DbtSyntax.calls(block).filter(c => c.name == "doc" && c.closed)
      val resolved = calls.flatMap(c => docBody(c).map(c.expressionRange -> _))
      val tokens = block.tokens.filterNot(t => resolved.exists { case (range, _) =>
        range.contains(t.range) && range.getStartOffset != t.range.getStartOffset
      }).map(t => resolved.find(_._1.getStartOffset == t.range.getStartOffset)
        .fold(t) { case (range, body) => DbtSyntax.Token(body, range, string = true) })
      val literal = block.range.substring(text).endsWith("}}") && tokens.nonEmpty && tokens.size % 2 == 1 && tokens.zipWithIndex.forall { case (t, i) =>
        if (i % 2 == 0) t.string else !t.string && t.image == "~"
      }
      if (calls.nonEmpty && literal) {
        val body = tokens.zipWithIndex.collect { case (t, i) if i % 2 == 0 => t.image }.mkString
        result.substring(0, block.range.getStartOffset) + body + result.substring(block.range.getEndOffset)
      } else result
    }

    enriched.copy(description = docs(enriched.description), columns = enriched.columns.map(c => c.copy(description = docs(c.description))))
  }

  /** Constraint types and data test names declared for a column. */
  private def columnDeclarations(column: YAMLMapping, name: String): Vector[String] =
    DbtYaml.items(column, "constraints").flatMap(DbtYaml.string(_, "type")) ++ testData(column, name).map(_.name)

  private def testData(m: YAMLMapping, column: String): Vector[DbtTestData] = Vector("data_tests", "tests").flatMap { key =>
    DbtYaml.elements(m, key).map {
      case test: YAMLMapping =>
        val key = test.getKeyValues.asScala.headOption
        DbtTestData(key.map(_.getKeyText).getOrElse("test"), column, key.map(_.getKey))
      case test => DbtTestData(test.getText, column, Some(test))
    }
  }
}

/** A logical resource within its owning project. DbtSymbol.id identifies an individual declaration instead. */
private[dbt] object DbtResourceIdentity {
  def key(workspace: DbtWorkspace, symbol: DbtSymbol): String =
    workspace.owner(symbol).map(_.directory.getPath).getOrElse("") + "|" + symbol.uniqueId
}
