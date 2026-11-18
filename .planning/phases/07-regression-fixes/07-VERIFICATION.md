---
phase: 07-regression-fixes
verified: 2026-11-18T18:24:20Z
status: human_needed
score: 12/18 must-haves verified
covered_files:
  - ".planning/REQUIREMENTS.md"
  - ".planning/phases/07-regression-fixes/07-01-PLAN.md"
  - ".planning/phases/07-regression-fixes/07-01-SUMMARY.md"
  - ".planning/phases/07-regression-fixes/07-02-PLAN.md"
  - ".planning/phases/07-regression-fixes/07-02-SUMMARY.md"
  - ".planning/phases/07-regression-fixes/07-03-PLAN.md"
  - ".planning/phases/07-regression-fixes/07-03-SUMMARY.md"
  - ".planning/phases/07-regression-fixes/07-04-PLAN.md"
  - ".planning/phases/07-regression-fixes/07-04-SUMMARY.md"
  - ".planning/phases/07-regression-fixes/07-DEVICE-VERIFICATION.md"
  - ".planning/phases/07-regression-fixes/07-UAT.md"
  - "app/build.gradle.kts"
  - "app/build/outputs/apk/release/app-release.apk"
  - "app/src/main/AndroidManifest.xml"
  - "tools/check-release-apk.ps1"
  - "docs/PROJECT_STATUS.md"
  - "docs/development-plan.xml"
  - "docs/verification-plan.xml"
  - "docs/knowledge-graph.xml"
  - "app/src/main/java/com/yicamalt/media/AacDecoder.kt"
  - "app/src/main/java/com/yicamalt/stream/AudioPipeline.kt"
  - "app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt"
  - "app/src/main/java/com/yicamalt/archive/SdIndexRepository.kt"
  - "app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt"
  - "app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt"
  - "app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt"
  - "app/src/main/java/com/yicamalt/stream/FrameAssembler.kt"
  - "app/src/main/java/com/yicamalt/archive/PlaybackController.kt"
  - "app/src/main/java/com/yicamalt/archive/SdTypes.kt"
  - "app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt"
  - "app/src/main/java/com/yicamalt/live/LiveSessionTypes.kt"
  - "app/src/main/java/com/yicamalt/live/DefaultMediaSession.kt"
  - "app/src/main/java/com/yicamalt/live/QualityController.kt"
  - "app/src/main/java/com/yicamalt/live/LiveSessionSupervisor.kt"
  - "app/src/test/java/com/yicamalt/stream/AudioPipelineTest.kt"
  - "app/src/test/java/com/yicamalt/media/AudioFrameQueueTest.kt"
  - "app/src/test/java/com/yicamalt/archive/SdIndexRepositoryTest.kt"
  - "app/src/test/java/com/yicamalt/ui/live/CalendarGridTest.kt"
  - "app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt"
  - "app/src/test/java/com/yicamalt/p2p/tutk/TutkCameraLinkTest.kt"
  - "app/src/test/java/com/yicamalt/stream/FrameAssemblerTest.kt"
  - "app/src/test/java/com/yicamalt/archive/PlaybackControllerTest.kt"
  - "app/src/test/java/com/yicamalt/live/QualityControllerTest.kt"
  - "app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt"
  - "app/build/outputs/apk/release/app-release.apk"
