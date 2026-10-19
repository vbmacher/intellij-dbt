package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.fileChooser.{FileChooserFactory, FileSaverDescriptor}
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.{DataFlavor, Transferable, UnsupportedFlavorException}
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import scala.util.Try
import scala.util.control.NonFatal

private[ide] object DbtGraphExport {
  private[ide] val PngFlavor = new DataFlavor("image/png;class=java.io.InputStream", "PNG image")

  private[ide] def copyPng(bytes: Array[Byte], publish: Transferable => Unit): Unit =
    Option(ImageIO.read(new ByteArrayInputStream(bytes))).foreach { image =>
      val png = bytes.clone()
      def content(withImage: Boolean): Transferable = new Transferable {
        override def getTransferDataFlavors: Array[DataFlavor] =
          if (withImage) Array(PngFlavor, DataFlavor.imageFlavor) else Array(PngFlavor)
        override def isDataFlavorSupported(flavor: DataFlavor): Boolean =
          flavor == PngFlavor || withImage && flavor == DataFlavor.imageFlavor
        override def getTransferData(flavor: DataFlavor): AnyRef =
          if (flavor == PngFlavor) new ByteArrayInputStream(png)
          else if (withImage && flavor == DataFlavor.imageFlavor) image
          else throw new UnsupportedFlavorException(flavor)
      }
      try publish(content(withImage = true))
      catch {
        // Some native image mappings fail while enumerating unrelated ImageIO writers.
        // Publish the existing PNG bytes directly, without changing global providers or mappings.
        case NonFatal(_) => publish(content(withImage = false))
      }
    }

  def show(project: Project, format: String, value: String): Unit = {
    val kind = if (format == "clipboard") "png" else format
    val prefix = if (kind == "png") "data:image/png;base64," else "data:image/svg+xml;base64,"
    if (!Set("png", "svg")(kind) || !value.startsWith(prefix) || value.length > 28000000) return
    Try(Base64.getDecoder.decode(value.substring(prefix.length))).toOption.foreach { bytes =>
      if (format == "clipboard") copyPng(bytes, CopyPasteManager.getInstance().setContents)
      else Option(FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Export dbt lineage", "Save the visible graph", kind), project)
        .save("lineage." + kind)).foreach(file => Files.write(file.getFile.toPath, bytes))
    }
  }
}
