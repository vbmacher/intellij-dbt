package com.github.vbmacher.intellij.dbt.syntax

import com.hubspot.jinjava.JinjavaConfig
import com.hubspot.jinjava.el.ext.ExtendedScanner
import com.hubspot.jinjava.tree.parse.{ExpressionToken, TagToken, TokenScanner, UnclosedToken}
import jinjava.de.odysseus.el.tree.impl.Scanner.{ScanException, Symbol}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiRecursiveElementWalkingVisitor}
import org.jetbrains.yaml.psi.{YAMLFile, YAMLScalar}
import scala.collection.mutable.ArrayBuffer

/** Library tokens and decoded host offsets; never evaluates a template. */
private[dbt] object DbtSyntax {
  final case class Token(image: String, range: TextRange, string: Boolean = false)

  final case class Block(tokens: Vector[Token], range: TextRange, tag: Boolean) {
    def tagName: Option[String] = if (tag) tokens.headOption.map(_.image) else None

    def isTag(name: String): Boolean = tagName.contains(name)
  }

  /** Tags opening a lexical scope; each is closed by the same tag prefixed with `end`. */
  val ScopeTags: Set[String] = Set("for", "macro", "test", "call", "with", "block")
  val ScopeEndTags: Set[String] = ScopeTags.map("end" + _)

  def isIdentifier(text: String): Boolean = text.matches("[A-Za-z_][A-Za-z_0-9]*")

  private val opening = Set("(", "[", "{")
  private val closing = Set(")", "]", "}")

  /** Indices of the names assigned at the top level of a `with` tag, such as `x` and `y` in `with x = 1, y = (2)`. */
  def assignedNames(tokens: Vector[Token]): Vector[Int] = {
    var depth = 0
    tokens.indices.filter { i =>
      if (opening(tokens(i).image)) depth += 1
      if (closing(tokens(i).image)) depth -= 1
      i > 0 && depth == 0 && tokens.lift(i + 1).exists(_.image == "=")
    }.toVector
  }

  /** Whether a range lies inside the block opened at `start` and closed by the first following `endTag`. */
  def encloses(blocks: Vector[Block], start: Int, endTag: String, range: TextRange): Boolean =
    start < range.getStartOffset && blocks.find(b => b.isTag(endTag) && b.range.getStartOffset > start)
      .exists(_.range.getEndOffset > range.getEndOffset)

  /** The `tag` block containing a range and the first `end` block following it. */
  def tagged(blocks: Vector[Block], tag: String, range: TextRange): Option[(Block, Block)] =
    blocks.find(b => b.isTag(tag) && b.range.contains(range)).flatMap(start =>
      blocks.find(b => b.isTag("end" + tag) && b.range.getStartOffset > start.range.getStartOffset).map(start -> _))

  final case class Argument(tokens: Vector[Token], keyword: Option[String], keywordRange: Option[TextRange] = None) {
    def literal: Option[String] = tokens match {
      case Vector(t) if t.string || t.image.matches("[0-9]+|true|false|True|False") => Some(t.image)
      case _ => None
    }

    def range: Option[TextRange] = tokens.headOption.map(t => new TextRange(t.range.getStartOffset,
      tokens.last.range.getEndOffset))
  }

  final case class Call(name: String, range: TextRange, arguments: Vector[Argument], block: Block,
    expressionRange: TextRange, closed: Boolean = true) {
    def positional: Vector[Argument] = arguments.filter(_.keyword.isEmpty)

    def literal(index: Int): Option[String] = positional.lift(index).flatMap(_.literal)

    def argumentRange(index: Int): Option[TextRange] = positional.lift(index).flatMap(_.range)

    def keywords: Vector[(String, Argument)] = arguments.flatMap(a => a.keyword.map(_ -> a))

    /** The `version` (or `v`) argument; `Some(None)` when the version is not a literal. */
    def version: Option[Option[String]] = arguments.find(_.keyword.exists(Set("v", "version"))).map(_.literal)

    /** Package-qualified calls such as `ref('package', 'model')` keep the name in the second argument. */
    def qualifiedName: Option[(Option[String], String)] =
      if (literal(1).nonEmpty) literal(1).map(literal(0) -> _) else literal(0).map(None -> _)

    /** Namespace and name of the called macro, e.g. `dbt_utils.star`. */
    def callee: (Option[String], String) = name.split("\\.").toVector match {
      case Vector(namespace, macroName) => (Some(namespace), macroName)
      case parts => (None, parts.last)
    }
  }

  private class Scanner(content: String) extends ExtendedScanner("${" + content + "}")

  private val config = JinjavaConfig.newBuilder().withTrimBlocks(false).build()

  def tokens(text: String, map: Int => Int): Vector[Token] = {
    val result = ArrayBuffer.empty[Token]
    val scanner = new Scanner(text)
    try {
      var t = scanner.next()
      while (t.getSymbol != Symbol.EOF) {
        ProgressManager.checkCanceled()
        if (t.getSymbol != Symbol.START_EVAL_DYNAMIC && t.getSymbol != Symbol.END_EVAL) {
          val start = scanner.getPosition - 2
          val quoted = t.getSymbol == Symbol.STRING
          result += Token(t.getImage, new TextRange(map(start + (if (quoted) 1 else 0)),
            map(start + t.getSize - (if (quoted) 1 else 0))), quoted)
        }
        t = scanner.next()
      }
    } catch {
      case _: ScanException => ()
    } // Preserve tokens preceding an unfinished expression.
    result.toVector
  }

  def blocks(text: String, map: Int => Int = identity): Vector[Block] = {
    val result = ArrayBuffer.empty[Block]
    val scanner = new TokenScanner(text.replaceAll("\\{%[-+]", "{% "), config)
    var offset = 0
    while (scanner.hasNext) {
      ProgressManager.checkCanceled()
      val token = scanner.next()
      val end = math.min(text.length, offset + token.getImage.length)
      val image = text.substring(offset, end)
      val closed = !token.isInstanceOf[UnclosedToken]
      token match {
        case _: ExpressionToken | _: TagToken | _: UnclosedToken if image.startsWith("{{") || image.startsWith("{%") =>
          val begin = offset + (if (image.lift(2).exists(c => c == '-' || c == '+')) 3 else 2)
          val finish = end - (if (closed) 2 else 0)
          val contentEnd = if (closed && text.lift(finish - 1).exists(c => c == '-' || c == '+')) finish - 1 else finish
          if (contentEnd >= begin) result += Block(tokens(text.substring(begin, contentEnd), i => map(begin + i)),
            new TextRange(map(offset), map(end)), image.startsWith("{%"))
        case _ => ()
      }
      offset = end
    }
    result.toVector
  }

  private val bareExpressionKeys = Set("input", "depends_on", "model", "relation", "to", "where", "filter", "group_by", "order_by")

  def inFile(file: PsiFile): Vector[Block] = file match {
    case _: YAMLFile =>
      val result = ArrayBuffer.empty[Block]
      file.accept(new PsiRecursiveElementWalkingVisitor {
        override def visitElement(element: PsiElement): Unit = {
          element match {
            case scalar: YAMLScalar if !DbtYaml.ancestors(scalar).contains("vars") =>
              DbtYaml.decoded(scalar).foreach { case (decoded, map) =>
                result ++= blocks(decoded, map)
                // Semantic layer and unit test properties hold bare expressions, such as `ref('orders')`.
                if (!decoded.contains("{{") && DbtYaml.ancestors(scalar).exists(bareExpressionKeys) &&
                  (decoded.contains("(") || decoded == "this"))
                  result += Block(tokens(decoded, map), scalar.getTextRange, tag = false)
              }
            case _ => ()
          }
          super.visitElement(element)
        }
      })
      result.toVector
    case _ => blocks(file.getText)
  }

  def calls(block: Block): Vector[Call] = {
    val ts = block.tokens
    if (block.tagName.exists(Set("macro", "test", "materialization"))) return Vector.empty
    ts.indices.flatMap { i =>
      val t = ts(i)
      val prev = ts.lift(i - 1).map(_.image)
      val blocked = t.string || prev.exists(Set(".", "|", "is")) ||
        (prev.contains("not") && ts.lift(i - 2).exists(_.image == "is")) ||
        (i == 1 && block.isTag("filter"))
      if (!blocked && ts.lift(i + 1).exists(_.image == "(") && isIdentifier(t.image)) {
        val begin = if (i >= 2 && ts(i - 1).image == ".") i - 2 else i
        Some(parseCall(ts, begin, i, block))
      } else if (!t.string && prev.contains(".") && i >= 2 && ts.lift(i + 1).exists(_.image == "(") &&
        (i < 3 || ts(i - 3).image != ".")) Some(parseCall(ts, i - 2, i, block))
      else None
    }.toVector
  }

  def parameters(block: Block): Vector[Token] = {
    val result = ArrayBuffer.empty[Token]
    val start = block.tokens.indexWhere(_.image == "(")
    var depth = 0
    var expectingName = false
    var finished = false
    if (start >= 0) block.tokens.drop(start).iterator.takeWhile(_ => !finished).foreach { token =>
      if (!token.string && opening(token.image)) {
        depth += 1
        if (depth == 1) expectingName = true
      } else if (!token.string && closing(token.image)) {
        depth -= 1
        if (depth == 0) finished = true
      }
      else if (depth == 1 && !token.string && token.image == ",") expectingName = true
      else if (depth == 1 && expectingName) {
        if (!token.string && isIdentifier(token.image)) result += token
        expectingName = false
      }
    }
    result.toVector
  }

  private def parseCall(ts: Vector[Token], begin: Int, callee: Int, block: Block): Call = {
    val args = ArrayBuffer.empty[Argument]
    val current = ArrayBuffer.empty[Token]
    var depth = 0
    var i = callee + 2
    var finished = false

    def flush(): Unit = if (current.nonEmpty) {
      val xs = current.toVector
      val named = xs.size >= 3 && xs(1).image == "="
      args += Argument(if (named) xs.drop(2) else xs, if (named) Some(xs.head.image) else None, if (named) Some(xs.head.range) else None)
      current.clear()
    }

    while (i < ts.size && !finished) {
      val t = ts(i)
      if (!t.string && t.image == ")" && depth == 0) {
        flush()
        finished = true
      }
      else if (!t.string && t.image == "," && depth == 0) flush()
      else {
        current += t
        if (!t.string && opening(t.image)) depth += 1
        if (!t.string && closing(t.image)) depth -= 1
      }
      i += 1
    }
    val name = ts.slice(begin, callee + 1).map(_.image).mkString
    Call(name, new TextRange(ts(begin).range.getStartOffset, ts(callee).range.getEndOffset), args.toVector, block,
      new TextRange(ts(begin).range.getStartOffset, ts(i - 1).range.getEndOffset), closed = finished)
  }
}

