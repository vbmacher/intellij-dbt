package com.github.vbmacher.intellij.dbt.navigation.sql

import com.github.vbmacher.intellij.dbt.navigation._
import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtRoot, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager}
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.{CastExpression, Expression, ExpressionVisitorAdapter, StringValue}
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.parser._
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select._
import net.sf.jsqlparser.util.TablesNamesFinder

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Relations, columns and aliases of SQL resources; the Jinja references found before resolve template relations. */
private[sql] final class DbtSqlNavigation extends DbtReferenceProvider {
  override def relationRanges(sql: String): Vector[TextRange] = DbtSqlNavigation.relationRanges(sql)

  override def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = DbtSqlNavigation.projectedColumns(sql)

  override def withPrefix(text: String, end: Int): Option[String] = DbtSqlNavigation.withPrefix(text, end)

  override def sqlAnalysis(file: PsiFile, workspace: DbtWorkspace, completionOffset: Option[Int]): Option[Either[String, DbtSqlAnalysis]] =
    Option(file.getVirtualFile).filter(_.getExtension == "sql").map(_ => DbtSqlAnalyzer.analyze(file, workspace, completionOffset))

  override def sqlSuppressionLayout(file: PsiFile): Option[DbtSqlSuppressionLayout] =
    Option(file.getVirtualFile).filter(_.getExtension == "sql").map(_ => DbtSqlNavigation.suppressionLayout(file))

  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] =
    DbtSqlNavigation.references(context.file, context.workspace, context.root, context.found)
}

