---
phase: 06-release-build
plan: 02
subsystem: release
tags: [release, signing, gradle, apksigner, aapt2, D-01, D-02]
requires: ["06-01"]
provides:
  - app/build.gradle.kts release signingConfig from the external properties file (BLOCK_RELEASE_SIGNING)
  - verifyReleaseSigning guard on (assemble|install|bundle|package)Release (BLOCK_VERIFY_RELEASE_SIGNING)
  - versionName 1.0.0 / versionCode 10000, minify + resource shrinking off
  - tools/check-release-apk.ps1 (value-free APK checker)
affects: ["06-03 (clean-clone build)", "06-06 (uninstall-before-install checkpoint: release signer != debug signer)"]
tech-stack:
  added: []
  patterns:
    - "signing values read at configuration via java.util.Properties, only found/missing + key count ever logged"
    - "apksigner via java -jar lib/apksigner.jar, output kept in memory, only booleans/counts printed"
key-files:
  created: [tools/check-release-apk.ps1]
  modified: [app/build.gradle.kts, docs/RELEASE.md]
decisions:
  - "import java.util.Properties instead of java.util.Properties(): in a Gradle Kotlin script `java` resolves to the java extension"
  - "Per-task commits (Task 1 gradle, Task 2 checker) instead of the single commit named in the plan, per the session rule of atomic task commits"
metrics:
  duration: ~25 min
  completed: 2026-11-05
requirements: [REL-01]
---

# Phase 6 Plan 02: Signed release build 1.0.0 Summary

Release builds are signed with the external PKCS12 key (v2+v3), unminified, version 1.0.0/10000, fail value-free with `RELEASE_SIGNING_MISSING` without a key, and a value-free checker proves signature, ARM-only ABIs, 8 pinned vendor libs, version and debuggable=false.

## Tasks

| Task | Name | Commit |
|---|---|---|
| 1 | Release signing, version 1.0.0, R8 off in app/build.gradle.kts | 4006100 |
| 2 | tools/check-release-apk.ps1 + check of the built APK (+ RELEASE.md usage) | acdcd5d |

## Evidence (value-free)

- Key presence: `new-release-key.ps1 -Check` -> `keys=4/4 store=found acl=ok`
- Dry-run `:app:assembleRelease --dry-run`: 0 lines `minifyReleaseWithR8`
- `-PyicamaltSigningFile=E:/no-such-dir/none.properties`: `:app:verifyReleaseSigning` fails with `code=RELEASE_SIGNING_MISSING file=missing keys=0/4 store=missing`, exit 1
- Without key: RedactionScannerTest BUILD SUCCESSFUL; `:app:assembleDebug` BUILD SUCCESSFUL with `vendor natives: OK (8 libs, manifest match)`
- Full gate `:app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleRelease`: BUILD SUCCESSFUL, exit 0; 1102 tests, 0 failures, 1 skipped; `vendor natives: OK`, `BLOCK_VERIFY_RELEASE_SIGNING] result=ok keys=4/4`; lintVital passed; `app-release.apk` (no `-unsigned`)
- Gradle log of the gate: 0 lines matching keystore path patterns
- `check-release-apk.ps1`: `verified=true v2=true v3=true signers=1`, `abis=arm64-v8a,armeabi-v7a`, `vendorLibs=8/8 otherLibs=0`, `package=com.yicamalt versionName=1.0.0 versionCode=10000 debuggable=false`, `RESULT=PASS`
- `-CompareApk app-debug.apk`: `signerSame=false`, RESULT=PASS
- Self-compare with `-ExpectSameSigner`: `signerSame=true` (proves digest extraction is not empty); debug compare with `-ExpectSameSigner`: RESULT=FAIL
- Negative: `-Apk app-debug.apk -SkipVersion` -> `debuggable=true`, `RESULT=FAIL failures=1`, exit 1
- Captured checker output: 0 lines matching `digest|CN=`; `git status --porcelain` clean (no APK, no key)

## Deviations from Plan

**1. [Rule 3 - Blocking] `java.util.Properties()` unresolved in the build script**
- Issue: in Gradle Kotlin DSL `java` binds to the `java` project extension, so the fully-qualified call did not compile.
- Fix: `import java.util.Properties` and `Properties().apply { ... }`.
- Commit: 4006100

**2. [Process] Two commits instead of one** — plan named one commit for both files; split per task per the session's atomic-commit rule. Commit message of Task 2 is the one from the plan.

**3. [Rule 2] Extra checker evidence** — `signerSame=false` could be a false negative if digest parsing failed; added a self-compare check (`signerSame=true`). Also docs/RELEASE.md line "появится в плане 06-02" replaced with the checker usage.

**4. [Flake] First full gate run** — `EventMediaRepositoryTest > purge deletes thumbs older than 30 days and their day folders` failed once (Windows file deletion, same class as the known wipe flake); rerun green with no code change.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: tools/check-release-apk.ps1, app/build.gradle.kts (BLOCK_RELEASE_SIGNING, BLOCK_VERIFY_RELEASE_SIGNING)
- FOUND: commits 4006100, acdcd5d
