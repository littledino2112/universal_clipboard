---
title: 'Mac-to-Mac Connectivity'
slug: 'mac-to-mac-connectivity'
created: '2026-03-04'
status: 'ready-for-dev'
stepsCompleted: [1, 2, 3, 4]
tech_stack: ['rust', 'tokio', 'snow', 'mdns-sd', 'clap', 'tauri-v2']
files_to_modify:
  - 'macos/core/src/crypto.rs'
  - 'macos/core/src/discovery.rs'
  - 'macos/core/src/lib.rs'
  - 'macos/cli/src/main.rs'
  - 'macos/app/src/main.rs'
  - 'macos/app/src/commands.rs'
code_patterns:
  - 'Noise handshake builder pattern: Builder::new(pattern).local_private_key(...).build_initiator()'
  - 'Handshake type byte prefix written before Noise messages (HANDSHAKE_PAIRING=0x00, HANDSHAKE_PAIRED=0x01)'
  - 'Paired reconnection also prefixes 32-byte local public key after handshake type byte'
  - 'mdns-sd ServiceDaemon used for both registration and browsing'
  - 'AppState Arc<> shared across server and client tasks via tauri::async_runtime::spawn'
  - 'tokio::sync::mpsc unbounded channel bridges handle_session outbound messages'
  - 'CancellationToken for graceful shutdown of long-lived tasks'
  - 'server::handle_session is symmetric — reuse it unchanged for the client side'
test_patterns:
  - 'Rust: inline #[cfg(test)] mod tests at bottom of each source file'
  - 'In-memory Noise handshake tests without TCP (see crypto.rs test_noise_xxpsk0_handshake_in_memory)'
  - 'tempfile::TempDir for DeviceStore tests'
  - 'tokio::test attribute for async unit tests'
---

# Tech-Spec: Mac-to-Mac Connectivity

**Created:** 2026-03-04

## Overview

### Problem Statement

The Mac client is hardcoded as TCP server / Noise responder only. It has no TCP client code (`TcpStream::connect` is never called), no Noise initiator handshake functions, and no mDNS browsing (only registration). This means two Macs cannot connect to each other — one Mac must always be the Android phone's role — which does not exist as a Mac feature.

Specifically:
- `macos/core/src/crypto.rs` exports only `accept_connection`, `handshake_pairing_responder`, and `handshake_paired_responder`. No initiator variants exist.
- `macos/core/src/discovery.rs` exports only `DiscoveryServer` which calls `mdns.register(...)`. It never calls `mdns.browse(...)`.
- `macos/core/src/server.rs` contains `run_server(listener: TcpListener, ...)`. No equivalent `connect_to_peer` function exists.

### Solution

Add the three missing initiator-side capabilities to `uclip-core`, mirroring the Android implementation:

1. **Noise initiator handshakes** — `handshake_pairing_initiator` (XXpsk0) and `handshake_paired_initiator` (KK), plus a `connect_to_peer` dispatcher that writes the handshake-type prefix byte (and public key for `HANDSHAKE_PAIRED`) before delegating — exactly symmetric to the existing `accept_connection`.

2. **TCP client** — `client::connect` (new module `macos/core/src/client.rs`) that calls `TcpStream::connect`, runs the appropriate initiator handshake, and then calls the existing `handle_session` logic (already fully symmetric at the message level).

3. **mDNS browser** — `DiscoveryBrowser` struct in `discovery.rs` that uses `ServiceDaemon::browse` and exposes a `tokio::sync::watch` channel of discovered `DiscoveredDevice` entries so callers can react to arrivals/departures.

Expose the new capability through:
- A new `uclip` CLI subcommand: `uclip connect <host> <port> [--pairing-code CODE]`
- A new Tauri command: `connect_to_device(host, port, pairing_code?)` for the app

### Scope

