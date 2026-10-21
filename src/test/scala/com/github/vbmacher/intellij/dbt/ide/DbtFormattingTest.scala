package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import org.junit.Assert._

import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.jdk.CollectionConverters._

final class DbtFormattingTest extends DbtTestCase {
  private def background[A](body: => A): A = CompletableFuture.supplyAsync(() => body).get(10, TimeUnit.SECONDS)

  private def directory(): Path = tempDirectory("dbt formatter with spaces ")

  private def script(dir: Path, content: String): Path = fakeTool(dir.resolve("tool with spaces"), "#!/bin/sh\n" + content)

  def testFakeFormatterReceivesArgumentVectorCwdEnvironmentAndRawMultilineJinja(): Unit = {
    val dir = directory()
    val executable = script(dir,
      """printf '%s\n' "$@" > arguments
pwd > cwd
printf '%s' "$DBT_FORMAT_TEST_CONTEXT" > environment
cat > input
cat input
""")
    val sql = "{% if execute %}\nselect '{{ ref(\"orders\") }}', 'ž 日本語'\n{% endif %}\n"
    val command = DbtToolCommand(Vector(executable.toString, "a file with spaces.sql", "$(touch never)"), dir.toString,
      Map("DBT_FORMAT_TEST_CONTEXT" -> "explicit environment"))
    val output = background(new DbtToolProcess().run(command, sql)).toOption.get
    assertEquals(0, output.getExitCode);
    assertEquals(sql, output.getStdout)
    assertEquals(Vector("a file with spaces.sql", "$(touch never)"), Files.readString(dir.resolve("arguments")).linesIterator.toVector)
    assertEquals(dir.toString, Files.readString(dir.resolve("cwd")).trim)
    assertEquals("explicit environment", Files.readString(dir.resolve("environment")))
    assertEquals(sql, Files.readString(dir.resolve("input")));
    assertFalse(Files.exists(dir.resolve("never")))
  }

  def testCommandUsesOwningProjectConfigurationAndStdinSourceFilename(): Unit = {
    project()
    val file = add("models/a file.sql", "select 1")
    val root = workspace.root(file).get
    root.runOptions.formatter = "sqlfluff";
    root.runOptions.formatterExecutable = "bin/a tool"
    val command = DbtToolCommand.create(file, lint = true).toOption.get
    assertEquals(root.executionDirectory.resolve("bin/a tool").toString, command.arguments.head)
    assertEquals(Vector("lint", "--nocolor", "--disable-progress-bar", "--stdin-filename", file.getVirtualFile.getPath,
      "--format", "json", "-"), command.arguments.tail)
    assertEquals(root.executionDirectory.toString, command.directory)
    root.runOptions.formatter = "sqlfmt"
    assertTrue(DbtToolCommand.create(file, lint = true).isLeft)
    assertEquals(Vector(root.executionDirectory.resolve("bin/a tool").toString, "-"), DbtToolCommand.create(file).toOption.get.arguments)
    root.runOptions.formatterExecutable = ""
    assertTrue(DbtToolCommand.create(file).isLeft);
    assertFalse(new DbtFormattingService().canFormat(file))
  }

  def testFailuresEmptyOutputMissingToolsAndTimeoutNeverReturnReplacementSql(): Unit = {
    val dir = directory()
    val broken = script(dir, "cat > input\nprintf 'invalid config' >&2\nexit 2\n")
    val before = "select {{ ref('orders') }}\n"
    val command = DbtToolCommand(Vector(broken.toString, "-"), dir.toString, Map.empty)
    assertTrue(background(DbtFormatting.result(new DbtToolProcess, command, before, new EmptyProgressIndicator)).left.toOption.get.contains("invalid config"))
    script(dir, "cat > input\n")
    assertTrue(background(DbtFormatting.result(new DbtToolProcess, command, before, new EmptyProgressIndicator)).isLeft)
    assertTrue(background(new DbtToolProcess().run(command.copy(arguments = Vector(dir.resolve("missing").toString)), before)).isLeft)
    script(dir, "cat > input\nexec sleep 30\n")
    assertTrue(background(new DbtToolProcess().run(command, before, timeoutMs = 50)).left.toOption.get.contains("timed out"))
  }

