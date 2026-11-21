---
phase: "04"
slug: "sd-archive"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-11-21"
register_authored_at_plan_time: false
---

# Phase 04 — Security

> Retroactive STRIDE register (no `<threat_model>` existed in any 04-xx PLAN).
> 7 threats derived from implementation across archive/, ui/live/, stream/, media/ and p2p/.
> All verified; device UAT 04-UAT.md 14/14 pass.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Camera wire → SD index parser | Untrusted 793/8192 IOCTRL payloads | binary index pages |
| Archive playback → session supervisor | Seek/stall/reset signals across coroutines | control state |
| Archive frame → gallery | Snapshot write | frame bitmap |
| Archive path → logs | Value-free markers only | codes, counts, sizes |

---

## Threat Register

| Threat ID | Category | Severity | Disposition | Mitigation | Status |
|-----------|----------|----------|-------------|------------|--------|
| T-01 | Tampering/DoS — malformed/oversized SD index page | medium | mitigate | `SdArchiveCodec.kt:167-207` length/count guards; `TutkCameraLink.kt:82,453,464-465` 8192 buffer + `coerceAtMost` | closed |
| T-02 | DoS — IOCTRL hang / reply-collector leak | medium | mitigate | `SdIndexRepository.kt:37,52,85,94,117-136` 10 s timeout, subscribe-before-send, `finally` cancel, MAX_PAGES=1000 | closed |
| T-03 | Tampering — wrong archive playback time base | medium | mitigate | `SdTime.kt:31,40,49-85` single `WIRE_IS_UTC` seam, injected zone, DST-safe day bounds; 0 `systemDefault` in core | closed |
| T-04 | DoS — silent re-seek / reconnect loop | high | mitigate | `PlaybackController.kt:85,371-421,467-476` maxSilentReseeks=2, past-end ENDED, linkHealthy freeze; `LiveSessionSupervisor.kt:592,617-624` archiveStall + 5-attempt budget | closed |
| T-05 | Tampering — stale-generation frames on resume/go-live | medium | mitigate | `PlaybackController.kt:293-326` resetPipeline + expectGeneration before sends; `FrameAssembler.kt:75,189-201` useCount gate | closed |
| T-06 | Info — gallery snapshot privacy | high | mitigate | `SnapshotSaver.kt:6,58-89,120-162` timestamp-only filename, no EXIF, IS_PENDING, delete on failure, value-free log | closed |
| T-07 | Info — secrets in archive log markers | high | mitigate | all `[BLOCK_*]` carry only codes/counts/sizes/ms; value-free `toString()`; `RealLogScanTest` sweeps 0 findings (UAT test 11) | closed |

---

## Accepted Risks Log

No accepted risks. All 7 threats closed.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-21 | 7 | 7 | 0 | gsd-security-auditor, ASVS L1 |

Verification-coverage notes (not security gaps): `SdTime.WIRE_IS_UTC=true` was never conclusively
confirmed against real recordings; H-SD14 natural end and H-SD12 AuthFailed variants are backed by
unit tests plus user attestation. Recorded in 04-UAT.md.

---

## Sign-Off

- [x] All threats have a disposition
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-21
