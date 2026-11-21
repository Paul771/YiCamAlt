---
phase: 03-live-view
plan: 12
subsystem: grace-docs
tags: [grace, verification, uat, gate]
requires: ["03-02", "03-04", "03-11"]
provides:
  - "docs/development-plan.xml: M-LIVE-SESSION, M-UI-LIVEVIEW implemented; Phase 3 exports for M-STREAM-CORE, M-TNP-LINK, M-TUTK-LINK, M-MEDIA-RENDER, M-UI-SHELL, M-MEDIA-AUDIO, M-MEDIA-SNAPSHOT; DF-LIVE-VIEW; Phase-3 steps 3/14/15/16 implemented"
  - "docs/knowledge-graph.xml: same statuses, annotations, 6 new CrossLinks for DEPENDS edges"
  - "docs/verification-plan.xml: real test files, unit scenarios, kind=device status=pending H-L scenarios, Gate-GSD-Phase-3"
  - ".planning/phases/03-live-view/03-HUMAN-UAT.md: H-L1..H-L9 pending"
affects: [03-13]
tech-stack:
  added: []
  patterns: []
key-files:
  created:
    - .planning/phases/03-live-view/03-HUMAN-UAT.md
  modified:
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
    - docs/PROJECT_STATUS.md
decisions:
  - "Phase 3 V-M records stay STATUS in-progress (JVM-verified, device pending); no H-L item is marked passed"
  - "Device checks use the real debug-row fields (audio=<muted|idle|playing|failed> audioUnits=<n>) and BLOCK_AUDIO_FIRST, not the research draft names audio=AAC/16000/1 / audioOk"
  - "H-L numbering follows 03-RESEARCH (H-L2 = TUTK audio, H-L3 = TNP audio); the swapped V-M-MEDIA-AUDIO follow-up was corrected"
metrics:
  duration: 45min
  completed: 2026-10-23
  tasks: 2
  files: 5
---

# Phase 3 Plan 12: GRACE sync and final autonomous gate Summary

The GRACE docs now describe the Phase 3 code exactly as it was built. Development plan, knowledge graph and verification plan take their exports, blocks, log markers and test files from the real MODULE_MAPs. Three full `--rerun-tasks` runs of the unit suite plus assembleDebug were green, and the nine device checks are queued as pending UAT. Phase 3 has JVM evidence only: nothing has been run on the phone yet.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | GRACE sync from the code | 67fd832 |
| 2 | Final autonomous gate and pending UAT list | d6f046c |

## Gate (Task 2)

`tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`, three consecutive runs:

| Run | Result | Elapsed | Suites | Tests | Failures | Skipped |
| --- | ------ | ------- | ------ | ----- | -------- | ------- |
| 1 | BUILD SUCCESSFUL, `--- gradle exit=0` | 124.6 s | 53 | 686 | 0 | 1 (RealLogScanTest, no log file) |
| 2 | BUILD SUCCESSFUL, `--- gradle exit=0` | 80.7 s | 53 | 686 | 0 | 1 |
| 3 | BUILD SUCCESSFUL, `--- gradle exit=0` | 85.6 s | 53 | 686 | 0 | 1 |

The counts come from `app/build/test-results/testDebugUnitTest/*.xml` and were identical in every run. Phase 3 classes: LiveSessionSupervisorTest 38, LiveViewModelTest 23, QualityControllerTest 17, ResumePolicyTest 8, NetStateTrackerTest 6, DefaultMediaSessionTest 7, AdtsParserTest 13, AudioPipelineTest 12, AudioFrameQueueTest 5, SnapshotSaverTest 4, ZoomMathTest 6, VideoSizeTest 4, TnpCameraLinkTest 33, TutkCameraLinkTest 38, TutkShimContractTest 6, IoCtrlMessagesTest 6.

Redaction sweep: `--tests com.yicamalt.test.RedactionScannerTest` together with the live, ui.live, p2p and AudioPipeline suites (the ones that call `assertClean`), run with `--rerun`. Result: gradle exit 0, 19 suites, 307 tests, 0 failures, RedactionScannerTest 31/31.

Task 1 checks:
- minidom parses development-plan, knowledge-graph, verification-plan and operational-packets.
- Every START_BLOCK_ has a matching END_BLOCK_ in live/, ui/live/, media/, stream/ and p2p/ (16/16, 22/22, 15/15, 19/19, 35/35), and every block name is paired in its own file.
- STATUS="implemented" appears inside M-LIVE-SESSION, M-MEDIA-AUDIO, M-MEDIA-SNAPSHOT and M-UI-LIVEVIEW.
- The required test names occur 22 times in verification-plan.xml.
- H-L ids are present, and no line has both H-L and `status="passed"`.

## What changed in the docs

