# Universal Clipboard

P2P encrypted clipboard sync between Android and macOS. No central server, no cloud accounts.

## How It Works

1. **Android app** (sender): Paste content into the app, select items, and send them to your Mac
2. **macOS receiver**: Runs in the background, receives content directly onto your system clipboard
   - **Menu bar app** (recommended): System tray icon with popup panel showing pairing code and status
   - **CLI**: Headless terminal receiver

All communication is encrypted using the **Noise Protocol Framework** (same cryptographic primitives as WireGuard: Curve25519, ChaCha20-Poly1305, SHA-256).

## Install

Download the latest release from [GitHub Releases](../../releases):

- **Android**: `universal-clipboard-*-android.apk`
- **macOS Menu Bar App**: `Universal-Clipboard-*-macos-aarch64.dmg`
- **macOS CLI**: `uclip-*-macos-aarch64`

### macOS: "App is damaged" error

The app is not code-signed with an Apple Developer certificate. After downloading, remove the quarantine attribute:

```bash
# For the .app (after mounting DMG or copying to /Applications)
xattr -cr "/Applications/Universal Clipboard.app"

# Or for the CLI binary
xattr -cr ~/Downloads/uclip-*-macos-aarch64
chmod +x ~/Downloads/uclip-*-macos-aarch64
```

## Dev Environment Setup (Nix)

Install [Determinate Nix](https://github.com/DeterminateSystems/nix-installer) (flakes enabled by default):

```bash
curl -fsSL https://install.determinate.systems/nix | sh -s -- install
```

Then enter the dev shell:

```bash
# With direnv (recommended — auto-activates on cd)
direnv allow

# Or manually
nix develop
```

This gives you Rust, Android SDK, JDK 17, Gradle, and all system deps.

## Quick Start

### macOS Menu Bar App (Tauri)

```bash
cd macos
cargo build --release -p uclip-app

# Run the app (appears in menu bar, no dock icon)
./target/release/uclip-app
```

The app auto-starts on login. Click the tray icon to see the pairing code and connection status.

### macOS CLI

```bash
cd macos
cargo build --release -p uclip

# Start the receiver
./target/release/uclip listen --name "My MacBook"
```

The receiver will display a **6-digit pairing code**. Enter this code on your Android device.

### Android Sender

1. Open the app
2. Enter the 6-digit code displayed on your Mac
3. Either scan the network or enter the Mac's IP address manually
4. Tap **Connect**
5. Paste content into the app using the paste button
6. Tap the send icon on any item to push it to your Mac's clipboard

## Architecture

```
Android (Sender)                    macOS (Receiver)
+-------------------+              +-------------------+
| Clipboard Items   |              | TCP Listener      |
| (manual paste)    |  Noise XX   | (port 9876)       |
|                   |<----------->|                   |
| Select & Send     |  Encrypted  | Write to system   |
|                   |  TCP/Noise  | clipboard         |
+-------------------+              +-------------------+
        |                                  |
        v                                  v
   mDNS Discovery            mDNS Advertisement
   (NSD Manager)              (dns-sd / Bonjour)
```

### Pairing Flow

1. Mac generates a random 6-digit code and displays it
2. You enter the code on your Android device
3. Both devices derive a pre-shared key (PSK) from the code
4. A Noise XXpsk0 handshake authenticates both devices and exchanges permanent keys
5. After pairing, reconnections use Noise KK (no code needed)

### Security

- **Noise Protocol Framework** for end-to-end encryption
- **Curve25519** key exchange (same as WireGuard, Signal)
- **ChaCha20-Poly1305** AEAD cipher
- **Forward secrecy** via ephemeral keys
- **No cloud, no accounts** - everything stays on your local network
- Pairing code is single-use and never stored

## Project Structure

```
universal_clipboard/
├── protocol/           # Protocol specification
│   └── PROTOCOL.md
├── macos/              # macOS receiver (Cargo workspace)
│   ├── core/           # uclip-core library
│   │   └── src/
│   │       ├── lib.rs          # Module re-exports
│   │       ├── server.rs       # TCP server & session handling
│   │       ├── crypto.rs       # Noise handshakes & transport
│   │       ├── protocol.rs     # Wire protocol messages
│   │       ├── clipboard.rs    # System clipboard access
│   │       ├── discovery.rs    # mDNS advertisement
│   │       ├── storage.rs      # Key & device persistence
│   │       └── events.rs       # ServerEvent & AppState
│   ├── cli/            # uclip CLI binary
│   │   └── src/main.rs
│   └── app/            # uclip-app Tauri menu bar app
│       ├── src/
│       │   ├── main.rs         # Tray icon, window, server spawn
│       │   └── commands.rs     # Tauri IPC commands
│       └── ui/
│           ├── index.html
│           ├── main.js
│           └── style.css
└── android/            # Android Kotlin sender app
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/example/universalclipboard/
            ├── MainActivity.kt
            ├── crypto/         # Noise handshakes & identity
            ├── network/        # TCP transport & mDNS discovery
            ├── data/           # Clipboard item model
            └── ui/             # Compose UI
```

## CLI Commands

```bash
# Start receiver daemon
uclip listen [--port 9876] [--name "My Mac"]

# Show identity info
uclip status

# List paired devices
uclip devices

# Remove a paired device
uclip unpair <device-name>

# Reset identity (delete all keys and pairings)
uclip reset
```

## Auto-Start on macOS

### Menu Bar App

The Tauri menu bar app automatically registers a LaunchAgent on first launch. It will start on login without any manual setup.

### CLI (launchd)

To run the CLI receiver automatically on login:

1. Copy the binary to a permanent location:

```bash
cp macos/target/release/uclip /usr/local/bin/uclip
```

2. Create a LaunchAgent plist:

```bash
mkdir -p ~/Library/LaunchAgents
cat > ~/Library/LaunchAgents/com.universalclipboard.uclip.plist << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.universalclipboard.uclip</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/local/bin/uclip</string>
        <string>listen</string>
        <string>--name</string>
        <string>My MacBook</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/uclip.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/uclip.err</string>
</dict>
</plist>
EOF
```

3. Load the service:

```bash
launchctl load ~/Library/LaunchAgents/com.universalclipboard.uclip.plist
```

4. Manage the service:

```bash
# Stop
launchctl unload ~/Library/LaunchAgents/com.universalclipboard.uclip.plist

# Restart (unload + load)
launchctl unload ~/Library/LaunchAgents/com.universalclipboard.uclip.plist
launchctl load ~/Library/LaunchAgents/com.universalclipboard.uclip.plist

# Check status
launchctl list | grep uclip
```

## Requirements

- **Dev environment**: [Determinate Nix](https://github.com/DeterminateSystems/nix-installer) (recommended) or install manually:
  - Rust 1.70+
  - JDK 17 + Android SDK (build-tools 34, platform 34)
- **Android device**: API 26+ (Android 8.0+)
- Both devices on the same local network (WiFi)
