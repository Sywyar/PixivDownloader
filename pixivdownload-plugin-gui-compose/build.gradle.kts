import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

abstract class VerifyPluginArtifact : DefaultTask() {
    @get:InputFile
    abstract val pluginArtifact: RegularFileProperty

    @TaskAction
    fun verify() {
        ZipFile(pluginArtifact.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            check("plugin.properties" in entries) { "plugin.properties must be at the artifact root" }
            check(entries.any { it == "top/sywyar/pixivdownload/guicompose/GuiComposePf4jPlugin.class" }) {
                "PF4J entry class is missing"
            }
            check(entries.any { it.startsWith("lib/ui-desktop-") }) { "Compose UI runtime is missing" }
            listOf("windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-arm64").forEach { target ->
                check(entries.any { it.startsWith("lib/skiko-awt-runtime-$target-") }) {
                    "Skiko native runtime is missing for $target"
                }
            }
            check(entries.none { it.startsWith("top/sywyar/pixivdownload/plugin/api/") }) {
                "plugin-api classes must remain host-provided"
            }
            val descriptor = Properties().also { properties ->
                zip.getInputStream(zip.getEntry("plugin.properties")).use(properties::load)
            }
            check(descriptor.getProperty("plugin.id") == "gui-compose") { "unexpected plugin id" }
            check(descriptor.getProperty("pixiv.lifecycle-policy") == "process-restart") {
                "desktop UI providers must use process-restart lifecycle"
            }
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "top.sywyar.lovepopup"
version = providers.gradleProperty("mavenVersion").orElse("1.0.0").get()

val projectDirectoryPath = layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()
val mavenBuildDirectory = providers.gradleProperty("mavenBuildDirectory").orNull
    ?.let(::file)
    ?: layout.projectDirectory.dir("target").asFile
val mavenBuildDirectoryPath = mavenBuildDirectory.toPath().toAbsolutePath().normalize()
require(mavenBuildDirectoryPath.startsWith(projectDirectoryPath) && mavenBuildDirectoryPath != projectDirectoryPath) {
    "Maven build directory must remain below the plugin module: $mavenBuildDirectoryPath"
}
val mavenFinalName = providers.gradleProperty("mavenFinalName")
    .orElse("pixivdownload-plugin-gui-compose-$version")
    .get()
require(mavenFinalName.isNotBlank() && File(mavenFinalName).name == mavenFinalName) {
    "Maven final name must be a plain file name: $mavenFinalName"
}
val mavenClasspathFiles = providers.gradleProperty("mavenClasspathFile").orNull
    ?.let(::file)
    ?.takeIf(File::isFile)
    ?.let { classpathFile ->
        classpathFile.readText(Charsets.UTF_8)
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(::file)
    }
    ?: emptyList()
val mavenClasspath = files(mavenClasspathFiles)
require(mavenClasspathFiles.isNotEmpty() || gradle.startParameter.taskNames.all { it == "wrapper" }) {
    "Missing Maven-owned SDK classpath; invoke this module through Maven or pass -PmavenClasspathFile=<file>."
}

kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-api")
}

dependencies {
    compileOnly(mavenClasspath)
    implementation(compose.desktop.currentOs)
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.144.6")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-arm64:0.144.6")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.144.6")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:0.144.6")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.144.6")

    testImplementation(mavenClasspath)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    enabled = providers.gradleProperty("mavenSkipTests").orNull != "true"
            && providers.gradleProperty("mavenTestSkip").orNull != "true"
}

tasks.named<Jar>("jar") { enabled = false }

val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Builds the reproducible PF4J JAR-with-lib artifact."
    dependsOn(tasks.named("classes"))
    archiveFileName.set("$mavenFinalName.jar")
    destinationDirectory.set(mavenBuildDirectory)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.get().output)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}

val stagePluginDevelopment = tasks.register<Sync>("stagePluginDevelopment") {
    group = "build"
    description = "Stages the PF4J development layout under Maven target/classes."
    dependsOn(tasks.named("classes"))
    into(mavenBuildDirectory.resolve("classes"))
    from(sourceSets.main.get().output)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}

val verifyPluginArtifact = tasks.register<VerifyPluginArtifact>("verifyPluginArtifact") {
    group = "verification"
    description = "Checks the real plugin artifact and its private dependency boundary."
    dependsOn(pluginJar)
    pluginArtifact.set(pluginJar.flatMap(Jar::getArchiveFile))
}

tasks.register("compilePlugin") {
    group = "build"
    dependsOn(tasks.named("classes"))
}
tasks.register("mavenTest") {
    group = "verification"
    dependsOn(tasks.named("test"))
}
tasks.register("assemblePlugin") {
    group = "build"
    dependsOn(pluginJar)
}
tasks.register("checkPlugin") {
    group = "verification"
    dependsOn(tasks.named("check"), verifyPluginArtifact)
}
tasks.register<Delete>("cleanPlugin") {
    delete(layout.buildDirectory, mavenBuildDirectory)
}

tasks.named("assemble") { dependsOn(pluginJar) }
tasks.named("check") { dependsOn(verifyPluginArtifact) }
