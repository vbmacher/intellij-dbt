package com.github.vbmacher.intellij.dbt.navigation.sql

import com.github.vbmacher.intellij.dbt.navigation.{DbtInfoSchema, DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider, DbtResolution}
import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtRoot, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager}
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.{ASTNodeAccess, CCJSqlParserUtil, CCJSqlParserTokenManager, SimpleCharStream, StringProvider, Token}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.expression.{Expression, ExpressionVisitorAdapter, StringValue}
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Relations, columns and aliases of SQL resources; the Jinja references found before resolve template relations. */
private[sql] final class DbtSqlNavigation extends DbtReferenceProvider {
  override def relationRanges(sql: String): Vector[TextRange] = DbtSqlRelations.ranges(sql)
  override def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = DbtSqlNavigation.projectedColumns(sql)
  override def withPrefix(text: String, end: Int): Option[String] = DbtSqlNavigation.withPrefix(text, end)

  override def sqlAnalysis(file: PsiFile, workspace: DbtWorkspace, completionOffset: Option[Int]): Option[com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis] =
    DbtSqlAnalyzer.analyze(file, workspace, completionOffset)

  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] =
    DbtSqlNavigation.references(context.file, context.workspace, context.root, context.found)
}

/** Columns projected by the SELECT of SQL models, unless declared by their properties. */
private[sql] final class DbtSqlProjections extends DbtDeclarationSource {
  override def declarations(w: DbtWorkspace): Vector[DbtSymbol] = {
    val resources = w.symbols.filter(s => s.kind == "model" && s.isCode)
    val declared = w.symbols.filter(_.kind == "column").map(s => (s.namespace, w.root(s.file), s.owner, s.name, s.version)).toSet
    resources.flatMap(DbtSqlNavigation.projections).filterNot(c =>
      declared((c.namespace, w.root(c.file), c.owner, c.name, c.version)))
  }
}

