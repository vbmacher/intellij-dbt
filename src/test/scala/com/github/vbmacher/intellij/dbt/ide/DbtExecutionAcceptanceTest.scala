package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import org.junit.Assert._

final class DbtExecutionAcceptanceTest extends DbtCodeInsightTestCase {
  def testMixedMalformedResultRecordsAreExplicitlyPartialWithoutDroppingValidEvidence(): Unit = {
    val directory = Files.createTempDirectory("dbt partial evidence ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(directory.toFile))
    val path = directory.resolve("run_results.json")
    def write(results: String): Unit = Files.writeString(path,
      s"""{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/run-results/v6.json","invocation_id":"run-1","generated_at":"2026-10-18T10:00:00Z"},"args":{"target":"dev"},"results":$results}""")
    write("""[{"unique_id":"model.analytics.orders","status":"success"},null,7]""")
    val partial = DbtExecutionEvidence.parse(path, Vector("root"), "dev")
    assertTrue(partial.problem, partial.problem.contains("Partial results"))
    assertEquals(Vector("model.analytics.orders"), partial.results.map(_.id))
    assertEquals("passed", partial.results.head.status)
    write("null")
    assertTrue(DbtExecutionEvidence.parse(path, Vector("root"), "dev").problem.contains("Partial results"))
    write("[]")
    assertEquals("A genuinely empty result array remains distinguishable from malformed records", "",
      DbtExecutionEvidence.parse(path, Vector("root"), "dev").problem)
  }

  def testTestOnlyFailureBadgeRetainsExactProvenanceWithoutClaimingModelBuildSuccess(): Unit = {
    project()
    val file = add("models/orders.sql", "select 1 as id")
    val w = DbtWorkspace.get(getProject)
    val model = w.codeSymbols(file).find(_.kind == "model").get
    val definition = DbtTestDefinition("test.analytics.unique_orders.hash", "unique orders", "Data tests", model.uniqueId,
      w.owner(model).get.directory.getPath, file.getVirtualFile, 0, "fqn:analytics.unique_orders", Vector(model.uniqueId))
    val execution = DbtExecutionRun(Vector("root", "prod"), "prod", "exact-test-invocation", "2026-10-18T10:00:00Z",
      "/project/target/run_results.json", Vector(DbtExecutionResult(definition.id, "failed", "3 duplicate ids", 3)))
    val overlay = getProject.getService(classOf[DbtExecutionEvidence]).overlay(model, Vector(execution), Vector(definition))
    assertEquals("unknown", overlay.get("status").getAsString)
    assertEquals("Not run / result unavailable", overlay.get("label").getAsString)
    assertEquals(1, overlay.get("failingTests").getAsInt)
    val details = overlay.get("details").getAsString
    Vector("prod", "exact-test-invocation", execution.timestamp, execution.artifact, definition.id, "3 duplicate ids").foreach(value =>
      assertTrue(value + " should be present in badge details: " + details, details.contains(value)))
    val anotherVersion = model.copy(version = Some("2"))
    assertEquals(0, getProject.getService(classOf[DbtExecutionEvidence]).overlay(anotherVersion, Vector(execution), Vector(definition))
      .get("failingTests").getAsInt)
    val freshPass = execution.copy(invocation = "new-run", results = Vector(DbtExecutionResult(definition.id, "passed", "")))
    assertEquals(0, getProject.getService(classOf[DbtExecutionEvidence]).overlay(model, Vector(freshPass, execution), Vector(definition))
      .get("failingTests").getAsInt)
  }
}
