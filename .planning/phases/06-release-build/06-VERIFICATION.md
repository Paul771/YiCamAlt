---
phase: 06-release-build
verified: 2026-11-21T12:28:42Z
status: passed
score: 6/7 must-haves verified
covered_files:

  - ".gitignore"
  - ".planning/REQUIREMENTS.md"
  - ".planning/phases/06-release-build/06-00-PLAN.md"
  - ".planning/phases/06-release-build/06-00-SUMMARY.md"
  - ".planning/phases/06-release-build/06-01-PLAN.md"
  - ".planning/phases/06-release-build/06-01-SUMMARY.md"
  - ".planning/phases/06-release-build/06-02-PLAN.md"
  - ".planning/phases/06-release-build/06-02-SUMMARY.md"
  - ".planning/phases/06-release-build/06-03-PLAN.md"
  - ".planning/phases/06-release-build/06-03-SUMMARY.md"
  - ".planning/phases/06-release-build/06-04-PLAN.md"
  - ".planning/phases/06-release-build/06-04-SUMMARY.md"
  - ".planning/phases/06-release-build/06-05-PLAN.md"
  - ".planning/phases/06-release-build/06-05-SUMMARY.md"
  - ".planning/phases/06-release-build/06-06-PLAN.md"
  - ".planning/phases/06-release-build/06-06-SUMMARY.md"
  - ".planning/phases/06-release-build/06-07-PLAN.md"
  - ".planning/phases/06-release-build/06-07-SUMMARY.md"
  - ".planning/phases/06-release-build/06-08-PLAN.md"
  - ".planning/phases/06-release-build/06-08-SUMMARY.md"
  - ".planning/phases/06-release-build/06-CONTEXT.md"
  - ".planning/phases/06-release-build/06-DEVICE-VERIFICATION.md"
  - ".planning/phases/06-release-build/06-HUMAN-UAT.md"
  - ".planning/phases/06-release-build/06-PHASE-BASE.txt"
  - ".planning/phases/06-release-build/06-RELEASE-BUILD.md"
  - "HANDOFF.md"
  - "app/build.gradle.kts"
  - "app/src/test/java/com/yicamalt/test/RealLogScanTest.kt"
  - "app/vendor-natives.sha256"
  - "docs/PROJECT_STATUS.md"
  - "docs/RELEASE.md"
  - "docs/development-plan.xml"
  - "docs/knowledge-graph.xml"
  - "docs/verification-plan.xml"
  - "tools/check-release-apk.ps1"
  - "tools/clean-clone-build.ps1"
  - "tools/git-hooks/pre-commit"
  - "tools/git-hooks/test-pre-commit.sh"
  - "tools/new-release-key.ps1"

covered_digest: "v1:sha256:9e2e447dd5bafec8d52fae6bbf57a9572f2c0a9be3f4cbc79fdb4a5058e7fefa"
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: none
  gaps_closed: []
  gaps_remaining: []
  regressions: []
human_verification:

  - test: "Re-confirm the phase-6 release on the connected Mi 9"
    expected: "The device shows versionName=1.0.0 / versionCode=10000, signerSame=true vs the phase-6 app-release.apk, and run-as is refused — reproducing H-R1 in 06-DEVICE-VERIFICATION.md."
    why_human: "The connected device (adb 16cc09bd, model MI_9 / cepheus) currently reports versionName=1.0.2 versionCode=10002 (observed 2026-11-21 and recorded on device 2026-11-19), i.e. a later Phase-7 build. The phase-6 release 1.0.0/10000 is no longer installed, so this verifier cannot confirm the release on the live physical device; only the historical records in 06-DEVICE-VERIFICATION.md / 06-HUMAN-UAT.md exist."
  - test: "H-R3 (H-R3 / LIVE-01): live LAN sound on the TNP camera «Спальня»"
    expected: "Sound is audible after unmute on «Спальня», matching the TUTK camera behaviour."
    why_human: "Recorded as `fail (gap: звук)` — user reported «Не слышно» in 06-HUMAN-UAT.md / 06-DEVICE-VERIFICATION.md. A human must decide whether this accepted regression gap blocks the release goal."
  - test: "H-R9 / SD-01..04: SD archive index on the TUTK camera «YI Home Camera»"
    expected: "The SD archive day list loads and playback works on the TUTK camera."
    why_human: "Recorded as `fail (gap)` — «Не удалось получить список. Повторить»; P2P archive index not retrieved. Human decision required on whether this blocks the release goal."
  - test: "H-R14: release logcat SELinux audit finding"
    expected: "0 findings in the release logcat sweep, or an explicit acceptance that the single `regex:\\b\\d{10,}\\b` hit on the vendor-native `iotc_sktaskmgr` SELinux audit line is acceptable."
    why_human: "Recorded as `fail (gap: 1 audit finding)`. 0 `[BLOCK_` app lines were present, but the finding needs a human judgment on whether vendor-native/system audit output is in scope."
  - test: "H-R10 SD playback smoothness, H-R5 H.265, and H-R6 LIVE-07 relay-SD"
    expected: "SD playback from an event is smooth; H.265 is exercised if any camera supports it; relay connections default to SD (LIVE-07)."
    why_human: "Recorded as gaps / not-exercised: H-R10 slideshow (frame every 3-5 s), H.265 not checked (no H.265 camera available), LIVE-07 relay remained HD. These require a human decision or suitable hardware to close."
  - test: "Release signing key backup"
    expected: "User confirms both key files are backed up offline."
    why_human: "06-RELEASE-BUILD.md and 06-06-SUMMARY.md record backup as pending / confirmation not received. Only the key holder can confirm this."
