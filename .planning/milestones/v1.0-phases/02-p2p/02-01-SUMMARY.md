---
phase: 02-p2p
plan: 01
subsystem: p2p-credentials
tags: [p2p, tnp, tutk, transport, device_info, grace]
requires:
  - "02-00: P2pCredentialsSource port (com.yicamalt.stream.CameraLink.kt)"
provides:
  - "TransportResolver.decide(deviceInfoDid, cloudUid) + isTnpDid (vendor rule, 02-RESEARCH.md Pattern 5)"
  - "P2pCredentials.transportVerified (persisted; v0.1.0 records decode as false)"
  - "CameraRegistry: /v4/tnp/device_info for every unverified camera; implements P2pCredentialsSource"
  - "Hilt binding CameraBindsModule.bindP2pCredentialsSource"
  - "DeviceRecordParser.parseTnpDid"
affects: [02-06, 02-07, 02-10]
tech-stack:
  added: []
  patterns: ["sealed lookup outcome (Answer / Rejected / Failed) instead of nullable result", "verified-once reuse, never a guess on network failure"]
key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/camera/P2pCredentials.kt
    - app/src/main/java/com/yicamalt/camera/P2pCredentialStore.kt
    - app/src/main/java/com/yicamalt/camera/CameraRegistry.kt
    - app/src/main/java/com/yicamalt/camera/CameraModule.kt
    - app/src/main/java/com/yicamalt/camera/DeviceRecordParser.kt
    - app/src/test/java/com/yicamalt/camera/P2pCredentialsTest.kt
    - app/src/test/java/com/yicamalt/camera/P2pCredentialStoreTest.kt
    - app/src/test/java/com/yicamalt/camera/CameraRegistryCredentialsTest.kt
    - app/src/test/java/com/yicamalt/camera/CameraRegistryModuleTest.kt
    - app/src/test/java/com/yicamalt/camera/DeviceListFixtures.kt
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "Transport follows the vendor rule only: TNP-shaped device_info DID -> TNP dialled with that DID; otherwise the cloud uid is the P2P id and its case decides (lower -> LANGTAO, else TUTK). List type/did are hints in logs only."
  - "A rejected device_info code counts as an answer (uid rule, verified); a TNP DID with missing InitString/License is 'incomplete' (TNP, unverified, asked again)."
  - "A device_info network failure keeps only a VERIFIED stored record; otherwise the camera becomes UNKNOWN / via=none / unverified, so a Phase 1 'TUTK by type' guess cannot reach GATE_2B."
metrics:
  duration: 25min
  completed: 2026-10-13
  tasks: 2
  files: 13
---

# Phase 2 Plan 01: Vendor transport rule and device_info for every camera Summary

TransportResolver now uses the vendor rule from 02-RESEARCH.md Pattern 5: the device_info DID shape decides, and without a TNP DID the case of the cloud uid decides. CameraRegistry asks `/v4/tnp/device_info` once for every camera that has no verified record and reuses verified records afterwards. A network failure never produces a guessed transport. The registry also implements the `P2pCredentialsSource` port through a Hilt binding.

## Tasks

| Task | Name | Commits |
| ---- | ---- | ------- |
| 1 | Vendor rule in TransportResolver + transportVerified persisted | b93924d (RED), 8c82f96 (GREEN) |
| 2 | Registry asks device_info for every unverified camera; P2pCredentialsSource binding | 827d67f (RED), 29fa3cc (GREEN) |

## Log markers (value-free)

- `[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] transport=<T> via=<deviceInfo|uid|none> listType=<n|-> tnpInfo=<ok|incomplete|notTnp|rejected|failed|reused> hasPassword=<b> needsPincode=<b>`
- `[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] stored=<n> withLicense=<n> withPassword=<n> verified=<n>`
- `[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] tnpCode=<code> result=rejected` (per rejected device_info answer, kept from v0.6.0)
- `[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=<n> shared=<n> tnp=<n> tutk=<n> langtao=<n> unknown=<n>`

`mismatch=` no longer appears anywhere. Plan 02-06 should read `via=` and `tnpInfo=` from the device log for both cameras. A camera that was `TUTK` by list type and now reports `via=deviceInfo tnpInfo=ok` is TNP, so the TUTK adapter (02-10) would not be needed for it.

