package com.github.vbmacher.intellij.dbt

import com.hubspot.jinjava.{JinjavaConfig, LegacyOverrides}
import com.hubspot.jinjava.el.ext.ExtendedScanner
import com.hubspot.jinjava.tree.parse.{ExpressionToken, TagToken, TokenScanner, UnclosedToken}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import jinjava.de.odysseus.el.tree.impl.Scanner.{ScanException, Symbol}

import scala.collection.mutable.ArrayBuffer

private[dbt] final case class DbtVarCall(name: String, range: TextRange, callOffset: Int)

/** Tokenize only Jinja. SQL remains opaque and is left to the IDE's existing language support. */
private[dbt] object DbtVarCalls {
  private val config = JinjavaConfig.newBuilder()
    .withTrimBlocks(false)
    .withLegacyOverrides(LegacyOverrides.newBuilder().withHandleBackslashInQuotesOnly(true).build())
    .build()

  private class ExpressionScanner(content: String) extends ExtendedScanner("${" + content + "}")

  private case class ExpressionTokenInfo(symbol: Symbol, image: String, start: Int, size: Int)

  def extract(text: String): Seq[DbtVarCall] = {
    val calls = ArrayBuffer.empty[DbtVarCall]
    // Jinjava's raw scanner misses trim prefixes on endraw. Spaces preserve positions;
    // expression tokens below are read from the original source, including quoted strings.
    val scanner = new TokenScanner(text.replaceAll("\\{%[-+]", "{% "), config)
    var offset = 0
    while (scanner.hasNext) {
      ProgressManager.checkCanceled()
      val token = scanner.next()
      val image = text.substring(offset, offset + token.getImage.length)
      token match {
        case _: ExpressionToken | _: TagToken => calls ++= inBlock(image, offset, closed = true)
        case _: UnclosedToken if image.startsWith("{{") || image.startsWith("{%") =>
          calls ++= inBlock(image, offset, closed = false)
        case _ =>
      }
      // Token images retain source text, unlike their line/column metadata for multiline blocks.
      offset += image.length
    }
    calls.toSeq
  }

  private def inBlock(image: String, offset: Int, closed: Boolean): Seq[DbtVarCall] = {
    val start = if (image.length > 2 && (image(2) == '-' || image(2) == '+')) 3 else 2
    var end = if (closed) image.length - 2 else image.length
    if (closed && end > start && (image(end - 1) == '-' || image(end - 1) == '+')) end -= 1
    if (end <= start) return Seq.empty

    val scanner = new ExpressionScanner(image.substring(start, end))
    val tokens = ArrayBuffer.empty[ExpressionTokenInfo]
    try {
      var token = scanner.next()
      while (token.getSymbol != Symbol.EOF) {
        ProgressManager.checkCanceled()
        if (token.getSymbol != Symbol.START_EVAL_DYNAMIC && token.getSymbol != Symbol.END_EVAL) {
          tokens += ExpressionTokenInfo(token.getSymbol, token.getImage, scanner.getPosition - 2, token.getSize)
        }
        token = scanner.next()
      }
    } catch {
      // An incomplete expression should not disable navigation in preceding complete calls.
      case _: ScanException =>
    }

    tokens.indices.flatMap { index =>
      val token = tokens(index)
      val previous = tokens.lift(index - 1)
      val filterOrTest = previous.exists(t => t.image == "|" || t.image == "is" ||
        (t.image == "filter" && index == 1)) ||
        (previous.exists(_.symbol == Symbol.NOT) && tokens.lift(index - 2).exists(_.image == "is"))
      if (token.symbol == Symbol.IDENTIFIER && token.image == "var" && index + 3 < tokens.size &&
        !previous.exists(_.symbol == Symbol.DOT) && !filterOrTest &&
        tokens(index + 1).symbol == Symbol.LPAREN && tokens(index + 2).symbol == Symbol.STRING &&
        Set(Symbol.COMMA, Symbol.RPAREN).contains(tokens(index + 3).symbol)) {
        val name = tokens(index + 2)
        Some(DbtVarCall(name.image, new TextRange(offset + start + name.start + 1,
          offset + start + name.start + name.size - 1), offset + start + token.start))
      } else None
    }.toSeq
  }
}
