{
  description = "fuji-ptp-organizer: Android 開発用の devShell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      # Android SDK の build-tools (aapt2 など) は x86_64 Linux 向けのバイナリしかない
      system = "x86_64-linux";

      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # SDK のバージョンは Gradle と同じくバージョンカタログから読む
      versions = (builtins.fromTOML (builtins.readFile ./gradle/libs.versions.toml)).versions;
      compileSdk = versions."android-compileSdk";
      buildTools = versions."android-buildTools";

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ compileSdk ];
        buildToolsVersions = [ buildTools ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
        includeSources = false;
      };
      androidSdk = androidComposition.androidsdk;
      sdkRoot = "${androidSdk}/libexec/android-sdk";

      jdk = pkgs.jdk17;
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          androidSdk # adb (platform-tools) も含む
          jdk
          pkgs.python3 # tools/generate_demo_dump.py
          pkgs.imagemagick # 同上（サムネイル生成）
        ];

        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        JAVA_HOME = jdk.home;

        # AGP が Maven から取得する aapt2 は動的リンクのバイナリで NixOS では動かないため、
        # Nix の SDK に入っている aapt2 を使わせる
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildTools}/aapt2";

        shellHook = ''
          echo "fuji-ptp-organizer devShell: Android SDK ${compileSdk} / build-tools ${buildTools} / JDK ${jdk.version}"
        '';
      };

      formatter.${system} = pkgs.nixfmt;
    };
}
