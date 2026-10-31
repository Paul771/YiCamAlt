---
phase: 04-sd-archive
plan: 08
subsystem: grace-docs
tags: [grace, knowledge-graph, verification-plan, development-plan, device-checklist, sd-archive]
requires:
  - phase: 04-sd-archive
    provides: M-SD-ARCHIVE code (04-01, 04-03, 04-05), transport / session / UI changes (04-00, 04-02, 04-06, 04-07), device probe facts (04-04)
provides:
  - M-SD-ARCHIVE record + DF-SD-ARCHIVE flow + Phase-3 step-17 in docs/development-plan.xml
  - completed M-SD-ARCHIVE graph node (the 04-01 stub, STATUS implemented) and CrossLinks in docs/knowledge-graph.xml
  - V-M-SD-ARCHIVE, Phase 4 scenario / marker / evidence updates on 6 V-M records, Gate-GSD-Phase-4 in docs/verification-plan.xml
  - PROJECT_STATUS.md Phase 4 focus + section 0.8
  - 04-HUMAN-UAT.md device checklist H-SD1..H-SD10 (all pending)
affects: [04-09]
tech-stack:
  added: []
  patterns:
    - "Device scenarios recorded as kind=device status=pending; JVM evidence never promotes them"
key-files:
  created:
    - .planning/phases/04-sd-archive/04-HUMAN-UAT.md
  modified:
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
    - docs/PROJECT_STATUS.md
key-decisions:
  - "04-08: M-SD-ARCHIVE placed as GRACE Phase-3 step-17 (GSD numbering stays independent of GRACE Phase-N)"
  - "04-08: the 04-04 SD probe device scenario in V-M-UI-LIVE-PROBE re-tagged kind=device status=partial (TNP INFO / INDEX run, no card; TUTK unreachable)"
metrics:
  duration: ~20min
  completed: 2026-10-31
  tasks: 2
  files: 5
---

# Phase 4 Plan 08: GRACE docs sync and device checklist Summary

The GRACE artifacts now describe what Phase 4 built: a full M-SD-ARCHIVE module record, flow and graph node (the 04-01 stub filled in, no second node), Phase 4 notes on the six changed modules, V-M-SD-ARCHIVE with real test files and gradle commands, and ten device scenarios H-SD1..H-SD10 that stay pending until 04-09.

## Tasks

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | development-plan.xml and knowledge-graph.xml | a872edb | docs/development-plan.xml, docs/knowledge-graph.xml |
| 2 | verification-plan.xml, PROJECT_STATUS.md, 04-HUMAN-UAT.md | 55c3c06 | docs/verification-plan.xml, docs/PROJECT_STATUS.md, .planning/phases/04-sd-archive/04-HUMAN-UAT.md |

## What was recorded

- **development-plan.xml:** `<M-SD-ARCHIVE ... STATUS="implemented">` after M-LIVE-SESSION with contract, 12 interface entries (export list taken from a grep of `archive/*.kt`), depends M-STREAM-CORE / M-P2P-CREDS, 7 sources + 9 test files, `[Archive]` critical blocks. "GSD Phase 4" notes on M-STREAM-CORE, M-TNP-LINK, M-TUTK-LINK, M-LIVE-SESSION, M-UI-LIVEVIEW, M-UI-LIVE-PROBE. New interface entries / sources / critical blocks for those modules (SupervisorArchivePort, ArchiveCoordinator, TimelineMath, CalendarGrid, TimelineRuler, ArchivePanel, SdProbe, LinkIoCtrlReply, StreamMode, FrameObservation, expectGeneration). `<DF-SD-ARCHIVE>` steps 1-6. Phase-3 step-17 + phase-note.
- **knowledge-graph.xml:** the 04-01 stub completed (purpose, 7 paths, 12 annotations, 9 test files, change-summary `plan="04-01..04-07" verification="jvm"`). New CrossLinks: M-SD-ARCHIVE -> M-P2P-CREDS, M-LIVE-SESSION -> M-SD-ARCHIVE (implements-port), M-UI-LIVEVIEW -> M-SD-ARCHIVE, M-UI-LIVE-PROBE -> M-SD-ARCHIVE. The existing TNP / TUTK -> M-STREAM-CORE edges got change-summary lines on their nodes instead of new edges.
- **verification-plan.xml:** V-M-SD-ARCHIVE (13 test files, 3 checks, 9 device log markers, 7 unit scenarios, H-SD1..H-SD10 as `kind="device" status="pending"`, notes on the probe facts and the extra port members). Updates on the six changed V-M records (scenarios, markers, evidence). Gate-GSD-Phase-4: JVM gate = full suite + assembleDebug, device gate = H-SD1..H-SD10.
- **Facts carried into the docs:** the TNP camera «Спальня» has no SD card (tfstat=5, verified), the TUTK camera is unverified (unreachable), SD-01..SD-04 need a microSD with recordings, H-SD6 is checkable on the TNP camera. `ArchiveLinkPort.framesIn()` (added in 04-05) and the `linkHealthy()` health signal are documented in the plan, the graph and the verification notes.
- **04-HUMAN-UAT.md:** `status: pending`, pre-condition block (microSD with at least one hour of recordings, see 04-PROBE.md D-PROBE-2), 10 `### H-SDn` sections each with `expected:`, `requirement:`, `result: [pending]`.

