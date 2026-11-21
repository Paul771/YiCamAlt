---
phase: "07"
slug: "regression-fixes"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-11-19"
---

# Phase 07 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Build environment → Release artifact | Clean-clone build preventing untracked files/secrets leaking into the APK | source tree, signing material |
| APK signing → Android OS | v2/v3 signature verification preventing tampering | signed APK |
| Network → Decoder | ADTS frames / P2P payloads parsed before decode | untrusted byte streams |
| SD card → Index repository | TUTK index pages parsed into timeline entries | untrusted flash content |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-07-01 | Tampering | AudioPipeline.kt / AdtsParser.kt | medium | mitigate | sync word `0xFF`/`0xF0` and frame-length/end bounds validated before decode (`AdtsParser.kt:41-59`, called at `AudioPipeline.kt:108`) | closed |
| T-07-02 | Denial of Service | AacDecoder.kt | low | mitigate | `WRITE_NON_BLOCKING` with written/dropped accounting (`:277`), bounded `AudioFrameQueue` (`:65`), oversized-frame drop (`:162`) | closed |
| T-07-SC | Tampering | npm/pip/cargo installs | high | mitigate | Standing package-legitimacy gate + blocking checkpoint; **not triggered in this phase** — no npm/pip/cargo install, no package manifests in repo, no install commands in `tools/*.ps1` (verified by git log + file scan) | closed (N/A this phase) |
| T-07-03 | Denial of Service | TutkCameraLink.kt | medium | mitigate | `IOCTRL_BUF_SIZE = 8192` (`:846`), bounded allocation (`:453`), `coerceAtMost`/`coerceIn` (`:464,:505,:575`), entry-count validation in `SdArchiveCodec.kt:193` | closed |
| T-07-04 | Tampering | SdIndexRepository.kt | low | mitigate | header-size, count bounds and byte-offset validation in `SdArchiveCodec.kt:190-203`; `total == 0`, endFlag/MAX_PAGES caps in `SdIndexRepository.kt:93-104` | closed |
| T-07-05 | Denial of Service | FrameAssembler.kt | medium | mitigate | `GAP_TOLERANCE = 3`, `MAX_SKIP_GAP = 30`, `MAX_PENDING = 100`, bounded `recoverGap()` (`:42-44,:106-115`) | closed |
| T-07-06 | Tampering | QualityController.kt | low | mitigate | closed `Quality` enum makes invalid resolutions unrepresentable (`LiveSessionTypes.kt:52-54`); `ResolutionPolicy.forMode` before IOCTRL 4881 dispatch | closed |
| T-07-07 | Tampering | clean-clone-build.ps1 | high | mitigate | isolated temp clone with `--no-hardlinks` and detached full SHA (`:155-170`); signer equality + SHA-256 vendor pins verified in `check-release-apk.ps1:142-161,189-198` | closed |
| T-07-08 | Information Disclosure | check-release-apk.ps1 | high | accept | only 1 of 3 planned controls present: `debuggable=false` enforced (`:222,:226`). Missing: BuildConfig value inspection/stripping; automated no-debug-log-in-release check. Accepted by user for this release — see Accepted Risks Log | accepted |

*Status: open · closed · accepted — only open threats at or above `security_block_on=high` count toward `threats_open`.*
*Severity: critical > high > medium > low.*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-07-01 | T-07-08 | Release checker enforces debuggable=false, v2/v3 signatures, signer equality, package/version and 8 vendor-library SHA-256 pins. BuildConfig still emits `YI_API_BASE_URL` / `LOCAL_RTSP_ENABLED`, which are non-secret build-time settings; no credential or key is compiled into the APK. The remaining logcat control is covered procedurally by UAT test H-R14, which was attested as pass on the physical device and is recorded in `07-UAT.md`. No secrets are placed in BuildConfig by this phase. | User (explicit choice on 2026-11-19 security gate) | 2026-11-19 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-19 | 9 | 8 | 1 (accepted → AR-07-01) | gsd-security-auditor, ASVS L1 |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-19
