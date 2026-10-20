package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution._
import com.intellij.execution.actions.{ConfigurationContext, LazyRunConfigurationProducer}
import com.intellij.execution.configurations._
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.{ExecutionEnvironment, ProgramRunner}
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.ui.components.{JBLabel, JBTextField}
import com.intellij.util.concurrency.AppExecutorUtil
import org.jdom.Element
import javax.swing.{JComponent, JPanel, JComboBox, JCheckBox}
import java.awt.BorderLayout
import java.util.concurrent.Callable
import scala.jdk.CollectionConverters._

final class DbtConfigurationType extends ConfigurationType {
  private val factory = new ConfigurationFactory(this) {
    override def getId: String = "Dbt"
    override def createTemplateConfiguration(project: Project): RunConfiguration = {
      val configuration = new DbtRunConfiguration(project, this, "dbt")
      configuration.action = "Run"; configuration
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
      if (executable.nonEmpty) { options.executable = executable; options.executableArguments = executableArguments }
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
        model <- w.symbols.find(s => s.isCode && s.kind == "model" && s.file.getVirtualFile.getPath == selected.path).toRight("The preview source model is unavailable.")
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
        new DefaultExecutionResult(session.view, session.process)
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
  private def prepareAsync(parent: com.intellij.openapi.Disposable)(done: Either[String, (DbtCommand, Vector[DbtTestDefinition])] => Unit): Unit = {
    val snapshot = clone().asInstanceOf[DbtRunConfiguration]
    ReadAction.nonBlocking(new Callable[Either[String, (DbtCommand, Vector[DbtTestDefinition])]] {
      override def call(): Either[String, (DbtCommand, Vector[DbtTestDefinition])] = snapshot.prepare()
    }).inSmartMode(project).withDocumentsCommitted(project).expireWith(parent)
      .finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, (DbtCommand, Vector[DbtTestDefinition])]) => done(result))
      .submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
  override def writeExternal(element: Element): Unit = {
    super.writeExternal(element)
    element.setAttribute("dbtRoot", root)
    Vector("scope" -> scope, "target" -> target, "executable" -> executable, "arguments" -> executableArguments,
      "profilesDirectory" -> profilesDirectory).foreach { case (key, value) => element.setAttribute(key, value) }
    element.removeChildren("inlineSql")
    if (inlineSql.nonEmpty) element.addContent(new Element("inlineSql").setText(inlineSql))
    element.setAttribute("command", action)
    element.setAttribute("exactTests", exactTests.toString)
    element.setAttribute("fullRefresh", fullRefresh.toString)
    element.setAttribute("namedSelector", namedSelector)
    element.removeChildren("exclude")
    exclude.foreach(s => element.addContent(new Element("exclude").setAttribute("term", s)))
    element.removeChildren("select")
    selectors.foreach(s => element.addContent(new Element("select").setAttribute("term", s)))
  }
  override def readExternal(element: Element): Unit = {
    super.readExternal(element)
    root = Option(element.getAttributeValue("dbtRoot")).getOrElse("")
    scope = Option(element.getAttributeValue("scope")).getOrElse("")
    inlineSql = Option(element.getChildText("inlineSql")).getOrElse("")
    target = Option(element.getAttributeValue("target")).getOrElse("")
    executable = Option(element.getAttributeValue("executable")).getOrElse("")
    executableArguments = Option(element.getAttributeValue("arguments")).getOrElse("")
    profilesDirectory = Option(element.getAttributeValue("profilesDirectory")).getOrElse("")
    cliSelection = None; selectionResolved = None
    action = Option(element.getAttributeValue("command")).getOrElse("Run")
    exactTests = element.getAttributeValue("exactTests") == "true"
    fullRefresh = element.getAttributeValue("fullRefresh") == "true"
    namedSelector = Option(element.getAttributeValue("namedSelector")).getOrElse("")
    exclude = element.getChildren("exclude").asScala.flatMap(e => Option(e.getAttributeValue("term"))).toVector
    selectors = element.getChildren("select").asScala.flatMap(e => Option(e.getAttributeValue("term"))).toVector
    captured = None
  }
}

