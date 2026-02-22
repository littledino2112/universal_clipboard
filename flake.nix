{
  description = "Universal Clipboard - P2P encrypted clipboard sync";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    android-nixpkgs = {
      url = "github:nickcao/android-nixpkgs/main";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, rust-overlay, android-nixpkgs }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        overlays = [ (import rust-overlay) ];
        pkgs = import nixpkgs { inherit system overlays; };

        # Rust toolchain
        rustToolchain = pkgs.rust-bin.stable.latest.default.override {
          extensions = [ "rust-src" "rust-analyzer" "clippy" ];
        };

        # Android SDK
        androidSdk = android-nixpkgs.sdk.${system} (sdkPkgs: with sdkPkgs; [
          cmdline-tools-latest
          build-tools-34-0-0
          platform-tools
          platforms-android-34
        ]);

        # System libraries needed by arboard (clipboard) on Linux
        linuxClipboardDeps = with pkgs; pkgs.lib.optionals pkgs.stdenv.isLinux [
          xorg.libX11
          xorg.libXcursor
          xorg.libXrandr
          xorg.libXi
          xorg.libxcb
          wayland
          libxkbcommon
        ];

        # System libraries needed by arboard on macOS
        darwinDeps = with pkgs; pkgs.lib.optionals pkgs.stdenv.isDarwin [
          darwin.apple_sdk.frameworks.AppKit
          darwin.apple_sdk.frameworks.CoreFoundation
          darwin.apple_sdk.frameworks.Security
        ];

      in {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            # Rust
            rustToolchain
            pkgs.cargo-watch
            pkgs.cargo-nextest

            # Android
            androidSdk
            pkgs.jdk17
            pkgs.gradle

            # Build essentials
            pkgs.pkg-config
            pkgs.openssl

            # Dev tools
            pkgs.just
          ] ++ linuxClipboardDeps ++ darwinDeps;

          # Environment variables
          JAVA_HOME = "${pkgs.jdk17}";
          ANDROID_HOME = "${androidSdk}/share/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";

          # Rust linker needs to find system libs
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath linuxClipboardDeps;

          shellHook = ''
            echo "Universal Clipboard dev environment"
            echo ""
            echo "  Rust:    $(rustc --version)"
            echo "  Cargo:   $(cargo --version)"
            echo "  Java:    $(java --version 2>&1 | head -1)"
            echo "  Android: $ANDROID_HOME"
            echo ""
            echo "Commands:"
            echo "  cargo test       - run macOS receiver tests"
            echo "  cargo run -- listen  - start the receiver"
            echo "  cd android && gradle build  - build Android app"
            echo ""
          '';
        };

        # Build the macOS/Linux receiver binary
        packages.default = pkgs.rustPlatform.buildRustPackage {
          pname = "uclip";
          version = "0.1.0";
          src = ./macos;
          cargoLock.lockFile = ./macos/Cargo.lock;

          nativeBuildInputs = [ pkgs.pkg-config ];
          buildInputs = [ pkgs.openssl ] ++ linuxClipboardDeps ++ darwinDeps;
        };
      }
    );
}
