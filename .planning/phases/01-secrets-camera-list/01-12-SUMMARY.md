---
phase: 01-secrets-camera-list
plan: 12
subsystem: camera
tags: [p2p, tnp, tutk, aes-ecb, encrypted-shared-preferences, room, hilt, sec-04]

requires:
  - phase: 01-secrets-camera-list (plan 01-06 + probe)
    provides: "BRANCH A: owner list on /v5/devices/list, field names (uid, did, type, password, hasPincode, ipcParam), /v4/tnp/device_info -> DID, InitString, License"
provides:
  - "TransportType, P2pCredentials (flag-only toString), TransportResolver.resolve(type, p2pId)"
  - "CameraPasswordCipher.decrypt(passwordHex, cloudUid)"
  - "P2pCredentialStore contract, EncryptedP2pCredentialStore (prefs file yicamalt_p2p), InMemoryP2pCredentialStore, P2pCredentialsCodec"
  - "Hilt binding P2pCredentialStore -> EncryptedP2pCredentialStore"
  - "Room cameras row v2 (+ transport, shared), YiDatabase version 2, CameraDao.replaceAll in one transaction"
affects: [01-09, 01-10, 01-11, phase-02-live-view]

tech-stack:
  added: []
  patterns:
    - "Secrets never reach Room: credential types live in camera/, the database package has no reference to them"
    - "Credential value types override toString() with has-flags only"
    - "Pure crypto helpers return null on any failure and never log"

key-files:
  created:
    - app/src/main/java/com/yicamalt/camera/P2pCredentials.kt
    - app/src/main/java/com/yicamalt/camera/CameraPasswordCipher.kt
    - app/src/main/java/com/yicamalt/camera/P2pCredentialStore.kt
    - app/src/test/java/com/yicamalt/camera/P2pCredentialsTest.kt
    - app/src/test/java/com/yicamalt/camera/CameraPasswordCipherTest.kt
    - app/src/test/java/com/yicamalt/camera/P2pCredentialStoreTest.kt
  modified:
    - app/src/main/java/com/yicamalt/camera/CameraModule.kt
    - app/src/main/java/com/yicamalt/database/CameraEntity.kt
    - app/src/main/java/com/yicamalt/database/CameraDao.kt
    - app/src/main/java/com/yicamalt/database/YiDatabase.kt
    - app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt
    - docs/verification-plan.xml

key-decisions:
  - "Device-list `type` wins over the p2pid shape; the shape only fills a missing type and raises a mismatch flag"
  - "A blank p2pid never raises a mismatch (nothing to contradict)"
  - "InMemoryP2pCredentialStore swaps one immutable map instead of clear+put on a ConcurrentHashMap, so replaceAll is atomic for readers"
  - "EncryptedP2pCredentialStore reports a failed commit with a value-free P2P_STORE_FAILED warning (count only)"

patterns-established:
  - "P2pCredentialStore is the only durable home of P2P secrets; callers hand it the full list (replaceAll)"
  - "Cache replace goes through CameraDao.replaceAll, not deleteAll + insert loop"

requirements-completed: [CAM-03, SEC-04]

duration: 7min
completed: 2026-10-11
---

# Phase 1 Plan 12: P2P credential types and encrypted store Summary

**Transport resolver (type 0/1/2 cross-checked with the DID shape), AES-ECB camera-password recovery keyed by the first 16 chars of the cloud uid, an EncryptedSharedPreferences credential store, and a secret-free Room cameras row v2 with a transactional replaceAll.**

## Performance

- **Duration:** 7 min
- **Started:** 2026-10-11T16:30:01Z
- **Completed:** 2026-10-11T16:36:43Z
- **Tasks:** 2
- **Files modified:** 12 (6 created, 6 modified)

## Accomplishments

