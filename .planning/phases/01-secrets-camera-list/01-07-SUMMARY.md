---
phase: 01-secrets-camera-list
plan: 07
subsystem: camera
tags: [parser, device-list, tnp, retrofit, kotlinx-serialization]
requires:
  - phase: 01-06
    provides: probe results (BRANCH A), field names of the device list and tnp device_info
provides:
  - DeviceRecord and TnpDeviceInfo (value-free toString)
  - DeviceRecordParser.parse / parseTnpInfo
  - DevicePaths, YiCloudDeviceApi.signedList(@Url), YiCloudDeviceApi.tnpDeviceInfo
affects: [01-09, 01-11, 01-12]
tech-stack:
  added: []
  patterns: [candidate-key lists with measured names first, JSON-encoded string decoded once]
key-files:
  created:
    - app/src/main/java/com/yicamalt/camera/DeviceRecordParser.kt
    - app/src/test/java/com/yicamalt/camera/DeviceListFixtures.kt
    - app/src/test/java/com/yicamalt/camera/DeviceRecordParserTest.kt
  modified:
    - app/src/main/java/com/yicamalt/camera/CameraInfo.kt
    - app/src/main/java/com/yicamalt/camera/YiCloudDeviceApi.kt
key-decisions:
  - "DevicePaths has no share path: shared cameras arrive inside /v5/devices/list (addendum)"
  - "p2pId = did, falling back to uid when did is absent or blank (addendum)"
  - "p2p_encrypt is true when ipcParam or the key is absent (vendor default); an explicit value is read strictly"
  - "A row is shared when category is non-zero; when category is absent, when share is true; or when the caller passes shared = true"
requirements-completed: []
duration: 20min
completed: 2026-10-11
---

# Phase 01 Plan 07: Device-list and tnp device_info parsers Summary

**Shape-tolerant `DeviceRecordParser` for `/v5/devices/list` and `/v4/tnp/device_info`, with `DevicePaths`, `signedList(@Url)` and `tnpDeviceInfo` on the Retrofit interface, pinned by 14 synthetic-fixture tests.**

## Result

- Task 1, commit `42e8844`: `grace(M-CAMERA-LIST): tolerant device-list and tnp device_info parsers, DevicePaths`
- Verification command: `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.camera.DeviceRecordParserTest" --tests "com.yicamalt.camera.CameraRegistryModuleTest"`
  - `BUILD SUCCESSFUL in 4s`, `--- gradle exit=0`
  - `DeviceRecordParserTest`: tests=14, failures=0, errors=0, skipped=0
  - `CameraRegistryModuleTest`: tests=13, failures=0, errors=0, skipped=0
- The full suite was not run (wave rule: the gate runs at the start of plan 01-09).
- CAM-03 and CAM-01 are not marked complete: this plan supplies parsers and the API surface only; the network path is plan 01-09 and credentials are plan 01-12.

## What the parser does

- `parse(data, shared)`: data may be a bare array, an object wrapping an array (`deviceList`, `device_list`, `list`, `devices`, `items`, `data`), a JSON-encoded string of either, or absent. Absent data (code 20200) gives an empty list. A row with no id key is dropped. Unknown fields are ignored.
- `ipcParam` is read as an object or as a JSON-encoded string.
- `type` is read from a number or a numeric string; anything else gives null. `type` is no longer a model key in either parser.
- `parseTnpInfo(data)`: keys matched case-insensitively; `licenseKey` is the text before the first colon of `License`; null when DID, InitString or the license key is missing or blank; data as a JSON-encoded string works.
- `DeviceRecord.toString()` and `TnpDeviceInfo.toString()` print flags only.

## Final candidate-key lists

First match wins. Names marked (probe) were measured on the device on 2026-10-11 (names only); (static) come from the vendor app analysis; the rest are research candidates never seen on the wire.

