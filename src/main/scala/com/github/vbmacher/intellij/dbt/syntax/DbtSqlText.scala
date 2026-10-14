package com.github.vbmacher.intellij.dbt.syntax

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import scala.collection.mutable.ArrayBuffer

/** Host spans only, not a SQL grammar. Used to keep diagnostics and clipboard edits out of comments/literals. */
private[dbt] object DbtSqlText {
  final case class Token(text: String, range: TextRange, protectedText: Boolean = false, identifier: Boolean = false)
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
      else if (text.startsWith("--", i)) { protectedText = true; i = text.indexOf('\n', i) match { case -1 => text.length; case n => n } }
      else if (text.startsWith("/*", i)) {
        protectedText = true
        var depth = 1; i += 2
        while (i < text.length && depth > 0) {
          if (text.startsWith("/*", i)) { depth += 1; i += 2 }
          else if (text.startsWith("*/", i)) { depth -= 1; i += 2 }
          else i += 1
        }
      } else if (Set('\'', '"', '`', '[')(text(i))) {
        val quote = text(i); val end = if (quote == '[') ']' else quote
        protectedText = quote == '\''; identifier = !protectedText; i += 1
        var done = false
        while (i < text.length && !done) {
          if (text(i) == end) {
            if (i + 1 < text.length && text(i + 1) == end) i += 2
            else { i += 1; done = true }
          } else if (text(i) == '\\' && i + 1 < text.length) i += 2 else i += 1
        }
      } else if (text(i) == '$' && "\\$[A-Za-z_0-9]*\\$".r.findPrefixOf(text.substring(i)).nonEmpty) {
        protectedText = true
        val delimiter = "\\$[A-Za-z_0-9]*\\$".r.findPrefixOf(text.substring(i)).getOrElse("$$")
        val end = text.indexOf(delimiter, i + delimiter.length)
        i = if (end < 0) text.length else end + delimiter.length
      } else if (text(i).isLetterOrDigit || text(i) == '_') {
        identifier = true; i += 1
        while (i < text.length && (text(i).isLetterOrDigit || Set('_', '$')(text(i)))) i += 1
      } else if (text(i).isWhitespace) { i += 1; while (i < text.length && text(i).isWhitespace) i += 1 }
      else i += 1
      result += Token(text.substring(start, i), new TextRange(start, i), protectedText, identifier)
    }
    result.toVector
  }

  def inactive(text: String, offset: Int): Boolean = tokens(text).exists(t => t.protectedText && t.range.containsOffset(offset))
}
