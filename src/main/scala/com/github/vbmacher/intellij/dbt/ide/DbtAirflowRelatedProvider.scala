package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.navigation.{GotoRelatedItem, GotoRelatedProvider}
import com.intellij.openapi.actionSystem.{CommonDataKeys, DataContext}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import scala.jdk.CollectionConverters._

final class DbtAirflowRelatedProvider extends GotoRelatedProvider {
  override def getItems(context: DataContext): java.util.List[_ <: GotoRelatedItem] = Option(CommonDataKeys.PROJECT.getData(context)).toVector.flatMap { project =>
    val w = DbtWorkspace.get(project)
    val offset = Option(CommonDataKeys.EDITOR.getData(context)).map(_.getCaretModel.getOffset).getOrElse(0)
    val resource = DbtImpactUi.context(w, Option(CommonDataKeys.PSI_FILE.getData(context)), offset)
    resource.toVector.flatMap(DbtAirflow.context(w, _)).flatMap { task =>
      val definition = Option(LocalFileSystem.getInstance.findFileByPath(task.evidence))
        .flatMap(f => Option(PsiManager.getInstance(w.project).findFile(f)))
        .flatMap(f => Option(f.findElementAt(task.offset))).map { at =>
          new GotoRelatedItem(at, "Airflow DAGs / tasks") {
            override def getCustomName: String = task.dag + " / " + task.task
            override def getCustomContainerName: String = task.evidence + ":" + task.line
          }
        }.toVector
      val browser = task.url.toVector.map { url =>
        new GotoRelatedItem(null, "Airflow task links", -1) {
          override def getCustomName: String = "Open " + task.dag + " / " + task.task
          override def getCustomContainerName: String = task.schedule
          override def navigate(): Unit = com.intellij.ide.BrowserUtil.browse(url)
        }
      }
      definition ++ browser
    }
  }.asJava
}
