---
phase: 01-secrets-camera-list
plan: 11
status: complete
---

# Plan 01-11 summary

- Task 1: GRACE XML and PROJECT_STATUS synced for waves 4-6 (commit 298b335); full suite 278 tests, 0 failures; repo-guard range scan clean.
- Task 2: user verified on the Mi 9: 2 cameras = official Yi Home, pull-to-refresh, airplane-mode cache-first start with banner.
- Task 3: real device log pulled twice, redaction sweep passed (forced rerun, executed); clean-install login found region US automatically (logins=1 lists=1); evidence in 01-DEVICE-VERIFICATION.md (RESULT: PASS), commit 1cce476.

## Findings for later phases
- The two cameras use different transports: one TNP, one TUTK. Live view needs both (plan the TUTK adapter in phase 2 or right after the first TNP picture).
- TNP camera carries mismatch=true (list `type` vs p2pid prefix): decide before connecting.
- Hardening ideas (not leaks): cloud uid is stored in plain Room (it is the key for decrypting the camera password); RedactionScanner has no shape for the 20-char TUTK UID; hook is installed by copying, so a fresh clone must run tools/install-hooks.ps1.
