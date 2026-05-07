pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "mtga-mod"

// Layout: this project tree corresponds to the LSPosed/Xposed module APK.
// The `:patches` ReVanced bundle lives in a separate Gradle root at
// ../patches/ — see ../patches/settings.gradle.kts.

include(":common")
include(":app")
include(":stub")
