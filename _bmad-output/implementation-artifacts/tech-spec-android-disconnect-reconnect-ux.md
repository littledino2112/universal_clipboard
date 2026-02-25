---
title: 'Android Disconnect UX During Reconnection'
slug: 'android-disconnect-reconnect-ux'
created: '2026-02-23'
status: 'completed'
stepsCompleted: [1, 2, 3, 4]
tech_stack: ['Kotlin 1.9.22', 'Jetpack Compose (BOM 2024.02.02)', 'Coroutines 1.7.3', 'Material3']
files_to_modify: ['ConnectionManager.kt', 'ClipboardSyncService.kt', 'MainViewModel.kt', 'MainScreen.kt']
code_patterns: ['sealed class state machine', 'MutableStateFlow → StateFlow', 'collectAsStateWithLifecycle', 'when expression UI branching', 'autoReconnectEnabled flag guard']
test_patterns: ['mirrored package structure', 'backtick method names', 'runTest coroutine testing', 'ConnectionManagerTest.kt exists (176 lines)']
---

# Tech-Spec: Android Disconnect UX During Reconnection

**Created:** 2026-02-23

## Overview

### Problem Statement

When the connection between the Android client and macOS receiver drops, users are stuck waiting up to 6.5 minutes (10 exponential-backoff retries, 3s→60s) with no way to cancel. The UI shows a generic "Connecting" spinner with no disconnect option — the disconnect button only appears when fully connected. Users must wait for all retries to exhaust before regaining control.

### Solution

Add a `Reconnecting` connection state distinct from `Connecting`, show the disconnect button during reconnection attempts so users can cancel immediately, and cap the retry mechanism at 3 attempts over ~10 seconds total. After retries exhaust or user disconnects, return to the full pairing screen.

### Scope

**In Scope:**
- New `Reconnecting` ConnectionState variant
- Disconnect button visible during reconnection
- Shortened retry parameters (3 attempts, ~10s total)
- UI treatment for the reconnecting state
- Return to pairing screen after disconnect or retry exhaustion

**Out of Scope:**
- macOS client changes
- Pairing flow modifications
- Keepalive interval tuning
- Notification improvements
- Any protocol-level changes

## Context for Development

### Codebase Patterns

- `ConnectionState` is a sealed class in `ConnectionManager.kt` (lines 15-20): `Disconnected | Connecting | Connected(deviceName) | Error(message)`
- State flows via `MutableStateFlow` from `ConnectionManager` → `MainViewModel` (line 83, direct copy) → `MainScreen` (when expression)
- Retry logic lives in `ClipboardSyncService.scheduleReconnect()` (lines 154-184) with exponential backoff
- `autoReconnectEnabled` flag (line 49) guards all auto-reconnect — set `true` on connect/reconnect, `false` on `userDisconnect()` or max attempts
- `userDisconnect()` (lines 94-101) cancels reconnectJob, disables flag, disconnects, stops service
- `handleDisconnect()` (lines 207-214) is the internal disconnect path — triggers state → Disconnected → scheduleReconnect
- Both `connectWithPairing()` (line 49) and `reconnect()` (line 94) set `ConnectionState.Connecting` — no distinction today

### Files to Reference

| File | Purpose |
| ---- | ------- |
| `network/ConnectionManager.kt` (215 lines) | ConnectionState sealed class, state flow, reconnect() method |
| `service/ClipboardSyncService.kt` (233 lines) | Retry constants, scheduleReconnect() loop, userDisconnect() |
| `ui/MainViewModel.kt` (226 lines) | State bridge, disconnect() action |
| `ui/screens/MainScreen.kt` (402 lines) | ConnectionSection when-block, disconnect button rendering |
| `network/ConnectionManagerTest.kt` (176 lines) | Existing tests for ACK/receive mechanics |

All paths under: `android/app/src/main/java/com/example/universalclipboard/`
Tests under: `android/app/src/test/java/com/example/universalclipboard/`

### Technical Decisions

