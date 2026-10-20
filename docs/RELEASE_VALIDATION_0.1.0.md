# 0.1.0 Device Validation

Date: 2026-10-20. Device: Xiaomi 24129PN74C, Android 17 / API 37, HyperOS. UI captures embedded in the README were taken from this phone with `adb exec-out screencap`.

## Completed Checks

- `:app:testDebugUnitTest` and `:app:lintDebug` pass. Debug, unsigned release, fixture, and Android instrumentation APKs build with JDK 25 / SDK 37.
- The Root backend diagnostic instrumentation passed all 19 MCP tool probes on this phone before the final reinstall. It created, exercised, and cleaned up its temporary virtual display.
- The real HTTP MCP server was called through the ADB forward. With no running display, `observe(mode=tree)` returned `NO_RUNNING_APP`, `application_running=false`, 66 launchable applications, and the expected recovery tools.
- A pending control request was opened with the same content `PendingIntent` used by its notification. The app showed the corresponding live request. The phone accessibility tree placed `拒绝`, `允许一次`, and `始终允许` at the same vertical coordinate; `始终允许` stayed on one line.
- The permission/backend and MCP settings screens were captured from the physical phone and are the README examples.

## Authorization-Limited Checks

The final reinstall cleared the app's Root preference. Although `su` is available to the ADB shell, KernelSU did not grant Root to the installed app during this run. Shizuku was not authorized. The full Root `DeviceBackendTest` therefore stopped at its authorization precondition; its Root screenshot, input, Unicode, and takeover checks did not run. Its Shizuku tests were also unavailable. This is an environment precondition, not a passing device test.

Consequently, this pass does not claim the alternating manual-touch regression, authorized accessibility-node reading, or the floating End action has passed on the reinstalled build. Rerun `DeviceBackendTest` and `scripts/verify-mcp.py` after authorizing Root or Shizuku. The app does not modify KernelSU or Shizuku grants.

## Build Artifacts

The release workflow checks that `app/build.gradle.kts` says `0.1.0`, the pushed tag is `v0.1.0`, and `docs/RELEASE_NOTES_0.1.0.md` exists. GitHub Actions signs the release APK with repository Secrets, validates the signature, generates `SHA256SUMS`, and publishes both files.
