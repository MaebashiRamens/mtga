{
  description = "MTGA - LSPosed module & Morphe patches for Truth Social";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      treefmt-nix,
    }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      treefmtEval = treefmt-nix.lib.evalModule pkgs ./treefmt.nix;

      androidSdk = android-nixpkgs.sdk.${system} (
        sdkPkgs: with sdkPkgs; [
          build-tools-35-0-0
          cmdline-tools-latest
          emulator
          platform-tools
          platforms-android-34
          platforms-android-35
          system-images-android-34-google-apis-x86-64
        ]
      );

      deps = import ./nix/deps.nix { inherit pkgs; };
      scripts = import ./nix/scripts.nix { inherit pkgs deps androidSdk; };
    in
    {
      formatter.${system} = treefmtEval.config.build.wrapper;

      checks.${system}.formatting = treefmtEval.config.build.check self;

      apps.${system} = {
        setup = {
          type = "app";
          program = "${scripts.setup}/bin/mtga-setup";
        };
        start = {
          type = "app";
          program = "${scripts.start}/bin/mtga-start";
        };
        deploy = {
          type = "app";
          program = "${scripts.deploy}/bin/mtga-deploy";
        };
        install-app = {
          type = "app";
          program = "${scripts.installApp}/bin/mtga-install-app";
        };
        patch-app = {
          type = "app";
          program = "${scripts.patchApp}/bin/mtga-patch-app";
        };
        build-patches = {
          type = "app";
          program = "${scripts.buildPatches}/bin/mtga-build-patches";
        };
        default = self.apps.${system}.setup;
      };

      devShells.${system}.default = pkgs.mkShell {
        buildInputs = with pkgs; [
          androidSdk
          jdk17
          gradle
          apktool
          jadx
          android-tools
          git
          gh
          curl
          unzip
          scripts.setup
          scripts.start
          scripts.deploy
          scripts.installApp
          scripts.patchApp
          scripts.buildPatches
          treefmtEval.config.build.wrapper
        ];

        shellHook = ''
          export ANDROID_HOME="${androidSdk}/share/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
          export JAVA_HOME="${pkgs.jdk17}"
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
          # ReVanced CLI / APKEditor / uber-apk-signer JARs (read by the
          # mtga-patch-app script and available for ad-hoc invocations).
          export MTGA_REVANCED_CLI="${deps.revancedCli}"
          export MTGA_APK_EDITOR="${deps.apkEditor}"
          export MTGA_APK_SIGNER="${deps.uberApkSigner}"
          # Point Android Gradle Plugin at the SDK. The Gradle root for the
          # LSPosed module is mod/, so the local.properties lives there.
          echo "sdk.dir=$ANDROID_HOME" > mod/local.properties
        '';
      };
    };
}
