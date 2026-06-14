# MTGA

LSPosed module + ReVanced/Morphe patches for Truth Social on Android. Both vectors read obfuscated-symbol coordinates from `mod/common/`, so calibrating one release updates both.

- `mod/` — Gradle root for the LSPosed module (Xposed hooks). `mod/common/.../Targets.kt` holds the calibrated symbol coordinates.
- `patches/` — ReVanced patch bundle (`.rvp`), built with `./gradlew :patches:patches:build`.
- `flake.nix`, `nix/scripts/` — the dev environment and the `mtga-*` task scripts.

## Dev shell commands

The dev shell is entered automatically via direnv (`.envrc` runs `use flake`), so the `mtga-*` commands below are on `PATH` already. Run them directly; do not re-enter `nix develop` or prefix with `nix run`. The shell prints no banner — this section is the reference for what exists.

Drive these yourself instead of asking the user to run them, unless the step needs a human at the device (a Magisk prompt, a manual reboot) or the user asked to do it.

| Command | What it does |
|---|---|
| `mtga-setup` | One-time: build a rooted Android 14 AVD (Magisk + Zygisk + LSPosed). ~10 min. |
| `mtga-start` | Boot the rooted emulator. |
| `mtga-deploy` | Build the Xposed module and install it on the running emulator. |
| `mtga-install-app <apk\|apkm\|xapk>` | Install a Truth Social build on the emulator. |
| `mtga-build-patches [gradle args]` | Build the MTGA `.rvp` bundle. Needs `gh` auth with `read:packages`. |
| `mtga-patch-app <apk\|apkm\|xapk> [out.apk]` | Apply the latest `.rvp` to an input via revanced-cli. |

The same tasks are exposed as flake apps (`nix run .#setup`, `.#deploy`, …) for use outside the shell.

### Env vars the shell exports

- `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `JAVA_HOME` (jdk17), `ANDROID_AVD_HOME`
- `MTGA_REVANCED_CLI`, `MTGA_APK_EDITOR`, `MTGA_APK_SIGNER` — JAR paths for ad-hoc revanced-cli / APKEditor / uber-apk-signer invocations
- `mod/local.properties` is regenerated on shell entry with `sdk.dir`

## Build & format

- LSPosed module: `cd mod && ./gradlew assembleDebug` (or use `mtga-deploy` to build + install).
- Patches: `./gradlew :patches:patches:build`.
- Format: `treefmt` (or `nix fmt`). `nix flake check` runs the formatting check.

## Calibrating a new release

Uncalibrated versions abort hook installation with a Toast. To support a new Truth Social build, add a `TargetSet` to `mod/common/src/main/kotlin/com/example/mtga/common/Targets.kt`; this feeds both the LSPosed and ReVanced vectors.
