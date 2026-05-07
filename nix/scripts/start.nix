{
  pkgs,
  apiLevel,
  lib,
}:
pkgs.writeShellApplication {
  name = "mtga-start";
  runtimeInputs = [ ];
  text = ''
    ${lib.androidEnv}

    # Force XCB backend to avoid Wayland/XWayland freeze (GNOME Mutter #3543).
    export QT_QPA_PLATFORM=xcb
    # Disable audio to prevent PipeWire/PulseAudio from blocking emulator boot.
    export QEMU_AUDIO_DRV=none

    if [ ! -f "${lib.stateDir}/.rooted" ]; then
      echo "Not set up. Run 'mtga-setup' first."
      exit 1
    fi
    echo "Starting rooted Android ${apiLevel} emulator..."
    exec emulator @${lib.avdName} \
      -no-boot-anim \
      -no-audio \
      -gpu swiftshader_indirect \
      -memory 4096 \
      -cores 2 \
      "$@"
  '';
}
