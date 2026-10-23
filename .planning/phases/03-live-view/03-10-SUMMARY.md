---
phase: 03-live-view
plan: 10
subsystem: live-view
tags: [tdd, viewmodel, media-session, badge, audio, quality, snapshot, lifecycle, grace]
requires: ["03-01", "03-06", "03-07", "03-08", "03-09"]
provides:
  - "DefaultMediaSession : MediaSession (key-frame tee -> FramePipeline, AudioPipeline, setMuted, resetPipeline, stats, audioAvailability)"
  - "LiveViewModel (ui, events; open, closeScreen, onStop, onStart, connect, retry, toggleMute, toggleQuality, toggleDebug, attachSurface, detachSurface, saveSnapshot)"
  - "LiveViewUi, LiveBadge, BadgeTone, LiveEvent, LiveViewTexts, badgeFor, LiveEnvironment, LiveVideoOutput, LiveAudioOutput"
  - "LiveFakes: keyUnit / pUnit / aacUnit / adtsFrame synthetic unit builders"
affects: [03-11]
tech-stack:
  added: []
  patterns:
    - "VM-owned AudioSwitch (AacFrameSink with a @Volatile target) survives reconnects; the session factory reads the current muted value"
    - "per-open CoroutineScope(viewModelScope + SupervisorJob) owns collectors and the 1 s ticker; closeScreen cancels it"
    - "tests wrap runTest so the view model is closed inside the body (open tickers on the shared scheduler would make runTest's final drain spin forever)"
key-files:
  created:
    - app/src/main/java/com/yicamalt/live/DefaultMediaSession.kt
    - app/src/test/java/com/yicamalt/live/DefaultMediaSessionTest.kt
    - app/src/main/java/com/yicamalt/ui/live/LiveViewModel.kt
    - app/src/test/java/com/yicamalt/ui/live/LiveViewModelTest.kt
  modified:
    - app/src/test/java/com/yicamalt/live/LiveFakes.kt
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "Re-open of the same camera while a supervisor exists only updates the title (rotation / recomposition must keep the session, H-L6); another camera closes the screen first"
  - "Waiting caused by WRONG_PASSWORD or KICK shows 'Подключение…' (NEUTRAL); NETWORK waits and Connecting(n>1) show 'Повтор (n/5)' (WARN)"
  - "Kicked badge tone ERROR; Exhausted message = linkErrorMessage(lastError) when known; decoder problems (NoDecoder / Unsupported / Failed) shown as message while Live"
  - "soundVisible = Live and audio availability was true at least once during this open (sticky across reconnects)"
  - "Audio output release stays synchronous on Main (AacDecoder.release waits <= 1 s), like the probe's decoder release"
metrics:
  duration: 45min
  completed: 2026-10-23
  tasks: 2
  files: 7
---

# Phase 3 Plan 10: DefaultMediaSession + LiveViewModel Summary

Production MediaSession (FramePipeline + AudioPipeline behind a key-frame tee) and the live-screen view model over the real LiveSessionSupervisor: auto-connect on open, D-04 badge table, muted-by-default sound with a VM-owned audio switch, HD/SD through `setQuality`, snapshot toasts, background stop / 5-minute resume, screen-left release, and a value-free debug line, all proven in virtual time.

## Tasks

| Task | Name | Commits |
| ---- | ---- | ------- |
| 1 | DefaultMediaSession (TDD) | 7ac0175 (RED), eb11198 (GREEN) |
| 2 | LiveViewModel + badge mapping (TDD) | 62bb4c4 (RED), 5c908ba (GREEN) |

## Verification

- RED 1: `--tests DefaultMediaSessionTest` -> 7 tests, 7 failed. GREEN 1: `--tests "com.yicamalt.live.*"` BUILD SUCCESSFUL, exit 0.
- RED 2: `--tests LiveViewModelTest` -> 23 tests, 23 failed. GREEN 2: `--tests "com.yicamalt.ui.live.*"` 23 + 26 + 6 passed.
- `:app:testDebugUnitTest :app:assembleDebug` -> BUILD SUCCESSFUL, `--- gradle exit=0`; whole suite 686 tests, 1 skipped (RealLogScanTest without a log), 0 failures.
- Greps: DefaultMediaSessionTest `@Test` = 7 (>= 5); `pipeline.onVideoUnit` = 1 line; LiveViewModelTest `@Test` = 23 (>= 16); "Повтор (" = 1, "Подключение…" = 1; the six required START_BLOCK names = 6; START_BLOCK_ = END_BLOCK_ = 7; LiveProbeViewModel.kt / LiveProbeScreen.kt unchanged; no block comments.
- RedactionScanner over every test's captured log + every observed `LiveViewUi.toString()` with LIVE_SECRETS (device id, DID, passwords, InitString, license): clean.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing functionality] Decoder reset must re-arm the pipeline**
- **Found during:** Task 1
- **Issue:** VideoDecoder's contract says the owner calls `FramePipeline.reset()` on each `resetEvents` (the probe VM does it). The planned `LiveVideoOutput` had no `resetEvents` and `DefaultMediaSession` no reset hook, so after a decoder reset the pipeline would not wait for the next key frame.
- **Fix:** `LiveVideoOutput.resetEvents`, `DefaultMediaSession.resetPipeline()`, VM collects reset events -> `current?.resetPipeline()`; tests in both suites.
- **Commits:** eb11198, 5c908ba

**2. [Rule 1 - Bug] Re-open of the same camera would restart the session**
- **Found during:** Task 2
- **Issue:** The screen (03-11) calls `open()` from composition; with "re-open = closeScreen first" a rotation would drop and reconnect the stream.
- **Fix:** same deviceId while a supervisor exists only updates the title; another camera still closes first. Test `re-open of the same camera keeps the session, another camera restarts`.
- **Commit:** 5c908ba

**3. [Rule 3 - Blocking] runTest hung with an open view model**
- **Found during:** Task 2 GREEN (first run hit the 10 min timeout)
- **Issue:** the per-open 1 s ticker and supervisor loops run on the test scheduler (Main = test dispatcher); runTest's final drain kept advancing virtual time.
- **Fix:** `vmTest {}` wrapper closes the view model inside the test body.
- **Commit:** 5c908ba

**4. [Docs] Verification marker tag aligned**
- verification-plan listed `[LiveView][quality][BLOCK_QUALITY_SWITCH]`; the plan's phase rules use `[LiveView][vm][BLOCK_*]`. Marker changed to `[LiveView][vm][BLOCK_QUALITY_SWITCH]` and BLOCK_OPEN_LIVE / BLOCK_TOGGLE_AUDIO / BLOCK_SNAPSHOT markers added.

Extra tests beyond the plan: `reconnect after unmute keeps sound on`, `decoder reset re-arms the media session pipeline`, `resetPipeline re-arms the key-frame gate`, `unmuted at creation delivers at once`.

## Known Stubs

None. The Compose screen that renders `LiveViewUi` is plan 03-11.

## Self-Check: PASSED
