# Repository Guidelines

## Project Structure & Module Organization

- `app/` is the Android application. Kotlin sources under `app/src/main/kotlin/io/github/lejw0925/virtualscreen/` are grouped into `backend/`, `core/`, `mcp/`, `tunnel/`, and Compose `ui/`.
- `app/src/main/res/` contains resources and English, Simplified Chinese, and Traditional Chinese strings. `assets/` holds licenses and native provenance; `jniLibs/` contains bundled tunnel executables.
- `app/src/test/` holds JVM tests; `app/src/androidTest/` holds device tests; `app/src/debug/` contains debug-only diagnostics.
- `device-fixture/` provides a disposable test application. `scripts/` contains native build and MCP verification tools; `docs/` documents setup and validation; `dist/` contains delivery APKs and checksums.

## Build, Test, and Development Commands

Use JDK 25 and Android SDK 37; the minimum device version is Android 13. Configure the SDK in `local.properties` or `ANDROID_HOME`.

- `./gradlew :app:assembleDebug` — build `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:installDebug` — install on a connected device.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` — run JVM tests and Android Lint.
- `./gradlew :device-fixture:assembleDebug :app:assembleDebugAndroidTest` — build the fixture and instrumentation APKs.
- `./gradlew :app:assembleRelease` — build release; unsigned unless signing is configured.
- `./scripts/build-tunnels.sh` — rebuild bundled frpc/cloudflared; requires NDK 28.2.13676358 and Go 1.25.6. See `docs/BUILD.md`.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use PascalCase for classes/files and composables, camelCase for functions/properties, and snake_case for Android resources. Keep packages aligned with their responsibilities. Update all three locales for user-facing text. Android Lint is configured; no dedicated formatter is configured.

## Testing Guidelines

JVM tests use JUnit 4, Robolectric, coroutine test utilities, and Ktor test hosting. Device tests use AndroidJUnit4. Name classes `<Feature>Test` and methods with descriptive camelCase behavior names. No numeric coverage threshold is configured. Cover changed lease, observation, cancellation, authentication, and display behavior. Follow `docs/BUILD.md` and `docs/VALIDATION.md` for device authorization, fixture installation, and instrumentation/MCP checks; record device/backend details and skips.

## Commit & Pull Request Guidelines

Git history is unavailable in this checkout. Use focused, imperative subjects, for example `Fix stale observation rejection`. PRs should explain behavior changes, link relevant issues, list validation and tested devices/backends, and include screenshots for UI changes.

## Security & Configuration

Keep credentials and keystores out of commits; use `AVS_*` environment variables for release signing. Preserve virtual-display-only input, authentication, leases, and phone-side restart approval. Do not rename or strip bundled `libfrpc.so`/`libcloudflared.so`; retain provenance and third-party notices.
