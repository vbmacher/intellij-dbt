package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.google.gson.{JsonArray, JsonElement, JsonNull, JsonObject}
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.{LocalFileSystem, VfsUtilCore, VirtualFile, VirtualFileManager}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import com.intellij.psi.{PsiDocumentManager, PsiFile}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** All dbt projects of an IDE project with their files and declarations; cached until PSI or VFS changes. */
private[dbt] final class DbtWorkspace(val project: Project, val roots: Vector[DbtRoot], val files: Vector[PsiFile],
  val symbols: Vector[DbtSymbol]) extends com.intellij.openapi.util.UserDataHolderBase {
  private val documentation = new java.util.concurrent.ConcurrentHashMap[String, DbtResourceData]()
  def resourceData(symbol: DbtSymbol)(read: => DbtResourceData): DbtResourceData =
    documentation.computeIfAbsent(symbol.id, _ => read)
  private lazy val byKindAndName = symbols.groupBy(s => (s.kind, s.name))

  private def rootOf(file: VirtualFile): Option[DbtRoot] =
    roots.filter(r => VfsUtilCore.isAncestor(r.directory, file, false)).sortBy(_.directory.getPath.length).lastOption

  def root(file: PsiFile): Option[DbtRoot] = Option(file.getVirtualFile).flatMap(rootOf)

  /** The project which declares a symbol, including metadata symbols read from artifacts. */
  def owner(symbol: DbtSymbol): Option[DbtRoot] = symbol.attributes.get("resource-root").flatMap(p => roots.find(_.directory.getPath == p))
    .orElse(root(symbol.file))
    .orElse(symbol.attributes.get("artifact_path").flatMap(p => Option(LocalFileSystem.getInstance.findFileByPath(p))).flatMap(rootOf))

  /** A file directly in the project directory. */
  def rootFile(root: DbtRoot, name: String): Option[PsiFile] =
    files.find(f => f.getVirtualFile.getParent == root.directory && f.getName == name)

  /** The project which installed a package. */
  def packageOwner(r: DbtRoot): Option[DbtRoot] = roots.filter(p => p != r &&
    p.relative(r.directory).exists(p.pathContains(p.packagePath, _))).sortBy(_.directory.getPath.length).lastOption

  /** Installed package models execute in the consuming project's target/profile context. */
  def executionRoot(r: DbtRoot): DbtRoot = packageOwner(r).map(executionRoot).getOrElse(r)

  private def samePackageTree(from: DbtRoot, target: DbtRoot): Boolean =
    packageOwner(target).contains(packageOwner(from).getOrElse(from))

  private def projectDependency(from: DbtRoot, namespace: String): Boolean = rootFile(from, "dependencies.yml")
    .flatMap(DbtYaml.mapping).exists(m => DbtYaml.items(m, "projects").exists(p => DbtYaml.string(p, "name").contains(namespace)))

  def namespaceVisible(from: DbtRoot, target: DbtRoot, qualified: Boolean): Boolean =
    from == target || samePackageTree(from, target) || qualified && from.supports(6) && projectDependency(from, target.name)

  private def visibleSymbol(symbol: DbtSymbol, from: DbtRoot, qualified: Boolean): Boolean = {
    val public = symbol.attributes.get("access").contains("public")
    if (symbol.metadata) owner(symbol).contains(from) &&
      (symbol.namespace == from.name || qualified && projectDependency(from, symbol.namespace) && public)
    else root(symbol.file).exists { target =>
      if (target == from) true
      else if (samePackageTree(from, target)) !symbol.attributes.get("access").contains("private")
      else qualified && from.supports(6) && projectDependency(from, target.name) && public
    }
  }

  /** Declarations visible from a project; unqualified names prefer the project's own declarations. */
  def declarations(kind: Set[String], name: String, from: DbtRoot, namespace: Option[String] = None): Vector[DbtSymbol] = {
    ProgressManager.checkCanceled()
    val candidates = kind.toVector.flatMap(k => byKindAndName.getOrElse(k -> name, Vector.empty)).filter { s =>
      ProgressManager.checkCanceled()
      namespace.forall(_ == s.namespace) && visibleSymbol(s, from, namespace.nonEmpty)
    }
    def usable(selected: Vector[DbtSymbol]): Vector[DbtSymbol] =
      selected.filter(s => from.supportsKind(s.kind) && (s.kind != "function" || functionSupported(s, from)))
        .filter(s => s.isEnabled || s.kind == "source" && symbols.exists(t => t.kind == "source_table" && t.owner == s.name &&
          t.namespace == s.namespace && owner(t) == owner(s) && t.isEnabled && s.mapping.exists(_.getTextRange.contains(t.range))))
        .filterNot(s => s.isProperty && selected.exists(c => !c.isProperty && c.sameResource(s)))
    // Like dbt, an unqualified name falls back to packages when no enabled local declaration exists.
    val local = if (namespace.isEmpty) usable(candidates.filter(_.namespace == from.name)) else Vector.empty
    if (local.nonEmpty) local else usable(candidates)
  }

  private def functionSupported(symbol: DbtSymbol, from: DbtRoot): Boolean = {
    val language = if (symbol.isCode) Option(symbol.file.getVirtualFile.getExtension)
      else symbol.attributes.get("language").orElse(symbol.attributes.get("node_language"))
    val adapter = from.adapter
    val languages = language.forall {
      case "js" | "javascript" => from.supports(12) && adapter.forall(Set("snowflake", "bigquery"))
      case "py" | "python" => adapter.forall(Set("snowflake", "bigquery", "databricks"))
      case "sql" => adapter.forall(a => Set("snowflake", "bigquery", "databricks", "redshift", "postgres")(a) &&
        !(from.engine == "v2" && a == "postgres"))
      case _ => false
    }
    languages && (!symbol.attributes.contains("overload") || from.supports(12) &&
      adapter.forall(a => a == "snowflake" || a == "postgres" && language.forall(_ == "sql")))
  }

  def properties(symbol: DbtSymbol): Vector[DbtSymbol] =
    symbols.filter(s => s.isProperty && s.sameResource(symbol) && root(s.file) == root(symbol.file))

  def symbolAt(file: PsiFile, offset: Int): Vector[DbtSymbol] = symbols.filter(s => s.file == file && s.range.containsOffset(offset))

  /** Declarations of a kind whose YAML entry encloses an offset. */
  def enclosing(kind: String, file: PsiFile, offset: Int): Vector[DbtSymbol] =
    symbols.filter(s => s.kind == kind && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(offset)))

  /** Columns declared for a relation, including its version. */
  def columns(relation: DbtSymbol): Vector[DbtSymbol] = symbols.filter(c => c.kind == "column" && c.namespace == relation.namespace &&
    c.owner == relation.relationName && c.version == relation.version && owner(c) == owner(relation))

  /** Relations owning a column: those of the same project, package and version named by the column's owner. */
  def relations(column: DbtSymbol): Vector[DbtSymbol] = symbols.filter(s => DbtSymbol.RelationKinds(s.kind) &&
    s.relationName == column.owner && s.namespace == column.namespace && s.version == column.version && owner(s) == owner(column))

  /** Symbols of the resources defined by a file itself, rather than by blocks or properties inside it. */
  def codeSymbols(file: PsiFile): Vector[DbtSymbol] = symbols.filter(s => s.file == file && s.isCode)

  /** The model defined by a file. */
  def model(file: PsiFile): Option[DbtSymbol] = codeSymbols(file).find(_.kind == "model")

  /** The model defined by the file at a path. */
  def model(path: String): Option[DbtSymbol] = symbols.find(s => s.isCode && s.kind == "model" && s.file.getVirtualFile.getPath == path)

  /** Macros a call may invoke, honouring an explicit `namespace.macro` qualifier. */
  def macros(call: DbtSyntax.Call, from: DbtRoot): Vector[DbtSymbol] = {
    val (namespace, name) = call.callee
    declarations(Set("macro"), name, from, namespace)
  }

  /** Model, function and doc declarations named by `ref`-like calls, optionally qualified by a package. */
  def definitions(call: DbtSyntax.Call, kinds: Set[String], from: DbtRoot): Vector[DbtSymbol] =
    call.qualifiedName.toVector.flatMap { case (namespace, name) => declarations(kinds, name, from, namespace) }

  /** Whether metadata generated at a time is older than the resource sources of a project, including unsaved edits. */
  def outdated(root: DbtRoot, generatedAt: Option[Long]): Boolean = generatedAt.isEmpty || files.exists(f =>
    this.root(f).exists(r => (r == root || packageOwner(r).contains(root)) && r.resource(f)) &&
      (generatedAt.exists(f.getVirtualFile.getTimeStamp > _) || Option(PsiDocumentManager.getInstance(project).getDocument(f))
        .exists(FileDocumentManager.getInstance().isDocumentUnsaved)))
}

