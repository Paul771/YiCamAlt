---
phase: 04-sd-archive
plan: 06
subsystem: live-session + ui-liveview (archive wiring)
tags: [sd-archive, supervisor, stream-mode, archive-coordinator, view-model, D-05, D-06, D-07]
requires:
  - 04-02 (CameraLink archive members: sendIoCtrl / ioCtrlReplies / nextUseCount / setStreamMode)
  - 04-05 (PlaybackController, MediaSession generation gate, ArchiveLinkPort.framesIn)
provides:
  - mode-aware LiveSessionSupervisor (stall 10 s LIVE / 35 s ARCHIVE / off PAUSED, HD/SD only in LIVE)
  - LiveSessionSupervisor.liveEpoch / ioCtrlReplies / setStreamMode / archivePort
  - SupervisorArchivePort (ArchiveLinkPort incl. framesIn and linkHealthy)
  - ArchivePanel / ArchiveUi / ArchiveTexts / ArchiveCoordinator
  - LiveViewModel.archive + 7 archive actions
affects:
  - 04-07 (archive UI renders ArchiveUi and calls the VM actions)
  - 04-09 (device checks: TUTK hide rule, no-card / empty-index paths)
tech-stack:
  added: []
  patterns:
    - one coordinator per open in the per-open scope, closed with the screen
    - stall window measured from max(last frame growth, last mode switch)
key-files:
  created:
    - app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt
    - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
    - app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
  modified:
    - app/src/main/java/com/yicamalt/live/LiveSessionSupervisor.kt
    - app/src/main/java/com/yicamalt/ui/live/LiveViewModel.kt
    - app/src/test/java/com/yicamalt/live/LiveSessionSupervisorTest.kt
    - app/src/test/java/com/yicamalt/live/LiveFakes.kt
    - app/src/test/java/com/yicamalt/ui/live/LiveViewModelTest.kt
key-decisions:
  - "04-06: supervisor stall window counts from the later of the last frame growth and the last setStreamMode, so ARCHIVE->LIVE (goLive) never stalls at once"
  - "04-06: TUTK hide needs Failed(NO_FRAMES) + no Playing ever + liveThroughout (set on a transition into Starting from a non-Starting state, cleared by any unhealthy observation or liveEpoch change) + units arriving during the window (checker addendum)"
  - "04-06: openDays refresh (>60 s) re-fetches only the index and keeps old data on failure; a full 816+index load runs on the first liveEpoch, on retry, and on a liveEpoch while LOADING / LIST_FAILED"
  - "04-06: selected day follows the cursor only when the cursor's local day changes (midnight crossing), so a manual day pick is not overridden"
metrics:
  duration: "~75min (split by a usage-limit pause)"
  completed: 2026-10-31
  tasks: 3
  files: 8
---

# Phase 4 Plan 06: Archive wiring into the live session and screen state Summary

Mode-aware supervisor (LIVE 10 s / ARCHIVE 35 s / PAUSED no stall, HD/SD requests only in LIVE), a supervisor-backed `ArchiveLinkPort` (incl. `framesIn()` from the current MediaSession and `linkHealthy()` = live link && network available && state Live), and an `ArchiveCoordinator` that turns 816/817 status, the index and `PlaybackController` state into one `ArchiveUi`, exposed by `LiveViewModel.archive` with seven actions.

## Tasks

| Task | Name | Commits | Files |
| ---- | ---- | ------- | ----- |
| 1 | Mode-aware supervisor and SupervisorArchivePort | 72e380d (RED), a0138e8 (GREEN) | LiveSessionSupervisor.kt, SupervisorArchivePort.kt, LiveSessionSupervisorTest.kt, LiveFakes.kt |
| 2 | ArchiveCoordinator screen state | df1a3a8 (RED), 9bdb017 (GREEN) | ArchiveCoordinator.kt, ArchiveCoordinatorTest.kt |
| 3 | LiveViewModel archive API and lifecycle | c0059d1 (RED), d1856a4 (GREEN) | LiveViewModel.kt, LiveViewModelTest.kt, LiveFakes.kt |

## What was built