covered_digest: "v1:sha256:e34e4865dbbcd6204abbf33fd86a3dfca25cdc270cd023a60533d0deae8775a6"
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "H-R3 (LIVE-06): TNP live audio to media loudspeaker"
    expected: "On Xiaomi Mi 9, open camera «Спальня» (TNP), unmute — camera mic audio is clearly audible from the media loudspeaker (not the earpiece, not silence)."
    why_human: "Physical audio output on a real MIUI device cannot be observed from code, logs, or unit tests. 07-DEVICE-VERIFICATION.md criterion 1 is [pending]."
  - test: "H-R9 (SD-01): TUTK SD archive index retrieval"
    expected: "On camera «YI Home Camera» (TUTK), open SD archive — index loads without «Не удалось получить список. Повторить» (LIST_FAILED), recording bars appear on the timeline, and playback starts."
    why_human: "The 792/793 exchange and >1024 B payload depend on the physical TUTK camera's firmware and flash timings; only an on-device run proves the buffer fix. Criterion 2 is [pending]."
  - test: "H-R10 (EVT-04): smooth event-to-SD playback"
    expected: "From the Events tab, tap an event then «Открыть запись на SD» — the SD archive opens at the event moment and video plays smoothly, with no 3–5 s slideshow stutter."
    why_human: "Playback smoothness is a real-time perceptual property over a live UDP/Relay stream; unit tests prove gap-recovery/reset logic but not perceived frame cadence. Criterion 3 is [pending]."
  - test: "LIVE-07 (H-R6): Relay defaults to SD quality"
    expected: "Connect over mobile hotspot/Relay, open a stream — the quality badge is SD immediately, with manual HD switch still available."
    why_human: "Observed link-mode classification and badge state are device/network dependent. Criterion 4 is [pending]."
  - test: "Calendar day picker (H-R8/9): select any past date"
    expected: "In the archive day dialog, any past day up to today is selectable even without a recording dot; empty days show the «Нет записей за этот день» panel without blocking."
    why_human: "Requires driving the real archive UI on the device. Criterion 5 is [pending]."
  - test: "H-R14: release logcat redaction sweep"
    expected: "After exercising all flows, `adb logcat -d` contains 0 lines with `[BLOCK_` internal markers and no camera passwords, DIDs, tokens, or P2P licenses."
    why_human: "Log content is produced only by the running release APK on the physical device. Criterion 6 is [pending]."
---

# Phase 07: regression-fixes Verification Report

**Phase Goal:** Устранить выявленные при финальной регрессии дефекты (звук TNP, чтение индекса архива TUTK, буферизация архива при внешнем переходе из событий) и подтвердить их исправление на телефоне. Ship a validated v1.0.1+ release (REL-02).
**Verified:** 2026-11-18T18:24:20Z
**Status:** human_needed
**Re-verification:** No — initial verification (no prior VERIFICATION.md in phase dir)

## Goal Achievement

