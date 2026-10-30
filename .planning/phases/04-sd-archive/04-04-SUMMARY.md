---
phase: 04-sd-archive
plan: 04
subsystem: sd-archive
tags: [archive, device-probe, debug, tnp, tutk, sd-card]
requires:
  - phase: 04-sd-archive
    provides: CameraLink IOCTRL surface (04-00/04-02), SdArchiveCodec / SdTime (04-01), ArchiveLinkPort / SdIndexRepository / PlaybackCommands (04-03), framesIn port member (04-05)
provides:
  - SdProbeTee / ProbeArchivePort / SdProbe debug actions on the long-press LiveProbe screen
  - 04-PROBE.md device facts (D-PROBE-1..4)
affects: [04-08, 04-09]
tech-stack:
  added: []
  patterns:
    - "Debug probe reuses the production ArchiveLinkPort path (SdIndexRepository, PlaybackCommands) over the probe CameraLink"
    - "Value-free probe logs [Archive][probe][BLOCK_SD_*]: counts, codes, sizes, ms, second deltas only"
key-files:
  created:
    - app/src/main/java/com/yicamalt/ui/live/SdProbe.kt
    - app/src/test/java/com/yicamalt/ui/live/SdProbeTest.kt
    - .planning/phases/04-sd-archive/04-PROBE.md
  modified:
    - app/src/main/java/com/yicamalt/ui/live/LiveProbeViewModel.kt
    - app/src/main/java/com/yicamalt/ui/live/LiveProbeScreen.kt
    - app/src/test/java/com/yicamalt/ui/live/LiveProbeViewModelTest.kt
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "TNP camera has no SD card (tfstat=5, verified on device); TUTK camera unverified (unreachable)"
  - "No camera with an Ok card: SD-01..SD-04 device scenarios stay not exercised until a microSD is inserted; H-SD6 checkable on TNP"
  - "WIRE_IS_UTC stays true (no deltaS observed; 817 isUtc=1 is supporting, not conclusive evidence)"
  - "BOUNDARY_MS 8000 and both transports in ArchiveSupport kept: boundary and TUTK feasibility unverified"
metrics:
  duration: ~45min (Task 1 + record; device run by orchestrator)
  completed: 2026-10-30
  tasks: 3
  files: 9
---

# Phase 4 Plan 04: SD archive device probe Summary

Debug SD probe (816 info, 9031 index, seek/pause/live/boundary, per-channel tee counters) on the long-press LiveProbe screen; the device run on the TNP camera confirmed the 816/817 and 9031/9032 exchanges end-to-end but found no SD card, so playback, boundary and wire time remain unverified.

## Tasks

| Task | Name | Commit |
|------|------|--------|
| 1 | SdProbe (tee, port adapter, actions) wired into LiveProbe (TDD) | b619c15 (RED), b0ad96d (GREEN) |
| 2 | Device run (checkpoint:human-verify) | performed by the orchestrator via adb, 2026-10-30 |
| 3 | Record probe facts, wire-time decision | 19a133f |

## Device result (from 04-PROBE.md)

- TNP «Спальня»: session streaming; `BLOCK_SD_INFO replyLen=344 tfstat=5 isUtc=1 ms=41` (NoCard); `BLOCK_FETCH_INDEX result=Ok(count=0) pages=1 ms=62`; `BLOCK_SD_CHANNELS` ch4..ch7 = 0 across 8 ticks. Steps 4-8 not run (nothing to play).
- TUTK: connect timeout (IOTC_ER_TIMEOUT), probe not run.
- Redaction sweep (`RealLogScanTest` over the 4548-line pulled log): tests=1, skipped=0, failures=0.
- `SdTime.WIRE_IS_UTC` not flipped (still `true`); SdTime.kt / SdTimeTest.kt unchanged.

## Verification

- Archive JVM tests re-run (`--rerun`): 7 suites, 111 tests, 0 failures, BUILD SUCCESSFUL.
- 04-PROBE.md: 5 `## ` sections, D-PROBE-1..4 present, 0 ISO timestamps.

## Deviations from Plan

1. **Device run executed by the orchestrator, not by a person.** The checkpoint was answered with a partial probe (TNP steps 1-3, TUTK off) rather than one of the literal resume strings; Task 3 ran its "real log" branch with the log the orchestrator pulled to `build/device-logs/diag_log.txt` (git-ignored) instead of a scratchpad copy.
2. **D-PROBE numbering follows the orchestrator's checkpoint result**, not the plan's Task 3 order: D-PROBE-1 = which camera has a card, D-PROBE-2 = no-card path, D-PROBE-3 = boundary, D-PROBE-4 = wire time + TUTK feasibility. D-PROBE-2 still carries the "no camera has an SD card" conclusion that 04-08 / 04-09 reference. A numbering note is in 04-PROBE.md.

## Known Stubs

None. (Probe actions after INDEX are implemented but were not exercised on a device.)

## Open items

- Device verification of SD-01..SD-05 is human_needed: insert a microSD into a camera and let it record at least one hour before 04-09.
- Wire time base, boundary behaviour, pause/resume/live on device and TUTK archive feasibility remain unverified.

## Self-Check: PASSED

- FOUND: app/src/main/java/com/yicamalt/ui/live/SdProbe.kt, app/src/test/java/com/yicamalt/ui/live/SdProbeTest.kt, .planning/phases/04-sd-archive/04-PROBE.md
- FOUND commits: b619c15, b0ad96d, 19a133f
- `const val WIRE_IS_UTC = true` (1 line) matches D-PROBE-4 (unverified, stays true)