private[dbt] object DbtWorkspace {
  val ProjectFileName = "dbt_project.yml"
  private val SettingsFileName = ".dbt-navigation.yml"
  private val FileIndex = Key.create[CachedValue[Vector[DbtSymbol]]]("dbt.file.declarations")

  def get(project: Project): DbtWorkspace = CachedValuesManager.getManager(project).getCachedValue(project, () => {
    val workspace = build(project)
    val inputs = DbtDeclarationSource.EP.getExtensionList.asScala.toVector.flatMap(_.inputs(workspace)).flatMap(DbtYaml.psiFile(project, _))
    CachedValueProvider.Result.create(workspace, (Vector[AnyRef](PsiModificationTracker.MODIFICATION_COUNT,
      VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, DbtProjectSettings.get(project)) ++ inputs): _*)
  })

  private def child(project: Project, directory: VirtualFile, name: String): Option[PsiFile] =
    DbtYaml.psiFile(project, directory.findChild(name))

  private def loadRoot(project: Project, directory: VirtualFile): Option[DbtRoot] =
    child(project, directory, ProjectFileName).flatMap(DbtYaml.mapping)
      .map(DbtRoot(directory, _, child(project, directory, SettingsFileName).flatMap(DbtYaml.mapping)))

  /** Nearest project configuration, without parsing the file or scanning all workspace resources. */
  def rootForFile(project: Project, file: VirtualFile): Option[DbtRoot] =
    if (file == null || project.isDisposed || !file.isValid) None
    else Iterator.iterate(file.getParent)(_.getParent).takeWhile(directory => directory != null && directory.isValid)
      .flatMap(directory => loadRoot(project, directory)).nextOption()

  /** Local declarations for file presentation, without building the entire workspace. */
  def declarationsInFile(file: PsiFile): Vector[DbtSymbol] = Option(file.getVirtualFile).toVector.flatMap { virtualFile =>
    val root = Iterator.iterate(virtualFile.getParent)(_.getParent).takeWhile(_ != null)
      .flatMap(directory => loadRoot(file.getProject, directory)).nextOption()
    root.filterNot(_.excluded(virtualFile)).toVector.flatMap(r => fileSymbols(file.getProject, file, r.directory))
  }

  private def discoverRoots(project: Project): Vector[DbtRoot] = {
    val bases = (Option(project.getBasePath).flatMap(p => Option(LocalFileSystem.getInstance.findFileByPath(p))).toVector ++
      ProjectRootManager.getInstance(project).getContentRoots.toVector).distinct
    val enclosing = mutable.Map.empty[VirtualFile, Option[DbtRoot]]
    def enclosingRoot(directory: VirtualFile): Option[DbtRoot] = if (directory == null) None
      else enclosing.getOrElseUpdate(directory, loadRoot(project, directory).orElse(enclosingRoot(directory.getParent)))
    // Installed packages are excluded from their owner's resources, but they are projects of their own.
    def discoverable(file: VirtualFile): Boolean = !Set(".git", ".idea", ".venv", "venv", "target", "logs").contains(file.getName) &&
      enclosingRoot(file.getParent).forall(r => !r.excluded(file) || r.relative(file).exists(r.pathContains(r.packagePath, _)))
    val configs = mutable.ArrayBuffer.empty[VirtualFile]
    bases.foreach(b => VfsUtilCore.iterateChildrenRecursively(b, discoverable(_), f => {
      ProgressManager.checkCanceled()
      if (f.getName == ProjectFileName) configs += f
      true
    }))
    configs.distinct.toVector.flatMap(f => loadRoot(project, f.getParent))
  }

  private def projectFiles(project: Project, roots: Vector[DbtRoot]): Vector[PsiFile] = {
    val extensions = Set("sql", "jinja", "jinja2", "py", "yml", "yaml", "md", "csv", "json", "js")
    val result = mutable.ArrayBuffer.empty[VirtualFile]
    roots.foreach(r => VfsUtilCore.iterateChildrenRecursively(r.directory,
      f => r.traversable(f) && !(f.isDirectory && f != r.directory && f.findChild(ProjectFileName) != null), f => {
        ProgressManager.checkCanceled()
        if (!f.isDirectory && extensions.contains(Option(f.getExtension).getOrElse(""))) result += f
        true
      }))
    result.distinct.toVector.flatMap(DbtYaml.psiFile(project, _))
  }

  /** Declarations of a single file, cached until the file or its project configuration changes. */
  private def fileSymbols(project: Project, file: PsiFile, directory: VirtualFile): Vector[DbtSymbol] =
    CachedValuesManager.getManager(project).getCachedValue(file, FileIndex, () => {
      val dependencies: Vector[AnyRef] = Vector(file, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS) ++
        child(project, directory, ProjectFileName) ++ child(project, directory, SettingsFileName)
      CachedValueProvider.Result.create(loadRoot(project, directory).map(DbtDeclarations.inFile(file, _))
        .getOrElse(Vector.empty), dependencies: _*)
    }, false)

  private def build(project: Project): DbtWorkspace = {
    val roots = discoverRoots(project)
    val files = projectFiles(project, roots)
    val initial = new DbtWorkspace(project, roots, files, Vector.empty)
    val symbols = files.flatMap(file => initial.root(file).toVector.flatMap(r => fileSymbols(project, file, r.directory)))
    val properties = symbols.filter(_.isProperty).groupBy(p => (p.kind, p.namespace, initial.root(p.file),
      p.attributes.getOrElse("defined_in", p.name + p.version.map("_v" + _).getOrElse(""))))
    // Versioned model properties identify the physical file using defined_in, independently of aliases.
    val remapped = symbols.map { s =>
      if (s.isCode) {
        val matches = properties.getOrElse((s.kind, s.namespace, initial.root(s.file), s.name), Vector.empty)
        val resolved = if (matches.size != 1) s else s.copy(name = matches.head.name, version = matches.head.version,
          attributes = (matches.head.attributes - "property") ++ s.attributes, mapping = matches.head.mapping)
        resolved.copy(attributes = DbtConfiguration.inherited(initial, resolved.file, resolved.name) ++ resolved.attributes)
      } else if (s.isProperty || s.kind == "source_table")
        s.copy(attributes = DbtConfiguration.propertyAttributes(initial, s) ++ s.attributes)
      else s
    }
    // Each source sees the declarations of the previous ones, so artifacts never shadow source declarations.
    DbtDeclarationSource.EP.getExtensionList.asScala.foldLeft(new DbtWorkspace(project, roots, files, remapped)) { (w, source) =>
      new DbtWorkspace(project, roots, files, w.symbols ++ source.declarations(w))
    }
  }
}

