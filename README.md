# Make Truth Great Again

Mods for Truth Social on Android: a LSPosed module for rooted devices and a re-signed APK via ReVanced patches for everyone else. Both vectors pull obfuscated-symbol coordinates from `mod/common/`, so calibrating a new Truth Social release updates both at once.

**Features**

- Block ads at the data layer (`AdQueueManager`) and the network layer (`/truth/ads`)
- Disable analytics + Firebase telemetry, strip the advertising-ID permission
- Bypass Play Integrity
- Hide For You feed, Help Center row, Truth Gems, TRUTH+ button, AI tab
- Block Truth+ upsell sheets and the per-feature roadblock dialog
- Optional: force-enable post editing / scheduling, Truth TV

Features are chosen at runtime (LSPosed: triple-tap the top-left to open MTGA Settings) or at patch time (ReVanced: `revanced-cli patch -e "<patch name>"`).

## Requirements

- x86_64 Linux with KVM
- Nix (flakes enabled)

## Quick start

```bash
# Rooted AVD path
nix run .#setup                             # ~10 min, one-time
nix run .#start
nix run .#install-app -- truth-social.apkm
nix run .#deploy                            # build + install module

# Sideload path
nix run .#build-patches                     # requires read:packages GitHub scope
nix run .#patch-app -- truth-social.apkm out.apk
```

## Commands

| | |
|---|---|
| `nix run .#setup` | Rooted Android 14 AVD with Magisk + Zygisk + LSPosed |
| `nix run .#start` | Boot the AVD |
| `nix run .#install-app -- <file>` | Install `.apk` / `.apkm` / `.xapk` |
| `nix run .#deploy` | Build + install the LSPosed module |
| `nix run .#build-patches` | Build the `.rvp` bundle |
| `nix run .#patch-app -- <bundle>` | Apply the latest `.rvp` via revanced-cli |
| `nix develop` | Dev shell (Android SDK, Gradle, apktool, jadx, gh, revanced-cli, treefmt) |

## Calibrated builds

| versionName | versionCode | base.apk SHA-256 |
|---|---|---|
| 1.27.0 | 1258 | `267851a53a8986a42a50dafb23f4666b5c02f5533f4075a9b68fe3d2927836ab` |
| 1.26.2 | 1256 | `2fa0e3c8dea0967e375a7e7aec135c4bb60ea67c9d6e577010f1496aad291fa3` |
| 1.26.1 | 1254 | `2e974acac3ec18b1dfc7ccf98c49159896fe391f2ee0d1606581315f4abda158` |
| 1.24.8 | 1228 | `bcca813e2920602f0a9908240c537dc1d9ee6b6a90213e2b0be03e6458f35c1a` |
| 1.24.6 | 1226 | `6108f4127e7ec04be40454ab083bfde870f0055ce7e2511e9f730418c2d2cc93` |

Uncalibrated versions abort hook installation with a Toast warning; add a `TargetSet` in `mod/common/.../Targets.kt` to support a new release.
