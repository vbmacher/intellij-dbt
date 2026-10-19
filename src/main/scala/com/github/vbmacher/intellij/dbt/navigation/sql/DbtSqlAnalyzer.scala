package com.github.vbmacher.intellij.dbt.navigation.sql

import com.github.vbmacher.intellij.dbt.navigation._
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.{Expression, ExpressionVisitorAdapter}
import net.sf.jsqlparser.parser.{ASTNodeAccess, CCJSqlParserUtil}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** A conservative, offset-preserving projection analysis. All dialect syntax is parsed by JSqlParser. */
private[sql] object DbtSqlAnalyzer {
  private final case class Relation(columns: Vector[DbtSymbol], known: Boolean,
    cte: Option[(String, TextRange)] = None)
  def analyze(file: PsiFile, w: DbtWorkspace, completionOffset: Option[Int]): Option[DbtSqlAnalysis] = {
    if (file.getVirtualFile.getExtension != "sql" || file.getTextLength > 1000000) return None
    val root = w.root(file).filter(r => r.resource(file) && !r.excluded(file.getVirtualFile)).getOrElse(return None)
    val text = file.getText
    val blocks = DbtSyntax.inFile(file)
    val sql = DbtSqlNavigation.masked(text, blocks).getOrElse(return Some(DbtSqlAnalysis(Vector.empty, Vector.empty,
      Vector.empty, Vector.empty, Vector("Dynamic Jinja control flow; column analysis unavailable."))))
    def parse(candidate: String): Option[net.sf.jsqlparser.statement.Statement] =
      try Some(CCJSqlParserUtil.parse(candidate, p => p.withTimeOut(1000))) catch { case _: JSQLParserException => None }
    val parsed = parse(sql).orElse(completionOffset.filter(i => i > 0 && i <= sql.length).flatMap { i =>
      parse(sql.updated(i - 1, if (sql(i - 1) == '.') ' ' else '*'))
    }).getOrElse(return None)
    val references = DbtNavigation.inFile(file, includeSelections = false)
    val issues = ArrayBuffer.empty[String]
    if (DbtSqlNavigation.tokens(sql).sliding(2).exists(ts => ts.size == 2 && ts.head.image.equalsIgnoreCase("with") && ts(1).image.equalsIgnoreCase("recursive")))
      issues += "Recursive CTE analysis is partial; query inspection is unsupported."
    val scopes = ArrayBuffer.empty[DbtSqlScope]
    val ctes = ArrayBuffer.empty[DbtCte]
    val cteReferences = ArrayBuffer.empty[(String, TextRange)]
    val wildcards = ArrayBuffer.empty[(TextRange, Vector[String])]
    val intermediate = ArrayBuffer.empty[DbtSqlColumn]
    val problems = ArrayBuffer.empty[DbtSqlProblem]
    val deletions = ArrayBuffer.empty[(TextRange, TextRange)]
    val declarations = ArrayBuffer.empty[DbtSymbol]
    val allTokens = DbtSqlNavigation.tokens(sql)
    def range(node: ASTNodeAccess): TextRange = {
      val tokens = DbtSqlNavigation.nodeTokens(node)
      if (tokens.isEmpty) TextRange.EMPTY_RANGE
      else new TextRange(DbtSqlRelations.offset(text, tokens.head), DbtSqlRelations.offset(text, tokens.last, end = true))
    }
    def nameRange(node: ASTNodeAccess): TextRange = DbtSqlNavigation.nodeTokens(node).lastOption
      .map(t => new TextRange(DbtSqlRelations.offset(text, t), DbtSqlRelations.offset(text, t, end = true))).getOrElse(TextRange.EMPTY_RANGE)
    def removable(item: TextRange, allowSoleWith: Boolean): Option[TextRange] = {
      val before = allTokens.filter(t => DbtSqlRelations.offset(text, t, end = true) <= item.getStartOffset).lastOption
      val after = allTokens.find(t => DbtSqlRelations.offset(text, t) >= item.getEndOffset)
      if (after.exists(_.image == ",")) Some(new TextRange(item.getStartOffset, DbtSqlRelations.offset(text, after.get, end = true)))
      else if (before.exists(_.image == ",")) Some(new TextRange(DbtSqlRelations.offset(text, before.get), item.getEndOffset))
      else if (allowSoleWith && before.exists(_.image.equalsIgnoreCase("with"))) Some(new TextRange(DbtSqlRelations.offset(text, before.get), item.getEndOffset))
      else None
    }
    def external(table: Table): Relation = {
      val candidates = blocks.zipWithIndex.find { case (_, i) => table.getName == s"_dbt$i" }.toVector.flatMap { case (b, _) =>
        references.filter(r => b.range.contains(r.getRangeInElement)).flatMap(_.candidates).collect {
          case e: DbtSymbolElement if DbtSymbol.RelationKinds(e.symbol.kind) => e.symbol
        }
      }.distinctBy(_.uniqueId)
      if (candidates.size != 1) Relation(Vector.empty, known = false)
      else {
        val model = candidates.head
        val declared = w.columns(model)
        if (!model.isCode) Relation(declared, declared.nonEmpty && !model.isStale)
        else {
          val analysis = DbtSqlAnalysis.get(w, model.file)
          val projected = analysis.toVector.flatMap(_.columns).map { c =>
            val metadata = declared.find(_.name == c.name).map(_.attributes).getOrElse(Map.empty)
            DbtSymbol("column", c.name, model.namespace, model.file, c.range, model.relationName, model.version,
              metadata ++ c.dataType.map("data_type" -> _) + ("projection" -> "true"))
          }
          Relation(projected, projected.nonEmpty && !model.isStale && analysis.exists(a => a.issues.isEmpty && a.columns.forall(_.complete)))
        }
      }
    }
    def local(columns: Vector[DbtSqlColumn]): Relation = {
      intermediate ++= columns
      Relation(columns.map(c => DbtSymbol("sql_projection", c.name, root.name, file, c.range)),
        columns.nonEmpty && columns.forall(_.complete))
    }

    def select(s: Select, inherited: Map[String, Relation]): Vector[DbtSqlColumn] = {
      ProgressManager.checkCanceled()
      var visible = inherited
      val withItems = Option(s.getWithItemsList).toVector.flatMap(_.asScala)
      withItems.foreach { item =>
        if (!item.getParenthesedStatement.isInstanceOf[ParenthesedSelect]) issues += "Data-modifying CTEs are unsupported."
        Option(item.getParenthesedStatement).collect { case body: ParenthesedSelect => body }.foreach { body =>
          val name = item.getUnquotedAliasName
          val explicitColumns = Option(item.getWithItemList).toVector.flatMap(_.asScala)
          val referencesStart = cteReferences.size
          val bodyColumns = select(body.getSelect, visible)
          if (explicitColumns.nonEmpty) issues += "Explicit CTE column lists are not supported by column analysis or refactoring."
          val columns = if (explicitColumns.nonEmpty) Vector.empty else bodyColumns
          // CTE output identities are retained; lineage can trace these local ranges back to their inputs.
          val bodyRange = range(body.getSelect)
          val before = explicitColumns.headOption.map(range(_).getStartOffset).getOrElse(range(body).getStartOffset)
          val nameToken = DbtSqlNavigation.tokens(sql).filter(t => DbtSqlRelations.offset(text, t) < before)
            .filter(t => t.image.replace("\"", "").equalsIgnoreCase(name)).lastOption
          val itemRange = new TextRange(nameToken.map(DbtSqlRelations.offset(text, _)).getOrElse(range(body).getStartOffset), range(body).getEndOffset)
          val dependencies = cteReferences.drop(referencesStart).filterNot { case (_, declaration) => bodyRange.contains(declaration) }
            .map(_._1).distinct.toVector
          visible += name.toLowerCase -> local(columns).copy(cte = Some(name -> itemRange))
          ctes += DbtCte(name, itemRange, bodyRange, dependencies, bodyRange.substring(text))
          nameToken.foreach(t => declarations += DbtSymbol("cte", name, root.name, file,
            new TextRange(DbtSqlRelations.offset(text, t), DbtSqlRelations.offset(text, t, end = true))))
          nameToken.foreach(t => removable(itemRange, allowSoleWith = true).foreach(delete => deletions +=
            new TextRange(DbtSqlRelations.offset(text, t), DbtSqlRelations.offset(text, t, end = true)) -> delete))
        }
      }
      s match {
        case p: ParenthesedSelect => select(p.getSelect, visible)
        case sets: SetOperationList =>
          val branches = Option(sets.getSelects).toVector.flatMap(_.asScala).map(select(_, visible))
          branches.headOption.toVector.flatten.zipWithIndex.map { case (column, index) =>
            val peers = branches.flatMap(_.lift(index))
            column.copy(inputs = peers.flatMap(_.inputs).distinct, direct = false,
              complete = peers.size == branches.size && peers.forall(_.complete), expression = "set operation")
          }
        case p: PlainSelect =>
          val from = Option(p.getFromItem).toVector ++ Option(p.getJoins).toVector.flatMap(_.asScala).map(_.getFromItem)
          val relations = from.map { f =>
            val relation = f match {
              case t: Table =>
                val resolved = visible.getOrElse(t.getUnquotedName.toLowerCase, external(t))
                resolved.cte.foreach(cteReferences += _)
                resolved
              case sub: ParenthesedSelect => local(select(sub.getSelect, visible))
              case _ => Relation(Vector.empty, known = false)
            }
            val alias = Option(f.getAlias).map(_.getUnquotedName).getOrElse(f match { case t: Table => t.getUnquotedName; case _ => "" })
            Option(f.getAlias).foreach { a => f match {
              case n: ASTNodeAccess => DbtSqlNavigation.nodeTokens(n).reverse.find(t => t.image.replace("\"", "").equalsIgnoreCase(a.getUnquotedName)).foreach { token =>
                val aliasRange = new TextRange(DbtSqlRelations.offset(text, token), DbtSqlRelations.offset(text, token, end = true))
                declarations += DbtSymbol("sql_alias", a.getUnquotedName, root.name, file, aliasRange)
                val previous = allTokens.filter(t => DbtSqlRelations.offset(text, t, end = true) <= aliasRange.getStartOffset).lastOption
                val start = previous.filter(_.image.equalsIgnoreCase("as")).map(DbtSqlRelations.offset(text, _)).getOrElse(aliasRange.getStartOffset)
                deletions += aliasRange -> new TextRange(start, aliasRange.getEndOffset)
              }
              case _ => ()
            }}
            alias -> relation
          }
          val complete = relations.forall(_._2.known)
          scopes += DbtSqlScope(range(p), relations.map { case (a, r) => a -> r.columns }, complete)
          def resolve(c: Column, report: Boolean = true): Vector[DbtSymbol] = {
            val qualifier = Option(c.getTable).flatMap(t => Option(t.getName))
            val candidates = relations.filter { case (a, _) => qualifier.forall(_.equalsIgnoreCase(a)) }
              .flatMap(_._2.columns).filter(_.name.equalsIgnoreCase(c.getUnquotedColumnName))
            if (report && candidates.isEmpty && complete && !c.getUnquotedColumnName.startsWith("_dbt"))
              problems += DbtSqlProblem(nameRange(c), "Unknown column '" + c.getUnquotedColumnName + "' in this SQL scope.")
            if (candidates.size == 1) candidates else Vector.empty
          }
          def quoted(c: DbtSymbol): String = {
            val raw = c.range.substring(c.file.getText)
            if (raw.startsWith("\"") || raw.startsWith("`")) raw
            else if (DbtSyntax.isIdentifier(c.name)) c.name else "\"" + c.name.replace("\"", "\"\"") + "\""
          }
          def qualifiedAlias(alias: String): String = from.find(f => Option(f.getAlias).exists(_.getUnquotedName.equalsIgnoreCase(alias)))
            .flatMap(f => Option(f.getAlias).map(_.getName)).getOrElse(alias)
          val output = Option(p.getSelectItems).toVector.flatMap(_.asScala).flatMap { item =>
            val expression = item.getExpression.asInstanceOf[Expression]
            val star = expression match {
              case a: AllTableColumns => Some(Option(a.getTable).map(_.getUnquotedName))
              case _: AllColumns => Some(None)
              case _ => None
            }
            if (star.nonEmpty) {
              val qualifier = star.flatten
              val chosen = relations.filter { case (a, _) => qualifier.forall(_.equalsIgnoreCase(a)) }
              val columns = chosen.flatMap(_._2.columns)
              val safe = chosen.nonEmpty && chosen.forall(_._2.known) && columns.nonEmpty &&
                !(relations.size > 1 && chosen.exists(_._1.startsWith("_dbt"))) &&
                expression.toString.trim.matches("(?s)([^ ]+\\.)?\\*")
              if (safe) wildcards += range(expression) -> chosen.flatMap { case (a, r) =>
                r.columns.map(c => (if (qualifier.nonEmpty || relations.size > 1) qualifiedAlias(a) + "." else "") + quoted(c))
              }
              else issues += "Wildcard has an unknown schema or unsupported modifiers."
              columns.map(c => DbtSqlColumn(c.name, range(expression), Vector(c), direct = true, expression.toString, safe))
            } else {
              val inputs = ArrayBuffer.empty[DbtSymbol]
              var resolved = true
              expression.accept(new ExpressionVisitorAdapter[Void] {
                override def visit[S](c: Column, context: S): Void = {
                  val targets = resolve(c)
                  if (targets.isEmpty) resolved = false
                  inputs ++= targets; null
                }
              }, null)
              val name = Option(item.getAlias).map(_.getUnquotedName).orElse(expression match {
                case c: Column => Some(c.getUnquotedColumnName)
                case _ => None
              })
              val dataType = expression match {
                case c: Column => resolve(c).headOption.flatMap(_.attributes.get("data_type"))
                case c: net.sf.jsqlparser.expression.CastExpression => Option(c.getColDataType).map(_.getDataType)
                case _: net.sf.jsqlparser.expression.StringValue => Some("string")
                case _ => None
              }
              name.filterNot(_ => item.getAlias == null && blocks.exists(_.range.intersects(nameRange(item)))).toVector.map(n => DbtSqlColumn(n, nameRange(item), inputs.toVector.distinct,
                expression.isInstanceOf[Column], range(expression).substring(text), resolved && complete, dataType, item.getAlias != null))
            }
          }.toVector
          output.filter(_.outputAlias).foreach(c => declarations += DbtSymbol("sql_projection", c.name, root.name, file, c.range))
          Option(p.getSelectItems).toVector.flatMap(_.asScala).foreach(item => removable(range(item), allowSoleWith = false)
            .foreach(delete => deletions += nameRange(item) -> delete))
          val contextInputs = ArrayBuffer.empty[(DbtSymbol, String)]
          def context(expression: Expression, kind: String): Unit = if (expression != null) expression.accept(new ExpressionVisitorAdapter[Void] {
            override def visit[S](c: Column, ignored: S): Void = {
              val inputs = resolve(c, report = false)
              val aliases = if (inputs.isEmpty && Option(c.getTable).forall(_.getName == null))
                output.filter(_.name.equalsIgnoreCase(c.getUnquotedColumnName)).flatMap(_.inputs) else Vector.empty
              contextInputs ++= (inputs ++ aliases).map(_ -> kind); null
            }
          }, null)
          context(p.getWhere, "filter"); context(p.getHaving, "filter")
          Option(p.getJoins).toVector.flatMap(_.asScala).foreach(j => Option(j.getOnExpressions).toVector.flatMap(_.asScala).foreach(context(_, "join")))
          output.map(c => c.copy(contextInputs = contextInputs.toVector.distinct))
        case _ => issues += "Unsupported SQL query shape."; Vector.empty
      }
    }
    parsed match {
      case s: Select =>
        val columns = select(s, Map.empty)
        Some(DbtSqlAnalysis(columns, scopes.toVector, ctes.toVector, wildcards.toVector,
          issues.toVector.distinct ++ (if (scopes.exists(!_.complete)) Vector("Some relation schemas are unavailable.") else Vector.empty), intermediate.toVector, problems.toVector.distinct, deletions.toVector, declarations.toVector))
      case _ => None
    }
  }
}
