---
phase: 06-release-build
plan: 04
subsystem: release
tags: [release, uat, redaction, REL-01, REL-02, SEC-01]
requires: ["06-03"]
provides:
  - RealLogScanTest v0.2.0 opt-in logcat sweep (-PredactionLogcatFile -> redaction.logcatFile)
  - .planning/phases/06-release-build/06-HUMAN-UAT.md (H-D1, H-R1..H-R14, all pending)
  - Gate-GSD-Phase-6 device-checks + command-7; V-M-RELEASE-BUILD passed; M-RELEASE-BUILD implemented
affects: ["06-05 (H-D1)", "06-06 (H-R1)", "06-07 (H-R2..H-R14)"]
tech-stack:
  added: []
  patterns:
    - "release logcat sweep without the [BLOCK_ marker requirement; empty capture = skipped, not passed"
key-files:
  created: [.planning/phases/06-release-build/06-HUMAN-UAT.md]
  modified: [app/src/test/java/com/yicamalt/test/RealLogScanTest.kt, app/build.gradle.kts, docs/verification-plan.xml, docs/development-plan.xml, docs/knowledge-graph.xml, .planning/phases/06-release-build/06-RELEASE-BUILD.md]
decisions:
  - "Device build commit for 06-05 / 06-06 = 664db41 (clean-clone RESULT=PASS); later commits are docs only"
  - "V-M-REPO-HYGIENE already carried scenario-7..9 and the 13-case evidence from 06-00; only check-3 got the Phase 6 range note"
metrics:
  duration: ~20 min
  completed: 2026-11-05
requirements: [REL-01, REL-02]
---

# Phase 6 Plan 04: Logcat sweep, UAT script and GRACE record Summary

RealLogScanTest can now sweep a release logcat capture (release writes no diag log), the 15-check device regression script for 06-05..06-07 exists, GRACE marks the release build module implemented / passed, and the full gate plus a clean-clone re-run passed on the device build commit.

## Tasks

| Task | Name | Commit |
|---|---|---|
| 1 | Opt-in logcat sweep in RealLogScanTest + Gradle pass-through | 927ba72 |
| 2 | 06-HUMAN-UAT.md and GRACE record (verification / development plan, knowledge graph) | 664db41 |
| 3 | Full gate, APK check, clean-clone re-run; evidence in 06-RELEASE-BUILD.md and Gate evidence-2 | efa4025 |

## Evidence (actually run in this plan)

- RealLogScanTest without properties: BUILD SUCCESSFUL, `tests=2 skipped=2 failures=0`.
- Clean capture (two `ActivityManager` style lines, scratchpad): `tests=2 skipped=1 failures=0`, BUILD SUCCESSFUL.
- Dirty capture (one `{"token":"<value>"}` line, value generated at run time in the scratchpad, never in the repo): BUILD FAILED, `failures=1`; the invented value occurs 0 times in the console output, in the Gradle log of that run and in the test result XML.
- Empty file and whitespace-only file: `tests=2 skipped=2 failures=0` ("logcat capture empty").
- XML validation: three `ok`; UAT headings `^### H-(D1|R[0-9]+)` = 15; gate elements `<H-(D1|R[0-9]+) ` = 15; URL / long-digit / IP grep over the UAT = 0 lines.
- Full gate `:app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleRelease`: BUILD SUCCESSFUL, gradle exit=0; 77 suites, 1103 tests, 0 failures, 0 errors, 2 skipped (both RealLogScanTest). No flaky test failed, no rerun needed.
- `check-release-apk.ps1 -CompareApk app-debug.apk`: `verified=true v2=true v3=true signers=1`, `signerSame=false`, `abis=arm64-v8a,armeabi-v7a`, `vendorLibs=8/8 otherLibs=0`, `versionName=1.0.0 versionCode=10000 debuggable=false`, `RESULT=PASS`.
- `clean-clone-build.ps1` on 664db41 (worktree clean): `trackedVendorFiles=0`, `build=OK vendorNatives=OK signing=ok`, `apkCheck=PASS signerSame=true`, `trackedChanged=0 untrackedNotIgnored=0`, `removed=true`, `RESULT=PASS commit=664db41`, exit 0; 0 clone folders left in TEMP.
- `pre-commit --range <06 phase base>..HEAD` exit 0.

## Deviations from Plan

**1. [Rule 3 - Blocking] RED stub blocked by the hook**
- **Found during:** Task 1
- **Issue:** the PreToolUse hook refused `error("RED stub")` in the new test.
- **Fix:** wrote the minimal real body (as the phase rules allow); RED evidence is the dirty capture failing (`failures=1`) against the real scanner.
- **Files modified:** RealLogScanTest.kt
- **Commit:** 927ba72

**2. Note on the full gate APKs**
- `packageRelease` / `packageDebug` were UP-TO-DATE in the gate (inputs unchanged since 06-03: only tests and docs changed), so the APKs in `app/build/outputs` were packaged earlier from identical inputs. The clean-clone re-run built and checked a fresh release APK from 664db41 with the same signer.

Otherwise the plan was executed as written. Not done by design: no phone access, no APK installed, STATE.md / ROADMAP / REQUIREMENTS untouched, no push, no merge.

## Flaky tests

None failed in this plan's runs.

## Known Stubs

None. All UAT results are `pending` by design (filled in by 06-05..06-07).

## Self-Check: PASSED