- Add `Reconnecting(deviceName: String)` as a data class variant — carries device name so UI can show "Reconnecting to {device}..."
- `reconnect()` in ConnectionManager gets an `isReconnecting: Boolean = false` parameter; when true, emits `Reconnecting(deviceName)` instead of `Connecting`
- `scheduleReconnect()` in ClipboardSyncService passes `isReconnecting = true` when calling `connectionManager.reconnect()`
- User pressing disconnect during reconnect calls existing `userDisconnect()` path — already handles cleanup correctly
- After max attempts exhausted, set state to `Disconnected` so UI falls through to pairing screen
- Retry params: fixed 3s interval (no exponential backoff), 3 max attempts

## Implementation Plan

### Tasks

- [x] Task 1: Add `Reconnecting` variant to ConnectionState sealed class
  - File: `network/ConnectionManager.kt`
  - Action: Add `data class Reconnecting(val deviceName: String) : ConnectionState()` after the `Connecting` variant (line 17)
  - Notes: Uses `data class` (not `data object`) to carry deviceName like `Connected` does

- [x] Task 2: Add `isReconnecting` parameter to `reconnect()` method
  - File: `network/ConnectionManager.kt`
  - Action: Change `reconnect()` signature (line 92) to `fun reconnect(host: String, port: Int, deviceName: String, remotePublicKey: ByteArray, isReconnecting: Boolean = false)`. On line 94, change `_state.value = ConnectionState.Connecting` to conditionally emit: `_state.value = if (isReconnecting) ConnectionState.Reconnecting(deviceName) else ConnectionState.Connecting`
  - Notes: Default `false` preserves backward compatibility for `reconnectToDevice()` in the service

- [x] Task 3: Shorten retry parameters and remove exponential backoff
  - File: `service/ClipboardSyncService.kt`
  - Action: Change constants (lines 30-32): `INITIAL_BACKOFF_MS = 3_000L` (keep), `MAX_BACKOFF_MS` (remove — no longer needed), `MAX_RECONNECT_ATTEMPTS = 3` (was 10). In `scheduleReconnect()`: remove the exponential backoff line `backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)` (line 179) — use fixed `INITIAL_BACKOFF_MS` delay each iteration. Remove the 5-second post-reconnect wait `delay(5_000L)` (line 181) — not needed with short fixed intervals.
  - Notes: 3 attempts x 3s = ~9s before giving up, plus the reconnect call time ≈ ~10s total

- [x] Task 4: Pass `isReconnecting = true` in scheduleReconnect loop
  - File: `service/ClipboardSyncService.kt`
  - Action: In `scheduleReconnect()` (line 178), change `connectionManager.reconnect(device.host, device.port, device.name, device.publicKey)` to `connectionManager.reconnect(device.host, device.port, device.name, device.publicKey, isReconnecting = true)`
  - Notes: This is what makes the ConnectionManager emit `Reconnecting` instead of `Connecting` during auto-retry

- [x] Task 5: Handle `Reconnecting` state in service observer
  - File: `service/ClipboardSyncService.kt`
  - Action: In `observeConnectionState()` (lines 116-152), add a branch for `is ConnectionState.Reconnecting` that updates the notification (similar to Connecting branch). Ensure it does NOT trigger `scheduleReconnect()` again.
  - Notes: The reconnect loop is already running — this state just needs notification handling

- [x] Task 6: Handle `Reconnecting` state in MainViewModel
  - File: `ui/MainViewModel.kt`
  - Action: No code change needed — `connectionState` is passed through directly via `_uiState.update { it.copy(connectionState = state) }` (line 83). The new state variant flows through automatically.
  - Notes: Verify this is true — the `when` on `Connected` (line 84) should still work since `Reconnecting` is a different type

- [x] Task 7: Add `Reconnecting` UI branch in MainScreen
  - File: `ui/screens/MainScreen.kt`
  - Action: In `ConnectionSection()`, add a new `is ConnectionState.Reconnecting` branch between the `Connected` and `Connecting` cases. Show: a `CircularProgressIndicator`, text "Reconnecting to {deviceName}...", and an `OutlinedButton("Disconnect")` that calls `onDisconnect`. Style similarly to the Connected section's row layout.
  - Notes: The `onDisconnect` callback already calls `viewModel.disconnect()` → `service.userDisconnect()` which cancels reconnectJob and disables auto-reconnect. The state will then become `Disconnected` and the `else` branch shows the pairing screen.

