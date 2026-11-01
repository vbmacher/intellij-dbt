package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution._
import com.intellij.execution.actions.{ConfigurationContext, LazyRunConfigurationProducer}
import com.intellij.execution.configurations._
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.RunDialog
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.{ExecutionEnvironment, ExecutionEnvironmentBuilder, ProgramRunner, RunContentBuilder}
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType, ExecutionConsole}
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{ActionManager, AnActionEvent, CommonDataKeys, IdeActions}
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.{DumbAwareAction, Project}
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.{Disposer, Key, Ref}
import com.intellij.psi.PsiElement
import com.intellij.ui.components.{JBLabel, JBScrollPane, JBTextArea, JBTextField}
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.io.OutputStream
import javax.swing.{JCheckBox, JComboBox, JComponent, JPanel, JTabbedPane}
import org.jdom.Element
import scala.jdk.CollectionConverters._

final class DbtConfigurationType extends ConfigurationType {
  private val factory = new ConfigurationFactory(this) {
    override def getId: String = "Dbt"

    override def createTemplateConfiguration(project: Project): RunConfiguration = {
      val configuration = new DbtRunConfiguration(project, this, "dbt")
      configuration.action = "Run";
      configuration
    }
  }

  override def getId: String = "Dbt"

  override def getDisplayName: String = "dbt"

  override def getConfigurationTypeDescription: String = "Run dbt commands, previews, data and unit tests"

  override def getIcon: javax.swing.Icon = DbtIcons.Gutter

  override def getConfigurationFactories: Array[ConfigurationFactory] = Array(factory)
}

