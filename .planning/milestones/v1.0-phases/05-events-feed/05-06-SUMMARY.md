---
phase: 05-events-feed
plan: 06
subsystem: docs
tags: [grace, development-plan, knowledge-graph, verification-plan, uat]
requires: [05-00, 05-01, 05-02, 05-03, 05-04, 05-05]
provides:
  - development-plan.xml M-EVENT / M-EVENT-PLAYBACK / M-UI-EVENTS as built (STATUS implemented), DF-EVENT-LIST / DF-PLAYBACK rewritten, DF-EVENT-SD-JUMP added
  - knowledge-graph.xml nodes and CrossLinks for the event package and ui/events
  - verification-plan.xml V-M-EVENT / V-M-EVENT-PLAYBACK / V-M-UI-EVENTS (in-progress), Gate-GSD-Phase-5 with H-EV1..H-EV10
  - 05-HUMAN-UAT.md device script for 05-07
affects: [05-07]
tech-stack:
  added: []
  patterns:
    - "Doc edits by scripted exact-string replacement with uniqueness asserts, CRLF preserved, XML parse check"
key-files:
  created:
    - .planning/phases/05-events-feed/05-HUMAN-UAT.md
    - .planning/phases/05-events-feed/deferred-items.md
  modified:
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "V-M-EVENT / V-M-EVENT-PLAYBACK / V-M-UI-EVENTS STATUS in-progress (JVM scenarios status=passed) until the device checks, as for Phase 4; development-plan module STATUS implemented"
  - "M-UI-EVENTPLAYBACK (and V-M-UI-EVENTPLAYBACK, Phase-3 step 7) marked superseded: the ClipDialog of M-UI-EVENTS replaces the separate playback screen"
  - "New gate id Gate-GSD-Phase-5 (next free after Gate-GSD-Phase-4); device checks as unique tags H-EV1..H-EV10"
  - "H-EV10 uses `grep -cF \"://\"` on the pulled log for the 'no media host' check, so the UAT itself carries no host or scheme"
metrics:
  duration: ~35 min
  completed: 2026-11-04
  tasks: 2
  files: 5
---

# Phase 5 Plan 06: GRACE sync and device UAT script Summary

The three GRACE documents now describe the events feed that was built: signed `v2/alert/list`, the gated, capped sync and the Room v3 cache (M-EVENT), media decrypt with preview and clip caches played by MediaPlayer on a TextureView (M-EVENT-PLAYBACK), and the Events tab with its clip dialog and SD jump (M-UI-EVENTS). `05-HUMAN-UAT.md` holds the ten device checks for plan 05-07. The full gate is green.

## Tasks

| # | Task | Commit |
|---|------|--------|
| 1 | development-plan.xml + knowledge-graph.xml | 2b1e9b2 |
| 2 | verification-plan.xml, 05-HUMAN-UAT.md, full gate | 54eebf2 |

## What changed

- **development-plan.xml**: M-EVENT contract/exports/targets/markers rewritten. Notes record the deletion of the old client in 05-01, the D-PROBE decisions (by id only), the deny list and value-free logs. M-EVENT-PLAYBACK covers MediaCrypto, MediaHttp, EventMediaRepository, EventMediaFiles and ThumbEncoder, with a note that ExoPlayer was replaced by MediaPlayer. M-UI-EVENTS covers D-01..D-04, the «Все дни ▾» deviation, ClipDialog and the SD jump. Notes were added to M-LOCAL-DB (v3), M-CAMERA-LIST (event wipe), M-UI-LIVEVIEW (`openAt` export) and M-SD-ARCHIVE (entry point only), and M-DEBUG-PROBE got the AlertProbe exports. Flows: DF-EVENT-LIST and DF-PLAYBACK rewritten, DF-EVENT-SD-JUMP added, DF-PUSH-NOTIF marked deferred. Phase-3 steps 4-6 are done with plan refs, step 7 is superseded and a Phase 5 note was added. M-PUSH stays planned.
- **knowledge-graph.xml**: rewrote the M-EVENT, M-EVENT-PLAYBACK and M-UI-EVENTS nodes (paths, exports, tests, change-summary) and marked M-UI-EVENTPLAYBACK superseded. Also extended M-LOCAL-DB, M-CAMERA-LIST, M-UI-SHELL (Events tab), M-UI-LIVEVIEW (openAt) and M-DEBUG-PROBE. CrossLinks:
  - M-EVENT -> M-HTTP / M-AUTH / M-LOCAL-DB / M-EVENT-PLAYBACK
  - M-EVENT-PLAYBACK -> M-EVENT / M-LOCAL-DB. Its links to M-HTTP / M-AUTH were removed: the plain media client has no auth.
  - M-UI-EVENTS -> M-EVENT / M-EVENT-PLAYBACK / M-UI-LIVEVIEW / M-CAMERA-LIST
  - M-UI-SHELL -> M-UI-EVENTS, M-DEBUG-PROBE -> M-EVENT, M-CAMERA-LIST -> M-EVENT