/**
 * Port of declarations not written in project sources, such as SQL projections, environment variables and artifacts.
 * Sources are registered in `plugin.xml` in priority order; each one sees the declarations of the sources before it.
 */
private[dbt] trait DbtDeclarationSource {
  def declarations(workspace: DbtWorkspace): Vector[DbtSymbol]

  /** Files read outside of PSI whose changes invalidate the workspace. */
  def inputs(workspace: DbtWorkspace): Vector[VirtualFile] = Vector.empty

  /** Optional documentation/physical metadata; source properties remain authoritative. */
  def documentation(workspace: DbtWorkspace, symbol: DbtSymbol): Option[DbtResourceData] = None

  /** Missing references are not conclusive while configured metadata is unavailable or malformed. */
  def canReportMissing(workspace: DbtWorkspace, root: DbtRoot): Boolean = true

  def artifactStatus(root: DbtRoot): Vector[String] = Vector.empty
}

private[dbt] object DbtDeclarationSource {
  val EP: ExtensionPointName[DbtDeclarationSource] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.declarationSource")
}

/** Consumers choose validation requirements; schema support and resource extraction have one owner.
 * Baseline snapshots contain only resources participating in source-state comparison.
 */
private[dbt] sealed abstract class DbtManifestPolicy(val maxBytes: Int) extends Product with Serializable
private[dbt] object DbtManifestPolicy {
  case object Navigation extends DbtManifestPolicy(Int.MaxValue)
  case object TestDiscovery extends DbtManifestPolicy(25000000)
  case object Baseline extends DbtManifestPolicy(50000000)
}

