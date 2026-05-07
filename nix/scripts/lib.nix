{ androidSdk, apiLevel }:
let
  # Per-AVD state directory. Bash expands $XDG_DATA_HOME / $HOME at runtime.
  # Using `\$` escapes the nix antiquotation so the literal `${...}` survives.
  stateDir = "\${XDG_DATA_HOME:-$HOME/.local/share}/mtga/avd/${apiLevel}";
in
{
  inherit stateDir;

  avdName = "mtga-${apiLevel}";
  imagePackage = "system-images;android-${apiLevel};google_apis;x86_64";

  # Common Android SDK env vars. Source at the top of any script that runs
  # `adb` / `emulator` / `avdmanager`.
  androidEnv = ''
    export PATH="${androidSdk}/bin:${androidSdk}/share/android-sdk/platform-tools:$PATH"
    export ANDROID_HOME="${androidSdk}/share/android-sdk"
    export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
  '';

  # Reusable bash helpers. Source after [androidEnv] so `adb` is on PATH.
  shellLib = ''
    # Wait up to ~4 min for the connected device/emulator to finish booting.
    wait_for_boot() {
      adb wait-for-device
      for _i in $(seq 1 120); do
        if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r\n")" = "1" ]; then
          return 0
        fi
        sleep 2
      done
      return 1
    }

    # Stop every running emulator instance (idempotent).
    kill_emulators() {
      adb devices 2>/dev/null | awk '/^emulator-/ { print $1 }' | while read -r s; do
        adb -s "$s" emu kill 2>/dev/null || true
      done
    }

    # Print message to stderr and exit non-zero.
    die() {
      echo "$@" >&2
      exit 1
    }

    # Require an emulator/device to be connected.
    require_device() {
      adb get-state >/dev/null 2>&1 || die "No device connected. Run 'mtga-start' first."
    }
  '';
}