- **verification-plan.xml**: V-M-EVENT, V-M-EVENT-PLAYBACK and V-M-UI-EVENTS list the real test files, the `tools/gradle.ps1` commands, scenarios and markers. ForbiddenEndpointsTest is scenario-3 of V-M-EVENT. Other updates:
  - V-M-DEBUG-PROBE: probe scenario and evidence added.
  - V-M-UI-LIVEVIEW: scenario-18 and marker `BLOCK_ARCHIVE_OPEN_AT` added.
  - V-M-UI-EVENTPLAYBACK: marked superseded.
  - New Gate-GSD-Phase-5 with the JVM gate, RealLogScanTest, the forbidden-path grep, device checks H-EV1..H-EV10 (pending) and the clip-refresh note.
- **05-HUMAN-UAT.md**: frontmatter `status: pending`. Sections: Current Test, Pre-condition (H-EV6 blocked rule), log pull, markers, then H-EV1..H-EV10. Each check lists its requirement, steps, expected result, evidence and `result: pending`, and the clip-refresh note comes before H-EV8.

## Verification (actually run)

- XML well-formed: `xml.dom.minidom.parse` on all three files prints `ok` after every edit.
- `grep -c "v2/alert/list" docs/development-plan.xml` = 7. `grep -n "v1/camera/event|getPlaybackUrl|EventServiceModule|YiCloudEventApi|EventTimelineParser"` over development-plan.xml and knowledge-graph.xml returns 0 lines.
- `H-EV[0-9]` line count: 05-HUMAN-UAT.md 15, verification-plan.xml 21. ForbiddenEndpointsTest appears 4 times in verification-plan.xml. `grep -nE "https?://|[0-9]{10,}"` on 05-HUMAN-UAT.md returns 0 lines. The new doc text adds no hits; the hits in the XML files were already there before this plan.
- Full gate `tools/gradle.ps1 :app:testDebugUnitTest --rerun :app:assembleDebug`:
  - Run 1: 1089 tests, 1 failure: `EventMediaRepositoryTest > wipeAll removes thumbs and clips`. The clips dir was still present after `deleteRecursively`.
  - Run 2: **BUILD SUCCESSFUL, `--- gradle exit=0`, 75 suites, 1089 tests, 0 failures, 0 errors, 1 skipped** (RealLogScanTest without a log file). Counted from the JUnit XML.

## Deviations from Plan

**1. [Scope] ExoPlayer still appears in the superseded M-STREAM-LIVE / M-STREAM-DECODE entries** of development-plan.xml (historic cloud-stream design, already marked superseded in Phase 2). The event modules mention ExoPlayer only in the "replaced by MediaPlayer" note. The superseded entries were left as history, so the acceptance grep `ExoPlayer` returns those 4 old lines as well.

**2. [Consistency] M-UI-EVENTPLAYBACK marked superseded** in development-plan.xml, knowledge-graph.xml and verification-plan.xml (not in the plan list). The clip dialog replaces it, and leaving it `planned` would contradict DF-PLAYBACK.

**3. [Status] V-M-* entries are `in-progress`, not `passed`**. This follows the orchestrator's instruction and the Phase 4 convention. The JVM scenarios carry `status="passed"`.

**4. [Plan detail] AlertProbe cap documented as 5 requests**, not the "≤3" in the plan text. The probe was raised to 5 in 05-00 (https retry).

**5. [Flaky, not caused by this plan] EventMediaRepositoryTest `wipeAll removes thumbs and clips`** failed once on the first full run and passed on the rerun. No code was touched in this plan. The likely cause is Windows file deletion timing: `EventMediaFiles.wipeAll` ignores the `false` result of `deleteRecursively()`. This was logged in `deferred-items.md` with a suggested fix (check the result, retry once, log `files=failed`). LiveViewModelTest «snapshot messages» and TutkCameraLinkTest did not fail.

## Deferred Issues

- See `deferred-items.md`: `EventMediaFiles.wipeAll` silently ignores a failed delete.

## Known Stubs

None (documentation only).

## Self-Check: PASSED

- FOUND: docs/development-plan.xml, docs/knowledge-graph.xml, docs/verification-plan.xml, .planning/phases/05-events-feed/05-HUMAN-UAT.md, .planning/phases/05-events-feed/deferred-items.md
- FOUND commits: 2b1e9b2, 54eebf2
