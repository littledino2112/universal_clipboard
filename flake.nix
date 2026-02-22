{
  description = "Universal Clipboard - P2P encrypted clipboard sync";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, rust-overlay, android-nixpkgs }:
    let
      # Systems supported by both rust-overlay and android-nixpkgs
      systems = [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];

      forEachSystem = f: nixpkgs.lib.genAttrs systems (system: f {
        inherit system;
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ rust-overlay.overlays.default ];
        };
      });
    in {
      devShells = forEachSystem ({ system, pkgs }: {
        default =
          let
            rustToolchain = pkgs.rust-bin.stable.latest.default.override {
              extensions = [ "rust-src" "rust-analyzer" "clippy" ];
            };

            androidSdk = android-nixpkgs.sdk.${system} (sdkPkgs: with sdkPkgs; [
              cmdline-tools-latest
              build-tools-34-0-0
              platform-tools
              platforms-android-34
            ]);

            linuxDeps = pkgs.lib.optionals pkgs.stdenv.isLinux (with pkgs; [
              xorg.libX11
              xorg.libXcursor
              xorg.libXrandr
              xorg.libXi
              xorg.libxcb
              wayland
              libxkbcommon
            ]);

            darwinDeps = pkgs.lib.optionals pkgs.stdenv.isDarwin [
              pkgs.apple-sdk_15
              pkgs.darwin.libiconv
            ];
          in
          pkgs.mkShell {
            buildInputs = [
              rustToolchain
              pkgs.cargo-watch
              pkgs.cargo-nextest

              androidSdk
              pkgs.jdk17
              pkgs.gradle

              pkgs.pkg-config
              pkgs.openssl
            ] ++ linuxDeps ++ darwinDeps;

            ANDROID_HOME = "${androidSdk}/share/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";
            JAVA_HOME = "${pkgs.jdk17}";

            LD_LIBRARY_PATH = pkgs.lib.optionalString pkgs.stdenv.isLinux
              (pkgs.lib.makeLibraryPath linuxDeps);

            shellHook = ''
              echo "Universal Clipboard dev environment"
              echo ""
              echo "  Rust:    $(rustc --version)"
              echo "  Cargo:   $(cargo --version)"
              echo "  Java:    $(java --version 2>&1 | head -1)"
              echo "  Android: $ANDROID_HOME"
              echo ""
              echo "Commands:"
              echo "  cargo test             - run receiver tests (in macos/)"
              echo "  cargo run -- listen    - start the receiver"
              echo "  cd android && gradle assembleDebug  - build Android APK"
              echo ""
            '';
          };
      });

      packages = forEachSystem ({ system, pkgs }:
        let
          linuxDeps = pkgs.lib.optionals pkgs.stdenv.isLinux (with pkgs; [
            xorg.libX11
            xorg.libxcb
            wayland
            libxkbcommon
          ]);

          darwinDeps = pkgs.lib.optionals pkgs.stdenv.isDarwin [
            pkgs.apple-sdk_15
            pkgs.darwin.libiconv
          ];
        in {
          default = pkgs.rustPlatform.buildRustPackage {
            pname = "uclip";
            version = "0.1.0";
            src = ./macos;
            cargoLock.lockFile = ./macos/Cargo.lock;
            nativeBuildInputs = [ pkgs.pkg-config ];
            buildInputs = [ pkgs.openssl ] ++ linuxDeps ++ darwinDeps;
          };
        }
      );
    };
}