/** SQL syntax comes exclusively from JSqlParser; templates are masked without changing offsets. */
private[sql] object DbtSqlNavigation {
  private val ProjectionCache = Key.create[CachedValue[Vector[(String, TextRange)]]]("dbt.sql.projections")
  /** Templates whose control flow would make the masked SQL meaningless. */
  private val ControlTags = Set("for", "if", "macro", "test", "snapshot", "materialization")
  private val InfoSchemaKinds = Map("models" -> "model", "seeds" -> "seed", "snapshots" -> "snapshot", "functions" -> "function",
    "analyses" -> "analysis", "checks" -> "check")

  private def sqlFile(file: PsiFile): Boolean = file.getVirtualFile.getExtension == "sql" && file.getTextLength <= 1000000

  /** The SQL with every template blanked out in place; each expression becomes a `_dbt<index>` identifier. */
  private[sql] def masked(text: String, blocks: Vector[DbtSyntax.Block]): Option[String] =
    if (blocks.exists(_.tagName.exists(ControlTags))) None
    else {
      val chars = text.toCharArray
      blocks.zipWithIndex.foreach { case (b, i) =>
        val start = b.range.getStartOffset
        start.until(b.range.getEndOffset).foreach(p => if (chars(p) != '\n' && chars(p) != '\r') chars(p) = ' ')
        val configuration = DbtSyntax.calls(b).exists(c => Set("config", "builtins.config")(c.name) && c.closed &&
          b.tokens.headOption.exists(_.range.getStartOffset == c.range.getStartOffset) &&
          b.tokens.lastOption.exists(_.range.getEndOffset == c.expressionRange.getEndOffset))
        if (!b.tag && !configuration) marker(i).zipWithIndex.foreach { case (c, n) => if (start + n < b.range.getEndOffset) chars(start + n) = c }
      }
      Some(new String(chars))
    }

  private def marker(index: Int): String = s"_dbt$index"

  def withPrefix(text: String, end: Int): Option[String] = {
    if (end < 0 || end > text.length || end > 1000000) return None
    val before = text.substring(0, end)
    val chars = before.toCharArray
    DbtSyntax.blocks(text).filter(_.range.getEndOffset <= end).foreach(b =>
      b.range.getStartOffset.until(b.range.getEndOffset).foreach(i => if (chars(i) != '\n' && chars(i) != '\r') chars(i) = ' '))
    tokens(new String(chars)).lastOption.filter(_.image.equalsIgnoreCase("with"))
      .map(token => text.substring(0, DbtSqlRelations.offset(before, token)))
  }

  private def parse(sql: String): Option[Statement] =
    try Some(CCJSqlParserUtil.parse(sql, p => p.withTimeOut(1000))) catch { case _: JSQLParserException => None }

  def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = {
    def safeFrom(item: FromItem): Boolean = item match {
      case p: ParenthesedSelect => safe(p.getSelect)
      case p: ParenthesedFromItem => safeFrom(p.getFromItem) && Option(p.getJoins).toVector.flatMap(_.asScala).forall(j => safeFrom(j.getFromItem))
      case _ => true
    }
    def safe(s: Select): Boolean = Option(s.getWithItemsList).toVector.flatMap(_.asScala).forall(i => i.getParenthesedStatement match {
      case p: ParenthesedSelect => safe(p.getSelect)
      case _ => false
    }) && (s match {
      case p: ParenthesedSelect => safe(p.getSelect)
      case sets: SetOperationList => Option(sets.getSelects).toVector.flatMap(_.asScala).forall(safe)
      case p: PlainSelect => Option(p.getIntoTables).forall(_.isEmpty) && p.getIntoTempTable == null && p.getMySqlSelectIntoClause == null &&
        safeFrom(p.getFromItem) && Option(p.getJoins).toVector.flatMap(_.asScala).forall(j => safeFrom(j.getFromItem))
      case _ => false
    })
    def select(s: Select): (Vector[String], Boolean) = s match {
      case p: ParenthesedSelect => select(p.getSelect)
      case sets: SetOperationList => Option(sets.getSelects).toVector.flatMap(_.asScala).headOption.map(select).getOrElse(Vector.empty -> false)
      case p: PlainSelect =>
        val items = Option(p.getSelectItems).toVector.flatMap(_.asScala)
        val names = items.flatMap(item => Option(item.getAlias).map(_.getUnquotedName).orElse(item.getExpression match {
          case c: Column => Some(c.getUnquotedColumnName)
          case _ => None
        })).filterNot(_.startsWith("_dbt"))
        names -> (names.size == items.size)
      case _ => Vector.empty -> false
    }
    if (sql.length > 1000000) None else masked(sql, DbtSyntax.blocks(sql)).filter { query => scala.util.Try {
      val parsedTokens = tokens(query)
      !parsedTokens.zipWithIndex.exists { case (token, index) => token.image == ";" && index != parsedTokens.size - 1 }
    }.getOrElse(false) }.flatMap(parse).collect { case s: Select if safe(s) => select(s) }
  }

  def projections(resource: DbtSymbol): Vector[DbtSymbol] = {
    val file = resource.file
    if (!sqlFile(file)) return Vector.empty
    val ranges = CachedValuesManager.getManager(file.getProject).getCachedValue(file, ProjectionCache,
      () => CachedValueProvider.Result.create(projectionRanges(file), file), false)
    ranges.map { case (name, range) =>
      DbtSymbol("column", name, resource.namespace, file, range, resource.name, resource.version, Map("projection" -> "true"))
    }
  }

  /** Parsing depends only on this file; resource identity and declared columns are applied by the caller. */
  private def projectionRanges(file: PsiFile): Vector[(String, TextRange)] = {
    val source = file.getText
    masked(source, DbtSyntax.blocks(source)).flatMap(parse) match {
      case Some(select: PlainSelect) => Option(select.getSelectItems).toVector.flatMap(_.asScala).flatMap { item =>
        val name = Option(item.getAlias).map(_.getUnquotedName).orElse(item.getExpression match {
          case c: Column => Some(c.getUnquotedColumnName)
          case _ => None
        })
        val token = Option(item.getASTNode).map(_.jjtGetLastToken())
        for (n <- name.toVector; t <- token.toVector if !n.startsWith("_dbt")) yield {
          n -> new TextRange(DbtSqlRelations.offset(source, t), DbtSqlRelations.offset(source, t, end = true))
        }
      }
      case _ => Vector.empty
    }
  }

  private final case class Relation(target: Vector[PsiElement], columns: Map[String, Vector[PsiElement]], infoView: Option[String] = None)

  def references(file: PsiFile, w: DbtWorkspace, root: DbtRoot, jinja: Vector[DbtNavigationReference]): Vector[DbtNavigationReference] = {
    if (!sqlFile(file)) return Vector.empty
    val text = file.getText
    val blocks = DbtSyntax.blocks(text)
    val sql = masked(text, blocks).getOrElse(return Vector.empty)
    val parsed = parse(sql).getOrElse(return Vector.empty)
    val templateRelations = blocks.zipWithIndex.collect { case (b, i) if !b.tag => marker(i) -> templateRelation(file, w, root, b, jinja) }.toMap
    val refs = ArrayBuffer.empty[DbtNavigationReference]

    def offset(t: Token, end: Boolean = false): Int = DbtSqlRelations.offset(text, t, end)

    def range(t: Token): TextRange = new TextRange(offset(t), offset(t, end = true))

    val allTokens = tokens(sql)

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
        Option(item.getParenthesedStatement).collect { case body: ParenthesedSelect => body }.foreach { body =>
          val explicitColumns = Option(item.getWithItemList).toVector.flatMap(_.asScala)
          val first = explicitColumns.headOption.flatMap(item => nodeTokens(item).headOption).orElse(nodeTokens(body).headOption).map(offset(_)).getOrElse(0)
          val target = declaration(item.getUnquotedAliasName, first, before = true).toVector.map(symbol("cte", item.getUnquotedAliasName, _))
          val columns = select(body.getSelect, ctes)
          ctes += item.getUnquotedAliasName.toLowerCase -> Relation(target, if (explicitColumns.nonEmpty) Map.empty else columns)
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
          Option(p.getJoins).toVector.flatMap(_.asScala).foreach(j => from(j.getFromItem))
          val projections = mutable.Map.empty[String, Vector[PsiElement]]

          def columnTargets(c: Column): Vector[PsiElement] = {
            val name = c.getUnquotedColumnName.toLowerCase
            Option(c.getTable).filter(_.getName != null).map(t => relations.get(t.getUnquotedName.toLowerCase).toVector.flatMap(_.columns.getOrElse(name, Vector.empty)))
              .getOrElse {
                val candidates = relations.valuesIterator.flatMap(_.columns.get(name)).toVector
                if (candidates.size == 1) candidates.head else Vector.empty
              }
          }

          def expression(e: Expression, aliases: Boolean = false): Unit = if (e != null) e.accept(new ExpressionVisitorAdapter[Void] {
            override def visit[S](equals: EqualsTo, context: S): Void = {
              (equals.getLeftExpression, equals.getRightExpression) match {
                case (column: Column, value: StringValue) if column.getUnquotedColumnName == "name" =>
                  val relation = Option(column.getTable).filter(_.getName != null)
                    .flatMap(t => relations.get(t.getUnquotedName.toLowerCase)).orElse(if (relations.size == 1) relations.values.headOption else None)
                  val kinds = relation.flatMap(_.infoView).flatMap(InfoSchemaKinds.get)
                  val targets = kinds.toVector.flatMap(k => w.declarations(Set(k), value.getValue, root)
                    .filterNot(_.isProperty).map(_.element))
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
          expression(p.getWhere)
          expression(p.getHaving)
          Option(p.getJoins).toVector.flatMap(_.asScala).foreach(j => Option(j.getOnExpressions).toVector.flatMap(_.asScala).foreach(expression(_)))
          Option(p.getOrderByElements).toVector.flatMap(_.asScala).foreach(o => expression(o.getExpression, aliases = true))
          projections.toMap
        case _ => Map.empty
      }
    }

    parsed match {
      case s: Select => select(s, Map.empty)
      case _ => ()
    }
    refs.toVector
  }

  /** The relation and columns an expression template such as `{{ ref('x') }}` stands for. */
  private def templateRelation(file: PsiFile, w: DbtWorkspace, root: DbtRoot, b: DbtSyntax.Block,
    jinja: Vector[DbtNavigationReference]): Relation = {
    val info = if (DbtInfoSchema.enabled(root, file)) DbtSyntax.calls(b).find(_.name == "info_schema")
      .flatMap(_.arguments.headOption.flatMap(_.literal)).filter(DbtInfoSchema.views.contains) else None
    info.map(view => Relation(Vector(DbtResolution.documentation(file, view, "info-schema", root)), DbtInfoSchema.columns(file, root, view), Some(view)))
      .getOrElse {
        val candidates = jinja.filter(ref => b.range.contains(ref.getRangeInElement)).flatMap(_.candidates).collect {
          case e: DbtSymbolElement if DbtSymbol.RelationKinds(e.symbol.kind) => e.symbol
        }.distinct
        val columns = candidates.flatMap(w.columns).groupBy(_.name.toLowerCase).view.mapValues(_.map(_.element: PsiElement)).toMap
        Relation(candidates.map(_.element), columns)
      }
  }

  private[sql] def nodeTokens(node: ASTNodeAccess): Vector[Token] = Option(node.getASTNode).map { n =>
    val last = n.jjtGetLastToken()
    val result = ArrayBuffer.empty[Token]
    var t = n.jjtGetFirstToken()
    while (t != null && !result.lastOption.contains(last)) {
      result += t
      t = t.next
    }
    result.toVector
  }.getOrElse(Vector.empty)

  private[sql] def tokens(sql: String): Vector[Token] = {
    val manager = new CCJSqlParserTokenManager(new SimpleCharStream(new StringProvider(sql)))
    Iterator.continually(manager.getNextToken()).takeWhile(_.kind != 0).toVector
  }
}
