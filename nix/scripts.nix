{
  pkgs,
  deps,
  androidSdk,
  apiLevel ? "34",
}:
import ./scripts {
  inherit
    pkgs
    deps
    androidSdk
    apiLevel
    ;
}