**In Scope:**
- `handshake_pairing_initiator` and `handshake_paired_initiator` in `crypto.rs`
- `connect_to_peer` dispatcher in `crypto.rs`
- New `macos/core/src/client.rs` module with `connect` function
- `DiscoveryBrowser` struct in `discovery.rs`
- `pub mod client;` export in `lib.rs`
- CLI `Connect` subcommand in `macos/cli/src/main.rs`
- Tauri `connect_to_device` command in `macos/app/src/commands.rs`
- Wire-up of `DiscoveryBrowser` in Tauri app setup

**Out of Scope:**
- UI redesign or new panel views for showing discovered devices (a future task)
- Android changes of any kind
- Simultaneous multi-device connections (current `session_tx` is a single `Option`)
- Auto-reconnect / connection persistence across restarts
- Mdns TXT record extensions

## Context for Development

### Codebase Patterns

**Noise handshake builder pattern (from `crypto.rs`):**

The existing responder functions follow a strict pattern that initiators must mirror. For `XXpsk0` pairing:

```rust
// Existing responder (crypto.rs lines 131-171)
pub async fn handshake_pairing_responder(
    mut stream: TcpStream,
    identity: &Identity,
    pairing_code: &str,
) -> Result<(NoiseTransport, Vec<u8>)> {
    let psk = derive_psk_from_code(pairing_code);
    let builder = Builder::new(NOISE_PATTERN_PAIRING.parse()?)
        .local_private_key(&identity.private_key)
        .psk(0, &psk);
    let mut handshake = builder.build_responder()?;
    // ... 3-message exchange
}
```

The initiator differs only in: (a) calling `.build_initiator()` instead of `.build_responder()`, and (b) reversing the read/write order of the 3 messages.

**Handshake type prefix (from `crypto.rs` `accept_connection`, lines 205-238):**