final class DbtRunConfiguration(project: Project, factory: ConfigurationFactory, name: String)
  extends RunConfigurationBase[Element](project, factory, name) {
  var root: String = ""
  var selectors: Vector[String] = Vector.empty
  var action: String = "Run"
  var exclude: Vector[String] = Vector.empty
  var fullRefresh: Boolean = false
  var exactTests: Boolean = false
  var namedSelector: String = ""
  var scope: String = ""
  var inlineSql: String = ""
  var target: String = ""
  var executable: String = ""
  var executableArguments: String = ""
  var profilesDirectory: String = ""
  private[ide] var cliSelection: Option[(Vector[String], Vector[String])] = None
  // Commands launched from context actions and failed-test reruns retain their captured execution context.
  private[ide] var selectionResolved: Option[Vector[String] => Unit] = None
  private[ide] var captured: Option[DbtCommand] = None
  private[ide] var completed: Option[DbtCommand => Unit] = None

  override def getConfigurationEditor: SettingsEditor[DbtRunConfiguration] = new DbtRunEditor(project)

  override def checkConfiguration(): Unit = {
    if (root.isEmpty) throw new RuntimeConfigurationError("Choose the owning dbt project directory.")
    if (DbtCommandAction.fromLabel(action).isEmpty) throw new RuntimeConfigurationError("Choose a dbt command.")
  }

  private[ide] def sourceKey(w: DbtWorkspace): Vector[String] =
    Vector(root, scope, inlineSql, action, selectors.mkString(" "), exclude.mkString(" "), namedSelector, target, executable,
      executableArguments, profilesDirectory, fullRefresh.toString, DbtProjectSettings.get(project).getModificationCount.toString) ++
      configuredRoot(w).toOption.toVector.flatMap(DbtArtifactContext.identity) ++
      w.files.filter(f => w.root(f).exists(r => !r.excluded(f.getVirtualFile))).sortBy(_.getVirtualFile.getPath)
        .flatMap(f => Vector(f.getVirtualFile.getPath, f.getModificationStamp.toString))

  private[ide] def configuredRoot(w: DbtWorkspace): Either[String, DbtRoot] =
    w.roots.find(_.directory.getPath == root).map(w.executionRoot).toRight("The owning dbt project is unavailable.").map { owner =>
      val options = owner.runOptions.copy()
      if (target.nonEmpty) options.target = target
      if (executable.nonEmpty) {
        options.executable = executable;
        options.executableArguments = executableArguments
      }
      if (profilesDirectory.nonEmpty) options.profilesDirectory = profilesDirectory
      options.selection = if (namedSelector.nonEmpty) "selector:" + namedSelector else selectors.mkString(" ")
      options.exclude = exclude.mkString(" ")
      owner.withRunOptions(options)
    }

  private[ide] def prepare(): Either[String, (DbtCommand, Vector[DbtTestDefinition])] = {
    val w = DbtWorkspace.get(project)
    for {
      owner <- configuredRoot(w)
      selected <- (if (scope.isEmpty) DbtRunSelection.all(w, owner.directory.getPath)
      else DbtRunSelection.available(w).find(s => s.path == scope && DbtRunSelection.owner(w, s).exists(r => w.executionRoot(r).directory == owner.directory)))
        .toRight("Choose an available model or directory in the owning dbt project.")
      definitions = DbtTestDiscovery.discover(w, Map(owner.directory.getPath -> owner.runOptions)).filter(_.root == owner.directory.getPath)
      base <- captured.map(Right(_)).getOrElse {
        val ids = cliSelection.filter(_._1 == sourceKey(w)).map(_._2)
        if (action == "Resolve Selection") DbtCommand.resolveSelection(w, owner, Some(selected))
        else if (exactTests) DbtCommand.create(w, selected, action, fullRefresh, Some(owner.runOptions))
        else if (selectors.nonEmpty || exclude.nonEmpty || namedSelector.nonEmpty)
          DbtCommand.selection(w, owner, action, fullRefresh, Some(selected), ids)
        else DbtCommand.create(w, selected, action, fullRefresh, Some(owner.runOptions))
      }
      preview <- if (inlineSql.isEmpty || captured.nonEmpty) Right(base) else for {
        _ <- Either.cond(action == "Preview" && !selected.directory, (), "Inline SQL requires Preview and one source model.")
        model <- w.model(selected.path).toRight("The preview source model is unavailable.")
        query <- DbtCteInspection.command(w, model, inlineSql)
      } yield base.copy(request = base.request.copy(scope = query.request.scope, debugSql = true), previewSource = query.previewSource)
      command <- if (!exactTests) Right(preview) else {
        val intended = definitions.filter(t => selectors.contains(t.selector))
        if (selectors.isEmpty) Left("Exact test identities unavailable. Generate Docs for this project and target, then select the tests again.")
        else if (selectors.exists(s => !intended.exists(_.selector == s))) Left("A selected test is no longer available. Select its declaration again.")
        else DbtTestDiscovery.select(preview, intended)
      }
    } yield command -> definitions
  }

  override def getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = new RunProfileState {
    override def execute(executor: Executor, runner: ProgramRunner[_]): ExecutionResult = {
      FileDocumentManager.getInstance.saveAllDocuments()
      if (Set("Test", "Build")(captured.map(_.action).getOrElse(action))) {
        val session = new DbtTestConsole(DbtRunConfiguration.this, executor)
        prepareAsync(session.view)(result => result match {
          case Right((command, definitions)) => session.start(command, definitions)
          case Left(problem) => session.fail(problem)
        })
        val result = new DefaultExecutionResult(session.view, session.process)
        result.setRestartActions(session.properties.createRerunFailedTestsAction(session.view))
        result
      } else {
        val session = new DbtCommandConsole(DbtRunConfiguration.this)
        prepareAsync(session)(result => result match {
          case Right((command, _)) => session.start(command)
          case Left(problem) => session.fail(problem)
        })
        new DefaultExecutionResult(session, session.process, session.console.createConsoleActions(): _*)
      }
    }
  }

  private def prepareAsync(parent: Disposable)(done: Either[String, (DbtCommand, Vector[DbtTestDefinition])] => Unit): Unit = {
    val snapshot = clone().asInstanceOf[DbtRunConfiguration]
    DbtUi.readLater(parent, smartIn = Some(project), committedIn = Some(project))(snapshot.prepare())(done)
  }

  override def writeExternal(element: Element): Unit = {
    super.writeExternal(element)
    Vector("dbtRoot" -> root, "scope" -> scope, "target" -> target, "executable" -> executable, "arguments" -> executableArguments,
      "profilesDirectory" -> profilesDirectory, "command" -> action, "exactTests" -> exactTests.toString,
      "fullRefresh" -> fullRefresh.toString, "namedSelector" -> namedSelector)
      .foreach { case (key, value) => element.setAttribute(key, value) }
    element.removeChildren("inlineSql")
    if (inlineSql.nonEmpty) element.addContent(new Element("inlineSql").setText(inlineSql))
    Vector("exclude" -> exclude, "select" -> selectors).foreach { case (name, terms) =>
      element.removeChildren(name)
      terms.foreach(term => element.addContent(new Element(name).setAttribute("term", term)))
    }
  }

  override def readExternal(element: Element): Unit = {
    super.readExternal(element)

    def attribute(name: String, default: String = ""): String = Option(element.getAttributeValue(name)).getOrElse(default)

    def terms(name: String): Vector[String] = element.getChildren(name).asScala.flatMap(e => Option(e.getAttributeValue("term"))).toVector

    root = attribute("dbtRoot");
    scope = attribute("scope")
    inlineSql = Option(element.getChildText("inlineSql")).getOrElse("")
    target = attribute("target");
    executable = attribute("executable");
    executableArguments = attribute("arguments")
    profilesDirectory = attribute("profilesDirectory")
    cliSelection = None;
    selectionResolved = None
    action = attribute("command", "Run")
    exactTests = attribute("exactTests") == "true"
    fullRefresh = attribute("fullRefresh") == "true"
    namedSelector = attribute("namedSelector")
    exclude = terms("exclude");
    selectors = terms("select")
    captured = None
  }
}

