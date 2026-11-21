---
phase: 04-sd-archive
plan: 07
subsystem: ui-liveview (archive UI)
tags: [sd-archive, compose, timeline, calendar, D-01, D-02, D-03, D-07]
requires:
  - 04-06 (LiveViewModel.archive StateFlow<ArchiveUi> + 7 archive actions, ArchiveTexts)
provides:
  - TimelineMath (pure ruler math) + TimelineMathTest
  - CalendarGrid / DayCell (pure month grid) + CalendarGridTest
  - TimelineRuler composable (pinch / pan, release-to-seek)
  - ArchivePanel / DayPickerDialog composables
  - LiveViewScreen v0.2.0 wiring (portrait panel, day dialog, fullscreen archive controls)
affects:
  - 04-08 (GRACE docs sync: new files and tests belong to M-UI-LIVEVIEW / V-M-UI-LIVEVIEW)
  - 04-09 (device checks of the archive UI and gestures)
tech-stack:
  added: []
  patterns:
    - pure math object + thin composable (same as ZoomMath / LiveViewScreen)
    - awaitEachGesture loop with calculateZoom / calculatePan, seek decided once all pointers are up
key-files:
  created:
    - app/src/main/java/com/yicamalt/ui/live/TimelineMath.kt
    - app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt
    - app/src/main/java/com/yicamalt/ui/live/TimelineRuler.kt
    - app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt
    - app/src/test/java/com/yicamalt/ui/live/TimelineMathTest.kt
    - app/src/test/java/com/yicamalt/ui/live/CalendarGridTest.kt
  modified:
    - app/src/main/java/com/yicamalt/ui/live/LiveViewScreen.kt
key-decisions:
  - "04-07: custom month grid instead of Material3 DatePicker (material3 1.2 has SelectableDates but no per-day dot); today stays tappable without recordings, other empty days disabled"
  - "04-07: navigable months = contiguous range from the earliest recorded month to max(latest recorded, today's month)"
  - "04-07: fullscreen overlay pause / «Живой» only in fullscreen; portrait shows them once, in ArchivePanel"
  - "04-07: ruler follows the playback cursor only while no finger is down; a pure pinch never seeks"
metrics:
  duration: "~45min (split by a session restart)"
  completed: 2026-10-31
  tasks: 3
  files: 7
---

# Phase 4 Plan 07: Archive UI on the camera screen Summary

The SD archive becomes reachable on the camera screen: under the video, a day button («Сегодня ▾»), a 24 h Canvas ruler with recorded segments, a centre cursor and pinch zoom (15 min..24 h, default 3 h), and release-to-seek; the D-07 state texts with «Повторить»; a month-grid day dialog with dots on recorded days; pause / «Живой» plus a «Запись HH:mm:ss» label in fullscreen.

## Tasks

| Task | Name | Commits | Files |
| ---- | ---- | ------- | ----- |
| 1 | Pure TimelineMath and CalendarGrid (TDD) | 319c884 (RED), 8e6384f (GREEN) | TimelineMath.kt, CalendarGrid.kt, TimelineMathTest.kt, CalendarGridTest.kt |
| 2 | TimelineRuler, ArchivePanel, DayPickerDialog | 8b86191 | TimelineRuler.kt, ArchivePanel.kt |
| 3 | Wiring into LiveViewScreen | 8ea3328 | LiveViewScreen.kt |

## What was built

- **TimelineMath:** span clamp / zoom / pan (drag right = earlier, centre clamped to the day), xOf, overlap filter, tick step 10 min / 1 h / 3 h with ticks at dayStart + k*step labelled HH:mm in the zone, initialCenter (today: now capped to the day; past day: last segment end, or noon), `shouldSeekAfterGesture` (moved >= max(4 px, 2 s); zero move = no seek).
- **CalendarGrid:** 42 Monday-first cells; enabled = recorded || today; marked = recorded; isToday / selected; `monthsWithRecordings` is a contiguous range; «Октябрь 2026» title and «Пн».. «Вс» headers.
- **TimelineRuler:** 64 dp Canvas, track in surfaceVariant, segments in primary, 1 dp ticks + labels via TextMeasurer, 2 dp centre cursor in the error colour with an HH:mm:ss label. BLOCK_TIMELINE_GESTURE: awaitEachGesture, zoom / pan per event, onSeek(centre) only when the release rule says so; log `released=true zoomed=%b seek=%b` (no times). Centre state is keyed on the day; `onSeek` and day end go through rememberUpdatedState so the pointerInput key (dayStartMs) does not hold stale lambdas.
- **ArchivePanel (BLOCK_ARCHIVE_PANEL):** HIDDEN -> message only; otherwise day button / pause-continue / «Живой» row, then LOADING spinner + text, NO_CARD / CARD_ERROR / NO_RECORDINGS text in a 64 dp box, LIST_FAILED text + «Повторить», TIMELINE ruler; hint below. Panel change logged by enum name only.
- **DayPickerDialog:** AlertDialog with month arrows limited to `monthsWithRecordings`, 40 dp cells, dot under recorded days, filled circle on the selected day, disabled cells at alpha 0.38 without clickable.
- **LiveViewScreen v0.2.0:** collects `viewModel.archive`; UI-only `ZoneId.systemDefault()`; `nowMs` ticker every 30 s; ArchivePanel after the video Box in portrait (before the message text); DayPickerDialog when `daysDialogOpen` (select = archiveSelectDay + archiveCloseDays); fullscreen overlay row gets Pause / PlayArrow (contentDescription = pauseLabel) and «Живой» when in the archive; «Запись HH:mm:ss» label under the badge while in the archive with a cursor. TextureView slot, gestures, snapshot and fullscreen code unchanged (one `AndroidView(`).