/** Gson field access and construction; a field holding another JSON type is absent. */
private[dbt] object DbtJson {
  def child(o: JsonObject, key: String): Option[JsonObject] = Option(o.get(key)).filter(_.isJsonObject).map(_.getAsJsonObject)
  /** A JSON string value; numbers, booleans and null are absent. */
  def text(o: JsonObject, key: String): Option[String] =
    Option(o.get(key)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString)
  /** Any JSON primitive as text, or empty. */
  def string(o: JsonObject, key: String): String = Option(o.get(key)).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")

  /** A JSON object of string, boolean, number or JSON values in the given order; `null` becomes JSON null. */
  def obj(values: (String, Any)*): JsonObject = {
    val o = new JsonObject
    values.foreach {
      case (k, v: JsonElement) => o.add(k, v)
      case (k, v: String) => o.addProperty(k, v)
      case (k, v: java.lang.Boolean) => o.addProperty(k, v)
      case (k, v: Number) => o.addProperty(k, v)
      case (k, null) => o.add(k, JsonNull.INSTANCE)
      case (k, v) => throw new IllegalArgumentException("Unsupported JSON value for " + k + ": " + v.getClass)
    }
    o
  }
  def strings(values: Iterable[String]): JsonArray = { val a = new JsonArray; values.foreach(a.add); a }
  def array(values: Iterable[JsonElement]): JsonArray = { val a = new JsonArray; values.foreach(a.add); a }
}

private[dbt] final case class DbtManifestResource(section: String, id: String, data: JsonObject)
private[dbt] final case class DbtManifest(schemaVersion: Int, projectName: Option[String], resources: Vector[DbtManifestResource]) {
  def in(sections: String*): Vector[DbtManifestResource] = resources.filter(r => sections.contains(r.section))
}

/** Local manifest access, implemented by metadata. Baseline paths must be checked by the caller before reading. */
private[dbt] trait DbtManifestReader {
  def parse(text: String, policy: DbtManifestPolicy): Either[String, DbtManifest]
  def read(root: DbtRoot, path: String, policy: DbtManifestPolicy): Either[String, DbtManifest]
}

private[dbt] object DbtManifestReader {
  def get(project: Project): DbtManifestReader = project.getService(classOf[DbtManifestReader])
}
