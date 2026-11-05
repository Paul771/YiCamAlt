---
phase: 06-release-build
plan: 01
subsystem: release
tags: [release, signing, keytool, pkcs12, secrets, D-01]
requires: ["06-00"]
provides:
  - tools/new-release-key.ps1 (key generator, -Check, -DryRun, -Prompt)
  - docs/RELEASE.md (user instruction incl. backup)
  - release key + release-signing.properties on this machine, outside the repo
affects: ["06-02 (app/build.gradle.kts reads release-signing.properties)"]
tech-stack:
  added: []
  patterns:
    - "passwords to keytool only via -storepass:env / -keypass:env, env var removed in finally"
    - "owner-only ACL via icacls with the current user SID"
key-files:
  created: [tools/new-release-key.ps1, docs/RELEASE.md]
  modified: [HANDOFF.md]
decisions:
  - "Properties values escape non-ASCII as \\uXXXX so java.util.Properties.load(InputStream) reads any user path correctly"
  - "Script saved as UTF-8 with BOM so Windows PowerShell 5.1 reads the Russian reminder line correctly"
metrics:
  duration: ~20 min
  completed: 2026-11-04
requirements: [REL-01]
---

# Phase 6 Plan 01: Release key tooling Summary

PKCS12 RSA-4096 release key generator with env-only password passing, in-repo/overwrite refusal and value-free `-Check`, plus the Russian release instruction; this machine's key is created outside Git.

## Tasks

| Task | Name | Commit |
|---|---|---|
| 1 | tools/new-release-key.ps1, docs/RELEASE.md, HANDOFF.md pointer | fc7a116 |
| 2 | Create this machine's key, prove no overwrite, value-free check | (no repo files; committed together with Task 1 per plan step 4) |

## Evidence (value-free transcript)

- `-DryRun` -> `result=dry-run target=userprofile-relative keytool=found alias=yicamalt-release`, exit 0
- `-OutDir <repo>/build/keytest` -> `result=refused code=RELEASE_KEY_PATH_IN_REPO`, exit 2; folder not created
- `-Check` before key -> `keys=0/4 store=missing acl=n/a`, exit 1
- Trial run in a scratch folder (deleted afterwards): created, `-Check` 4/4, second run exit 3; keystore opens with the stored password (keytool -list exit 0, storePassword == keyPassword, length 32)
- Real run -> `result=created alias=yicamalt-release files=2 acl=ok backup=REQUIRED`, exit 0
- Second run -> `result=refused code=RELEASE_KEY_EXISTS hint=back-up-and-reuse`, second-run-exit=3
- `-Check` -> `keys=4/4 store=found acl=ok`, exit 0
- Real keystore opens with the stored password (keytool -list exit 0)
- `git status --porcelain` showed only the 3 task files; tracked key/signing files = 0
- grep: contract/blocks = 6, `резервн` = 1, `storePassword=<literal>` = 0

## Key status

- Key: **created** (not reused), location pattern `%USERPROFILE%\.yicamalt\`.
- Backup by the user still pending (asked again in plan 06-06).

## Deviations from Plan

**1. [Rule 2 - Correctness] Non-ASCII escaping in properties values**
- Issue: `java.util.Properties.load(InputStream)` reads ISO-8859-1; a user path with non-ASCII characters would break `storeFile`.
- Fix: `ConvertTo-PropValue` escapes `\` as `\\` and chars outside 32..126 as `\uXXXX`.
- Commit: fc7a116

**2. [Rule 1 - Bug] UTF-8 BOM for the script**
- Issue: Windows PowerShell 5.1 reads BOM-less files as ANSI, the Russian reminder line would be garbled.
- Fix: file saved with UTF-8 BOM (other tools/*.ps1 are ASCII-only, unaffected).

**3. [Rule 2] Alias validation** — `-Alias` limited to `[A-Za-z0-9._-]` (exit 5 `BAD_ALIAS`), since it is written into the properties file unescaped.

**4. `-Check` prints `acl=n/a`** when the properties file is missing (instead of a misleading `ok`/`open`).

## Known Stubs

None. `tools/check-release-apk.ps1` and `tools/clean-clone-build.ps1` are referenced in RELEASE.md as coming in 06-02 / 06-03 (as planned).

## Self-Check: PASSED

- FOUND: tools/new-release-key.ps1, docs/RELEASE.md, HANDOFF.md pointer
- FOUND: commit fc7a116
