plugins { java }

group = "com.example.pixivdownload"
version = "0.1.0"

repositories { mavenCentral() }
dependencies {
    compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:@SDK_VERSION@")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val checkJavaScript by tasks.registering(Exec::class) {
    commandLine("node", "--check", "src/main/resources/static/example-minimal/example-minimal-init.js")
}
tasks.check { dependsOn(checkJavaScript) }

for (action in listOf("run", "debug")) {
    tasks.register<JavaExec>("${action}Plugin") {
        group = "application"
        dependsOn(tasks.jar, tasks.check)
        classpath = files("../../tools/sdk-tools.jar")
        jvmArgs("-Dfile.encoding=UTF-8")
        args(action, projectDir.absolutePath, tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
}
tasks.register<JavaExec>("stopPlugin") {
    group = "application"
    classpath = files("../../tools/sdk-tools.jar")
    jvmArgs("-Dfile.encoding=UTF-8")
    args("stop", projectDir.absolutePath)
}
