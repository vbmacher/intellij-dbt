package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLMapping

/** Declarations of a single file, read from Jinja blocks and YAML properties without evaluating anything. */
private[dbt] object DbtDeclarations {
  /** Top-level keys of YAML property files and the kinds of the resources they declare. */
  val ResourceKeys: Map[String, String] = Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot", "analyses" -> "analysis",
    "macros" -> "macro", "sources" -> "source", "exposures" -> "exposure", "groups" -> "group", "metrics" -> "metric",
    "semantic_models" -> "semantic_model", "saved_queries" -> "saved_query", "functions" -> "function",
    "unit_tests" -> "unit_test", "checks" -> "check")
  private val childKeys = Vector("columns" -> "column", "entities" -> "entity", "dimensions" -> "dimension",
    "measures" -> "measure", "arguments" -> "parameter", "tables" -> "source_table", "exports" -> "export")
  private val blockKinds = Map("macro" -> "macro", "test" -> "generic_test", "docs" -> "doc", "snapshot" -> "snapshot",
    "materialization" -> "materialization")
  private val Code = Map("code" -> "true")

  private final class Builder(file: PsiFile, root: DbtRoot) {
    def apply(kind: String, name: String, range: TextRange, owner: String = "", version: Option[String] = None,
      attributes: Map[String, String] = Map.empty, mapping: Option[YAMLMapping] = None): DbtSymbol =
      DbtSymbol(kind, name, root.name, file, range, owner, version, attributes, mapping)
  }

  def inFile(file: PsiFile, root: DbtRoot): Vector[DbtSymbol] = {
    val symbol = new Builder(file, root)
    val ext = Option(file.getVirtualFile.getExtension).getOrElse("")
    val yaml = root.kind(file.getVirtualFile).nonEmpty && Set("yml", "yaml").contains(ext)
    val properties = if (yaml) DbtYaml.mapping(file).toVector.flatMap(resources(symbol, root, _)) else Vector.empty
    selectors(symbol, file, root) ++ code(symbol, file, root) ++ blocks(symbol, file, root) ++ properties ++
      metricAliases(symbol, root, properties)
  }

  private def selectors(symbol: Builder, file: PsiFile, root: DbtRoot): Vector[DbtSymbol] =
    if (!root.isFile(file, "selectors.yml")) Vector.empty
    else DbtYaml.mapping(file).toVector.flatMap(DbtYaml.items(_, "selectors")).flatMap(m =>
      DbtYaml.string(m, "name").map(n => symbol("selector", n, DbtYaml.nameRange(m), mapping = Some(m))))

  private def configLiterals(blocks: Vector[DbtSyntax.Block]): Vector[(String, String)] =
    blocks.flatMap(DbtSyntax.calls).filter(_.name == "config")
      .flatMap(_.arguments.flatMap(a => for (key <- a.keyword; value <- a.literal.orElse(literalTags(a))) yield key -> value))

  private def literalTags(argument: DbtSyntax.Argument): Option[String] = {
    val tokens = argument.tokens
    if (!argument.keyword.contains("tags") || tokens.size < 2 || tokens.head.string || tokens.last.string ||
      tokens.head.image != "[" || tokens.last.image != "]") None
    else {
      val values = tokens.slice(1, tokens.size - 1)
      val entries = if (values.lastOption.exists(t => !t.string && t.image == ",")) values.dropRight(1) else values
      val literal = entries.zipWithIndex.forall { case (token, index) =>
        if (index % 2 == 0) token.string else !token.string && token.image == ","
      }
      Option.when(literal && (entries.isEmpty || entries.size % 2 == 1))(
        entries.zipWithIndex.collect { case (token, index) if index % 2 == 0 => token.image }.mkString(","))
    }
  }

  /** Resources defined by the file itself: fixtures, models, seeds, snapshots, analyses, tests, checks and functions. */
  private def code(symbol: Builder, file: PsiFile, root: DbtRoot): Vector[DbtSymbol] = {
    val ext = Option(file.getVirtualFile.getExtension).getOrElse("")
    val kind = root.kind(file.getVirtualFile)
    val name = file.getVirtualFile.getNameWithoutExtension
    val fixture = kind.contains("test") && Set("csv", "sql").contains(ext) && root.relative(file.getVirtualFile)
      .exists(relative => root.pathKinds.exists { case (k, path) => k == "test" && root.pathContains(path.stripSuffix("/") + "/fixtures", relative) })
    val code = kind.exists {
      case "seed" => ext == "csv"
      case "model" => Set("sql", "py").contains(ext)
      case "function" => Set("sql", "py", "js").contains(ext)
      case "snapshot" | "analysis" | "test" | "check" => ext == "sql"
      case _ => false
    }
    lazy val blocks = DbtSyntax.inFile(file)
    // Legacy snapshot blocks declare the snapshot themselves.
    val snapshotBlock = kind.contains("snapshot") && blocks.exists(_.isTag("snapshot"))
    if (fixture) Vector(symbol("fixture", name, TextRange.EMPTY_RANGE, attributes = Code))
    else if (code && !snapshotBlock) Vector(symbol(kind.get, name, TextRange.EMPTY_RANGE, attributes = Code ++ configLiterals(blocks)))
    else Vector.empty
  }

  /** Macros, generic tests, docs, snapshots and materializations declared by Jinja blocks. */
  private def blocks(symbol: Builder, file: PsiFile, root: DbtRoot): Vector[DbtSymbol] = {
    val ext = Option(file.getVirtualFile.getExtension).getOrElse("")
    if (!root.resource(file) || !Set("sql", "md").contains(ext)) return Vector.empty
    val blocks = DbtSyntax.inFile(file)
    blocks.filter(_.tag).flatMap { b =>
      b.tokens.take(2) match {
        case Vector(tag, name) if blockKinds.contains(tag.image) =>
          val kind = blockKinds(tag.image)
          val attributes = Map("block-start" -> b.range.getEndOffset.toString) ++ (kind match {
            case "snapshot" => Code ++ configLiterals(blocks.dropWhile(_.range.getStartOffset <= b.range.getStartOffset)
              .takeWhile(!_.isTag("endsnapshot"))).toMap
            case "materialization" => Map("adapter" -> b.tokens.sliding(3).collectFirst {
              case Vector(key, eq, value) if key.image == "adapter" && eq.image == "=" && value.string => value.image
            }.getOrElse("default"))
            case _ => Map.empty[String, String]
          })
          val parameters = if (Set("macro", "test").contains(tag.image))
            DbtSyntax.parameters(b).map(t => symbol("parameter", t.image, t.range, name.image)) else Vector.empty
          symbol(kind, name.image, name.range, attributes = attributes) +: parameters
        case _ => Vector.empty
      }
    }
  }

  /** Resources and their children declared in YAML property files. */
  private def resources(symbol: Builder, root: DbtRoot, top: YAMLMapping): Vector[DbtSymbol] =
    // Legacy metrics and saved queries stay declared; resolution gates them by version.
    ResourceKeys.toVector.filter { case (_, kind) => Set("metric", "saved_query")(kind) || root.supportsKind(kind) }.flatMap { case (key, kind) =>
      DbtYaml.items(top, key).flatMap(m => DbtYaml.string(m, "name").toVector.flatMap { name =>
        (if (kind == "model" && root.supports(12)) modelSemantics(symbol, m, name) else Vector.empty) ++
          (if (kind == "function" && root.supports(12)) functionOverloads(symbol, m, name) else Vector.empty) ++
          versions(symbol, root, m, kind, name)
      })
    }

  /** Semantic models, entities, dimensions and metrics embedded in model properties (dbt 1.12). */
  private def modelSemantics(symbol: Builder, m: YAMLMapping, name: String): Vector[DbtSymbol] = {
    val semantics = DbtYaml.map(m, "semantic_model").toVector.flatMap { semantic =>
      val semanticName = DbtYaml.string(semantic, "name").getOrElse(name)
      val model = symbol("semantic_model", semanticName, DbtYaml.nameRange(semantic), name,
        attributes = Map("embedded" -> "true"), mapping = Some(m))
      val columns = DbtYaml.items(m, "columns").flatMap { column =>
        DbtYaml.string(column, "name").toVector.flatMap { columnName =>
          Vector("entity", "dimension").flatMap { field =>
            DbtYaml.map(column, field).map { definition =>
              symbol(field, DbtYaml.string(definition, "name").getOrElse(columnName),
                DbtYaml.value(definition, "name").map(_.getTextRange).getOrElse(DbtYaml.nameRange(column)), semanticName,
                attributes = DbtYaml.attributes(definition) ++ Map("column" -> columnName, "model" -> name), mapping = Some(definition))
            }
          }
        }
      }
      val derived = DbtYaml.map(m, "derived_semantics").toVector.flatMap { derived =>
        Vector("dimensions" -> "dimension", "entities" -> "entity").flatMap { case (key, kind) =>
          DbtYaml.items(derived, key).flatMap(definition => DbtYaml.string(definition, "name").map(n =>
            symbol(kind, n, DbtYaml.nameRange(definition), semanticName,
              attributes = DbtYaml.attributes(definition) ++ Map("model" -> name), mapping = Some(definition))))
        }
      }
      model +: (columns ++ derived)
    }
    val metrics = DbtYaml.items(m, "metrics").flatMap(metric => DbtYaml.string(metric, "name").map(n =>
      symbol("metric", n, DbtYaml.nameRange(metric), name, attributes = DbtYaml.attributes(metric), mapping = Some(metric))))
    semantics ++ metrics
  }

  /** Function overloads with their arguments (dbt 1.12). */
  private def functionOverloads(symbol: Builder, m: YAMLMapping, name: String): Vector[DbtSymbol] =
    DbtYaml.items(m, "overloads").flatMap { overload =>
      DbtYaml.string(overload, "defined_in").toVector.flatMap { body =>
        symbol("function", name, DbtYaml.nameRange(m), mapping = Some(overload), attributes = DbtYaml.attributes(m) ++
          DbtYaml.attributes(overload) ++ Map("property" -> "true", "overload" -> body)) +:
          DbtYaml.items(overload, "arguments").flatMap(argument => DbtYaml.string(argument, "name").map(n =>
            symbol("parameter", n, DbtYaml.nameRange(argument), name, attributes = Map("overload" -> body), mapping = Some(argument))))
      }
    }

  /** A property entry for every model version, or a single entry for unversioned resources. */
  private def versions(symbol: Builder, root: DbtRoot, m: YAMLMapping, kind: String, name: String): Vector[DbtSymbol] = {
    val versions = if (root.supports(5)) DbtYaml.items(m, "versions") else Vector.empty
    val entries = if (versions.isEmpty) Vector(m -> Option.empty[String]) else versions.map(v => v -> DbtYaml.string(v, "v"))
    entries.flatMap { case (entry, version) =>
      val resource = symbol(kind, name, DbtYaml.nameRange(m), version = version, mapping = Some(entry),
        attributes = DbtYaml.attributes(m) ++ DbtYaml.attributes(entry) ++ Map("property" -> "true"))
      val children = childKeys.flatMap { case (key, childKind) =>
        // Versions without their own columns inherit the columns of the model.
        val items = if (key == "columns" && version.nonEmpty && DbtYaml.value(entry, key).isEmpty) DbtYaml.items(m, key)
          else DbtYaml.items(entry, key)
        items.flatMap(c => DbtYaml.string(c, "name").toVector.flatMap { n =>
          val inherited = if (childKind == "source_table") DbtYaml.attributes(m) else Map.empty[String, String]
          val child = symbol(childKind, n, DbtYaml.nameRange(c), name, version, inherited ++ DbtYaml.attributes(c), Some(c))
          val columns = if (childKind != "source_table") Vector.empty else DbtYaml.items(c, "columns").flatMap(col =>
            DbtYaml.string(col, "name").map(cn => symbol("column", cn, DbtYaml.nameRange(col), s"$name.$n",
              attributes = DbtYaml.attributes(col), mapping = Some(col))))
          child +: columns
        })
      }
      resource +: children
    }
  }

  private def metricAliases(symbol: Builder, root: DbtRoot, declarations: Vector[DbtSymbol]): Vector[DbtSymbol] =
    declarations.filter(_.kind == "metric").flatMap(metric => metric.mapping.toVector.flatMap { m =>
      val parameters = DbtYaml.map(m, "type_params").getOrElse(m)
      val inputs = DbtYaml.items(parameters, "metrics") ++ (if (root.supports(12)) DbtYaml.items(m, "input_metrics") else Vector.empty)
      inputs.flatMap(input => DbtYaml.string(input, "alias").map(alias =>
        symbol("metric_alias", alias, DbtYaml.nameRange(input, "alias"), metric.name,
          attributes = DbtYaml.attributes(input), mapping = Some(input))))
    })
}
