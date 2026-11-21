---
phase: 05-events-feed
plan: 07
subsystem: verification
tags: [device, uat, events, mi9, redaction]
requires: [05-06]
provides:
  - 05-HUMAN-UAT.md with H-EV1..H-EV10 results (status partial: 9 passed, 1 not exercised, 0 failed)
  - 05-DEVICE-VERIFICATION.md (UAT session 2026-11-04, probe sessions 2026-11-02/03, value-free)
  - 05-VERIFICATION.md (status human_needed, no open blockers)
  - verification-plan.xml H-EV statuses and Gate-GSD-Phase-5 device scenarios
affects: [phase-5-closure, phase-6]
tech-stack:
  added: []
  patterns:
    - "Defects found on the device are reproduced by a JVM test first, then fixed, then re-checked on the device in the same session"
key-files:
  created:
    - .planning/phases/05-events-feed/05-DEVICE-VERIFICATION.md
    - .planning/phases/05-events-feed/05-VERIFICATION.md
  modified:
    - .planning/phases/05-events-feed/05-HUMAN-UAT.md
    - docs/verification-plan.xml
decisions:
  - "Phase 5 verification status human_needed (not passed): H-EV7 not exercised and the H-EV3 label (D-03) awaits the user; no failed check, so not gaps_found"
  - "H-EV5 passes with BLOCK_CLIP_REFRESH reason=missing (media keys only in memory), not reason=stale as the UAT text expected"
  - "MediaPlayer on TextureView stays (mp4 / H.264 plays on MIUI); no move to Media3 ExoPlayer"
  - "Requirements EVT-01..EVT-06 are not marked complete by this plan; the orchestrator decides"
metrics:
  duration: device session 2026-11-04 (orchestrator) + ~20 min docs
  completed: 2026-11-04
  tasks: 3
  files: 5
---

# Phase 5 Plan 07: Device verification of the events feed Summary

On the Mi 9 the events feed passed 9 of 10 device checks (H-EV7 not exercised, 0 failed). Four defects were found and fixed during the session: the clip dialog reading Room on the main thread, a dead pooled CDN connection, a stuck pull-to-refresh indicator, and a Phase 1 log hole (`HTTP FAILED` lines carried the server and phone IPv4). The phase stays `human_needed` for the H-EV3 label decision and H-EV7.

## Tasks

| # | Task | Commit |
|---|------|--------|
| 1 | Build, install, device session prep (orchestrator; full JVM gate 1102 tests, 0 failures) | (no files) |
| 2 | User ran H-EV1..H-EV10 on the Mi 9 (checkpoint, orchestrator) | (no files) |
| 3 | Record device evidence and statuses | 2843e27 |
| — | Verification report | see final docs commit |

Fixes made during the device session (outside this plan's docs-only scope, committed by the orchestrator): a43316d, f8ff4af (clip dialog main-thread Room read), 4f97df7 (dead pooled connection), 2658424 (pull-to-refresh indicator), 2f464b5 (docs), 5b47900, de20d23, c6163c4 (HTTP FAILED redaction).

## Results

| Check | Result |
|---|---|
| H-EV1 tab and feed | PASS |
| H-EV2 camera chips | PASS |
| H-EV3 day picker | PASS, label question open (D-03) |
| H-EV4 clip | PASS after 2 fixes; cosmetic green strip |
| H-EV5 link refresh | PASS (log) |
| H-EV6 jump to SD | PASS (2 events) |
| H-EV7 jump without SD | NOT EXERCISED |
| H-EV8 5-minute gate | PASS |
| H-EV9 offline cache | PASS |
| H-EV10 log sweep | PASS after fix |

UAT counters: total 10, passed 9, issues 0, not_exercised 1, blocked 0, open_questions 1.

## Deviations from Plan

1. **[Rule 1 - Bug] Code fixes during a docs-only plan.** The plan says defects become gaps and no code changes are made. The orchestrator instead fixed the four defects in the same session (each with a reproducing test) and re-ran the checks, so H-EV4 and H-EV10 are recorded as PASS after fix rather than as gaps. Commits listed above.
2. **H-EV10 sweep scope.** Two old `HTTP FAILED` lines written before the fix stay in the device log and were excluded from the sweep; all later lines were checked.
3. **Commit trailer.** The plan asks for `Co-Authored-By: Claude Sonnet 5.5`; the commits of this run use the trailer from the current session attribution (Claude Opus 5.5).

## Known Stubs

None.

## Open items

D-03 label decision, green strip under the clip, H-EV7 on the device, retention beyond 2 days, shared-camera events, HLS clips, `wipeAll` delete result (deferred-items.md).

## Self-Check: PASSED

- FOUND: .planning/phases/05-events-feed/05-DEVICE-VERIFICATION.md
- FOUND: .planning/phases/05-events-feed/05-VERIFICATION.md
- FOUND: .planning/phases/05-events-feed/05-HUMAN-UAT.md (no `result: pending` left)
- FOUND: commit 2843e27; session fix commits a43316d, f8ff4af, 4f97df7, 2658424, 5b47900, de20d23, c6163c4
