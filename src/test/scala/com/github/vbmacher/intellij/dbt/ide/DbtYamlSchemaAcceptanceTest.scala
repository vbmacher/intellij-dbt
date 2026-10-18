package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState}
import com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection
import org.junit.Assert._
import scala.jdk.CollectionConverters._

/** Exercise IntelliJ's actual YAML schema completion/inspection, including local $ref resolution. */
final class DbtYamlSchemaAcceptanceTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def details: String = {
    val service = com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(getProject)
    val file = myFixture.getFile
    val schema = Option(service.getSchemaObject(file))
    val modelSchema = schema.flatMap(s => Option(s.getPropertyByName("models")))
    val items = modelSchema.flatMap(s => Option(s.getItemsSchema).orElse(Option(s.getAnyOf).toVector.flatMap(_.asScala).flatMap(s => Option(s.getItemsSchema)).headOption))
    val resource = items.flatMap(s => Option(s.resolveRefSchema(service)))
    val resolved = resource.map(_.getPropertyNames.asScala.toVector)
    s"resourceProperties=$resolved; service=${service.getClass.getName}; applicable=${service.isApplicableToFile(file.getVirtualFile)}; providers=${providers(file)}; schemas=${service.getSchemaFilesForFile(file.getVirtualFile)}; schemaLoaded=${service.getSchemaObject(file) != null}; source=${file.getText}"
  }
  private def suggestions(): Set[String] = Option(myFixture.completeBasic()).toVector.flatMap(_.toVector).map(_.getLookupString).toSet
  private def profile(version: String, engine: String = "core"): Unit = {
    add(".dbt-navigation.yml", s"dbt-version: '$version'\nengine: $engine\n")
    ()
  }
  private def providers(file: com.intellij.psi.PsiFile): Vector[String] = new DbtYamlSchemaProvider().getProviders(getProject).asScala
    .filter(_.isAvailable(file.getVirtualFile)).map(_.getName).toVector

  def testNativeNestedPropertiesCompletionInsertsYamlKey(): Unit = {
    project()
    configure("models/schema.yml", "models:\n  - name: orders\n    <caret>\n")
    val choices = suggestions()
    assertTrue("Native schema completions: " + choices + "; " + details, Set("columns", "config").subsetOf(choices))
    val columns = myFixture.getLookupElements.find(_.getLookupString == "columns").get
    myFixture.getLookup.setCurrentItem(columns)
    myFixture.finishLookup('\n')
    assertTrue(myFixture.getFile.getText.contains("    columns:"))
  }

  def testNativeColumnCompletionResolvesBundledCommonSchema(): Unit = {
    project()
    configure("models/schema.yml", "models:\n  - name: orders\n    columns:\n      - name: order_id\n        <caret>\n")
    val choices = suggestions()
    assertTrue("Column schema completions: " + choices + "; " + details, Set("data_type", "description", "data_tests").subsetOf(choices))
  }

  def testNativeSchemaInspectionMarksInvalidValueAndAcceptsTemplatesAndCustomConfig(): Unit = {
    project()
    val invalid = configure("models/schema.yml", "models:\n- name: orders\n  columns: wrong<caret>\n")
    myFixture.enableInspections(new YamlJsonSchemaHighlightingInspection)
    val problems = myFixture.doHighlighting().asScala.filter(h => h.getSeverity.compareTo(HighlightSeverity.WARNING) >= 0 && h.getDescription != null)
    assertTrue("Native schema diagnostics: " + problems.map(_.getDescription) + "; " + details, problems.exists(h =>
      invalid.getText.substring(h.getStartOffset, h.getEndOffset).contains("wrong") &&
        (h.getDescription.toLowerCase.contains("array") || h.getDescription.toLowerCase.contains("type") || h.getDescription.toLowerCase.contains("pattern"))))
    configure("models/templated.yml", "models:\n- name: orders\n  columns: \"{{ dynamic_columns }}\"\n  config:\n    custom_adapter_option: custom_value\n    meta: {owner: Finance}\n<caret>")
    assertTrue("Templates and adapter config must not be rejected", myFixture.doHighlighting().asScala.filter(h =>
      h.getSeverity.compareTo(HighlightSeverity.WARNING) >= 0 && h.getDescription != null).isEmpty)
  }

  def testNativeTopLevelCompletionsFollowCoreAndEngineVersions(): Unit = {
    project()
    val cases = Vector(
      ("1.5", "core", Set.empty[String], Set("semantic_models", "saved_queries", "unit_tests", "functions")),
      ("1.6", "core", Set("semantic_models"), Set("saved_queries", "unit_tests", "functions")),
      ("1.7", "core", Set("semantic_models", "saved_queries"), Set("unit_tests", "functions")),
      ("1.8", "core", Set("semantic_models", "saved_queries", "unit_tests"), Set("functions")),
      ("1.11", "core", Set("semantic_models", "saved_queries", "unit_tests", "functions"), Set.empty[String]),
      ("1.10", "v2", Set("semantic_models", "saved_queries", "unit_tests", "functions"), Set.empty[String]))
    val configuration = add(".dbt-navigation.yml", "dbt-version: '1.5'\n")
    cases.zipWithIndex.foreach { case ((version, engine, expected, absent), index) =>
      edit(configuration, s"dbt-version: '$version'\nengine: $engine\n")
      configure(s"models/profile_$index.yml", "models: []\n<caret>\n")
      val choices = suggestions()
      assertTrue(s"$engine $version suggestions: $choices", expected.subsetOf(choices))
      assertTrue(s"$engine $version must omit unsupported keys: $choices", absent.intersect(choices).isEmpty)
      com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject)
    }
  }

  def testNativeDiagnosticsRejectUnsupportedUnitTestsBefore18(): Unit = {
    project(); profile("1.7")
    val file = configure("models/units.yml", "models: []\nunit_tests: []<caret>\n")
    myFixture.enableInspections(new YamlJsonSchemaHighlightingInspection)
    val problems = myFixture.doHighlighting().asScala.filter(h => h.getSeverity.compareTo(HighlightSeverity.WARNING) >= 0 && h.getDescription != null)
    assertTrue("Unsupported unit tests should have a native schema diagnostic: " + problems.map(_.getDescription), problems.exists(h =>
      file.getText.substring(h.getStartOffset, h.getEndOffset).contains("unit_tests") || h.getDescription.toLowerCase.contains("not allowed") || h.getDescription.contains("unit_tests") && h.getDescription.contains("false")))
  }

  def testForeignAndExcludedYamlDoNotReceiveDbtSchemaDiagnostics(): Unit = {
    project(); add(".dbtignore", "models/ignored.yml\n")
    val foreign = configure("services/service.yml", "models: wrong<caret>\n")
    assertTrue(providers(foreign).isEmpty)
    myFixture.enableInspections(new YamlJsonSchemaHighlightingInspection)
    assertTrue(myFixture.doHighlighting().asScala.forall(h => h.getDescription == null || h.getSeverity.compareTo(HighlightSeverity.WARNING) < 0))
    val ignored = configure("models/ignored.yml", "models: wrong<caret>\n")
    assertTrue(providers(ignored).isEmpty)
    assertTrue(myFixture.doHighlighting().asScala.forall(h => h.getDescription == null || h.getSeverity.compareTo(HighlightSeverity.WARNING) < 0))
    val unrelated = configure("models/service.yml", "services: {http: {port: 8080}}<caret>\n")
    assertTrue(providers(unrelated).isEmpty)
  }
  def testNativeInlineExposureKeysProvideNestedSuggestions(): Unit = {
    project()
    configure("models/exposure.yml", "exposures:\n  - name: finance\n    <caret>\n")
    val choices = suggestions()
    assertTrue("Inline exposure schema completions: " + choices + "; " + details, Set("type", "url", "owner").subsetOf(choices))
  }

  def testEveryProfileResolvesNestedResourceAndSourceColumnKeys(): Unit = {
    project()
    val configuration = add(".dbt-navigation.yml", "dbt-version: '1.4'\n")
    val cases = Vector("1.4" -> "core", "1.5" -> "core", "1.6" -> "core", "1.7" -> "core",
      "1.8" -> "core", "1.10" -> "core", "1.11" -> "core", "1.10" -> "v2")
    cases.zipWithIndex.foreach { case ((version, engine), index) =>
      edit(configuration, s"dbt-version: '$version'\nengine: $engine\n")
      configure(s"models/nested_$index.yml", "models:\n  - name: orders\n    <caret>\n")
      val resourceKeys = suggestions()
      assertTrue(s"$engine $version resource keys: $resourceKeys; $details", Set("columns", "config", "description").subsetOf(resourceKeys))
      if (engine == "core" && Set("1.4", "1.5", "1.6", "1.7").contains(version))
        assertFalse(s"$version must not suggest data_tests: $resourceKeys", resourceKeys.contains("data_tests"))
      com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject)
      configure(s"models/source_$index.yml", "sources:\n  - name: raw\n    tables:\n      - name: orders\n        columns:\n          - name: id\n            <caret>\n")
      val columnKeys = suggestions()
      assertTrue(s"$engine $version source column keys: $columnKeys; $details", Set("description", "data_type").subsetOf(columnKeys))
      if (engine == "core" && Set("1.4", "1.5", "1.6", "1.7").contains(version))
        assertFalse(s"$version must not suggest column data_tests: $columnKeys", columnKeys.contains("data_tests"))
      com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject)
    }
  }

}
