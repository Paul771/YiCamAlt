---
phase: 01-secrets-camera-list
plan: 09
subsystem: camera
tags: [camera-list, p2p-credentials, tnp, room, sec-04, cam-01, cam-03, cam-05]

requires:
  - phase: 01-07
    provides: DeviceRecordParser / DeviceRecord / TnpDeviceInfo, DevicePaths, signedList(@Url), tnpDeviceInfo
  - phase: 01-08
    provides: region kept by login discovery (RegionSelector), real default gateway
  - phase: 01-12
    provides: TransportResolver, CameraPasswordCipher, P2pCredentialStore (+ Hilt binding), CameraDao.replaceAll, Room row v2
provides:
  - DeviceSourcePlan (single edit point for the device-list source order)
  - CameraRegistry v0.5.0 - getCameraList, cachedCameras, getCredentials, clearAll
  - CameraError.ServerRejected
  - CameraListPort.cachedCameras
  - logout wipes camera cache + P2P credential store
affects: [01-10 camera list UI, 01-11 on-device verification, phase-02 live view (getCredentials)]

tech-stack:
  added: []
  patterns:
    - "Distinct refresh outcomes: empty list (no cameras, clears cache + store) vs Unauthorized / ServerRejected / FetchFailed (leave cache + store untouched)"
    - "TNP credentials reused per DID; tnp device_info failure never fails the list"

key-files:
  created:
    - app/src/main/java/com/yicamalt/camera/DeviceSourcePlan.kt
    - app/src/test/java/com/yicamalt/camera/CameraRegistryCredentialsTest.kt
  modified:
    - app/src/main/java/com/yicamalt/camera/CameraRegistry.kt
    - app/src/main/java/com/yicamalt/camera/CameraInfo.kt
    - app/src/main/java/com/yicamalt/MainActivity.kt
    - app/src/test/java/com/yicamalt/camera/CameraRegistryModuleTest.kt
    - app/src/test/java/com/yicamalt/ui/cameras/CameraListViewModelTest.kt
    - docs/knowledge-graph.xml
    - docs/development-plan.xml
    - docs/verification-plan.xml

key-decisions:
  - "DeviceSourcePlan.OWNED_PATHS = [/v5/devices/list]; no shared path; no /v4 fallback (addendum)"
  - "No region fallback inside the registry: tokens are per region (CROSS_REGION_SESSION: no); the region is the one kept by login discovery (01-08)"
  - "A non-JSON body on the list counts as FetchFailed instead of escaping as SerializationException"
  - "TNP license reuse compares the stored DID with the list did; a row without did (parser fell back to uid) always reuses"

requirements-completed: [CAM-01, CAM-03, CAM-05, SEC-04]

duration: 30min
completed: 2026-10-11
---

# Phase 01 Plan 09: Camera registry on the probed source with P2P credentials Summary

**CameraRegistry now reads `/v5/devices/list` on the login region, treats 20200 as "no cameras", stores transport + P2P credentials (decrypted password, TNP DID/InitString/License from `/v4/tnp/device_info`) only in the encrypted store, writes Room atomically, and wipes both on logout.**

## Final DeviceSourcePlan values

| Key (01-PROBE-RESULTS.md) | Value | Effect in code |
|---|---|---|
| BRANCH | A | - |
| OWNED_PATH | /v5/devices/list | `OWNED_PATHS = listOf(DevicePaths.LIST_V5)` |
| SHARED_PATH | none | no shared-list call; shared cameras are rows of the same list (`category`/`share`) |
| DATA_REGION / LOGIN_REGION | US / US | the registry uses the current base URL, which login discovery set |
| CROSS_REGION_SESSION | no | no region fallback in the registry |

Branch-C caveat: not applicable (BRANCH A, LOGIN_REGION = DATA_REGION).

## Request bounds per user refresh (as built)

- 1 signed `GET /v5/devices/list`.
- Plus at most 1 signed `GET /v4/tnp/device_info` per TNP camera, and only when no license is stored for the same DID. On later refreshes the count is 0 (tested).
- No other region and no retry. No timer and no login call. The PIN password endpoint is never called (tested).

## Task Commits

1. **Task 1: probe-driven sources, shared merge, distinct outcomes**: `c8d0d0f`
2. **Task 2: P2P credential resolution into the encrypted store, logout cleanup**: `de27b3f`
3. **Docs sync (knowledge-graph, development-plan, verification-plan)**: `2ab33cd`

## Verification evidence