- `TransportResolver.resolve(type, p2pId)`: 2 -> TNP, 0 -> TUTK, 1 -> LANGTAO, other -> UNKNOWN; a missing type falls back to the TNP DID shape; a conflict between type and shape is flagged, type still wins.
- `CameraPasswordCipher.decrypt`: null/blank field -> factory default; hex ciphertext (either case) -> plain password; short or missing uid, malformed hex, wrong key -> `null`, no exception, no log output. Pinned with a synthetic vector computed outside the code under test (openssl).
- `P2pCredentials.toString()` prints flags only; a test checks that no synthetic secret value appears in it.
- `EncryptedP2pCredentialStore` (file `yicamalt_p2p`, MasterKey AES256_GCM, same pattern as `EncryptedAuthStore`), bound in Hilt as `P2pCredentialStore`.
- `CameraEntity` gained `transport` and `shared` only; a test reads `PRAGMA table_info(cameras)` and pins the exact column set, so a secret column cannot be added silently.
- `CameraDao.replaceAll` runs delete + inserts in one Room transaction (closes the CONCERNS item "non-atomic delete-then-insert" at the DAO level).

## Task Commits

1. **Task 1: TransportType / P2pCredentials / TransportResolver / CameraPasswordCipher** - `768dada`
2. **Task 2: Encrypted P2P credential store + secret-free Room row v2 + transactional replaceAll** - `15722f2`

## Verification evidence

Final scoped run on the committed state (all four filters in one invocation):

```
BUILD SUCCESSFUL in 20s
35 actionable tasks: 12 executed, 23 up-to-date
--- gradle exit=0 elapsed=20.9s log=E:\Dev\YiCamAlt\build\logs\gradle-61872.log ---
```

| Test class | tests | failures | errors | skipped |
|---|---|---|---|---|
| com.yicamalt.camera.P2pCredentialsTest | 10 | 0 | 0 | 0 |
| com.yicamalt.camera.CameraPasswordCipherTest | 7 | 0 | 0 | 0 |
| com.yicamalt.camera.P2pCredentialStoreTest | 8 | 0 | 0 | 0 |
| com.yicamalt.database.YiDatabaseTest | 11 | 0 | 0 | 0 |

RED for Task 1 was observed before the implementation existed (`compileDebugUnitTestKotlin FAILED`, unresolved `TransportType`, `BUILD FAILED in 31s`). For Task 2 the tests were written first, but a separate RED run was not executed.

Acceptance greps: all pass (one `AES/ECB/PKCS5Padding` line, one `"888888"` line, no `Timber`/`Log.` in the two pure files, one `START_BLOCK_RESOLVE_TRANSPORT`, 0 secret-named `val` in `CameraEntity`, `version = 2`, one `@Transaction`, one `"yicamalt_p2p"`, 5 x `EncryptedSharedPreferences`, no `P2pCredentials` under `database/`, one `bindP2pCredentialStore`).

Not verified here: `EncryptedP2pCredentialStore` itself. It needs the Android Keystore and cannot run on the JVM; it is covered on the phone in plan 01-11. The full unit-test suite was not run (wave rule: scoped filters only; the wave gate runs at the start of plan 01-09).

## Files Created/Modified

- `camera/P2pCredentials.kt` - `TransportType`, `P2pCredentials`, `TransportResolver`
- `camera/CameraPasswordCipher.kt` - password recovery, `DEFAULT_PASSWORD`
- `camera/P2pCredentialStore.kt` - store contract, encrypted and in-memory implementations, JSON codec
- `camera/CameraModule.kt` - `bindP2pCredentialStore`
- `database/CameraEntity.kt` - row v2: `transport`, `shared`
- `database/CameraDao.kt` - `@Transaction replaceAll`
- `database/YiDatabase.kt` - `version = 2`
- `test/.../camera/P2pCredentialsTest.kt`, `CameraPasswordCipherTest.kt`, `P2pCredentialStoreTest.kt` - new
- `test/.../database/YiDatabaseTest.kt` - version 2, transport/shared round trip, replaceAll, column-set pin
- `docs/verification-plan.xml` - V-M-P2P-CREDS test file names

