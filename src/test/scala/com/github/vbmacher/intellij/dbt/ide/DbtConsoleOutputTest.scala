package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.process.{ProcessOutputType, ProcessOutputTypes}
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert._
import scala.collection.mutable.ArrayBuffer

final class DbtConsoleOutputTest extends BasePlatformTestCase {
  private val chunks = ArrayBuffer.empty[(String, Key[_])]
  private def output(): DbtConsoleOutput = new DbtConsoleOutput((text, kind) => { chunks += text -> kind; () })
  private def text: String = chunks.map(_._1).mkString

  def testDbtMessagesShowTimeLevelMultilineDetailsAndBlankSeparators(): Unit = {
    val display = output()
    display.textAvailable("""{"data":{"unused":"metadata"},"info":{"msg":"Running with dbt=1.12.5","level":"info","ts":"2026-10-20T12:34:56.123Z"}}
{"data":{},"info":{"msg":"Unexpected rows\nexpected: 0\nactual: 3","level":"warn","ts":"2026-10-20T12:34:57Z"}}
{"data":{"msg":""},"info":{"msg":"","level":"info"}}
{"data":{},"info":{"msg":"Database error","level":"error"}}
""", ProcessOutputTypes.STDOUT)
    assertEquals("12:34:56  Running with dbt=1.12.5\n12:34:57  [WARN] Unexpected rows\nexpected: 0\nactual: 3\n\n[ERROR] Database error\n", text)
  }

  def testSplitJsonAndIndependentStreamsFlushFinalRecordWithoutNewline(): Unit = {
    val display = output()
    val record = """{"data":{},"info":{"msg":"PASS orders","level":"info"}}"""
    record.dropRight(1).foreach(c => display.textAvailable(c.toString, ProcessOutputTypes.STDOUT))
    assertEquals("", text)
    display.textAvailable("stderr diagnostic\n", ProcessOutputTypes.STDERR)
    display.textAvailable(record.takeRight(1), ProcessOutputTypes.STDOUT)
    display.finish()
    assertEquals("stderr diagnostic\nPASS orders\n", text)
    assertTrue(ProcessOutputType.isStderr(chunks.head._2))
    assertTrue(ProcessOutputType.isStdout(chunks.last._2))
    display.finish()
    assertEquals(2, chunks.size)
  }

  def testPlainTextStreamsImmediatelyAndOtherJsonAndMalformedRecordsRemainVisible(): Unit = {
    val display = output()
    display.textAvailable("Connecting...", ProcessOutputTypes.STDOUT)
    assertEquals("Connecting...", text)
    val raw = """
{"preview":"[{\"id\":1}]"}
{"unique_id":"model.analytics.orders"}
{"data":{},"info":{"msg":null}}
{"data":{},"info":{"msg":42}}
{"data":{},"info":{"msg":{"unexpected":true}}}
{"data":{},"info":"unexpected"}
{"data":{},"info":{"msg":"truncated
"""
    display.textAvailable(raw, ProcessOutputTypes.STDOUT)
    display.textAvailable("{unfinished", ProcessOutputTypes.STDERR)
    display.finish()
    assertEquals("Connecting..." + raw + "{unfinished", text)
  }

  def testAnsiInJsonMessagesAndPlainOutputProducesColoredTextWithoutEscapeCodes(): Unit = {
    val display = output()
    display.textAvailable("{\"data\":{},\"info\":{\"msg\":\"\\u001b[32mPASS\\u001b[0m orders\"}}\n", ProcessOutputTypes.STDOUT)
    display.textAvailable("\u001b[31", ProcessOutputTypes.STDERR)
    display.textAvailable("mFailure\u001b[0m\n", ProcessOutputTypes.STDERR)
    assertEquals("PASS orders\nFailure\n", text)
    val pass = chunks.find(_._1 == "PASS").get
    val failure = chunks.find(_._1 == "Failure").get
    assertTrue(ProcessOutputType.isStdout(pass._2))
    assertTrue(ProcessOutputType.isStderr(failure._2))
    assertNotEquals(ConsoleViewContentType.NORMAL_OUTPUT,
      ConsoleViewContentType.getConsoleViewType(pass._2))
    assertNotEquals(ConsoleViewContentType.ERROR_OUTPUT,
      ConsoleViewContentType.getConsoleViewType(failure._2))
  }

  def testOversizedUnterminatedRecordDoesNotHideOutput(): Unit = {
    val display = output()
    val raw = "{" + "x" * 1000001
    display.textAvailable(raw, ProcessOutputTypes.STDOUT)
    assertEquals(raw, text)
    display.finish()
    assertEquals(raw, text)
  }

  def testJsonEmbeddedInPlainScriptOutputIsNotReformattedAcrossChunks(): Unit = {
    val display = output()
    val record = """{"data":{},"info":{"msg":"diagnostic payload"}}"""
    display.textAvailable("Script payload: ", ProcessOutputTypes.STDOUT)
    display.textAvailable(record + "\n", ProcessOutputTypes.STDOUT)
    assertEquals("Script payload: " + record + "\n", text)
    display.textAvailable(record + "\n", ProcessOutputTypes.STDOUT)
    assertEquals("Script payload: " + record + "\ndiagnostic payload\n", text)
  }
}
