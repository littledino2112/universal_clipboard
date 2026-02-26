# Universal Clipboard - Protocol Specification

## Overview

Universal Clipboard is a P2P encrypted clipboard sync application. An Android device
acts as the **sender** (user manually pastes content into the app and selects items to
send), and a macOS device acts as the **receiver** (content arrives directly on the
system clipboard).

## Transport

- **TCP** over local network (LAN/WiFi)
- Receiver listens on a configurable port (default: `9876`)

## Discovery

- **mDNS/DNS-SD** service type: `_uclip._tcp.local.`
- Fallback: manual IP address entry on the Android app

## Encryption

All communication is encrypted using the **Noise Protocol Framework**.

- **Cipher suite:** `Noise_XXpsk0_25519_ChaChaPoly_SHA256`
  - Curve25519 for key exchange (same as WireGuard)
  - ChaCha20-Poly1305 for AEAD encryption
  - SHA-256 for hashing
  - Pre-shared key (PSK) for initial pairing authentication

### Key Management

Each device generates a **static Curve25519 keypair** on first launch and persists it.

## Pairing Flow (First Connection)

1. Receiver (macOS) starts listening and generates a random **6-digit numeric code**
2. Receiver displays the code to the user
3. User enters the code on the Sender (Android)
4. Both derive a PSK: `HKDF-SHA256(ikm=utf8(code), salt="uclip-pair-v1", info="psk", len=32)`
5. Sender initiates a **Noise XXpsk0** handshake:
   ```
   XXpsk0:
     -> psk, e
     <- e, ee, s, es
     -> s, se
   ```
6. If the handshake succeeds, both sides have verified the code and exchanged static keys
7. Both devices store the peer's static public key and device name
8. The pairing code is discarded (one-time use)

## Reconnection Flow (Subsequent Connections)

1. Sender connects to Receiver's TCP port
2. Sender sends a 1-byte **handshake type** marker: `0x01` (paired reconnection)
3. Both perform a **Noise KK** handshake (both already know each other's static keys):
   ```
   KK:
     -> s
     ...
     -> e, es, ss
     <- e, ee, se
   ```
4. If the handshake succeeds, communication proceeds over the encrypted channel

## Message Format (Post-Handshake)

All messages are sent through the Noise transport (encrypted + authenticated).

### Frame Format

```
+--------+----------+---------+
| Type   | Length   | Payload  |
| 1 byte | 4 bytes  | N bytes  |
|        | (big-endian)        |
+--------+----------+---------+
```

### Message Types

| Type | Name             | Payload                     |
|------|------------------|-----------------------------|
| 0x01 | CLIPBOARD_SEND   | UTF-8 text content          |
| 0x02 | CLIPBOARD_ACK    | Empty                       |
| 0x03 | PING             | Empty                       |
| 0x04 | PONG             | Empty                       |
| 0x05 | DEVICE_INFO      | JSON: `{"name": "..."}` |
| 0x06 | ERROR            | UTF-8 error message         |
| 0x07 | IMAGE_SEND_START | JSON: `{"width":W,"height":H,"totalBytes":N,"mimeType":"image/png"}` |
| 0x08 | IMAGE_CHUNK      | Raw PNG bytes (up to 60,000 bytes per chunk) |
| 0x09 | IMAGE_SEND_END   | Empty                       |
| 0x0A | IMAGE_ACK        | Empty                       |

### Flow

1. After handshake, both sides exchange `DEVICE_INFO` messages
2. Sender selects a clipboard item and sends `CLIPBOARD_SEND`
3. Receiver writes content to system clipboard and responds with `CLIPBOARD_ACK`
4. Periodic `PING`/`PONG` for keepalive (every 30 seconds)

### Image Transfer Flow

Images are transferred in chunks due to the Noise transport frame limit (~65KB).

1. Sender sends `IMAGE_SEND_START` with JSON metadata (width, height, totalBytes, mimeType)
2. Receiver validates `totalBytes <= 25 MB`; sends `ERROR` and rejects if exceeded
3. Sender sends N `IMAGE_CHUNK` messages, each with up to 60,000 bytes of raw PNG data
4. Sender sends `IMAGE_SEND_END` to signal completion
5. Receiver reassembles chunks, writes image to system clipboard, sends `IMAGE_ACK`

**Constraints:**
- Maximum image size: 25 MB (`totalBytes` in metadata)
- Maximum chunk payload: 60,000 bytes (under the ~65,519-byte Noise plaintext limit)
- Single transfer at a time: no concurrent image transfers
- Abort via `ERROR (0x06)`: if `ERROR` arrives during an active image receive, the buffer is discarded

## Security Properties

- **Forward secrecy:** Ephemeral keys ensure past sessions can't be decrypted
- **Mutual authentication:** Both devices verify each other's identity
- **Replay protection:** Noise protocol's built-in nonce management
- **No central server:** All communication is direct P2P
- **No cloud auth:** Pairing is done via a local 6-digit code