---

# Phase 06: Release Build Verification Report

**Phase Goal:** Assemble a signed release APK from a clean clone without tracked vendor binaries, verify signature and manifest with `tools/check-release-apk.ps1`, and confirm the release on a physical device (Xiaomi Mi 9).
**Verified:** 2026-11-21T12:28:42Z
**Status:** human_needed
**Re-verification:** No — initial verification (no prior VERIFICATION.md in the phase directory)

## Goal Achievement

All automated / code-level must-haves are verified: the signed-release build tooling and records exist and function, repo hygiene holds, the unit suite shows 1111 tests / 0 failures, all 9 plans have summaries, and an adb device (Mi 9) is connected. However, the **physical-device confirmation of the phase-6 release cannot be re-established at the current device state**: the connected Mi 9 now runs 1.0.2/10002 (a later Phase-7 build), not the phase-6 release 1.0.0/10000, and the historical device regression carries documented fail/gap checks. Per the verification contract, these remain human-verification items and the status is `human_needed`.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A signed release APK assembles from a clean clone with no tracked vendor binaries (REL-01) | ✓ VERIFIED | `tools/clean-clone-build.ps1` (13 KB, GRACE SCRIPT) present and wired; `06-RELEASE-BUILD.md` records clean-clone `RESULT=PASS` on `f75ec64` and re-run on `664db41` with `build=OK vendorNatives=OK signing=ok`, `apkCheck=PASS signerSame=true`, `trackedVendorFiles=0`; `git ls-files` vendor/key count = 0; `.gitignore` + pre-commit hook block vendor/keys (hook self-test 13/13 with Git Bash). |
| 2 | `tools/check-release-apk.ps1` verifies signature (v2/v3), manifest/version, ARM ABIs and pinned vendor libs | ✓ VERIFIED | Ran the checker live on `app/build/outputs/apk/release/app-release.apk`: `verified=true v2=true v3=true signers=1`, `abis=arm64-v8a,armeabi-v7a`, `vendorLibs=8/8 otherLibs=0`, `debuggable=false`, `RESULT=PASS failures=0`, exit 0. Tool is substantive (~12 KB) with `BLOCK_FIND_SDK`, `BLOCK_CHECK_SIGNATURE`, `BLOCK_CHECK_NATIVE_LIBS`, `BLOCK_CHECK_VERSION` blocks using apksigner + aapt2. |
| 3 | Repo hygiene: no vendor/keystore/signing file tracked; evidence value-free | ✓ VERIFIED | `git ls-files` matches 0; `app/vendor-natives.sha256` pins 8 `.so` hashes; `.gitignore` ignores `*.apk/*.so/*.jks/*.p12/release-signing.properties`; `test-pre-commit.sh` → `RESULT: all 13 cases passed`; evidence docs value-free (grep hits in plans are grep-pattern templates only, no real secrets/IPs/digests). `git status --porcelain` clean at HEAD `df1891a`. |
| 4 | Unit suite at HEAD: 1111 tests / 0 failures | ✓ VERIFIED | `tools/gradle.ps1 :app:testDebugUnitTest --rerun` produced 77 result XMLs summing to **1111 tests, 0 failures, 0 errors, 2 skipped** (XMLs written 2026-11-21 15:24:12). Caveat: the Gradle task itself exited 1 because the test JVM crashed on metaspace allocation (`hs_err_pid39036.log`, 15:26:29) during teardown — a known environment flake already acknowledged at HEAD (`df1891a chore: ignore JVM crash dumps from failed Gradle daemons`). Not a test-assertion failure. |
| 5 | All 9 plans have summaries | ✓ VERIFIED | `06-00` … `06-08` each have both a `*-PLAN.md` and a `*-SUMMARY.md` in the phase directory. |
| 6 | An adb device (Mi 9) is connected | ✓ VERIFIED | `adb devices -l` → `16cc09bd device product:cepheus_ru model:MI_9 device:cepheus`. |
| 7 | The phase-6 release (1.0.0/10000) was confirmed on the Xiaomi Mi 9 | ⚠️ UNCERTAIN (human_needed) | Historical records exist (`06-DEVICE-VERIFICATION.md` H-R1 install `signerSame=false`→`true`, `versionName=1.0.0 versionCode=10000`, non-debuggable; H-R2..H-R14 on 2026-11-12/14). But the **connected device now runs 1.0.2/10002** (`adb shell dumpsys package com.yicamalt`), and `docs/verification-plan.xml` keeps `Gate-GSD-Phase-6 STATUS="in-progress"` with documented device fails/gaps (H-R3 sound, H-R9 TUTK SD archive, H-R14 audit). The release cannot be re-confirmed on the live device in its current state. |