private[ide] object DbtRunConfiguration {
  def openEvidence(project: Project, key: String): Unit = {
    val parts = key.split("\\|", 3)
    if (parts.length >= 2) ReadAction.nonBlocking(new Callable[Option[(String, Vector[DbtTestDefinition], DbtExecutionRun)]] {
      override def call(): Option[(String, Vector[DbtTestDefinition], DbtExecutionRun)] = {
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
      }
    }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), result => result.foreach { case (root, tests, execution) =>
      val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
      val configuration = new DbtRunConfiguration(project, factory, "dbt recorded results")
      configuration.root = root; configuration.action = "Test"
      configuration.selectors = tests.map(_.selector).distinct
      configuration.exactTests = true
      val executor = DefaultRunExecutor.getRunExecutorInstance
      val session = new DbtTestConsole(configuration, executor)
      val environment = com.intellij.execution.runners.ExecutionEnvironmentBuilder.create(executor, configuration).build()
      new com.intellij.execution.runners.RunContentBuilder(new DefaultExecutionResult(session.view, session.process), environment).showRunContent(null)
      session.showEvidence(execution, tests)
    }).submit(AppExecutorUtil.getAppExecutorService)
    ()
  }

  def edit(project: Project, root: String, action: String, selection: String = "", excluded: String = ""): Unit = {
    val manager = RunManager.getInstance(project)
    val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val settings = manager.createConfiguration("dbt " + action.toLowerCase + " · " + DbtUi.name(root), factory)
    val configuration = settings.getConfiguration.asInstanceOf[DbtRunConfiguration]
    configuration.root = root; configuration.action = action
    configuration.selectors = selection.trim.split("\\s+").filter(_.nonEmpty).toVector
    configuration.exclude = excluded.trim.split("\\s+").filter(_.nonEmpty).toVector
    if (com.intellij.execution.impl.RunDialog.editConfiguration(project, settings, "dbt run configuration")) {
      manager.addConfiguration(settings); manager.setSelectedConfiguration(settings)
    }
  }

  def launchConfiguration(project: Project, configuration: DbtRunConfiguration): Unit = {
    val settings = RunManager.getInstance(project).createConfiguration(configuration, configuration.getFactory)
    RunManager.getInstance(project).setTemporaryConfiguration(settings)
    RunManager.getInstance(project).setSelectedConfiguration(settings)
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance)
  }

  def launch(project: Project, command: DbtCommand): Unit = {
    val manager = RunManager.getInstance(project)
    val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val settings = manager.createConfiguration("dbt " + command.action.toLowerCase + " · " + DbtUi.name(command.root), factory)
    val configuration = settings.getConfiguration.asInstanceOf[DbtRunConfiguration]
    configuration.root = command.root
    configuration.captured = Some(command)
    configuration.target = command.target
    configuration.executable = command.prefix.headOption.getOrElse("")
    configuration.executableArguments = com.intellij.util.execution.ParametersListUtil.join(command.prefix.drop(1).asJava)
    configuration.profilesDirectory = command.environment.getOrElse("DBT_PROFILES_DIR", "")
    configuration.scope = command.previewSource.map(_.path).getOrElse("")
    if (command.action == "Preview" && configuration.scope.isEmpty) {
      configuration.scope = ReadAction.computeBlocking[String, RuntimeException](() => {
        val w = DbtWorkspace.get(project)
        DbtRunSelection.available(w).find(s => !s.directory && DbtRunSelection.selectors(w, s).exists(term => command.request.scope match {
          case DbtCommandScope.Selected(terms) => terms.contains(term)
          case _ => false
        })).map(_.path).getOrElse("")
      })
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
    manager.setTemporaryConfiguration(settings)
    manager.setSelectedConfiguration(settings)
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance)
  }
}

