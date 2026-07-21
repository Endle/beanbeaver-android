# Development shell for the standalone Android tree.
#
#   cd android && nix-shell
#   ./build-android.sh
#   ./gradlew :app:assembleDebug
#
# Accepts the Android SDK license and unfree packages (required for androidsdk).
{
  pkgs ? import (builtins.getFlake "nixpkgs") {
    system = builtins.currentSystem;
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  },
}:
let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "35.0.0" ];
    includeNDK = true;
    includeSystemImages = false;
    includeEmulator = false;
  };
  sdkRoot = "${android.androidsdk}/libexec/android-sdk";
  ndkRoot = "${sdkRoot}/ndk-bundle";
in
pkgs.mkShell {
  name = "beanbeaver-android";

  buildInputs = [
    pkgs.jdk17
    android.androidsdk
    pkgs.android-tools
    pkgs.pkg-config
    pkgs.openssl
    pkgs.gcc
  ];

  ANDROID_HOME = sdkRoot;
  ANDROID_SDK_ROOT = sdkRoot;
  ANDROID_NDK_HOME = ndkRoot;
  JAVA_HOME = "${pkgs.jdk17}";

  shellHook = ''
    export PATH="$JAVA_HOME/bin:$PATH"
    # Always write from this tree (standalone android/).
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
    echo "BeanBeaver Android shell (standalone)"
    echo "  ANDROID_HOME=$ANDROID_HOME"
    echo "  ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
    echo "  JAVA_HOME=$JAVA_HOME"
  '';
}
