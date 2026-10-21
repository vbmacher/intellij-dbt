package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtNavigationReference}
import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtRoot, DbtSettingsState, DbtWorkspace}
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.json.JsonFileType
import com.intellij.model.psi.impl.TargetsKt
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.{FileEditor, FileEditorManager}
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.{TestDialog, TestDialogManager}
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiManager, PsiReference}
import com.intellij.testFramework.{PlatformTestUtil, ServiceContainerUtil}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert._

import java.awt.{Component, Container}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/**
 * Shared light-fixture support for dbt behavior tests: project files, workspace lookups, editor carets, Go to Declaration,
 * Undo, Swing trees, external fake executables and asynchronous waits. Expectations come from source locations.
 * Fixture projects can be reused between tests, so project and consumer settings are reset after every test.
 */
abstract class DbtTestCase extends BasePlatformTestCase {
  override protected def tearDown(): Unit = try {
    getProject.getService(classOf[DbtConsumersSettings]).loadState(new DbtConsumersState)
    DbtProjectSettings.get(getProject).loadState(new DbtSettingsState)
  } finally super.tearDown()

  protected def add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)

  protected def project(config: String = "", path: String = ""): PsiFile =
    add(path + "dbt_project.yml", "name: analytics\nconfig-version: 2\n" + config)

  /** A minimal project file containing only the project name. */
  protected def bareProject(config: String = "", path: String = ""): PsiFile = add(path + "dbt_project.yml", "name: analytics\n" + config)

  protected def workspace: DbtWorkspace = DbtWorkspace.get(getProject)

  /** The `analytics` project; suites with other or ambiguous project names override it. */
  protected def root: DbtRoot = workspace.roots.find(_.name == "analytics").get

  protected def projectFile(path: String, owner: DbtRoot = workspace.roots.head): PsiFile =
    PsiManager.getInstance(getProject).findFile(owner.directory.findFileByRelativePath(path))

  protected def configure(path: String, text: String): PsiFile = {
    val offset = text.indexOf("<caret>")
    assertTrue("The editor fixture must contain one caret", offset >= 0 && offset == text.lastIndexOf("<caret>"))
    val file = add(path, text.replace("<caret>", ""))
    open(file, offset)
    file
  }

  protected def open(file: PsiFile, offset: Int): Unit = {
    myFixture.configureFromExistingVirtualFile(file.getVirtualFile)
    myFixture.getEditor.getCaretModel.moveToOffset(offset)
  }

  protected def edit(file: PsiFile, text: String): Unit = {
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = {
        val documents = PsiDocumentManager.getInstance(getProject)
        documents.getDocument(file).setText(text)
        documents.commitAllDocuments()
      }
    })
  }

  /** dbt navigation references at the given occurrence of `text`, probed one character inside it. */
  protected def referencesAt(file: PsiFile, text: String, occurrence: Int = 0): Vector[DbtNavigationReference] = {
    val start = Iterator.iterate(file.getText.indexOf(text))(i => file.getText.indexOf(text, i + 1)).drop(occurrence).next()
    assertTrue(s"Missing text $text", start >= 0)
    DbtNavigation.at(file, start + math.min(1, text.length - 1))
  }

  protected def targetsAt(file: PsiFile, text: String, occurrence: Int = 0): Vector[PsiElement] =
    referencesAt(file, text, occurrence).flatMap(_.candidates).distinct

  /**
   * Runs `body` while `.sql` files use the JSON parser. An existing parser without dbt PSI references exercises the
   * fallback needed by SQL PSI without requiring a paid SQL plugin.
   */
  protected def withSqlParsedAsJson[A](body: => A): A = {
    val fileTypes = FileTypeManager.getInstance
    val original = fileTypes.getFileTypeByExtension("sql")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = fileTypes.associateExtension(JsonFileType.INSTANCE, "sql")
    })
    try body finally WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = {
        fileTypes.removeAssociatedExtension(JsonFileType.INSTANCE, "sql")
        fileTypes.associateExtension(original, "sql")
      }
    })
  }

  protected def reference: PsiReference = myFixture.getReferenceAtCaretPositionWithAssertion()

  protected def declarationTargets: Vector[PsiElement] =
    TargetsKt.targetDeclarationAndReferenceSymbols(myFixture.getFile, myFixture.getCaretOffset).getSecond.asScala
      .map(_.asInstanceOf[DbtPsiSymbol].element).toVector

  protected def assertTarget(file: PsiFile, offset: Int = 0): PsiElement = {
    val targets = declarationTargets
    assertEquals("Go to Declaration should find one target", 1, targets.size)
    val target = targets.head
    assertEquals("Declaration file", file, target.getContainingFile)
    assertEquals("Declaration offset", offset, target.getTextOffset)
    target
  }

  protected def assertNoTarget(): Unit = {
    assertEquals("Go to Declaration should not offer a target", Vector.empty, declarationTargets)
  }

  protected def selectedEditor: FileEditor = FileEditorManager.getInstance(getProject).getSelectedEditor

  /**
   * One native Undo in `editor` (`null` is the project-wide undo stack), accepting the platform's confirmation dialog.
   * With `orProject`, an editor without undoable changes falls back to the project-wide stack.
   */
  protected def undo(editor: FileEditor = selectedEditor, orProject: Boolean = false): Unit = {
    val manager = UndoManager.getInstance(getProject)
    val target = if (orProject && !manager.isUndoAvailable(editor)) null else editor
    assertTrue("One native Undo must restore the complete action", manager.isUndoAvailable(target))
    val dialog = TestDialogManager.setTestDialog(TestDialog.OK)
    try manager.undo(target) finally TestDialogManager.setTestDialog(dialog)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }

  protected def components(component: Component): Vector[Component] = Vector(component) ++ (component match {
    case container: Container => container.getComponents.toVector.flatMap(components)
    case _ => Vector.empty
  })

  protected def await(message: String, seconds: Int = 20)(condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(message, () => condition, seconds)

  /** A real directory outside the light fixture, deleted with the test. Prefixes with spaces exercise argument quoting. */
  protected def tempDirectory(prefix: String): Path = {
    val directory = Files.createTempDirectory(prefix)
    Disposer.register(getTestRootDisposable, () => FileUtil.delete(directory.toFile))
    directory
  }

  /** Writes a fake external tool (with its own shebang) and makes it executable. */
  protected def fakeTool(path: Path, script: String): Path = {
    Files.writeString(path, script)
    assertTrue(path.toFile.setExecutable(true))
    path
  }

  /** A managed runner disposed with `parent`; `service` makes it the project's [[DbtRunner]] for code under test. */
  protected def newRunner(service: Boolean = false, parent: Disposable = getTestRootDisposable): DbtRunner = {
    val runner = new DbtRunner(getProject)
    Disposer.register(parent, runner)
    if (service) ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, parent)
    runner
  }

  protected def runConfiguration(name: String = "dbt command"): DbtRunConfiguration = {
    val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val configuration = new DbtRunConfiguration(getProject, factory, name)
    configuration.root = workspace.roots.head.directory.getPath
    configuration
  }
}
