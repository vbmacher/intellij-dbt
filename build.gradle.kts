import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.vbmacher"
version = "1.3.0-SNAPSHOT"

val platformBuild = "262.9437.185"
val localPlatform = providers.gradleProperty("intellijPlatformLocalPath")
val pythonNavigationTest = "com.github.vbmacher.intellij.dbt.navigation.python.DbtPythonNavigationTest"
val packagedRuntimeTest = "com.github.vbmacher.intellij.dbt.metadata.DbtPackagedRuntimeTest"
val testPluginIds =
    "com.github.vbmacher.intellij-dbt,org.jetbrains.plugins.yaml,org.jetbrains.plugins.textmate,com.intellij.modules.json,intellij.libraries.misc.plugin,intellij.structureView.plugin,intellij.testRunner.plugin,intellij.execution.serviceView.plugin,intellij.navbar.plugin"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val proguard = configurations.create("proguard")

dependencies {
    intellijPlatform {
        if (localPlatform.isPresent) local(localPlatform.get())
        else intellijIdea("2026.2.1")
        bundledPlugins(
            "org.jetbrains.plugins.yaml", "org.jetbrains.plugins.textmate",
            "com.intellij.modules.json", "intellij.libraries.misc.plugin", "intellij.structureView.plugin",
            "com.intellij.modules.jcef", "intellij.execution.serviceView.plugin", "intellij.navbar.plugin",
            "intellij.testRunner.plugin"
        )
        testFramework(TestFrameworkType.Platform, platformBuild)
        pluginVerifier()
        zipSigner()
    }
    implementation("com.hubspot.jinjava:jinjava:2.8.4")
    implementation("com.github.jsqlparser:jsqlparser:5.4")
    implementation("org.apache.parquet:parquet-hadoop:1.18.1")
    implementation("org.apache.hadoop:hadoop-client-api:3.5.0")
    testImplementation("org.apache.hadoop:hadoop-client-runtime:3.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    listOf("test-framework-core", "test-framework-team-city", "test-framework-common").forEach {
        testImplementation("com.jetbrains.intellij.platform:$it:$platformBuild") { isTransitive = false }
    }
    listOf("codeowners-runtime-resolver", "codeowners-monorepo-resolver").forEach {
        testImplementation("com.jetbrains.intellij.codeowners:$it:$platformBuild") { isTransitive = false }
    }
    proguard("com.guardsquare:proguard-base:7.10.0")
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
tasks.named<KotlinCompile>("compileKotlin") {
    // Preserve defensive null handling at Java-facing callbacks before Kotlin checks their arguments.
    compilerOptions.freeCompilerArgs.add("-Xno-param-assertions")
}
tasks.named<KotlinCompile>("compileTestKotlin") {
    // JUnit 3 treats zero-argument invokedynamic helpers starting with "test" as test methods.
    compilerOptions.freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
}

intellijPlatform {
    projectName = "intellij-dbt"
    buildSearchableOptions = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion { sinceBuild = "262.8665"; untilBuild = "262.*" }
    }
    pluginVerification {
        ides {
            val verificationIde = providers.gradleProperty("verificationIde")
            if (verificationIde.isPresent) local(file(verificationIde.get())) else current()
        }
    }
    publishing { token = providers.environmentVariable("IJ_PLUGIN_REPO_TOKEN") }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

tasks.jar { archiveFileName.set("intellij-dbt.jar") }
val minimizeRuntime = tasks.register<MinimizeRuntime>("minimizeRuntime") {
    pluginJar.set(tasks.named<ComposedJarTask>("composedJar").flatMap { it.archiveFile })
    runtimeClasspath.from(configurations.runtimeClasspath)
    libraryClasspath.from(configurations.compileClasspath)
    toolClasspath.from(proguard)
    rules.set(layout.projectDirectory.file("gradle/plugin.pro"))
    javaHome.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.metadata.installationPath.asFile.absolutePath })
    outputJar.set(layout.buildDirectory.file("minimized-plugin/dbt-runtime.jar"))
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from("src/main/textmate/dbt") { into("${pluginName.get()}/textmate/dbt") }
}
tasks.named<PrepareSandboxTask>("prepareSandbox") {
    runtimeClasspath.setFrom(minimizeRuntime.flatMap { it.outputJar })
}

tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "4g"
    maxParallelForks = 1
    systemProperty("idea.load.plugins.id", testPluginIds)
    testLogging {
        events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.test {
    exclude("**/DbtPythonNavigationTest.class", "**/DbtPackagedRuntimeTest.class")
}

/** Write Parquet fixtures with writer dependencies that must stay outside the packaged reader runtime. */
val generatePackagedFixtures = tasks.register<JavaExec>("generatePackagedFixtures") {
    dependsOn(tasks.testClasses, tasks.named("prepareTestSandbox"))
    classpath = files(provider { tasks.test.get().classpath })
    mainClass.set("com.github.vbmacher.intellij.dbt.metadata.DbtParquetFixtures")
    args(layout.buildDirectory.dir("packaged-fixtures").get().asFile.absolutePath)
    outputs.dir(layout.buildDirectory.dir("packaged-fixtures"))
}

intellijPlatformTesting {
    testIde.register("testPackaged") {
        testFramework(TestFrameworkType.Platform, platformBuild)
        prepareSandboxTask { runtimeClasspath.setFrom(minimizeRuntime.flatMap { it.outputJar }) }
        task {
            testClassesDirs = tasks.test.get().testClassesDirs
            dependsOn(generatePackagedFixtures)
            exclude(
                "**/DbtPythonNavigationTest.class",
                "**/DbtParquetNavigationTest.class",
                "**/DbtParquetCodecsTest.class"
            )
            val originalRuntime = configurations.runtimeClasspath
            val packagedSandbox = tasks.named<PrepareSandboxTask>("prepareSandbox_testPackaged")
            val pluginJarName = tasks.named<ComposedJarTask>("composedJar").get().archiveFile.get().asFile.name
            val packagedPluginJar = packagedSandbox.flatMap { it.pluginDirectory }.map { it.file("lib/$pluginJarName") }
            val packagedRuntimeJar = packagedSandbox.flatMap { it.pluginDirectory }.map { it.file("lib/dbt-runtime.jar") }
            dependsOn(packagedSandbox)
            // Test-only dependencies remain; production code and readers must come from the packaged sandbox.
            classpath = (classpath + sourceSets.test.get().output).filter {
                it !in sourceSets.main.get().output.files && it !in originalRuntime.get().files && !it.name.startsWith("hadoop-client-runtime-")
            } + files(packagedPluginJar, packagedRuntimeJar)
            doFirst { systemProperty("dbt.packaged.jar", packagedPluginJar.get().asFile.absolutePath) }
            systemProperty(
                "dbt.packaged.fixtures",
                layout.buildDirectory.dir("packaged-fixtures").get().asFile.absolutePath
            )
        }
    }
    testIde.register("testPython") {
        testFramework(TestFrameworkType.Platform, platformBuild)
        plugins { plugin("PythonCore:$platformBuild") }
        task {
            testClassesDirs = tasks.test.get().testClassesDirs
            classpath += sourceSets.test.get().output
            include("**/DbtPythonNavigationTest.class")
            systemProperty("idea.load.plugins.id", "$testPluginIds,PythonCore,org.toml.lang,com.intellij.modules.jcef")
        }
    }
}

tasks.buildPlugin {
    archiveBaseName.set("intellij-dbt")
    doLast { check(archiveFile.get().asFile.length() <= 20_000_000L) { "Plugin ZIP exceeds the 20 MB release size limit" } }
}
