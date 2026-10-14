package com.github.vbmacher.intellij.dbt.metadata

import io.airlift.compress.lz4.Lz4Decompressor
import io.airlift.compress.snappy.SnappyDecompressor
import io.airlift.compress.zstd.ZstdDecompressor
import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.compression.CompressionCodecFactory
import org.apache.parquet.compression.CompressionCodecFactory.{BytesInputCompressor, BytesInputDecompressor}
import org.apache.parquet.hadoop.metadata.CompressionCodecName

import java.io.{ByteArrayInputStream, IOException}
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/** Local metadata only needs page decompression, without Hadoop's configuration and codec runtime. */
private[metadata] object DbtParquetCodecs extends CompressionCodecFactory {
  private val MaxPageBytes = 128 * 1024 * 1024

  override def getCompressor(codec: CompressionCodecName): BytesInputCompressor =
    throw new UnsupportedOperationException("dbt metadata is read-only")

  override def getDecompressor(codec: CompressionCodecName): BytesInputDecompressor = new BytesInputDecompressor {
    private def checkedSize(size: Long): Int = {
      if (size < 0 || size > MaxPageBytes) throw new IOException("Parquet page exceeds the metadata size limit")
      size.toInt
    }

    private def decode(input: Array[Byte], size: Int): Array[Byte] = {
      checkedSize(size)
      if (codec == CompressionCodecName.UNCOMPRESSED) {
        if (input.length != size) throw new IOException("Invalid uncompressed Parquet page size")
        return input
      }
      val output = new Array[Byte](size)
      val count = codec match {
        case CompressionCodecName.SNAPPY => new SnappyDecompressor().decompress(input, 0, input.length, output, 0, size)
        case CompressionCodecName.ZSTD => new ZstdDecompressor().decompress(input, 0, input.length, output, 0, size)
        case CompressionCodecName.LZ4_RAW => new Lz4Decompressor().decompress(input, 0, input.length, output, 0, size)
        case CompressionCodecName.GZIP =>
          val stream = new GZIPInputStream(new ByteArrayInputStream(input))
          try {
            val bytes = stream.readNBytes(output, 0, size)
            if (stream.read() != -1) throw new IOException("Invalid decompressed Parquet page size")
            bytes
          } finally stream.close()
        case _ => throw new IOException(s"Unsupported dbt metadata compression: $codec")
      }
      if (count != size) throw new IOException("Invalid decompressed Parquet page size")
      output
    }

    override def decompress(bytes: BytesInput, size: Int): BytesInput = {
      val input = bytes.toInputStream.readNBytes(checkedSize(bytes.size()))
      BytesInput.from(decode(input, size))
    }

    override def decompress(input: ByteBuffer, compressedSize: Int, output: ByteBuffer, size: Int): Unit = {
      val bytes = new Array[Byte](checkedSize(compressedSize))
      input.get(bytes)
      output.put(decode(bytes, size))
    }

    override def release(): Unit = ()
  }

  override def release(): Unit = ()
}
