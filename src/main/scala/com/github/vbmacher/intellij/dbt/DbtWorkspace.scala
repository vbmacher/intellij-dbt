package com.github.vbmacher.intellij.dbt

import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.{LocalFileSystem, VfsUtilCore, VirtualFile, VirtualFileManager}
import com.intellij.psi.{PsiElement, PsiFile, PsiManager, PsiNamedElement}
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.util.{CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import org.jetbrains.yaml.psi._
import scala.jdk.CollectionConverters._
import scala.collection.mutable.ArrayBuffer

private[dbt] object DbtYaml {
  def mapping(file: PsiFile): Option[YAMLMapping] = file match {
    case yaml: YAMLFile => yaml.getDocuments.asScala.headOption.flatMap(d => Option(d.getTopLevelValue)).collect {
      case m: YAMLMapping => m
    }
    case _ => None
  }
  def value(m: YAMLMapping, key: String): Option[YAMLValue] = Option(m.getKeyValueByKey(key)).flatMap(k => Option(k.getValue))
  def map(m: YAMLMapping, key: String): Option[YAMLMapping] = value(m, key).collect { case x: YAMLMapping => x }
  def string(m: YAMLMapping, key: String): Option[String] = value(m, key).collect { case s: YAMLScalar => s.getTextValue }
  def items(m: YAMLMapping, key: String): Vector[YAMLMapping] = value(m, key).collect {
    case s: YAMLSequence => s.getItems.asScala.toVector.flatMap(i => Option(i.getValue).collect { case x: YAMLMapping => x })
  }.getOrElse(Vector.empty)
  def strings(m: YAMLMapping, key: String, default: Vector[String] = Vector.empty): Vector[String] = value(m, key) match {
    case Some(s: YAMLSequence) => s.getItems.asScala.toVector.flatMap(i => Option(i.getValue).collect { case x: YAMLScalar => x.getTextValue })
    case Some(s: YAMLScalar) => Vector(s.getTextValue)
    case None => default
    case _ => Vector.empty
  }
  def nameRange(m: YAMLMapping, key: String = "name"): TextRange = value(m, key).map(_.getTextRange).getOrElse(m.getTextRange)
  def attributes(m: YAMLMapping): Map[String, String] = m.getKeyValues.asScala.flatMap(k => Option(k.getValue).collect {
    case s: YAMLScalar => k.getKeyText -> s.getTextValue
    case s: YAMLSequence if k.getKeyText == "tags" => k.getKeyText -> strings(m, "tags").mkString(",")
  }).toMap ++ map(m, "config").toVector.flatMap(attributes)
}

private[dbt] object DbtSymbolLanguage extends Language("dbt-symbol")

/** A source declaration with a stable identity, including declarations in plain-text Community files. */
final class DbtSymbolElement private[dbt] (val symbol: DbtSymbol)
  extends LightElement(symbol.file.getManager, DbtSymbolLanguage) with PsiNamedElement {
  private val pointer = com.intellij.psi.SmartPointerManager.getInstance(symbol.file.getProject)
    .createSmartPsiFileRangePointer(symbol.file, symbol.range)
  override def getName: String = symbol.name
  override def setName(name: String): PsiElement = throw new UnsupportedOperationException("dbt rename is not supported")
  override def getContainingFile: PsiFile = symbol.file
  override def getTextRange: TextRange = Option(pointer.getRange).map(r => new TextRange(r.getStartOffset, r.getEndOffset)).getOrElse(TextRange.EMPTY_RANGE)
  override def getTextOffset: Int = getTextRange.getStartOffset
  override def getText: String = symbol.name
  override def getParent: PsiElement = symbol.file
  override def isValid: Boolean = symbol.file.isValid && pointer.getRange != null
  override def isWritable: Boolean = false
  override def navigate(requestFocus: Boolean): Unit = symbol.attributes.get("url") match {
    case Some(url) => com.intellij.ide.BrowserUtil.browse(url)
    case None => new OpenFileDescriptor(getProject, symbol.file.getVirtualFile, getTextOffset).navigate(requestFocus)
  }
  override def canNavigate: Boolean = isValid
  override def canNavigateToSource: Boolean = isValid && !symbol.metadata
  override def toString: String = s"dbt ${symbol.kind}: ${symbol.namespace}.${symbol.name}${if (symbol.metadata && symbol.attributes.get("stale").contains("true")) " (stale metadata)" else if (symbol.metadata) " (metadata)" else ""}"
  override def equals(other: Any): Boolean = other match {
    case e: DbtSymbolElement => symbol.id == e.symbol.id
    case _ => false
  }
  override def hashCode(): Int = symbol.id.hashCode
}

private[dbt] final case class DbtSymbol(kind: String, name: String, namespace: String, file: PsiFile,
  range: TextRange, owner: String = "", version: Option[String] = None,
  attributes: Map[String, String] = Map.empty, mapping: Option[YAMLMapping] = None, metadata: Boolean = false) {
  def id: String = s"${file.getVirtualFile.getPath}:${range.getStartOffset}:$kind:$name:$owner:${version.getOrElse("")}"
  lazy val element: DbtSymbolElement = new DbtSymbolElement(this)
}

private[dbt] final case class DbtRoot(directory: VirtualFile, config: YAMLMapping, settings: Option[YAMLMapping]) {
  val name: String = DbtYaml.string(config, "name").getOrElse(directory.getName)
  def setting(key: String): Option[String] = settings.flatMap(DbtYaml.string(_, key))
  def runConfiguration: Option[YAMLMapping] = setting("run-config").flatMap(p => Option(directory.findFileByRelativePath(p)))
    .flatMap(f => Option(PsiManager.getInstance(config.getProject).findFile(f))).flatMap(DbtYaml.mapping)
  def runtimeSetting(key: String): Option[String] = setting(key).orElse(runConfiguration.flatMap(DbtYaml.string(_, key)))
  val engine: String = setting("engine").getOrElse("core")
  val version: String = setting("dbt-version").orElse(DbtYaml.string(config, "require-dbt-version")
    .flatMap("[0-9]+\\.[0-9]+".r.findFirstIn)).getOrElse("1.10")
  def supports(minor: Int): Boolean = engine == "v2" || (version.split("\\.").take(2).flatMap(_.toIntOption).toVector match {
    case Vector(major, _) if major >= 2 => true
    case Vector(1, m) => m >= minor
    case _ => false
  })
  val pathKinds: Vector[(String, String)] = Vector(
    "model" -> "models", "seed" -> "seeds", "snapshot" -> "snapshots", "analysis" -> "analyses",
    "macro" -> "macros", "test" -> "tests", "doc" -> "docs", "function" -> "functions", "check" -> "checks")
    .filter { case (k, _) => (k != "function" || supports(11)) && (k != "check" || engine == "v2") }
    .flatMap { case (kind, default) =>
      val key = Map("model" -> "model-paths", "analysis" -> "analysis-paths", "doc" -> "docs-paths").getOrElse(kind, s"$kind-paths")
      DbtYaml.strings(config, key, Vector(default)).map(kind -> _)
    }
  val packagePath: String = DbtYaml.string(config, "packages-install-path").getOrElse("dbt_packages")
  private val ignores: Vector[java.nio.file.PathMatcher] = Option(directory.findChild(".dbtignore")).toVector.flatMap { file =>
    Option(PsiManager.getInstance(config.getProject).findFile(file)).map(_.getText).getOrElse("").linesIterator
      .map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#") && !s.startsWith("!")).flatMap { pattern =>
        val relative = pattern.stripPrefix("/").stripSuffix("/")
        Vector(relative, "**/" + relative, relative + "/**")
          .flatMap(p => scala.util.Try(java.nio.file.FileSystems.getDefault.getPathMatcher("glob:" + p)).toOption)
      }.toVector
  }
  val exclusions: Vector[String] = Vector(".git", ".idea", "logs", packagePath, DbtYaml.string(config, "target-path").getOrElse("target"))
  def relative(file: VirtualFile): Option[String] = Option(VfsUtilCore.getRelativePath(file, directory, '/'))
  def pathContains(path: String, relative: String): Boolean = path == "." || relative == path || relative.startsWith(path.stripSuffix("/") + "/")
  def excluded(file: VirtualFile): Boolean = relative(file).exists(r => exclusions.exists(pathContains(_, r)) || ignores.exists(_.matches(java.nio.file.Paths.get(r))))
  def kind(file: VirtualFile): Option[String] = relative(file).flatMap { r => pathKinds.collectFirst { case (k, p) if pathContains(p, r) => k } }
  def resource(file: PsiFile): Boolean = !excluded(file.getVirtualFile) && kind(file.getVirtualFile).nonEmpty
}

