---
phase: 07-regression-fixes
reviewed: 2026-11-18T18:25:59Z
depth: standard
files_reviewed: 30
files_reviewed_list:
  - app/build.gradle.kts
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/com/yicamalt/archive/PlaybackController.kt
  - app/src/main/java/com/yicamalt/archive/SdIndexRepository.kt
  - app/src/main/java/com/yicamalt/archive/SdTypes.kt
  - app/src/main/java/com/yicamalt/live/DefaultMediaSession.kt
  - app/src/main/java/com/yicamalt/live/LiveSessionTypes.kt
  - app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt
  - app/src/main/java/com/yicamalt/media/AacDecoder.kt
  - app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt
  - app/src/main/java/com/yicamalt/stream/AudioPipeline.kt
  - app/src/main/java/com/yicamalt/stream/FrameAssembler.kt
  - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
  - app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt
  - app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt
  - app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
  - app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
  - app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt
  - app/src/test/java/com/yicamalt/archive/PlaybackControllerTest.kt
  - app/src/test/java/com/yicamalt/archive/SdIndexRepositoryTest.kt
  - app/src/test/java/com/yicamalt/live/QualityControllerTest.kt
  - app/src/test/java/com/yicamalt/stream/AudioPipelineTest.kt
  - app/src/test/java/com/yicamalt/stream/FrameAssemblerTest.kt
  - app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
  - app/src/test/java/com/yicamalt/ui/live/CalendarGridTest.kt
  - docs/development-plan.xml
  - docs/knowledge-graph.xml
  - docs/PROJECT_STATUS.md
  - docs/verification-plan.xml
  - tools/check-release-apk.ps1
findings:
  critical: 0
  warning: 2
  info: 3
  total: 5
status: issues_found
---

# Phase 07: Code Review Report

**Reviewed:** 2026-11-18T18:25:59Z
**Depth:** standard
**Files Reviewed:** 30
**Status:** issues_found

## Summary

Reviewed all 30 source files changed in phase 07 (regression fixes), including the in-scope uncommitted
`TutkCameraLink.kt` WIP (`-20013/-20014` drop-rule change), read-only. Cross-checked the git history against
the phase base `d5dd6a3`: commits `77dde98` and `0480d41` made **opposite** audio-decoder changes. `77dde98`
introduced native ADTS decoding (`KEY_IS_ADTS=1`, full ADTS frames, no csd-0); `0480d41` then reverted the code
to raw-AU decoding (`KEY_IS_ADTS=0`, csd-0, header stripped) while **leaving every descriptive comment,
test name and verification record claiming the native-ADTS fix**. `:app:testDebugUnitTest --offline` exits 0,
so the code is internally consistent — the defect is that the shipped GRACE artifacts describe an
implementation that is not present, and the phase-7 verification gate is marked `passed` on that false
evidence.

The remaining changes (IOCTRL buffer 8192, calendar past-day enablement, timeline auto-seek, `resetPipeline()`
hook, `FrameAssembler.recoverGap()`, launcher icons, version bump 1.0.2) are internally consistent and covered
by tests. `resetPipeline()` is safe to call from the archive coroutine: `FramePipeline.reset()` and
`onVideoUnit()` are both `@Synchronized`.

## Narrative Findings (AI reviewer)

## Warnings

### WR-01: Live-audio implementation contradicts its own module contracts, test, and change summaries

**File:** `app/src/main/java/com/yicamalt/media/AacDecoder.kt:4,45,84-86,184-186`;
`app/src/main/java/com/yicamalt/stream/AudioPipeline.kt:4,24,129-131`;
`app/src/test/java/com/yicamalt/stream/AudioPipelineTest.kt:34,278`

**Issue:** The narrative claims native ADTS decoding; the code does the opposite. Concretely:

- `AacDecoder.kt:45` — CHANGE_SUMMARY says *"configure MediaCodec with KEY_IS_ADTS=1 for native ADTS decoding"*,
  but line 186 sets `format.setInteger(MediaFormat.KEY_IS_ADTS, 0)` and line 184 still supplies `csd-0`.
- `AacDecoder.kt:4,84-85` — PURPOSE and the `onAacFrame` contract say *"ADTS AAC access unit / ADTS AAC frame"*,
  but the frame actually received is a header-stripped raw access unit.
- `AudioPipeline.kt:24` — CHANGE_SUMMARY says *"deliver complete ADTS frames to the sink so MediaCodec decodes
  with native KEY_IS_ADTS=1"*, but line 130 is `data.copyOfRange(off + f.headerLen, off + f.frameLen)` — the
  7-byte ADTS header is explicitly removed (only the local variable was renamed `raw` → `rawAu`).
- `AudioPipelineTest.kt:34` — test CHANGE_SUMMARY says *"test full ADTS frame delivery"*, while its assertion
  at line 278 is `assertArrayEquals(plain.copyOfRange(7, plain.size), raw)` — proving the sink receives the
  **stripped** frame.

