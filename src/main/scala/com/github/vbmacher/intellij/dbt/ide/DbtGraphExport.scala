package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.fileChooser.{FileChooserFactory, FileSaverDescriptor}
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.{DataFlavor, Transferable, UnsupportedFlavorException}
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import scala.util.Try

private[ide] object DbtGraphExport {
  def show(project: Project, format: String, value: String): Unit = {
    val kind = if (format == "clipboard") "png" else format
    val prefix = if (kind == "png") "data:image/png;base64," else "data:image/svg+xml;base64,"
    if (!Set("png", "svg")(kind) || !value.startsWith(prefix) || value.length > 28000000) return
    Try(Base64.getDecoder.decode(value.substring(prefix.length))).toOption.foreach { bytes =>
      if (format == "clipboard") Option(ImageIO.read(new ByteArrayInputStream(bytes))).foreach { image =>
        CopyPasteManager.getInstance().setContents(new Transferable {
          override def getTransferDataFlavors: Array[DataFlavor] = Array(DataFlavor.imageFlavor)
          override def isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
          override def getTransferData(flavor: DataFlavor): AnyRef =
            if (isDataFlavorSupported(flavor)) image else throw new UnsupportedFlavorException(flavor)
        })
      }
      else Option(FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Export dbt lineage", "Save the visible graph", kind), project)
        .save("lineage." + kind)).foreach(file => Files.write(file.getFile.toPath, bytes))
    }
  }
}