## Verification (actually run)

- `python -c "import xml.dom.minidom,sys;[xml.dom.minidom.parse(f) for f in sys.argv[1:]]" docs/development-plan.xml docs/knowledge-graph.xml docs/verification-plan.xml`: exit 0 (after each task and at the end).
- Task 1 greps: `<M-SD-ARCHIVE` = 1 in development-plan.xml and 1 in knowledge-graph.xml. CrossLinks from / to M-SD-ARCHIVE = 5 (needed >= 4). "GSD Phase 4" = 19 (needed >= 6). `<DF-SD-ARCHIVE` = 1 line.
- Task 2 greps: `<V-M-SD-ARCHIVE` = 1. `H-SD[0-9]` lines = 21 (needed >= 10), and 0 of them have `status="passed"`. `^### H-SD` = 10. `result: [pending]` = 10. "Phase 4" in PROJECT_STATUS.md = 3 lines.
- A script check found no duplicate numbered `scenario-N` / `marker-N` / `evidence-N` tags inside any V-M record section.
- Full JVM suite `:app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, gradle exit=0. The fresh JUnit XML gives 64 suites, 903 tests, 0 failures, 0 errors, 1 skipped (the pre-existing RealLogScanTest skip without a log file). This plan changed no code; the run confirms the docs-only change left the suite green.
- The added doc lines contain no DIDs, passwords, uids or IPs. The only URL match is the pre-existing repository row in PROJECT_STATUS.md, where only the branch name changed.

## Deviations from Plan

**1. [Rule 2 - Correctness] Pause commands described from the code, not from the plan text**
- **Found during:** Task 1
- **Issue:** the first draft described the TUTK pause as "cmd 1". `PlaybackCommands.pause` actually sends 767 + stop record (12718 / 794 cmd 1) on both transports.
- **Fix:** the M-SD-ARCHIVE interface entry and DF-SD-ARCHIVE step-6 were corrected before the commit.

**2. [Scope] Extra navigation entries beyond the listed notes**
- Besides the dated notes, the changed modules got interface / target / critical-block entries for the Phase 4 files and markers (SupervisorArchivePort, the archive UI files, SdProbe, the new stream types). Without these, a grep from the graph would not reach every Phase 4 artifact.

**3. [Clarification] 04-04 probe scenario re-tagged**
- `V-M-UI-LIVE-PROBE` scenario-17 (added by 04-04 as `kind="success"`) is now `kind="device" status="partial"`, with the actual run scope (TNP steps 1-3, no card; TUTK unreachable).

**4. [Process] Commit trailer**
- The orchestrator rules named `Claude Sonnet 5.5`. The plan, the 04-07 history and the session attribution use `Claude Opus 5.5`, so both commits carry `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

REQUIREMENTS.md was not touched and SD-xx were not marked complete; the orchestrator does that after the device checks.

## Known Stubs

None. Docs only; device scenarios are deliberately pending (plan 04-09).

## Self-Check: PASSED

- FOUND: docs/development-plan.xml, docs/knowledge-graph.xml, docs/verification-plan.xml, docs/PROJECT_STATUS.md, .planning/phases/04-sd-archive/04-HUMAN-UAT.md
- FOUND commits: a872edb, 55c3c06
