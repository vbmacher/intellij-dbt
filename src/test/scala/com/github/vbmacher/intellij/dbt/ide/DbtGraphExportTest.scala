package com.github.vbmacher.intellij.dbt.ide

import java.awt.datatransfer.{DataFlavor, Transferable, UnsupportedFlavorException}
import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, InputStream}
import javax.imageio.ImageIO
import org.junit.Test
import org.junit.Assert._

final class DbtGraphExportTest {
  private def png(): Array[Byte] = {
    val image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 0x40708090)
    image.setRGB(1, 0, 0xff123456)
    val output = new ByteArrayOutputStream
    assertTrue(ImageIO.write(image, "png", output))
    output.toByteArray
  }

  @Test def normalCopyOffersEncodedPngAndStandardImageWithExactPixels(): Unit = {
    val bytes = png()
    var calls = 0
    DbtGraphExport.copyPng(bytes, content => {
      calls += 1
      assertEquals(Vector(DbtGraphExport.PngFlavor, DataFlavor.imageFlavor), content.getTransferDataFlavors.toVector)
      assertTrue(content.isDataFlavorSupported(DataFlavor.imageFlavor))
      val image = content.getTransferData(DataFlavor.imageFlavor).asInstanceOf[BufferedImage]
      assertEquals(0x40708090, image.getRGB(0, 0))
      assertEquals(0xff123456, image.getRGB(1, 0))
      val stream = content.getTransferData(DbtGraphExport.PngFlavor).asInstanceOf[InputStream]
      try assertArrayEquals(bytes, stream.readAllBytes()) finally stream.close()
      assertThrows(classOf[UnsupportedFlavorException], () => content.getTransferData(DataFlavor.stringFlavor))
    })
    assertEquals(1, calls)
  }

  @Test def failedNativeImageMappingRetriesOnlyEncodedPngWithIndependentStreams(): Unit = {
    val bytes = png()
    val expected = bytes.clone()
    var attempts = Vector.empty[Transferable]
    DbtGraphExport.copyPng(bytes, content => {
      attempts :+= content
      if (content.isDataFlavorSupported(DataFlavor.imageFlavor))
        throw new NullPointerException("Native clipboard ImageWriter unavailable")
    })
    assertEquals(2, attempts.size)
    val encoded = attempts.last
    assertEquals(Vector(DbtGraphExport.PngFlavor), encoded.getTransferDataFlavors.toVector)
    assertEquals("image/png", DbtGraphExport.PngFlavor.getPrimaryType + "/" + DbtGraphExport.PngFlavor.getSubType)
    assertTrue(DbtGraphExport.PngFlavor.isRepresentationClassInputStream)
    assertFalse(encoded.isDataFlavorSupported(DataFlavor.imageFlavor))
    assertThrows(classOf[UnsupportedFlavorException], () => encoded.getTransferData(DataFlavor.imageFlavor))
    java.util.Arrays.fill(bytes, 0.toByte)
    val first = encoded.getTransferData(DbtGraphExport.PngFlavor).asInstanceOf[InputStream]
    try assertArrayEquals(expected, first.readAllBytes()) finally first.close()
    val next = encoded.getTransferData(DbtGraphExport.PngFlavor).asInstanceOf[InputStream]
    try {
      val image = ImageIO.read(next)
      assertEquals(2, image.getWidth)
      assertEquals(0x40708090, image.getRGB(0, 0))
    } finally next.close()
  }

  @Test def failureOfBothNativeAndEncodedPublicationIsNotSuppressed(): Unit = {
    val failure = new IllegalStateException("Clipboard unavailable")
    var calls = 0
    val error = assertThrows(classOf[IllegalStateException], () => DbtGraphExport.copyPng(png(), _ => {
      calls += 1
      throw failure
    }))
    assertSame(failure, error)
    assertEquals(2, calls)
  }
}
