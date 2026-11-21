---
phase: "02"
slug: "p2p"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-11-19"
register_authored_at_plan_time: false
---

# Phase 02 — Security

> Retroactive STRIDE register (no `<threat_model>` block existed in any 02-xx PLAN).
> Threats derived from implementation, then verified against code and UAT evidence.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Cloud → Credential store | Device list carries P2P credentials; stored encrypted, never in Room | password / InitString / license / DID |
| Camera wire → P2P stream | Untrusted IOCTRL/audio-video frames parsed before decode | binary frames, auth challenges |
| Vendor `.so` → process | Native TUTK/PPPP libraries loaded from APK | native code |
| Process → log sinks | Timber / diag logs | value-free markers only |

---

## Threat Register

| Threat ID | STRIDE category | Severity | Disposition | Mitigation | Status |
|-----------|-----------------|----------|-------------|------------|--------|
| T-02-01 | Information Disclosure — P2P secrets at rest | high | mitigate | `P2pCredentialStore.kt:138-146` AES256_GCM + EncryptedSharedPreferences (AES256_SIV keys); `CameraModule.kt:43` sole binding; `CameraEntity.kt` has no secret columns | closed |
| T-02-02 | Information Disclosure — secrets in logs | high | mitigate | `LogRedactor.kt:83-188`, value-free Timber markers, `RealLogScanTest` 2/2 on 200 app-tagged lines (UAT H7), 0 unredacted hits | closed |
| T-02-03 | Tampering / EoP — substituted vendor `.so` | high | mitigate | `build.gradle.kts:114-272` SHA-256 pin + `verifyVendorNatives` gate; `vendor-natives.sha256` 8 pins; `.gitignore` SEC-03 + pre-commit guard; `NativeLibs.kt:95-108` state machine | closed |
| T-02-04 | DoS — oversized/corrupt IOCTRL/frame head | high | mitigate | `TnpCodec.kt:93-101,126-134,163-187` bounds + `validateHead`; `IFrameDecryptor.kt:40-52` MIN_PAYLOAD=36; `SdArchiveCodec.kt:167-230` count bounds | closed |
| T-02-05 | Spoofing / Replay — weak transport auth | high | mitigate | `YiP2pAuth.kt:65-104` HMAC-SHA1 + 32-byte authInfo + random tail; SecureRandom at `TnpCameraLink.kt:115,164,388` and `TutkCameraLink.kt:120` | closed |
| T-02-06 | Info Disclosure — native debug logging | medium | mitigate | `PpppRuntime.kt:111` `configDebug(0, 4194311)` (debug off) | closed |
| T-02-07 | Info Disclosure — session/DID bytes exposed | medium | mitigate | `AndroidPpppNative.kt:24-25,48-53` address/DID reduced to boolean or zero-filled, never logged | closed |
| T-02-08 | Tampering — partial credential write | medium | mitigate | `P2pCredentialStore.kt:152-170` `replaceAll` atomic `commit()` under `@Synchronized`; in-memory store `@Volatile` atomic swap | closed |
| T-02-09 | Info Disclosure — stale credentials after logout | medium | mitigate | `CameraRegistry.kt:459` clear on logout; `P2pCredentialStore.kt:167-170` wipe committed | closed |
| T-02-10 | Spoofing — transport confusion downgrade | medium | mitigate | `P2pCredentials.kt:88-95` vendor rule from verified device_info; `CameraRegistry.kt:280` UNKNOWN on no verified record; `TransportLinkFactory.kt:51-63` TNP/TUTK only, others fail closed | closed |

---

## Accepted Risks Log

No accepted risks. All 10 threats closed by verified mitigations.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-19 | 10 | 10 | 0 | gsd-security-auditor, ASVS L1, retroactive STRIDE |

---

## Sign-Off

- [x] All threats have a disposition
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-19
