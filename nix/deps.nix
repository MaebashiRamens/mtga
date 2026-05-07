{ pkgs }:

{
  rootAVD = pkgs.fetchgit {
    url = "https://gitlab.com/newbit/rootAVD.git";
    rev = "613caa44371f85e1a461bc030e07ddc2d71afe32";
    hash = "sha256-OlVHXZqToJDt3LSsUVrI+ieJO0ZFTEaFYEHOMnPHiKU=";
  };

  # Magisk v28.1 — Zygisk is built-in and always active (no toggle needed)
  magiskApk = pkgs.fetchurl {
    url = "https://github.com/topjohnwu/Magisk/releases/download/v28.1/Magisk-v28.1.apk";
    hash = "sha256-i/0zRrPaWBT4Lv9vGxtf7dCtWF85olcJsj61SqxFaR0=";
  };

  lsposedZip = pkgs.fetchurl {
    url = "https://github.com/LSPosed/LSPosed/releases/download/v1.9.2/LSPosed-v1.9.2-7024-zygisk-release.zip";
    hash = "sha256-Drxry0ZdHEtEtyIKtfAlLmtOt/5D2nRlBHbSeYuyliI=";
  };

  # ReVanced CLI v6.0.0 — fat JAR that consumes a .rvp patch bundle and
  # produces a patched APK. Requires JDK 17 at runtime; aapt2 is bundled
  # in the JAR for x86_64 Linux. Accepts .apk, .apkm, .xapk directly.
  # https://github.com/ReVanced/revanced-cli
  revancedCli = pkgs.fetchurl {
    url = "https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar";
    hash = "sha256-wlVJvBfVnS65T6X4bmDpt3oCdyyoj3BQ+PEnb5I6mVg=";
  };

  # APKEditor v1.4.3 — merges .apkm/.xapk split bundles into a single APK so
  # the patcher and signer can deal with a flat archive. Used as a fallback
  # when revanced-cli rejects a particular bundle layout.
  # https://github.com/REAndroid/APKEditor
  apkEditor = pkgs.fetchurl {
    url = "https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar";
    hash = "sha256-wkL1/EWRZnoAhGaDINABaiDnwquuECwb1NZA4R2fYO4=";
  };

  # uber-apk-signer v1.3.0 — re-signs (and zipaligns) APK / split APK bundles
  # using a debug keystore. Used after a manual smali patch flow.
  # https://github.com/patrickfav/uber-apk-signer
  uberApkSigner = pkgs.fetchurl {
    url = "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar";
    hash = "sha256-4Smf1vz02lJ91Tc1tWEn6OqSKjIRKBI7nDLWGbuh2DU=";
  };
}
