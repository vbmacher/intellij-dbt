package com.github.vbmacher.intellij.dbt.navigation.sql

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.{CCJSqlParserUtil, Token}
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.util.TablesNamesFinder
import scala.collection.mutable.ArrayBuffer

/** Only AST Table nodes are replaced; alias.column qualifiers and literals are never relations. */
private[dbt] object DbtSqlRelations {
  /** The text offset of a token; JSqlParser counts a tab as a single column. */
  private[sql] def offset(text: String, t: Token, end: Boolean = false): Int = {
    val (line, column) = if (end) (t.endLine, t.endColumn) else (t.beginLine, t.beginColumn)
    val lineStart = Iterator.iterate(0)(i => text.indexOf('\n', i) + 1).drop(line - 1).next()
    math.min(text.length, lineStart + column - (if (end) 0 else 1))
  }

  def ranges(sql: String): Vector[TextRange] = {
    Vector("", "select ", "select * ").iterator.flatMap { prefix =>
      parsed(prefix + sql).map(_.filter(_.getStartOffset >= prefix.length).map(_.shiftRight(-prefix.length)))
    }.take(1).toVector.flatten
  }

  private def parsed(sql: String): Option[Vector[TextRange]] = {
    val result = ArrayBuffer.empty[TextRange]
    val visitor = new TablesNamesFinder[Void] {
      override def visit[S](table: Table, context: S): Void = {
        ProgressManager.checkCanceled()
        Option(table.getASTNode).foreach { node =>
          // The Table AST ends before its alias. Offsets preserve all surrounding SQL formatting.
          val first = node.jjtGetFirstToken()
          var last = first
          // FromItem includes aliases/hints; consume only the parser's multipart table name.
          var remaining = table.getNameParts.size() * 2 - 2
          while (remaining > 0 && last.next != null) { last = last.next; remaining -= 1 }
          result += new TextRange(offset(sql, first), offset(sql, last, end = true))
        }
        super.visit(table, context)
      }
    }
    try {
      val statements = CCJSqlParserUtil.parseStatements(sql, p => p.withTimeOut(1000))
      statements.forEach(statement => visitor.getTables(statement))
      Some(result.toVector.distinct)
    } catch { case _: JSQLParserException => None }
  }
}
