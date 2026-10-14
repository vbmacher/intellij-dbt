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
  def ranges(sql: String): Vector[TextRange] = {
    Vector("", "select ", "select * ").iterator.flatMap { prefix =>
      parsed(prefix + sql).map(_.filter(_.getStartOffset >= prefix.length).map(_.shiftRight(-prefix.length)))
    }.take(1).toVector.flatten
  }

  private def parsed(sql: String): Option[Vector[TextRange]] = {
    val result = ArrayBuffer.empty[TextRange]
    def offset(t: Token, end: Boolean): Int = {
      val line = if (end) t.endLine else t.beginLine
      val column = if (end) t.endColumn else t.beginColumn
      var i = 0; var l = 1; var c = 1
      while (i < sql.length && (l < line || c < column)) {
        if (sql(i) == '\n') { l += 1; c = 1 }
        else if (sql(i) == '\t') c += 8 - ((c - 1) % 8) else c += 1
        i += 1
      }
      if (end) math.min(sql.length, i + 1) else i
    }
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
          result += new TextRange(offset(first, end = false), offset(last, end = true))
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