- Wave-4 gate, run before any edit: `:app:testDebugUnitTest` gave `BUILD SUCCESSFUL in 4s` and `--- gradle exit=0`. Result: 21 suites, 241 tests, 1 skipped, 0 failures, 0 errors.
- After Task 1, full suite: `BUILD SUCCESSFUL in 8s`, `--- gradle exit=0`. Result: 21 suites, 248 tests, 1 skipped, 0 failures.
- Final wave gate `:app:testDebugUnitTest :app:assembleDebug`: `BUILD SUCCESSFUL in 16s`, `--- gradle exit=0 elapsed=17.3s`. Result: 22 suites, 260 tests, 1 skipped, 0 failures, 0 errors. `app/build/outputs/apk/debug/app-debug.apk` exists (18 MB), so the Hilt graph resolves `P2pCredentialStore` for CameraRegistry.
  - CameraRegistryModuleTest: tests=20, failures=0.
  - CameraRegistryCredentialsTest: tests=12, failures=0.
- Redaction: creds_12 runs two refreshes through the production `HttpClientModule`. The scanned buffer contains the HTTP log lines; the test asserts that `/v4/tnp/device_info` appears in it. `RedactionScanner.assertClean` checks the buffer against `ALL_SECRETS`, `synthpw1`, `SYNTHLIC01`, `USER_ID`, the pinned password hex and a second DID. It is clean.
- SEC-04 grep: under `database/`, `initString|licenseKey|password` matches only the existing CameraEntity comment that says these values are not stored there.

## Deviations from Plan

### From the addendum (it overrides the plan)

1. **One owner path, no shared path, no `/v4` fallback.** `OWNED_PATHS = [/v5/devices/list]`. `SHARED_PATHS` was not created. Shared cameras arrive as rows of the same list. A `dedupeRecords` step (owned row wins over a shared duplicate) replaces `mergeRecords(owned, shared)`. The "second owned path" scenario does not apply. Instead, scenario_2b checks that 20200 produces exactly one request.
2. **No registry region fallback.** I did not create `REGION_FALLBACK_ENABLED`, `BLOCK_REGION_FALLBACK`, the `RegionSelector` constructor parameter or the two-server region test. Reasons: the addendum says "region = the one stored by login discovery"; the probe shows `CROSS_REGION_SESSION: no` (a US token gets 20202 / -10003 elsewhere); and the plan itself says to keep the switch off in that case. Code behind a switch that is always off would never run. Acceptance greps that are therefore not met: the `START_BLOCK_REGION_FALLBACK` count (2 of 3 anchors) and the `OWNED_PATHS|SHARED_PATHS|REGION_FALLBACK_ENABLED` count in DeviceSourcePlan.kt. The first commit subject reads "distinct outcomes" instead of "region fallback". The commit was amended once before anything else was built on it.

### Auto-fixed

3. **[Rule 1 - Bug] A non-JSON list body escaped as a raw `SerializationException`.** The first IO test exposed it. A gateway error page or a captive portal would have crashed the caller with an exception that is not a CameraError. It now counts as FetchFailed (`result=malformed`) and leaves the cache untouched. Test: scenario_5d. The same catch is in the tnp call, where the result is null.
4. **[Rule 1 - Bug] The reuse rule as written would re-fetch on every refresh for rows without `did`.** The 01-07 parser puts the cloud uid into `p2pId` when `did` is absent, so `record.p2pId.isNullOrBlank()` is never true. The code compares `listDid = record.p2pId` with the uid fallback removed. Behaviour is as the plan intends: creds_7 shows 1 request and then 0. The literal grep `isNullOrBlank() || record.p2pId == known.p2pId` does not match. The equivalent line is `listDid.isNullOrBlank() || listDid == known.p2pId`.
5. **Test infrastructure:** CameraRegistryModuleTest serves list responses from a queue and answers tnp `device_info` by path. Since v0.5.0, TNP rows trigger that call, which would otherwise consume list responses. Before the change, scenario_12 timed out at 10 s.

### Other

- `streamUrl` is null after a refresh because the device list has no stream URL. scenario_1 now asserts null. scenario_6 seeds a row to keep `getLiveStreamUrl` covered.
- `YiCloudDeviceApi.deviceList()` stays on the interface (file not owned by this plan). The registry no longer calls it.
- CameraRegistryModuleTest's `FakeCameraDao` / `FakeSession` became `internal` so the credentials test can reuse them.
- The plan's TDD RED/GREEN commits were combined: tests and code went into one commit per task, matching the two commit subjects in the plan.

## Known Stubs

None. `getLiveStreamUrl` still throws `NoStreamUrl` for every refreshed camera, because the cloud list has no stream URL. Live view goes through P2P (Phase 2, `getCredentials`).

## Open points

- If a real list `did` differs from the TNP DID returned by `device_info`, the license is fetched again on every refresh: 1 call per TNP camera per refresh, still within the bound. Plan 01-11 on the device can show which case applies. The log line `tnpInfo=reused` vs `ok` tells them apart.
- Production OkHttp keeps its default `retryOnConnectionFailure` (transparent reconnect inside one call). That is HttpClientModule configuration and outside this plan.

## Self-Check: PASSED

- Files exist: DeviceSourcePlan.kt, CameraRegistryCredentialsTest.kt, CameraRegistry.kt, MainActivity.kt.
- Commits exist: c8d0d0f, de27b3f, 2ab33cd.