private[dbt] final class DbtWorkspace(val project: Project, val roots: Vector[DbtRoot], val files: Vector[PsiFile],
  val symbols: Vector[DbtSymbol]) {
  def root(file: PsiFile): Option[DbtRoot] = Option(file.getVirtualFile).flatMap(v => roots.filter(r => VfsUtilCore.isAncestor(r.directory, v, false))
    .sortBy(_.directory.getPath.length).lastOption)
  def packageOwner(r: DbtRoot): Option[DbtRoot] = roots.filter(p => p != r &&
    p.relative(r.directory).exists(p.pathContains(p.packagePath, _))).sortBy(_.directory.getPath.length).lastOption
  def namespaceVisible(from: DbtRoot, target: DbtRoot, qualified: Boolean): Boolean = {
    if (from == target || packageOwner(target).contains(packageOwner(from).getOrElse(from))) true
    else if (!qualified) false
    else files.find(f => f.getVirtualFile.getParent == from.directory && f.getName == "dependencies.yml")
      .flatMap(DbtYaml.mapping).exists(m => DbtYaml.items(m, "projects").exists(x => DbtYaml.string(x, "name").contains(target.name)))
  }
  private def projectDependency(from: DbtRoot, namespace: String): Boolean = files.find(f =>
    f.getVirtualFile.getParent == from.directory && f.getName == "dependencies.yml").flatMap(DbtYaml.mapping)
    .exists(m => DbtYaml.items(m, "projects").exists(p => DbtYaml.string(p, "name").contains(namespace)))
  private def visibleSymbol(symbol: DbtSymbol, from: DbtRoot, qualified: Boolean): Boolean = {
    if (symbol.namespace == from.name) true
    else if (symbol.metadata) qualified && projectDependency(from, symbol.namespace) && symbol.attributes.get("access").contains("public")
    else root(symbol.file).exists { target =>
      if (packageOwner(target).contains(packageOwner(from).getOrElse(from))) !symbol.attributes.get("access").contains("private")
      else qualified && projectDependency(from, target.name) && symbol.attributes.get("access").contains("public")
    }
  }
  def declarations(kind: Set[String], name: String, from: DbtRoot, namespace: Option[String] = None): Vector[DbtSymbol] = {
    val candidates = symbols.filter(s => kind(s.kind) && s.name == name &&
      namespace.forall(_ == s.namespace) && visibleSymbol(s, from, namespace.nonEmpty))
    val local = candidates.filter(_.namespace == from.name)
    val selected = if (namespace.isEmpty && local.nonEmpty) local else candidates
    selected.filterNot(_.attributes.get("enabled").exists(_.equalsIgnoreCase("false"))).filterNot(s => s.attributes.contains("property") && selected.exists(c =>
      !c.attributes.contains("property") && c.kind == s.kind && c.namespace == s.namespace && c.name == s.name && c.version == s.version))
  }
  def properties(symbol: DbtSymbol): Vector[DbtSymbol] = symbols.filter(s => s.attributes.contains("property") &&
    s.kind == symbol.kind && s.namespace == symbol.namespace && s.name == symbol.name && s.version == symbol.version)
  def symbolAt(file: PsiFile, offset: Int): Vector[DbtSymbol] = symbols.filter(s => s.file == file && s.range.containsOffset(offset))
}

