# Preserve the plugin's registered extensions, reflective APIs and stack traces.
-dontoptimize
-dontobfuscate
-keepattributes *
-keepdirectories
-keep class com.github.vbmacher.intellij.dbt.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# IntelliJ also calls these shared libraries, including package-private Guava helpers.
-keep class com.google.common.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.hubspot.jinjava.el.** { *; }
# Parquet's Packer selects generated implementations and their factory fields by name.
-keep class org.apache.parquet.column.values.bitpacking.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Unused dependency APIs refer to optional Hadoop, native and adapter libraries.
# Plugin references remain checked here; testPackaged and Plugin Verifier check the output.
-dontwarn !com.github.vbmacher.intellij.dbt.**,**
-dontnote