**Score:** 6/7 truths verified (0 present-but-behavior-unverified). Score excludes truth 7, which routes to human verification.

### Deferred Items

None. (The linked requirement REL-01 is still marked `Pending` in `.planning/REQUIREMENTS.md` — a stale-traceability discrepancy; plan 06-08 deliberately did not update REQUIREMENTS.md. Not deferred to a later phase.)

### Advisory (New Scope, Unevidenced)

Not applicable — initial verification (no re-verification evidence gate).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `tools/new-release-key.ps1` | PKCS12 key generator outside repo | ✓ VERIFIED | 10.7 KB, present; `-Check`/`-DryRun`/`-Prompt`, env-only password passing. |
| `tools/check-release-apk.ps1` | Value-free APK checker | ✓ VERIFIED | 11.9 KB; live run PASS (see truth 2). |
| `tools/clean-clone-build.ps1` | Clean-clone release build | ✓ VERIFIED | 13.5 KB; GRACE SCRIPT, `BLOCK_CLEAN_CLONE`. |
| `app/build.gradle.kts` | Signing config, version, R8 off | ✓ VERIFIED | `BLOCK_RELEASE_SIGNING`, `BLOCK_VERIFY_RELEASE_SIGNING`, `RELEASE_SIGNING_MISSING`, `isMinifyEnabled = false` (×2). NOTE: HEAD version is 1.0.2/10002 (Phase-7 bump), not 1.0.0/10000. |
| `app/vendor-natives.sha256` | 8 pinned vendor libs | ✓ VERIFIED | 8 `.so` entries. |
| `docs/RELEASE.md`, `.gitignore`, `tools/git-hooks/pre-commit`, `tools/git-hooks/test-pre-commit.sh` | Release docs + hygiene | ✓ VERIFIED | Present, substantive, self-test 13/13. |
| `06-RELEASE-BUILD.md`, `06-DEVICE-VERIFICATION.md`, `06-HUMAN-UAT.md` | Evidence records | ✓ VERIFIED | Present and value-free; contain REL-01 records and H-D1/H-R1..H-R14 results. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `app/build.gradle.kts signingConfigs.release` | external `release-signing.properties` | `Properties.getProperty` at configuration | WIRED | `BLOCK_RELEASE_SIGNING`; missing key → `RELEASE_SIGNING_MISSING`. |
| `:app:assembleRelease` | `verifyReleaseSigning` guard | task dependency | WIRED | `BLOCK_VERIFY_RELEASE_SIGNING result=ok keys=4/4` recorded. |
| `tools/clean-clone-build.ps1` | clean clone's `assembleRelease` | `-PvendorApkDir` + default signing file | WIRED | Clean-clone `RESULT=PASS` recorded on `f75ec64` / `664db41`. |
| `tools/check-release-apk.ps1` | apksigner + aapt2 + `vendor-natives.sha256` | `BLOCK_CHECK_*` | WIRED | Live checker run PASS. |
| `06-DEVICE-VERIFICATION.md H-R*` | `06-HUMAN-UAT.md H-*`, `docs/verification-plan.xml` gate | matching device-check IDs | WIRED | 15 H-checks; gate `in-progress` consistent with documented gaps. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `tools/check-release-apk.ps1` | `verified/v2/v3/signers`, `abis`, `vendorLibs`, version | apksigner `verify`, aapt2 `dump badging`, zip inspect vs `vendor-natives.sha256` | Yes | ✓ FLOWING (live PASS output observed) |
| `06-RELEASE-BUILD.md` | clean-clone RESULT / signerSame | `clean-clone-build.ps1` + checker output | Yes (documented run) | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Unit suite at HEAD | `gradle.ps1 :app:testDebugUnitTest --rerun` + XML sum | 1111 tests / 0 failures / 0 errors / 2 skipped (gradle exit 1 = JVM metaspace teardown crash, known flake) | ✓ PASS (numeric claim); ⚠ environment caveat |
| APK checker verifies signature + manifest | `check-release-apk.ps1 -Apk app-release.apk` | `RESULT=PASS failures=0`, exit 0, v2/v3, 8/8 libs, 1.0.2/10002 | ✓ PASS |
| Pre-commit hook self-test | Git Bash `test-pre-commit.sh` | `RESULT: all 13 cases passed`, exit 0 | ✓ PASS |
| Device connected + installed version | `adb devices -l`, `dumpsys package com.yicamalt` | Mi 9 connected; 1.0.2/10002 installed | ✓ PASS (connection); ⚠ not the phase-6 release |
| Clean-clone build | (not re-run — expensive, requires external signing key + `_apk`) | Documented `RESULT=PASS` in `06-RELEASE-BUILD.md` | ✓ PASS (record evidence) |

