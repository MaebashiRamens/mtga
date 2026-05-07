{
  pkgs,
  deps,
  androidSdk,
  apiLevel ? "34",
}:
let
  lib = import ./lib.nix { inherit androidSdk apiLevel; };
in
{
  setup = import ./setup.nix {
    inherit
      pkgs
      deps
      apiLevel
      lib
      ;
  };
  start = import ./start.nix { inherit pkgs apiLevel lib; };
  deploy = import ./deploy.nix { inherit pkgs lib; };
  installApp = import ./install-app.nix { inherit pkgs lib; };
  patchApp = import ./patch-app.nix { inherit pkgs deps lib; };
  buildPatches = import ./build-patches.nix { inherit pkgs lib; };
}
