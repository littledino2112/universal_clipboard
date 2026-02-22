# Universal Clipboard

P2P encrypted clipboard sync between Android and macOS. No central server, no cloud accounts.

## How It Works

1. **Android app** (sender): Paste content into the app, select items, and send them to your Mac
2. **macOS CLI** (receiver): Runs in the background, receives content directly onto your system clipboard

All communication is encrypted using the **Noise Protocol Framework** (same cryptographic primitives as WireGuard: Curve25519, ChaCha20-Poly1305, SHA-256).

## Quick Start

### macOS Receiver (Rust)

```bash
cd macos
cargo build --release

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
├── macos/              # macOS Rust CLI receiver
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs         # CLI entry point
│       ├── server.rs       # TCP server & session handling
│       ├── crypto.rs       # Noise handshakes & transport
│       ├── protocol.rs     # Wire protocol messages
│       ├── clipboard.rs    # System clipboard access
│       ├── discovery.rs    # mDNS advertisement
│       └── storage.rs      # Key & device persistence
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

## Requirements

- **macOS**: Rust 1.70+ (for building)
- **Android**: API 26+ (Android 8.0+), Android Studio
- Both devices on the same local network (WiFi)