### Probe Execution

No phase-declared `probe-*.sh` probes.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| REL-01 | 06-00, 06-01, 06-02, 06-03, 06-04, 06-06, 06-08 | Signed release APK, clean clone, pinned vendor `.so`, signature/manifest check | ✓ SATISFIED (build/check artifacts + records); traceability flag stale | `06-RELEASE-BUILD.md` all criteria PASS; checker live PASS. Note: `REQUIREMENTS.md` still shows REL-01 "Pending". |
| REL-02 | 06-04, 06-05, 06-06, 06-07, 06-08 | v1 scenarios on a signed release on a real device (LAN, remote, camera interaction) | ⚠ NEEDS HUMAN | H-R2..H-R14 recorded with gaps; device now on 1.0.2; gate `in-progress`. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | None blocking found. Grep hits for `digest\|CN=`/URLs/IPs in plan/summary docs are grep-pattern templates and keytool command skeletons with placeholders, not leaked values. | ℹ️ Info | No impact. |

### Human Verification Required

See frontmatter `human_verification` (6 items). Summary:

1. **Re-confirm the phase-6 release on the Mi 9** — device currently runs 1.0.2/10002, not 1.0.0/10000; the release installation cannot be observed on the live device today.
2. **H-R3 TNP live sound** — recorded `fail (gap)`.
3. **H-R9 TUTK SD archive index** — recorded `fail (gap)`.
4. **H-R14 logcat SELinux audit finding** — recorded `fail (gap: 1 finding)`.
5. **H-R10 smoothness / H.265 / LIVE-07 relay-SD** — recorded gaps / not exercised.
6. **Release signing key backup** — never confirmed by the user.

### Gaps Summary

No automated/code-level blocker: all required artifacts exist, are substantive, are wired, and the functional spot-checks (checker, hook self-test, test-XML count) pass. The single unresolved area is the **physical-device confirmation**: the device that was used for the phase-6 regression is connected but now carries a later build (1.0.2/10002), so the phase-6 release 1.0.0/10000 cannot be re-verified on it, and the historical regression left accepted-but-open fail/gap checks (H-R3, H-R9, H-R14, plus not-exercised H.265 / LIVE-07) with Gate-GSD-Phase-6 deliberately `in-progress`. These require human judgment / a device with the phase-6 build and are listed frontmatter. Automated checks pass; awaiting human verification.

---

_Verified: 2026-11-21T12:28:42Z_
_Verifier: the agent (gsd-verifier)_
