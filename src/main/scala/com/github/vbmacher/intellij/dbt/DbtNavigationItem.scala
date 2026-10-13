package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.ui.popup.ListItemDescriptor
import com.intellij.ui.popup.list.GroupedItemsListRenderer
import com.intellij.util.ui.JBUI
import javax.swing.{Icon, JList}

private[dbt] final case class DbtNavigationItem(target: DbtSymbolElement, text: String,
  reference: Boolean = false, separator: Option[String] = None) {
  override def toString: String = text
}

private[dbt] final class DbtNavigationItemRenderer extends GroupedItemsListRenderer[DbtNavigationItem](
  new ListItemDescriptor[DbtNavigationItem] {
    override def getTextFor(item: DbtNavigationItem): String = item.text

    override def getTooltipFor(item: DbtNavigationItem): String =
      if (item.reference) item.target.symbol.file.getVirtualFile.getPath
      else s"${item.target.symbol.kind}: ${item.text}"

    override def getIconFor(item: DbtNavigationItem): Icon = if (item.reference) null else item.target.getIcon(0)

    override def hasSeparatorAboveOf(item: DbtNavigationItem): Boolean = item.separator.nonEmpty

    override def getCaptionAboveOf(item: DbtNavigationItem): String = item.separator.orNull
  }) {
  override protected def customizeComponent(list: JList[_ <: DbtNavigationItem], item: DbtNavigationItem,
    selected: Boolean): Unit = {
    myTextLabel.setBorder(JBUI.Borders.emptyLeft(if (item.reference) 28 else 0))
  }
}
