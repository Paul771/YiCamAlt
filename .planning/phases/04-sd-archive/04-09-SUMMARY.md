---
phase: 04-sd-archive
plan: 09
subsystem: device-verification
tags: [device, uat, sd-archive, redaction, human-needed]
requires:
  - phase: 04-sd-archive
    provides: archive UI on the camera screen (04-07), H-SD1..H-SD10 checklist and V-M-SD-ARCHIVE scenarios (04-08), no-card probe facts (04-04)
provides:
  - 04-DEVICE-VERIFICATION.md (value-free per-item journal, RESULT PARTIAL)
  - 04-HUMAN-UAT.md results (status partial; 1 pass, 9 blocked by missing SD card / unreachable TUTK)
  - verification-plan.xml H-SD statuses (H-SD6 passed, the rest pending with the not-exercised reason)
affects: [phase-4-verification, phase-4-closure]
tech-stack:
  added: []
  patterns:
    - "Not-exercised device items stay status=pending with a dated reason; never promoted to passed"
key-files:
  created:
    - .planning/phases/04-sd-archive/04-DEVICE-VERIFICATION.md
  modified:
    - .planning/phases/04-sd-archive/04-HUMAN-UAT.md
    - docs/verification-plan.xml
key-decisions:
  - "04-09: D-06 (hide the TUTK archive) not applied: the TUTK camera was unreachable, no evidence that its playback fails; ArchiveSupport.transports unchanged"
  - "04-09: H-SD6 recorded as passed for the no-card state only; «За этот день записей нет» and «Не удалось получить список» stay open (need a carded camera)"
metrics:
  duration: ~15min (Task 3; Tasks 1-2 run by the orchestrator)
  completed: 2026-10-31
  tasks: 3
  files: 3
---

# Phase 4 Plan 09: SD archive device verification Summary

Device run on the Mi 9 recorded honestly as PARTIAL: the no-card state (H-SD6) and the redaction sweep pass on the TNP camera; the other nine H-SD items could not be exercised because no reachable camera has a microSD card with recordings and the TUTK camera was off-line.

## Tasks

| Task | Name | Result | Commit |
|---|---|---|---|
| 1 | Build, install, prepare evidence capture | Done by the orchestrator: debug APK at the 04-07 code installed with `adb install -r`, diag log pulled to `build/device-logs/diag_log.txt` | — (nothing committed) |
| 2 | Phone checks H-SD1..H-SD10 (checkpoint) | Done by the orchestrator with the user: H-SD6 pass, live regression pass, the rest not exercised | — (user observation) |
| 3 | Record results, D-06, verification status | 04-DEVICE-VERIFICATION.md, UAT and verification-plan updated; D-06 not applied | 6993226 |

## Results

- H-SD6 PASS (no-card state, TNP «Спальня»): «Нет SD-карты» under the live video, `BLOCK_SD_STATUS status=NoCard`, `BLOCK_ARCHIVE_LOAD index=skipped` (no index request sent), day dialog Monday-first with only today tappable.
- Live regression with the archive panel present PASS: Home and return, `BLOCK_RESUME resumed=true`, Live again in ~0.6 s, picture not black (Phase 3 behaviour, not H-SD7).
- Redaction sweep PASS: `RealLogScanTest` over the 4645-line pulled log, tests=1 skipped=0 failures=0 errors=0, no line flagged.
- NOT EXERCISED (no SD card): H-SD1, H-SD2, H-SD3, H-SD4, H-SD5, H-SD7, H-SD8, H-SD10. NOT EXERCISED (TUTK unreachable): H-SD9.
- Never verified on a device: ruler pinch / pan feel, fullscreen overlay buttons in the archive, day dots, `SdTime.WIRE_IS_UTC` with real recordings.

## Gaps for the orchestrator

- No failures, so nothing for `/gsd:plan-phase 04 --gaps`.
- Device coverage gap: SD-01..SD-04 cannot be confirmed until a camera has a microSD card with at least an hour of recordings; SD-05 is confirmed only for the no-card text. Phase 4 stays `human_needed`; 04-HUMAN-UAT.md status `partial`.
- Requirements SD-01..SD-05 were NOT marked complete here (orchestrator decision).

## Deviations from Plan

- Tasks 1 and 2 were run by the orchestrator, not by this executor; the log was pulled to `build/device-logs/` (git-ignored) instead of the session scratchpad, matching 04-HUMAN-UAT.md.
- UAT results for not-exercised items use `blocked (no SD card)` / `blocked (TUTK camera unreachable)` as the UAT pre-condition section prescribes, so `result: [pending]` count is 0 (every item has a reported outcome).

## Known Stubs

None added (docs-only plan).

## Self-Check: PASSED

- FOUND: .planning/phases/04-sd-archive/04-DEVICE-VERIFICATION.md (10 `### H-SD` sections, 0 ISO timestamps)
- FOUND: commit 6993226
- verification-plan.xml parses (minidom)
