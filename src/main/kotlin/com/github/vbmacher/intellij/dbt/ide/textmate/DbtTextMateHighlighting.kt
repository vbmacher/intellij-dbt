package com.github.vbmacher.intellij.dbt.ide.textmate

import com.github.vbmacher.intellij.dbt.project.DbtRoot
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.github.vbmacher.intellij.dbt.ide.DbtTargetElementEvaluator
import com.github.vbmacher.intellij.dbt.ide.DbtUi
import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.*
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.textmate.joni.JoniRegexFactory
import org.jetbrains.plugins.textmate.TextMateFileType
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateEditorHighlighterProvider
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateHighlighter
import org.jetbrains.plugins.textmate.language.syntax.lexer.caching
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateSyntaxMatcherImpl
import org.jetbrains.plugins.textmate.language.syntax.selector.caching
import org.jetbrains.plugins.textmate.language.syntax.selector.TextMateSelectorWeigherImpl
import org.jetbrains.plugins.textmate.regex.CaffeineCachingRegexProvider
import org.jetbrains.plugins.textmate.regex.RememberingLastMatchRegexFactory

/** Supplies the plugin's private dbt grammars without changing the IDE's global SQL, YAML or Markdown associations. */
class DbtTextMateBundleProvider : TextMateBundleProvider, PluginAware {
    private lateinit var descriptor: PluginDescriptor
    override fun setPluginDescriptor(value: PluginDescriptor) {
        descriptor = value
    }

    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> =
        listOf(TextMateBundleProvider.PluginBundle("dbt", descriptor.pluginPath.resolve("textmate/dbt")))
}

/** TextMate leaf tokens use Language.ANY; route only TextMate files to dbt's declaration evaluator. */
class DbtTextMateTargetElementEvaluator : TargetElementEvaluatorEx2() {
    private val delegate = DbtTargetElementEvaluator()

    @Suppress("SENSELESS_COMPARISON")
    override fun adjustReferenceOrReferencedElement(
        file: PsiFile, editor: Editor, offset: Int,
        flags: Int, refElement: PsiElement?
    ): PsiElement? =
        if (file != null && file.language.id == "textmate") delegate.adjustReferenceOrReferencedElement(
            file,
            editor,
            offset,
            flags,
            refElement
        ) else refElement
}

/**
 * Applies private dbt grammars only to supported, unexcluded project files.
 * Host PSI and file associations remain available for native navigation and inspections.
 */
class DbtTextMateHighlighterProvider : SyntaxHighlighterProvider {
    private val syntaxMatcher by lazy {
        TextMateSyntaxMatcherImpl(
            CaffeineCachingRegexProvider(RememberingLastMatchRegexFactory(JoniRegexFactory())),
            TextMateSelectorWeigherImpl().caching()
        ).caching()
    }

    override fun create(fileType: FileType, project: Project?, file: VirtualFile?): SyntaxHighlighter? {
        if (project == null || project.isDisposed || file == null || !file.isValid || file.isDirectory) return null
        val supportedTypes = setOf("DBT_SQL", "SQL", "PLAIN_TEXT", "YAML", "Markdown", TextMateFileType.INSTANCE.name)
        if (fileType.name !in supportedTypes) return null
        val extension = file.extension.orEmpty()
        val grammar = when (extension) {
            "sql" -> "dbt-sql"
            "yml", "yaml" -> "dbt-yaml"
            "md" -> "dbt-markdown"
            else -> return null
        }
        val supported = DbtSqlFileType.inProject(project, file) { root ->
            root.kind(file) != null ||
                    (extension != "sql" && file.parent == root.directory) || root.relative(file) == root.profilesFile
        }
        if (!supported) return null
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByExtension(grammar) ?: return null
        return TextMateHighlighter(TextMateHighlightingLexer(descriptor, syntaxMatcher, -1))
    }
}

/** Community's custom SQL file type cannot use TextMate editor storage; keep its plain-text PSI. */
class DbtSqlFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (!file.isValid || file.isDirectory || file.extension != "sql") return null
        val original = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        if (original is LanguageFileType) return null
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return null
        if (project.isDisposed) return null
        return if (DbtSqlFileType.inProject(project, file) { it.kind(file) != null }) DbtSqlFileType.instance else null
    }
}

private class DbtSqlFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName() = "DBT_SQL"
    override fun getDescription() = "dbt SQL"
    override fun getDefaultExtension() = "sql"
    override fun getIcon(): javax.swing.Icon = com.intellij.icons.AllIcons.FileTypes.Text

    companion object {
        val instance = DbtSqlFileType()

        /** Whether file is an unexcluded file of a dbt project root accepted by accept. */
        fun inProject(project: Project, file: VirtualFile, accept: (DbtRoot) -> Boolean): Boolean =
            DbtUi.read { DbtWorkspace.rootForFile(project, file)?.let { !it.excluded(file) && accept(it) } == true }
    }
}

/** TextMate tokens require its editor storage, rather than the platform's registered-token storage. */
class DbtTextMateEditorHighlighterProvider : EditorHighlighterProvider {
    override fun getEditorHighlighter(
        project: Project?,
        fileType: FileType,
        file: VirtualFile?,
        colors: EditorColorsScheme
    ): EditorHighlighter {
        val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, file)
        return if (syntax is TextMateHighlighter) TextMateEditorHighlighterProvider().getEditorHighlighter(
            project,
            fileType,
            file,
            colors
        )
        else FileTypeEditorHighlighterProviders.getInstance().allForFileType(fileType)
            .firstOrNull { it !is DbtTextMateEditorHighlighterProvider }
            ?.getEditorHighlighter(project, fileType, file, colors)
            ?: EditorHighlighterFactory.getInstance().createEditorHighlighter(syntax, colors)
    }
}
