---
phase: 04-sd-archive
plan: 10
subsystem: archive-playback / live-session
tags: [gap-closure, w-2, sd-archive, supervisor, playback, tdd]
requires:
  - phase: 04-sd-archive
    provides: PlaybackController (04-05), supervisor stream modes + SupervisorArchivePort (04-06), device defect 4 / W-2 (04-09 device verification)
provides:
  - ArchiveLinkPort.archiveStallCount() (default 0) + supervisor / SupervisorArchivePort implementation
  - supervisor rule: short-lived ARCHIVE stall closes do not reset the attempt counter (W-2c)
  - PlaybackController past-end ENDED (W-2a) and silent re-seek cap -> PauseReason.SILENT (W-2b)
affects: [04-11, 04-12 (maps Paused(SILENT) to «Нет записи в это время»), 04-13 (docs/*.xml sync)]
tech-stack:
  added: []
  patterns:
    - "Cross-module counter through the narrow port (monotonic archiveStallCount snapshot at each sent seek) instead of a callback"
    - "Interface default member keeps ProbeArchivePort untouched"
key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/archive/SdTypes.kt
    - app/src/main/java/com/yicamalt/live/LiveSessionSupervisor.kt
    - app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt
    - app/src/main/java/com/yicamalt/archive/PlaybackController.kt
    - app/src/test/java/com/yicamalt/live/LiveSessionSupervisorTest.kt
    - app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt
    - app/src/test/java/com/yicamalt/archive/PlaybackControllerTest.kt
key-decisions:
  - "04-10: an ARCHIVE stall close counts as unhealthy only when frames stopped growing < healthyResetMs after Streaming; a long playback that then stalls keeps the Phase 3 reset rule"
  - "04-10: silent re-seek cap = 2 consecutive ARCHIVE-stall reconnects at one position (+-2 s) -> Paused(SILENT) with live video; network drops never count; seek/resume/goLive/pause reset"
  - "04-10: position at/after the last known segment end with no next segment counts as end-near (ENDED at 30 s, reason ended-past-index)"
metrics:
  duration: ~3h20m wall (includes a rate-limit pause; active work ~40 min)
  completed: 2026-10-31
  tasks: 3
  files: 7
---

# Phase 4 Plan 10: W-2 endless archive reconnect loop Summary

Three independent guards end the «Подключение…» cycle on silent archive playback. Past the last known segment end, playback reaches Paused(ENDED) in 30 s. Two ARCHIVE-stall reconnects at one position stop in Paused(SILENT) with live video. The supervisor no longer resets its attempt counter for short-lived ARCHIVE stall closes, so it reaches Exhausted after 5. All three are proven with virtual-time tests.

## Tasks

| Task | Name | RED commit | GREEN commit |
| ---- | ---- | ---------- | ------------ |
| 1 | Supervisor: ARCHIVE stall-closed links keep the attempt counter; archiveStallCount | 466a35f | 2ddfeb7 |
| 2 | PlaybackController: position at/after the last known end = end of recording | f1bd013 | 4cabfcd |
| 3 | PlaybackController: silent re-seek cap -> Paused(SILENT) | aa086ac | df0ba55 |

## Verification

- `:app:testDebugUnitTest :app:assembleDebug --rerun`: BUILD SUCCESSFUL, gradle exit=0. 915 tests, 0 failures, 0 errors. 1 skipped (RealLogScanTest, as expected).
- New tests: 4 supervisor tests (LiveSessionSupervisorTest), 3 for past-end ENDED and 5 for the SILENT cap (PlaybackControllerTest). No existing test was changed.
- RED evidence: in Task 1, 2 of the 4 new tests failed (Waiting(2) instead of Waiting(3); count 0 instead of 1). In Task 2, 2 of 3 failed (still Playing). In Task 3, 3 of 5 failed (the second bump re-seeked). The `network drops never count` and `a stall at a different position starts a new count` tests are guards that pass before and after the change.
- All grep acceptance checks pass. Timings are unchanged (stallMs 10 s, archiveStallMs 35 s, healthyResetMs 30 s, endedMs 30 s).
- The plan's commits change only files listed in files_modified.
- No `when` on PauseReason needs exhaustiveness outside the plan's files. ArchiveCoordinator uses an `if`-style check (`s.reason == PauseReason.ENDED`), so it compiles unchanged.

## Deviations from Plan

1. **[Rule 3 - Blocking] Test name punctuation.** The plan's name `silent camera after a seek: two ARCHIVE-stall reconnects then Paused SILENT` cannot be used because Kotlin/JVM backtick names may not contain `:`. The test is named `silent camera after a seek - two ARCHIVE-stall reconnects then Paused SILENT`. The acceptance grep substring still matches (count = 5).
2. **[Rule 3 - Blocking] Compiling stubs in the Task 3 RED commit.** `PauseReason.SILENT` and `FakeArchivePort.archiveStalls` were added in the RED commit. This follows the Task 1 W-B pattern, so the tests fail on assertions rather than on compilation.
3. Test helper `silentTwice(controller, port)` added so the SILENT and resume tests share one setup. ENDED-before-stall test: the state is checked at exactly 30.5 s after the key frame (`elapsed == 30_500 < 35_000`).

`roadmap update-plan-progress`, `requirements mark-complete` and `phase complete` were not run, per the orchestrator's instructions.

## Known Stubs

None. `ArchiveLinkPort.archiveStallCount()` defaults to 0 on purpose, for ProbeArchivePort (debug probe without a supervisor).

## Self-Check: PASSED
