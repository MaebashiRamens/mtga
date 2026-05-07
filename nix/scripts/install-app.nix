{ pkgs, lib }:
pkgs.writeShellApplication {
  name = "mtga-install-app";
  runtimeInputs = with pkgs; [
    coreutils
    unzip
  ];
  text = ''
        ${lib.androidEnv}
        ${lib.shellLib}

        usage() {
          cat <<EOF
    Usage: mtga-install-app <apk|apkm|xapk>

    Install Truth Social onto the running emulator.
    Supports: .apk, .apkm (APKMirror bundle), .xapk (APKPure bundle)

    Download from:
      https://www.apkmirror.com/apk/t-media-tech-llc/truth-social/
      https://apkpure.com/truth-social/com.truthsocial.android.app
    EOF
          exit 1
        }

        [ $# -eq 0 ] && usage
        FILE="$1"
        [ ! -f "$FILE" ] && die "File not found: $FILE"

        EXT="''${FILE##*.}"
        TMPDIR=$(mktemp -d)
        trap 'rm -rf "$TMPDIR"' EXIT

        case "$EXT" in
          apk)
            echo "Installing single APK..."
            adb install -r "$FILE"
            ;;
          apkm | xapk)
            echo "Extracting bundle..."
            unzip -o "$FILE" -d "$TMPDIR" >/dev/null

            BASE="$TMPDIR/base.apk"
            [ -f "$BASE" ] || die "Error: base.apk not found in bundle"

            # base + the splits the x86_64 emulator actually needs.
            SPLITS=("$BASE")
            for f in "$TMPDIR"/split_config.x86_64.apk \
              "$TMPDIR"/split_config.en.apk \
              "$TMPDIR"/split_config.xxhdpi.apk \
              "$TMPDIR"/split_config.xhdpi.apk \
              "$TMPDIR"/split_config.hdpi.apk; do
              [ -f "$f" ] && SPLITS+=("$f")
            done

            echo "Installing ''${#SPLITS[@]} APKs..."
            adb install-multiple "''${SPLITS[@]}"
            ;;
          *)
            echo "Unsupported format: .$EXT"
            usage
            ;;
        esac

        echo "Truth Social installed."
  '';
}
