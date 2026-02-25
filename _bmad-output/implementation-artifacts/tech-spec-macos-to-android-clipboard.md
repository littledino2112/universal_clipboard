---
title: 'macOS to Android Clipboard Sending'
slug: 'macos-to-android-clipboard'
created: '2026-02-25'
status: 'ready-for-dev'
stepsCompleted: [1, 2, 3, 4]
tech_stack: ['rust', 'kotlin', 'tauri-v2', 'jetpack-compose', 'noise-protocol', 'tokio', 'snow', 'arboard']
files_to_modify:
  - 'macos/core/src/events.rs'
  - 'macos/core/src/server.rs'
  - 'macos/core/src/protocol.rs'
  - 'macos/core/src/clipboard.rs'
  - 'macos/app/src/commands.rs'
  - 'macos/app/src/main.rs'
  - 'macos/app/ui/index.html'
  - 'macos/app/ui/main.js'
  - 'macos/app/ui/style.css'
  - 'android/app/src/main/java/com/example/universalclipboard/network/ConnectionManager.kt'
  - 'android/app/src/main/java/com/example/universalclipboard/service/ClipboardSyncService.kt'
code_patterns:
  - 'AppState with Arc<RwLock<...>> for shared mutable state'
  - 'tokio::select! for multiplexing async futures'
  - 'Tauri commands (async fn with State<Arc<AppState>>) for UI→backend'
  - 'broadcast::Sender<ServerEvent> for backend→UI events'
  - 'ConnectionManager receive loop with when(msg.type) dispatch'
test_patterns:
  - 'Rust: inline #[cfg(test)] mod tests at bottom of source files'
  - 'Kotlin: FakeTransport with piped streams, no real Noise crypto'
  - 'Kotlin: backtick method names, runTest coroutine scope'
---

# Tech-Spec: macOS to Android Clipboard Sending

**Created:** 2026-02-25

## Overview

### Problem Statement

Clipboard sync is currently one-way (Android→macOS only). Users cannot send text from their Mac to a connected Android device.

### Solution

