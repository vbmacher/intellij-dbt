package com.github.vbmacher.intellij.dbt.metadata

import org.apache.parquet.column.ParquetProperties.WriterVersion
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageTypeParser
import java.nio.file.Files
import java.nio.file.Path

/** Runs before packaged tests in a JVM containing unminimized writer dependencies. */
object DbtParquetFixtures {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0]); Files.createDirectories(directory)
        val schema =
            MessageTypeParser.parseMessageType("message dbt { optional binary unique_id (UTF8); optional binary name (UTF8); optional binary package_name (UTF8); optional int64 ingested_at (TIMESTAMP(MICROS,true)); }")
        val factory = SimpleGroupFactory(schema)
        val timestamp = java.time.Instant.parse("2099-01-01T00:00:00Z").epochSecond * 1000000L
        for (version in WriterVersion.values()) for (codec in listOf(
            CompressionCodecName.UNCOMPRESSED,
            CompressionCodecName.SNAPPY,
            CompressionCodecName.GZIP,
            CompressionCodecName.ZSTD,
            CompressionCodecName.LZ4_RAW
        )) {
            val path = directory.resolve("$version-$codec.parquet"); Files.deleteIfExists(path)
            ExampleParquetWriter.builder(LocalOutputFile(path)).withConf(PlainParquetConfiguration()).withType(schema)
                .withCompressionCodec(codec).withWriterVersion(version).build().use { writer ->
                for (i in 1..20) {
                    val name = "remote_$i"; writer.write(
                        factory.newGroup().append("unique_id", "model.analytics.$name").append("name", name)
                            .append("package_name", "analytics").append("ingested_at", timestamp)
                    )
                }
            }
        }
    }
}
