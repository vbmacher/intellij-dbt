package com.github.vbmacher.intellij.dbt.ide

import com.google.gson.{JsonObject, JsonParser}
import com.intellij.execution.process.{AnsiEscapeDecoder, ProcessOutputTypes}
import com.intellij.openapi.util.Key
import scala.collection.mutable
import scala.util.Try

/** Formats the display stream; execution evidence and query results use the original output. */
private[ide] final class DbtConsoleOutput(sink: (String, Key[_]) => Unit) {
  private val decoder = new AnsiEscapeDecoder
  private val acceptor = new AnsiEscapeDecoder.ColoredTextAcceptor {
    override def coloredTextAvailable(text: String, attributes: Key[_]): Unit = sink(text, attributes)
  }
  private val pending = mutable.LinkedHashMap.empty[Key[_], StringBuilder]
  private val continuing = mutable.Set.empty[Key[_]]
  private val time = "(?:T|^)([0-9]{2}:[0-9]{2}:[0-9]{2})".r

  def textAvailable(text: String, kind: Key[_]): Unit = synchronized {
    if (kind == ProcessOutputTypes.SYSTEM) emit(text, kind)
    else {
      val buffer = pending.getOrElseUpdate(kind, new StringBuilder)
      var start = 0
      while (start < text.length) {
        val end = text.indexOf('\n', start)
        val chunk = text.substring(start, if (end < 0) text.length else end + 1)
        if (buffer.nonEmpty || !continuing(kind) && chunk.trim.startsWith("{")) {
          buffer.append(chunk)
          if (end >= 0) {
            emit(format(buffer.toString), kind)
            buffer.clear()
          } else if (buffer.length > 1000000) {
            emit(buffer.toString, kind)
            buffer.clear()
          }
        } else emit(chunk, kind)
        if (end >= 0) continuing -= kind else if (chunk.trim.nonEmpty) continuing += kind
        start = if (end < 0) text.length else end + 1
      }
    }
  }

  def finish(): Unit = synchronized {
    pending.foreach { case (kind, buffer) =>
      if (buffer.nonEmpty) emit(format(buffer.toString), kind)
    }
    pending.clear()
    continuing.clear()
  }

  private def emit(text: String, kind: Key[_]): Unit = decoder.escapeText(text, kind, acceptor)

  private def string(obj: JsonObject, key: String): Option[String] = Option(obj.get(key))
    .filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString)

  private def format(line: String): String = {
    val message = Try(JsonParser.parseString(line)).toOption.filter(_.isJsonObject).flatMap { element =>
      val obj = element.getAsJsonObject
      for {
        _ <- Option(obj.get("data")).filter(_.isJsonObject)
        info <- Option(obj.get("info")).filter(_.isJsonObject).map(_.getAsJsonObject)
        text <- string(info, "msg")
      } yield {
        val timestamp = string(info, "ts").flatMap(value => time.findFirstMatchIn(value).map(_.group(1)))
          .map(_ + "  ").getOrElse("")
        val level = string(info, "level").map(_.toUpperCase(java.util.Locale.ROOT))
          .filterNot(_ == "INFO").map(value => s"[$value] ").getOrElse("")
        if (text.isEmpty) "\n" else timestamp + level + text.stripSuffix("\n") + "\n"
      }
    }
    message.getOrElse(line)
  }
}
