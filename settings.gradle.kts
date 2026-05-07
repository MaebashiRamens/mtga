// Composite-build umbrella so Android Studio / IntelliJ can open the whole
// repository in one shot. Each included build keeps its own settings.gradle.kts,
// repositories, and plugin management — so patches/'s GitHub Packages auth
// stays scoped to that build only and never affects mod/ resolution.
//
//   ./gradlew :mod:app:assembleDebug      builds the LSPosed module APK
//   ./gradlew :patches:patches:build      builds the ReVanced .rvp
//
// See ./mod/settings.gradle.kts and ./patches/settings.gradle.kts for the
// component-specific configuration.

rootProject.name = "mtga"

includeBuild("mod") {
    name = "mod"
}
includeBuild("patches") {
    name = "patches"
}