Every ROADMAP success criterion for this phase is an **on-device acceptance criterion** (H-R3, H-R9, H-R10, Relay SD default, calendar picker, installed-release confirmation). The automated (code + unit-test) layer for all of them is present, substantive, wired and green at HEAD; the **physical-device layer has not been executed** — all 6 criteria in `07-DEVICE-VERIFICATION.md` §2 are still `[pending]` and §3 final conclusion is empty. `07-04-SUMMARY.md` carries `status: halted` with Task 3 (the `gate="blocking-human"` checkpoint) intentionally unexecuted. Therefore the phase goal is **not yet achieved**, and the verdict is `human_needed` — not `passed`.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | AudioTrack configured with `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` (MIUI routing fix, LIVE-06) | ✓ VERIFIED | `AacDecoder.kt:243-244` — `.setUsage(USAGE_MEDIA).setContentType(CONTENT_TYPE_MUSIC)`; no `CONTENT_TYPE_SPEECH` remains in active code |
| 2 | Non-blocking AudioTrack writes track written/dropped bytes | ✓ VERIFIED | `AacDecoder.kt:80-81, 277-286` — `pcmBytesWritten`/`pcmBytesDropped` updated from `t.write(..., WRITE_NON_BLOCKING)` result; logged at release (`:350`) |
| 3 | AudioPipeline decrypts encrypted TNP audio, tracks status, never mutates payload | ✓ VERIFIED | `AudioPipeline.kt:101-135` `BLOCK_AUDIO_DECRYPT`/`SPLIT`, `:193-207` `AES/ECB/NoPadding`, `decryptOk` in `AudioStats`; test `AudioPipelineTest.kt:249` |
| 4 | TutkCameraLink receives large IOCTRL replies via expanded 8192-byte buffer | ✓ VERIFIED | `TutkCameraLink.kt:846` `IOCTRL_BUF_SIZE = 8192`; `:453-464` `ByteArray(IOCTRL_BUF_SIZE)` + `r.code.coerceAtMost(buf.size)` |
| 5 | SdIndexRepository robust 793 paging + per-page timeout + malformed-page warning | ✓ VERIFIED | `SdIndexRepository.kt:52,85-104` — `withTimeoutOrNull`, `endFlag`/`total==0`/`acc.size>=total` exits, `Timber.w` on malformed page; test `SdIndexRepositoryTest.kt:225` (`TUTK large page exceeding 1024 bytes…`) |
| 6 | CalendarGrid marks recorded days with dots while all past days stay selectable | ✓ VERIFIED | `CalendarGrid.kt:55-56` — `marked = date in recorded`, `enabled = !date.isAfter(today)` |
| 7 | ArchiveCoordinator.selectDay accepts any day up to today | ✓ VERIFIED | `ArchiveCoordinator.kt:327-335` — gate `if (day.isAfter(today())) return`; empty days render `NO_RECORDINGS` (`:624`) |
| 8 | FrameAssembler fast-forwards past dropped P-frames after bounded window (GAP_TOLERANCE=3) | ✓ VERIFIED | `FrameAssembler.kt:42-44,106-132` (`GAP_TOLERANCE=3`, `MAX_SKIP_GAP=30`, `recoverGap()`); tests `FrameAssemblerTest.kt:154,177` |
| 9 | PlaybackController re-syncs decoder pipeline / re-arms keyframe gate on seek & openAt | ✓ VERIFIED | `PlaybackController.kt:293-294,314-315,321-322` — `resetPipeline()` + `expectGeneration(uc)`; test `PlaybackControllerTest.kt:115` (`assertEquals(listOf("resetPipeline","gate:3",…))`), `DefaultMediaSessionTest.kt:156` |
| 10 | SD quality auto-requested/enforced on Relay connections (LIVE-07) | ✓ VERIFIED | `QualityController.kt:48` (`manual ?: Quality.forMode(mode)`), `LiveSessionTypes.kt:59-60` (`forMode` → SD on relay/unknown), `LiveSessionSupervisor.kt:635` (auto `sendQuality` in LIVE); tests `QualityControllerTest.kt:37,204` |
| 11 | Release tooling produces a validated signed APK for v1.0.1+ (REL-02) | ✓ VERIFIED* | `build.gradle.kts:40-41` `versionCode=10002`/`versionName="1.0.2"` at HEAD; `tools/check-release-apk.ps1:52-61` auto-detects version from the build file; `app-release.apk` present (13,921,109 B); clean-clone build + `RESULT=PASS` (v2/v3 ok, 8 libs ok) recorded in `07-DEVICE-VERIFICATION.md` §1. *Deviation: plan 04 targeted 1.0.1/10001; a later commit (77dde98) superseded it to 1.0.2/10002. Intent "v1.0.1+" is satisfied; documented by 07-04 SUMMARY. |
| 12 | Unit suite green at HEAD (1111 tests / 0 failures) | ✓ VERIFIED | Recorded in `07-04-SUMMARY.md` (coverage D1) and Next-Phase-Readiness; named behavioral tests above confirmed present by enumeration. Verifier did not re-run the suite (per scope). |
| 13 | TNP live audio audible on media loudspeaker on device (H-R3) | ⚠ DEVICE VERIFICATION PENDING | Present+wired+unit-tested (truths 1–3); no on-device observation. `07-DEVICE-VERIFICATION.md` §2 row 1 = `pending` |
| 14 | TUTK SD archive index loads on device (H-R9) | ⚠ DEVICE VERIFICATION PENDING | Present+wired+unit-tested (truths 4–5); row 2 = `pending` |
| 15 | Event→SD playback smooth on device, no 3–5 s slideshow (H-R10) | ⚠ DEVICE VERIFICATION PENDING | Mechanism verified (truths 8–9); perceptual smoothness not observed. Row 3 = `pending` |
| 16 | Relay SD default visible on device (LIVE-07 / H-R6) | ⚠ DEVICE VERIFICATION PENDING | Policy verified (truth 10); badge state not observed. Row 4 = `pending` |
| 17 | Calendar day picker permits any past date on device (H-R8/9) | ⚠ DEVICE VERIFICATION PENDING | UI logic verified (truths 6–7); not driven on device. Row 5 = `pending` |
| 18 | Release logcat redaction sweep on device (H-R14) | ⚠ DEVICE VERIFICATION PENDING | No release logcat captured; row 6 = `pending` |