/** Host spans only, not a SQL grammar. Used to keep diagnostics and clipboard edits out of comments/literals. */
private[dbt] object DbtSqlText {
  final case class Token(text: String, range: TextRange, protectedText: Boolean = false, identifier: Boolean = false)

  private val DollarQuote = "\\$[A-Za-z_0-9]*\\$".r

  def tokens(text: String): Vector[Token] = {
    val result = ArrayBuffer.empty[Token]
    var i = 0
    while (i < text.length) {
      ProgressManager.checkCanceled()
      val start = i
      var protectedText = false
      var identifier = false
      if (text.startsWith("{{", i) || text.startsWith("{%", i) || text.startsWith("{#", i)) {
        val endTag = if (text(i + 1) == '{') "}}" else if (text(i + 1) == '%') "%}" else "#}"
        val end = text.indexOf(endTag, i + 2)
        i = if (end < 0) text.length else end + 2
      }
      else if (text.startsWith("--", i)) {
        protectedText = true;
        i = text.indexOf('\n', i) match {
          case -1 => text.length;
          case n => n
        }
      }
      else if (text.startsWith("/*", i)) {
        protectedText = true
        var depth = 1;
        i += 2
        while (i < text.length && depth > 0) {
          if (text.startsWith("/*", i)) {
            depth += 1;
            i += 2
          }
          else if (text.startsWith("*/", i)) {
            depth -= 1;
            i += 2
          }
          else i += 1
        }
      } else if (Set('\'', '"', '`', '[')(text(i))) {
        val quote = text(i);
        val end = if (quote == '[') ']' else quote
        protectedText = quote == '\'';
        identifier = !protectedText;
        i += 1
        var done = false
        while (i < text.length && !done) {
          if (text(i) == end) {
            if (i + 1 < text.length && text(i + 1) == end) i += 2
            else {
              i += 1;
              done = true
            }
          } else if (text(i) == '\\' && i + 1 < text.length) i += 2 else i += 1
        }
      } else if (text(i) == '$' && DollarQuote.findPrefixOf(text.substring(i)).nonEmpty) {
        protectedText = true
        val delimiter = DollarQuote.findPrefixOf(text.substring(i)).get
        val end = text.indexOf(delimiter, i + delimiter.length)
        i = if (end < 0) text.length else end + delimiter.length
      } else if (text(i).isLetterOrDigit || text(i) == '_') {
        identifier = true;
        i += 1
        while (i < text.length && (text(i).isLetterOrDigit || Set('_', '$')(text(i)))) i += 1
      } else if (text(i).isWhitespace) {
        i += 1;
        while (i < text.length && text(i).isWhitespace) i += 1
      }
      else i += 1
      result += Token(text.substring(start, i), new TextRange(start, i), protectedText, identifier)
    }
    result.toVector
  }

  def inactive(text: String, offset: Int): Boolean = tokens(text).exists(t => t.protectedText && t.range.containsOffset(offset))
}
