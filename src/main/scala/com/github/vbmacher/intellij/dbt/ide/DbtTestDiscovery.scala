package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.yaml.psi.{YAMLMapping, YAMLScalar, YAMLSequence}
import scala.jdk.CollectionConverters._

private[ide] final case class DbtTestDefinition(id: String, name: String, kind: String, group: String,
  root: String, file: VirtualFile, offset: Int, selector: String, dependencies: Vector[String],
  fixtures: Vector[(VirtualFile, Int)] = Vector.empty, relation: String = "", provenance: String = "Source declaration", packageName: String = "", projectName: String = "", description: String = "")

/** Source definitions remain browsable offline. Manifest identities enrich generated generic tests without guessing their hashes. */
private[ide] object DbtTestDiscovery {
  import DbtExecutionEvidence._
  def discover(w: DbtWorkspace, executionOptions: Map[String, DbtRunOptions] = Map.empty): Vector[DbtTestDefinition] = {
    lazy val sourceDependencies = DbtLineage.edges(w).filter(e => !e.ambiguous && DbtSymbol.RelationKinds(e.to.kind))
      .groupMap(_.from.id)(_.to.uniqueId)
    val source = w.symbols.filter(s => Set("test", "unit_test")(s.kind) && !s.metadata && s.isEnabled && !w.symbols.exists(g => g.kind == "generic_test" && g.file == s.file)).flatMap { s =>
      w.owner(s).map { owner =>
        val root = w.executionRoot(owner)
        val group = s.mapping.flatMap(DbtYaml.string(_, "model")).getOrElse("Data tests")
        val fixtures = s.mapping.toVector.flatMap(m => DbtYaml.items(m, "given") ++ DbtYaml.map(m, "expect").toVector).flatMap { m =>
          DbtYaml.string(m, "fixture").toVector.flatMap(name => w.symbols.filter(f => f.kind == "fixture" && f.name == name && w.owner(f).contains(owner))
            .map(f => f.file.getVirtualFile -> f.range.getStartOffset))
        }
        val fqn = Vector(s.namespace) ++ owner.resourceFolders(s.file.getVirtualFile).toVector.flatMap(_._2) ++ Vector(s.name)
        val dependencies = if (s.kind == "unit_test") w.declarations(Set("model"), group.split("\\.").last, owner,
          if (group.contains(".")) group.split("\\.").headOption else None).map(_.uniqueId)
          else (DbtResourceData.get(w, s).dependencies ++ sourceDependencies.getOrElse(s.id, Vector.empty)).distinct
        DbtTestDefinition(s.uniqueId, s.presentableText, if (s.kind == "unit_test") "Unit tests" else "Data tests", group,
          root.directory.getPath, s.file.getVirtualFile, s.range.getStartOffset,
          if (s.kind == "unit_test") "fqn:" + (Vector(s.namespace) ++ owner.resourceFolders(s.file.getVirtualFile).toVector.flatMap(_._2) ++ Vector(group, s.name)).mkString(".") + ",test_type:unit" else "fqn:" + fqn.mkString("."), dependencies, fixtures, packageName = owner.name, projectName = root.name, description = DbtResourceData.get(w, s).description)
      }
    }
    val attached = w.symbols.filter(s => s.mapping.nonEmpty && (DbtSymbol.RefKinds(s.kind) || s.kind == "column" || s.kind == "source_table")).flatMap { s =>
      w.owner(s).toVector.flatMap { owner => s.mapping.toVector.flatMap { m =>
        Vector("tests", "data_tests").flatMap(k => DbtYaml.value(m, k).toVector.collect { case seq: YAMLSequence => seq }.flatMap(_.getItems.asScala)).flatMap { item =>
          val value = Option(item.getValue)
          val declaration = value.flatMap {
            case scalar: YAMLScalar => Some((scalar.getTextValue, scalar.getTextOffset, Option.empty[YAMLMapping]))
            case mapping: YAMLMapping => mapping.getKeyValues.asScala.headOption.map(k => (k.getKeyText, k.getTextOffset, Option(k.getValue).collect { case m: YAMLMapping => m }))
            case _ => None
          }
          declaration.map { case (name, offset, config) =>
            val customName = config.flatMap(DbtYaml.string(_, "name"))
            val resource = if (s.kind == "column") w.symbols.find(r => DbtSymbol.RelationKinds(r.kind) && r.relationName == s.owner &&
              r.namespace == s.namespace && r.version == s.version && w.owner(r).contains(owner)) else Some(s)
            DbtTestDefinition("declaration:" + s.id + ":" + offset, customName.getOrElse(name) + (if (s.kind == "column") " (" + s.name + ")" else ""),
              "Data tests", resource.map(_.presentableText).getOrElse(s.owner), w.executionRoot(owner).directory.getPath,
              s.file.getVirtualFile, offset, customName.map(n => "fqn:" + s.namespace + "." + n).getOrElse(""), resource.map(_.uniqueId).toVector, packageName = owner.name, projectName = w.executionRoot(owner).name)
          }
        }
      }}
    }.distinctBy(t => (t.root, t.file.getPath, t.offset, t.dependencies))
    val declarations = source ++ attached
    val manifest = w.roots.map(w.executionRoot).distinct.map(r => executionOptions.get(r.directory.getPath).fold(r)(r.withRunOptions)).flatMap { root =>
      root.setting("manifest").flatMap(p => DbtManifestReader.get(w.project)
        .read(root, p, DbtManifestPolicy.TestDiscovery).toOption).toVector.flatMap { top =>
        top.in("nodes", "unit_tests").map(_.data).filter(n => Set("test", "unit_test")(string(n, "resource_type")) && string(obj(n, "config"), "enabled") != "false").flatMap { n =>
          ProgressManager.checkCanceled()
          val id = string(n, "unique_id"); val name = string(n, "name"); val pkg = string(n, "package_name")
          val path = string(n, "original_file_path")
          val deps = Option(obj(n, "depends_on").get("nodes")).filter(_.isJsonArray).toVector.flatMap(_.getAsJsonArray.asScala.map(_.getAsString))
          val fqn = Option(n.get("fqn")).filter(_.isJsonArray).toVector.flatMap(_.getAsJsonArray.asScala.map(_.getAsString)).mkString(".")
          val matching = declarations.filter(t => t.root == root.directory.getPath &&
            (t.id == id || t.file.getPath.endsWith("/" + path) && t.dependencies.exists(deps.contains) &&
              (t.name.takeWhile(_ != ' ').split("\\.").last == name || t.name.takeWhile(_ != ' ') == string(obj(n, "test_metadata"), "name")) &&
              (string(n, "column_name").isEmpty || t.name.endsWith("(" + string(n, "column_name") + ")"))))
          val owner = w.roots.find(r => r.name == pkg && w.executionRoot(r) == root)
          val ignored = owner.exists(r => r.psiFile(path).exists(f => r.excluded(f.getVirtualFile)))
          val file = if (ignored) None else (if (matching.size == 1) matching.headOption.map(_.file) else None)
            .orElse(owner.flatMap(_.psiFile(path)).map(_.getVirtualFile))
            .orElse(root.setting("manifest").flatMap(root.psiFile).map(_.getVirtualFile))
          file.map { f =>
            val exact = matching.size == 1
            DbtTestDefinition(id, s"$pkg.$name", if (string(n, "resource_type") == "unit_test") "Unit tests" else "Data tests",
              deps.filter(_.startsWith("model.")).mkString(", ") match { case "" => "Data tests"; case g => g },
              root.directory.getPath, f, if (exact) matching.head.offset else 0,
              if (fqn.nonEmpty) "fqn:" + fqn + ",resource_type:" + string(n, "resource_type") else "", deps,
              if (exact) matching.head.fixtures else Vector.empty, string(n, "relation_name"),
              if (exact) "Source definition + manifest identity" else "Manifest identity; source range unavailable", pkg, root.name, string(n, "description"))
          }
        }
      }
    }
    val superseded = manifest.flatMap(t => declarations.filter(s => s.root == t.root && s.file == t.file && s.offset == t.offset && s.dependencies.exists(t.dependencies.contains)).map(_.id)).toSet
    (declarations.filterNot(s => superseded(s.id)) ++ manifest).distinctBy(t => (t.root, t.id)).sortBy(t => (t.root, t.group, t.kind, t.name, t.id))
  }

  def preview(command: DbtCommand, relation: String, limit: Int): Either[String, DbtCommand] = {
    if (relation.trim.isEmpty) Left("No stored failing rows are available. Configure store_failures and run this test.")
    else Right(command.copy(request = DbtCommandRequest(DbtCommandAction.Preview,
      DbtCommandScope.Inline("select * from " + relation), limit = limit.max(1).min(1000)), colors = false,
      sourceSql = "", previewSource = None))
  }

  /** Preserve the exact executable, environment, profiles and target from the original run. */
  def rerun(command: DbtCommand, tests: Vector[DbtTestDefinition], results: Vector[DbtExecutionResult]): Either[String, DbtCommand] = {
    val failed = results.filter(r => Set("failed", "error")(r.status)).map(_.id).toSet
    val intended = tests.filter(t => failed(t.id))
    select(command, intended)
  }

  /** Exact test scope replaces prior selectors while retaining target, profiles and execution flags. */
  def select(command: DbtCommand, intended: Vector[DbtTestDefinition]): Either[String, DbtCommand] = {
    val root = Option(command.root).filter(_.nonEmpty).getOrElse(command.artifactContext.headOption.getOrElse(command.directory))
    if (intended.isEmpty) Left("No tests in this scope.")
    else if (intended.exists(_.root != root)) Left("Choose tests from one execution project.")
    else if (intended.exists(_.selector.isEmpty)) Left("Exact test identities unavailable. Generate Docs for this project and target, then refresh tests.")
    else {
      Right(command.copy(request = command.request.copy(scope = DbtCommandScope.Selected(intended.map(_.selector).distinct),
        exclude = Vector.empty, indirectSelection = Some("empty"))))
    }
  }
}
