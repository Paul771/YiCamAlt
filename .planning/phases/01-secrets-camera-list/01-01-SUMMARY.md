---
phase: 01-secrets-camera-list
plan: 01
subsystem: network / test-infrastructure
tags: [security, redaction, logging, SEC-01, grace]
requires: ["01-00"]
provides:
  - "LogRedactor.maskQuery / maskJsonBody (shared HTTP-layer masking)"
  - "RedactionFixtures (synthetic SYNTH secrets for every redaction test)"
  - "RedactionScanner v0.2.0 + assertCleanQuiet"
  - "RealLogScanTest: -PredactionLogFile=<abs path> device-log sweep"
affects: ["01-02", "01-05", "01-06", "01-07"]
tech-stack:
  added: []
  patterns:
    - "Allowlist (shape-only) JSON body masking: only `code`, booleans and null survive"
    - "HttpLoggingInterceptor at BASIC level (no header values)"
key-files:
  created:
    - app/src/main/java/com/yicamalt/network/LogRedactor.kt
    - app/src/test/java/com/yicamalt/network/LogRedactorTest.kt
    - app/src/test/java/com/yicamalt/test/RedactionFixtures.kt
    - app/src/test/java/com/yicamalt/test/RedactionScannerTest.kt
    - app/src/test/java/com/yicamalt/test/RealLogScanTest.kt
  modified:
    - app/src/main/java/com/yicamalt/network/HttpClientModule.kt
    - app/src/test/java/com/yicamalt/network/HttpClientModuleTest.kt
    - app/src/test/java/com/yicamalt/test/RedactionScanner.kt
    - app/build.gradle.kts
decisions:
  - "Response bodies are masked by allowlist, not by secret-key denylist: real device-list field names are unknown, so any unknown key must still be safe"
  - "HTTP logger level HEADERS -> BASIC: header values never logged; redactHeader kept as defence in depth"
  - "maskQuery matches keys with a no-word-char lookbehind instead of only [?&], so `password=` at line start is still masked and lookalikes like `xuid=` are not"
  - "Non-JSON or truncated (peekBody 4096) bodies log as `<non-json len=N>`, never raw"
metrics:
  duration: "~25 min"
  completed: 2026-10-10
  tasks: 2
  files: 9
---

# Phase 01 Plan 01: SEC-01 Log Redaction Hardening Summary

Shape-only allowlist JSON masking and hmac/userid/uid query masking via a new `LogRedactor`, HTTP logger dropped to BASIC, and a scanner that now detects DID/hmac/token/InitString/License/nonce/serial shapes on synthetic fixtures, plus an opt-in quiet sweep for pulled device logs.

## Tasks

| Task | Name | Commit | Files |
| ---- | ---- | ------ | ----- |
| 1 | Synthetic fixtures + extended RedactionScanner + device-log sweep | 8c2fa9e | RedactionFixtures.kt, RedactionScanner.kt, RedactionScannerTest.kt, RealLogScanTest.kt, app/build.gradle.kts |
| 2 | LogRedactor + rewired HttpClientModule loggers | 1bde519 | LogRedactor.kt, LogRedactorTest.kt, HttpClientModule.kt, HttpClientModuleTest.kt |

## Verification

- Task 1: `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.*"` -> `BUILD SUCCESSFUL`, `--- gradle exit=0`;
  RedactionScannerTest tests=22 failures=0; TestInfrastructureSmokeTest tests=7 failures=0; RealLogScanTest skipped (no property).
- Device-log sweep proven: a scratch log with `[BLOCK_STORE_TOKEN] token=<SYNTH token>` run with `-PredactionLogFile=...`
  fails (gradle exit=1, failures="1", 2 hits listed as pattern + index) and the token text appears 0 times in console output and the test XML.
- Task 2: `--tests "com.yicamalt.network.*" --tests "com.yicamalt.test.*"` -> `BUILD SUCCESSFUL`, `--- gradle exit=0`;
  HttpClientModuleTest tests=9 failures=0; LogRedactorTest tests=11 failures=0; YiRequestSignerTest tests=6 failures=0.
- Informational full `:app:testDebugUnitTest` (shared tree, includes other wave-1 executors' uncommitted work): exit=0, 15 suites, 0 failures. The formal wave gate remains in plan 01-05.
- Acceptance greps: SYNTH count 12; no `167315`; `fun assertCleanQuiet` 1; `redaction.logFile` 1; `Level.BASIC` 1, `Level.HEADERS` 0; `LogRedactor.` 4; old `(password=)[` regex 0; `START_BLOCK_MASK_JSON_BODY` 1; `START_BLOCK_REDACTED_LOG` 1.

## Deviations from Plan

1. **[Rule 2 - Correctness] maskQuery key anchor.** Plan regex anchored keys on `[?&]`; implemented as `(?<![A-Za-z0-9_])` so that a `password=` / `hmac=` at the start of a line or after a space is still masked (the old v0.3.2 regex had no anchor and masked those), while `xuid=` style lookalikes are untouched. Keys are tried longest-first so `token_secret` wins over `token`. Covered by `maskQuery masks token and token_secret but not lookalike keys`.
2. **[Rule 1 - Bug avoidance] Non-JSON detection.** `Json { isLenient = true }` would accept a bare unquoted literal like `<html>oops</html>` as a JSON primitive; `maskJsonBody` therefore only accepts a top-level object/array (same check used for encoded-`data` strings) and otherwise returns `<non-json len=N>`.
3. **Colliding `<key>` names** (several identifier-like keys in one object) are disambiguated as `<key2>`, `<key3>` instead of silently overwriting.
4. **Gradle race:** the first Task 1 run failed in `hiltJavaCompileDebugUnitTest` on a half-written generated `Hilt_YiCamAltApp.java` (parallel executor sharing the build dir); re-run passed with no code change. One later run used `--rerun-tasks` to regenerate test XMLs that a concurrent run had wiped.

## Deferred leaks

None found: with scanner v0.2.0 the full unit suite (including CameraRegistry, CameraCommand, YiDatabase, Auth, Event, Login tests) is green.

## Known Stubs

None.

## Notes for later plans

- `peekBody(4096L)` is unchanged: a device-list body over 4 KB will parse-fail and log as `<non-json len=4096>` (safe, but no shape). Plan 01-06 may want a larger peek for the probe.
- docs/*.xml (knowledge-graph M-HTTP export LogRedactor, verification-plan V-M-HTTP test file LogRedactorTest) are synced centrally in plan 01-06 per this plan's verification note.

## Self-Check: PASSED
