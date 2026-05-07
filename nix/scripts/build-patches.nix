{ pkgs, lib }:
pkgs.writeShellApplication {
  name = "mtga-build-patches";
  runtimeInputs = with pkgs; [
    jdk17
    github-cli
    coreutils
    findutils
  ];
  text = ''
    ${lib.shellLib}

    if ! gh auth status >/dev/null 2>&1; then
      die "gh is not authenticated. Run 'gh auth login --scopes read:packages' first."
    fi

    # Verify the gh token actually has read:packages scope. The header
    # `X-OAuth-Scopes` from any authenticated request lists the granted
    # scopes; bail early with a helpful message if read:packages is missing.
    SCOPES=$(gh api -i user 2>/dev/null | awk -F': ' 'tolower($1) == "x-oauth-scopes" { sub("\r","", $2); print $2 }')
    case ",$SCOPES," in
      *,read:packages,* | *", read:packages,"* | *",read:packages "* | *", read:packages "*)
        ;;
      *)
        die "gh token lacks 'read:packages' scope. Run 'gh auth refresh -s read:packages' to add it."
        ;;
    esac

    GITHUB_TOKEN=$(gh auth token)
    GITHUB_ACTOR=$(gh api user --jq .login)
    # Two naming conventions in flight:
    # - Our own settings.gradle.kts reads `gpr.user` / `gpr.key` (or
    #   GITHUB_ACTOR / GITHUB_TOKEN) for the project's own GHP repo.
    # - The `app.revanced.patches` plugin internally registers its own
    #   `githubPackages` repo and looks for `githubPackagesUsername` /
    #   `githubPackagesPassword` Gradle properties (settable via env via
    #   the standard `ORG_GRADLE_PROJECT_<name>` mapping).
    export GITHUB_TOKEN GITHUB_ACTOR
    export ORG_GRADLE_PROJECT_gpr_user="$GITHUB_ACTOR"
    export ORG_GRADLE_PROJECT_gpr_key="$GITHUB_TOKEN"
    export ORG_GRADLE_PROJECT_githubPackagesUsername="$GITHUB_ACTOR"
    export ORG_GRADLE_PROJECT_githubPackagesPassword="$GITHUB_TOKEN"

    PROJECT_ROOT=$PWD
    [ -x "$PROJECT_ROOT/gradlew" ] || die "Run this from the MTGA project root (no ./gradlew here)."

    echo "Building MTGA patches as $GITHUB_ACTOR (gh token has read:packages)..."
    (cd "$PROJECT_ROOT" && ./gradlew :patches:patches:build "$@")

    echo
    RVP=$(find "$PROJECT_ROOT/patches" -path "*/build/libs/*.rvp" \
      -not -name "*-sources.rvp" 2>/dev/null | head -1)
    if [ -n "$RVP" ]; then
      echo "Wrote: $RVP"
    else
      echo "Build succeeded but no .rvp found under patches/*/build/libs/"
      exit 1
    fi
  '';
}