private[ide] object DbtRunConfiguration {
  private[ide] def factory: ConfigurationFactory =
    ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head

  private[ide] def terms(text: String): Vector[String] = text.trim.split("\\s+").filter(_.nonEmpty).toVector

  private def create(project: Project, action: String, root: String): (RunnerAndConfigurationSettings, DbtRunConfiguration) = {
    val settings = RunManager.getInstance(project).createConfiguration("dbt " + action.toLowerCase + " · " + DbtUi.name(root), factory)
    settings -> settings.getConfiguration.asInstanceOf[DbtRunConfiguration]
  }

  def openEvidence(project: Project, key: String): Unit = {
    val parts = key.split("\\|", 3)
    if (parts.length >= 2) DbtUi.readLater(project) {
      val w = DbtWorkspace.get(project)
      w.roots.find(_.directory.getPath == parts(0)).map(w.executionRoot).map { owner =>
        val tests = DbtTestDiscovery.discover(w).filter(t => t.root == owner.directory.getPath &&
          (t.id == parts(1) || t.dependencies.contains(parts(1))))
        val ids = tests.map(_.id).toSet + parts(1)
        val runs = project.getService(classOf[DbtExecutionEvidence]).runs(owner)
        val run = runs.find(_.results.exists(r => ids(r.id))).orElse(runs.headOption)
          .getOrElse(DbtExecutionRun(Vector.empty, "", "", "", "", Vector.empty, "No execution evidence available"))
        (owner.directory.getPath, tests, run.copy(results = run.results.filter(r => ids(r.id))))
      }
    }(_.foreach { case (root, tests, execution) =>
      val configuration = new DbtRunConfiguration(project, factory, "dbt recorded results")
      configuration.root = root;
      configuration.action = "Test"
      configuration.selectors = tests.map(_.selector).distinct
      configuration.exactTests = true
      val executor = DefaultRunExecutor.getRunExecutorInstance
      val session = new DbtTestConsole(configuration, executor)
      val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
      new RunContentBuilder(new DefaultExecutionResult(session.view, session.process), environment).showRunContent(null)
      session.showEvidence(execution, tests)
    })
  }

  def edit(project: Project, root: String, action: String, selection: String = "", excluded: String = ""): Unit = {
    val (settings, configuration) = create(project, action, root)
    configuration.root = root;
    configuration.action = action
    configuration.selectors = terms(selection)
    configuration.exclude = terms(excluded)
    if (RunDialog.editConfiguration(project, settings, "dbt run configuration")) {
      val manager = RunManager.getInstance(project)
      manager.addConfiguration(settings);
      manager.setSelectedConfiguration(settings)
    }
  }

  /** Selection review must complete while its modal configuration editor is open. */
  private[ide] def resolveSelection(configuration: DbtRunConfiguration): Unit = {
    val executor = DefaultRunExecutor.getRunExecutorInstance
    val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
    environment.assignNewExecutionId()
    val result = configuration.getState(executor, environment).execute(executor, environment.getRunner)
    new RunContentBuilder(result, environment).showRunContent(null)
    ()
  }

  def launch(project: Project, command: DbtCommand, completed: Option[DbtCommand => Unit] = None): Unit = {
    val (settings, configuration) = create(project, command.action, command.root)
    configuration.root = command.root
    configuration.captured = Some(command)
    configuration.completed = completed
    configuration.target = command.target
    configuration.executable = command.prefix.headOption.getOrElse("")
    configuration.executableArguments = ParametersListUtil.join(command.prefix.drop(1).asJava)
    configuration.profilesDirectory = command.environment.getOrElse("DBT_PROFILES_DIR", "")
    configuration.scope = command.previewSource.map(_.path).getOrElse("")
    if (command.action == "Preview" && configuration.scope.isEmpty) {
      configuration.scope = DbtUi.read {
        val w = DbtWorkspace.get(project)
        DbtRunSelection.available(w).find(s => !s.directory && DbtRunSelection.selectors(w, s).exists(term => command.request.scope match {
          case DbtCommandScope.Selected(terms) => terms.contains(term)
          case _ => false
        })).map(_.path).getOrElse("")
      }
    }
    configuration.action = command.action
    configuration.fullRefresh = command.request.fullRefresh
    configuration.exclude = command.request.exclude
    command.request.scope match {
      case DbtCommandScope.Selected(terms) => configuration.selectors = terms
      case DbtCommandScope.Named(name) => configuration.namedSelector = name
      case DbtCommandScope.Inline(sql) => configuration.inlineSql = sql
      case _ => ()
    }
    val manager = RunManager.getInstance(project)
    manager.setTemporaryConfiguration(settings)
    manager.setSelectedConfiguration(settings)
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance)
  }
}