private[dbt] object DbtWorkspace {
  private val FileIndex = com.intellij.openapi.util.Key.create[com.intellij.psi.util.CachedValue[Vector[DbtSymbol]]]("dbt.file.declarations")
  private val resourceKeys = Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot", "analyses" -> "analysis",
    "macros" -> "macro", "sources" -> "source", "exposures" -> "exposure", "groups" -> "group", "metrics" -> "metric",
    "semantic_models" -> "semantic_model", "saved_queries" -> "saved_query", "functions" -> "function", "unit_tests" -> "unit_test", "checks" -> "check")
  def get(project: Project): DbtWorkspace = CachedValuesManager.getManager(project).getCachedValue(project, () => {
    val workspace = build(project)
    val artifacts = DbtParquetArtifacts.files(workspace).flatMap(f => Option(PsiManager.getInstance(project).findFile(f)))
    CachedValueProvider.Result.create(workspace, (Vector[AnyRef](PsiModificationTracker.MODIFICATION_COUNT,
      VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS) ++ artifacts): _*)
  })

  private def build(project: Project): DbtWorkspace = {
    val manager = PsiManager.getInstance(project)
    val bases = (Option(project.getBasePath).flatMap(p => Option(LocalFileSystem.getInstance.findFileByPath(p))).toVector ++
      ProjectRootManager.getInstance(project).getContentRoots.toVector).distinct
    val configs = ArrayBuffer.empty[VirtualFile]
    val discoveryRoots = scala.collection.mutable.Map.empty[VirtualFile, Option[DbtRoot]]
    def discoveryRoot(directory: VirtualFile): Option[DbtRoot] = if (directory == null) None else discoveryRoots.getOrElseUpdate(directory, {
      Option(directory.findChild(DbtPlugin.ProjectFileName)).flatMap(f => Option(manager.findFile(f))).flatMap(DbtYaml.mapping)
        .map(m => DbtRoot(directory, m, None)).orElse(discoveryRoot(directory.getParent))
    })
    def discoverable(file: VirtualFile): Boolean = !Set(".git", ".idea", "target", "logs").contains(file.getName) &&
      discoveryRoot(file.getParent).forall(r => !r.excluded(file) || r.relative(file).exists(r.pathContains(r.packagePath, _)))
    bases.foreach(b => VfsUtilCore.iterateChildrenRecursively(b, discoverable, f => {
      ProgressManager.checkCanceled()
      if (f.getName == DbtPlugin.ProjectFileName) configs += f
      true
    }))
    val roots = configs.distinct.toVector.flatMap(f => Option(manager.findFile(f)).flatMap(DbtYaml.mapping).map { m =>
      val settings = Option(f.getParent.findChild(".dbt-navigation.yml")).flatMap(v => Option(manager.findFile(v))).flatMap(DbtYaml.mapping)
      DbtRoot(f.getParent, m, settings)
    })
    val virtualFiles = ArrayBuffer.empty[VirtualFile]
    roots.foreach(r => VfsUtilCore.iterateChildrenRecursively(r.directory,
      f => !r.excluded(f) && !(f.isDirectory && f != r.directory && f.findChild(DbtPlugin.ProjectFileName) != null), f => {
        ProgressManager.checkCanceled()
        if (!f.isDirectory && Set("sql", "py", "yml", "yaml", "md", "csv", "json", "js").contains(Option(f.getExtension).getOrElse(""))) virtualFiles += f
        true
      }))
    val files = virtualFiles.distinct.toVector.flatMap(f => Option(manager.findFile(f)))
    val initial = new DbtWorkspace(project, roots, files, Vector.empty)
    val symbols = files.flatMap(file => initial.root(file).toVector.flatMap { r =>
      val directory = r.directory
      CachedValuesManager.getManager(project).getCachedValue(file, FileIndex, () => {
        val configFile = manager.findFile(directory.findChild(DbtPlugin.ProjectFileName))
        val settingsFile = Option(directory.findChild(".dbt-navigation.yml")).flatMap(f => Option(manager.findFile(f)))
        val currentRoot = DbtRoot(directory, DbtYaml.mapping(configFile).get, settingsFile.flatMap(DbtYaml.mapping))
        val dependencies: Vector[AnyRef] = Vector(file, configFile, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS) ++ settingsFile
        CachedValueProvider.Result.create(indexFile(file, currentRoot), dependencies: _*)
      }, false)
    })
    // Versioned model properties identify the physical file using defined_in, independently of aliases.
    val remapped = symbols.map { s =>
      if (s.attributes.contains("code")) {
        val matches = symbols.filter(p => p.attributes.contains("property") && p.kind == s.kind && p.namespace == s.namespace &&
          p.attributes.get("defined_in").getOrElse(p.name + p.version.map("_v" + _).getOrElse("")) == s.name)
        val resolved = matches.headOption.filter(_ => matches.size == 1).map(p => s.copy(name = p.name, version = p.version,
          attributes = (p.attributes - "property") ++ s.attributes, mapping = p.mapping)).getOrElse(s)
        resolved.copy(attributes = DbtConfiguration.inherited(initial, resolved.file, resolved.name) ++ resolved.attributes)
      } else s
    }
    val projections = remapped.filter(s => s.kind == "model" && s.attributes.contains("code")).flatMap(DbtSqlNavigation.projections)
      .filterNot(c => remapped.exists(s => s.kind == "column" && s.namespace == c.namespace && s.owner == c.owner && s.name == c.name && s.version == c.version))
    val local = new DbtWorkspace(project, roots, files, remapped ++ projections)
    val json = new DbtWorkspace(project, roots, files, local.symbols ++ DbtArtifacts.symbols(local))
    new DbtWorkspace(project, roots, files, json.symbols ++ DbtParquetArtifacts.symbols(json))
  }

  private def indexFile(file: PsiFile, root: DbtRoot): Vector[DbtSymbol] = {
    val result = ArrayBuffer.empty[DbtSymbol]
    val ext = Option(file.getVirtualFile.getExtension).getOrElse("")
    val kind = root.kind(file.getVirtualFile)
    def symbol(k: String, name: String, range: TextRange, owner: String = "", version: Option[String] = None,
      attrs: Map[String, String] = Map.empty, mapping: Option[YAMLMapping] = None): DbtSymbol =
      DbtSymbol(k, name, root.name, file, range, owner, version, attrs, mapping)
    val fixture = kind.contains("test") && root.relative(file.getVirtualFile).exists(relative => root.pathKinds.exists {
      case ("test", path) => root.pathContains(path.stripSuffix("/") + "/fixtures", relative)
      case _ => false
    }) && Set("csv", "sql").contains(ext)
    if (fixture) result += symbol("fixture", file.getVirtualFile.getNameWithoutExtension, new TextRange(0, 0), attrs = Map("code" -> "true"))
    val code = kind.exists {
      case "seed" => ext == "csv"
      case "model" => Set("sql", "py").contains(ext)
      case "function" => Set("sql", "py", "js").contains(ext)
      case "snapshot" | "analysis" | "test" | "check" => ext == "sql"
      case _ => false
    }
    if (code && !fixture) {
      val attrs = Map("code" -> "true") ++ DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).filter(_.name == "config")
        .flatMap(_.arguments.flatMap(a => for (key <- a.keyword; value <- a.literal) yield key -> value))
      result += symbol(kind.get, file.getVirtualFile.getNameWithoutExtension, new TextRange(0, 0), attrs = attrs)
    }
    if (root.resource(file) && Set("sql", "md").contains(ext)) {
      DbtSyntax.inFile(file).filter(_.tag).foreach { b =>
        b.tokens.take(2) match {
          case Vector(tag, name) if Set("macro", "test", "docs", "snapshot", "materialization").contains(tag.image) =>
            val k = Map("docs" -> "doc", "test" -> "generic_test").getOrElse(tag.image, tag.image)
            val adapter = if (k == "materialization") b.tokens.sliding(3).collectFirst {
              case Vector(key, eq, value) if key.image == "adapter" && eq.image == "=" && value.string => value.image
            }.getOrElse("default") else ""
            result += symbol(k, name.image, name.range, attrs = Map("block-start" -> b.range.getEndOffset.toString) ++
              (if (k == "materialization") Map("adapter" -> adapter) else Map.empty[String, String]))
            if (Set("macro", "test").contains(tag.image)) DbtSyntax.parameters(b).foreach(t =>
              result += symbol("parameter", t.image, t.range, name.image))
          case _ => ()
        }
      }
    }
    if (kind.nonEmpty && Set("yml", "yaml").contains(ext)) DbtYaml.mapping(file).foreach { top =>
      resourceKeys.foreach { case (key, k) =>
        if ((k != "function" || root.supports(11)) && (k != "check" || root.engine == "v2") &&
          (k != "unit_test" || root.supports(8)) && (k != "semantic_model" || root.supports(6))) DbtYaml.items(top, key).foreach { m =>
          DbtYaml.string(m, "name").foreach { name =>
            if (k == "model" && root.supports(12)) {
              DbtYaml.map(m, "semantic_model").foreach { semantic =>
                val semanticName = DbtYaml.string(semantic, "name").getOrElse(name)
                result += symbol("semantic_model", semanticName, DbtYaml.nameRange(semantic), name,
                  attrs = Map("embedded" -> "true"), mapping = Some(m))
                DbtYaml.items(m, "columns").foreach { column =>
                  DbtYaml.string(column, "name").foreach { columnName =>
                    Vector("entity", "dimension").foreach { field => DbtYaml.map(column, field).foreach { definition =>
                      val n = DbtYaml.string(definition, "name").getOrElse(columnName)
                      result += symbol(field, n, DbtYaml.value(definition, "name").map(_.getTextRange).getOrElse(DbtYaml.nameRange(column)),
                        semanticName, attrs = DbtYaml.attributes(definition) ++ Map("column" -> columnName, "model" -> name), mapping = Some(definition))
                    }}
                  }
                }
                DbtYaml.map(m, "derived_semantics").foreach { derived =>
                  Vector("dimensions" -> "dimension", "entities" -> "entity").foreach { case (key, childKind) =>
                    DbtYaml.items(derived, key).foreach { definition => DbtYaml.string(definition, "name").foreach { n =>
                      result += symbol(childKind, n, DbtYaml.nameRange(definition), semanticName,
                        attrs = DbtYaml.attributes(definition) ++ Map("model" -> name), mapping = Some(definition))
                    }}
                  }
                }
              }
              DbtYaml.items(m, "metrics").foreach { metric => DbtYaml.string(metric, "name").foreach { n =>
                result += symbol("metric", n, DbtYaml.nameRange(metric), name, attrs = DbtYaml.attributes(metric), mapping = Some(metric))
              }}
            }
            if (k == "function" && root.supports(12)) DbtYaml.items(m, "overloads").foreach { overload =>
              DbtYaml.string(overload, "defined_in").foreach { body =>
                result += symbol(k, name, DbtYaml.nameRange(m), attrs = DbtYaml.attributes(m) ++ DbtYaml.attributes(overload) ++
                  Map("property" -> "true", "overload" -> body), mapping = Some(overload))
                DbtYaml.items(overload, "arguments").foreach { argument => DbtYaml.string(argument, "name").foreach { n =>
                  result += symbol("parameter", n, DbtYaml.nameRange(argument), name, attrs = Map("overload" -> body), mapping = Some(argument))
                }}
              }
            }
            val versions = if (root.supports(5)) DbtYaml.items(m, "versions") else Vector.empty
            val entries = if (versions.isEmpty) Vector(m -> Option.empty[String]) else versions.map(v => v -> DbtYaml.string(v, "v"))
            entries.foreach { case (entry, version) =>
              val attrs = DbtYaml.attributes(m) ++ DbtYaml.attributes(entry) ++ Map("property" -> "true")
              result += symbol(k, name, DbtYaml.nameRange(m), version = version, attrs = attrs, mapping = Some(entry))
              Vector("columns" -> "column", "entities" -> "entity", "dimensions" -> "dimension", "measures" -> "measure",
                "arguments" -> "parameter", "tables" -> "source_table", "exports" -> "export").foreach { case (children, childKind) =>
                val entries = if (children == "columns" && version.nonEmpty && DbtYaml.value(entry, children).isEmpty) DbtYaml.items(m, children) else DbtYaml.items(entry, children)
                entries.foreach { c => DbtYaml.string(c, "name").foreach { n =>
                  result += symbol(childKind, n, DbtYaml.nameRange(c), name, version, (if (childKind == "source_table") DbtYaml.attributes(m) else Map.empty[String, String]) ++ DbtYaml.attributes(c), Some(c))
                  if (childKind == "source_table") DbtYaml.items(c, "columns").foreach { col => DbtYaml.string(col, "name").foreach { cn =>
                    result += symbol("column", cn, DbtYaml.nameRange(col), s"$name.$n", attrs = DbtYaml.attributes(col), mapping = Some(col))
                  }}
                }}
              }
            }
          }
        }
      }
    }
    result.filter(_.kind == "metric").toVector.foreach { metric => metric.mapping.foreach { m =>
      val parameters = DbtYaml.map(m, "type_params").getOrElse(m)
      DbtYaml.items(parameters, "metrics").foreach { input => DbtYaml.string(input, "alias").foreach { alias =>
        result += symbol("metric_alias", alias, DbtYaml.nameRange(input, "alias"), metric.name, attrs = DbtYaml.attributes(input), mapping = Some(input))
      }}
    }}
    result.toVector
  }
}
