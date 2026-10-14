package com.github.vbmacher.intellij.dbt.metadata

import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

final class DbtParquetCodecsTest {
  private def gzip(bytes: Array[Byte]): Array[Byte] = {
    val output = new ByteArrayOutputStream
    val stream = new GZIPOutputStream(output)
    try stream.write(bytes) finally stream.close()
    output.toByteArray
  }

  @Test def decompressesBufferSlicesAndConsumesOnlyThePage(): Unit = {
    val expected = "orders".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val compressed = gzip(expected)
    val input = ByteBuffer.allocateDirect(compressed.length + 2)
    input.put(1.toByte).put(compressed).put(2.toByte).flip()
    input.position(1)
    val output = ByteBuffer.allocateDirect(expected.length + 2)
    output.position(1)
    DbtParquetCodecs.getDecompressor(CompressionCodecName.GZIP).decompress(input, compressed.length, output, expected.length)
    assertEquals(compressed.length + 1, input.position())
    assertEquals(expected.length + 1, output.position())
    output.flip().position(1)
    val actual = new Array[Byte](expected.length)
    output.get(actual)
    assertArrayEquals(expected, actual)
  }

  @Test def rejectsIncorrectAndOversizedPageLengths(): Unit = {
    val bytes = BytesInput.from(gzip(Array[Byte](1, 2, 3)))
    val decoder = DbtParquetCodecs.getDecompressor(CompressionCodecName.GZIP)
    for (size <- Vector(-1, 2, 4, 128 * 1024 * 1024 + 1))
      assertThrows(classOf[IOException], () => decoder.decompress(bytes, size))
    assertThrows(classOf[IOException], () => DbtParquetCodecs.getDecompressor(CompressionCodecName.UNCOMPRESSED)
      .decompress(BytesInput.from(Array[Byte](1, 2)), 3))
  }
}
