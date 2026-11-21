---
phase: 01-secrets-camera-list
plan: 02
subsystem: security
tags: [timber, logging, redaction, sec-01, sec-02, room]

requires:
  - phase: 01-00
    provides: tools/gradle.ps1 argument binding, RedactionScanner/TraceRecorder test infra
provides:
  - DiagnosticLog (filesDir/diag_log.txt, legacy auth_log.txt purge) + rotating FileLogTree (1 MiB, one .1 backup)
  - Serial/user-id/epoch-free log lines in CameraDao, CameraCommandService, AppShellScreen, AuthRepository
affects: [01-03 (.gitignore diag_log.txt), 01-05 (wave-1 full-suite gate), 01-06 (device-log sweep, docs/*.xml sync), 01-07 (CameraDao replaceAll)]

tech-stack:
  added: []
  patterns:
    - "Log booleans/derived values (hasUser, hasModel, expiresInSec) instead of identifiers"
    - "Redaction tests use a realistic 13-digit serial and a 13-digit epoch clock"

key-files:
  created:
    - app/src/test/java/com/yicamalt/DiagnosticLogTest.kt
  modified:
    - app/src/main/java/com/yicamalt/YiCamAltApp.kt
    - app/src/main/java/com/yicamalt/database/CameraDao.kt
    - app/src/main/java/com/yicamalt/camera/CameraCommandService.kt
    - app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt
    - app/src/main/java/com/yicamalt/auth/AuthRepository.kt
    - app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt
    - app/src/test/java/com/yicamalt/camera/CameraCommandModuleTest.kt
    - app/src/test/java/com/yicamalt/auth/AuthRepositoryTest.kt

key-decisions:
  - "Diagnostic log is internal-only; read it with adb exec-out run-as com.yicamalt cat files/diag_log.txt (debug build)"
  - "Rotation keeps exactly one backup (diag_log.txt.1), so worst case is about 2 MiB on disk"

patterns-established:
  - "No identifiers in log lines outside the redacting HTTP layer: log presence flags instead"

requirements-completed: [SEC-01, SEC-02]

duration: 25min
completed: 2026-10-10
---

# Phase 01 Plan 02: Internal-only diag log + identifier-free log sites Summary

**Debug log moved to filesDir/diag_log.txt with 1 MiB rotation and legacy auth_log.txt purge; DAO, command, shell and auth logs no longer carry serials, user ids or epoch-millis**

## Performance

- **Duration:** ~25 min
- **Completed:** 2026-10-10
- **Tasks:** 2/2
- **Files modified:** 9 (1 created, 8 modified)

## Accomplishments
- `YiCamAltApp` v0.2.0: `DiagnosticLog` object, `FileLogTree(file, maxBytes)` with `BLOCK_WRITE_DIAG_LOG` rotation; the external-dir FileLogTree is gone, the only `getExternalFilesDir` call is the purge; init logs `diagLog=internal legacyPurged=N`.
- `CameraDao` `BLOCK_DB_WRITE` logs `online=` and `hasModel=` only (anchors unchanged).
- `CameraCommandService` logs `cmd=` (and `pan=`/`tilt=`) only; payload still carries device_id.
- `AppShellScreen` logs `hasUser=`; `AuthRepository` logs `hasUser=`/`expiresInSec=` instead of user id and `expiresAt`.
- Tests: `DiagnosticLogTest` (4), `YiDatabaseTest.scenario_5` with serial `9876543210123`, `CameraCommandModuleTest` `redaction no device serial leaks into command logs`, `AuthRepositoryTest` `redaction auth logs carry no user id or epoch` (clock moved to a 13-digit epoch, user id 167315).

## Task Commits

1. **Task 1: Internal-only rotating diagnostic log + legacy purge**: `e057057`
2. **Task 2: Remove serial/user id/epoch from DAO, command, shell and auth logs**: `d29e98f`

## Verification

- Task 1: `gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.DiagnosticLogTest"` gave `BUILD SUCCESSFUL in 21s`, `--- gradle exit=0`; DiagnosticLogTest tests="4" failures="0" errors="0".
- Task 2 scoped run (database.*, CameraCommandModuleTest, auth.*, DiagnosticLogTest) gave `BUILD SUCCESSFUL in 8s`, `--- gradle exit=0 elapsed=9.2s`. Result XMLs: DiagnosticLogTest 4/0/0, AuthRepositoryTest 12/0/0, CameraCommandModuleTest 10/0/0, YiDatabaseTest 8/0/0 (tests/failures/errors).
- All acceptance greps hold (no `expiresAt=`, `device_id}`, `device=$deviceId`, `user=$userId`; `9876543210123` appears 2 times in YiDatabaseTest; one `getExternalFilesDir` in app/src/main).

## Deviations from Plan

1. **TDD RED and GREEN combined into one commit per task.** The plan marks the tasks `tdd="true"`. A test that references the not-yet-written `DiagnosticLog` would break test compilation for the three parallel executors sharing this tree, so the test and the implementation were committed together. The new redaction tests target the exact log sites changed, and the 13-digit serial and epoch they use would be flagged by the existing `\b\d{10,}\b` rule if those sites still logged them.
2. **Gradle contention.** Two scoped runs failed with `FileSystemException ... classes.jar` (the file was locked by another executor's Gradle run) and one failed with an empty test-results dir. Retried after waiting; no code change was needed.
3. **Commit message for Task 1.** Task 1 used `grace(M-LOCAL-DB): SEC-02 internal-only rotating diag log, legacy auth_log purge`. Task 2 used the exact message from the plan's success criteria.

## Known Stubs

None.

## Next Phase Readiness
- docs/*.xml (knowledge-graph, verification-plan) were not edited. Plan 01-06 syncs them, as the plan requires.
- The wave-1 full-suite gate runs in plan 01-05 Task 1.

## Self-Check: PASSED
