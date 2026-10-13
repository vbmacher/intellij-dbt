package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile}
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.{ASTNodeAccess, CCJSqlParserUtil, CCJSqlParserTokenManager, SimpleCharStream, StringProvider, Token}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.expression.{Expression, ExpressionVisitorAdapter}
import net.sf.jsqlparser.statement.select._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** SQL syntax comes exclusively from JSqlParser; templates are masked without changing offsets. */
private[dbt] object DbtSqlNavigation {
  private def offset(text: String, t: Token, end: Boolean = false): Int = {
    val line = if (end) t.endLine else t.beginLine
    val column = if (end) t.endColumn else t.beginColumn
    var p = 0
    var l = 1
    var c = 1
    while (p < text.length && (l < line || c < column)) {
      if (text(p) == '\n') {
        l += 1
        c = 1
      }
      else if (text(p) == '\t') c += 8 - ((c - 1) % 8)
      else c += 1
      p += 1
    }
    if (end) math.min(text.length, p + 1) else p
  }

  def projections(resource: DbtSymbol): Vector[DbtSymbol] = {
    val file = resource.file
    if (file.getVirtualFile.getExtension != "sql" || file.getTextLength > 1000000) return Vector.empty
    val source = file.getText
    val blocks = DbtSyntax.blocks(source)
    if (blocks.exists(b => b.tag && b.tokens.headOption.exists(t => Set("for", "if", "macro", "test", "snapshot", "materialization").contains(t.image)))) return Vector.empty
    val masked = source.toCharArray
    blocks.foreach { b =>
      b.range.getStartOffset.until(b.range.getEndOffset).foreach(i => if (masked(i) != '\n' && masked(i) != '\r') masked(i) = ' ')
      if (!b.tag) "_dbt".zipWithIndex.foreach { case (c, n) => if (b.range.getStartOffset + n < b.range.getEndOffset) masked(b.range.getStartOffset + n) = c }
    }
    val parsed = try CCJSqlParserUtil.parse(new String(masked), p => p.withTimeOut(1000)) catch {
      case _: JSQLParserException => return Vector.empty
    }
    parsed match {
      case select: PlainSelect => Option(select.getSelectItems).toVector.flatMap(_.asScala).flatMap { item =>
        val name = Option(item.getAlias).map(_.getUnquotedName).orElse(item.getExpression match {
          case c: Column => Some(c.getUnquotedColumnName)
          case _ => None
        })
        val token = Option(item.getASTNode).map(_.jjtGetLastToken())
        for (n <- name.toVector; t <- token.toVector if !n.startsWith("_dbt")) yield {
          val start = offset(source, t)
          val end = offset(source, t, end = true)
          DbtSymbol("column", n, resource.namespace, file, new TextRange(start, end), resource.name, resource.version,
            Map("projection" -> "true"))
        }
      }
      case _ => Vector.empty
    }
  }

  private final case class Relation(target: Vector[PsiElement], columns: Map[String, Vector[PsiElement]], infoView: Option[String] = None)

  def references(file: PsiFile, w: DbtWorkspace, root: DbtRoot, jinja: Vector[DbtNavigationReference]): Vector[DbtNavigationReference] = {
    if (file.getVirtualFile.getExtension != "sql" || file.getTextLength > 1000000) return Vector.empty
    val text = file.getText
    val blocks = DbtSyntax.blocks(text)
    if (blocks.exists(b => b.tag && b.tokens.headOption.exists(t => Set("for", "if", "macro", "test", "materialization", "snapshot").contains(t.image)))) return Vector.empty
    val masked = text.toCharArray
    val templateRelations = scala.collection.mutable.Map.empty[String, Relation]
    blocks.zipWithIndex.foreach { case (b, i) =>
      val candidates = jinja.filter(ref => b.range.contains(ref.getRangeInElement)).flatMap(_.candidates).collect {
        case e: DbtSymbolElement if Set("model", "seed", "snapshot", "source_table").contains(e.symbol.kind) => e.symbol
      }.distinct
      b.range.getStartOffset.until(b.range.getEndOffset).foreach(p => if (masked(p) != '\n' && masked(p) != '\r') masked(p) = ' ')
      if (!b.tag) {
        val marker = s"_dbt$i"
        marker.zipWithIndex.foreach { case (c, n) => if (b.range.getStartOffset + n < b.range.getEndOffset) masked(b.range.getStartOffset + n) = c }
        val columns = candidates.flatMap(s => w.symbols.filter(c => c.kind == "column" && c.namespace == s.namespace && w.owner(c) == w.owner(s) &&
            c.owner == (if (s.kind == "source_table") s.owner + "." + s.name else s.name) && c.version == s.version))
          .groupBy(_.name.toLowerCase).view.mapValues(_.map(_.element: PsiElement)).toMap
        val info = if (DbtInfoSchema.enabled(root, file)) DbtSyntax.calls(b).find(_.name == "info_schema")
          .flatMap(_.arguments.headOption.flatMap(_.literal)).filter(DbtInfoSchema.views.contains) else None
        templateRelations(marker) = info.map(view => Relation(Vector(DbtNavigation.documentation(file, view, "info-schema", root)),
          DbtInfoSchema.columns(file, root, view), Some(view))).getOrElse(Relation(candidates.map(_.element), columns))
      }
    }
    val sql = new String(masked)
    val parsed = try CCJSqlParserUtil.parse(sql, p => p.withTimeOut(1000)) catch {
      case _: JSQLParserException => return Vector.empty
    }
    val refs = ArrayBuffer.empty[DbtNavigationReference]

    def offset(t: Token, end: Boolean = false): Int = DbtSqlNavigation.offset(text, t, end)

    def range(t: Token): TextRange = new TextRange(offset(t), offset(t, end = true))

    def nodeTokens(node: ASTNodeAccess): Vector[Token] = Option(node.getASTNode).map { n =>
      val result = ArrayBuffer.empty[Token];
      var t = n.jjtGetFirstToken();
      val last = n.jjtGetLastToken()
      while (t != null && (result.isEmpty || result.last != last)) {
        result += t;
        t = t.next
      }
      result.toVector
    }.getOrElse(Vector.empty)

    val tokenManager = new CCJSqlParserTokenManager(new SimpleCharStream(new StringProvider(sql)))
    val tokenBuffer = ArrayBuffer.empty[Token]
    var nextToken = tokenManager.getNextToken()
    while (nextToken.kind != 0) {
      tokenBuffer += nextToken;
      nextToken = tokenManager.getNextToken()
    }
    val allTokens = tokenBuffer.toVector

    def declaration(name: String, near: Int, before: Boolean): Option[Token] = {
      val matches = allTokens.filter(t => t.image.replace("\"", "").replace("`", "").equalsIgnoreCase(name))
      if (before) matches.filter(t => offset(t) < near).lastOption else matches.find(t => offset(t) >= near)
    }

    def symbol(kind: String, name: String, t: Token): PsiElement = DbtSymbol(kind, name, root.name, file, range(t)).element

    def add(t: Token, targets: Vector[PsiElement]): Unit = {
      val rr = range(t)
      if (!blocks.exists(_.range.intersects(rr)) && targets.nonEmpty) refs += new DbtNavigationReference(file, rr, targets.distinct, t.image)
    }

    def select(s: Select, inherited: Map[String, Relation]): Map[String, Vector[PsiElement]] = {
      ProgressManager.checkCanceled()
      var ctes = inherited
      Option(s.getWithItemsList).toVector.flatMap(_.asScala).foreach { item =>
        Option(item.getSelect).foreach { body =>
          val first = nodeTokens(body).headOption.map(offset(_)).getOrElse(0)
          val target = declaration(item.getUnquotedAliasName, first, before = true).toVector.map(symbol("cte", item.getUnquotedAliasName, _))
          ctes += item.getUnquotedAliasName.toLowerCase -> Relation(target, select(body.getSelect, ctes))
        }
      }
      s match {
        case p: ParenthesedSelect => select(p.getSelect, ctes)
        case sets: SetOperationList => Option(sets.getSelects).toVector.flatMap(_.asScala).headOption.map(select(_, ctes)).getOrElse(Map.empty)
        case p: PlainSelect =>
          var relations = Map.empty[String, Relation]

          def from(f: FromItem): Unit = if (f != null) {
            val relation = f match {
              case t: Table =>
                val base = templateRelations.get(t.getName).orElse(ctes.get(t.getUnquotedName.toLowerCase)).getOrElse(Relation(Vector.empty, Map.empty))
                nodeTokens(t).headOption.foreach(add(_, base.target))
                base
              case sub: ParenthesedSelect => Relation(Vector.empty, select(sub.getSelect, ctes))
              case _ => Relation(Vector.empty, Map.empty)
            }
            val alias = Option(f.getAlias)
            val key = alias.map(_.getUnquotedName).orElse(f match { case t: Table => Some(t.getUnquotedName); case _ => None })
            val aliasTarget = alias.toVector.flatMap { a =>
              f match {
                case n: ASTNodeAccess => nodeTokens(n).reverse.find(_.image.replace("\"", "").equalsIgnoreCase(a.getUnquotedName)).toVector
                case _ => Vector.empty
              }
            }.map(t => symbol("sql_alias", alias.get.getUnquotedName, t))
            key.foreach(k => relations += k.toLowerCase -> relation.copy(target = if (aliasTarget.nonEmpty) aliasTarget else relation.target))
          }

          from(p.getFromItem)
          Option(p.getJoins).toVector.flatMap(_.asScala).foreach(j => from(j.getRightItem))
          val projections = scala.collection.mutable.Map.empty[String, Vector[PsiElement]]

          def columnTargets(c: Column): Vector[PsiElement] = {
            val name = c.getUnquotedColumnName.toLowerCase
            Option(c.getTable).filter(_.getName != null).map(t => relations.get(t.getUnquotedName.toLowerCase).toVector.flatMap(_.columns.getOrElse(name, Vector.empty)))
              .getOrElse {
                val candidates = relations.valuesIterator.flatMap(_.columns.get(name)).toVector
                if (candidates.size == 1) candidates.head else Vector.empty
              }
          }

          def expression(e: Expression, aliases: Boolean = false): Unit = if (e != null) e.accept(new ExpressionVisitorAdapter[Void] {
            override def visit[S](equals: net.sf.jsqlparser.expression.operators.relational.EqualsTo, context: S): Void = {
              (equals.getLeftExpression, equals.getRightExpression) match {
                case (column: Column, value: net.sf.jsqlparser.expression.StringValue) if column.getUnquotedColumnName == "name" =>
                  val relation = Option(column.getTable).filter(_.getName != null)
                    .flatMap(t => relations.get(t.getUnquotedName.toLowerCase)).orElse(if (relations.size == 1) relations.values.headOption else None)
                  val kinds = relation.flatMap(_.infoView).flatMap(v => Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot", "functions" -> "function", "analyses" -> "analysis", "checks" -> "check").get(v))
                  val targets = kinds.toVector.flatMap(k => w.symbols.filter(s => s.kind == k && s.name == value.getValue &&
                    !s.attributes.contains("property") && w.root(s.file).exists(r => w.namespaceVisible(root, r, qualified = false))).map(_.element))
                  nodeTokens(value).lastOption.foreach { t =>
                    if (targets.nonEmpty) {
                      val rr = range(t)
                      refs += new DbtNavigationReference(file, new TextRange(rr.getStartOffset + 1, rr.getEndOffset - 1), targets, value.getValue)
                    }
                  }
                case _ => ()
              }
              super.visit(equals, context)
            }

            override def visit[S](c: Column, context: S): Void = {
              val ts = nodeTokens(c)
              val targets = if (aliases && Option(c.getTable).forall(_.getName == null)) projections.getOrElse(c.getUnquotedColumnName.toLowerCase, columnTargets(c)) else columnTargets(c)
              ts.lastOption.foreach(add(_, targets))
              if (ts.size >= 3) ts.headOption.foreach(t => add(t, relations.get(c.getUnquotedTableName.toLowerCase).toVector.flatMap(_.target)))
              null
            }
          }, null)

          Option(p.getSelectItems).toVector.flatMap(_.asScala).foreach { item =>
            expression(item.getExpression.asInstanceOf[Expression])
            val alias = Option(item.getAlias)
            val name = alias.map(_.getUnquotedName).orElse(item.getExpression match { case c: Column => Some(c.getUnquotedColumnName); case _ => None })
            val target = alias match {
              case Some(a) => nodeTokens(item).reverse.find(_.image.replace("\"", "").equalsIgnoreCase(a.getUnquotedName)).toVector.map(symbol("sql_projection", a.getUnquotedName, _))
              case None => item.getExpression match {
                case c: Column => columnTargets(c);
                case _ => Vector.empty
              }
            }
            name.foreach(n => projections(n.toLowerCase) = target)
          }
          expression(p.getWhere);
          expression(p.getHaving)
          Option(p.getJoins).toVector.flatMap(_.asScala).foreach(j => Option(j.getOnExpressions).toVector.flatMap(_.asScala).foreach(expression(_)))
          Option(p.getOrderByElements).toVector.flatMap(_.asScala).foreach(o => expression(o.getExpression, aliases = true))
          projections.toMap
        case _ => Map.empty
      }
    }

    parsed match {
      case s: Select => select(s, Map.empty);
      case _ => ()
    }
    refs.toVector
  }
}