Add clipboard item management to the macOS tray panel UI (mirroring Android's UX pattern), with manual paste and send actions. Android silently writes received clipboard content to the system clipboard and sends back an acknowledgment.

### Scope

**In Scope:**
- macOS panel UI: "Paste from clipboard" button, clipboard items list (max 5), per-item "Send" and "Delete" buttons
- macOS core: send `CLIPBOARD_SEND` message over existing connection, handle `CLIPBOARD_ACK`
- Android: handle incoming `CLIPBOARD_SEND` in receive loop, write to system clipboard silently, send `CLIPBOARD_ACK` back
- Text-only content

**Out of Scope:**
- Image/rich content support
- Pull model (Android requesting clipboard from macOS)
- Connection model changes (Android stays TCP initiator)
- Auto-sync / clipboard change monitoring
- Clipboard persistence across app restarts

## Context for Development

### Codebase Patterns

- macOS tray app uses a panel window (HTML/JS/CSS) positioned near the tray icon, not native menu items
- Android uses ViewModel + StateFlow with `ClipboardItem` data class, FAB for paste, cards with send/delete
- Protocol messages use `CLIPBOARD_SEND` (0x01) and `CLIPBOARD_ACK` (0x02) — already bidirectional
- Tauri commands bridge JS ↔ Rust state; events broadcast from core via `"server-event"` channel
- User explicitly stated: mirror Android's UX pattern but cap at 5 items instead of 10
- `snow::TransportState` cannot be split into read/write halves — must multiplex at `handle_session` level
- `tokio::sync::mpsc` already available via tokio "full" features — no new crate dependencies
- Android `ClipboardManager.setPrimaryClip()` requires no special permissions (API 26+)
- Foreground service can write to system clipboard without user interaction

### Files to Reference

| File | Purpose |
| ---- | ------- |
| `macos/core/src/events.rs` | `AppState` struct — add `session_tx` field for outbound message channel |
| `macos/core/src/server.rs` | `handle_session()` — refactor `tokio::select!` to multiplex outbound channel |
| `macos/core/src/protocol.rs` | `Message::clipboard_send()` — already exists (line 46, currently `#[allow(dead_code)]`) |
| `macos/core/src/clipboard.rs` | `get_clipboard_text()` — already exists (line 17, currently `#[allow(dead_code)]`) |
| `macos/app/src/commands.rs` | Tauri commands — add `paste_clipboard`, `send_clipboard_item`, `get_clipboard_items`, `remove_clipboard_item` |
| `macos/app/src/main.rs` | Tauri setup — register new commands, adjust panel window height |
| `macos/app/ui/index.html` | Panel HTML — add clipboard section |
| `macos/app/ui/main.js` | Panel JS — add clipboard item management and send logic |
| `macos/app/ui/style.css` | Panel CSS — style clipboard section (Catppuccin dark theme) |
| `android/.../network/ConnectionManager.kt` | Receive loop — add `CLIPBOARD_SEND` handler |
| `android/.../service/ClipboardSyncService.kt` | Service — pass `Context` to `ConnectionManager` |
| `android/.../network/Protocol.kt` | `MessageType.CLIPBOARD_SEND` — already defined (0x01) |
| `android/.../data/ClipboardItem.kt` | Reference for data model pattern |

### Technical Decisions

- **Manual trigger model**: user pastes to app, then sends — no auto-sync or clipboard monitoring
- **Max 5 clipboard items on macOS** (vs 10 on Android) — user-specified constraint
- **Silent clipboard write on Android** (no notification, no UI update)
- **Reuse existing protocol messages** — `CLIPBOARD_SEND`/`CLIPBOARD_ACK` are already bidirectional, no protocol changes
- **Outbound channel pattern**: `tokio::sync::mpsc::unbounded_channel` stored as `Arc<RwLock<Option<UnboundedSender<Message>>>>` in `AppState`. Created when session starts, cleared when session ends. Tauri commands push messages into channel, `handle_session` consumes them in `tokio::select!`
- **Context injection**: Pass `Context` to `ConnectionManager` constructor so it can write to system clipboard via `ClipboardManager`
- **Panel height increase**: Increase from 420 → 560 to accommodate the new clipboard section
- **ACK handling on macOS**: When `handle_session` receives `ClipboardAck` from the remote, emit `ServerEvent::ClipboardSent { chars }` so the UI can mark the item as sent. No strict request/response correlation needed — only one connection, one outstanding send at a time
- **Clipboard item state on macOS**: Managed entirely in the Tauri app layer (in-memory JS array or Rust managed state). Items are ephemeral — lost on app restart. Each item has: id, text, timestamp, sent status

## Implementation Plan

### Tasks

#### Phase 1: macOS Core — Outbound Channel Infrastructure

- [ ] Task 1: Add `session_tx` to `AppState` and `ClipboardSent` event variant
  - File: `macos/core/src/events.rs`
  - Action: Add `use tokio::sync::mpsc` import. Add field `pub session_tx: Arc<RwLock<Option<mpsc::UnboundedSender<crate::protocol::Message>>>>` to `AppState`. Initialize as `Arc::new(RwLock::new(None))` in `AppState::new()`. Add `ClipboardSent { chars: usize }` variant to `ServerEvent` enum.
  - Notes: Follows existing `connected_device: Arc<RwLock<Option<String>>>` pattern exactly.

- [ ] Task 2: Refactor `handle_session` to multiplex outbound channel
  - File: `macos/core/src/server.rs`
  - Action: At the start of `handle_session`, create `let (tx, mut rx) = mpsc::unbounded_channel()`. Store `tx` in `state.session_tx` (acquire write lock, set to `Some(tx)`, drop lock). Add a new branch to `tokio::select!`: `Some(outbound_msg) = rx.recv() => { transport.send_message(&outbound_msg).await?; }`. Add `ClipboardAck` handling in the recv branch: when received, emit `ServerEvent::ClipboardSent { chars: 0 }`. At the end of `handle_session` (after the loop or on error/cancel), clear `session_tx` back to `None`. Use a scope guard or explicit cleanup in all exit paths.
  - Notes: The `chars` value on `ClipboardSent` can be 0 since the ACK doesn't carry the original size. The UI tracks item text locally and uses the event as a "success" signal. Ensure cleanup runs even if the session loop errors — wrap the loop body or use `defer`-style cleanup before returning.

- [ ] Task 3: Remove dead_code annotations from now-used functions
  - File: `macos/core/src/protocol.rs` line 45 — remove `#[allow(dead_code)]` from `clipboard_send()`
  - File: `macos/core/src/clipboard.rs` line 16 — remove `#[allow(dead_code)]` from `get_clipboard_text()`

#### Phase 2: macOS App — Tauri Commands & State

- [ ] Task 4: Add clipboard item state and Tauri commands
  - File: `macos/app/src/commands.rs`
  - Action: Define a `ClipboardItem` struct with fields: `id: u64`, `text: String`, `preview: String`, `timestamp: u64` (epoch millis), `sent: bool`. Store items in Tauri managed state as `Arc<RwLock<Vec<ClipboardItem>>>`. Add four commands:
    1. `paste_clipboard()` — calls `clipboard::get_clipboard_text()`, creates a `ClipboardItem`, prepends to the list, enforces max 5 (remove oldest), returns the updated list.
    2. `get_clipboard_items()` — returns the current list.
    3. `send_clipboard_item(id: u64)` — finds item by id, acquires read lock on `state.session_tx`, sends `Message::clipboard_send(&item.text)` through the channel. Returns `Err` if no session or item not found.
    4. `remove_clipboard_item(id: u64)` — removes item by id from the list.
  - Notes: Preview is the first 80 chars of text, single-lined (replace newlines with spaces, trim). Use `SystemTime::now()` for timestamp. The `id` can be a monotonic counter or timestamp-based (like Android's `System.nanoTime()`).

- [ ] Task 5: Register new commands and clipboard state in Tauri setup
  - File: `macos/app/src/main.rs`
  - Action: Add `Arc<RwLock<Vec<commands::ClipboardItem>>>` to `app.manage()` in setup. Register all four new commands in `invoke_handler!`. Increase panel inner_size from `(320.0, 420.0)` to `(320.0, 560.0)`.
  - Notes: Import `commands::ClipboardItem` if needed. The managed state must be initialized as `Arc::new(RwLock::new(Vec::new()))`.

- [ ] Task 6: Handle `ClipboardSent` event to mark items as sent
  - File: `macos/app/src/commands.rs` (or inline in the event forwarder in `main.rs`)
  - Action: The `ClipboardSent` event is already forwarded via the generic event forwarder in `main.rs:93-99` (broadcasts all `ServerEvent` variants). No Rust-side change needed for forwarding. However, we need a way to mark the item as `sent: true`. Two options:
    - **Option A** (simpler): After `send_clipboard_item` pushes the message to the channel, immediately mark the item as `sent: true` optimistically. If the channel send fails, return error.
    - **Option B**: Wait for the `ClipboardSent` event from core, then update the item.
  - Decision: Use **Option A** — mark as sent immediately when the channel accepts the message. The channel send is local and fast; if it succeeds, the message will be delivered to the session loop. The UI can show "Sent" right away. If the session dies before actually transmitting, the worst case is a false "Sent" badge, which is acceptable for this scope.

#### Phase 3: macOS App — Panel UI

- [ ] Task 7: Add clipboard section to panel HTML
  - File: `macos/app/ui/index.html`
  - Action: Add a new `<section class="clipboard-section">` between the connection section and the devices section. Contents:
    - `<div class="section-label">Clipboard</div>`
    - `<div class="clipboard-header">` containing a "Paste" button (`id="pasteBtn"`) with clipboard icon/label and item count badge (`id="clipCount"`, e.g., "2/5")
    - `<div class="clipboard-list" id="clipboardList">` — container for clipboard item cards
    - Empty state: `<div class="empty-state">No clipboard items</div>`
  - Notes: Mirror Android's structure: button at top, scrollable list below. The clipboard section should be visible regardless of connection state (user can paste items before connecting). The "Send" button on each item should be disabled when not connected.

- [ ] Task 8: Add clipboard interaction logic to panel JS
  - File: `macos/app/ui/main.js`
  - Action: Add the following:
    1. DOM references for new elements (`pasteBtn`, `clipboardList`, `clipCount`).
    2. `loadClipboardItems()` — calls `invoke("get_clipboard_items")`, renders the list. Each item shows: preview text, timestamp (HH:MM:SS), "Sent" badge (if sent), "Send" button (green if connected, gray+disabled if not), "Delete" button (red on hover).
    3. `pasteBtn` click handler — calls `invoke("paste_clipboard")`, updates the list.
    4. Send button click handler — calls `invoke("send_clipboard_item", { id })`, updates item to show "Sent".
    5. Delete button click handler — calls `invoke("remove_clipboard_item", { id })`, re-renders list.
    6. Update existing `server-event` listener: on `DeviceConnected`, enable send buttons. On `DeviceDisconnected`, disable send buttons. On `ClipboardSent`, update item sent status (if using Option B; skip if Option A).
    7. Call `loadClipboardItems()` on initial load.
  - Notes: Timestamp formatting: extract hours/minutes/seconds from epoch millis. Use `escapeHtml()` for all text rendering (existing helper). Keep connection state in a JS variable (`let isConnected = false`) updated by events, so send button state can be toggled.

- [ ] Task 9: Style the clipboard section
  - File: `macos/app/ui/style.css`
  - Action: Add styles for:
    - `.clipboard-section` — similar to `.devices-section` (flex: 1, overflow-y: auto)
    - `.clipboard-header` — flex row, space-between, containing paste button and count badge
    - `.paste-btn` — styled like a primary action button (Catppuccin mauve/purple `#cba6f7` background, dark text, rounded corners)
    - `.clip-count` — small muted badge showing "N/5"
    - `.clipboard-list` — flex column, gap 4px (same as `.devices-list`)
    - `.clipboard-item` — card style matching `.device-item` (background `#181825`, rounded 8px, padding 10px 12px). Flex layout: left side has preview text + timestamp, right side has send + delete buttons.
    - `.clipboard-item-preview` — font-size 12px, white-space nowrap, overflow hidden, text-overflow ellipsis, max-width 180px
    - `.clipboard-item-time` — font-size 10px, color `#6c7086`, monospace
    - `.sent-badge` — small green badge, font-size 10px, color `#a6e3a1`
    - `.send-btn` — small button, green border/text when enabled (`#a6e3a1`), gray when disabled (`#45475a`)
    - `.delete-btn` — small button matching `.unpair-btn` pattern (gray, red on hover)
  - Notes: Follow existing Catppuccin Mocha color scheme throughout. Keep consistent with existing `.device-item`, `.unpair-btn` sizing and spacing.

#### Phase 4: Android — Receive Clipboard from macOS

- [ ] Task 10: Pass Context to ConnectionManager
  - File: `android/.../service/ClipboardSyncService.kt`
  - Action: Change `ConnectionManager(identityManager)` constructor call to `ConnectionManager(identityManager, applicationContext)`.
  - File: `android/.../network/ConnectionManager.kt`
  - Action: Add `private val context: android.content.Context` as second constructor parameter: `class ConnectionManager(private val identityManager: IdentityManager, private val context: Context)`. Add imports for `android.content.ClipData`, `android.content.ClipboardManager`, `android.content.Context`.
  - Notes: Use `applicationContext` (not activity context) to avoid leaks. The Context is needed only for `getSystemService()`.

- [ ] Task 11: Add `CLIPBOARD_SEND` handler to receive loop
  - File: `android/.../network/ConnectionManager.kt`
  - Action: In `startReceiving()` method, add a new case in the `when (msg.type)` block (after the existing `MessageType.ERROR` case):
    ```
    MessageType.CLIPBOARD_SEND -> {
        val text = msg.payloadText()
        Log.i(TAG, "Received clipboard from remote (${text.length} chars)")
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Universal Clipboard", text)
            clipboardManager.setPrimaryClip(clip)
            transport?.sendMessage(ProtocolMessage.clipboardAck())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard", e)
            transport?.sendMessage(ProtocolMessage.error("clipboard error: ${e.message}"))
        }
    }
    ```
  - Notes: The label "Universal Clipboard" in `ClipData.newPlainText` is a user-visible description in some Android clipboard viewers. Send ACK on success, ERROR on failure — mirrors macOS's behavior in `server.rs:99-111`.

#### Phase 5: Tests

- [ ] Task 12: Add Rust tests for outbound channel and session lifecycle
  - File: `macos/core/src/events.rs` (add `#[cfg(test)] mod tests` at bottom)
  - Action: Test that `session_tx` is `None` by default after `AppState::new()`. Test that after storing a sender, messages can be received on the corresponding receiver.
  - File: `macos/core/src/server.rs` (add `#[cfg(test)] mod tests` at bottom if not present)
  - Action: Test is limited here since `handle_session` requires a real `NoiseTransport`. Focus on verifying the `ClipboardSent` event variant serializes correctly (test via `serde_json::to_string`).

- [ ] Task 13: Add Kotlin tests for CLIPBOARD_SEND receive handler
  - File: `android/.../test/.../network/ConnectionManagerTest.kt`
  - Action: Add test using existing `FakeTransport` pattern:
    1. `"receive loop handles CLIPBOARD_SEND and sends ACK"` — Remote sends `CLIPBOARD_SEND("hello")`, verify local sends back `CLIPBOARD_ACK`. Since we can't easily mock `ClipboardManager` in unit tests (requires Context), test the protocol flow by verifying the ACK message is sent. The actual clipboard write is tested via the integration/manual test.
    2. `"receive loop handles CLIPBOARD_SEND among other message types"` — Remote sends PING then CLIPBOARD_SEND, verify both are handled correctly (PONG reply + CLIPBOARD_ACK reply).
    3. `"existing receive loop functionality unaffected"` — Verify CLIPBOARD_ACK still completes pendingAck, PING still gets PONG response.
  - Notes: The `FakeTransport` class in the test file provides piped-stream simulation. For clipboard write testing, either use Robolectric for a real Context in a follow-up, or accept that the unit test covers protocol flow only.

### Acceptance Criteria

- [ ] AC 1: Given the macOS tray panel is open, when the user clicks "Paste", then the system clipboard text is read and added as a new item in the clipboard list (most recent first), and the count badge updates (e.g., "1/5").

- [ ] AC 2: Given the clipboard list has 5 items and the user clicks "Paste", when new clipboard text is read, then the oldest item is removed and the new item is prepended (FIFO eviction, max 5).

- [ ] AC 3: Given a clipboard item exists and a device is connected, when the user clicks "Send" on that item, then a `CLIPBOARD_SEND` message containing the item's text is sent over the encrypted Noise connection to the Android device, and the item is marked as "Sent" in the UI.

- [ ] AC 4: Given no device is connected, when the user views the clipboard list, then the "Send" buttons are visually disabled (gray) and non-functional.

- [ ] AC 5: Given a clipboard item exists, when the user clicks "Delete", then the item is removed from the list and the count badge updates.

- [ ] AC 6: Given an Android device is connected and macOS sends a `CLIPBOARD_SEND` message, when the Android device receives it, then the text payload is silently written to the Android system clipboard via `ClipboardManager.setPrimaryClip()` and a `CLIPBOARD_ACK` message is sent back to macOS.

- [ ] AC 7: Given the macOS app is restarted, when the panel opens, then the clipboard list is empty (items are ephemeral, not persisted).

- [ ] AC 8: Given a device disconnects while clipboard items exist, when the device reconnects later, then the "Send" buttons become enabled again and items can still be sent.

- [ ] AC 9: Given the Android receive loop handles a `CLIPBOARD_SEND`, when the clipboard write fails (exception), then an `ERROR` message is sent back instead of `CLIPBOARD_ACK`, and the receive loop continues running (does not disconnect).

- [ ] AC 10: Given the system clipboard is empty on macOS, when the user clicks "Paste", then no item is added (or an appropriate empty-state message is shown).

- [ ] AC 11: Given `cargo test` is run in the `macos/` directory, then all existing tests pass and new tests for `ClipboardSent` event serialization pass.

- [ ] AC 12: Given `gradle app:testDebugUnitTest` is run in `android/`, then all existing tests pass and new tests for `CLIPBOARD_SEND` receive handling pass.

- [ ] AC 13: Given `cargo fmt --all` and `cargo clippy --workspace -- -D warnings` are run in `macos/`, then no formatting issues or warnings are reported.

## Additional Context

### Dependencies

- No new crate dependencies on macOS — `tokio::sync::mpsc` is part of tokio "full" features (already in Cargo.toml), `arboard` already present
- No new Gradle dependencies on Android — `ClipboardManager`, `ClipData` are Android SDK built-ins
- No AndroidManifest.xml changes needed — no clipboard permissions required on API 26+
- This feature depends on an active Noise-encrypted session (existing pairing/reconnection flow)

### Testing Strategy

**Unit Tests (automated):**
- Rust: `AppState` session_tx lifecycle, `ClipboardSent` event serialization
- Kotlin: `CLIPBOARD_SEND` receive → `CLIPBOARD_ACK` response flow using `FakeTransport`
- Kotlin: Verify existing receive loop behavior (ACK, PING/PONG) is unaffected

**Manual End-to-End Test:**
1. Launch macOS app, pair with Android device
2. Copy text to macOS clipboard
3. Click "Paste" in tray panel — verify item appears
4. Click "Send" — verify Android receives text in system clipboard
5. Verify "Sent" badge appears on macOS
6. Test with 5+ items to verify FIFO eviction
7. Disconnect Android, verify send buttons disable
8. Reconnect, verify send buttons re-enable
9. Test with empty clipboard (Paste should no-op or show message)
10. Test with unicode/emoji content

**Linting:**
- `cargo fmt --all` and `cargo clippy --workspace -- -D warnings` must pass clean
- No new warnings introduced

### Notes

- `Message::clipboard_send()` in `protocol.rs:46` currently has `#[allow(dead_code)]` — Task 3 removes it
- `clipboard::get_clipboard_text()` in `clipboard.rs:17` currently has `#[allow(dead_code)]` — Task 3 removes it
- The `ClipboardSent` server event is new — added in Task 1, forwarded automatically by the existing generic event broadcaster in `main.rs:93-99`
- Android's `ConnectionManager` constructor changes from 1 param to 2 params — tests need to provide a Context (or use Robolectric). For initial unit tests, the protocol-level flow is tested without mocking clipboard; clipboard write correctness is covered by manual E2E testing
- Future consideration: if both platforms send clipboard simultaneously, both will write to each other's clipboards. This is acceptable for manual-trigger mode (unlikely race). If auto-sync is added later, deduplication via content hashing would be needed
