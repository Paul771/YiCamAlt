---
phase: "05"
slug: "events-feed"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-11-21"
register_authored_at_plan_time: false
---

# Phase 05 — Security

> Retroactive STRIDE register (no `<threat_model>` existed in any 05-xx PLAN).
> 12 threats derived from implementation; all verified. Device UAT 05-UAT.md 12/12 pass.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| App → Yi Cloud alert API | Fetch gate, signing, throttling | signed requests |
| API response → event cache | Tolerant parse then validated write | event rows |
| Event → media client | Signed CDN links, never persisted | clip bytes |
| App → logs | Value-free markers only | counts, statuses |

---

## Threat Register

| Threat ID | STRIDE / Category | Severity | Disposition | Mitigation | Status |
|-----------|-------------------|----------|-------------|------------|--------|
| T-01 | DoS — request amplification / throttling | high | mitigate | `AlertSync.kt:36,64,88-96` 5-min gate + MAX_PAGES=10; `AlertSyncService.kt:157,429-472` mutex + 60 s/key, 6/min global; `AlertProbe.kt:101` MAX_REQUESTS=5 | closed |
| T-02 | T/E — destructive push/payment endpoints | high | mitigate | `AlertWire.kt:33` only `v2/alert/list`; `AlertListApi.kt:78-90` no defaults; `ForbiddenEndpointsTest.kt` deny+allow lists with self-check | closed |
| T-03 | S — invalid HMAC / signing key exposure | high | mitigate | `YiRequestSigner.kt:59-80` single HMAC-SHA1 signer, no logging; vendor order in `AlertWire.kt:51-59` | closed |
| T-04 | I — signed CDN URLs / hosts in logs | high | mitigate | `MediaHttp.kt:42-51` no logger; `EventMediaRepository.kt` only status/size/ext/magic; value-free `toString()` | closed |
| T-05 | I — secrets in HTTP logs (SEC-01) | high | mitigate | `LogRedactor.kt:48-109` masks account/password/hmac/userid/from/to and strips exception messages; device-verified all `██`, `RealLogScanTest` 2/2 | closed |
| T-06 | I — signed links/keys persisted | high | mitigate | `EventEntity.kt:37-50` no url/key column; `YiDatabaseTest.kt:116-126`; memory-only `MediaRefStore`; SHA-256 hashed filenames; logout wipes | closed |
| T-07 | T — path traversal via thumb_path | high | mitigate | `EventMediaFiles.kt:54-58` rejects absolute/backslash/`..`, requires `event_thumbs/` prefix | closed |
| T-08 | DoS — unbounded media body / cache growth | high | mitigate | `EventMediaRepository.kt:82-104,328-329` 2/8 MB caps; `EventMediaFiles.kt:116-139` 50 MB LRU; prefetch caps; `Semaphore(2)` | closed |
| T-09 | I — cross-account stale writes after logout | medium | mitigate | `EventCacheCleaner.kt:53,63-65,76-99` generation + writeIfCurrent; session re-check inside writes | closed |
| T-10 | I — debug probe in release / value leaks | medium | mitigate | `AlertProbe.kt:79` disabled-in-release, session + request cap; `AlertProbeShape.kt` value-free allowlists | closed |
| T-11 | T — cleartext media transport / MITM | medium | mitigate | `MediaHttp.kt:72-79` secureUrl http→https; cleartext off app-wide | closed |
| T-12 | T — malformed alert response poisons cache | low | mitigate | `AlertListApi.kt:49-59` tolerant code; `AlertMapper.kt:56-104` strict validation and dedup | closed |

---

## Accepted Risks Log

No accepted risks. All 12 threats closed.

Informational: `EventMediaFiles.wipeAll` ignores the `deleteRecursively()` return value — tracked robustness defect (`files=ok` may be reported on a false result); the logout-wipe control itself is present and verified.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-21 | 12 | 12 | 0 | gsd-security-auditor, ASVS L1 |

---

## Sign-Off

- [x] All threats have a disposition
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-21
