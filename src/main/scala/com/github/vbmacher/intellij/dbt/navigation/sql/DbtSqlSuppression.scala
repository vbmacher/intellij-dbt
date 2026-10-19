package com.github.vbmacher.intellij.dbt.navigation.sql

import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.navigation.{DbtSqlSuppressionComment => Comment, DbtSqlSuppressionStatement => Statement, DbtSqlSuppressionLayout => Layout}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import net.sf.jsqlparser.parser.{CCJSqlParserTokenManager, SimpleCharStream, StringProvider, Token, TokenMgrException}
import scala.collection.mutable.ArrayBuffer

/** SQL tokens distinguish statement boundaries and real comments from quoted comment text. */
private[dbt] object DbtSqlSuppression {
  private val Directive = "(?s)--\\s*(file:\\s*)?noinspection\\s+([A-Za-z_0-9.-]+(?:\\s*,\\s*[A-Za-z_0-9.-]+)*)(?:\\s.*)?".r

  def layout(file: PsiFile): Layout = {
    val text = file.getText
    val blocks = DbtSyntax.inFile(file)
    val chars = text.toCharArray
    (blocks.map(_.range) ++ "(?s)\\{#.*?#\\}".r.findAllMatchIn(text).map(m => new TextRange(m.start, m.end))).foreach { range =>
      range.getStartOffset.until(range.getEndOffset).foreach(i => if (chars(i) != '\n' && chars(i) != '\r') chars(i) = ' ')
    }
    val stream = new SimpleCharStream(new StringProvider(new String(chars)))
    stream.setTabSize(1)
    val lexer = new CCJSqlParserTokenManager(stream)
    val starts = Vector(0) ++ text.indices.filter(text(_) == '\n').map(_ + 1)
    def range(token: Token): TextRange = new TextRange(
      math.min(text.length, starts.lift(token.beginLine - 1).getOrElse(0) + token.beginColumn - 1),
      math.min(text.length, starts.lift(token.endLine - 1).getOrElse(0) + token.endColumn))
    val tokens = ArrayBuffer.empty[(String, TextRange)]
    val comments = ArrayBuffer.empty[Comment]
    try {
      var done = false
      while (!done) {
        ProgressManager.checkCanceled()
        val token = lexer.getNextToken()
        var comment = token.specialToken
        while (comment != null) {
          comment.image.trim match {
            case Directive(prefix, ids) => comments += Comment(range(comment), ids, prefix != null)
            case _ => ()
          }
          comment = comment.specialToken
        }
        done = token.kind == 0
        if (!done) tokens += token.image -> range(token)
      }
    } catch { case _: TokenMgrException => () } // Keep proven boundaries preceding unfinished SQL.
    val ends = tokens.collect { case (";", r) => r.getEndOffset }.toVector :+ text.length
    val statements = (Vector(0) ++ ends).sliding(2).collect { case Vector(start, end) if end > start =>
      val first = tokens.iterator.map(_._2.getStartOffset).find(i => i >= start && i < end)
      val template = blocks.iterator.map(_.range.getStartOffset).find(i => i >= start && i < end)
      Statement(new TextRange(start, end), (first.toVector ++ template).minOption.getOrElse(end))
    }.toVector
    Layout(statements, comments.toVector)
  }
}