The server reads one byte before doing the Noise handshake:
- `0x00` (`HANDSHAKE_PAIRING`) — go straight to `handshake_pairing_responder`
- `0x01` (`HANDSHAKE_PAIRED`) — read 32 more bytes (the remote's public key for device lookup), then call `handshake_paired_responder`

The client (`connect_to_peer`) must write these bytes first, exactly as the Android `NoiseHandshake.kt` does (lines 66-67, 114-116).

**Android reference for pairing initiator (NoiseHandshake.kt lines 50-94):**

```kotlin
fun pairingHandshake(socket, localKeyPair, pairingCode): Pair<CipherStatePair, ByteArray> {
    output.writeByte(HANDSHAKE_PAIRING)   // prefix byte
    // msg1: initiator writes (-> psk, e)
    val msg1Len = handshake.writeMessage(...)
    output.writeShort(msg1Len); output.write(buf, 0, msg1Len)
    // msg2: initiator reads (<- e, ee, s, es)
    val msg2Len = input.readUnsignedShort(); handshake.readMessage(msg2, ...)
    // msg3: initiator writes (-> s, se)
    val msg3Len = handshake.writeMessage(...)
    output.writeShort(msg3Len); output.write(buf, 0, msg3Len)
}
```

Rust translation uses `stream.write_u16` / `stream.read_u16` (big-endian u16 length prefix, matching `NoiseTransport::send`/`recv`).

**Android reference for paired initiator (NoiseHandshake.kt lines 100-133):**

```kotlin
fun pairedHandshake(socket, localKeyPair, remotePublicKey): CipherStatePair {
    output.writeByte(HANDSHAKE_PAIRED)    // prefix byte
    output.write(localKeyPair.publicKey)  // 32-byte self-identification
    // msg1: initiator writes (-> e, es, ss)
    val msg1Len = handshake.writeMessage(...)
    // msg2: initiator reads (<- e, ee, se)
    val msg2Len = input.readUnsignedShort(); handshake.readMessage(msg2, ...)
    return handshake.split()
}
```

**mdns-sd browsing API:**

The crate already in `Cargo.toml` (`mdns-sd = "0.11"`) supports both registering and browsing on the same `ServiceDaemon`. Browse via:

```rust
let receiver = mdns.browse(SERVICE_TYPE)?;  // returns crossbeam Receiver<ServiceEvent>
```

`ServiceEvent` variants include `ServiceResolved(ServiceInfo)` and `ServiceRemoved(String, String)`. `ServiceInfo` has `.get_addresses()` (returns `HashSet<IpAddr>`) and `.get_port()`.

**`handle_session` reuse:**

`server::handle_session` in `server.rs` (lines 124-145) takes `NoiseTransport` and `&AppState` — it has no knowledge of whether it was the TCP server or client side. It is fully symmetric and must be called unchanged from the new `client::connect` function.

**`AppState` field for outbound messages:**

`state.session_tx: Arc<RwLock<Option<mpsc::UnboundedSender<Message>>>>` is set by `handle_session` while a session is active and cleared on disconnect. The Tauri `send_clipboard_item` command reads this field to enqueue messages. No changes needed — the client path sets and clears it through the same `handle_session` call.

### Files to Reference

| File | Purpose |
|------|---------|
| `macos/core/src/crypto.rs` | Add `handshake_pairing_initiator`, `handshake_paired_initiator`, `connect_to_peer` |
| `macos/core/src/discovery.rs` | Add `DiscoveryBrowser` struct using `mdns.browse()` |
| `macos/core/src/server.rs` | Reference for `handle_session` signature — call it unchanged from client path |
| `macos/core/src/events.rs` | `AppState` struct and `ServerEvent` enum — add `ConnectionInitiated` event variant |
| `macos/core/src/lib.rs` | Add `pub mod client;` |
| `macos/cli/src/main.rs` | Add `Connect` subcommand |
| `macos/app/src/commands.rs` | Add `connect_to_device` Tauri command |
| `macos/app/src/main.rs` | Register new command in `invoke_handler`, optionally wire `DiscoveryBrowser` |
| `android/.../crypto/NoiseHandshake.kt` | Reference for initiator handshake message ordering |
| `android/.../network/ConnectionManager.kt` | Reference for TCP connect + handshake flow |
| `android/.../network/DeviceDiscovery.kt` | Reference for mDNS browse pattern |

### Technical Decisions

1. **New `client.rs` module rather than expanding `server.rs`** — Server-side logic (accept loop) and client-side logic (connect-once) have different lifecycles. Separating them keeps each file focused and matches the existing module structure.

2. **`DiscoveryBrowser` uses `tokio::sync::watch`** — The mdns-sd `ServiceDaemon::browse` returns a crossbeam `Receiver<ServiceEvent>`. Wrap this in a tokio task that translates events into a `watch::Sender<Vec<DiscoveredDevice>>`, allowing callers to `watch.borrow()` the current device list or `.changed().await` for reactive updates.

3. **Store host/port in `DeviceStore`** — Currently `paired_devices.json` stores `{ name -> public_key_hex }`. For initiator reconnection the Mac needs the peer's last-known host and port. Add an optional `host` and `port` field to the stored entry. Use `serde` default (None) for backward compatibility with existing entries. A new `save_paired_device_with_addr` method avoids breaking the existing `save_paired_device` call signature.

4. **Pairing via CLI requires user to supply the remote pairing code** — The remote Mac (running as server) displays its code; the initiating Mac supplies it on the command line with `--pairing-code`. This mirrors the Android UX.

5. **`connect_to_peer` saves the pairing upon success** — Same as `accept_connection` which calls `store.save_paired_device(...)` after a successful `HANDSHAKE_PAIRING`. The initiator must do the same, storing the remote static key + address.

## Implementation Plan

### Tasks

---

#### Task 1: Extend `DeviceStore` to persist host/port

**File:** `macos/core/src/storage.rs`

**What to do:**

1. Change `PairedDevices.devices` map value from `String` to a new struct:
   ```rust
   #[derive(serde::Serialize, serde::Deserialize)]
   struct StoredDevice {
       public_key: String,        // hex
       #[serde(default)]
       host: Option<String>,
       #[serde(default)]
       port: Option<u16>,
   }
   ```
   Update `PairedDevices` to `HashMap<String, StoredDevice>`.

2. Add method:
   ```rust
   pub fn save_paired_device_with_addr(
       &self,
       name: &str,
       public_key: &[u8],
       host: &str,
       port: u16,
   ) -> Result<()>
   ```

3. Add method:
   ```rust
   pub fn get_device_addr(&self, name: &str) -> Result<Option<(String, u16)>>
   ```

4. Update existing `save_paired_device` to populate `StoredDevice { public_key, host: None, port: None }` — no change to callers.

5. Update `find_device_by_key` and `list_paired_devices` to work with the new struct.

**Tests to add** (`#[cfg(test)]` block at bottom of `storage.rs`):
- `test_save_and_retrieve_device_addr` — save with address, verify `get_device_addr` returns correct values
- `test_save_device_addr_backward_compat` — save via old `save_paired_device`, verify `get_device_addr` returns `None`
- `test_save_device_addr_overwrites` — save twice with different address, verify latest wins

---

#### Task 2: Add Noise initiator handshake functions to `crypto.rs`

**File:** `macos/core/src/crypto.rs`

**What to do:**

Add the following three functions after the existing responder functions (after line 202):

```rust
/// Perform a Noise XXpsk0 handshake as the initiator (connecting Mac).
/// Sends HANDSHAKE_PAIRING prefix byte, then executes 3-message XXpsk0.
/// Returns the transport and the remote's static public key.
pub async fn handshake_pairing_initiator(
    mut stream: TcpStream,
    identity: &Identity,
    pairing_code: &str,
) -> Result<(NoiseTransport, Vec<u8>)>
```

Message order for initiator (mirror of responder in reverse):
- Write `HANDSHAKE_PAIRING` byte (`stream.write_u8(HANDSHAKE_PAIRING).await?`)
- Build `Builder::new(NOISE_PATTERN_PAIRING.parse()?).local_private_key(...).psk(0, &psk).build_initiator()?`
- `-> psk, e` — `handshake.write_message(&[], &mut buf)?`, send length-prefixed
- `<- e, ee, s, es` — recv length-prefixed, `handshake.read_message(...)`
- `-> s, se` — `handshake.write_message(&[], &mut buf)?`, send length-prefixed
- Extract `handshake.get_remote_static()`, call `handshake.into_transport_mode()?`

```rust
/// Perform a Noise KK handshake as the initiator for a paired device.
/// Sends HANDSHAKE_PAIRED prefix byte + 32-byte local public key for identification.
pub async fn handshake_paired_initiator(
    mut stream: TcpStream,
    identity: &Identity,
    remote_static_key: &[u8],
) -> Result<NoiseTransport>
```

Message order:
- Write `HANDSHAKE_PAIRED` byte
- Write `identity.public_key` (32 bytes) — so responder can look up device
- Build `Builder::new(NOISE_PATTERN_PAIRED.parse()?).local_private_key(...).remote_public_key(remote_static_key).build_initiator()?`
- `-> e, es, ss` — write message 1
- `<- e, ee, se` — read message 2
- `handshake.into_transport_mode()?`

```rust
/// Initiate a connection to a remote Mac.
/// Dispatches to pairing or paired handshake based on whether the device is known.
pub async fn connect_to_peer(
    stream: TcpStream,
    identity: &Identity,
    pairing_code: Option<&str>,
    store: &DeviceStore,
    remote_public_key: Option<&[u8]>,
) -> Result<(NoiseTransport, String)>
```

Logic:
- If `remote_public_key` is `Some(key)` and no `pairing_code` — use `handshake_paired_initiator`, return device name from `store.find_device_by_key(key)?.unwrap_or("unknown-device")`
- If `pairing_code` is `Some(code)` — use `handshake_pairing_initiator`, call `store.save_paired_device_with_addr(...)`, return derived device name `format!("device-{}", hex::encode(&remote_key[..4]))`
- Otherwise — `bail!("must supply either pairing_code or remote_public_key")`

**Tests to add** (`#[cfg(test)]` block):
- `test_noise_xxpsk0_initiator_responder_pair` — full in-memory handshake with `build_initiator()` + `build_responder()` in the initiator-first message order, verifying both remote static keys are obtained. (Pattern: same as existing `test_noise_xxpsk0_handshake_in_memory` but explicitly names the roles.)
- `test_noise_kk_initiator_responder_pair` — analogous for KK, verifying encrypted message exchange.

---

#### Task 3: Add `client.rs` module

**File:** `macos/core/src/client.rs` (new file)

**What to do:**

```rust
use anyhow::Result;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;
use tracing::info;

use crate::crypto::{self, Identity};
use crate::events::{AppState, ServerEvent};
use crate::server::handle_session;   // re-use existing session handler
use crate::storage::DeviceStore;

/// Connect to a remote Mac as the TCP/Noise initiator.
/// If `pairing_code` is Some, performs a first-time pairing (XXpsk0).
/// If `remote_public_key` is Some, performs a paired reconnection (KK).
/// After handshake, runs the same handle_session loop as the server path.
pub async fn connect(
    addr: &str,
    identity: &Identity,
    pairing_code: Option<&str>,
    remote_public_key: Option<&[u8]>,
    state: Arc<AppState>,
    cancel: CancellationToken,
) -> Result<()>
```

Implementation steps:
1. `let stream = TcpStream::connect(addr).await?;` — the only `TcpStream::connect` call in the whole Mac codebase
2. Call `crypto::connect_to_peer(stream, identity, pairing_code, &state.store, remote_public_key).await?` → `(transport, remote_name)`
3. `state.emit(ServerEvent::DeviceConnected { name: remote_name.clone() })`
4. Set `state.connected_device` write lock to `Some(remote_name.clone())`
5. Call `handle_session(transport, &state, &cancel).await` (same function as server path)
6. On return: emit `ServerEvent::DeviceDisconnected`, clear `connected_device`

This function has identical structure to the per-connection block in `server::run_server` (lines 41-74 of `server.rs`).

**Note:** `handle_session` in `server.rs` is `async fn handle_session(...)` — it is currently private. Change its visibility to `pub(crate)` so `client.rs` can call it.

**Tests to add:**
- `test_connect_returns_error_on_refused` — attempt `connect` to `127.0.0.1:1` (port guaranteed to refuse), verify `Err` is returned quickly (no panic, no hang). Use `tokio::test`.

---

#### Task 4: Add `DiscoveryBrowser` to `discovery.rs`

**File:** `macos/core/src/discovery.rs`

**What to do:**

Add a `DiscoveredDevice` struct and `DiscoveryBrowser` struct after the existing `DiscoveryServer`:

```rust
/// A device discovered via mDNS browsing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub name: String,
    pub host: String,
    pub port: u16,
}

/// Browse for Universal Clipboard peers on the local network.
pub struct DiscoveryBrowser {
    // Internal handle to the background browse task
    _task: tokio::task::JoinHandle<()>,
    pub devices: tokio::sync::watch::Receiver<Vec<DiscoveredDevice>>,
}

impl DiscoveryBrowser {
    pub fn new() -> Result<Self>
}
```

`DiscoveryBrowser::new()` implementation:
1. Create a `ServiceDaemon::new()?`
2. Call `let receiver = mdns.browse(SERVICE_TYPE)?` — returns a `crossbeam_channel::Receiver<ServiceEvent>` (re-exported by mdns-sd)
3. Create a `tokio::sync::watch::channel::<Vec<DiscoveredDevice>>(vec![])` → `(tx, rx)`
4. Spawn a `tokio::task::spawn_blocking` (or `tokio::spawn` with `recv` in a blocking context) that loops:
   - `receiver.recv()` on each `ServiceEvent`
   - On `ServiceEvent::ServiceResolved(info)`: extract first address from `info.get_addresses()`, add to device list, send via `tx`
   - On `ServiceEvent::ServiceRemoved(_, fullname)`: remove matching entry, send via `tx`
   - On `ServiceEvent::SearchStopped(_)`: break
5. Return `Self { _task: task_handle, devices: rx }`

**Note on address extraction:** `info.get_addresses()` returns `&HashSet<IpAddr>`. Pick the first IPv4 address with `info.get_addresses().iter().find(|a| a.is_ipv4()).map(|a| a.to_string())`. Fall back to any address if no IPv4 is found.

**Tests to add:**
- `test_discovered_device_fields` — construct a `DiscoveredDevice` directly, verify field access (pure struct test, no network).

---

#### Task 5: Export `client` module from `lib.rs`

**File:** `macos/core/src/lib.rs`

**What to do:**

Add one line:

```rust
pub mod client;
```

Current `lib.rs` content (lines 1-7):
```rust
pub mod clipboard;
pub mod crypto;
pub mod discovery;
pub mod events;
pub mod protocol;
pub mod server;
pub mod storage;
```

Becomes:
```rust
pub mod client;
pub mod clipboard;
pub mod crypto;
pub mod discovery;
pub mod events;
pub mod protocol;
pub mod server;
pub mod storage;
```

No tests needed for this change.

---

#### Task 6: Add `Connect` subcommand to CLI

**File:** `macos/cli/src/main.rs`

**What to do:**

1. Add a `Connect` variant to the `Commands` enum:

```rust
/// Connect to another Mac as the initiator
Connect {
    /// Target host (IP or hostname)
    host: String,

    /// Target port
    #[arg(short, long, default_value_t = 9876)]
    port: u16,

    /// Pairing code displayed on the remote Mac (omit if already paired)
    #[arg(short, long)]
    pairing_code: Option<String>,

    /// Device name for this Mac (local identity)
    #[arg(short, long, default_value = "My Mac")]
    name: String,
},
```

2. Add a match arm in `main()`:

```rust
Commands::Connect { host, port, pairing_code, name } => {
    let identity = crypto::Identity::load_or_generate(&store)?;

    // Resolve remote_public_key from store if no pairing_code given
    let remote_public_key: Option<Vec<u8>> = if pairing_code.is_none() {
        // Look for any known device at this host/port
        // For simplicity: user must supply pairing_code on first connect.
        // On reconnect the host/port match is handled inside client::connect.
        None
    } else {
        None
    };

    let pairing_code_ref = pairing_code.as_deref();
    let addr = format!("{}:{}", host, port);

    println!("Connecting to {} ...", addr);

    let state = Arc::new(AppState::new(
        identity,
        crypto::generate_pairing_code(), // our own server pairing code (unused in client mode)
        name.clone(),
        store,
        port,
    ));

    let cancel = CancellationToken::new();

    // Ctrl-C handler
    let cancel_clone = cancel.clone();
    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        cancel_clone.cancel();
    });

    // Subscribe to events for terminal output
    let mut rx = state.subscribe();
    tokio::spawn(async move {
        while let Ok(event) = rx.recv().await {
            println!("[event] {:?}", event);
        }
    });

    uclip_core::client::connect(
        &addr,
        &state.identity,
        pairing_code_ref,
        remote_public_key.as_deref(),
        state,
        cancel,
    ).await?;
}
```

3. Add `use uclip_core::client;` to imports (or use full path).

**Note:** The `state.identity` borrow conflict (moving `state` while borrowing `.identity`) can be resolved by extracting the identity reference before the `state` Arc is moved, or by cloning the identity fields. The cleanest approach: extract `let identity_ref = &state.identity;` before the async block, but since `state` is `Arc<AppState>` and `client::connect` takes `&Identity`, pass `&state.identity` — the borrow is on the Arc which is still alive. Actually pass `state.clone()` to the event subscriber and the original to `connect`. Adjust accordingly.

---

#### Task 7: Add `connect_to_device` Tauri command

**File:** `macos/app/src/commands.rs`

**What to do:**

Add a new command after `unpair_device`:

```rust
#[tauri::command]
pub async fn connect_to_device(
    host: String,
    port: u16,
    pairing_code: Option<String>,
    state: State<'_, Arc<AppState>>,
    cancel: State<'_, CancellationToken>,
) -> Result<(), String> {
    let addr = format!("{}:{}", host, port);

    // Determine remote public key from store if pairing_code is absent
    // For initial implementation: if no pairing_code, scan store for matching host/port
    // This is a best-effort lookup; fails gracefully if not found
    let remote_key: Option<Vec<u8>> = if pairing_code.is_none() {
        find_known_device_key_by_addr(&state.store, &host, port)
    } else {
        None
    };

    let pairing_ref = pairing_code.as_deref();
    let state_clone = state.inner().clone();
    let cancel_clone = cancel.inner().clone();

    tauri::async_runtime::spawn(async move {
        if let Err(e) = uclip_core::client::connect(
            &addr,
            &state_clone.identity,
            pairing_ref,
            remote_key.as_deref(),
            state_clone,
            cancel_clone,
        ).await {
            tracing::error!("connect_to_device failed: {}", e);
        }
    });

    Ok(())
}

fn find_known_device_key_by_addr(store: &uclip_core::storage::DeviceStore, host: &str, port: u16) -> Option<Vec<u8>> {
    // Iterate paired devices looking for one stored with this host/port
    // Returns the public key bytes if found
    store.list_paired_devices().ok()?.into_iter().find_map(|(name, key_hex)| {
        let addr = store.get_device_addr(&name).ok()??;
        if addr.0 == host && addr.1 == port {
            hex::decode(key_hex).ok()
        } else {
            None
        }
    })
}
```

Add required imports to the top of `commands.rs`:
- `use uclip_core::client;` (or use full path)

**File:** `macos/app/src/main.rs`

Register the new command in `invoke_handler`:

```rust
.invoke_handler(tauri::generate_handler![
    commands::get_status,
    commands::get_devices,
    commands::unpair_device,
    commands::connect_to_device,   // NEW
    commands::paste_clipboard,
    commands::get_clipboard_items,
    commands::send_clipboard_item,
    commands::remove_clipboard_item,
    commands::paste_image_from_clipboard,
    commands::send_image_item,
])
```

Optionally (can be deferred): instantiate `DiscoveryBrowser` in the Tauri setup block and store it in managed state so the frontend can query discovered devices. Minimum viable: just register the command; browser can be wired up in a follow-on task.

---

#### Task 8: Make `handle_session` pub(crate) in `server.rs`

**File:** `macos/core/src/server.rs`

**What to do:**

Change line 124 from:
```rust
async fn handle_session(
```
to:
```rust
pub(crate) async fn handle_session(
```

This allows `client.rs` to call `server::handle_session(...)` without duplicating the session loop.

No functional change, no new tests needed.

---

### Acceptance Criteria

#### Feature: Noise pairing initiator handshake

**Given** two Noise handshake states configured with matching `XXpsk0` pattern and same PSK,
**When** the initiator runs `handshake_pairing_initiator` and the responder runs `handshake_pairing_responder` against in-memory byte buffers,
**Then** both sides complete without error, both `get_remote_static()` calls return the other party's public key, and the resulting transports can exchange encrypted messages.

#### Feature: Noise paired reconnect initiator handshake

**Given** two Noise handshake states configured with matching `KK` pattern and pre-known key pairs,
**When** the initiator runs `handshake_paired_initiator` and the responder runs `handshake_paired_responder`,
**Then** both sides complete without error and the resulting transports exchange encrypted messages symmetrically.

#### Feature: TCP client connect

**Given** a running `run_server` instance bound to a local port and an `AppState` with a valid identity,
**When** `client::connect` is called pointing to that port with a valid pairing code,
**Then** the server accepts the connection, both sides complete the Noise handshake, `DeviceConnected` events are emitted on both sides, and `session_tx` is populated so that `send_clipboard_item` can queue a message that the other side receives and ACKs.

#### Feature: mDNS browsing

**Given** a `DiscoveryServer` advertising `_uclip._tcp.local.` on port 9876,
**When** a `DiscoveryBrowser` is created on the same host,
**Then** within 5 seconds the `devices` watch receiver contains at least one entry whose `port` is 9876.

#### Feature: CLI `connect` subcommand

**Given** Mac A is running `uclip listen --port 9876` and displays pairing code "123456",
**When** Mac B runs `uclip connect 192.168.1.x --port 9876 --pairing-code 123456`,
**Then** Mac B outputs a `[event] DeviceConnected` line, Mac A logs "authenticated", and both processes remain connected until Ctrl-C.

#### Feature: Tauri `connect_to_device` command

**Given** the tray app is running and a remote Mac is listening on a known address,
**When** the JS frontend calls `invoke('connect_to_device', { host, port, pairingCode })`,
**Then** the command returns `Ok(())` without blocking the UI thread, a background task establishes the connection, and the frontend receives a `server-event` with type `DeviceConnected`.

#### Feature: Host/port persistence

**Given** a successful pairing initiated by `client::connect` with `pairing_code = Some("123456")`,
**When** `store.get_device_addr(&device_name)` is called,
**Then** it returns `Some((host, port))` matching the address used for the connection.

## Additional Context

### Dependencies

No new crate dependencies are required. All needed crates are already in `macos/core/Cargo.toml`:

| Crate | Version | Usage |
|-------|---------|-------|
| `snow` | 0.9 | `Builder::new(...).build_initiator()` — already used for responder |
| `tokio` | 1 (full) | `TcpStream::connect`, `watch::channel`, `spawn_blocking` |
| `mdns-sd` | 0.11 | `ServiceDaemon::browse` — already used for registration |
| `hkdf`, `sha2`, `rand`, `hex` | existing | Already used by `derive_psk_from_code` |

The `crossbeam_channel` type returned by `mdns-sd::ServiceDaemon::browse` is re-exported by `mdns-sd` — no direct `crossbeam-channel` dependency needed.

### Testing Strategy

**Unit tests (no network required):**
- All Noise handshake tests use in-memory byte buffers (pattern established by `test_noise_xxpsk0_handshake_in_memory` in `crypto.rs`)
- `DeviceStore` tests use `tempfile::TempDir` (pattern established in `storage.rs`)
- `DiscoveredDevice` struct test is pure field access

**Integration tests (network, can be skipped in CI):**
- The `test_connect_returns_error_on_refused` test uses `127.0.0.1:1` which will refuse immediately — no real server needed
- A full round-trip test (server + client in same process using `TcpListener::bind("127.0.0.1:0")` for a random port) can be added as a `#[tokio::test]` in `client.rs` or a separate integration test file under `macos/core/tests/`

**Test naming convention:** Follow the existing pattern of `test_<what_is_being_tested>` in `snake_case`, placed in `#[cfg(test)] mod tests` at the bottom of each source file.

### Notes

- **Wire format compatibility:** The protocol byte ordering between `handshake_pairing_initiator` (Rust) and `handshake_pairing_responder` (Rust) must match the existing Android↔Mac pair. The PSK test vector `derive_psk_from_code("123456") == "2ae98c1b..."` (verified in `test_derive_psk_known_vector`) ensures HKDF is already compatible. The 3-message XXpsk0 exchange direction already works between Android (initiator) and Mac (responder), so Rust initiator↔Rust responder will work identically using the same `snow` crate.

- **KK remote-key identification:** In `accept_connection` (responder side), after reading `HANDSHAKE_PAIRED` the server reads 32 bytes of the initiator's public key to look up the device in the store. The initiator (`handshake_paired_initiator`) must write exactly these 32 bytes (`identity.public_key`) immediately after the handshake-type byte, before any Noise messages. This matches the Android `NoiseHandshake.pairedHandshake` (line 115: `output.write(localKeyPair.publicKey)`).

- **Concurrent server + client:** The current `AppState.session_tx` is a single `Option`. If both `run_server` and `client::connect` are active simultaneously and each establishes a session, the second one will overwrite `session_tx`. For now this is acceptable (the feature request explicitly excludes multi-device simultaneous connections). A future task could add a connection role field or a map.

- **mDNS self-discovery exclusion:** When browsing, a Mac will discover its own `DiscoveryServer` advertisement. The browser should filter out entries whose `name` matches `state.device_name` or whose resolved address matches a local interface. This can be deferred — it is not a correctness issue, just a UX refinement.

- **`server::handle_session` visibility:** Currently `async fn handle_session` (line 124) is private to `server.rs`. The simplest fix is `pub(crate)`. An alternative is to extract the session loop into a shared `session.rs` module, but that is unnecessary complexity for this feature.
