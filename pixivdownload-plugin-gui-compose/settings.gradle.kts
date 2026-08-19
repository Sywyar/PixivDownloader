import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    plugins {
        kotlin("jvm") version providers.gradleProperty("kotlinVersion").get()
        id("org.jetbrains.kotlin.plugin.compose") version providers.gradleProperty("kotlinVersion").get()
        id("org.jetbrains.compose") version providers.gradleProperty("composeVersion").get()
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

if (providers.gradleProperty("mavenOffline").orNull == "true") {
    gradle.startParameter.isOffline = true
}

rootProject.name = "pixivdownload-plugin-gui-compose"
