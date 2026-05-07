# Make Truth Great Again

Truth Social (Android) LSPosed module + ReVanced-style patches.

## Requirements

- x86_64 Linux with KVM
- Nix (flakes enabled)

## Quick start

```bash
nix run .#setup                                # one-time rooted AVD
nix run .#start                                # boot emulator
nix run .#install-app -- truth-social.apkm     # install Truth Social
nix run .#deploy                               # build + install MTGA module
```

## Commands

| Command | Purpose |
|---|---|
| `nix run .#setup` | Provision a rooted Android 14 AVD with Magisk + Zygisk + LSPosed. State at `$XDG_DATA_HOME/mtga/avd/34/`. |
| `nix run .#start` | Boot the rooted emulator. Extra flags after `--`. |
| `nix run .#install-app -- <file>` | Install `.apk` / `.apkm` / `.xapk` (auto-picks the x86_64 split). |
| `nix run .#deploy` | Build the LSPosed module (`mod/app`) and `adb install -r` it. |
| `nix run .#build-patches` | Build the ReVanced `.rvp` (uses gh CLI auth — needs `read:packages`). |
| `nix run .#patch-app -- <bundle> [out.apk]` | Apply the latest `.rvp` to a Truth Social bundle via revanced-cli. Falls back to merge+sign smoke test if no `.rvp` is present. |
| `nix develop` | Dev shell: Android SDK, Gradle, apktool, jadx, gh, revanced-cli, treefmt. |

## Project layout

Two independent Gradle projects:

- **`mod/`** — LSPosed/Xposed module. Standard Android toolchain. Subprojects: `:common` (shared `Targets`/`TargetSet`/`SettingKeys` registry), `:app` (runtime hooks), `:stub` (IDE resolution).
- **`patches/`** — ReVanced patches bundle. Pulls the `app.revanced.patches` plugin from GitHub Packages and emits `patches/patches/build/libs/*.rvp`. Recompiles `mod/common/src/` directly via `sourceSets.srcDir`, so the same registry drives both vectors.

## Target builds

| versionName | versionCode | base.apk SHA-256 |
|---|---|---|
| 1.24.8 | 1228 | `bcca813e2920602f0a9908240c537dc1d9ee6b6a90213e2b0be03e6458f35c1a` |
| 1.24.6 | 1226 | `6108f4127e7ec04be40454ab083bfde870f0055ce7e2511e9f730418c2d2cc93` |

Hook / patch coordinates live in `mod/common/.../Targets.kt` keyed by
versionCode; an unknown version aborts hook installation with a Toast
warning.