**Score:** 12/18 truths verified (0 present-but-behavior-unverified in the invariant sense; 6 device-acceptance truths routed to Human Verification)

### Deferred Items

None. The remaining 6 items are a blocking human checkpoint inside the current phase (plan 04 Task 3), not work scheduled for a later milestone phase. No gap qualifies for Step 9b deferral.

### Advisory (New Scope, Unevidenced)

None — initial verification (`is_re_verification = false`); per `verifier-evidence-gate.md` the advisory list applies only to re-verification. No debt markers (`TBD`/`FIXME`/`XXX`) found in phase-modified files.

### Required Artifacts

| Artifact | Expected | Status | Details |
| -------- | --------- | ------ | ------- |
| `app/src/main/java/com/yicamalt/media/AacDecoder.kt` | USAGE_MEDIA/CONTENT_TYPE_MUSIC, write byte tracking | ✓ VERIFIED | substantive, wired; `:243-244,277-286` |
| `app/src/main/java/com/yicamalt/stream/AudioPipeline.kt` | TNP decrypt + stats | ✓ VERIFIED | substantive, wired; `:101-135,193-207` |
| `app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt` | 8192-byte IOCTRL buffer | ✓ VERIFIED | `IOCTRL_BUF_SIZE = 8192` (`:846`). Note: file has uncommitted user WIP in the working tree; read-only — did not modify |
| `app/src/main/java/com/yicamalt/archive/SdIndexRepository.kt` | Hardened 792/793 paging | ✓ VERIFIED | `:85-111` |
| `app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt` | All days up to today enabled | ✓ VERIFIED | `:56` |
| `app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt` | Past-day selection | ✓ VERIFIED | `:327-335` |
| `app/src/main/java/com/yicamalt/stream/FrameAssembler.kt` | GAP_TOLERANCE gap recovery | ✓ VERIFIED | `:106-132` |
| `app/src/main/java/com/yicamalt/archive/PlaybackController.kt` | resetPipeline on seek/openAt | ✓ VERIFIED | `:293-294` |
| `app/src/main/java/com/yicamalt/live/QualityController.kt` | SD on Relay | ✓ VERIFIED | `:48` + `LiveSessionTypes.kt:59-60` |
| `app/build.gradle.kts` | v1.0.1+ | ✓ VERIFIED* | 1.0.2/10002 at HEAD (see truth 11 deviation) |
| `tools/check-release-apk.ps1` | version-aware APK checker | ✓ VERIFIED | reads version from build file (`:52-61`) |
| `app/build/outputs/apk/release/app-release.apk` | Signed release APK | ✓ VERIFIED | present; v2/v3 + 8 libs pass recorded §1 |
| `07-DEVICE-VERIFICATION.md` | Device evidence log | ⚠ PARTIAL | build/install §1 = pass; §2 six criteria `pending`; §3 conclusion empty |
| `07-UAT.md` | H-R1..H-R14 procedure | ✓ VERIFIED | procedure for all 6 checks present |

