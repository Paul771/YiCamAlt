---
phase: "03"
slug: "live-view"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-11-19"
register_authored_at_plan_time: false
---

# Phase 03 — Security

> Retroactive STRIDE register (no `<threat_model>` block existed in any 03-xx PLAN).
> 14 threats derived from implementation across 8 focus areas; all verified against code,
> JVM tests and device UAT (03-UAT.md: 11/11 pass).

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Session supervisor → camera link | Reconnect/backoff and stall control over a live camera session | control commands, lifecycle |
| Activity lifecycle → camera | Background hand-over must release the camera | session ownership |
| Live frame → UI/storage | Snapshot capture and gallery write | frame bitmap |
| App → log sinks | Value-free markers only | counters, booleans, names |

---

## Threat Register

| Threat ID | STRIDE / Category | Severity | Disposition | Mitigation | Status |
|-----------|-------------------|----------|-------------|------------|--------|
| T-01 | DoS — reconnect storm | high | mitigate | `LiveSessionSupervisor.kt:94-95,357-362,390-394` backoff 2/5/10/20 s, max 5 attempts → Exhausted, no sixth open | closed |
| T-02 | DoS — hung link holds camera | medium | mitigate | `LiveSessionSupervisor.kt:601-629` stall watchdog → `requestClose(REASON_STALL)` | closed |
| T-03 | DoS — camera not released in background | high | mitigate | `LiveViewScreen.kt:203-210` → `LiveViewModel.kt:385-392,441-447` → supervisor close; UAT H-L6 pass | closed |
| T-04 | DoS — late open after stop | medium | mitigate | `LiveSessionSupervisor.kt:285,430-466` run token under lock, late open closed | closed |
| T-05 | Info — snapshot privacy | medium | mitigate | `SnapshotSaver.kt:75,84-89,143-162` timestamp-only filename, no EXIF, IS_PENDING, delete on failure, value-free log | closed |
| T-06 | Info — secrets in log markers | high | mitigate | all `BLOCK_*` logs value-free; `RedactionScanner.kt:32-44`; UAT H-L9 849-line pull, 0 failures | closed |
| T-07 | Info — network monitor leaks SSID/IP | medium | mitigate | `NetStateTracker.kt:22-24,41-61` opaque key; `AndroidNetworkMonitor.kt:56-67` only flags/generation | closed |
| T-08 | DoS — fullscreen tap-jacking / bars | low | mitigate | `LiveViewScreen.kt:245-290,376-494` gesture overlay, Back exits fullscreen first, bars + orientation restored | closed |
| T-09 | Info — stale frame shown as live | low | mitigate | `LiveViewScreen.kt:365-372` alpha 0 when showConnect/showRetry | closed |
| T-10 | DoS — wake lock not released | low | mitigate | `LiveViewScreen.kt:364` keepScreenOn tied to videoRendering, cleared on dispose | closed |
| T-11 | DoS — AudioTrack/codec leak | medium | mitigate | `LiveViewModel.kt:441-447`, `AacDecoder.kt:106-132,354-390` idempotent bounded release | closed |
| T-12 | DoS — 4881 quality-switch flood | medium | mitigate | `QualityController.kt:73-115` auto-request-once, 8 s timeout → `onUnsupported`; UAT H-L4 pass | closed |
| T-13 | Info — credentials in device backup | medium | mitigate | `AndroidManifest.xml:11` `android:allowBackup="false"` | closed |
| T-14 | Info — snapshot without permission / partial file | low | mitigate | `SnapshotSaver.kt:84-89,113-118`, `LiveViewScreen.kt:303-315` permission launcher | closed |

---

## Accepted Risks Log

No accepted risks. All 14 threats closed.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-19 | 14 | 14 | 0 | gsd-security-auditor, ASVS L1 + L2 boundary checks |

---

## Sign-Off

- [x] All threats have a disposition
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-19
