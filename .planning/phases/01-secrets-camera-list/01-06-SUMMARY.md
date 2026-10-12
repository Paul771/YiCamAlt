---
phase: 01-secrets-camera-list
plan: 06
status: complete
---

# Plan 01-06 summary

- Task 1: GRACE XML synced (commit 1f08b84), full suite green (165 tests), repo-guard range scan clean.
- Task 2: probe run on the user's Mi 9 over USB; first attempt failed at login because the default base URL is a placeholder (api.yicamalt.local); second run with https://gw-eu.xiaoyi.com gave 12 requests.
- Task 3: redaction sweep of the device log passed (executed, not skipped); results in 01-PROBE-RESULTS.md (commit ef6c8db).

**BRANCH: A, DATA_REGION: US.** First runs (EU, SG logins) returned empty lists and were recorded as D; static APK analysis showed code 20200 means an empty list and our request equals the vendor's. A login on gw-us then returned 2 cameras on /v5/devices/list and working /v4/tnp/device_info (DID, InitString, License => TNP). Plans 01-07..01-12 may proceed. Plan 01-08 must discover the region by non-empty device list, because the same credentials log in on every region.

## Deviations / findings
- Default base URL in the app is a placeholder, so first login fails out of the box (to fix in plan 01-08 or earlier).
- Windows/MIUI device setup was slow: USB cable, Wi-Fi pairing failed (protocol fault on adb 37 and 35), MIUI blocks adb install (INSTALL_FAILED_USER_RESTRICTED); APK installed by hand from Downloads.
