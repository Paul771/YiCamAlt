---
phase: 01-secrets-camera-list
plan: 06
status: complete-branch-D
---

# Plan 01-06 summary

- Task 1: GRACE XML synced (commit 1f08b84), full suite green (165 tests), repo-guard range scan clean.
- Task 2: probe run on the user's Mi 9 over USB; first attempt failed at login because the default base URL is a placeholder (api.yicamalt.local); second run with https://gw-eu.xiaoyi.com gave 12 requests.
- Task 3: redaction sweep of the device log passed (executed, not skipped); results in 01-PROBE-RESULTS.md (commit ef6c8db).

**BRANCH: D.** No row has devices. Signing is proven good (friends list on EU answers 20000). Owner/shared lists fail on request content (code 20200 on EU, shareddetail 405). Plans 01-07..01-12 must not run until the missing request parameters are found (static APK analysis in progress, apk-mitm capture as fallback).

## Deviations / findings
- Default base URL in the app is a placeholder, so first login fails out of the box (to fix in plan 01-08 or earlier).
- Windows/MIUI device setup was slow: USB cable, Wi-Fi pairing failed (protocol fault on adb 37 and 35), MIUI blocks adb install (INSTALL_FAILED_USER_RESTRICTED); APK installed by hand from Downloads.
