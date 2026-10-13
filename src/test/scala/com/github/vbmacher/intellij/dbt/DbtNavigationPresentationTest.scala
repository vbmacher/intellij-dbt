package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert._
import java.awt.{Component, Container}
import javax.swing.{JLabel, JList}

final class DbtNavigationPresentationTest extends BasePlatformTestCase {
  private def add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)

  private def model(workspace: DbtWorkspace, file: PsiFile): DbtSymbol =
    workspace.symbols.find(s => s.file == file && s.attributes.contains("code")).getOrElse(
      throw new AssertionError(s"Missing model in ${file.getName}"))

  def testResourceIconsAreSharedByDeclarationsAndNativePresentation(): Unit = {
    add("dbt_project.yml", "name: analytics\n")
    val orders = add("models/orders.sql", "select 1 as id")
    val yaml = add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\nsources:\n- name: raw\n  tables:\n  - name: events\n")
    val workspace = DbtWorkspace.get(getProject)
    val declaration = model(workspace, orders).element
    val properties = workspace.properties(declaration.symbol)
    assertFalse(properties.isEmpty)
    properties.foreach(s => assertSame(declaration.getIcon(0), s.element.getIcon(0)))
    assertSame(declaration.getIcon(0), declaration.getPresentation.getIcon(false))
    assertEquals("analytics.orders", declaration.getPresentation.getPresentableText)
    val source = workspace.symbols.find(s => s.file == yaml && s.kind == "source").get
    val table = workspace.symbols.find(s => s.file == yaml && s.kind == "source_table").get
    assertNotSame(declaration.getIcon(0), source.element.getIcon(0))
    assertNotSame(source.element.getIcon(0), table.element.getIcon(0))
  }

  def testDependenciesGroupReferencesAndKeepDirectionAndExactTargets(): Unit = {
    add("dbt_project.yml", "name: analytics\n")
    val orders = add("models/orders.sql", "select 1 as id")
    val events = add("models/events.sql", "\nselect * from {{ ref('orders') }}\nunion all select * from {{ ref('orders') }}")
    val report = add("models/report.sql", "select * from {{ ref('events') }}")
    val workspace = DbtWorkspace.get(getProject)
    val edges = DbtLineage.edges(workspace)
    val downstream = DbtDependenciesAction.choices(edges, Set(model(workspace, orders).id))
    assertEquals(Vector("analytics.events", "events.sql:2", "events.sql:3"), downstream.map(_.text))
    assertEquals(Some("Used by"), downstream.head.separator)
    assertFalse(downstream.head.reference)
    assertTrue(downstream.drop(1).forall(_.reference))
    assertEquals(events, downstream.head.target.getContainingFile)
    assertEquals(events.getText.indexOf("orders"), downstream(1).target.getTextOffset)
    assertEquals(events.getText.lastIndexOf("orders"), downstream(2).target.getTextOffset)
    val both = DbtDependenciesAction.choices(edges, Set(model(workspace, events).id))
    assertEquals(Vector("Uses", "Used by"), both.flatMap(_.separator))
    assertEquals(Vector(orders, report), both.filterNot(_.reference).map(_.target.getContainingFile))
  }

  def testIdenticalReferenceLabelsKeepTheirOwnNavigationTargets(): Unit = {
    add("dbt_project.yml", "name: analytics\n")
    val orders = add("models/orders.sql", "select 1")
    val first = add("models/a/use.sql", "{{ ref('orders') }}")
    val second = add("models/b/use.sql", "{{ ref('orders') }}")
    val workspace = DbtWorkspace.get(getProject)
    val choices = DbtDependenciesAction.choices(DbtLineage.edges(workspace), Set(model(workspace, orders).id))
    val references = choices.filter(_.reference)
    assertEquals(Vector("use.sql:1", "use.sql:1"), references.map(_.text))
    assertEquals(Set(first, second), references.map(_.target.getContainingFile).toSet)
  }

  def testRendererIndentsReferencesAndRestoresResourceIcon(): Unit = {
    val file = add("models/orders.sql", "select 1")
    val symbol = DbtSymbol("model", "orders", "analytics", file, TextRange.EMPTY_RANGE).element
    val renderer = new DbtNavigationItemRenderer
    val list = new JList[DbtNavigationItem]()
    def labels(component: Component): Vector[JLabel] = component match {
      case label: JLabel => Vector(label)
      case container: Container => container.getComponents.toVector.flatMap(labels)
      case _ => Vector.empty
    }
    val reference = DbtNavigationItem(symbol, "orders.sql:1", reference = true)
    val referenceLabel = labels(renderer.getListCellRendererComponent(list, reference, 0, false, false))
      .find(_.getText == reference.text).get
    val referenceIndent = referenceLabel.getInsets.left
    assertNull(referenceLabel.getIcon)
    val resource = DbtNavigationItem(symbol, "analytics.orders")
    val resourceLabel = labels(renderer.getListCellRendererComponent(list, resource, 1, false, false))
      .find(_.getText == resource.text).get
    assertTrue(referenceIndent > resourceLabel.getInsets.left)
    assertNotNull(resourceLabel.getIcon)
  }
}
