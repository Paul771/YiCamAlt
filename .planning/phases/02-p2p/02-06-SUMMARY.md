---
phase: 02-p2p
plan: 06
subsystem: device-check
tags: [p2p, device, native, jni, r8, decoders, gate]
requires: ["02-01", "02-02", "02-03", "02-04", "02-05"]
provides:
  - "02-DEVICE-CHECK-1.md: value-free device evidence (CRITERION_1_RUNTIME, HEVC/AVC decoders, TRANSPORTS, GATE_2B, RESULT)"
  - "GATE_2B: needed -> plan 02-10 (TUTK) is required"
  - "Release R8 build unblocked (Tink Error Prone dontwarn); shim class names kept"
affects: [02-07, 02-08, 02-09, 02-10, 02-11]
tech-stack:
  added: []
  patterns: ["device evidence from value-free log markers + redaction sweep", "machine-readable gate line with python self-check"]
key-files:
  created:
    - .planning/phases/02-p2p/02-DEVICE-CHECK-1.md
  modified:
    - app/proguard-rules.pro
decisions:
  - "Evidence taken from log markers only: the user replied 'готово' without p2p:/cams: lines; cross-check is log-internal"
  - "GATE_2B: needed — camera B is TUTK (via=uid, tnpInfo=notTnp), camera A is TNP (via=deviceInfo); verified=2 of 2"
  - "Tink's com.google.errorprone.annotations.* handled with -dontwarn (compile-time-only annotations), not keep"
metrics:
  duration: 1d (incl. user device step)
  completed: 2026-10-15
  tasks: 3
  files: 2
---

# Phase 2 Plan 06: Device check 1 (native load, API version, transports) Summary

On the user's arm64 phone the vendor PPPP library loads with our JNI shim. `PPPP_Initialize` returns 0 and the API version is 0xA2050401, which is the value we expected. The phone has hardware AVC and HEVC decoders (OMX.qcom). Under the corrected transport rule the real account resolves to one TNP camera and one TUTK camera, and the device_info answer verifies both. That makes the TUTK plan (02-10) necessary: `GATE_2B: needed`.

## Tasks

| Task | Name | Commit | Result |
| ---- | ---- | ------ | ------ |
| 1 | Wave-1 gate, R8 shim check, debug APK | c4f3dd0 | 397 tests green, assembleDebug OK, APK lib/dex check OK, mapping.txt 2 lines, phone attached |
| 2 | User installs APK, refreshes list, runs P2P check | (human step) | User replied "готово" (APK installed 2026-10-15 19:05) |
| 3 | Pull + sweep log, record evidence and 2B gate | 2179027 | Sweep clean, CRITERION_1_RUNTIME PASS, GATE_2B needed, RESULT PASS |

## Evidence (from 02-DEVICE-CHECK-1.md)

- `CRITERION_1_RUNTIME: PASS` (pppp=ok, init=0, api=0xA2050401 = expected)
- `HEVC_DECODER: OMX.qcom.video.decoder.hevc(hw)`, `AVC_DECODER: OMX.qcom.video.decoder.avc(hw)`
- `TRANSPORTS: tnp=1 tutk=1 langtao=0 unknown=0`, verified=2 of devices=2
- `GATE_2B: needed`, `RESULT: PASS`, `GATE_RULE_OK`
- RealLogScanTest sweep (`--rerun-tasks`): tests=1 skipped=0 failures=0; the pulled log was deleted afterwards

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Release R8 failed on missing Error Prone annotation classes**
- **Found during:** Task 1 (step 3)
- **Issue:** `:app:minifyReleaseWithR8` failed with `Missing class com.google.errorprone.annotations.*`. Tink, which comes in through `androidx.security:security-crypto` (added in M-SCAFFOLD), references those classes. The failure had nothing to do with our keep rules, but it blocked the shim keep check.
- **Fix:** Added `-dontwarn` rules from AGP's `missing_rules.txt` to `app/proguard-rules.pro`. The annotations are compile-time only. After the fix, mapping.txt keeps `com.p2p.pppp_api.PPPP_APIs` and `com.tnp.model.st_PPPP_Session`.
- **Files modified:** app/proguard-rules.pro
- **Commit:** c4f3dd0

### Other notes

- **User reply without status lines:** the user sent only "готово" and no `p2p:`/`cams:` lines, so the on-screen camera labels are missing. We used the log markers as evidence and cross-checked them against each other: BLOCK_NATIVE_CHECK against BLOCK_INIT_PPPP, and the BLOCK_RESOLVE_P2P_CREDENTIALS summary against BLOCK_FETCH_CAMERA_LIST. The evidence file says this too.
- **`BLOCK_QUERY_DECODERS` marker missing:** SettingsViewModel calls `DecoderCatalog.find()` directly and never calls `summary()`, so this marker is never written. BLOCK_NATIVE_CHECK carries the same decoder facts. We left it as is (out of scope). Wire it in when the session layer picks the decoder.

## Known Stubs

None.

## Next

- Plan 02-10 (TUTK) is needed for camera B. Langtao is not involved.
- LIVE-01..03 stay open until live view is proven (later plans).
## Self-Check: PASSED
