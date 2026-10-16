package com.github.vbmacher.intellij.dbt.ide.textmate

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.github.vbmacher.intellij.dbt.ide.DbtTargetElementEvaluator
import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.{EditorHighlighter, EditorHighlighterFactory}
import com.intellij.openapi.fileTypes.{EditorHighlighterProvider, FileType, FileTypeEditorHighlighterProviders,
  FileTypeManager, LanguageFileType, PlainTextLanguage, SyntaxHighlighter, SyntaxHighlighterFactory, SyntaxHighlighterProvider}
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.{Project, ProjectLocator}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.textmate.joni.JoniRegexFactory
import org.jetbrains.plugins.textmate.{TextMateFileType, TextMateService}
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.language.syntax.highlighting.{TextMateEditorHighlighterProvider, TextMateHighlighter}
import org.jetbrains.plugins.textmate.language.syntax.lexer.{TextMateCachingSyntaxMatcherCoreKt, TextMateHighlightingLexer, TextMateSyntaxMatcherImpl}
import org.jetbrains.plugins.textmate.language.syntax.selector.{TextMateCachingSelectorWeigherKt, TextMateSelectorWeigherImpl}
import org.jetbrains.plugins.textmate.regex.{CaffeineCachingRegexProvider, RememberingLastMatchRegexFactory}

import scala.jdk.CollectionConverters._

final class DbtTextMateBundleProvider extends TextMateBundleProvider {
  override def getBundles: java.util.List[TextMateBundleProvider.PluginBundle] = {
    val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.github.vbmacher.intellij-dbt"))
    java.util.List.of(new TextMateBundleProvider.PluginBundle("dbt", plugin.getPluginPath.resolve("textmate/dbt")))
  }
}

/** TextMate leaf tokens use Language.ANY; route only TextMate files to dbt's declaration evaluator. */
final class DbtTextMateTargetElementEvaluator extends TargetElementEvaluatorEx2 {
  private val delegate = new DbtTargetElementEvaluator
  override def adjustReferenceOrReferencedElement(file: PsiFile, editor: Editor, offset: Int,
    flags: Int, refElement: PsiElement): PsiElement =
    if (file != null && file.getLanguage.getID == "textmate")
      delegate.adjustReferenceOrReferencedElement(file, editor, offset, flags, refElement)
    else refElement
}

/** Use the complete TextMate grammar without replacing the host language's PSI or file associations. */
final class DbtTextMateHighlighterProvider extends SyntaxHighlighterProvider {
  private lazy val syntaxMatcher = TextMateCachingSyntaxMatcherCoreKt.caching(new TextMateSyntaxMatcherImpl(
    new CaffeineCachingRegexProvider(new RememberingLastMatchRegexFactory(new JoniRegexFactory)),
    TextMateCachingSelectorWeigherKt.caching(new TextMateSelectorWeigherImpl)))

  override def create(fileType: FileType, project: Project, file: VirtualFile): SyntaxHighlighter = {
    if (project == null || project.isDisposed || file == null || !file.isValid || file.isDirectory) return null
    val supportedTypes = Set("DBT_SQL", "SQL", "PLAIN_TEXT", "YAML", "Markdown", TextMateFileType.INSTANCE.getName)
    if (!supportedTypes(fileType.getName)) return null
    val extension = Option(file.getExtension).getOrElse("")
    val grammar = extension match {
      case "sql" => "dbt-sql"
      case "yml" | "yaml" => "dbt-yaml"
      case "md" => "dbt-markdown"
      case _ => return null
    }
    val supported = ReadAction.computeBlocking[Boolean, RuntimeException](() => {
      DbtWorkspace.rootForFile(project, file).exists { root =>
        !root.excluded(file) && (root.kind(file).nonEmpty || (extension != "sql" && file.getParent == root.directory) ||
          root.relative(file).contains(root.profilesFile))
      }
    })
    if (!supported) return null
    val descriptor = TextMateService.getInstance.getLanguageDescriptorByExtension(grammar)
    if (descriptor == null) null else new TextMateHighlighter(new TextMateHighlightingLexer(descriptor, syntaxMatcher, -1))
  }
}

/** Community's custom SQL file type cannot use TextMate editor storage; keep its plain-text PSI. */
final class DbtSqlFileTypeOverrider extends FileTypeOverrider {
  override def getOverriddenFileType(file: VirtualFile): FileType = {
    if (!file.isValid || file.isDirectory || file.getExtension != "sql") return null
    val original = FileTypeManager.getInstance.getFileTypeByFileName(file.getName)
    if (original.isInstanceOf[LanguageFileType]) return null
    val project = ProjectLocator.getInstance.guessProjectForFile(file)
    if (project == null || project.isDisposed) return null
    val resource = ReadAction.computeBlocking[Boolean, RuntimeException](() =>
      DbtWorkspace.rootForFile(project, file).exists(r => !r.excluded(file) && r.kind(file).nonEmpty))
    if (resource) DbtSqlFileType.Instance else null
  }
}

private final class DbtSqlFileType extends LanguageFileType(PlainTextLanguage.INSTANCE, true) {
  override def getName: String = "DBT_SQL"
  override def getDescription: String = "dbt SQL"
  override def getDefaultExtension: String = "sql"
  override def getIcon: javax.swing.Icon = com.intellij.icons.AllIcons.FileTypes.Text
}

private object DbtSqlFileType {
  val Instance: LanguageFileType = new DbtSqlFileType
}

/** TextMate tokens require its editor storage, rather than the platform's registered-token storage. */
final class DbtTextMateEditorHighlighterProvider extends EditorHighlighterProvider {
  override def getEditorHighlighter(project: Project, fileType: FileType, file: VirtualFile,
    colors: EditorColorsScheme): EditorHighlighter = {
    val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, file)
    if (syntax.isInstanceOf[TextMateHighlighter])
      new TextMateEditorHighlighterProvider().getEditorHighlighter(project, fileType, file, colors)
    else FileTypeEditorHighlighterProviders.getInstance.allForFileType(fileType).asScala
      .find(!_.isInstanceOf[DbtTextMateEditorHighlighterProvider])
      .map(_.getEditorHighlighter(project, fileType, file, colors))
      .getOrElse(EditorHighlighterFactory.getInstance.createEditorHighlighter(syntax, colors))
  }
}
