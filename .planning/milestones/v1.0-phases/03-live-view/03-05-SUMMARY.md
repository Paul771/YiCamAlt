---
phase: 03-live-view
plan: 05
subsystem: live-view
tags: [tdd, quality, resume, policy, grace]
requires: ["03-00"]
provides:
  - "QualityController (target, observe, needsAutoRequest, beginRequest, tick, onUnsupported, setManual; SWITCH_TIMEOUT_MS 8000)"
  - "ResumePolicy (onStopped, onStarted, shouldAutoResume; RESUME_WINDOW_MS 300000, REASON_BACKGROUND)"
affects: [03-06]
tech-stack:
  added: []
  patterns: ["pure single-context policy classes with a read-only state snapshot; supervisor logs, policies do not"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/live/QualityController.kt
    - app/src/main/java/com/yicamalt/live/ResumePolicy.kt
    - app/src/test/java/com/yicamalt/live/QualityControllerTest.kt
    - app/src/test/java/com/yicamalt/live/ResumePolicyTest.kt
  modified: []
decisions:
  - "needsAutoRequest also requires no pending request; beginRequest (including a null useCount) marks the per-link auto request as done"
  - "shouldAutoResume consumes the pending resume even when the window has expired; any non-background or inactive stop clears it"
metrics:
  duration: 6min
  completed: 2026-10-23
  tasks: 2
  files: 4
---

# Phase 3 Plan 05: QualityController and ResumePolicy Summary

Pure HD/SD policy (manual over mode, observed quality by key-frame width >= 1280, confirmation by useCount or width class, 8 s timeout / null useCount / unsupported hide the switch) and a single-use 5-minute background auto-resume rule, both proven by JVM tests.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | RED - stubs and failing tests | 2fc5ecc |
| 2 | GREEN - implement both policies | d3d82c1 |

## Verification

- RED: `:app:testDebugUnitTest --tests "com.yicamalt.live.*"` -> 25 tests, 24 failed (only the constants test passed), as expected.
- GREEN: `:app:testDebugUnitTest :app:assembleDebug` -> BUILD SUCCESSFUL, `--- gradle exit=0`; QualityControllerTest 17/17, ResumePolicyTest 8/8; whole suite 45 suites, 583 tests, 1 skipped (RealLogScanTest without log), 0 failures.
- Greps: `SWITCH_TIMEOUT_MS = 8_000L` = 1, `RESUME_WINDOW_MS = 300_000L` = 1, no `/*` in either source file. `Quality.ofWidth` used in `observe`.
- docs/development-plan.xml, knowledge-graph.xml and verification-plan.xml already listed these files from plan 03-00; no doc change needed.

## Deviations from Plan

- RED stubs use `error("RED stub")` instead of `TODO()` (the write hook rejects TODO()-only stubs; per executor instructions).

Otherwise the plan was executed as written.

## Known Stubs

None.

## Self-Check: PASSED
