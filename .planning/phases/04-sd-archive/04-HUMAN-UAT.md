---
status: pending
phase: 04-sd-archive
source: [04-CONTEXT.md, 04-RESEARCH.md, 04-PROBE.md, 04-08-PLAN.md]
started: 2026-10-31
updated: 2026-10-31
---

## Current Test

[awaiting the phone session (plan 04-09): Mi 9 over USB, HEAD APK installed over the current one (data kept), official Yi Home app closed]

## Pre-condition

- **A camera with a microSD card that has recordings** (see `04-PROBE.md` D-PROBE-2). On the 04-04 probe the TNP camera «Спальня» reported **no SD card** (tfstat=5, empty index) and the TUTK camera was unreachable, so no camera is known to have recordings.
- If none: the user inserts a microSD card into a camera and lets the camera record **at least one hour** before running 04-09.
- Without a card only **H-SD6** (the «Нет SD-карты» state) can be checked, on the TNP camera «Спальня». H-SD1..H-SD5, H-SD7, H-SD8 and H-SD10 (SD-01..SD-04) then stay `blocked (no SD card)`, not failed.
- H-SD9 needs the TUTK camera powered on and reachable.

Log pull (no `sh -c`, see verification-plan Gate-Phase-2 note-1):

```
adb exec-out run-as com.yicamalt cat files/diag_log.txt.1 > build/device-logs/diag_log.txt
adb exec-out run-as com.yicamalt cat files/diag_log.txt >> build/device-logs/diag_log.txt
```

Markers to look for: `[Archive][status][BLOCK_SD_STATUS]`, `[Archive][index][BLOCK_FETCH_INDEX]`, `[Archive][playback][BLOCK_PLAYBACK_STATE]`, `[Archive][playback][BLOCK_SEEK]`, `[LiveView][archive][BLOCK_ARCHIVE_LOAD]`, `[LiveView][archive][BLOCK_ARCHIVE_HIDE]`, `[LiveView][archive][BLOCK_ARCHIVE_TUTK_HIDE]`, `[LiveView][vm][BLOCK_ARCHIVE_ACTION]`, `[LiveView][archive-ui][BLOCK_ARCHIVE_PANEL]`, `[LiveView][archive-ui][BLOCK_TIMELINE_GESTURE]`, `[LiveSession][supervisor][BLOCK_STREAM_MODE]`, `[P2P][tnp][BLOCK_SEND_IOCTRL]`, `[P2P][tnp][BLOCK_IOCTRL_REPLY]`, `[P2P][tutk][BLOCK_STREAM_MODE]`.

## Tests

### H-SD1. Day dialog
steps: open a camera with a recorded SD card, tap the day button under the video.
expected: recorded days carry a dot; other days are greyed and not tappable (today stays tappable); the default selection is «Сегодня».
requirement: SD-01 (D-02)
result: [pending]

### H-SD2. Timeline of the selected day
steps: pick a day with recordings; scroll the ruler, pinch in and out.
expected: coloured segments of the selected day match the recordings (compare with the official app); scroll and pinch work (15 min..24 h); the cursor stays in the centre.
requirement: SD-02 (D-03)
result: [pending]

### H-SD3. Seek by releasing the timeline
steps: drag the ruler onto a recorded segment and release.
expected: playback starts at that moment; the «Запись HH:mm:ss» time matches the camera's own on-picture timestamp within a few seconds (confirms the wire-time base, `SdTime.WIRE_IS_UTC`).
requirement: SD-03 (wire-time base)
result: [pending]

### H-SD4. Segment boundary and end of recording
steps: start playback shortly before the end of a segment that has a following segment; then seek near the end of the last segment.
expected: playback continues across the segment boundary without user action; at the end of the last segment the panel shows «Записи закончились».
requirement: SD-03 (D-04)
result: [pending]

### H-SD5. Pause, «Продолжить», «Живой»
steps: during archive playback press pause, then «Продолжить», then «Живой».
expected: all three work on the same session without a reconnect (badge does not go through Подключение…); «Живой» returns to live video.
requirement: SD-04
result: [pending]

### H-SD6. Empty and error states
steps: open the TNP camera «Спальня» (no card); on a carded camera pick a day without recordings; if possible provoke a list failure.
expected: «Нет SD-карты» or «За этот день записей нет» while live video keeps playing; «Не удалось получить список» + «Повторить» when the list cannot be fetched.
requirement: SD-05 (D-07)
result: [pending]

### H-SD7. Sound and background in the archive
steps: unmute live sound, enter the archive; press Home (or lock), return within 5 min; tap «Продолжить».
expected: sound is off on entering the archive; after the return live video plays normally (picture neither black nor frozen); the panel shows «Продолжить» at the saved position and does not auto-play; tapping «Продолжить» seeks to the saved moment; in this state (Paused(BACKGROUND), inArchive) the HD/SD button is hidden by design.
requirement: SD-04 (D-05, Phase 3 D-10)
result: [pending]

### H-SD8. Fullscreen, zoom and snapshot in the archive
steps: during archive playback go fullscreen, pinch zoom, take a snapshot.
expected: fullscreen shows pause / «Живой» and the record time; pinch zoom works; the snapshot lands in Pictures/YiCamAlt.
requirement: SD-03 (D-01)
result: [pending]

### H-SD9. TUTK camera
steps: power on the TUTK camera, open it, wait for the archive panel; afterwards pull the diag log and run `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=E:/Dev/YiCamAlt/build/device-logs/diag_log.txt --rerun-tasks`.
expected: the archive works on TUTK, or is hidden with «Архив SD на этой камере недоступен» (D-06); RealLogScanTest executed (skipped=0) with failures=0.
requirement: SD-01..SD-05 (D-06)
result: [pending]

### H-SD10. Reconnect during archive playback
steps: while a recording plays, turn Wi-Fi off, wait for the retry badge, turn it back on.
expected: playback resumes at the same moment (± a few seconds) without user action; retry schedule as live (2 / 5 / 10 / 20 s, 5 attempts); no «Записи закончились» and no jump to another segment.
requirement: SD-03 (D-04)
result: [pending]

## Summary

total: 10
passed: 0
issues: 0
pending: 10
partial: 0
skipped: 0
blocked: 0

## Gaps