## Request bound

At most one `GET /v4/tnp/device_info` per camera per user-triggered refresh, and only while that camera's stored record is not `transportVerified`. No retries and no timers. In steady state there are zero extra calls. Records written in Phase 1 have no `transportVerified` key, decode as unverified, and are each asked exactly once. Incomplete answers and network failures stay unverified, so the camera is asked again on the next user refresh. No new endpoints were added, and the PIN, orderpay and push_prop endpoints are not called.

## Decision table (per camera, in BLOCK_RESOLVE_P2P_CREDENTIALS)

| Stored record | device_info outcome | Result | verified | via / tnpInfo |
| --- | --- | --- | --- | --- |
| verified (and TNP has license) | not asked | stored values | true | deviceInfo or uid / reused |
| otherwise | success, complete TNP DID | TNP, DID, InitString, license | true | deviceInfo / ok |
| otherwise | success, TNP DID, no InitString/License | TNP, DID, no secrets (stored verified record kept if any) | false | deviceInfo / incomplete |
| otherwise | success, non-TNP or no DID | uid rule, p2pId = uid | true | uid / notTnp |
| otherwise | non-success code | uid rule, p2pId = uid | true | uid / rejected |
| verified | network/HTTP/parse failure | stored values | true | of stored / failed |
| none or unverified | network/HTTP/parse failure | UNKNOWN, p2pId = uid | false | none / failed |

## Verification

- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.camera.*" :app:compileDebugKotlin`: BUILD SUCCESSFUL, `--- gradle exit=0`. Camera suites: P2pCredentialsTest 11/11, P2pCredentialStoreTest 10/10, CameraRegistryCredentialsTest 23/23, CameraRegistryModuleTest 22/22, DeviceRecordParserTest 14/14, CameraPasswordCipherTest 7/7, CameraCommandModuleTest 10/10, all with `failures="0"`.
- `hiltJavaCompileDebug` ran in the same build, so the Hilt graph accepts the new binding.
- Acceptance greps: no `server.requestCount` assertions remain in CameraRegistryModuleTest. The only match is a CHANGE_SUMMARY comment that names what was replaced. No `mismatch` or `LegacyResolution` in camera main sources, no `TransportResolver.resolve(`, and BLOCK_RESOLVE_P2P_CREDENTIALS appears 7 times.
- creds_12 runs a full refresh twice through the production HttpClientModule. The log buffer passes RedactionScanner with `ALL_SECRETS + synthpw1, SYNTHLIC01, USER_ID, PINNED_HEX, DID_2`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - GRACE protocol] Synced GRACE docs for changed exports and log markers**
- **Found during:** Task 2
- **Issue:** The `docs/*.xml` entries for TransportResolver and the P2pCredentials type, the `mismatch=` device marker and the V-M-CAMERA-LIST / V-M-P2P-CREDS scenarios still described the Phase 1 heuristic. AGENTS.md requires the graph and the verification plan to change together with exports and markers.
- **Fix:** Updated the TransportResolver export and the P2pCredentials type in development-plan.xml and knowledge-graph.xml. Added `CrossLink M-CAMERA-LIST -> M-STREAM-CORE`. Updated the device markers and scenarios in verification-plan.xml. All three files parse as XML.
- **Commit:** 29fa3cc

**2. [Rule 3 - Acceptance conflict] CHANGE_SUMMARY wording**
- **Issue:** The plan's CHANGE_SUMMARY text says "mismatch removed", but the acceptance grep requires no `mismatch` in camera main sources.
- **Fix:** Reworded it to "type-vs-id disagreement flag removed".

### Notes

- TDD: each task has a RED commit with compiling `TODO()` stubs (tests failed at runtime only) and a GREEN commit with the plan's fixed message.
- `creds_4c` covers "incomplete answer keeps a stored verified record". The only verified record that still gets asked is a TNP record without a license, so the test seeds exactly that.
- Requirements LIVE-01 and LIVE-02 are NOT marked complete. They close only after device verification (plan 02-06).

## Known Stubs

None.

## Self-Check: PASSED
