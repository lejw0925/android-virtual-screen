# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Virtual Screen provides an AI agent with an isolated Android display. It runs an authenticated MCP server directly on the phone, eliminating the need for separate desktop phone-control servers. Input is confined to the virtual display, never falling back to the main screen. The app uses Root or Shizuku for privileged display operations and offers optional tunneling through frpc or Cloudflared.

## Architecture

### Module Organization

Kotlin sources are under `app/src/main/kotlin/io/github/lejw0925/virtualscreen/`:

- `backend/` — Privileged display operations via Root or Shizuku. Manages virtual display creation (`PrivilegedDisplayEngine`), input injection (`RootInputInjector`), accessibility node capture, and the live display channel for real-time frame streaming.
- `core/` — Session lifecycle, state management, and security. `VirtualScreenSessionManager` serializes all backend calls and fences operations during takeover. `ControlLeaseManager` enforces exclusive five-minute leases. `AppRestartApprovalManager` handles cross-display app restart confirmations. `BearerTokenStore` and `CredentialVault` manage authentication.
- `mcp/` — MCP protocol implementation. `McpServerController` manages the Ktor HTTP server lifecycle. `McpToolRegistry` defines the 10 MCP tools with multilingual schemas and handles tool execution. `RequestReceipts` stores operation results queryable by request ID.
- `tunnel/` — Network tunneling. `TunnelManager` spawns frpc/cloudflared native binaries. `AndroidDnsBridge` bridges Go DNS to Android's system resolver, required because the bundled executables use native Go DNS rather than Android's.
- `ui/` — Jetpack Compose interface using Material 3. `MainActivity` hosts the home screen and MCP access card. `ViewerActivity` provides full-screen preview with local touch input. `SettingsPages` and `TunnelSettings` manage configuration. `LiveDisplayView` renders the real-time display feed.

Top-level files:
- `VirtualScreenApplication` manages runtime scope, MCP startup/shutdown, and foreground service lifecycle.
- `VirtualScreenForegroundService` keeps MCP alive in the background with a persistent notification.

### Key Behavioral Patterns

- **Exclusive control leases**: Only one client controls the display at a time. Local phone touch immediately revokes remote leases. Remote clients must request takeover through approval notifications.
- **Observation consumption**: Each input consumes one observation ID. Stale observations, expired leases, and non-unique selectors are rejected to prevent race conditions.
- **Cross-display app restart approvals**: Launching an app already running on another display requires explicit user confirmation via system notification. Approval waits up to two minutes; the MCP client timeout must be at least 150 seconds.
- **Session auto-cleanup**: Uninitialized sessions expire after 1 minute; initialized sessions after 5 minutes of inactivity. Active leases protect sessions from cleanup.
- **Backend fencing**: `VirtualScreenSessionManager.fence()` increments the observation epoch and cancels in-flight operations whenever control changes hands.

### MCP Tool Design

The 10 tools expose precise, composable operations:

1. `control` — Acquire or release exclusive leases; lease-authenticated operations renew automatically
2. `request_control` — Request takeover through phone notification (returns pending immediately; poll for approval)
3. `service_status` — Query backend capabilities, display state, and controller; fetch receipts by request ID
4. `screen` — Create, reconfigure, or close the virtual display
5. `applications` — List launcher apps or launch one on the virtual display
6. `observe` — Capture accessibility tree, screenshot, or both; `mode=tree` avoids image capture; `format=compact` returns only visible useful nodes
7. `pointer` — Tap, long press, swipe, or scroll using display coordinates
8. `input_text` — Insert Unicode into the focused editor; `replace=true` replaces all text
9. `node_action` — Act on a specific node by ID or unique selector (click, long click, focus, scroll, set_text)
10. `wait` — Wait for conditions: delay, frame change, node text/presence/absence, app foreground, or UI stability (500ms unchanged)

All tools requiring control include `lease_token`. Input tools consume `observation_id` and support `observe_after` to return a fresh observation in the same response. Tool descriptions and error messages are multilingual (English, Simplified Chinese, Traditional Chinese), following the phone's system language.

## Build, Test, and Development Commands

### Environment Setup

Requires **JDK 25** and **Android SDK 37** (API 37). Configure the SDK path in `local.properties`:

```sh
sdk.dir=/absolute/path/to/Android/sdk
```

or set the `ANDROID_HOME` environment variable.

Published SDK packages are `platforms;android-37.0` and `build-tools;37.0.0`.

### Build Commands

```sh
# Build debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build unsigned release APK
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk

# Build signed release (requires AVS_* environment variables)
export AVS_KEYSTORE=/path/to/release.jks
export AVS_KEY_ALIAS=your-alias
export AVS_STORE_PASSWORD=store-password
export AVS_KEY_PASSWORD=key-password
./gradlew :app:assembleRelease

# Install debug APK on connected device
./gradlew :app:installDebug
```

### Testing Commands