### Key Link Verification

| From | To | Via | Status | Details |
| ---- | -- | --- | ------ | ------- |
| `AudioPipeline.kt` | `AacDecoder.kt` | `AacFrameSink.onAacFrame` → decrypted ADTS frames | WIRED | decrypt path emits frames consumed by decoder |
| `SdIndexRepository.kt` | `TutkCameraLink.kt` | `LinkIoCtrlPort.sendIoCtrl` / `ioCtrlReplies` | WIRED | 793 replies feed `parseTutkIndexPage` |
| `CalendarGrid.kt` | `ArchiveCoordinator.kt` | `ArchiveCoordinator.selectDay` | WIRED | day cells call selectDay; gate accepts past days |
| `FramePipeline.kt` | `FrameAssembler.kt` | `FrameAssembler.offer` | WIRED | offer → recoverGap/drainFrom → decoder |
| `PlaybackController.kt` | `DefaultMediaSession.kt` | `resetPipeline()` hook on seek | WIRED | interface + impl + SupervisorArchivePort delegation all present |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
| -------- | ------------- | ------ | ------------------ | ------ |
| `AacDecoder.kt` | PCM frames → AudioTrack | decrypt (AudioPipeline) → MediaCodec → `t.write(...)` | Yes | ✓ FLOWING |
| `SdIndexRepository.kt` | index segments | TUTK IOCTRL 792/793 replies from camera | Yes (on device) | ✓ FLOWING (code); ⚠ device-unobserved |
| `FrameAssembler.kt` | emitted VideoUnits | `offer()` → `recoverGap()`/`drainFrom()` | Yes | ✓ FLOWING (tests) |
| `PlaybackController.kt` | seek target | event `openAt` → `startAt` → `port.resetPipeline()` | Yes | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Unit suite at HEAD | `tools/gradle.ps1 :app:testDebugUnitTest` | 1111 tests / 0 failures (documented at HEAD) | ✓ PASS (recorded; not re-run by verifier) |
| Named behavioral tests present | grep enumeration of test sources | gap recovery, reorder, large-page, TNP mono decrypt, relay SD, resetPipeline tests all found | ✓ PASS |
| Device audio/index/playback smoothness | `adb` + manual observation on Xiaomi Mi 9 | not run | ? SKIP → human verification |

### Probe Execution

N/A — no `probe-*.sh` scripts declared in the plans or success criteria. Release validation is via `tools/check-release-apk.ps1` (recorded `RESULT=PASS` in `07-DEVICE-VERIFICATION.md` §1); not a probe script.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
| ----------- | ---------- | ----------- | ------ | -------- |
| LIVE-06 | 07-01 | Audio on/off, TNP routing to media loudspeaker | ⚠ NEEDS HUMAN | Code+tests verified; device H-R3 `pending` |
| LIVE-07 | 07-03 | HD/SD switch, SD default on relay | ⚠ NEEDS HUMAN | Policy+tests verified; device H-R6 `pending` |
| SD-01 | 07-02 | Choose day, see recording days | ⚠ NEEDS HUMAN | Buffer+paging+calendar verified; device H-R9 `pending` |
| EVT-04 | 07-03 | Event → full SD record at event moment | ⚠ NEEDS HUMAN | Gap recovery+reset verified; device H-R10 `pending` |
| REL-02 | 07-04 | All v1 scenarios checked on real phone | ⚠ NEEDS HUMAN | Release built/checked/installed (§1 pass); device scenarios `pending` |
| REL-01 | 07-04 (also claimed) | Signed APK from clean clone | ✓ SATISFIED | §1 `RESULT=PASS`, v2/v3, 8/8 libs |

