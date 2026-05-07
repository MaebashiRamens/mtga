{ pkgs, ... }:
{
  projectRootFile = "flake.nix";

  programs.ktlint.enable = true;
  programs.google-java-format = {
    enable = true;
    aospStyle = true;
  };
  programs.nixfmt.enable = true;
  programs.mdformat.enable = true;

  # markdownlint is a linter, not strictly a formatter — but with --fix it
  # auto-resolves a chunk of style issues, so we run it via treefmt as a
  # custom formatter immediately after mdformat.
  settings.formatter.markdownlint = {
    command = "${pkgs.markdownlint-cli2}/bin/markdownlint-cli2";
    options = [ "--fix" ];
    includes = [ "*.md" ];
  };

  settings.global.excludes = [
    "gradle/wrapper/*"
    "gradlew"
    "gradlew.bat"
    "*.lock"
    "*.png"
    "*.apk"
    "*.apkm"
    "*.xapk"
    "*.jar"
    "build/**"
    ".gradle/**"
    "local.properties"
  ];
}