```sh
# Run JVM unit tests (includes Robolectric)
./gradlew :app:testDebugUnitTest

# Run Android Lint
./gradlew :app:lintDebug

# Build instrumentation test APKs
./gradlew :device-fixture:assembleDebug :app:assembleDebugAndroidTest

# Install and run device tests (replace IP:PORT with device address or use `-d` for USB)
adb -s IP:PORT install -r device-fixture/build/outputs/apk/debug/device-fixture-debug.apk
adb -s IP:PORT install -r app/build/outputs/apk/debug/app-debug.apk
adb -s IP:PORT install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s IP:PORT shell am instrument -w -e class io.github.lejw0925.virtualscreen.DeviceBackendTest io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner

# Run all checks (standard pre-commit workflow)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Test files are in `app/src/test/kotlin/` (JVM tests) and `app/src/androidTest/kotlin/` (instrumentation tests). Use JUnit 4, Robolectric for Android framework simulation, coroutine test utilities (`runTest`, `TestScope`), and Ktor test hosting for MCP integration tests.

### Native Tunnel Builds

The APK bundles `frpc` 0.65.0 and `cloudflared` 2025.8.1 as `libfrpc.so` and `libcloudflared.so`. Rebuilding requires **Android NDK 28.2.13676358** and **Go 1.25.6**:

```sh
export ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358
./scripts/build-tunnels.sh
```

This builds for `arm64-v8a`, `armeabi-v7a`, and `x86_64` with 16 KiB ELF LOAD alignment (Android 16+ requirement). Source archives are downloaded and SHA-256 verified. The script updates `app/src/main/assets/native-build.json` with build metadata.

**Important**: These files are ELF executables, not JNI libraries. Do not strip, rename, or remove them during packaging. Gradle is configured with `useLegacyPackaging = true` and `keepDebugSymbols` to preserve them.

## Coding Conventions

- **Style**: Official Kotlin conventions with four-space indentation. No automated formatter is configured; follow existing file style.
- **Naming**: PascalCase for classes, files, and composables; camelCase for functions and properties; snake_case for Android resources.
- **Package alignment**: Keep classes in packages matching their responsibility. Backend privilege code stays in `backend/`, core state in `core/`, MCP protocol in `mcp/`, UI in `ui/`.
- **Localization**: Update all three string resource files (`values/`, `values-zh-rCN/`, `values-zh-rTW/`) for user-facing text.
- **Comments**: Write comments where behavior is non-obvious. Explain why, not what. Document cross-display restart approval logic, lease expiration rules, and observation epoch fencing.

## Testing Guidelines

- **Naming**: Test classes are `<Feature>Test`. Test methods use descriptive camelCase names describing the behavior under test.
- **Scope**: Cover changed lease acquisition, observation consumption, cancellation, authentication, cross-display approval, session cleanup, and MCP tool validation.
- **JVM tests**: Use Robolectric for Android framework components. Use `runTest` and `TestScope` for coroutine-based logic. Mock `VirtualScreenBackend` when testing `VirtualScreenSessionManager`.
- **Device tests**: Use AndroidJUnit4 and the `device-fixture` app for real device validation. Follow `docs/VALIDATION.md` and `docs/BUILD.md` for setup.
- **No numeric coverage threshold**: Prioritize meaningful coverage of state transitions, error paths, and concurrent access patterns.

## Key Files and Entry Points

- `VirtualScreenApplication.kt` — Application lifecycle, MCP startup/restore logic
- `core/VirtualScreenSessionManager.kt` — Central coordinator for display sessions, leases, observations
- `core/ControlLeaseManager.kt` — Exclusive lease enforcement
- `core/AppRestartApprovalManager.kt` — Cross-display app restart confirmations
- `mcp/McpToolRegistry.kt` — MCP tool schemas and execution
- `mcp/McpServerController.kt` — Ktor HTTP server lifecycle
- `backend/PrivilegedDisplayEngine.kt` — Virtual display creation via Root/Shizuku
- `backend/RootInputInjector.kt` — Touch and key injection
- `ui/MainActivity.kt` — Home screen and MCP access card
- `ui/ViewerActivity.kt` — Full-screen preview with local touch

## Documentation

Detailed documentation is in `docs/`:

- `BUILD.md` — Environment, native builds, release signing, GitHub Actions
- `MCP.md` — MCP client configuration, tool parameters, observations, receipts
- `TUNNELS.md` — frpc and Cloudflared setup, network requirements
- `VALIDATION.md` — Device test setup and validation procedures
- `RELEASE_VALIDATION.md` — Physical device validation, benchmark data, tested devices
- `TOOL_RESEARCH.md` — Design decisions and comparisons with other MCP implementations

Most documentation is in Chinese; commands and JSON schemas are language-agnostic.

## Security and Configuration

- **Credentials**: Never commit release keystores, passwords, or Bearer tokens. Use `AVS_*` environment variables for release signing. Tokens are stored encrypted via `CredentialVault`.
- **Input isolation**: All input injection is display-scoped. Failed operations never fall back to the main display.
- **Cross-display restart approval**: Explicit user confirmation required for apps running on other displays.
- **Native binary preservation**: Keep `libfrpc.so` and `libcloudflared.so` intact; they are executables, not JNI libraries.
- **Third-party notices**: Preserve attribution in `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/licenses.json`. Native provenance is in `app/src/main/assets/native-build.json`.