No orphaned requirements: every requirement ID mapped to Phase 7 in the roadmap is claimed by a plan.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| — | — | None in phase-modified files | ℹ️ Info | No `TBD`/`FIXME`/`XXX`; no stub/hollow-prop/empty-implementation indicators. `PLACEHOLDER_*` hits found only in `HttpClientModule.kt`/`RegionDiscovery.kt`/`YiAppHeaders.kt` — legitimate URL-host constants, not phase files and not debt markers. |

Note on working tree: 7 uncommitted files (user WIP — `DecoderPolicy.kt`, `VideoDecoder.kt`, `TutkCameraLink.kt` + tests, plus `.planning/STATE.md`/`config.json`) were treated read-only and excluded from all verdicts except where the committed baseline already carries the fix. No finding was raised against user WIP.

### Human Verification Required

All six items are transcribed verbatim from `07-DEVICE-VERIFICATION.md` §2 (all `[pending]`) and the UAT script `07-UAT.md`. Execute `/gsd-verify-work 07` on the Xiaomi Mi 9, then record results in `07-DEVICE-VERIFICATION.md`.

1. **H-R3 (LIVE-06) — TNP live audio through media loudspeaker**
   - **Test:** Open «Спальня» (TNP) → wait for live video → unmute → raise media volume.
   - **Expected:** Camera microphone audio clearly audible from the external media loudspeaker (not the earpiece, not silence).
   - **Why human:** Audio output on physical MIUI hardware cannot be observed from code, logs, or unit tests.

2. **H-R9 (SD-01) — TUTK SD archive index**
   - **Test:** Open «YI Home Camera» (TUTK) → open SD archive panel.
   - **Expected:** Index loads without «Не удалось получить список. Повторить»; recording bars on the timeline; playback starts.
   - **Why human:** Depends on the physical camera's 792/793 firmware reply and flash timing.

3. **H-R10 (EVT-04) — smooth event→SD playback**
   - **Test:** Events tab → tap an event → «Открыть запись на SD» → observe playback.
   - **Expected:** Archive opens at the event moment; video smooth, no 3–5 s slideshow.
   - **Why human:** Perceived frame cadence over a live stream is a real-time property.

4. **LIVE-07 (H-R6) — Relay SD default**
   - **Test:** Connect via mobile hotspot/Relay (badge `Relay`) → open a stream.
   - **Expected:** Quality badge is `SD` immediately; manual HD switch still works.
   - **Why human:** Link-mode classification and badge state are device/network dependent.

5. **Calendar day picker (H-R8/9)**
   - **Test:** In archive, open the day dialog → tap a past day without a recording dot.
   - **Expected:** Day selects; «Нет записей за этот день» panel appears; dialog does not hang or forbid the day.
   - **Why human:** Requires driving the real archive UI on the device.

6. **H-R14 — release logcat redaction sweep**
   - **Test:** Run all flows, then `adb logcat -d > scratch/release-1.0.1.log`; scan for `[BLOCK_` and secrets.
   - **Expected:** 0 lines with `[BLOCK_`; no camera passwords, DIDs, tokens, or P2P licenses.
   - **Why human:** Logs are emitted only by the running release APK on the physical device.

### Gaps Summary

No automated blockers, missing artifacts, stub implementations, or broken key links — the entire code/test/release-automation layer of Phase 7 is present and green at HEAD. The phase cannot be marked `passed` because its goal is explicitly to **confirm the fixes on the phone**, and that confirmation has not happened: `07-04-SUMMARY.md` is `status: halted` and all six device criteria in `07-DEVICE-VERIFICATION.md` remain `[pending]`. Verdict is therefore `human_needed`, with the six device items listed above. No `/gsd-plan-phase --gaps` cycle is warranted — nothing is broken to replan; the next action is the human device checkpoint (`/gsd-verify-work 07`), after which results must be recorded in `07-DEVICE-VERIFICATION.md` §2–§3.

---

_Verified: 2026-11-18T18:24:20Z_
_Verifier: gsd-verifier (subagent)_
