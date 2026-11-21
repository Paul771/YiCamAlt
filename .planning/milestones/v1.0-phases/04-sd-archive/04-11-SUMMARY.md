---
phase: 04-sd-archive
plan: 11
subsystem: ui-liveview / archive screen state
tags: [gap-closure, defect-1, defect-2, sd-archive, timeline, tdd]
requires:
  - phase: 04-sd-archive
    provides: ArchiveCoordinator (04-06), ArchivePanel / TimelineRuler (04-07), device defects 1 and 2 (04-09 device verification)
provides:
  - ArchiveUi.rulerMs - ruler centre source (archive position in the archive, wall time floored to 1 s in Live for today)
  - «Живой» selects today; Live day roll-over at local midnight
  - ArchivePanel.IDLE display state; spinner (LOADING) only while a fetch is in flight on a healthy link
affects: [04-12, 04-13 (docs/*.xml sync, device confirmation of the ruler after «Живой» and of no spinner with a failed link)]
tech-stack:
  added: []
  patterns:
    - "Display-only panel state: internal LOADING kept for the liveEpoch reload rule, mapped to IDLE in build() unless fetching && linkHealthy"
    - "Live ruler re-published by the existing 500 ms tick; floored wall time + StateFlow de-dup gives about 1 Hz"
key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
    - app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt
    - app/src/main/java/com/yicamalt/ui/live/TimelineRuler.kt
    - app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
    - app/src/test/java/com/yicamalt/ui/live/LiveViewModelTest.kt
key-decisions:
  - "04-11: ruler position is a new field ArchiveUi.rulerMs; cursorMs keeps its archive-only meaning (the «Запись HH:mm:ss» overlay and existing tests depend on it)"
  - "04-11: in Live with a past day selected rulerMs is null - the ruler keeps its own centre instead of pinning to that day"
  - "04-11: ArchivePanel.IDLE is display-only; the coordinator keeps LOADING internally so the next liveEpoch still reloads a missing list"
metrics:
  duration: ~6 min measured (start to final task commit)
  completed: 2026-10-31
  tasks: 3
  files: 5
---

# Phase 4 Plan 11: Ruler after «Живой» and spinner-only-while-fetching Summary

Archive screen state fixes for device defects 1 and 2: a separate `ArchiveUi.rulerMs` makes the timeline follow the floored wall clock in Live (also after «Живой» from any archive position, with «Живой» returning to today and a midnight roll-over), and a display-only `ArchivePanel.IDLE` replaces the endless «Загрузка записей…» spinner whenever nothing is actually being fetched on a healthy link.

## Tasks

| # | Task | Commits |
|---|------|---------|
| 1 | Ruler follows the wall clock in Live (rulerMs), «Живой» returns to today | 9640f90 (test RED), 07f575e (fix GREEN) |
| 2 | Spinner only while a fetch is in flight on a healthy link (ArchivePanel.IDLE) | de0cafe (test RED), e28408f (fix GREEN) |
| 3 | Composables: ruler fed from rulerMs, IDLE empty box, ruler contract | 80ab3bc |

## Verification

- `:app:testDebugUnitTest --tests "com.yicamalt.ui.live.*" :app:assembleDebug --rerun` - BUILD SUCCESSFUL, gradle exit=0; 134 tests in `com.yicamalt.ui.live.*`, 0 failures (ArchiveCoordinatorTest 34 -> 39 after Task 2: +6 ruler tests in Task 1, +5 new and 1 renamed load test in Task 2).
- RED evidence: Task 1 - 5 of 6 new tests failed on the null stub (`in Live with a past day selected the ruler has no live position` passes trivially against a null stub, it guards against pinning after GREEN); Task 2 - 5 failures (4 coordinator + LiveViewModel IDLE start; the spinner-in-flight and LIST_FAILED tests already matched old behaviour and guard the new rule).
- All acceptance greps from the plan hold (rulerMs field / floor / build wiring; IDLE enum, `fetching && port.linkHealthy()`, `fetching = false` x3; `cursorMs = ui.rulerMs`, IDLE branch, LiveViewScreen still uses `archive.cursorMs`).

## Deviations from Plan

### Minor adjustments

**1. [Rule 3 - Blocking] IDLE exhaustiveness branches added in the Task 2 RED commit**
- Kotlin requires exhaustive `when` over the enum, so the RED commit already carries `ArchivePanel.IDLE -> null` in build() and the empty IDLE Box in ArchivePanel.kt (the plan placed them in the Task 2 action). Behaviour in RED was unchanged (initial state still LOADING).

**2. LiveViewModelTest VERSION bump to 0.2.1 with a CHANGE_SUMMARY line** - GRACE rule for touched files; no other edits.

Otherwise the plan executed as written. docs/*.xml untouched (04-13).

## Known Stubs

None.

## Self-Check: PASSED
