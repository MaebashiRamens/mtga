{
  pkgs,
  deps,
  apiLevel,
  lib,
}:
pkgs.writeShellApplication {
  name = "mtga-setup";
  runtimeInputs = with pkgs; [
    coreutils
    gnused
    gnugrep
    unzip
    findutils
    gawk
  ];
  text = ''
    ${lib.androidEnv}
    ${lib.shellLib}

    STATE="${lib.stateDir}"
    MARKER="$STATE/.rooted"
    AVD="${lib.avdName}"

    if [ -f "$MARKER" ]; then
      echo "Already set up. Run mtga-start to boot."
      echo "To re-setup, delete $STATE"
      exit 0
    fi

    echo "=== MTGA rooted AVD setup (API ${apiLevel}) ==="

    echo "[1/6] Copying system image..."
    STOCK="$ANDROID_HOME/system-images/android-${apiLevel}/google_apis/x86_64"
    mkdir -p "$STATE/image"
    cp -rL "$STOCK"/* "$STATE/image/"
    chmod -R u+w "$STATE/image"

    echo "[2/6] Creating AVD: $AVD"
    avdmanager create avd --force \
      --name "$AVD" \
      --package "${lib.imagePackage}" \
      --device "pixel_6" 2>/dev/null || true
    AVD_CFG="$ANDROID_AVD_HOME/$AVD.avd/config.ini"
    if [ -f "$AVD_CFG" ]; then
      sed -i "s|image.sysdir.1=.*|image.sysdir.1=$STATE/image/|" "$AVD_CFG"
    fi

    echo "[3/6] Booting emulator..."
    emulator "@$AVD" -no-snapshot-load -gpu swiftshader_indirect \
      -no-audio -no-window -no-boot-anim &
    EMU_PID=$!
    wait_for_boot || die "Emulator did not boot in time"
    echo "  Emulator booted"

    echo "[4/6] Rooting with rootAVD..."
    ROOTAVD_WORK="$(mktemp -d)/rootAVD"
    cp -rL "${deps.rootAVD}" "$ROOTAVD_WORK"
    chmod -R u+w "$ROOTAVD_WORK"

    # Replace bundled Magisk v26.4 with v28.1.
    # rootAVD's LD_PRELOAD/init-ld support (Oct 2024) handles v28+ automatically.
    # AVD is often "offline" for rootAVD's BusyBox wget check, so we
    # pre-stage the APK as Magisk.zip to avoid needing online download.
    cp "${deps.magiskApk}" "$ROOTAVD_WORK/Magisk.zip"

    # Patch rootAVD.sh for v28+ compat: magisk binary renamed from
    # magisk64 to magisk. The post-boot script checks "test ! -f magisk64"
    # and exits early. Add a symlink fallback.
    sed -i '/^magisk_name=\\"magisk32\\"/a\
    # v28+ compat patch\
    [ ! -f "./magisk64" ] \&\& [ -f "./magisk" ] \&\& ln -sf ./magisk ./magisk64' \
      "$ROOTAVD_WORK/rootAVD.sh"

    FAKE_SDK="$(mktemp -d)/sdk"
    mkdir -p "$FAKE_SDK/system-images/android-${apiLevel}/google_apis/x86_64"
    cp "$STATE/image/ramdisk.img" \
      "$FAKE_SDK/system-images/android-${apiLevel}/google_apis/x86_64/ramdisk.img"
    cp "$STATE/image/source.properties" \
      "$FAKE_SDK/system-images/android-${apiLevel}/google_apis/x86_64/" 2>/dev/null || true
    ln -sf "$ANDROID_HOME/platform-tools" "$FAKE_SDK/platform-tools"

    cd "$ROOTAVD_WORK"
    # rootAVD detects the local Magisk.zip version and uses it.
    # v28+ triggers LD_PRELOAD/init-ld code path automatically.
    echo "" | ANDROID_HOME="$FAKE_SDK" timeout 300 bash rootAVD.sh \
      "system-images/android-${apiLevel}/google_apis/x86_64/ramdisk.img" 2>&1 \
      | grep -E "^\[|Magisk|patch|backup|Done|Error|rooted|pull|replace|init-ld|LD_PRELOAD|Version" || true
    cd - > /dev/null

    PATCHED="$FAKE_SDK/system-images/android-${apiLevel}/google_apis/x86_64/ramdisk.img"
    if [ -f "$PATCHED" ]; then
      cp "$PATCHED" "$STATE/image/ramdisk.img"
      echo "  Ramdisk patched"
    fi

    # Stop everything cleanly before phase 5 — we need exclusive access
    # to the emulator port range.
    kill_emulators
    kill "$EMU_PID" 2>/dev/null || true
    wait "$EMU_PID" 2>/dev/null || true
    sleep 5

    echo "[5/6] Configuring Magisk + Zygisk + LSPosed..."
    emulator "@$AVD" -no-snapshot-load -gpu swiftshader_indirect \
      -no-audio -no-window -no-boot-anim &
    EMU_PID=$!
    wait_for_boot || die "Emulator did not boot for phase 5"
    adb root
    sleep 2
    echo "  Magisk: $(adb shell 'magisk -v' 2>/dev/null | tr -d '\r\n')"

    # Install Magisk APK
    adb install "${deps.magiskApk}" 2>/dev/null || true

    # Populate /data/adb/magisk/ with v28.1 binaries.
    # rootAVD's post-boot script fails to copy them because v28+ renamed
    # magisk64 → magisk. Without these files, magisk daemon won't start.
    adb push "${deps.magiskApk}" /data/local/tmp/magisk_setup.apk 2>/dev/null || true
    adb shell "
      cd /data/local/tmp
      unzip -o magisk_setup.apk lib/x86_64/* assets/util_functions.sh assets/boot_patch.sh assets/stub.apk -d _m 2>/dev/null
      D=/data/adb/magisk; mkdir -p \$D
      cp _m/lib/x86_64/libmagisk.so \$D/magisk64
      cp _m/lib/x86_64/libmagisk.so \$D/magisk
      cp _m/lib/x86_64/libmagiskinit.so \$D/magiskinit
      cp _m/lib/x86_64/libmagiskboot.so \$D/magiskboot
      cp _m/lib/x86_64/libmagiskpolicy.so \$D/magiskpolicy
      cp _m/lib/x86_64/libbusybox.so \$D/busybox
      cp _m/lib/x86_64/libinit-ld.so \$D/init-ld 2>/dev/null
      cp _m/assets/util_functions.sh \$D/
      cp _m/assets/boot_patch.sh \$D/
      cp _m/assets/stub.apk \$D/
      chmod 755 \$D/magisk* \$D/busybox \$D/magiskpolicy \$D/init-ld 2>/dev/null
      rm -rf _m magisk_setup.apk
    " 2>/dev/null || true

    # Enable Zygisk via SharedPreferences (device-encrypted storage).
    PREFS_DIR=$(adb shell "find /data -path '*topjohnwu*shared_prefs' -type d 2>/dev/null" | tr -d "\r" | head -1)
    if [ -n "$PREFS_DIR" ]; then
      adb shell "printf '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<map>\n<boolean name=\"zygisk\" value=\"true\" />\n</map>' > $PREFS_DIR/com.topjohnwu.magisk_preferences.xml"
      echo "  Zygisk enabled via SharedPreferences"
    fi
    # Also set via Magisk SQLite DB.
    adb shell "magisk --sqlite \"REPLACE INTO settings(key,value) VALUES('zygisk',1)\"" 2>/dev/null || true

    echo "  Installing LSPosed..."
    adb push "${deps.lsposedZip}" /data/local/tmp/lsposed.zip
    adb shell "
      mkdir -p /data/adb/modules/zygisk_lsposed
      cd /data/adb/modules/zygisk_lsposed
      unzip -o /data/local/tmp/lsposed.zip
      mkdir -p zygisk
      for arch in x86_64 x86 arm64-v8a armeabi-v7a; do
        [ -f lib/\$arch/liblspd.so ] && cp lib/\$arch/liblspd.so zygisk/\$arch.so
      done
      chmod 755 daemon 2>/dev/null
      pm install manager.apk 2>/dev/null
      rm /data/local/tmp/lsposed.zip

      # Create preinit SEPolicy directory and copy LSPosed's rule.
      # Without this, magiskinit cannot load SEPolicy at boot and
      # LSPosed shows 'Partially activated' / 'SEPolicy not loaded'.
      mkdir -p /data/.magisk/preinit
      cp /data/adb/modules/zygisk_lsposed/sepolicy.rule /data/.magisk/preinit/sepolicy.rule 2>/dev/null
    " 2>/dev/null || true

    adb reboot
    sleep 5
    wait_for_boot || die "Emulator did not boot after LSPosed install"
    adb root 2>/dev/null
    sleep 1

    MAGISK_VER=$(adb shell "magisk -v" 2>/dev/null | tr -d "\r\n")

    # Verify LSPosed is running.
    LSPD_OK=$(adb shell "logcat -d 2>/dev/null | grep -c 'lspd.*Core platform'" | tr -d "\r\n")
    if [ "$LSPD_OK" -gt 0 ] 2>/dev/null; then
      echo "  LSPosed active"
    else
      echo "  WARNING: LSPosed may not be active (check Zygisk)"
    fi

    # Pre-register MTGA module in LSPosed (if MTGA APK is installed).
    if adb shell pm path com.example.mtga >/dev/null 2>&1; then
      MTGA_PATH=$(adb shell pm path com.example.mtga | tr -d "\r" | sed "s/package://")
      adb shell "
        DB=/data/adb/lspd/config/modules_config.db
        if [ -f \$DB ]; then
          sqlite3 \$DB \"INSERT OR REPLACE INTO modules (module_pkg_name, apk_path, enabled) VALUES ('com.example.mtga', '$MTGA_PATH', 1);\"
          MID=\$(sqlite3 \$DB \"SELECT mid FROM modules WHERE module_pkg_name='com.example.mtga';\")
          sqlite3 \$DB \"INSERT OR REPLACE INTO scope (mid, app_pkg_name, user_id) VALUES (\$MID, 'com.truthsocial.android.app', 0);\"
          echo 'MTGA registered in LSPosed'
        fi
      " 2>/dev/null || true
    fi

    adb emu kill 2>/dev/null || true
    wait "$EMU_PID" 2>/dev/null || true

    touch "$MARKER"
    echo "[6/6] Done! Magisk: $MAGISK_VER"
    echo "  Run 'mtga-start' to boot."
  '';
}
