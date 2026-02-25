---
project_name: 'universal_clipboard'
user_name: 'hn'
date: '2026-02-23'
sections_completed: ['technology_stack', 'language_rules', 'framework_rules', 'testing_rules', 'code_quality', 'workflow_rules', 'critical_rules']
status: 'complete'
rule_count: 38
optimized_for_llm: true
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

### macOS (Rust)
- Cargo workspace: `core/` (lib), `cli/` (binary: `uclip`), `app/` (binary: `uclip-app`)
- Rust edition 2021, stable toolchain
- snow 0.9 (Noise Protocol), tokio 1.x (full), Tauri 2.x, arboard 3.x, mdns-sd 0.11
- Crypto: hkdf 0.12, sha2 0.10, rand 0.8, base64 0.22
- Error handling: anyhow 1.x, thiserror 1.x
- Logging: tracing 0.1 / tracing-subscriber 0.3

### Android (Kotlin)
- Kotlin 1.9.22, AGP 8.2.2, JDK 17
- Jetpack Compose via BOM 2024.02.02, Material3
- compileSdk/targetSdk 34, minSdk 26
- Vendored noise-java (commit 49377b6dfc) with XXpsk0 PSK patches
- Coroutines 1.7.3, JSON via org.json 20231013
- NSD (mDNS) via built-in android.net.nsd

### Dev Environment
- Nix flakes + direnv for reproducible dev shell
- Provides: Rust stable, Android SDK 34, JDK 17, Gradle

## Critical Implementation Rules

### Rust Rules
- Use `anyhow::Result<T>` for all fallible functions — no custom Error enums
- Use `bail!("msg")` for early returns, `.context("description")?` for error wrapping
- Flat module structure: all modules in `src/` root, bare `pub mod` in `lib.rs`
- Async: tokio with `CancellationToken` for graceful shutdown, `tokio::select!` for multiplexing
- Logging: `tracing` crate macros (`info!`, `debug!`, `warn!`, `error!`)
- Binary data serialized as hex strings in JSON storage
- rustfmt: edition 2021, max_width 100

### Kotlin Rules
- `object` for stateless utilities, `data class` for value types
- Manager/Service suffix pattern for stateful components
- Compose: ViewModel with `StateFlow`, collect via `collectAsStateWithLifecycle()`
- Backtick test method names for readability
- Package structure mirrors domain: `crypto/`, `network/`, `data/`, `service/`, `ui/`

### Framework-Specific Rules

#### Noise Protocol (Cross-Platform)
- Pairing: `Noise_XXpsk0_25519_ChaChaPoly_SHA256` — PSK from 6-digit code via HKDF-SHA256
- Reconnection: `Noise_KK_25519_ChaChaPoly_SHA256` — both keys already known
- Wire handshake type: `0x00` = pairing, `0x01` = paired
- Message frame: `[type: 1B][length: 4B big-endian][payload: NB]`
- Rust uses `snow` crate builder pattern; Android uses vendored `noise-java`

#### Tauri v2 (macOS Menu Bar App)
- Tray icon app, no main window — uses `tray-icon` and `image-png` features
- Plugins: positioner, autostart
- Bridge async core logic via `tauri::async_runtime::spawn`

#### Jetpack Compose (Android)
- Single-activity with `UniversalClipboardTheme` wrapper
- Private composables for sub-components within screen files
- ViewModel + StateFlow pattern for state management

### Testing Rules
- Rust: inline tests at bottom of source files (`#[cfg(test)] mod tests`), use `tempfile::TempDir` for filesystem tests
- Kotlin: mirrored package structure, `{ClassName}Test.kt` naming, backtick method names
- Cross-platform test vectors MUST match — PSK derivation for `"123456"` → `"2ae98c1b..."` verified on both platforms
- Every new class, data class, utility, or behavioral change requires corresponding unit tests
- Run `cargo test` (Rust) and `gradle app:testDebugUnitTest` (Android) before completing any task
- Android: `unitTests.isReturnDefaultValues = true` — default Android API returns work in tests

### Code Quality & Style Rules
- Rust: run `cargo fmt --all` and `cargo clippy --workspace -- -D warnings` before every commit — fix all issues
- Rust formatting: edition 2021, max_width 100 (per `rustfmt.toml`)
- Rust naming: `snake_case` files/functions, `PascalCase` types, `SCREAMING_SNAKE` constants
- Kotlin naming: `PascalCase.kt` files matching class names, `camelCase` functions
- Keep Rust modules flat in `src/` — do not introduce nested module directories without discussion
- Keep Android packages at one nesting level (`crypto/`, `network/`, `ui/`, `data/`, `service/`)

### Development Workflow Rules
- Conventional Commits: `<type>(<scope>): <description>`
- Types: feat, fix, refactor, test, docs, chore, build
- Scopes: android, macos, protocol (omit for project-wide)
- PRs always target `main`
- CI: GitHub Actions — Android build+test on push, release on tag push
- Release builds three artifacts in parallel: Android APK, macOS CLI binary, macOS Tauri .app

### Critical Don't-Miss Rules
- **NEVER** replace vendored `noise-java` with upstream — XXpsk0 PSK patches are required and not available upstream
- **NEVER** call `setPublicKey()` after `setPrivateKey()` on Android's `Curve25519DHState` — it zeros the private key; `setPrivateKey()` derives the public key automatically
- **NEVER** change message frame format without updating both platforms — wire format must be byte-identical
- Any change to crypto, handshake, or encoding requires verifying cross-platform test vectors still pass on both Rust and Kotlin
- PSK is ephemeral (derived from pairing code) — never persist it to storage
- Do not downgrade or substitute Noise cipher suites (`25519_ChaChaPoly_SHA256`)
- Rust snow builder: set `local_private_key` and `psk(0, &psk)` — snow derives public key internally (same principle as Android)
- DeviceStore persists keys as hex-encoded strings — maintain this convention for any new key storage

---

## Usage Guidelines

**For AI Agents:**
- Read this file before implementing any code
- Follow ALL rules exactly as documented
- When in doubt, prefer the more restrictive option
- Update this file if new patterns emerge

**For Humans:**
- Keep this file lean and focused on agent needs
- Update when technology stack changes
- Review periodically for outdated rules
- Remove rules that become obvious over time

Last Updated: 2026-02-23