final class DbtRunProducer extends LazyRunConfigurationProducer[DbtRunConfiguration] {
  override def getConfigurationFactory: ConfigurationFactory = DbtRunConfiguration.factory

  override def setupConfigurationFromContext(configuration: DbtRunConfiguration, context: ConfigurationContext, source: Ref[PsiElement]): Boolean = {
    val tests = DbtTestRunLineMarker.testsAt(source.get()).filter(_.nonEmpty)
    tests match {
      case Some(values) if values.forall(_.selector.nonEmpty) =>
        configuration.root = values.head.root;
        configuration.action = "Test"
        configuration.selectors = values.map(_.selector).distinct;
        configuration.exactTests = true
        configuration.setName(if (values.size == 1) values.head.name else "dbt tests")
        true
      case Some(_) => false
      case None =>
        val w = DbtWorkspace.get(configuration.getProject)
        val file = Option(context.getDataContext.getData(CommonDataKeys.VIRTUAL_FILE))
          .orElse(Option(source.get()).flatMap(e => Option(e.getContainingFile)).map(_.getVirtualFile))
        val selected = file.flatMap { f =>
          if (f.isDirectory) DbtRunSelection.available(w).find(_.path == f.getPath)
            .flatMap(s => DbtRunSelection.owner(w, s)).map(_ -> f.getPath)
          else w.model(f.getPath).filter(_.isEnabled).flatMap(w.owner).filterNot(_.excluded(f)).map(_ -> f.getPath)
        }
        selected.exists { case (owner, path) =>
          val root = w.executionRoot(owner)
          configuration.root = root.directory.getPath;
          configuration.scope = path;
          configuration.action = "Run"
          configuration.setName("dbt run · " + DbtUi.name(path));
          true
        }
    }
  }

