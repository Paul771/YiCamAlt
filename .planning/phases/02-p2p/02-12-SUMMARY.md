---
phase: 02-p2p
plan: 12
subsystem: verification
tags: [android, p2p, tutk, tnp, device-uat, redaction]

# Dependency graph
requires:
  - phase: 02-00..02-11
    provides: native P2P transport, credential resolution, SD archive and live-view shell
provides:
  - physical-device evidence for LIVE-01/LIVE-02/LIVE-03 on both TNP and TUTK cameras
  - redaction sweep of a real live-session logcat
affects: [phase-03, release, verification]

# Actuals
actuals:
  tokens: 60
  tasks: 1
  commits: 1

# Tech tracking
tech-stack:
  added: []
  patterns: [adb logcat capture filtered to app-tagged lines for RealLogScanTest]

key-files:
  created:
    - .planning/phases/02-p2p/02-HUMAN-UAT.md
  modified:
    - .planning/phases/02-p2p/02-DEVICE-VERIFICATION.md

key-decisions:
  - "UAT results are user-attested; adb logcat supplied objective markers where available"
  - "H.265 recorded as 'not exercised on device' rather than failed — no H.265 camera exists"

patterns-established:
  - "Device evidence log keeps raw logcat out of Git and records value-free markers only"

requirements-completed: [LIVE-01, LIVE-02, LIVE-03]

coverage:
  - id: D1
    description: "TNP camera live on home Wi-Fi and on mobile data, relay-only mode, H.264 path"
    requirement: LIVE-01
    verification:
      - kind: manual_procedural
        ref: "02-HUMAN-UAT.md H1-H4 — 4/4 pass on Mi 9, 2026-11-19"
        status: pass
    human_judgment: false
  - id: D2
    description: "TUTK regression cycle and hand-over with the official Yi Home app"
    requirement: LIVE-02
    verification:
      - kind: manual_procedural
        ref: "02-HUMAN-UAT.md H5-H6 — 2/2 pass on Mi 9, 2026-11-19"
        status: pass
    human_judgment: false
  - id: D3
    description: "Release logcat redaction sweep and required stream markers"
    requirement: LIVE-03
    verification:
      - kind: unit
        ref: "RealLogScanTest 2/2 pass on app message values (tag stripped); adb logcat 45010 lines"
        status: pass
    human_judgment: false

# Metrics
duration: 0min
completed: 2026-11-19
status: complete
---

# Phase 02: p2p — Plan 12 Summary

**Physical-device UAT closed on Xiaomi Mi 9: TNP and TUTK live streaming verified on LAN, mobile data and relay, with a redaction sweep of the live logcat**

## Performance

- **Duration:** not measured across the original device sessions; final UAT 2026-11-19
- **Tasks:** 1 (blocking human device verification)
- **Files modified:** 2

## Accomplishments

- TNP camera ("Спальня") verified on home Wi-Fi: `AUTH_REPLY outcome=OK`, `FIRST_VIDEO codecId=78 decryptOk=true 1920x1080`, `FIRST_RENDER ms=83`, UI badge LAN
- TNP verified on mobile data and in relay-only mode; H.264 hardware decode confirmed
- TUTK connect/stop/reconnect/reopen regression passed; hand-over with official Yi Home app passed
- Logcat sweep: 45 010 lines captured, 200 app-tagged lines, no unredacted password/account/token/Authorization values; `RealLogScanTest` 2/2 on message values
- H.265 explicitly recorded as not exercised — no H.265 camera available

## Task Commits

1. **Task 12: device regression verification** — `bc81c5f` (test)

**Plan metadata:** `85d9344` (docs: defer incomplete Phase 2 item to backlog `999.1`)

## Files Created/Modified

- `02-HUMAN-UAT.md` — 7-item structured UAT with per-test evidence
- `02-DEVICE-VERIFICATION.md` — criteria table and RESULT: PASS

## Decisions Made

- Device results recorded as user-attested because the observations require a physical phone and cameras.
- A `RedactionScanner` false positive on the tag `YiCamAlt/EncryptedP2pCredentialStore` was identified during H7; the scan passes on message values, so it was recorded as a tooling caveat rather than a data leak.

## Deviations from Plan

- Original plan expected a per-criterion RESULT row filled by the user; the structured `02-HUMAN-UAT.md` was used instead, and `02-DEVICE-VERIFICATION.md` was updated from it.

**Total deviations:** 1
**Impact on plan:** none — same evidence, better structure.

## Issues Encountered

- Initial `RealLogScanTest` run on the full system logcat failed on other apps' data (email, IP, long digit runs). Re-running on app-only messages passed. No product code change was needed.

## Next Phase Readiness

- Phase 02 device layer is closed; remaining gate is the retroactive STRIDE security audit for the phase.

---
*Phase: 02-p2p*
*Completed: 2026-11-19*
