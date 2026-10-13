package com.github.vbmacher.intellij.dbt.navigation.sql

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider}
import com.intellij.openapi.util.TextRange
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter
import net.sf.jsqlparser.schema.Column
import scala.collection.mutable.ArrayBuffer

/** Parameters used in the body of SQL functions. */
private[sql] final class DbtFunctionParameters extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] = {
    val (file, w) = (context.file, context.workspace)
    if (file.getVirtualFile.getExtension != "sql" || file.getText.contains("{{")) return Vector.empty
    val functions = w.codeSymbols(file).filter(_.kind == "function")
    if (functions.isEmpty) return Vector.empty
    val params = functions.flatMap(f => w.symbols.filter(p => p.kind == "parameter" && p.owner == f.name && p.namespace == f.namespace && w.root(p.file) == w.root(f.file) &&
      p.attributes.get("overload") == f.attributes.get("overload")))
    val expression = try CCJSqlParserUtil.parseExpression(file.getText) catch {
      case _: JSQLParserException => return Vector.empty
    }
    val result = ArrayBuffer.empty[DbtNavigationReference]
    expression.accept(new ExpressionVisitorAdapter[Void] {
      override def visit[S](column: Column, context: S): Void = {
        val node = Option(column.getASTNode)
        node.foreach { n =>
          val t = n.jjtGetLastToken()
          val start = file.getText.linesWithSeparators.take(t.beginLine - 1).map(_.length).sum + t.beginColumn - 1
          val end = file.getText.linesWithSeparators.take(t.endLine - 1).map(_.length).sum + t.endColumn
          val matches = params.filter(_.name == column.getUnquotedColumnName)
          if (matches.nonEmpty && Option(column.getTable).forall(_.getName == null)) result += new DbtNavigationReference(file,
            new TextRange(start, end), matches.map(_.element), column.getUnquotedColumnName)
        }
        null
      }
    }, null)
    result.toVector
  }
}
