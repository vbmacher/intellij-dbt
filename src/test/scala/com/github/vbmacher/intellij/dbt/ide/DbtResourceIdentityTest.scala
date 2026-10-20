package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import org.junit.Assert._

/** features/project-context.md: resource ownership composes with source/property declarations and packages. */
final class DbtResourceIdentityTest extends DbtCodeInsightTestCase {
  def testOneResourceSharesIdentityAcrossDeclarationsButIndependentOwnersRemainSeparate(): Unit = {
    project()
    val code = add("models/orders.sql", "select 1")
    val properties = add("models/schema.yml", "models: [{name: orders}]\n")
    val other = add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    val otherCode = add("other/models/orders.sql", "select 2")
    add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    val packageCode = add("dbt_packages/util/models/orders.sql", "select 3")
    val w = DbtWorkspace.get(getProject)
    val source = w.symbols.find(s => s.file == code && s.isCode).get
    val property = w.symbols.find(s => s.file == properties && s.kind == "model").get
    val independent = w.symbols.find(s => s.file == otherCode && s.isCode).get
    val installed = w.symbols.find(s => s.file == packageCode && s.isCode).get
    assertNotEquals(source.id, property.id)
    assertEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, property))
    assertEquals(source.uniqueId, independent.uniqueId)
    assertNotEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, independent))
    assertNotEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, installed))
    assertEquals(w.executionRoot(w.owner(source).get), w.executionRoot(w.owner(installed).get))
    assertEquals(other.getVirtualFile.getParent, w.owner(independent).get.directory)
  }
}