  def testCancellationStopsFakeProcessAndRejectsOutput(): Unit = {
    val dir = directory()
    val executable = script(dir, "cat > input\nprintf ready > ready\nexec sleep 30\n")
    val process = new DbtToolProcess
    val future = CompletableFuture.supplyAsync(() => process.run(DbtToolCommand(Vector(executable.toString), dir.toString, Map.empty), "select 1"))
    await("Fake process should start", 5)(Files.exists(dir.resolve("ready")))
    process.cancel()
    assertTrue(future.get(5, TimeUnit.SECONDS).left.toOption.get.contains("canceled"))
  }

  def testSqlFluffRangesAndFixesUseRawSourceUnicodePositions(): Unit = {
    val source = "select '😀' as x, foo\nfrom {{ ref('orders') }}\n"
    val start = source.indexOf("foo");
    val cp = source.codePointCount(0, start)
    val json = s"""[{"filepath":"stdin","violations":[{"code":"CP02","description":"Uppercase identifier","start_line_no":1,"start_line_pos":${cp + 1},"start_file_pos":$cp,"end_file_pos":${cp + 3},"fixes":[{"type":"replace","edit":"FOO","start_file_pos":$cp,"end_file_pos":${cp + 3}}]}]}]"""
    val diagnostic = DbtSqlFluff.parse(source, json).toOption.get.head
    assertEquals(new TextRange(start, start + 3), diagnostic.range)
    assertEquals(Vector(DbtSqlFluffEdit(new TextRange(start, start + 3), "FOO")), diagnostic.edits)
    assertTrue(DbtSqlFluff.parse(source, json.replace("\"start_file_pos\":" + cp, "\"start_file_pos\":9999")).isLeft)
    assertTrue(DbtSqlFluff.parse(source, json.replace("\"filepath\":\"stdin\"", "\"filepath\":\"target/compiled/orders.sql\"")).isLeft)
  }

  def testParseErrorsAndOverlappingOrUnsupportedFixesDoNotOfferEdits(): Unit = {
    val diagnostics = """[{"filepath":"stdin","violations":[{"code":"PRS","description":"Parse failed","start_file_pos":0,"end_file_pos":6},{"code":"CP01","start_file_pos":0,"end_file_pos":6,"fixes":[{"type":"replace","edit":"SELECT","start_file_pos":0,"end_file_pos":6}]}]}]"""
    assertTrue(DbtSqlFluff.parse("select x", diagnostics).toOption.get.forall(_.edits.isEmpty))
    assertTrue(DbtSqlFluff.parse("select x", "not json").isLeft)
    val overlap = """[{"filepath":"stdin","violations":[{"code":"LT01","start_file_pos":0,"end_file_pos":6,"fixes":[{"type":"replace","edit":"A","start_file_pos":0,"end_file_pos":4},{"type":"delete","edit":"","start_file_pos":3,"end_file_pos":6}]}]}]"""
    assertTrue(DbtSqlFluff.parse("select x", overlap).toOption.get.head.edits.isEmpty)
  }