Commit history confirms the contradiction: `77dde98` set `KEY_IS_ADTS=1` and passed the full frame
(`data.copyOfRange(off, off + f.frameLen)`); `0480d41` reverted both code lines to raw-AU mode but left all
the `77dde98` prose intact. In a GRACE project these contracts are load-bearing; a future agent reading them
will "fix" the decoder back to `KEY_IS_ADTS=1`, reintroducing whatever caused the revert. The H-R3 fix as
*described* is not in the shipped code.

**Fix:** Decide which mode ships and make code + contracts + test agree. Given `0480d41` deliberately
reverted and the suite passes, the low-risk fix is to keep raw-AU mode and rewrite the documentation:
```kotlin
// AacDecoder.kt:45 (replace)
//   LAST_CHANGE: v0.1.2 - raw AAC access units + csd-0 with KEY_IS_ADTS=0; AudioTrack CONTENT_TYPE_MUSIC
//     and pcmBytesWritten/Dropped tracking retained from v0.1.1 (H-R3).
// AudioPipeline.kt:4,24 and AudioPipelineTest.kt:34, test name -> "raw AU delivery" ...
// AacDecoder.kt:4,84-85: "raw AAC access unit (ADTS header stripped by AudioPipeline)"
```
If native ADTS is genuinely required, instead remove `csd-0`, set `KEY_IS_ADTS=1`, pass
`data.copyOfRange(off, off + f.frameLen)`, and change the test assertion to the full `plain` array — then
re-run device verification H-R3.

### WR-02: Phase-7 verification gate and GRACE status records assert a device-verified fix that the code does not contain

**File:** `docs/verification-plan.xml:1893-1908` (`Gate-GSD-Phase-7`, `STATUS="passed"`, `evidence-1`);
`docs/knowledge-graph.xml:471` (`change-summary phase="07-regression-fixes"`);
`docs/PROJECT_STATUS.md:22-28` (section 0.11, item 1 and the "Закрытые гэпы" row)

**Issue:** The verification artifacts state as passed evidence: *"AacDecoder KEY_IS_ADTS=1 + ... + raw ADTS
frames from AudioPipeline fixes LIVE-06 (H-R3); confirmed working by user on physical Xiaomi Mi 9"*, and
PROJECT_STATUS 0.11/1 states *"в AacDecoder.kt декодер MediaCodec сконфигурирован с KEY_IS_ADTS = 1 без
csd-0"*. None of this matches the shipped code (`KEY_IS_ADTS=0`, csd-0 present, header stripped — see WR-01).
The gate is therefore `passed` on evidence that describes a reverted implementation. Per AGENTS.md
("Verification Is a First-Class Artifact", "Logs are evidence"), this is a verification defect: H-R3 cannot be
considered closed on this record, and the release-1.0.2 status summary is factually wrong.

**Fix:** Correct `evidence-1`, the knowledge-graph change-summary and PROJECT_STATUS §0.11 to the actual
configuration after resolving WR-01, then re-verify H-R3 against the 1.0.2 APK on the Xiaomi Mi 9 and only
then keep `STATUS="passed"`. Do not leave a `passed` gate whose evidence cites a constant absent from the code.

## Info

### IN-01: Stale fallback version in the release APK checker

**File:** `tools/check-release-apk.ps1:62-63`

**Issue:** Auto-detection from `app/build.gradle.kts` correctly yields 1.0.2/10002, but the fallback is
hard-coded to `'1.0.1'` / `'10001'`. If the gradle file is unavailable, the checker validates against the
previous release and would reject a correct 1.0.2 APK. The CHANGE_SUMMARY at line 24 also still says v1.0.1.

**Fix:** Update the fallback to `1.0.2`/`10002` (or fail with an explicit "cannot determine expected version"
error when the gradle file is missing) and bump the checker's CHANGE_SUMMARY to v1.0.2.

### IN-02: ArchiveCoordinator VERSION header disagrees with its own LAST_CHANGE

**File:** `app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt:2` vs `:64`

**Issue:** `// VERSION: 0.5.2` but `// LAST_CHANGE: v0.5.3 - auto-seek ...`. The test file
`ArchiveCoordinatorTest.kt:2` is at `0.5.2` with a matching `v0.5.2` LAST_CHANGE, so the production module
header is the stale one.

**Fix:** Set the production module header to `// VERSION: 0.5.3`.

### IN-03: recoverGap() over-counts `dropped` for stale non-consecutive pending units

**File:** `app/src/main/java/com/yicamalt/stream/FrameAssembler.kt:129-131`

**Issue:** `dropped += gap` accounts for the whole skipped sequence `(lastSeq, lastSeq + minDistance)` but
only `pendingBySeq.remove(target...)` removes the chosen decode point; other pending units that fall inside
the skipped range (e.g. pending `{12,14,15,16}` with `lastSeq=10` recovers from 14 and counts 11/12/13, yet
unit 12 stays) are later removed again by the key-frame stale sweep at lines 81-89, incrementing `dropped`
a second time. Emission order is unaffected; only the `dropped` stat is wrong.

**Fix:** When applying the gap, also remove pending units whose `(seq - lastSeq) and SEQ_MASK` lies in
`1 until minDistance`, or subtract the number of such units from `gap` before `dropped += gap`.

---

_Reviewed: 2026-11-18T18:25:59Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
