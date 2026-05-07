{ pkgs, lib }:
pkgs.writeShellApplication {
  name = "mtga-deploy";
  runtimeInputs = with pkgs; [ jdk17 ];
  text = ''
    ${lib.androidEnv}

    PROJECT_ROOT=$PWD
    [ -x "$PROJECT_ROOT/gradlew" ] || {
      echo "Run this from the MTGA project root (no ./gradlew here)."
      exit 1
    }
    echo "Building MTGA..."
    (cd "$PROJECT_ROOT" && ./gradlew :mod:app:assembleDebug)
    echo "Installing..."
    adb install -r "$PROJECT_ROOT/mod/app/build/outputs/apk/debug/app-debug.apk"
    adb shell am force-stop com.truthsocial.android.app
    echo "Done."
  '';
}
