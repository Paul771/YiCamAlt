---
phase: 02-p2p
plan: 02
subsystem: build-vendor-natives
tags: [p2p, gradle, jni, sha256, r8, hygiene]
requires:
  - "02-00: GRACE node M-P2P-NATIVE / V-M-P2P-NATIVE (lists :app:verifyVendorNatives)"
provides:
  - ":app:extractVendorNatives (never throws; writes <outDir>-status.txt)"
  - ":app:verifyVendorNatives (fails APK tasks on MISSING / MISMATCH)"
  - "app/vendor-natives.sha256 (8 pinned hashes, Yi Home 6.9.8)"
  - "jniLibs srcDir app/build/generated/vendorJniLibs, abiFilters arm64-v8a + armeabi-v7a, legacy packaging"
  - "R8 keep rules for com.p2p.pppp_api, com.tnp.model, com.tutk.IOTC, native method names"
affects: [02-03, 02-06, 02-10]
tech-stack:
  added: []
  patterns: ["extract never fails, verify fails only for APK-producing tasks (exact-name hook)", "status file outside the packaged dir"]
key-files:
  created:
    - app/vendor-natives.sha256
  modified:
    - app/build.gradle.kts
    - app/proguard-rules.pro
    - HANDOFF.md
decisions:
  - "Verify hook uses exact task names: assembleDebug, assembleRelease, installDebug, bundleDebug, bundleRelease, mergeDebugJniLibFolders, mergeReleaseJniLibFolders. assembleDebugUnitTest / bundleDebugClassesToCompileJar are deliberately not matched."
  - "java.util / java.security are imported at the top of build.gradle.kts: inside a Kotlin DSL script the bare name `java` resolves to the java extension, so fully-qualified java.* calls do not compile."
metrics:
  duration: 20min
  completed: 2026-10-13
  tasks: 2
  files: 4
---

# Phase 2 Plan 02: Vendor natives extraction with SHA-256 manifest Summary

The build now copies `libPPPP_API.so`, `libIOTCAPIs.so`, `libAVAPIs.so` and `libsCHL.so` (arm64-v8a and armeabi-v7a) out of the user's own Yi Home APK into `app/build/generated/vendorJniLibs` and checks them against the committed `app/vendor-natives.sha256`. A fresh clone without an APK still runs every JVM test. Only APK assembly fails, and its message says where to put the APK. A different vendor build fails with the observed hashes and an explicit opt-in.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | extractVendorNatives / verifyVendorNatives, manifest, packaging wiring | 517f5fe |
| 2 | Fresh-clone and mismatch proofs, R8 keep rules, HANDOFF | 963432e |

## Evidence

- **Real APK:** `:app:extractVendorNatives :app:verifyVendorNatives` printed `vendor natives: OK (8 libs, manifest match)`, `--- gradle exit=0`, and `STATUS=OK` with all 8 lines `expected=match`. Sources were `SOURCE=split_config.arm64_v8a.apk abi=arm64-v8a` and `SOURCE=split_config.armeabi_v7a.apk abi=armeabi-v7a`. Each ABI dir holds exactly `libAVAPIs.so libIOTCAPIs.so libPPPP_API.so libsCHL.so`. **Extraction time:** about 5 s for the whole build (6.5 s wall).
- **Bundle path (extra check):** `-PvendorApk=_apk/Yi+Home_6.9.8_…_APKPure.xapk` with an empty apk dir and the sim out dir also gave `OK`. The libs were read zip-in-zip from the xapk for both ABIs.
- **Simulation 1a (fresh clone, JVM tests):** `:app:testDebugUnitTest --tests com.yicamalt.test.RedactionScannerTest` with an empty `-PvendorApkDir` printed the warn line `STATUS=MISSING`, then `BUILD SUCCESSFUL`, exit 0.
- **Simulation 1b (fresh clone, verify):** `:app:verifyVendorNatives` failed with `Vendor native libs missing: put your own Yi Home APK … see HANDOFF.md section 3`, exit 1 (expected).
- **Task graphs (dry run `-m`):** `assembleDebug` includes `:app:extractVendorNatives`, `:app:verifyVendorNatives`, `:app:mergeDebugJniLibFolders`. `testDebugUnitTest` includes only `:app:extractVendorNatives` (through preBuild) and not verify.
- **1c dependency proof:** `:app:mergeDebugJniLibFolders -m` printed, in this order:
  ```
  :app:extractVendorNatives SKIPPED
  :app:verifyVendorNatives SKIPPED
  :app:mergeDebugJniLibFolders SKIPPED
  ```
- **Simulation 2 (mismatch):** a fake APK with eight 8-byte `SYNTHLIB` entries failed with 8 `DIFF <abi>/<lib> sha256=954CB0AD… bytes=8` lines and the hint `re-run with -PvendorNativesAccept=true`, exit 1. The accept switch was not used. The manifest is unchanged (`git diff` empty). `build/fake-apk-dir` and `build/sim-vendor` were removed, and the real re-verify printed `OK`.
- **Hygiene:** `sh tools/git-hooks/test-pre-commit.sh` → `RESULT: all 8 cases passed`. `git ls-files | grep -Eic '\.(apk|apkm|xapk|so|dex)$'` → 0 (`HYGIENE_OK`). There are no `startsWith(` hooks, and `build.gradle.kts` contains no `/*`.

Merge task names confirmed with `:app:tasks --all`: `mergeDebugJniLibFolders` and `mergeReleaseJniLibFolders`, plus `mergeDebugAndroidTestJniLibFolders`, which is intentionally not hooked. `installRelease` does not exist because release has no signing config.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `java.*` qualified names do not compile in build.gradle.kts**
- **Found during:** Task 1, first run
- **Issue:** `java` resolves to the Java plugin extension inside the Kotlin DSL script (`Unresolved reference: security`).
- **Fix:** I added top-level imports of `MessageDigest`, `ZipFile` and `ZipInputStream`.
- **Commit:** 517f5fe

**2. [Rule 1 - Docs accuracy] Two more stale LFS mentions in HANDOFF.md**
- **Issue:** Section 1 ("LFS нужен только для APK в `_apk/`") and the section 2 table row for `_apk/extracted/` ("в LFS") contradicted the new section 3 paragraph.
- **Fix:** I reworded both to say the APK is local only. The Git for Windows row that mentions bundled LFS was left unchanged.
- **Commit:** 963432e

### Notes

- Two commits instead of the plan's single commit message: the executor protocol needs one commit per task. Both commits use the plan's `build(M-P2P-NATIVE)` scope.
- The status file also carries one `SOURCE=<file name> abi=<abi>` line per ABI. It holds the file name only and never a path.
- R8 with the real shim classes is verified in 02-06 (the shim comes from 02-03).
- Requirements LIVE-01 and LIVE-02 are NOT marked complete. They close only after device verification.

## Known Stubs

None.

## Self-Check: PASSED