## Decisions Made

See `key-decisions` in the frontmatter. The addendum changed nothing in the code shape of this plan: the password source is the device-list field `password`, and the TNP fields (DID, InitString, License) map to `p2pId`, `initString`, `licenseKey`. The call to `/v4/tnp/device_info` and the assembly of `P2pCredentials` from a device record are not part of this plan.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Failed store commit is reported**
- **Found during:** Task 2
- **Issue:** the plan said the store logs only the success line; a `commit()` returning false would then be silent and the app would run with stale or missing credentials.
- **Fix:** on `commit() == false` the store logs `[P2pCreds][replaceAll][BLOCK_STORE_P2P_CREDENTIALS] code=P2P_STORE_FAILED count=N` (count only, no key, no value).
- **Files modified:** `camera/P2pCredentialStore.kt`
- **Committed in:** `15722f2`

**2. [Rule 1 - Bug] InMemoryP2pCredentialStore uses an immutable map swap**
- **Found during:** Task 2
- **Issue:** `clear()` + `putAll()` on a `ConcurrentHashMap` (plan wording) lets a reader see an empty store in the middle of `replaceAll`.
- **Fix:** one `@Volatile` immutable map replaced as a whole.
- **Committed in:** `15722f2`

**3. [GRACE rule 4] verification-plan.xml test file names**
- **Issue:** V-M-P2P-CREDS listed `TransportResolverTest.kt`, which this plan does not create (the plan names the file `P2pCredentialsTest.kt`).
- **Fix:** replaced with `P2pCredentialsTest.kt` and added `P2pCredentialStoreTest.kt`. Status stays `planned` (flipped in plan 01-11).
- **Committed in:** `15722f2`

**4. Extra tests beyond the behavior list:** blank-id mismatch, codec per transport, 15-byte ciphertext, `PRAGMA table_info` column pin, `replaceAll(emptyList())`.

**5. Process:** one commit per task with the commit subjects required by the plan's success criteria, instead of separate test/feat TDD commits. STATE.md, ROADMAP.md and REQUIREMENTS.md were not touched (parallel wave; the orchestrator owns them).

---

**Total deviations:** 2 auto-fixed (1 missing critical, 1 bug), 1 docs alignment, extra tests, 1 process note.
**Impact on plan:** no scope creep; no file outside the plan's list except the three-line docs fix.

## Issues Encountered

None. No foreign compile errors and no locked files during the four Gradle runs.

## Known Stubs

None. `EncryptedP2pCredentialStore` is bound in Hilt but has no caller yet; plan 01-09 wires it into the camera registry.

## Open points for later plans

- `needsPincode` is a flag only; the PIN-protected password call is not implemented.
- The existing `CameraRegistry.cacheCameraList` still uses `deleteAll()` + insert loop; plan 01-09 should switch it to `CameraDao.replaceAll`. Test fakes that subclass `CameraDao` inherit `replaceAll` and work unchanged.
- `TransportResolver.TNP_DID` is `^TNP[A-Z]*-\d+-[A-Z]+$` (from research). If a real DID has another prefix, type 2 still resolves to TNP and only the mismatch flag is affected.
- `docs/knowledge-graph.xml` and `docs/development-plan.xml` still describe `TransportType` as "TNP | TUTK" and keep M-P2P-CREDS at `planned`; plan 01-11 flips the node.
- Room schema v2 with destructive migration: the camera/event cache is wiped once on upgrade.

## User Setup Required

None.

## Next Phase Readiness

Plan 01-09 can build `P2pCredentials` from a device record + tnp device info, store them through `P2pCredentialStore.replaceAll`, and write the cache with `CameraDao.replaceAll` using `transport = TransportType.name` and `shared`.

## Self-Check: PASSED

- All 6 created files exist on disk; commits `768dada` and `15722f2` are in `git log`.

---
*Phase: 01-secrets-camera-list*
*Completed: 2026-10-11*
