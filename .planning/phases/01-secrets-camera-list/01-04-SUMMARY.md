---
phase: 01-secrets-camera-list
plan: 04
subsystem: network
tags: [hmac, signing, M-HTTP, SEC-01, CAM-03]
requires: ["01-00"]
provides: ["YiRequestSigner (signingKey, canonicalMessage with extra params, hmacSha1Base64, sign)"]
affects: [M-CAMERA-LIST, M-EVENT, "01-05 probe", "01-09 credential fetch"]
tech-stack:
  added: []
  patterns: ["single pure-function signer taking plain strings (M-HTTP free of M-AUTH)"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/network/YiRequestSigner.kt
    - app/src/test/java/com/yicamalt/network/YiRequestSignerTest.kt
  modified:
    - app/src/main/java/com/yicamalt/camera/CameraRegistry.kt
    - app/src/main/java/com/yicamalt/event/EventServiceModule.kt
    - app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt
decisions:
  - "YiRequestSigner takes token/tokenSecret/userId strings, not AuthSession, so M-HTTP does not depend on M-AUTH"
  - "extra params are appended in caller order after seq/userid (seq=1&userid=<uid>&uid=<cloud uid>)"
metrics:
  duration: ~15min
  completed: 2026-10-10
  tasks: 2
  files: 5
---

# Phase 1 Plan 04: Single YiRequestSigner Summary

One HMAC-SHA1 signer (`network/YiRequestSigner`) now covers the device list and the event API, and it accepts extra canonical params for `/v4/tnp/device_info`. `EventSigner` and the inline `CameraRegistry` copy are gone, and the stream-URL log no longer prints the device id.

## Tasks

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | YiRequestSigner with pinned vectors | c08a3ea | network/YiRequestSigner.kt, network/YiRequestSignerTest.kt |
| 2 | Switch CameraRegistry + EventServiceModule; delete EventSigner; drop device id from log | 05dcd39 | camera/CameraRegistry.kt, event/EventServiceModule.kt, event/YiCloudEventApi.kt |

## Verification

Scoped run: `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.network.YiRequestSignerTest" --tests "com.yicamalt.camera.CameraRegistryModuleTest" --tests "com.yicamalt.event.*"`. It printed `BUILD SUCCESSFUL in 31s` and `--- gradle exit=0`.
- YiRequestSignerTest: tests=6 failures=0 errors=0 (includes the public vector `3nybhbi3iqa8ino29wqQcBydtNk=`)
- CameraRegistryModuleTest: tests=13 failures=0 errors=0 (scenario_1c was not changed, so the wire format is the same)
- EventServiceModuleTest: tests=23 failures=0 errors=0
- `Mac.getInstance("HmacSHA1")` in app/src/main appears only in network/YiRequestSigner.kt and debug/DeviceSignProbe.kt. Plan 01-05 deletes DeviceSignProbe.kt.
- `device=$deviceId` no longer appears in CameraRegistry.kt. `/v5/alert/events` is still in YiCloudEventApi.kt.

## Deviations from Plan

1. **Acceptance grep `grep -rn "EventSigner" app/src` is not empty.** It matches 4 comment lines and no code. Two of them are the CHANGE_SUMMARY texts the plan itself requires ("EventSigner removed"). One is the YiRequestSigner v0.1.0 summary that the plan also specifies. The fourth is the historical v0.1.0 CHANGE_SUMMARY entry in YiCloudEventApi.kt, which I kept because history entries are not rewritten. No code symbol `EventSigner` remains, and compilation proves that.
2. **TDD RED/GREEN were not committed separately.** Test and implementation went into one commit (c08a3ea). Another plan's in-flight `DiagnosticLogTest.kt` broke test compilation during the first run, so a RED run could not be observed in isolation. The later scoped run was green.
3. CameraRegistry's header said `VERSION: 0.1.0` even though its CHANGE_SUMMARY already had v0.2.0. Following the plan, I set it to 0.3.0.

## Known Stubs

None.

## Self-Check: PASSED
- FOUND: app/src/main/java/com/yicamalt/network/YiRequestSigner.kt
- FOUND: app/src/test/java/com/yicamalt/network/YiRequestSignerTest.kt
- FOUND: c08a3ea, 05dcd39
