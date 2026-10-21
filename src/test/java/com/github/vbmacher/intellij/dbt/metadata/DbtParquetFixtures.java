package com.github.vbmacher.intellij.dbt.metadata;

import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageTypeParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Run before packaged tests, in a separate JVM containing the unminimized writer dependencies.
 */
public final class DbtParquetFixtures {
    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[0]);
        Files.createDirectories(directory);
        var schema = MessageTypeParser.parseMessageType("message dbt { " + "optional binary unique_id (UTF8); optional binary name (UTF8); " + "optional binary package_name (UTF8); optional int64 ingested_at (TIMESTAMP(MICROS,true)); }");
        var factory = new SimpleGroupFactory(schema);
        var timestamp = java.time.Instant.parse("2099-01-01T00:00:00Z").getEpochSecond() * 1000000L;
        for (var version : WriterVersion.values()) {
            for (var codec : new CompressionCodecName[]{CompressionCodecName.UNCOMPRESSED, CompressionCodecName.SNAPPY, CompressionCodecName.GZIP, CompressionCodecName.ZSTD, CompressionCodecName.LZ4_RAW}) {
                var path = directory.resolve(version + "-" + codec + ".parquet");
                Files.deleteIfExists(path);
                try (var writer = ExampleParquetWriter.builder(new LocalOutputFile(path)).withConf(new PlainParquetConfiguration()).withType(schema).withCompressionCodec(codec).withWriterVersion(version).build()) {
                    for (int i = 1; i <= 20; i++) {
                        var name = "remote_" + i;
                        writer.write(factory.newGroup().append("unique_id", "model.analytics." + name).append("name", name).append("package_name", "analytics").append("ingested_at", timestamp));
                    }
                }
            }
        }
    }
}
