---
phase: 04-sd-archive
plan: 12
subsystem: ui-liveview / archive screen state
tags: [gap-closure, defect-3, W-1, sd-archive, index-refresh, tdd]
requires:
  - phase: 04-sd-archive
    provides: ArchiveCoordinator (04-06, 04-11), PauseReason.SILENT (04-10), defect 3 / verifier W-1 (04-VERIFICATION)
provides:
  - Index-only refresh of today's list on every liveEpoch increase after the first and every refreshMs (60 s) while today is shown, the link is healthy and the app is in the foreground
  - Refresh-first seek (from Live, or on today at / after the last known end) with a pending target bounded by SEEK_REFRESH_WAIT_MS (3 s)
  - Paused(SILENT) hint «Нет записи в это время» with «Продолжить»
  - BLOCK_ARCHIVE_REFRESH log anchor (reason, full, refreshed - value-free)
affects: [04-13 (docs/*.xml sync for M-UI-LIVEVIEW / V-M-UI-LIVEVIEW, device confirmation that recent footage is reachable by drag)]
tech-stack:
  added: []
  patterns:
    - "Poll slot measured from max(loadedAt, refreshAttemptAt): a failed refresh waits a full slot, no retry storm"
    - "Pending seek = target in coordinator state + a job joining the load job under withTimeoutOrNull; the ruler reads the pending target"
key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
    - app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
key-decisions:
  - "04-12: one 60 s rule (refreshMs) for both the day dialog refresh and the periodic poll; today's NO_RECORDINGS panel is polled too so a fresh card's first recording appears without user action"
  - "04-12: background flag is the coordinator's own (set by onBackground, cleared only by the next liveEpoch increase), independent of link health"
  - "04-12: pre-seek refresh only when the index is older than 5 s; on a 3 s timeout the seek resolves against the old list (old end clamp)"
  - "04-12: the onEpoch full load (first load and the LIST_FAILED / LOADING reload) logs reason=first; reason=epoch is the index-only reconnect refresh"
metrics:
  duration: ~25 min
  completed: 2026-10-31
  tasks: 2
  files: 2
---

# Phase 4 Plan 12: Recording list refresh (defect 3 / W-1) Summary

Today's SD index no longer freezes at screen-open time: ArchiveCoordinator refreshes it (index only) after every reconnect, every 60 s while today's timeline or empty-day panel is visible on a healthy foreground link, and right before a seek from Live or past the last known end, so footage recorded after the screen was opened is reachable by dragging the ruler; Paused(SILENT) from 04-10 now shows the existing «нет записи» hint.

## Tasks

| # | Task | Commits |
|---|------|---------|
| 1 | Refresh after every reconnect and every 60 s, no overlap | 53e9f1c (test RED), aeb7aef (fix GREEN) |
| 2 | Refresh-first seek with a bounded pending seek; SILENT hint | 31e4be8 (test RED), 62624e9 (fix GREEN) |

## What changed

- `ArchiveCoordinator` v0.4.0: `launchLoad(full, reason)` inside `BLOCK_ARCHIVE_REFRESH` (reasons first / retry / epoch / periodic / days / seek, plus `seek-resolve refreshed=%b`); `refreshAttemptAt`, `backgrounded`; `onEpoch` index-only refresh on an increase with TIMELINE / NO_RECORDINGS; `shouldPoll()` in the 500 ms tick; `needsRefreshBeforeSeek`, `pendingSeekMs` / `pendingJob`, `firePendingSeek`, `cancelPendingSeek` (goLive, togglePause, onBackground, close); `rulerOf` prefers the pending target; SILENT hint branch. Contracts, MODULE_MAP, CHANGE_SUMMARY updated.
- `ArchiveCoordinatorTest` v0.3.0: `BLOCK_REFRESH_TESTS` (8 tests) and `BLOCK_SEEK_REFRESH_TESTS` (10 tests), `NEW_SEG`, `pendingSeek()`, `Rig.playbackSent()`.

## Verification

- `:app:testDebugUnitTest :app:assembleDebug --rerun` -> BUILD SUCCESSFUL, gradle exit=0: 944 tests, 0 failures, 0 errors, 1 skipped (RealLogScanTest).
- `:app:testDebugUnitTest --tests "com.yicamalt.ui.live.*" --rerun` green after Task 1.
- All acceptance greps of both tasks match (2 refresh block anchors, shouldPoll, reason="epoch", reason="periodic", backgrounded=true, both constants, needsRefreshBeforeSeek, firePendingSeek / cancelPendingSeek, SILENT hint line, 8 + 10 test names).
- RED runs: Task 1 - 7 of 8 new tests failed (`no periodic refresh for a past day` passes by construction - guard test); Task 2 - 8 of 10 failed (`seek with a fresh index does not refresh` and `seek on a past day never refreshes` pass by construction - guard tests).

## Adjusted existing tests (per phase_rules)

1. `openDays refreshes a stale index` - `r.port.healthy = false` during `step(61_000)` and `null` again before `openDays()`, so the counted 9031 comes from the dialog refresh and not from the new periodic poll (planned adjustment). All assertions unchanged.
2. `background then auto-resume keeps the marker` - `r.port.sent.size` replaced by `r.playbackSent()` (all sent commands except 816 / 9031 / 792): the liveEpoch bump now sends an index refresh (9031), which is not a playback command. Both the equality (no playback command after the reconnect) and the `>` after «Продолжить» kept, just type-filtered.

No LiveViewModelTest change was needed.

## Deviations from Plan

None in behaviour. Implementation notes:
- `firePendingSeek` takes `refreshed: Boolean` (computed by the waiting job from `withTimeoutOrNull`) instead of reading the load job state, to log `refreshed` exactly as specified.
- The onEpoch full load uses `reason = "first"` also when it is the LIST_FAILED / LOADING reload on a later liveEpoch (the plan named only the first-epoch case).

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt, app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
- FOUND commits: 53e9f1c, aeb7aef, 31e4be8, 62624e9