  override def isConfigurationFromContext(configuration: DbtRunConfiguration, context: ConfigurationContext): Boolean = {
    val candidate = new DbtRunConfiguration(configuration.getProject, getConfigurationFactory, "")
    val source = new Ref[PsiElement](context.getPsiLocation)
    setupConfigurationFromContext(candidate, context, source) && candidate.root == configuration.root &&
      candidate.scope == configuration.scope && candidate.action == configuration.action && candidate.selectors == configuration.selectors
  }
}

final class DbtRunEditor(project: Project) extends SettingsEditor[DbtRunConfiguration] {
  private val root = new TextFieldWithBrowseButton
  private val scope = new TextFieldWithBrowseButton
  private val inline = new JBTextArea(4, 50)
  private val target = new JBTextField
  private val executable = new TextFieldWithBrowseButton
  private val arguments = new JBTextField
  private val profiles = new TextFieldWithBrowseButton
  private val selectors = new JBTextField
  private val excluded = new JBTextField
  private val named = new JBTextField
  private val action = new JComboBox[String]((DbtCommand.Actions :+ "Resolve Selection").toArray)
  private val refresh = new JCheckBox("Full Refresh (Run / Build)")
  private val effective = new JBLabel
  private val inlineLabel = new JBLabel("Inline SQL:")
  private val inlinePane = new JBScrollPane(inline)
  private val overrides = new JPanel(new BorderLayout)
  private val showOverrides = new JCheckBox("Show execution overrides")
  overrides.setVisible(false)
  showOverrides.addActionListener(_ => overrides.setVisible(showOverrides.isSelected))
  private var configuration: DbtRunConfiguration = _
  private var resolved = Option.empty[(Vector[String], Vector[String])]
  private var disposed = false
  private def updateControls(): Unit = {
    val command = action.getSelectedItem.toString
    val whole = Set("Generate Docs", "Source Freshness")(command)
    val preview = command == "Preview"
    val hasNamed = named.getText.trim.nonEmpty
    val hasSelection = selectors.getText.trim.nonEmpty
    scope.setEnabled(!whole && (preview || !hasNamed && !hasSelection))
    selectors.setEnabled(!whole && !preview && !hasNamed)
    named.setEnabled(!whole && !preview)
    excluded.setEnabled(!whole && !preview)
    refresh.setVisible(Set("Run", "Build")(command))
    inlineLabel.setVisible(preview)
    inlinePane.setVisible(preview)
    arguments.setEnabled(executable.getText.trim.nonEmpty)
    val selected = if (whole) "Whole execution project"
    else if (!preview && hasNamed) "Named selector: " + named.getText.trim
    else if (!preview && hasSelection) "Selection: " + selectors.getText.trim
    else Option(scope.getText.trim).filter(_.nonEmpty).getOrElse("Whole execution project")
    effective.setText("Effective scope: " + selected +
      (if (!whole && !preview && excluded.getText.trim.nonEmpty) " · Exclude: " + excluded.getText.trim else ""))
    effective.setToolTipText(effective.getText)
  }
  action.addActionListener(_ => updateControls())
  Vector(selectors, excluded, named, scope.getTextField, executable.getTextField).foreach(_.getDocument.addDocumentListener(
    new com.intellij.ui.DocumentAdapter {
      override def textChanged(event: javax.swing.event.DocumentEvent): Unit = updateControls()
    }))
  root.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Choose owning dbt project"))
  scope.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFileOrDir().withTitle("Choose dbt model or directory"))
  executable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Choose dbt executable or wrapper"))
  profiles.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Choose profiles directory"))
  Vector(selectors, excluded).foreach { field =>
    field.setToolTipText("dbt selector expression. Press Ctrl+Space for names, tags, packages and named selectors.")
    new DumbAwareAction("Complete dbt selection") {
      override def actionPerformed(event: AnActionEvent): Unit = {
        val text = field.getText;
        val caret = field.getCaretPosition
        val source = draft()
        DbtUi.readLater(DbtRunEditor.this) {
          val w = DbtWorkspace.get(project)
          source.configuredRoot(w).map(r => DbtSelectionReview.complete(text, caret, DbtSelectionReview.completions(w, r)))
            .getOrElse(Vector.empty)
        } { choices =>
          if (field.getText == text && choices.nonEmpty) JBPopupFactory.getInstance
            .createPopupChooserBuilder(choices.map(_._1).asJava).setTitle("dbt selectors")
            .setItemChosenCallback((chosen: String) => choices.find(_._1 == chosen).foreach { case (_, value, next) =>
              field.setText(value); field.setCaretPosition(next)
            }).createPopup().showUnderneathOf(field)
        }
      }
    }.registerCustomShortcutSet(ActionManager.getInstance.getAction(IdeActions.ACTION_CODE_COMPLETION).getShortcutSet, field, this)
  }

  private def draft(): DbtRunConfiguration = {
    val copy = configuration.clone().asInstanceOf[DbtRunConfiguration]
    applyEditorTo(copy);
    copy
  }

  override def createEditor(): JComponent = {
    scope.getTextField.asInstanceOf[JBTextField].getEmptyText.setText("All models; or an absolute model/directory path")
    target.getEmptyText.setText("Project setting / profile default")
    executable.getTextField.asInstanceOf[JBTextField].getEmptyText.setText("Use project executable and arguments")
    arguments.setToolTipText("Prefix arguments for the overridden executable, e.g. run dbt for uv. Shell expressions are not evaluated.")
    val review = DbtUi.button("Review selection")(DbtSelectionResults.review(draft()))
    val resolve = DbtUi.button("Resolve with dbt ls") {
      val source = draft()
      val key = DbtUi.read(source.sourceKey(DbtWorkspace.get(project)))
      val resolving = source.clone().asInstanceOf[DbtRunConfiguration]
      resolving.action = "Resolve Selection"
      resolving.setName("dbt ls · " + DbtUi.name(source.root))
      resolving.selectionResolved = Some(ids => {
        if (!disposed) {
          val current = draft()
          val now = DbtUi.read(current.sourceKey(DbtWorkspace.get(project)))
          if (key == now) resolved = Some(key -> ids)
        }
      })
      DbtRunConfiguration.resolveSelection(resolving)
    }
    val compare = DbtUi.button("Compare baseline")(DbtStateComparison.show(draft()))
    val buttons = DbtUi.buttons(review, resolve, compare)
    val form = FormBuilder.createFormBuilder()
      .addLabeledComponent("Owning dbt root:", root)
      .addLabeledComponent("Scope:", scope)
      .addLabeledComponent("Command:", action)
      .addLabeledComponent("Target:", target)
      .addLabeledComponent("Selection:", selectors)
      .addLabeledComponent("Exclude:", excluded)
      .addLabeledComponent("Named selector (overrides Selection):", named)
      .addComponent(effective)
      .addComponent(refresh).addComponent(buttons)
      .addLabeledComponent(inlineLabel, inlinePane)
      .addComponent(showOverrides)
      .getPanel
    overrides.add(FormBuilder.createFormBuilder()
      .addLabeledComponent("Executable override:", executable)
      .addLabeledComponent("Prefix arguments:", arguments)
      .addLabeledComponent("Profiles directory override:", profiles)
      .addComponent(new JBLabel("Other execution defaults: Settings → dbt Workspace"))
      .getPanel, BorderLayout.CENTER)
    val panel = new JPanel(new BorderLayout);
    val content = new JPanel(new BorderLayout)
    content.add(form, BorderLayout.NORTH)
    content.add(overrides, BorderLayout.CENTER)
    panel.add(content, BorderLayout.NORTH)
    updateControls()
    new JBScrollPane(panel)
  }

  override def resetEditorFrom(value: DbtRunConfiguration): Unit = {
    configuration = value;
    resolved = value.cliSelection
    root.setText(value.root);
    scope.setText(value.scope);
    target.setText(value.target);
    inline.setText(value.inlineSql)
    executable.setText(value.executable);
    arguments.setText(value.executableArguments);
    profiles.setText(value.profilesDirectory)
    selectors.setText(value.selectors.mkString(" "));
    excluded.setText(value.exclude.mkString(" "));
    named.setText(value.namedSelector)
    action.setSelectedItem(value.action);
    refresh.setSelected(value.fullRefresh)
    showOverrides.setSelected(value.executable.nonEmpty || value.profilesDirectory.nonEmpty)
    overrides.setVisible(showOverrides.isSelected)
    updateControls()
  }

  override def applyEditorTo(value: DbtRunConfiguration): Unit = {
    value.root = root.getText.trim;
    value.scope = scope.getText.trim;
    value.target = target.getText.trim;
    value.inlineSql = inline.getText
    value.executable = executable.getText.trim;
    value.executableArguments = arguments.getText;
    value.profilesDirectory = profiles.getText.trim
    val terms = DbtRunConfiguration.terms(selectors.getText)
    if (terms != value.selectors || named.getText.trim.nonEmpty) value.exactTests = false
    value.selectors = terms;
    value.exclude = DbtRunConfiguration.terms(excluded.getText)
    value.namedSelector = named.getText.trim;
    value.action = action.getSelectedItem.toString
    val whole = Set("Generate Docs", "Source Freshness")(value.action)
    if (whole) value.scope = ""
    if (whole || value.action == "Preview") {
      value.exactTests = false
      value.selectors = Vector.empty
      value.exclude = Vector.empty
      value.namedSelector = ""
    } else if (value.namedSelector.nonEmpty) value.selectors = Vector.empty
    if (!whole && value.action != "Preview" && (value.selectors.nonEmpty || value.namedSelector.nonEmpty)) value.scope = ""
    if (value.action != "Preview") value.inlineSql = ""
    value.fullRefresh = refresh.isSelected && Set("Run", "Build")(value.action);
    value.captured = None;
    value.completed = None;
    value.cliSelection = resolved
  }

  override def disposeEditor(): Unit = {
    disposed = true
    Vector(root, scope, executable, profiles).foreach(_.dispose());
    super.disposeEditor()
  }
}