## Verification (actually run)

- Task 1 RED: `--tests TimelineMathTest --tests CalendarGridTest` -> 20 tests, 20 failed (BUILD FAILED, exit 1), as intended.
- Task 1 GREEN: same command -> BUILD SUCCESSFUL, exit 0; XML results TimelineMathTest 14 / 0 failures, CalendarGridTest 6 / 0 failures.
- Task 2: `:app:compileDebugKotlin` BUILD SUCCESSFUL, exit 0; no compiler warnings in the new files.
- Task 3 / plan gate: `:app:testDebugUnitTest --rerun :app:assembleDebug` BUILD SUCCESSFUL, `--- gradle exit=0`; summed XML results 903 tests, 0 failures, 0 errors, 1 skipped (pre-existing skip); `app-debug.apk` built (Oct 31 11:50).
- Acceptance greps: `@Test` TimelineMathTest 14, CalendarGridTest 6; `fun shouldSeekAfterGesture` 1; `DEFAULT_SPAN_MS = 3 * 3_600_000L` 1; TimelineRuler `awaitEachGesture` 2 lines (import + call), `calculateZoom|calculatePan` 4, `shouldSeekAfterGesture` 1; ArchivePanel `fun ArchivePanel|fun DayPickerDialog` 2, `DatePicker` only in CHANGE_SUMMARY, `today stays tappable` 1; LiveViewScreen `ArchivePanel(` 1, `DayPickerDialog(` 1, `archiveGoLive` 2, `AndroidView(` 1.
- **Not run on a device.** Layout at phone width, gestures, the dialog and fullscreen behaviour are unverified until 04-09. No Compose UI tests exist (composables are compile-checked only).

## Deviations from Plan

### Auto-fixed / discretion

**1. [Discretion] Fullscreen-only overlay archive buttons**
- **Issue:** the plan's step 4 adds pause / «Живой» to the overlay row unconditionally; in portrait the same buttons are already in ArchivePanel right below the video, so they would appear twice at phone width.
- **Fix:** overlay buttons shown when `fullscreen && archive.inArchive` (matches the must-have "in fullscreen the overlay row has pause/continue and «Живой»"). The «Запись HH:mm:ss» label is shown in both modes.
- **Commit:** 8ea3328

**2. [Rule 2 - Correctness] Stale lambdas in pointerInput**
- `pointerInput(dayStartMs)` would keep the first `onSeek` / `dayEndMs`; wrapped in `rememberUpdatedState`. Commit 8b86191.

**3. [Process] Commit trailer**
- The orchestrator rules named `Claude Sonnet 5.5`; the plan, the existing history and the session attribution use `Claude Opus 5.5`. The first commit was amended to `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; all 04-07 commits carry that trailer.

### Notes

- docs/*.xml not changed: 04-08 owns the Phase 4 GRACE docs sync (TimelineMath / CalendarGrid / TimelineRuler / ArchivePanel and the two tests belong to M-UI-LIVEVIEW / V-M-UI-LIVEVIEW).
- Requirements SD-01..SD-05 not marked complete (orchestrator does it after device checks).
- The `ArchivePanel` composable shares its name with the 04-06 `enum class ArchivePanel`; Kotlin resolves the call to the function and `ArchivePanel.X` to the enum (compiles cleanly).

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/ui/live/TimelineMath.kt
- FOUND: app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt
- FOUND: app/src/main/java/com/yicamalt/ui/live/TimelineRuler.kt
- FOUND: app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt
- FOUND: app/src/test/java/com/yicamalt/ui/live/TimelineMathTest.kt
- FOUND: app/src/test/java/com/yicamalt/ui/live/CalendarGridTest.kt
- FOUND commits: 319c884, 8e6384f, 8b86191, 8ea3328
