// The settings plugin (`app.revanced.patches` in settings.gradle.kts) auto-
// applies the patches plugin to this project — no `plugins {}` block needed.

group = "com.example.mtga"
version = "0.1.0-SNAPSHOT"

patches {
    about {
        name = "MTGA Patches"
        description = "Truth Social ad blocker & feature mod (static-patch subset of mod/app)."
        source = "https://github.com/MaebashiRamens/mtga"
        author = "MaebashiRamens"
        contact = "shun819.mail@gmail.com"
        website = "https://github.com/MaebashiRamens/mtga"
        license = "GPL-3.0-or-later"
    }
}

// The patches plugin auto-adds a dependency on `app.revanced:patcher` but
// hard-codes version 21.0.0, which doesn't exist on GHP — the artifact was
// renamed from `revanced-patcher` to `patcher` and the new naming starts at
// 22.0.0. Force the resolved version up.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "app.revanced" && requested.name == "patcher") {
            useVersion("22.0.1")
            because("plugin requests 21.0.0 which was never published under the renamed artifact")
        }
    }
}

// Compile :common's source files directly into this project's output so the
// resulting .rvp contains `com.example.mtga.common.*` classes. The plugin
// jars only the project's own compiled classes — adding :common as a regular
// `implementation(project(":common"))` would resolve at compile time but the
// classes wouldn't end up inside the .rvp, causing NoClassDefFoundError when
// revanced-cli loads it.
sourceSets {
    named("main") {
        kotlin.srcDir("../../mod/common/src/main/kotlin")
    }
}