- **development-plan.xml:**
  - M-LIVE-SESSION and M-UI-LIVEVIEW are now `implemented`; M-MEDIA-AUDIO and M-MEDIA-SNAPSHOT already were.
  - Full interfaces for the new modules, e.g. `export-LiveSessionSupervisor`, `fn-classify`, `fn-classifyClosed`, `export-QualityController`, `export-ResumePolicy`, `export-NetStateTracker`, `export-AndroidNetworkMonitor`, `export-LiveModule`, `export-DefaultMediaSession`, `export-AacDecoder`, `type-AudioOutStatus`, `export-AudioFrameQueue`, `export-SnapshotStore`, `export-SnapshotSpec`, `export-MediaStoreSnapshotStore`, `export-LiveViewModel`, `export-LiveViewScreen`, `fn-badgeFor`, `export-ZoomMath`.
  - Phase 3 exports added to existing modules:
    - M-STREAM-CORE: `export-AdtsParser`, `export-AudioPipeline`, `type-AudioUnit`, `type-LinkCapabilities`, `fn-setResolution`, and others.
    - M-TNP-LINK / M-TUTK-LINK: `fn-setAudioSink`, `fn-requestResolution`, plus `fn-capabilities` (TNP) and `fn-recvIoCtrl` (TUTK), and the new log blocks.
    - M-MEDIA-RENDER: `fn-visibleSize`, `type-VideoSize`, videoSize.
    - M-UI-SHELL: `fn-onOpenCamera`, `fn-onLongPressCamera`.
  - M-UI-LIVE-PROBE note: "kept as debug screen, reachable by long-press".
  - New data flow DF-LIVE-VIEW.
  - `<Phase-3>` steps 3, 14, 15 and 16 are `implemented`, with the phase-note "2026-10-23: GSD Phase 3 (03-live-view) implemented, JVM-verified; device checks H-L1..H-L9 pending (03-HUMAN-UAT.md)".
- **knowledge-graph.xml:**
  - Same statuses and annotations, plus change-summaries for each touched module.
  - New CrossLinks: M-LIVE-SESSION -> M-P2P-CREDS, M-MEDIA-AUDIO -> M-STREAM-CORE, M-UI-LIVEVIEW -> M-STREAM-CORE, M-UI-LIVEVIEW -> M-P2P-CREDS, M-UI-LIVE-PROBE -> M-STREAM-CORE, M-UI-SHELL -> M-UI-SETTINGS.
  - The relation of M-UI-SHELL -> M-UI-LIVE-PROBE now says "long press".
- **verification-plan.xml:**
  - V-M-UI-LIVEVIEW and V-M-LIVE-SESSION move from planned to in-progress. JVM scenarios have `evidence-type="unit"`; device scenarios have `kind="device" status="pending"` and reference H-L ids.
  - New device scenarios in V-M-STREAM-CORE, V-M-TNP-LINK (blocked on Phase 2 H1), V-M-TUTK-LINK, V-M-MEDIA-RENDER and V-M-UI-SHELL.
  - Gate-evidence added to every Phase 3 record.
  - New `Gate-GSD-Phase-3` with "JVM suite + assembleDebug green; device gate pending H-L1..H-L9" and the 3-run evidence.
- **PROJECT_STATUS.md:** section 0 says Phase 3 is "implemented, device verification pending" and the next step is plan 03-13. A new section 0.7 lists the new modules, and the phase table has GSD Phase 2 and Phase 3 rows.

## Deviations from Plan

**1. [Rule 1 - Bug] Debug-row field names in the device checks.** The research "Human checks" quote `audio=AAC/16000/1` and `audioOk=yes`, but the built LiveViewModel debug line has `audio=<muted|idle|playing|failed> audioUnits=<n>`. H-L2 and H-L3, in both the UAT file and verification-plan.xml, now point at the real fields and at `[Stream][audio][BLOCK_AUDIO_FIRST] codecId= rate= ch= enc=`.

**2. [Rule 1 - Bug] H-L2 / H-L3 swapped in V-M-MEDIA-AUDIO.** The old follow-up said "H-L2 TNP, H-L3 TUTK", which is the reverse of 03-RESEARCH. It now matches the research: H-L2 is TUTK and H-L3 is TNP after H1.

**3. Gate order.** The three gate runs ran before the Task 1 commit, while only docs/*.xml and PROJECT_STATUS.md were being edited. Docs do not take part in the build, so the tree under test is identical to the final code tree. The 3-run evidence then went into the Task 1 commit (Gate-GSD-Phase-3 evidence-3).

**4. Line endings.** PROJECT_STATUS.md is stored with CRLF in git. My first commit rewrote it as LF, which showed up as a 1086-line diff. I restored CRLF and amended the local commit (`hash-object --no-filters` + `update-index`), so the diff is now 34 lines. Not pushed.

## Requirements

LIVE-04..LIVE-11 are deliberately **not** marked complete. Device behaviour is unverified until plan 03-13.

## Known Stubs

None. The UAT result fields are `[pending]` on purpose.

## Self-Check: PASSED

- FOUND: .planning/phases/03-live-view/03-HUMAN-UAT.md
- FOUND: commits 67fd832, d6f046c
