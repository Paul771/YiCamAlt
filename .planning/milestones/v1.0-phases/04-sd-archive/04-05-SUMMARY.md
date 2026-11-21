---
phase: 04-sd-archive
plan: 05
subsystem: sd-archive
tags: [archive, playback, state-machine, generation-gate, media-session, coroutines, tdd]
requires:
  - phase: 04-sd-archive
    provides: FrameObservation / StreamMode (04-00), ArchiveLinkPort, SdTimeline, PlaybackCommands, FakeArchivePort (04-03)
provides:
  - FrameAssembler.expectGeneration / FramePipeline.expectGeneration (useCount low-byte equality gate)
  - MediaSession.expectGeneration / lastFrame (default bodies) and DefaultMediaSession implementation
  - PlaybackController + PlaybackState / PauseReason / FailReason
  - ArchiveLinkPort.framesIn() (new port member, see deviations)
affects: [04-04, 04-06, 04-08]
tech-stack:
  added: []
  patterns:
    - "Gate before send: port.expectGeneration(uc) -> setStreamMode -> IOCTRL commands"
    - "Health-keyed timers: freeze while !linkHealthy(), re-base on health return, liveEpoch re-seek"
    - "Start timeout accumulates only over ticks where framesIn() grew"
key-files:
  created:
    - app/src/main/java/com/yicamalt/archive/PlaybackController.kt
    - app/src/test/java/com/yicamalt/archive/PlaybackControllerTest.kt
  modified:
    - app/src/main/java/com/yicamalt/stream/FrameAssembler.kt
    - app/src/main/java/com/yicamalt/stream/FramePipeline.kt
    - app/src/test/java/com/yicamalt/stream/FrameAssemblerTest.kt
    - app/src/test/java/com/yicamalt/stream/FramePipelineTest.kt
    - app/src/main/java/com/yicamalt/live/LiveSessionTypes.kt
    - app/src/main/java/com/yicamalt/live/DefaultMediaSession.kt
    - app/src/test/java/com/yicamalt/live/DefaultMediaSessionTest.kt
    - app/src/main/java/com/yicamalt/archive/SdTypes.kt
    - app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt
decisions:
  - "ArchiveLinkPort gains abstract framesIn(): Long (units of any generation) for checker addendum 1; 04-04 / 04-06 port implementations must forward MediaSession.framesIn()"
  - "PlaybackController serialises public calls, ticker and liveEpoch collector with one lock instead of dispatching onto the scope, so calls are synchronous and deterministic"
  - "liveEpoch collector compares with the epoch captured at start() (UNDISPATCHED) instead of drop(1), so an early bump is never lost"
  - "A seek already sent on the current epoch (health-back path) is not re-sent by that epoch's collector event (seekEpoch guard)"
  - "On a liveEpoch in Paused / Failed the controller calls expectGeneration(null) (clears, never sets a gate) so the new link shows live video"
  - "goLive while the link is unhealthy or without a useCount: clear gate, mode LIVE, state Live, nothing sent"
  - "pause() while unhealthy changes state only (nothing sent)"
  - "Resolution change across the gate already reaches the decoder with new size + csd; no resetPipeline() call added"
metrics:
  duration: 35min
  completed: 2026-10-30
  tasks: 3
  files: 11
---

# Phase 4 Plan 05: Generation gate, frame observation, playback state machine Summary

The decode path now has the vendor useCount equality gate: after a seek or go-live only units whose useCount low byte equals the command's generation reach the decoder. The media session exposes the last gated frame as `FrameObservation`, so the archive cursor follows recording time. `PlaybackController` is the SD-03 / SD-04 state machine over `ArchiveLinkPort`. It covers seek, pause, resume, «Живой», segment boundary jumps, end of recording, link-health freeze with reconnect re-seek (D-04) and background pause without auto-resume (D-05). All of it is tested on the JVM with virtual time.

## Tasks

| # | Task | Commits |
|---|------|---------|
| 1 | Generation gate in FrameAssembler / FramePipeline | 0fbb62e (RED), 8806552 (GREEN) |
| 2 | MediaSession gate and frame observation | 55cb82b (RED), a0a96a8 (GREEN) |
| 3 | PlaybackController state machine (+ checker addendum 1 and 2) | 2ce7ae8 (RED), 79103e0 (GREEN) |