| Field | Keys, in order |
|---|---|
| cloud uid | `uid` (probe), `device_id`, `deviceId`, `did` (probe), `sn`, `id` |
| p2p id | `did` (probe), `p2pid`, `p2p_id`, `p2pId`, `DID`, `tnp_did`; none present: the cloud uid |
| type | `type` (probe), `p2p_type`, `p2pType` |
| password (hex, still encrypted) | `password` (probe), `pwd`, `view_password`, `viewPassword` |
| name | `name` (probe), `device_name`, `nick_name`, `nickname`, `alias` |
| model | `model` (probe), `device_model`, `model_name`, `dev_type` |
| online | `online` (probe), `is_online`, `online_status` |
| pincode flag | `hasPincode` (probe), `has_pincode` |
| ipc container | `ipcParam` (probe), `ipc_param` |
| inside ipc | `p2p_encrypt` (probe), `wakeup` (static) |
| ownership | `category` (probe; 0 = own, static), then `share` (probe) |
| tnp info | `DID`, `InitString`, `License` (all probe), plus `did`, `init_string`, `license_key`; case-insensitive |

`CameraListParser.ID_KEYS` is now `uid, device_id, deviceId, did, sn, id`, so the cache key is the cloud uid.

## Deviations from Plan

### From the addendum (it overrides the plan)

1. **No share paths in `DevicePaths`.** It holds `LIST_V5`, `LIST_V4` and `TNP_DEVICE_INFO`. `SHARE_FRIENDS` and `SHARE_DETAIL` were not added. The acceptance check "at least 5 `const val`" therefore reads 3; a test asserts that no constant contains `deviceshare`. `LIST_V4` is kept as a named constant only and is documented as never a default.
2. **Ownership is read from the row** (`category`, then `share`), in addition to the `shared` argument of the plan.
3. **`p2pId` falls back to the cloud uid** when `did` is absent or blank.
4. **Code 20200** is covered by a test: absent or null data gives an empty list.

### Auto-fixed

5. **[Rule 1 - Bug] `p2p_encrypt` defaults to true when absent.** The plan said every non-true value is false. The vendor app treats a missing `p2p_encrypt` as true (01-STATIC-FINDINGS.md section 4). Defaulting to false would make a later P2P session skip encryption for a camera that expects it. An explicit value is still read strictly (`true`, `"true"`, `1`, `"1"` are true; anything else false). `wakeup` and `hasPincode` default to false.

### Process

6. **No separate failing-test commit.** Other executors compile the same test source set at the same time, so a commit with tests against a missing class would have broken their builds. Tests and code went into one commit, as the plan's success criteria name a single commit. The tests passed on their first run; a failing run was never observed.
7. **One Gradle run failed to compile** in `app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt` (a file of another executor, mid-edit). It was retried without touching that file and passed on the next attempt.
8. **STATE.md, ROADMAP.md and REQUIREMENTS.md were not updated**, as instructed for the parallel wave. `docs/*.xml` were not edited; the plan assigns that sync to plan 01-11.

## Open point for plan 01-12

The addendum maps `p2pid = did`. The static analysis says the vendor app uses `uid` as UID, DID and P2PID, and `did` only as the display DID. Both values are kept on the record (`cloudUid`, `p2pId`). The TNP connection should take its DID from `/v4/tnp/device_info` (`TnpDeviceInfo.did`), which is the measured source, and not from `DeviceRecord.p2pId`.

## Known Stubs

None.

## Self-Check: PASSED

- All five files exist and are in commit `42e8844`.
- `MODEL_KEYS` line contains no `"type"`; no `v8/cloud/deviceList` text remains in the two edited files; `"/v4/tnp/device_info"` appears once in `YiCloudDeviceApi.kt`; no payment, alert, password or deviceshare path in `YiCloudDeviceApi.kt`; `substringBefore(':')` is present in `DeviceRecordParser.kt`.
- Not met by design: "at least 5 `const val`" (3, see deviation 1).
