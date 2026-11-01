package com.github.vbmacher.intellij.dbt.metadata

import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

class DbtParquetCodecsTest {
    private fun gzip(bytes: ByteArray): ByteArray {
        val output =
            ByteArrayOutputStream(); GZIPOutputStream(output).use { it.write(bytes) }; return output.toByteArray()
    }

    @Test
    fun decompressesBufferSlicesAndConsumesOnlyThePage() {
        val expected = "orders".toByteArray(java.nio.charset.StandardCharsets.UTF_8);
        val compressed = gzip(expected)
        val input = ByteBuffer.allocateDirect(compressed.size + 2); input.put(1.toByte()).put(compressed)
            .put(2.toByte()).flip(); input.position(1)
        val output = ByteBuffer.allocateDirect(expected.size + 2); output.position(1)
        DbtParquetCodecs.getDecompressor(CompressionCodecName.GZIP)
            .decompress(input, compressed.size, output, expected.size)
        assertEquals(compressed.size + 1, input.position()); assertEquals(expected.size + 1, output.position())
        output.flip().position(1);
        val actual = ByteArray(expected.size); output.get(actual); assertArrayEquals(expected, actual)
    }

    @Test
    fun rejectsIncorrectAndOversizedPageLengths() {
        val bytes = BytesInput.from(gzip(byteArrayOf(1, 2, 3)));
        val decoder = DbtParquetCodecs.getDecompressor(CompressionCodecName.GZIP)
        for (size in listOf(
            -1,
            2,
            4,
            128 * 1024 * 1024 + 1
        )) assertThrows(IOException::class.java) { decoder.decompress(bytes, size) }
        assertThrows(IOException::class.java) {
            DbtParquetCodecs.getDecompressor(CompressionCodecName.UNCOMPRESSED)
                .decompress(BytesInput.from(byteArrayOf(1, 2)), 3)
        }
    }
}
