plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compose compiler plugin — needed for Kotlin 2.x. With the plugin
    // applied we do NOT set composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.mtga"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.mtga"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Compose + Material 3 + Compose-Settings + Reorderable adds
            // ~17 MB unminified. R8 shrinks that to a small fraction by
            // dropping unreferenced Compose components and Material icons.
            // LSPosed entry points live under com.example.mtga.* and are
            // entered via reflection; keep that subtree intact.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":common"))

    // LSPosed / Xposed API (compile-only, provided by framework at runtime)
    compileOnly("de.robv.android.xposed:api:82")

    compileOnly(project(":stub"))

    // Compose / Material 3 stack for SettingsActivity.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pre-built Material 3 settings rows used for SettingsSwitch / Group.
    // 3.x requires compileSdk 36 (AGP 8.7.3 caps at 35), so we stay on 2.10.0,
    // the latest 2.x line still compatible with compileSdk 35.
    implementation("com.github.alorma.compose-settings:ui-tiles:2.10.0")
    // Drag-to-reorder LazyColumn for the bottom-bar tab list.
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
}
