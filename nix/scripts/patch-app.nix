{
  pkgs,
  deps,
  lib,
}:
pkgs.writeShellApplication {
  name = "mtga-patch-app";
  runtimeInputs = with pkgs; [
    jdk17
    coreutils
    unzip
    findutils
  ];
  text = ''
        ${lib.shellLib}

        usage() {
          cat <<EOF
    Usage: mtga-patch-app <apk|apkm|xapk> [output.apk]

    Stage 1 (always): if input is .apkm/.xapk, merge to a single APK with APKEditor.
    Stage 2:
      - If MTGA_PATCHES_RVP env var points to a .rvp bundle (or one is auto-
        discovered at \$PWD/patches/build/libs/*.rvp), run revanced-cli.
      - Otherwise, re-sign the merged APK with uber-apk-signer as a smoke
        test of the toolchain. Output is the merged APK re-signed with the
        debug key — useful only for verifying that the build chain works.

    To produce a real .rvp:
      - \`mtga-build-patches\` (uses your gh CLI auth — no separate PAT
        needed; gh must be logged in with read:packages scope), OR
      - \`./gradlew -Dmtga.patches=true :patches:build\` (set gpr.user /
        gpr.key in ~/.gradle/gradle.properties manually).
    Then re-run mtga-patch-app. See HANDOFF.md §10.14 for full setup.
    EOF
          exit 1
        }

        [ $# -eq 0 ] && usage
        INPUT="$1"
        OUT="''${2:-truth-social-patched.apk}"
        [ ! -f "$INPUT" ] && die "File not found: $INPUT"

        RVCLI="${deps.revancedCli}"
        APKEDIT="${deps.apkEditor}"
        APKSIGNER="${deps.uberApkSigner}"

        WORK=$(mktemp -d)
        trap 'rm -rf "$WORK"' EXIT

        # Stage 1: bundle → single APK
        EXT="''${INPUT##*.}"
        case "$EXT" in
          apk)
            echo "[1/2] Single APK input — skipping merge."
            cp "$INPUT" "$WORK/merged.apk"
            ;;
          apkm | xapk)
            echo "[1/2] Merging $EXT bundle with APKEditor..."
            java -jar "$APKEDIT" m -i "$INPUT" -o "$WORK/merged.apk"
            ;;
          *)
            die "Unsupported format: .$EXT"
            ;;
        esac

        # Stage 2: patch (if .rvp available) or smoke-test sign
        # Skip *-sources.rvp which is the source-classifier artifact.
        RVP="''${MTGA_PATCHES_RVP:-}"
        if [ -z "$RVP" ]; then
          RVP=$(find "$PWD/patches" -path "*/build/libs/*.rvp" \
            -not -name "*-sources.rvp" 2>/dev/null | head -1)
        fi

        if [ -n "$RVP" ] && [ -f "$RVP" ]; then
          echo "[2/2] Running revanced-cli with $RVP..."
          # `-b` bypasses signature/attestation verification of the RVP bundle.
          # Production CI signs RVPs with PGP; for local dev we don't.
          java -jar "$RVCLI" patch -b -p "$RVP" -o "$OUT" "$WORK/merged.apk"
        else
          echo "[2/2] No .rvp found — running uber-apk-signer smoke test."
          echo "       (Set MTGA_PATCHES_RVP=path/to/mtga-patches.rvp for real patches.)"
          java -jar "$APKSIGNER" --apks "$WORK/merged.apk" --out "$WORK/signed/" >/dev/null
          mv "$WORK/signed/merged-aligned-debugSigned.apk" "$OUT"
        fi

        echo "Output: $OUT"
  '';
}