/** Parameters used in the body of SQL functions. */
private[sql] final class DbtFunctionParameters extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] = {
    val (file, w) = (context.file, context.workspace)
    if (file.getVirtualFile.getExtension != "sql" || file.getText.contains("{{")) return Vector.empty
    val functions = w.codeSymbols(file).filter(_.kind == "function")
    if (functions.isEmpty) return Vector.empty
    val params = functions.flatMap(f => w.symbols.filter(p => p.kind == "parameter" && p.owner == f.name && p.namespace == f.namespace &&
      w.root(p.file) == w.root(f.file) && p.attributes.get("overload") == f.attributes.get("overload")))
    val text = file.getText
    val expression = try CCJSqlParserUtil.parseExpression(text) catch {
      case _: JSQLParserException => return Vector.empty
    }
    val result = ArrayBuffer.empty[DbtNavigationReference]
    expression.accept(new ExpressionVisitorAdapter[Void] {
      override def visit[S](column: Column, context: S): Void = {
        Option(column.getASTNode).foreach { n =>
          val matches = params.filter(_.name == column.getUnquotedColumnName)
          if (matches.nonEmpty && Option(column.getTable).forall(_.getName == null)) result += new DbtNavigationReference(file,
            DbtSqlNavigation.range(text, n.jjtGetLastToken()), matches.map(_.element), column.getUnquotedColumnName)
        }
        null
      }
    }, null)
    result.toVector
  }
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
  private val Directive = "(?s)--\\s*(file:\\s*)?noinspection\\s+([A-Za-z_0-9.-]+(?:\\s*,\\s*[A-Za-z_0-9.-]+)*)(?:\\s.*)?".r

  private def sqlFile(file: PsiFile): Boolean = file.getVirtualFile.getExtension == "sql" && file.getTextLength <= 1000000

  def all[A](xs: java.util.Collection[A]): Vector[A] = Option(xs).toVector.flatMap(_.asScala)

  /** The text offset of a token; JSqlParser counts a tab as a single column. */
  def offset(text: String, t: Token, end: Boolean = false): Int = {
    val (line, column) = if (end) (t.endLine, t.endColumn) else (t.beginLine, t.beginColumn)
    val lineStart = Iterator.iterate(0)(i => text.indexOf('\n', i) + 1).drop(line - 1).next()
    math.min(text.length, lineStart + column - (if (end) 0 else 1))
  }

  def range(text: String, t: Token): TextRange = new TextRange(offset(text, t), offset(text, t, end = true))

  def parse(sql: String): Option[Statement] = parseResult(sql).toOption

  def parseResult(sql: String): Either[String, Statement] =
    try Right(CCJSqlParserUtil.parse(sql, p => p.withTimeOut(1000))) catch {
      case error: JSQLParserException =>
        val causes = Iterator.iterate(error: Throwable)(_.getCause).takeWhile(_ != null).toVector
        if (causes.exists(_.isInstanceOf[java.util.concurrent.TimeoutException]))
          Left("SQL analysis exceeded the 1-second parsing limit.")
        else {
          val position = causes.collectFirst { case e: ParseException => e }.flatMap(e => Option(e.currentToken))
            .flatMap(t => Option(t.next)).filter(t => t.beginLine > 0 && t.beginColumn > 0)
            .map(t => s"\nThe parser stopped at line ${t.beginLine}, column ${t.beginColumn}.").getOrElse("")
          Left("The plugin could not parse this model's SQL." + position +
            "\nThe SQL may be incomplete or use syntax the plugin does not support.")
        }
    }

  /** The output name of a select item: its alias or its column. */
  def itemName(item: SelectItem[_]): Option[String] = Option(item.getAlias).map(_.getUnquotedName).orElse(item.getExpression match {
    case c: Column => Some(c.getUnquotedColumnName)
    case _ => None
  })

  /** The last token of a node naming an alias. */
  def aliasToken(node: ASTNodeAccess, alias: String): Option[Token] =
    nodeTokens(node).reverse.find(_.image.replace("\"", "").equalsIgnoreCase(alias))

  /** Blanks a range in place, keeping line breaks. */
  private def blank(chars: Array[Char], range: TextRange): Unit =
    range.getStartOffset.until(range.getEndOffset).foreach(i => if (chars(i) != '\n' && chars(i) != '\r') chars(i) = ' ')

  def marker(index: Int): String = s"_dbt$index"

  /** The SQL with every template blanked out in place; each expression becomes a `_dbt<index>` identifier. */
  def masked(text: String, blocks: Vector[DbtSyntax.Block]): Option[String] =
    if (blocks.exists(_.tagName.exists(ControlTags))) None
    else {
      val chars = text.toCharArray
      blocks.zipWithIndex.foreach { case (b, i) =>
        val start = b.range.getStartOffset
        blank(chars, b.range)
        val configuration = DbtSyntax.calls(b).exists(c => Set("config", "builtins.config")(c.name) && c.closed &&
          b.tokens.headOption.exists(_.range.getStartOffset == c.range.getStartOffset) &&
          b.tokens.lastOption.exists(_.range.getEndOffset == c.expressionRange.getEndOffset))
        if (!b.tag && !configuration) marker(i).zipWithIndex.foreach { case (c, n) => if (start + n < b.range.getEndOffset) chars(start + n) = c }
      }
      Some(new String(chars))
    }

  def withPrefix(text: String, end: Int): Option[String] = {
    if (end < 0 || end > text.length || end > 1000000) return None
    val before = text.substring(0, end)
    val chars = before.toCharArray
    DbtSyntax.blocks(text).filter(_.range.getEndOffset <= end).foreach(b => blank(chars, b.range))
    tokens(new String(chars)).lastOption.filter(_.image.equalsIgnoreCase("with"))
      .map(token => text.substring(0, offset(before, token)))
  }

  /** Only AST Table nodes are replaced; alias.column qualifiers and literals are never relations. */
  def relationRanges(sql: String): Vector[TextRange] = {
    def parsed(sql: String): Option[Vector[TextRange]] = {
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
            while (remaining > 0 && last.next != null) {
              last = last.next;
              remaining -= 1
            }
            result += new TextRange(offset(sql, first), offset(sql, last, end = true))
          }
          super.visit(table, context)
        }
      }
      try {
        val statements = CCJSqlParserUtil.parseStatements(sql, p => p.withTimeOut(1000))
        statements.forEach(statement => visitor.getTables(statement))
        Some(result.toVector.distinct)
      } catch {
        case _: JSQLParserException => None
      }
    }

    Vector("", "select ", "select * ").iterator.flatMap { prefix =>
      parsed(prefix + sql).map(_.filter(_.getStartOffset >= prefix.length).map(_.shiftRight(-prefix.length)))
    }.take(1).toVector.flatten
  }

  /** SQL tokens distinguish statement boundaries and real comments from quoted comment text. */
  def suppressionLayout(file: PsiFile): DbtSqlSuppressionLayout = {
    val text = file.getText
    val blocks = DbtSyntax.inFile(file)
    val chars = text.toCharArray
    (blocks.map(_.range) ++ "(?s)\\{#.*?#\\}".r.findAllMatchIn(text).map(m => new TextRange(m.start, m.end))).foreach(blank(chars, _))
    val stream = new SimpleCharStream(new StringProvider(new String(chars)))
    stream.setTabSize(1)
    val lexer = new CCJSqlParserTokenManager(stream)
    val starts = Vector(0) ++ text.indices.filter(text(_) == '\n').map(_ + 1)

    def range(token: Token): TextRange = new TextRange(
      math.min(text.length, starts.lift(token.beginLine - 1).getOrElse(0) + token.beginColumn - 1),
      math.min(text.length, starts.lift(token.endLine - 1).getOrElse(0) + token.endColumn))

    val tokens = ArrayBuffer.empty[(String, TextRange)]
    val comments = ArrayBuffer.empty[DbtSqlSuppressionComment]
    try {
      var done = false
      while (!done) {
        ProgressManager.checkCanceled()
        val token = lexer.getNextToken()
        var comment = token.specialToken
        while (comment != null) {
          comment.image.trim match {
            case Directive(prefix, ids) => comments += DbtSqlSuppressionComment(range(comment), ids, prefix != null)
            case _ => ()
          }
          comment = comment.specialToken
        }
        done = token.kind == 0
        if (!done) tokens += token.image -> range(token)
      }
    } catch {
      case _: TokenMgrException => ()
    } // Keep proven boundaries preceding unfinished SQL.
    val ends = tokens.collect { case (";", r) => r.getEndOffset }.toVector :+ text.length
    val statements = (Vector(0) ++ ends).sliding(2).collect { case Vector(start, end) if end > start =>
      val first = tokens.iterator.map(_._2.getStartOffset).find(i => i >= start && i < end)
      val template = blocks.iterator.map(_.range.getStartOffset).find(i => i >= start && i < end)
      DbtSqlSuppressionStatement(new TextRange(start, end), (first.toVector ++ template).minOption.getOrElse(end))
    }.toVector
    DbtSqlSuppressionLayout(statements, comments.toVector)
  }

  def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = {
    def safeFrom(item: FromItem): Boolean = item match {
      case p: ParenthesedSelect => safe(p.getSelect)
      case p: ParenthesedFromItem => safeFrom(p.getFromItem) && all(p.getJoins).forall(j => safeFrom(j.getFromItem))
      case _ => true
    }

    def safe(s: Select): Boolean = all(s.getWithItemsList).forall(i => i.getParenthesedStatement match {
      case p: ParenthesedSelect => safe(p.getSelect)
      case _ => false
    }) && (s match {
      case p: ParenthesedSelect => safe(p.getSelect)
      case sets: SetOperationList => all(sets.getSelects).forall(safe)
      case p: PlainSelect => Option(p.getIntoTables).forall(_.isEmpty) && p.getIntoTempTable == null && p.getMySqlSelectIntoClause == null &&
        safeFrom(p.getFromItem) && all(p.getJoins).forall(j => safeFrom(j.getFromItem))
      case _ => false
    })

    def select(s: Select): (Vector[String], Boolean) = s match {
      case p: ParenthesedSelect => select(p.getSelect)
      case sets: SetOperationList => all(sets.getSelects).headOption.map(select).getOrElse(Vector.empty -> false)
      case p: PlainSelect =>
        val items = all(p.getSelectItems)
        val names = items.flatMap(itemName(_)).filterNot(_.startsWith("_dbt"))
        names -> (names.size == items.size)
      case _ => Vector.empty -> false
    }

    if (sql.length > 1000000) None else masked(sql, DbtSyntax.blocks(sql)).filter { query =>
      scala.util.Try {
        val parsedTokens = tokens(query)
        !parsedTokens.zipWithIndex.exists { case (token, index) => token.image == ";" && index != parsedTokens.size - 1 }
      }.getOrElse(false)
    }.flatMap(parse).collect { case s: Select if safe(s) => select(s) }
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
      case Some(select: PlainSelect) => all(select.getSelectItems).flatMap { item =>
        val token = Option(item.getASTNode).map(_.jjtGetLastToken())
        for (n <- itemName(item).toVector; t <- token.toVector if !n.startsWith("_dbt")) yield n -> range(source, t)
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
    val allTokens = tokens(sql)

    def declaration(name: String, near: Int, before: Boolean): Option[Token] = {
      val matches = allTokens.filter(t => t.image.replace("\"", "").replace("`", "").equalsIgnoreCase(name))
      if (before) matches.filter(t => offset(text, t) < near).lastOption else matches.find(t => offset(text, t) >= near)
    }

    def symbol(kind: String, name: String, t: Token): PsiElement = DbtSymbol(kind, name, root.name, file, range(text, t)).element

    def add(t: Token, targets: Vector[PsiElement]): Unit = {
      val rr = range(text, t)
      if (!blocks.exists(_.range.intersects(rr)) && targets.nonEmpty) refs += new DbtNavigationReference(file, rr, targets.distinct, t.image)
    }

    def select(s: Select, inherited: Map[String, Relation]): Map[String, Vector[PsiElement]] = {
      ProgressManager.checkCanceled()
      var ctes = inherited
      all(s.getWithItemsList).foreach { item =>
        Option(item.getParenthesedStatement).collect { case body: ParenthesedSelect => body }.foreach { body =>
          val explicitColumns = all(item.getWithItemList)
          val first = explicitColumns.headOption.flatMap(item => nodeTokens(item).headOption).orElse(nodeTokens(body).headOption)
            .map(offset(text, _)).getOrElse(0)
          val target = declaration(item.getUnquotedAliasName, first, before = true).toVector.map(symbol("cte", item.getUnquotedAliasName, _))
          val columns = select(body.getSelect, ctes)
          ctes += item.getUnquotedAliasName.toLowerCase -> Relation(target, if (explicitColumns.nonEmpty) Map.empty else columns)
        }
      }
      s match {
        case p: ParenthesedSelect => select(p.getSelect, ctes)
        case sets: SetOperationList => all(sets.getSelects).headOption.map(select(_, ctes)).getOrElse(Map.empty)
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
                case n: ASTNodeAccess => aliasToken(n, a.getUnquotedName).toVector
                case _ => Vector.empty
              }
            }.map(t => symbol("sql_alias", alias.get.getUnquotedName, t))
            key.foreach(k => relations += k.toLowerCase -> relation.copy(target = if (aliasTarget.nonEmpty) aliasTarget else relation.target))
          }

          from(p.getFromItem)
          all(p.getJoins).foreach(j => from(j.getFromItem))
          val projections = mutable.Map.empty[String, Vector[PsiElement]]

          def columnTargets(c: Column): Vector[PsiElement] = {
            val name = c.getUnquotedColumnName.toLowerCase
            Option(c.getTable).filter(_.getName != null)
              .map(t => relations.get(t.getUnquotedName.toLowerCase).toVector.flatMap(_.columns.getOrElse(name, Vector.empty)))
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
                      val rr = range(text, t)
                      refs += new DbtNavigationReference(file, new TextRange(rr.getStartOffset + 1, rr.getEndOffset - 1), targets, value.getValue)
                    }
                  }
                case _ => ()
              }
              super.visit(equals, context)
            }

            override def visit[S](c: Column, context: S): Void = {
              val ts = nodeTokens(c)
              val targets = if (aliases && Option(c.getTable).forall(_.getName == null))
                projections.getOrElse(c.getUnquotedColumnName.toLowerCase, columnTargets(c)) else columnTargets(c)
              ts.lastOption.foreach(add(_, targets))
              if (ts.size >= 3) ts.headOption.foreach(t => add(t, relations.get(c.getUnquotedTableName.toLowerCase).toVector.flatMap(_.target)))
              null
            }
          }, null)

          all(p.getSelectItems).foreach { item =>
            expression(item.getExpression.asInstanceOf[Expression])
            val target = Option(item.getAlias) match {
              case Some(a) => aliasToken(item, a.getUnquotedName).toVector.map(symbol("sql_projection", a.getUnquotedName, _))
              case None => item.getExpression match {
                case c: Column => columnTargets(c)
                case _ => Vector.empty
              }
            }
            itemName(item).foreach(n => projections(n.toLowerCase) = target)
          }
          expression(p.getWhere)
          expression(p.getHaving)
          all(p.getJoins).foreach(j => all(j.getOnExpressions).foreach(expression(_)))
          all(p.getOrderByElements).foreach(o => expression(o.getExpression, aliases = true))
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

  def nodeTokens(node: ASTNodeAccess): Vector[Token] = Option(node.getASTNode).map { n =>
    val last = n.jjtGetLastToken()
    val result = ArrayBuffer.empty[Token]
    var t = n.jjtGetFirstToken()
    while (t != null && !result.lastOption.contains(last)) {
      result += t
      t = t.next
    }
    result.toVector
  }.getOrElse(Vector.empty)

  def tokens(sql: String): Vector[Token] = {
    val manager = new CCJSqlParserTokenManager(new SimpleCharStream(new StringProvider(sql)))
    Iterator.continually(manager.getNextToken()).takeWhile(_.kind != 0).toVector
  }
}

