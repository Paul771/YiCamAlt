---
phase: 06-release-build
plan: 05
status: complete-with-gaps
requirements: [REL-02]
---

# Plan 06-05 summary: final debug-session redaction sweep (H-D1)

Result: H-D1 partial. The sweep passed; two scenario steps were not confirmed.

- Debug build 1.0.0 (commit fb7bd65) installed with `adb install -r`, diag log cleared before the session.
- Real LAN session on both cameras: live, HD/SD, SD archive, events clip, background and return.
- RealLogScanTest over the pulled diag log: device-log test executed, 0 failures. Forbidden paths 0, HTTP FAILED lines 0.
- Not confirmed: sound (the user did not answer) and the clip -> «Открыть запись на SD» jump into an existing recording (the event is older than the SD retention, screen showed «Нет записи в это время»).
- Gaps (details in 06-DEVICE-VERIFICATION.md): magenta/green blocks on bright areas of «Спальня», stale «Нет записи в это время» message after return from background, two WRONG_VIEWACCorPWD attempts on «YI Home Camera» right after the camera was powered on.
- No code changes. Debug build is still installed; 06-06 will remove it only after the user's «да».
