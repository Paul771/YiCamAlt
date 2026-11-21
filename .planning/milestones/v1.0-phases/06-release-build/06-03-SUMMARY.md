---
phase: 06-release-build
plan: 03
subsystem: release
tags: [release, clean-clone, REL-01, SEC-03]
requires: ["06-02"]
provides:
  - tools/clean-clone-build.ps1 (GRACE SCRIPT, BLOCK_CLEAN_CLONE)
  - .planning/phases/06-release-build/06-RELEASE-BUILD.md (value-free REL-01 evidence)
affects: ["06-06 (uninstall-before-install checkpoint)"]
tech-stack:
  added: []
  patterns:
    - "child Gradle output captured in memory, only log anchors read from the clone's build/logs"
    - "guarded temp clean-up: temp root + name prefix + not under repo"
key-files:
  created: [tools/clean-clone-build.ps1, .planning/phases/06-release-build/06-RELEASE-BUILD.md]
  modified: [docs/RELEASE.md]
decisions:
  - "Online build worked; -Offline not needed"
  - "R8 dry-run, missing-key failure and keyless JVM tests cited from 06-02 evidence, not re-run"
metrics:
  duration: ~15 min
  completed: 2026-11-05
requirements: [REL-01]
---

# Phase 6 Plan 03: Clean clone release build Summary

A fresh clone of HEAD in TEMP, with only `-PvendorApkDir` pointing at the local `_apk`, builds a signed release APK (`--no-build-cache`) that passes the checker with the same signer as the repo APK, leaves no changed/untracked file, and is removed afterwards; SEC-03 checks still hold.

## Tasks

| Task | Name | Commit |
|---|---|---|
| 1 | tools/clean-clone-build.ps1 + clean-clone run | f75ec64 |
| 1b | RELEASE.md usage of the script | c0b3647 |
| 2 | SEC-03 checks + 06-RELEASE-BUILD.md | fd0e366 |

## Evidence (actually run in this plan)

- `clean-clone-build.ps1` (commit f75ec64, online): `trackedVendorFiles=0 apkDir=absent appBuild=absent localProperties=absent`, `build=OK vendorNatives=OK signing=ok offline=false`, checker `verified=true v2=true v3=true signers=1`, `abis=arm64-v8a,armeabi-v7a`, `vendorLibs=8/8 otherLibs=0`, `versionName=1.0.0 versionCode=10000 debuggable=false`, `apkCheck=PASS signerSame=true`, `trackedChanged=0 untrackedNotIgnored=0`, `removed=true`, `RESULT=PASS`, exit 0; 0 `yicamalt-clean-*` folders in TEMP afterwards
- `git ls-files` vendor/key count 0 -> `NO_TRACKED_VENDOR_OR_KEY`; range scan from phase base exit 0 (`RANGE_OK`, re-run after the last commit)
- `test-pre-commit.sh`: `RESULT: all 13 cases passed`
- `git check-ignore`: release APK and `_apk/x.apk` ignored
- Repo APK vs debug: `signerSame=false`, `RESULT=PASS`
- `new-release-key.ps1 -Check`: `keys=4/4 store=found acl=ok`
- Value-free grep on 06-RELEASE-BUILD.md: 0 lines

Not run here (cited from 06-02): R8 dry-run, missing-key `RELEASE_SIGNING_MISSING`, keyless JVM tests. No unit-test suite was run in this plan, so no flaky tests occurred. Phone not touched, nothing installed.

## Deviations from Plan

**1. [Rule 2] docs/RELEASE.md** — the line "появится в плане 06-03" replaced with the actual usage of `clean-clone-build.ps1` (commit c0b3647).

**2. [Rule 2] Extra hygiene field** — the hygiene line also prints `appBuild=absent` (plan asked to assert `app/build` absence; it is now visible in the output).

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: tools/clean-clone-build.ps1, .planning/phases/06-release-build/06-RELEASE-BUILD.md
- FOUND: commits f75ec64, c0b3647, fd0e366
