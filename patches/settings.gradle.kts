// Standalone Gradle build for the MTGA ReVanced patches.
//
// Run from this directory (or via `mtga-build-patches` from the project root):
//   ./gradlew build
//
// Authentication: the ReVanced Gradle plugin and `revanced-patcher` runtime
// are only published to GitHub Packages. Set `gpr.user` / `gpr.key` in
// ~/.gradle/gradle.properties (a GitHub PAT with `read:packages` scope), or
// pass `GITHUB_ACTOR` / `GITHUB_TOKEN` env vars (which `mtga-build-patches`
// derives from the gh CLI).

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

plugins {
    // Settings plugin: configures this project as a ReVanced patches build,
    // auto-applies the patches plugin to it, and registers the build task
    // that emits build/libs/<group>-<version>.rvp.
    id("app.revanced.patches") version "1.0.0-dev.11"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "mtga-patches"

// The settings plugin auto-applies the patches plugin to a subproject
// **named exactly `patches`**. So our actual Kotlin source lives at
// patches/patches/src/main/kotlin/... and the inner subproject is what
// emits the .rvp.
include(":patches")

// :common is shared with the LSPosed module — see ../mod/common/. Patches
// compiles those sources directly into its own output (via a sourceSets
// srcDir entry in patches/patches/build.gradle.kts) so the resulting .rvp
// is self-contained without needing fat-jar bundling.