## Verification (actually run)

- `:app:testDebugUnitTest --tests "com.yicamalt.stream.*"`: BUILD SUCCESSFUL, exit 0. FrameAssemblerTest has 19 tests, 0 failures.
- `:app:testDebugUnitTest --tests "com.yicamalt.live.*"`: BUILD SUCCESSFUL, exit 0. DefaultMediaSessionTest has 12 tests, 0 failures. LiveFakes.kt is unchanged.
- `:app:testDebugUnitTest --tests "com.yicamalt.archive.*" --rerun`: three consecutive runs, each BUILD SUCCESSFUL with exit 0. PlaybackControllerTest had 28 tests and 0 failures every time, and the result XML timestamps were fresh.
- The full JVM suite `:app:testDebugUnitTest --rerun` is BUILD SUCCESSFUL: 826 tests, 0 failures, 0 errors.
- RED was confirmed before each GREEN step: 7 stream failures, 4 live failures and 28 archive failures. The resolution-change test passed in RED. It pins behaviour that already exists: the size and csd reach the decoder, and VideoDecoder.formatChanged recreates the codec.
- Acceptance greps:
  - `fun expectGeneration(useCount: Int?)` appears 2 times (assembler and pipeline).
  - `and 0xFF` appears 4 times in FrameAssembler.
  - `expectGeneration` appears 8 times in FrameAssemblerTest.
  - PlaybackControllerTest has 28 `@Test` and 29 `controller.close()`.
  - `reconnecting` appears 13 times, `linkHealthy()` 6 times and `isLive()` 0 times.
  - The block markers appear 2 times, and `expectGeneration(uc)` comes before the sends in startAt and goLive.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ArchiveLinkPort had no units counter for checker addendum 1**
- **Found during:** Task 3
- **Issue:** The start timeout has to count only while units of any generation arrive. `lastFrame()` is gated, so it cannot see units of other generations.
- **Fix:** Added an abstract `fun framesIn(): Long` to `ArchiveLinkPort` in SdTypes.kt (v0.2.0). FakeArchivePort gets `unitsFlowing` / `units`. The only implementer today is the fake. The production ports (04-04 debug probe, 04-06 supervisor) must return `MediaSession.framesIn()` of the current session.
- **Files modified:** app/src/main/java/com/yicamalt/archive/SdTypes.kt, app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt
- **Commit:** 2ce7ae8

**2. [Rule 3 - Blocking] Test name `first recorded key frame -> Playing` does not compile**
- **Issue:** Kotlin/JVM rejects `>` in a backtick name.
- **Fix:** Renamed the test to `first recorded key frame starts Playing`.

**3. Fake port additions:** `useCountAvailable` (the not-ready scenario) and an ordered `events` trace (proves the gate is set before the first send). The fake's `useCounter` is set to 3 in the tests: the live link used 2, so the first archive generation is 3, which matches the plan's expected `Starting(…, 3)`.

### Interpretation notes
- The plan says "All state changes on the scope". This is implemented as one lock that serialises public calls, the ticker and the collector. Public calls take effect synchronously.
- The plan has "expected stays null / expected null" after a liveEpoch in Paused. The gate set by the last seek is still on the old session, so the controller clears it with `expectGeneration(null)`. No new gate is set.
- The plan's ">= 21 tests" is met with 28. Extra tests: go-live fail-open, link drop near the segment end with a timer re-base, addendum 1, addendum 2, redaction, and close idempotence.
- docs/*.xml were not changed, because the plan does not ask for it. M-SD-ARCHIVE / V-M-SD-ARCHIVE graph entries for PlaybackController can be added when the phase docs are synced.

## Known Stubs

None. PlaybackController has no UI wiring yet; that is planned for 04-06 / 04-08.

## Self-Check: PASSED
- Files present: PlaybackController.kt, PlaybackControllerTest.kt, 04-05-SUMMARY.md
- Commits present: 0fbb62e, 8806552, 55cb82b, a0a96a8, 2ce7ae8, 79103e0
