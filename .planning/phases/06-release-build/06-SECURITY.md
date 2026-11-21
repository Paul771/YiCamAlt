---
phase: "06"
slug: "release-build"
status: verified
threats_open: 0
asvs_level: 1
created: "2026-11-21"
register_authored_at_plan_time: false
---

# Phase 06 — Security

> Retroactive STRIDE register (no `<threat_model>` existed in any 06-xx PLAN).
> 8 threats derived from implementation; 7 closed by existing controls, 1 low finding
> sanitized in the UAT artifact. Device UAT 06-UAT.md 6/6 pass.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Signing key → repo | Key and passwords must never enter Git | keystore, credentials |
| Clean clone → release APK | Isolated build, pinned vendor libs | source tree, native libs |
| Release APK → device | Signature, debuggable and version checks | APK |

---

## Threat Register

| Threat ID | Category | Severity | Disposition | Mitigation | Status |
|-----------|----------|----------|-------------|------------|--------|
| T-01 | Info — key/passwords in public Git | high | mitigate | `.gitignore:50-56`, `tools/git-hooks/pre-commit:37,89-90`, `new-release-key.ps1:162-228` (env passwords, refuse in-repo path); verified no key in tracked files or history | closed |
| T-02 | Tampering — unsigned/wrong-key APK | high | mitigate | `build.gradle.kts:48-59,27-28,275-295` v2/v3 signing + guard; checker live `verified=true v2=true v3=true signers=1` | closed |
| T-03 | Tampering — substituted vendor `.so` | high | mitigate | `build.gradle.kts:103-273` SHA-256 pins, `verifyVendorNatives`; checker `vendorLibs=8/8 otherLibs=0` | closed |
| T-04 | EoP — debuggable release | high | mitigate | `check-release-apk.ps1:222,226` aapt2 debuggable check; live `debuggable=false`, `run-as` refused on device | closed |
| T-05 | Tampering — wrong/rolled-back version | medium | mitigate | `build.gradle.kts:40-41` v1.0.2/10002; checker auto-detects from build file, mismatch → failure | closed |
| T-06 | Info — secrets in tool output / evidence | high | mitigate | all three scripts keep output in memory, print only booleans/digests-free anchors; 0 suspicious lines in device docs | closed |
| T-07 | Tampering — poisoned clean-clone build | medium | mitigate | `clean-clone-build.ps1:120-280` dirty precheck, TEMP clone + under-repo guard, CLONE_HYGIENE, `--no-build-cache`, guarded cleanup; live `RESULT=PASS`, `trackedChanged=0` | closed |
| T-08 | Info — absolute local key path in evidence doc | low | mitigate | path in `06-UAT.md` sanitized to `<userprofile>/.yicamalt/...`; no credential material exposed | closed |

---

## Accepted Risks Log

No accepted risks. All 8 threats closed at ASVS L1.

Follow-up (operational, not a security threat): make an offline backup of the signing key
and `release-signing.properties`. Do **not** rotate the key — a new signature would break
updates for already-installed app users. Tracked in `06-UAT.md` Deferred Follow-Ups.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-21 | 8 | 8 | 0 | gsd-security-auditor, ASVS L1 |

Pre-commit hook self-test: 13/13 cases pass.

---

## Sign-Off

- [x] All threats have a disposition
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-21