  def testSupportedSqlFluffFixIsOneUndoableEditAndRejectsStaleSource(): Unit = {
    project()
    val file = configure("models/orders.sql", "select <caret>x\nfrom {{ ref('orders') }}\n")
    val document = myFixture.getEditor.getDocument
    val before = document.getText
    val result = DbtSqlFluffResult(before, document.getModificationStamp, Vector.empty)
    val diagnostic = DbtSqlFluffDiagnostic("CP01", "Uppercase", new TextRange(0, 6), warning = false,
      Vector(DbtSqlFluffEdit(new TextRange(0, 6), "SELECT")))
    assertTrue(DbtSqlFluff.applyEdits(file, result, diagnostic))
    assertEquals(before.replace("select", "SELECT"), document.getText)
    undo(TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor))
    assertEquals(before, document.getText)
    // IntelliJ Undo may restore the original modification stamp. A real source edit must invalidate the old result.
    edit(file, before + "-- concurrent edit\n")
    assertFalse(DbtSqlFluff.applyEdits(file, result, diagnostic))
    assertEquals(before + "-- concurrent edit\n", document.getText)
  }

  def testFormatOnSaveRequiresExplicitSettingAndPreservesConcurrentEdits(): Unit = {
    project()
    val file = configure("models/orders.sql", "select <caret>1\n")
    val root = workspace.root(file).get
    root.runOptions.formatterExecutable = "configured-tool"
    assertFalse(DbtFormatOnSave.enabled(getProject, file.getVirtualFile))
    root.runOptions.formatOnSave = true
    assertTrue(DbtFormatOnSave.enabled(getProject, file.getVirtualFile))
    val document = myFixture.getEditor.getDocument
    val stamp = document.getModificationStamp;
    val before = document.getText
    assertFalse(DbtFormatOnSave.applyText(getProject, document, before, stamp, before))
    edit(file, "select 2\n")
    assertFalse(DbtFormatOnSave.applyText(getProject, document, before, stamp, "SELECT 1\n"))
    assertEquals("select 2\n", document.getText)
    assertTrue(DbtFormatOnSave.applyText(getProject, document, document.getText, document.getModificationStamp, "SELECT 2\n"))
    assertEquals("SELECT 2\n", document.getText)
  }

  def testExplicitInspectionPublishesNativeSourceRangeAndAltEnterFixWithUndo(): Unit = {
    project()
    val file = configure("models/orders.sql", "<caret>select x\nfrom {{ ref('orders') }}\n")
    val dir = directory()
    val executable = script(dir,
      """cat > input
cat <<'JSON'
[{"filepath":"stdin","violations":[{"code":"CP01","description":"Use uppercase SELECT","start_line_no":1,"start_line_pos":1,"start_file_pos":0,"end_line_no":1,"end_line_pos":7,"end_file_pos":6,"fixes":[{"type":"replace","edit":"SELECT","start_file_pos":0,"end_file_pos":6}]}]}]
JSON
exit 1
""")
    val root = workspace.root(file).get
    root.runOptions.formatter = "sqlfluff";
    root.runOptions.formatterExecutable = executable.toString
    root.runOptions.projectRoot = dir.toString
    myFixture.enableInspections(new DbtSqlFluffInspection)
    new DbtInspectSqlAction().inspect(getProject, file)
    await("SQLFluff publishes its explicit result")(file.getUserData(DbtSqlFluff.Result) != null)
    val diagnostics = myFixture.doHighlighting().asScala.filter(h => Option(h.getDescription).exists(_.startsWith("SQLFluff CP01:")))
    assertEquals(1, diagnostics.size)
    assertEquals(0, diagnostics.head.getStartOffset);
    assertEquals(6, diagnostics.head.getEndOffset)
    val before = myFixture.getEditor.getDocument.getText
    myFixture.launchAction(myFixture.findSingleIntention("Apply SQLFluff CP01 fix"))
    assertEquals(before.replace("select", "SELECT"), myFixture.getEditor.getDocument.getText)
    undo(TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor))
    assertEquals(before, myFixture.getEditor.getDocument.getText)
  }

  def testNativeSaveFormatsAndSavesOnlyWhenExplicitlyEnabled(): Unit = {
    project()
    val file = configure("models/orders.sql", "select <caret>1\n")
    val dir = directory()
    val executable = script(dir, "cat > input\nsed 's/^select/SELECT/' input\n")
    val root = workspace.root(file).get
    root.runOptions.formatterExecutable = executable.toString;
    root.runOptions.projectRoot = dir.toString
    root.runOptions.formatOnSave = true
    edit(file, "select 2\n")
    val document = myFixture.getEditor.getDocument
    val manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
    manager.saveDocument(document)
    await("Format-on-save applies its background result")(document.getText == "SELECT 2\n")
    assertEquals("SELECT 2\n", file.getText)
    assertEquals("SELECT 2\n", new String(file.getVirtualFile.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8))
    root.runOptions.formatOnSave = false
    edit(file, "select 3\n")
    manager.saveDocument(document)
    assertEquals("select 3\n", document.getText)
  }

  def testNativeReformatCodePreservesJinjaAndIsOneStepUndoable(): Unit = {
    project()
    val file = configure("models/orders.sql", "<caret>select   {{ ref('orders') }}\n")
    val dir = directory()
    val executable = script(dir, "cat > input\nsed 's/^select   /select /' input\n")
    val root = workspace.root(file).get
    root.runOptions.formatterExecutable = executable.toString;
    root.runOptions.projectRoot = dir.toString
    val document = myFixture.getEditor.getDocument;
    val before = document.getText
    myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_REFORMAT)
    await("Native Reformat Code uses the configured formatter")(document.getText == "select {{ ref('orders') }}\n")
    undo(TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor))
    assertEquals(before, document.getText)
  }

}
