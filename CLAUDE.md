# Universal Clipboard

P2P encrypted clipboard sync between Android and macOS using the Noise Protocol Framework.

## Project Structure

- `android/` — Android app (Kotlin + Compose, Gradle build)
- `macos/` — macOS receiver (Rust)
- `protocol/` — Protocol documentation
- `flake.nix` — Nix dev environment (Rust, JDK17, Android SDK, Gradle)

## Development Environment

Uses Nix flakes. Enter the dev shell with `nix develop` or use direnv.

## Build Commands

### Android
```
cd android && gradle assembleDebug
```
APK output: `android/app/build/outputs/apk/debug/app-debug.apk`

### macOS Receiver
```
cd macos && cargo build
cargo run -- listen
```

## Test Commands

### Android (unit tests)
```
cd android && gradle app:testDebugUnitTest
```

### macOS
```
cd macos && cargo test
```

## Commit Messages & PR Titles

Use [Conventional Commits](https://www.conventionalcommits.org/) format for both commit messages and PR titles:
```
<type>(<scope>): <description>
```
Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`
Scopes: `android`, `macos`, `protocol`, or omit for project-wide changes.

PRs should always target `main` as the base branch.

## Important: After Every Task

After implementing any feature, bug fix, or code change:
1. **Add tests** for all new logic — every new class, data class, utility function, or behavioral change must have corresponding unit tests.
2. Run the relevant test suite and verify all tests pass before considering the task complete.
3. **Rust (macOS):** Run `cd macos && cargo fmt` and `cd macos && cargo clippy -- -D warnings` and fix any issues before committing.

## Key Architecture Notes

### Noise Protocol (vendored)
The `noise-java` library (from `rweather/noise-java`, commit `49377b6dfc`) is vendored at:
`android/app/src/main/java/com/southernstorm/noise/`

It has been patched to support `XXpsk0` (PSK token-based patterns). Key patched files:
- `Pattern.java` — added `PSK` token constant and `XXpsk0` pattern
- `HandshakeState.java` — added `case Pattern.PSK` handling in `writeMessage`/`readMessage`

Do NOT replace this with an upstream dependency — the PSK patches are required.

### Handshake Patterns
- **Pairing** (first connection): `Noise_XXpsk0_25519_ChaChaPoly_SHA256` — PSK derived from 6-digit code
- **Reconnection** (paired devices): `Noise_KK_25519_ChaChaPoly_SHA256` — both keys already known

### Curve25519 Key Setup
When setting keys on `Curve25519DHState`, only call `setPrivateKey()`. Do NOT call `setPublicKey()` after `setPrivateKey()` — it zeros the private key. `setPrivateKey()` already derives the public key internally.