/**
 * Run tool window handle for a command executed by the shared [[DbtRunner]]. Stop calls `stop`, which either cancels
 * the runner (whose completion event then completes this handle) or completes it directly, conventionally with 130.
 */
private[ide] final class DbtManagedProcess(stop: DbtManagedProcess => Unit) extends ProcessHandler {
  def complete(code: Int): Unit = if (!isProcessTerminated) notifyProcessTerminated(code)

  val output: (String, Key[_]) => Unit = (text, kind) => notifyTextAvailable(text, kind)

  override def getProcessInput: OutputStream = null

  override def detachIsDefault: Boolean = false

  override protected def destroyProcessImpl(): Unit = stop(this)

  override protected def detachProcessImpl(): Unit = destroyProcessImpl()
}

/** One native Run session owns its console, results and cancellation. */
private[ide] final class DbtCommandConsole(configuration: DbtRunConfiguration) extends ExecutionConsole {
  private val project = configuration.getProject
  private val snapshot = configuration.clone().asInstanceOf[DbtRunConfiguration]
  private val runner = project.getService(classOf[DbtRunner])
  val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole
  private val tabs = new JTabbedPane
  private val panel = new JPanel(new BorderLayout)
  private var active = Option.empty[DbtCommand]
  private var canceled = false
  private val grid = new DbtResultGrid(project, limit => active.foreach { previous =>
    DbtUi.readLater(this) {
      val fresh = snapshot.clone().asInstanceOf[DbtRunConfiguration];
      fresh.captured = None
      fresh.prepare().map(_._1).flatMap { command =>
        val same = command.artifactContext == previous.artifactContext && command.environment == previous.environment &&
          command.sourceSql == previous.sourceSql && command.previewSource.map(_.text) == previous.previewSource.map(_.text) &&
          command.copy(request = command.request.copy(limit = previous.request.limit)).arguments == previous.arguments
        Either.cond(same, command.copy(request = command.request.copy(limit = limit),
          previewSource = previous.previewSource.orElse(command.previewSource)),
          "Source or execution settings changed. Start a fresh preview before rerunning with a limit.")
      }
    } {
      case Right(command) => DbtRunConfiguration.launch(project, command)
      case Left(error) => console.print(error + "\n", ConsoleViewContentType.ERROR_OUTPUT)
    }
  })
  private val evidence = new DbtPreviewEvidencePanel(project)
  val process = new DbtManagedProcess(p => {
    canceled = true;
    if (active.nonEmpty) runner.stop() else p.complete(130)
  })
  tabs.addTab("Console", console.getComponent)
  panel.add(tabs, BorderLayout.CENTER)
  console.attachToProcess(process)
  Disposer.register(this, console)
  process.startNotify()
  runner.listen(this, event => if (active.exists(c => event.command.exists(_ eq c))) {
    grid.setRunning(event.running)
    if (!event.running) {
      console.print("\n" + event.message + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
      event.execution.foreach { run =>
        console.print(s"Target: ${run.target} · Invocation: ${run.invocation} · ${run.timestamp}\n${run.artifact}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        if (run.problem.nonEmpty) console.print(run.problem + "\n", ConsoleViewContentType.ERROR_OUTPUT)
      }
      evidence.showEvidence(event.previewEvidence)
      event.result.foreach { result =>
        grid.showResult(result)
        val rows = new JPanel(new BorderLayout)
        rows.add(grid, BorderLayout.CENTER);
        rows.add(evidence, BorderLayout.SOUTH)
        tabs.addTab("Rows", rows);
        tabs.setSelectedComponent(rows)
      }
      if (event.result.isEmpty && event.previewEvidence.nonEmpty) panel.add(evidence, BorderLayout.SOUTH)
      event.selectionOutput.foreach(raw => DbtSelectionResults.showCli(configuration, raw))
      process.complete(event.exitCode.getOrElse(if (canceled) 130 else 1))
      if (!canceled && event.exitCode.contains(0)) active.foreach(command => snapshot.completed.foreach(_(command)))
      panel.revalidate()
    }
  })

  def start(command: DbtCommand): Unit = if (!canceled && !process.isProcessTerminated) {
    active = Some(command)
    console.print(command.commandLine + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    if (!runner.start(command, process.output)) {
      active = None;
      fail("Another dbt command is running. Stop it in Run or Debug first.")
    }
  }

  def fail(problem: String): Unit = {
    console.print(problem + "\n", ConsoleViewContentType.ERROR_OUTPUT);
    process.complete(1)
  }

  override def getComponent: JComponent = panel

  override def getPreferredFocusableComponent: JComponent = console.getPreferredFocusableComponent

  override def dispose(): Unit = if (!process.isProcessTerminated) process.destroyProcess()
}