- [x] Task 8: Handle max-attempts exhaustion transition to Disconnected
  - File: `service/ClipboardSyncService.kt`
  - Action: After max reconnect attempts (line 172), explicitly set `connectionManager.disconnect()` (not just `autoReconnectEnabled = false`). This ensures the state transitions from `Reconnecting` → `Disconnected`, which triggers the pairing screen in UI.
  - Notes: Currently the state might stay as `Error` or `Reconnecting` after max attempts — need clean transition to `Disconnected`

- [x] Task 9: Write unit tests for new ConnectionState variant
  - File: `network/ConnectionManagerTest.kt` (extend existing)
  - Action: Add test: `Reconnecting state carries device name`. Add test: `reconnect with isReconnecting=true emits Reconnecting state`. Add test: `reconnect with isReconnecting=false emits Connecting state` (backward compat).
  - Notes: Follow existing backtick method naming pattern, use `runTest`

- [x] Task 10: Write unit tests for shortened retry logic
  - File: `service/ClipboardSyncServiceTest.kt` (new file)
  - Action: Test that `MAX_RECONNECT_ATTEMPTS` is 3. Test that retry uses fixed interval (no exponential backoff). Test that `userDisconnect()` cancels reconnection and disables auto-reconnect.
  - Notes: May need to mock ConnectionManager for service-level tests. Follow mirrored package structure.

### Acceptance Criteria

- [x] AC 1: Given the connection drops while connected, when auto-reconnect starts, then the UI shows "Reconnecting to {deviceName}..." with a spinner and a Disconnect button.
- [x] AC 2: Given the user is in the Reconnecting state, when they tap Disconnect, then auto-reconnect stops immediately and the UI returns to the full pairing screen.
- [x] AC 3: Given the connection drops, when 3 reconnection attempts fail over ~10 seconds, then auto-reconnect stops and the UI returns to the full pairing screen showing the Disconnected state.
- [x] AC 4: Given a first-time pairing attempt, when connecting, then the UI still shows the existing "Connecting..." spinner without a disconnect button (no regression).
- [x] AC 5: Given the user taps a paired device to reconnect manually, when connecting, then the UI shows "Connecting..." (not "Reconnecting...") since this is a user-initiated action.
- [x] AC 6: Given the connection succeeds during a reconnection attempt, when the state transitions to Connected, then the reconnect loop stops and the UI shows the normal Connected view with the disconnect button.

## Additional Context

### Dependencies

None — purely Android-side changes. No new libraries or external dependencies needed.

### Testing Strategy

**Unit Tests:**
- Extend `ConnectionManagerTest.kt` with tests for `Reconnecting` state emission and the `isReconnecting` parameter
- Create `ClipboardSyncServiceTest.kt` for retry parameter validation and `userDisconnect()` behavior

**Manual Testing:**
1. Pair Android with macOS receiver
2. Kill the macOS receiver process while connected
3. Verify Android shows "Reconnecting to {device}..." with Disconnect button
4. Verify reconnection auto-stops after ~10 seconds and shows pairing screen
5. Repeat step 2, but tap Disconnect immediately — verify instant return to pairing screen
6. Verify normal pairing flow still shows "Connecting..." without disconnect button

### Notes

- **Risk:** The `reconnect()` method runs in a `scope.launch` block — the `isReconnecting` flag is read before the coroutine starts, so timing should be safe. However, if the reconnect coroutine starts and immediately fails, the `Error` state emission (line 121) will trigger `scheduleReconnect()` again. The `reconnectJob?.isActive` check (line 140) prevents double-scheduling.
- **Known limitation:** If the network is completely down, each reconnect attempt will fail quickly (TCP connect timeout), so the 3 attempts may complete in well under 10 seconds. This is acceptable — faster failure is better UX.
- **Future consideration:** Could add a "Retry" button on the pairing screen after reconnection exhaustion, pre-filled with the last device info. Out of scope for this change.

## Review Notes
- Adversarial review completed
- Findings: 8 total, 3 fixed, 5 skipped (noise/by-design/infrastructure-gap)
- Resolution approach: auto-fix
- Fixed: settlement delay after reconnect(), off-by-one clarity, restored disconnect notification
