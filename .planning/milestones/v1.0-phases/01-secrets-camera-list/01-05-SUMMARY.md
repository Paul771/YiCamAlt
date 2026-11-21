---
phase: 01-secrets-camera-list
plan: 05
subsystem: network, debug
tags: [retrofit, okhttp, base-url, region, probe, redaction, auth-refresh]
requires:
  - phase: 01-secrets-camera-list
    provides: LogRedactor (01-01), YiRequestSigner (01-04), repo guard (01-03)
provides:
  - per-request base URL resolution for all Retrofit APIs (placeholder host yi-api.invalid)
  - official Yi app headers (YiAppHeaders, Yi Home 6.9.8 / 454) on device/deviceshare/tnp, not login
  - at most one auth_token call per session expiry
  - HttpClientModule.probeClient() (no auth, no authenticator, no redirects)
  - ProbeMatrix (pure) + EndpointProbe runner + debug-only Settings button
affects: [01-06, 01-08, 01-09]
tech-stack:
  added: []
  patterns:
    - "Retrofit on placeholder host + interceptor rewrite from ConfigModule per request"
    - "Value-free probe lines: status/code/len/count/shape/field names only"
key-files:
  created:
    - app/src/main/java/com/yicamalt/network/YiAppHeaders.kt
    - app/src/main/java/com/yicamalt/debug/ProbeMatrix.kt
    - app/src/main/java/com/yicamalt/debug/EndpointProbe.kt
    - app/src/test/java/com/yicamalt/debug/ProbeMatrixTest.kt
  modified:
    - app/src/main/java/com/yicamalt/network/HttpClientModule.kt
    - app/src/main/java/com/yicamalt/auth/AuthRepository.kt
    - app/src/test/java/com/yicamalt/network/HttpClientModuleTest.kt
    - app/src/test/java/com/yicamalt/auth/AuthRepositoryTest.kt
    - app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/yicamalt/ui/settings/SettingsScreen.kt
    - docs/verification-plan.xml
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
  deleted:
    - app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt
key-decisions:
  - "After a failed refresh, getAccessToken() returns the stale token (no network) instead of null; the memo is keyed on session expiresAt so a new login re-arms exactly one attempt"
  - "ProbeMatrix codes are emitted only if they match -?[0-9]{1,6}; anything else prints '?'"
  - "ProbeResult gained a defaulted nestedKinds field (object vs string-json) to render <field>=string-json(...)"
requirements-completed: [CAM-01, CAM-02]
duration: ~1h (split across a session-limit interruption)
completed: 2026-10-11
---

# Phase 01 Plan 05: Per-request base URL + endpoint probe matrix Summary

**Retrofit now sits on a placeholder host and the real gw-us/gw-eu/gw-sg base URL is resolved from ConfigModule on every request, with official Yi Home 6.9.8 headers. A debug-only, value-free probe covers 4 list endpoints x 3 regions plus one tnp device_info call, on a client that cannot refresh the token.**

## Performance
- Tasks: 3/3
- Files: 4 created, 9 modified, 1 deleted

## Accomplishments
- Fixed the stale Retrofit problem: one `PingApi` instance follows a base-URL switch (A to B) without a rebuild (scenario_8). A base path prefix is preserved, and an invalid URL raises `IOException CONFIG_BAD_BASE_URL`.
- Official app headers go on device paths only. `/v4/users/…` (login) carries none of the `x-kamihome-*`/`x-xiaoyi-*` headers.
- Bounded token refresh: an expired session triggers exactly one `POST /v4/users/auth_token` (memo in AuthRepository, scenario_12 + 2 AuthRepositoryTest cases). The authenticator's `refreshGuard` is now released in `finally`.
- `probeClient()`: no Authorization header and `Authenticator.NONE`, so the 401 never reaches a refresh (scenario_13).
- ProbeMatrix: pure analyzer. Keys that look like identifiers are counted (`redactedKeys`) and never printed. `deviceshare/friends` is never used as a device source. All 12 tests pass RedactionScanner with the synthetic secrets.
- EndpointProbe: at most 13 GETs, 300 ms apart, no retries, no auth calls. It does nothing in release builds or with an expired session, and it logs/returns only `ProbeMatrix.format()` lines plus a `BLOCK_PROBE_SUMMARY` line.

## Task Commits
1. Task 1, per-request base URL + headers + refresh bound + probeClient: `0ec7ee5`
2. Task 2, ProbeMatrix + tests: `a8db263`
3. Task 3, EndpointProbe, Settings button, DeviceSignProbe deleted, docs sync: `59cce25`

## Verification
- Wave-1 gate (before edits): BUILD SUCCESSFUL, tests=145 skipped=1 failures=0 errors=0
- After Task 1: BUILD SUCCESSFUL, full suite tests=153 failures=0
- Final, `:app:testDebugUnitTest :app:assembleDebug`: `BUILD SUCCESSFUL in 49s`, `--- gradle exit=0`, tests=165 skipped=1 failures=0 errors=0; `app/build/outputs/apk/debug/app-debug.apk` present
- HttpClientModuleTest 15/15, AuthRepositoryTest 14/14, ProbeMatrixTest 12/12

## Deviations from Plan
1. **[Rule 3] YiRequestSigner.kt comment edited (file not in plan scope).** Its CHANGE_SUMMARY mentioned `DeviceSignProbe`, which broke the criterion "`grep -rn DeviceSignProbe app/src` returns nothing". Reworded it to "debug sign-probe". The change is comment-only.
2. **[Rule 1] getAccessToken() after a failed refresh returns the stale token instead of null**, so the first and later calls behave the same (the plan specified this only for the memo branch).
3. **refreshSession() also goes through the memo**, so no refresh path escapes the bound.
4. **ProbeResult has an extra defaulted field `nestedKinds`.** It is needed to print `string-json(...)` versus `object(...)`.
5. **V-M-DEBUG-PROBE log markers corrected** to `[Probe][run][BLOCK_PROBE_MATRIX]` / `[Probe][run][BLOCK_PROBE_SUMMARY]`. ProbeMatrix is pure, so `BLOCK_ANALYZE_PROBE_RESPONSE` is a code anchor, not a log marker.
6. **Task 2 commit subject is `test+feat(01-05): ...`** rather than a single conventional type. It was not amended.

## Known Stubs
None. Scenario-3 of V-M-DEBUG-PROBE (no-session line) is checked on the device in plan 01-06. EndpointProbe has no JVM test because it needs BuildConfig plus live gateways.

## Self-Check: PASSED
- Files: YiAppHeaders.kt, ProbeMatrix.kt, ProbeMatrixTest.kt, EndpointProbe.kt present; DeviceSignProbe.kt absent
- Commits 0ec7ee5, a8db263, 59cce25 present in git log