final class DbtRunProducer extends LazyRunConfigurationProducer[DbtRunConfiguration] {
  override def getConfigurationFactory: ConfigurationFactory =
    ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
  override def setupConfigurationFromContext(configuration: DbtRunConfiguration, context: ConfigurationContext, source: Ref[PsiElement]): Boolean = {
    val tests = DbtTestRunLineMarker.testsAt(source.get()).filter(_.nonEmpty)
    tests match {
      case Some(values) if values.forall(_.selector.nonEmpty) =>
        configuration.root = values.head.root; configuration.action = "Test"
        configuration.selectors = values.map(_.selector).distinct; configuration.exactTests = true
        configuration.setName(if (values.size == 1) values.head.name else "dbt tests")
        true
      case Some(_) => false
      case None =>
        val w = DbtWorkspace.get(configuration.getProject)
        val file = Option(context.getDataContext.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE))
          .orElse(Option(source.get()).flatMap(e => Option(e.getContainingFile)).map(_.getVirtualFile))
        file.flatMap(f => DbtRunSelection.available(w).find(_.path == f.getPath)).flatMap(selected =>
          DbtRunSelection.owner(w, selected).map(w.executionRoot).map(_ -> selected)).exists { case (root, selected) =>
          configuration.root = root.directory.getPath; configuration.scope = selected.path; configuration.action = "Run"
          configuration.setName("dbt run · " + DbtUi.name(selected.path)); true
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
  private val inline = new com.intellij.ui.components.JBTextArea(4, 50)
  private val target = new JBTextField
  private val executable = new TextFieldWithBrowseButton
  private val arguments = new JBTextField
  private val profiles = new TextFieldWithBrowseButton
  private val selectors = new JBTextField
  private val excluded = new JBTextField
  private val named = new JBTextField
  private val action = new JComboBox[String]((DbtCommand.Actions :+ "Resolve Selection").toArray)
  private val refresh = new JCheckBox("Full Refresh (Run / Build)")
  private var configuration: DbtRunConfiguration = _
  private var resolved = Option.empty[(Vector[String], Vector[String])]
  root.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Choose owning dbt project"))
  scope.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFileOrDir().withTitle("Choose dbt model or directory"))
  executable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Choose dbt executable or wrapper"))
  profiles.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Choose profiles directory"))
  Vector(selectors, excluded).foreach { field =>
    field.setToolTipText("dbt selector expression. Press Ctrl+Space for names, tags, packages and named selectors.")
    new com.intellij.openapi.project.DumbAwareAction("Complete dbt selection") {
      override def actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent): Unit = {
        val text = field.getText; val caret = field.getCaretPosition
        val source = draft()
        ReadAction.nonBlocking(new Callable[Vector[(String, String, Int)]] {
          override def call(): Vector[(String, String, Int)] = {
            val w = DbtWorkspace.get(project)
            source.configuredRoot(w).map(r => DbtSelectionReview.complete(text, caret, DbtSelectionReview.completions(w, r)))
              .getOrElse(Vector.empty)
          }
        }).expireWith(DbtRunEditor.this).finishOnUiThread(ModalityState.defaultModalityState(), choices => {
          if (field.getText == text && choices.nonEmpty) com.intellij.openapi.ui.popup.JBPopupFactory.getInstance
            .createPopupChooserBuilder(choices.map(_._1).asJava).setTitle("dbt selectors")
            .setItemChosenCallback((chosen: String) => choices.find(_._1 == chosen).foreach { case (_, value, next) =>
              field.setText(value); field.setCaretPosition(next)
            }).createPopup().showUnderneathOf(field)
        }).submit(AppExecutorUtil.getAppExecutorService)
        ()
      }
    }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.ActionManager.getInstance
      .getAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_CODE_COMPLETION).getShortcutSet, field, this)
  }
  private def draft(): DbtRunConfiguration = {
    val copy = configuration.clone().asInstanceOf[DbtRunConfiguration]
    applyEditorTo(copy); copy
  }
  override def createEditor(): JComponent = {
    scope.getTextField.asInstanceOf[JBTextField].getEmptyText.setText("All models; or an absolute model/directory path")
    target.getEmptyText.setText("Project setting / profile default")
    executable.getTextField.asInstanceOf[JBTextField].getEmptyText.setText("Use project executable and arguments")
    arguments.setToolTipText("Prefix arguments for the overridden executable, e.g. run dbt for uv. Shell expressions are not evaluated.")
    val review = new javax.swing.JButton("Review selection")
    review.addActionListener(_ => DbtSelectionResults.review(draft()))
    val resolve = new javax.swing.JButton("Resolve with dbt ls")
    resolve.addActionListener(_ => {
      val source = draft()
      val key = ReadAction.computeBlocking[Vector[String], RuntimeException](() => source.sourceKey(DbtWorkspace.get(project)))
      val resolving = source.clone().asInstanceOf[DbtRunConfiguration]
      resolving.action = "Resolve Selection"
      resolving.setName("dbt ls · " + DbtUi.name(source.root))
      resolving.selectionResolved = Some(ids => {
        val current = draft()
        val now = ReadAction.computeBlocking[Vector[String], RuntimeException](() => current.sourceKey(DbtWorkspace.get(project)))
        if (!com.intellij.openapi.util.Disposer.isDisposed(DbtRunEditor.this) && key == now) resolved = Some(key -> ids)
      })
      val manager = RunManager.getInstance(project)
      val selected = manager.getSelectedConfiguration
      DbtRunConfiguration.launchConfiguration(project, resolving)
      if (selected != null) manager.setSelectedConfiguration(selected)
    })
    val compare = new javax.swing.JButton("Compare baseline")
    compare.addActionListener(_ => DbtStateComparison.show(draft()))
    val buttons = new JPanel; buttons.add(review); buttons.add(resolve); buttons.add(compare)
    val form = com.intellij.util.ui.FormBuilder.createFormBuilder()
      .addLabeledComponent("Owning dbt root:", root)
      .addLabeledComponent("Scope:", scope)
      .addLabeledComponent("Command:", action)
      .addLabeledComponent("Target:", target)
      .addLabeledComponent("Selection:", selectors)
      .addLabeledComponent("Exclude:", excluded)
      .addLabeledComponent("Named selector (overrides Selection):", named)
      .addComponent(refresh).addComponent(buttons)
      .addLabeledComponent("Inline SQL (Preview only):", new com.intellij.ui.components.JBScrollPane(inline))
      .addLabeledComponent("Executable override:", executable)
      .addLabeledComponent("Prefix arguments:", arguments)
      .addLabeledComponent("Profiles directory override:", profiles)
      .addComponent(new JBLabel("Other execution defaults: Settings → dbt Workspace"))
      .getPanel
    val panel = new JPanel(new BorderLayout); panel.add(form, BorderLayout.NORTH); panel
  }
  override def resetEditorFrom(value: DbtRunConfiguration): Unit = {
    configuration = value; resolved = value.cliSelection
    root.setText(value.root); scope.setText(value.scope); target.setText(value.target); inline.setText(value.inlineSql)
    executable.setText(value.executable); arguments.setText(value.executableArguments); profiles.setText(value.profilesDirectory)
    selectors.setText(value.selectors.mkString(" ")); excluded.setText(value.exclude.mkString(" ")); named.setText(value.namedSelector)
    action.setSelectedItem(value.action); refresh.setSelected(value.fullRefresh)
  }
  override def applyEditorTo(value: DbtRunConfiguration): Unit = {
    value.root = root.getText.trim; value.scope = scope.getText.trim; value.target = target.getText.trim; value.inlineSql = inline.getText
    value.executable = executable.getText.trim; value.executableArguments = arguments.getText; value.profilesDirectory = profiles.getText.trim
    val terms = selectors.getText.trim.split("\\s+").filter(_.nonEmpty).toVector
    if (terms != value.selectors || named.getText.trim.nonEmpty) value.exactTests = false
    value.selectors = terms; value.exclude = excluded.getText.trim.split("\\s+").filter(_.nonEmpty).toVector
    value.namedSelector = named.getText.trim; value.action = action.getSelectedItem.toString
    value.fullRefresh = refresh.isSelected; value.captured = None; value.cliSelection = resolved
  }
  override def disposeEditor(): Unit = { Vector(root, scope, executable, profiles).foreach(_.dispose()); super.disposeEditor() }
}