- **Supervisor (v0.3.0):** `Timing.archiveStallMs = 35_000`; `setStreamMode` (BLOCK_STREAM_MODE) stores the mode, restarts the stall window, forwards it to the live link. A link reaching Streaming resets the mode to LIVE before becoming `liveLink`; `liveEpoch += 1` after Live is published. Replies of every watched link are forwarded to `ioCtrlReplies` (buffer 16, drop oldest). `archivePort(transport, encrypted)`. Phase 3 LIVE behaviour unchanged (all old tests green).
- **SupervisorArchivePort:** pure delegation; without a live link: send false, useCount null, not live/healthy; without a session: no gate, no frame, `framesIn() = 0`.
- **ArchiveCoordinator (M-UI-LIVEVIEW):** the D-07 states, all wired and tested: «Загрузка записей…» before the first liveEpoch (nothing sent), «Нет SD-карты» (tfstat 5, no index request: the real TNP camera's state per 04-PROBE), «SD-карта не готова», «Не удалось получить список» + «Повторить», «За этот день записей нет» (empty day / empty index), TIMELINE with segments clipped to the local day. Days dialog, day labels («Сегодня ▾» / «Вчера ▾» / «28 октября ▾»), seek / pause / «Продолжить» / «Живой» with forced mute (D-05), hints (ended / start failed / no recording), Paused(BACKGROUND) kept as a marker across a reconnect, TUTK hide rule (D-06) with the checker addendum, unsupported transport -> HIDDEN «Архив SD на этой камере недоступен».
- **LiveViewModel (v0.3.0):** `LiveEnvironment.wallNow()` / `zone()` (the only `ZoneId.systemDefault` read in the new code); coordinator per open; `onStop` calls `coordinator?.onBackground()` before the background stop; `closeScreen` closes it; `qualityVisible` false in the archive.

## Verification (actually run)

- Task 1: `:app:testDebugUnitTest --tests "com.yicamalt.live.*" --rerun` three consecutive runs: BUILD SUCCESSFUL, `--- gradle exit=0` each (task executed, not UP-TO-DATE; LiveSessionSupervisorTest 47 tests).
- Task 2: `--tests "com.yicamalt.ui.live.ArchiveCoordinatorTest"`: 28 tests, 0 failures.
- Task 3: `--tests "com.yicamalt.ui.live.LiveViewModelTest"`: 33 tests, 0 failures.
- Full suite `:app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, exit 0; 883 tests, 0 failures, 0 errors, 1 skipped (pre-existing skip, not touched by this plan).
- Acceptance greps: `archiveStallMs: Long = 35_000` 1 line; `stallMs: Long = 10_000` 1 line; `START_BLOCK_STREAM_MODE` 1; `class SupervisorArchivePort` 1; `fun linkHealthy` 2 lines; ArchiveCoordinatorTest `@Test` = 28; `liveThroughout` 8 lines; `linkHealthy()` 2 lines; D-07 texts 9 lines; `ZoneId.systemDefault` in ArchiveCoordinator 0; `val archive: StateFlow<ArchiveUi>` 1; 7 archive action functions; `coordinator?.onBackground()` 1.
- No device run in this plan (JVM only).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Stall window restarts on a mode switch**
- **Found during:** Task 1
- **Issue:** with the window measured only from the last frame growth, returning ARCHIVE -> LIVE after more than 10 s of archive silence would close the link at the next tick.
- **Fix:** `modeSince` is set in `setStreamMode` and at Streaming; idle = `t - max(lastGrowthAt, modeSince)`. LIVE-only runs behave exactly as before.
- **Commit:** a0138e8

**2. [Rule 1 - Bug] liveThroughout semantics reconciled with the link-drop bullets**
- **Found during:** Task 2
- **Issue:** "set true on every transition into Starting" would re-arm the flag on the reconnect re-seek (Starting -> Starting), contradicting "a Failed(NO_FRAMES) after a window that contained a drop does NOT hide".
- **Fix:** the flag is set only on a transition into Starting from a non-Starting state (user seek / resume / boundary from Playing); reconnect re-seeks keep the cleared flag.
- **Commit:** 9bdb017

**3. [Rule 2 - Correctness] Selected day follows the cursor only on a cursor day change**
- **Found during:** Task 2
- **Issue:** a plain "cursor day != selectedDay -> switch" would override every manual day pick while in the archive.
- **Fix:** tracked `lastCursorDay`; the midnight-crossing test passes and manual picks stick.
- **Commit:** 9bdb017

**4. [Rule 2 - Correctness] Days-dialog refresh keeps old data on failure**
- **Issue:** a refresh timeout must not replace a visible timeline with LIST_FAILED.
- **Fix:** refresh = index only; non-Ok result keeps the previous index. Commit 9bdb017.

**5. [Rule 3 - Test support] FakeCameraLink.onIoCtrl hook**
- Needed so view-model tests get real 817 / 9032 replies through the supervisor. Commit c0059d1.

### Notes

- Checker addendum: implemented as `unitsSeen` / `unitsAtStart` evidence in `shouldHide()`. With the real PlaybackController a Failed(NO_FRAMES) can only happen while units arrive (04-05 addendum 1), so the "no units" test (`TUTK camera silent after a seek does not hide`) is green through both guards; the coordinator check is defence in depth.
- `SdProbe.kt` (04-04 debug probe) still reads `ZoneId.systemDefault()` for a log count; pre-existing, out of scope.
- docs/*.xml not changed (plan does not require it; no new module IDs, files belong to M-LIVE-SESSION / M-UI-LIVEVIEW).
- Requirements SD-01..SD-05 were not marked complete (orchestrator does it after device checks).

## Known Stubs

None. The archive UI rendering itself is plan 04-07.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt
- FOUND: app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
- FOUND: app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
- FOUND commits: 72e380d, a0138e8, df1a3a8, 9bdb017, c0059d1, d1856a4
