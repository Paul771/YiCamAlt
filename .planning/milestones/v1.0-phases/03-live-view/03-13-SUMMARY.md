---
phase: 03-live-view
plan: 13
subsystem: device-verification
status: partial
requirements-completed: []
completed: 2026-10-25
---

# Plan 03-13: Device verification of the live view (H-L1..H-L9) — partial

## What was done

Checks were run on the real phone (Mi 9, MIUI, API 30) with the TUTK camera "YI Home Camera" (2026-10-23, 2026-10-25) and the TNP camera "Спальня" over LAN (2026-10-25). Results, evidence (log block names, screenshots) and open items are recorded value-free in `03-DEVICE-VERIFICATION.md`; the per-check status is in `03-HUMAN-UAT.md` (4 passed, 5 partial).

## Outcome

- Passed: H-L1 (TextureView on MIUI), H-L2 (TUTK audio), H-L4 (HD/SD on both cameras), H-L7 (fullscreen, gestures, keep-screen-on), H-L9 (redaction sweep).
- Partial: H-L3 (TNP sound decoded but audibility unconfirmed), H-L5 (Wi-Fi->4G only a user report), H-L6 (parallel use with the official app not checked on TNP), H-L8 (not taken while zoomed).
- One UX defect found and fixed during the checks: last frame stayed visible behind "Не подключено" (commit 4e958b0).

## Not done

TNP sound audibility, Wi-Fi->4G with logs, TNP RELAY / mobile data, H.265, parallel use with the official app on TNP, a 15+ minute soak run.

## Self-Check

No code changes in this plan beyond 4e958b0; JVM suite last green at 686 tests (1 skipped, the log sweep).