/** A conservative, offset-preserving projection analysis. All dialect syntax is parsed by JSqlParser. */
private object DbtSqlAnalyzer {

  import DbtSqlNavigation.{aliasToken, all, itemName, nodeTokens, offset, parse, tokens}

  private final case class Relation(columns: Vector[DbtSymbol], known: Boolean, cte: Option[(String, TextRange)] = None)

  private val ModifierProblem = "Wildcard EXCEPT, EXCLUDE or REPLACE modifiers cannot be expanded safely. " +
    "Write the required columns explicitly, or remove the modifier before expanding."

  def analyze(file: PsiFile, w: DbtWorkspace, completionOffset: Option[Int]): Either[String, DbtSqlAnalysis] = {
    if (file.getTextLength > 1000000) return Left("This file exceeds the SQL analysis limit of 1,000,000 characters.")
    val root = w.root(file).filter(r => r.resource(file) && !r.excluded(file.getVirtualFile))
      .getOrElse(return Left("This file is outside the dbt project's supported SQL resource paths or is excluded."))
    val text = file.getText
    val blocks = DbtSyntax.inFile(file)
    val sql = DbtSqlNavigation.masked(text, blocks).getOrElse(return Right(DbtSqlAnalysis(Vector.empty, Vector.empty,
      Vector.empty, Vector.empty, Vector("Dynamic Jinja control flow; column analysis unavailable."))))

    def span(t: Token): TextRange = DbtSqlNavigation.range(text, t)

    val attempt = DbtSqlNavigation.parseResult(sql)
    val parsed = attempt.toOption.orElse(completionOffset.filter(i => i > 0 && i <= sql.length).flatMap { i =>
      parse(sql.updated(i - 1, if (sql(i - 1) == '.') ' ' else '*'))
    }).getOrElse {
      // A dialect modifier may be rejected by the parser. Parsing only through
      // the star still proves a projection wildcard, without accepting a rewrite.
      val unsupported = scala.util.Try(tokens(sql)).toOption.toVector.flatten.sliding(2).flatMap {
        case Vector(star, modifier) if star.image == "*" && Set("EXCEPT", "EXCLUDE", "REPLACE")(modifier.image.toUpperCase(java.util.Locale.ROOT)) =>
          ProgressManager.checkCanceled()
          val end = offset(text, star, end = true)
          parse(sql.substring(0, end)).collect { case select: PlainSelect => select }.toVector.flatMap { select =>
            all(select.getSelectItems).lastOption.toVector.flatMap { item =>
              item.getExpression match {
                case expression: AllColumns =>
                  val ts = nodeTokens(expression)
                  ts.headOption.filter(_ => ts.lastOption.exists(t => offset(text, t, end = true) == end))
                    .map(first => DbtSqlProblem(new TextRange(offset(text, first), end), ModifierProblem))
                case _ => None
              }
            }
          }
        case _ => Vector.empty
      }.toVector
      return if (unsupported.nonEmpty) Right(DbtSqlAnalysis(Vector.empty, Vector.empty, Vector.empty, Vector.empty,
        Vector("Wildcard modifier is unsupported by SQL analysis."), wildcardProblems = unsupported)) else
        Left(attempt.left.toOption.getOrElse("The plugin could not parse this model's SQL."))
    }
    val references = DbtNavigation.inFile(file, includeSelections = false)
    val issues = ArrayBuffer.empty[String]
    val allTokens = tokens(sql)
    if (allTokens.sliding(2).exists(ts => ts.size == 2 && ts.head.image.equalsIgnoreCase("with") && ts(1).image.equalsIgnoreCase("recursive")))
      issues += "Recursive CTE analysis is partial; query inspection is unsupported."
    val scopes = ArrayBuffer.empty[DbtSqlScope]
    val ctes = ArrayBuffer.empty[DbtCte]
    val cteReferences = ArrayBuffer.empty[(String, TextRange)]
    val wildcards = ArrayBuffer.empty[(TextRange, Vector[String])]
    val wildcardProblems = ArrayBuffer.empty[DbtSqlProblem]
    val intermediate = ArrayBuffer.empty[DbtSqlColumn]
    val problems = ArrayBuffer.empty[DbtSqlProblem]
    val deletions = ArrayBuffer.empty[(TextRange, TextRange)]
    val declarations = ArrayBuffer.empty[DbtSymbol]

    def range(node: ASTNodeAccess): TextRange = {
      val tokens = nodeTokens(node)
      if (tokens.isEmpty) TextRange.EMPTY_RANGE else new TextRange(offset(text, tokens.head), offset(text, tokens.last, end = true))
    }

    def nameRange(node: ASTNodeAccess): TextRange = nodeTokens(node).lastOption.map(span).getOrElse(TextRange.EMPTY_RANGE)

    def removable(item: TextRange, allowSoleWith: Boolean): Option[TextRange] = {
      val before = allTokens.filter(t => offset(text, t, end = true) <= item.getStartOffset).lastOption
      val after = allTokens.find(t => offset(text, t) >= item.getEndOffset)
      if (after.exists(_.image == ",")) Some(new TextRange(item.getStartOffset, offset(text, after.get, end = true)))
      else if (before.exists(t => t.image == "," || allowSoleWith && t.image.equalsIgnoreCase("with")))
        Some(new TextRange(offset(text, before.get), item.getEndOffset))
      else None
    }

    def external(table: Table): Relation = {
      val candidates = blocks.zipWithIndex.find { case (_, i) => table.getName == DbtSqlNavigation.marker(i) }.toVector.flatMap { case (b, _) =>
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
      all(s.getWithItemsList).foreach { item =>
        if (!item.getParenthesedStatement.isInstanceOf[ParenthesedSelect]) issues += "Data-modifying CTEs are unsupported."
        Option(item.getParenthesedStatement).collect { case body: ParenthesedSelect => body }.foreach { body =>
          val name = item.getUnquotedAliasName
          val explicitColumns = all(item.getWithItemList)
          val referencesStart = cteReferences.size
          val bodyColumns = select(body.getSelect, visible)
          if (explicitColumns.nonEmpty) issues += "Explicit CTE column lists are not supported by column analysis or refactoring."
          val columns = if (explicitColumns.nonEmpty) Vector.empty else bodyColumns
          // CTE output identities are retained; lineage can trace these local ranges back to their inputs.
          val bodyRange = range(body.getSelect)
          val before = explicitColumns.headOption.map(range(_).getStartOffset).getOrElse(range(body).getStartOffset)
          val nameToken = allTokens.filter(t => offset(text, t) < before)
            .filter(t => t.image.replace("\"", "").equalsIgnoreCase(name)).lastOption
          val itemRange = new TextRange(nameToken.map(offset(text, _)).getOrElse(range(body).getStartOffset), range(body).getEndOffset)
          val dependencies = cteReferences.drop(referencesStart).filterNot { case (_, declaration) => bodyRange.contains(declaration) }
            .map(_._1).distinct.toVector
          visible += name.toLowerCase -> local(columns).copy(cte = Some(name -> itemRange))
          ctes += DbtCte(name, itemRange, bodyRange, dependencies, bodyRange.substring(text))
          nameToken.foreach { t =>
            declarations += DbtSymbol("cte", name, root.name, file, span(t))
            removable(itemRange, allowSoleWith = true).foreach(delete => deletions += span(t) -> delete)
          }
        }
      }
      s match {
        case p: ParenthesedSelect => select(p.getSelect, visible)
        case sets: SetOperationList =>
          val branches = all(sets.getSelects).map(select(_, visible))
          branches.headOption.toVector.flatten.zipWithIndex.map { case (column, index) =>
            val peers = branches.flatMap(_.lift(index))
            column.copy(inputs = peers.flatMap(_.inputs).distinct, direct = false,
              complete = peers.size == branches.size && peers.forall(_.complete), expression = "set operation")
          }
        case p: PlainSelect =>
          val from = Option(p.getFromItem).toVector ++ all(p.getJoins).map(_.getFromItem)
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
            Option(f.getAlias).foreach { a =>
              f match {
                case n: ASTNodeAccess => aliasToken(n, a.getUnquotedName).foreach { token =>
                  val aliasRange = span(token)
                  declarations += DbtSymbol("sql_alias", a.getUnquotedName, root.name, file, aliasRange)
                  val previous = allTokens.filter(t => offset(text, t, end = true) <= aliasRange.getStartOffset).lastOption
                  val start = previous.filter(_.image.equalsIgnoreCase("as")).map(offset(text, _)).getOrElse(aliasRange.getStartOffset)
                  deletions += aliasRange -> new TextRange(start, aliasRange.getEndOffset)
                }
                case _ => ()
              }
            }
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

          val output = all(p.getSelectItems).flatMap { item =>
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
              val plain = expression.toString.trim.matches("(?s)([^ ]+\\.)?\\*")
              val safe = chosen.nonEmpty && chosen.forall(_._2.known) && columns.nonEmpty &&
                (qualifier.isEmpty || chosen.size == 1) &&
                !(relations.size > 1 && chosen.exists(_._1.startsWith("_dbt"))) && plain
              if (safe) wildcards += range(expression) -> chosen.flatMap { case (a, r) =>
                r.columns.map(c => (if (qualifier.nonEmpty || relations.size > 1) qualifiedAlias(a) + "." else "") + quoted(c))
              }
              else {
                issues += "Wildcard has an unknown schema or unsupported modifiers."
                val reason = if (!plain) ModifierProblem
                else if (chosen.isEmpty || (qualifier.nonEmpty && chosen.size != 1))
                  "No unique relation matches this wildcard. Use a declared relation alias, or write the required columns explicitly."
                else if (relations.size > 1 && chosen.exists(_._1.startsWith("_dbt")))
                  "This join needs explicit relation aliases before its wildcard can be expanded. Add an alias to each relation and use alias.*."
                else "A complete column list is unavailable for this relation. Add or update its model/source column declarations, " +
                  "or generate current dbt documentation for this project and target. You can also write the required columns explicitly."
                wildcardProblems += DbtSqlProblem(range(expression), reason)
              }
              columns.map(c => DbtSqlColumn(c.name, range(expression), Vector(c), direct = true, expression.toString, safe))
            } else {
              val inputs = ArrayBuffer.empty[DbtSymbol]
              var resolved = true
              expression.accept(new ExpressionVisitorAdapter[Void] {
                override def visit[S](c: Column, context: S): Void = {
                  val targets = resolve(c)
                  if (targets.isEmpty) resolved = false
                  inputs ++= targets;
                  null
                }
              }, null)
              val dataType = expression match {
                case c: Column => resolve(c).headOption.flatMap(_.attributes.get("data_type"))
                case c: CastExpression => Option(c.getColDataType).map(_.getDataType)
                case _: StringValue => Some("string")
                case _ => None
              }
              itemName(item).filterNot(_ => item.getAlias == null && blocks.exists(_.range.intersects(nameRange(item)))).toVector.map(n =>
                DbtSqlColumn(n, nameRange(item), inputs.toVector.distinct, expression.isInstanceOf[Column], range(expression).substring(text),
                  resolved && complete, dataType, item.getAlias != null))
            }
          }
          output.filter(_.outputAlias).foreach(c => declarations += DbtSymbol("sql_projection", c.name, root.name, file, c.range))
          all(p.getSelectItems).foreach(item => removable(range(item), allowSoleWith = false).foreach(delete => deletions += nameRange(item) -> delete))
          val contextInputs = ArrayBuffer.empty[(DbtSymbol, String)]

          def context(expression: Expression, kind: String): Unit = if (expression != null) expression.accept(new ExpressionVisitorAdapter[Void] {
            override def visit[S](c: Column, ignored: S): Void = {
              val inputs = resolve(c, report = false)
              val aliases = if (inputs.isEmpty && Option(c.getTable).forall(_.getName == null))
                output.filter(_.name.equalsIgnoreCase(c.getUnquotedColumnName)).flatMap(_.inputs) else Vector.empty
              contextInputs ++= (inputs ++ aliases).map(_ -> kind);
              null
            }
          }, null)

          context(p.getWhere, "filter");
          context(p.getHaving, "filter")
          all(p.getJoins).foreach(j => all(j.getOnExpressions).foreach(context(_, "join")))
          output.map(c => c.copy(contextInputs = contextInputs.toVector.distinct))
        case _ => issues += "Unsupported SQL query shape."; Vector.empty
      }
    }

    parsed match {
      case s: Select =>
        val columns = select(s, Map.empty)
        Right(DbtSqlAnalysis(columns, scopes.toVector, ctes.toVector, wildcards.toVector,
          issues.toVector.distinct ++ (if (scopes.exists(!_.complete)) Vector("Some relation schemas are unavailable.") else Vector.empty),
          intermediate.toVector, problems.toVector.distinct, deletions.toVector, declarations.toVector, wildcardProblems.toVector))
      case _ => Left("SQL analysis requires a SELECT query. This file contains a different SQL statement.")
    }
  }
}
