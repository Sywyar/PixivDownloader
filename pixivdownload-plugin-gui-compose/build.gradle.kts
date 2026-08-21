import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
            check(entries.any { it.startsWith("lib/material3-desktop-") }) { "Material 3 runtime is missing" }
            check(entries.any { it.startsWith("lib/material-icons-extended-desktop-") }) {
                "Material icon runtime is missing"
            }
            check(entries.any { it.startsWith("lib/jna-") }) { "JNA runtime is missing" }
            check(entries.any { it.startsWith("lib/jna-platform-") }) { "JNA Platform runtime is missing" }
            check("cn/longzhengyi/windowsdecoration/BorderlessTitleBarScaffoldKt.class" in entries) {
                "Windows decoration implementation is missing"
            }
            check("META-INF/licenses/ComposeWindowsDecoration-LICENSE.txt" in entries) {
                "Windows decoration license is missing"
            }
            val classOwners = mutableMapOf<String, MutableList<String>>()
            zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".jar") }
                .forEach { library ->
                    ZipInputStream(zip.getInputStream(library)).use { nested ->
                        generateSequence(nested::getNextEntry)
                            .map { it.name }
                            .filter { it.endsWith(".class") && !it.endsWith("module-info.class") }
                            .forEach { className ->
                                classOwners.getOrPut(className, ::mutableListOf).add(library.name)
                            }
                    }
                }
            val duplicateClasses = classOwners.filterValues { it.size > 1 }
            check(duplicateClasses.isEmpty()) {
                "Plugin runtime libraries contain duplicate classes: $duplicateClasses"
            }
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
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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
val mavenClasspathFile = providers.gradleProperty("mavenClasspathFile").orNull
    ?.let(::file)
    ?: layout.projectDirectory.file("target/gradle-sdk-classpath.txt").asFile
val mavenClasspathFiles = mavenClasspathFile
    .takeIf(File::isFile)
    ?.let { classpathFile ->
        classpathFile.readText(Charsets.UTF_8)
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(::file)
    }
    ?: emptyList()
val mavenClasspath = files(mavenClasspathFiles)
val missingMavenClasspathFiles = mavenClasspathFiles.filterNot(File::exists)
val mavenClasspathReady = mavenClasspathFiles.isNotEmpty() && missingMavenClasspathFiles.isEmpty()
val idePluginApiClasses = layout.projectDirectory.dir("../pixivdownload-plugin-api/target/classes")
val sdkClasspath = if (mavenClasspathReady) mavenClasspath else files(idePluginApiClasses)
val idePf4jVersion = if (mavenClasspathReady) null else {
    val parentPom = providers.fileContents(layout.projectDirectory.file("../pom.xml")).asText.get()
    Regex("""<pf4j\.version>\s*([^<\s]+)\s*</pf4j\.version>""")
        .find(parentPom)?.groupValues?.get(1)
        ?: error("Missing pf4j.version in the Maven parent POM")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        val configuredLanguageVersion = providers.gradleProperty("kotlinLanguageVersion")
            .map(KotlinVersion::fromVersion)
        languageVersion.set(configuredLanguageVersion)
        apiVersion.set(configuredLanguageVersion)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-api")
}

dependencies {
    compileOnly(sdkClasspath)
    idePf4jVersion?.let { version ->
        compileOnly("org.pf4j:pf4j:$version") { isTransitive = false }
    }
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    val material3Version = providers.gradleProperty("composeMaterial3Version").get()
    implementation("org.jetbrains.compose.material3:material3:$material3Version")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    val skikoVersion = providers.gradleProperty("skikoVersion").get()
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-arm64:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:$skikoVersion")

    testImplementation(sdkClasspath)
    idePf4jVersion?.let { version ->
        testImplementation("org.pf4j:pf4j:$version") { isTransitive = false }
    }
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.compose.ui:ui-test:${providers.gradleProperty("composeVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyMavenClasspath = tasks.register("verifyMavenClasspath") {
    group = "verification"
    description = "Fails build tasks unless Maven supplied the exact SDK classpath."
    inputs.property("mavenClasspathFile", mavenClasspathFile.absolutePath)
    notCompatibleWithConfigurationCache("Reads the Maven-generated classpath at task execution time.")
    doLast {
        val classpathFile = File(inputs.properties.getValue("mavenClasspathFile").toString())
        val entries = classpathFile.takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.split(File.pathSeparatorChar)
            ?.filter(String::isNotBlank)
            ?.map(::File)
            ?: emptyList()
        check(entries.isNotEmpty()) {
            "Missing Maven-owned SDK classpath; invoke this module through Maven before running build tasks."
        }
        val missingEntries = entries.filterNot(File::exists)
        check(missingEntries.isEmpty()) {
            "Maven-owned SDK classpath contains missing entries: $missingEntries"
        }
    }
}

tasks.named("compileKotlin") { dependsOn(verifyMavenClasspath) }
tasks.named("compileJava") { dependsOn(verifyMavenClasspath) }

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
