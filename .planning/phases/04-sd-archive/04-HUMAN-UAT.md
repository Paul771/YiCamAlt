---
status: partial
phase: 04-sd-archive
source: [04-CONTEXT.md, 04-RESEARCH.md, 04-PROBE.md, 04-08-PLAN.md]
started: 2026-10-31
updated: 2026-10-31
---

## Current Test

[plan 04-13: gap-closure build (04-10..04-12) installed; H-SD11..H-SD14 re-check pending on the carded cameras.] [plan 04-09 session done 2026-10-31 (Mi 9 over USB, 04-07 APK). Waiting for a camera with a microSD card that has recordings (and the TUTK camera reachable) to run H-SD1..H-SD5, H-SD7..H-SD10 and the rest of H-SD6. Evidence: 04-DEVICE-VERIFICATION.md]

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

Markers to look for: `[Archive][status][BLOCK_SD_STATUS]`, `[Archive][index][BLOCK_FETCH_INDEX]`, `[Archive][playback][BLOCK_PLAYBACK_STATE]`, `[Archive][playback][BLOCK_SEEK]`, `[LiveView][archive][BLOCK_ARCHIVE_LOAD]`, `[LiveView][archive][BLOCK_ARCHIVE_HIDE]`, `[LiveView][archive][BLOCK_ARCHIVE_TUTK_HIDE]`, `[LiveView][vm][BLOCK_ARCHIVE_ACTION]`, `[LiveView][archive-ui][BLOCK_ARCHIVE_PANEL]`, `[LiveView][archive-ui][BLOCK_TIMELINE_GESTURE]`, `[LiveSession][supervisor][BLOCK_STREAM_MODE]`, `[LiveSession][supervisor][BLOCK_STALL]` (keepsAttempt), `[LiveView][archive][BLOCK_ARCHIVE_REFRESH]`, `[Archive][playback][BLOCK_SILENT_RESEEK]`, `[P2P][tnp][BLOCK_SEND_IOCTRL]`, `[P2P][tnp][BLOCK_IOCTRL_REPLY]`, `[P2P][tutk][BLOCK_STREAM_MODE]`.

## Tests

### H-SD1. Day dialog
steps: open a camera with a recorded SD card, tap the day button under the video.
expected: recorded days carry a dot; other days are greyed and not tappable (today stays tappable); the default selection is «Сегодня».
requirement: SD-01 (D-02)
result: partial (2026-10-31, TNP + TUTK cameras with cards): both cameras load the index (TNP 7 segments, TUTK 59) and show the day button; the dot marks on the day dialog were not looked at this session, only that the dialog source (`days=`) is filled (`BLOCK_ARCHIVE_LOAD days=30` on TUTK, `days=1` on TNP) — calendar dots still unchecked by eye

### H-SD2. Timeline of the selected day
steps: pick a day with recordings; scroll the ruler, pinch in and out.
expected: coloured segments of the selected day match the recordings (compare with the official app); scroll and pinch work (15 min..24 h); the cursor stays in the centre.
requirement: SD-02 (D-03)
result: passed (2026-10-31): segments drawn at the recorded times on both cameras, red cursor, hour ticks, ruler recentres with the clock; pinch zoom not judged

### H-SD3. Seek by releasing the timeline
steps: drag the ruler onto a recorded segment and release.
expected: playback starts at that moment; the «Запись HH:mm:ss» time matches the camera's own on-picture timestamp within a few seconds (confirms the wire-time base, `SdTime.WIRE_IS_UTC`).
requirement: SD-03 (wire-time base)
note: recent footage on today without opening the day dialog (device defect 3 / W-1) is re-checked in H-SD13
result: passed (2026-10-31, TUTK and TNP): one drag + release -> `BLOCK_SEEK result=Archive`, Playing on the first key frame in 0.2 s (TNP) and 0.5-1 s (TUTK); label «Запись HH:mm:ss» advances at wall speed (TNP 18:11:26 -> 18:12:24 in ~58 s). Picture content vs the official app not compared

### H-SD4. Segment boundary and end of recording
steps: start playback shortly before the end of a segment that has a following segment; then seek near the end of the last segment.
expected: playback continues across the segment boundary without user action; at the end of the last segment the panel shows «Записи закончились».
requirement: SD-03 (D-04)
note: the end of the last recording without a reconnect loop (device defect 4 / W-2) is re-checked in H-SD14
result: not exercised: segment-boundary crossing was not reached on a device; unit tests cover it (see H-SD14 for the end of the last segment)

### H-SD5. Pause, «Продолжить», «Живой»
steps: during archive playback press pause, then «Продолжить», then «Живой».
expected: all three work on the same session without a reconnect (badge does not go through Подключение…); «Живой» returns to live video.
requirement: SD-04
result: passed (2026-10-31, TUTK and TNP): pause freezes the position, «Продолжить» restarts from the marker (TNP `Paused(BACKGROUND) -> Starting reason=resume` -> Playing 0.2 s, label 18:13:11 -> 18:13:19), «Живой» -> `Playing -> Live`, `gate-released` 0.2-1.2 s later with live picture, HD button back

### H-SD6. Empty and error states
steps: open the TNP camera «Спальня» (no card); on a carded camera pick a day without recordings; if possible provoke a list failure.
expected: «Нет SD-карты» or «За этот день записей нет» while live video keeps playing; «Не удалось получить список» + «Повторить» when the list cannot be fetched.
requirement: SD-05 (D-07)
result: pass (no-card state) — 2026-10-31 on the TNP camera «Спальня»: «Сегодня ▾» + «Нет SD-карты» under the live video, LAN Live badge; `BLOCK_SD_STATUS status=NoCard`, `BLOCK_ARCHIVE_LOAD index=skipped` (no index request); day dialog: Monday-first grid, only today tappable, «Закрыть» closes. Still open (need a carded camera): «За этот день записей нет», «Не удалось получить список» + «Повторить»

### H-SD7. Sound and background in the archive
steps: unmute live sound, enter the archive; press Home (or lock), return within 5 min; tap «Продолжить».
expected: sound is off on entering the archive; after the return live video plays normally (picture neither black nor frozen); the panel shows «Продолжить» at the saved position and does not auto-play; tapping «Продолжить» seeks to the saved moment; in this state (Paused(BACKGROUND), inArchive) the HD/SD button is hidden by design.
requirement: SD-04 (D-05, Phase 3 D-10)
result: passed (2026-10-31, TNP): sound muted on entering the archive; Home during playback -> `Paused(BACKGROUND)`, link closed (`BLOCK_CLOSE reason=background`), return after 7 s shows the last frame, marker 18:13:11 and «Продолжить» (no autoplay), no black screen

### H-SD8. Fullscreen, zoom and snapshot in the archive
steps: during archive playback go fullscreen, pinch zoom, take a snapshot.
expected: fullscreen shows pause / «Живой» and the record time; pinch zoom works; the snapshot lands in Pictures/YiCamAlt.
requirement: SD-03 (D-01)
result: partial (2026-10-31, TNP): snapshot saved in the archive (`BLOCK_SAVE_SNAPSHOT result=Saved`), fullscreen from the button keeps the «Запись» label and the picture, returns to portrait on Back; pinch/double-tap zoom not tried in the archive

### H-SD9. TUTK camera
steps: power on the TUTK camera, open it, wait for the archive panel; afterwards pull the diag log and run `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=E:/Dev/YiCamAlt/build/device-logs/diag_log.txt --rerun-tasks`.
expected: the archive works on TUTK, or is hidden with «Архив SD на этой камере недоступен» (D-06); RealLogScanTest executed (skipped=0) with failures=0.
requirement: SD-01..SD-05 (D-06)
result: passed (2026-10-31): TUTK live regression: video, LAN badge, HD button, archive index and playback on the TUTK camera (04-DEVICE-VERIFICATION.md, parts 2 and 3); redaction sweep PASS (RealLogScanTest, 5966-line log, 0 findings)

### H-SD10. Reconnect during archive playback
steps: while a recording plays, turn Wi-Fi off, wait for the retry badge, turn it back on.
expected: playback resumes at the same moment (± a few seconds) without user action; retry schedule as live (2 / 5 / 10 / 20 s, 5 attempts); no «Записи закончились» and no jump to another segment.
requirement: SD-03 (D-04)
result: passed (2026-10-31, TUTK camera): Wi-Fi off during archive playback froze the position at 17:02:18 (`link-unhealthy`), ARCHIVE stall at 35 s (`BLOCK_STALL mode=ARCHIVE keepsAttempt=false`), Wi-Fi on -> reconnect and playback resumed from 17:02:19 (`BLOCK_SILENT_RESEEK stalled=true count=1`, Starting -> Playing 0.8 s); no Paused(SILENT), no Exhausted

### H-SD11. Ruler follows live time after «Живой»
steps: open the camera; note the ruler label follows the clock (two screenshots ~5 s apart); drag onto a recorded segment and release (playback starts, label «Запись HH:mm:ss»); tap «Живой».
expected: within ~1 s the ruler label shows the current time again and keeps advancing (two screenshots ~5 s apart differ by ~5 s); it does NOT stay at the last archive time.
requirement: SD-02 / SD-04 (device defect 1, plan 04-11)
result: passed (2026-10-31, TUTK camera with card): in Live the ruler label advanced 17:10:37 -> 17:11:16 -> 17:11:37 between screenshots; after a seek to a recorded segment and «Живой» (`Playing -> Live`, `gate-released` 1.2 s) the label returned to the clock and kept advancing

### H-SD12. No spinner while the link is failed / retrying / waiting
steps: power the camera off (or turn the phone's Wi-Fi off) while the camera screen is open and wait for «Повтор (n/5)» / AuthFailed / Waiting; then power the camera back on / Wi-Fi on.
expected: the area under the day button is empty (no «Загрузка записей…» spinner); «Не удалось получить список» only if a list request really failed. After the badge returns to Live the timeline appears by itself (log `BLOCK_ARCHIVE_REFRESH` or `BLOCK_ARCHIVE_LOAD`). After a camera power-on the camera may reject the password for a few minutes (known) — that is the AuthFailed case to check.
requirement: SD-05 (device defect 2, plan 04-11)
result: partial (2026-10-31): with Wi-Fi off the panel kept the timeline and showed no spinner, playback position frozen (`reason=link-unhealthy`); the list reloaded itself after Wi-Fi returned (`BLOCK_ARCHIVE_REFRESH`/`LOAD`). The AuthFailed variant (password rejected after camera power-on) was not reproduced; covered by unit tests only

### H-SD13. Recent footage on today seekable without opening the day dialog (W-1)
steps: keep the camera screen open for more than 5 min (do not open the day dialog); drag the ruler to 2-3 min before now and release.
expected: playback starts at the chosen moment (label within a few seconds of the target); the segment on the ruler reaches close to now; log shows `BLOCK_ARCHIVE_REFRESH reason=periodic` at ~60 s intervals and / or `reason=seek` before the `BLOCK_SEEK`.
requirement: SD-02 / SD-03 (device defect 3, W-1, plan 04-12)
result: passed (2026-10-31): seek on today from Live to 17:00:58 without opening the day dialog: `BLOCK_ARCHIVE_REFRESH reason=seek` then `seek-resolve refreshed=true`, `BLOCK_SEEK result=Archive`, Playing 0.5 s later; `reason=periodic` fired at ~60 s intervals; a seek past the end of the list resolved to Live (`result=Live`) without a loop

### H-SD14. End of recording without a reconnect loop (W-2)
steps: drag to ~20 s before the last recorded moment (or right up to now on a continuously recording camera, then let it play into the end) and wait 60 s.
expected: «Записи закончились» within ~30 s after the picture stops; the badge never shows «Подключение…» (no `BLOCK_STALL ... mode=ARCHIVE`). If the camera goes silent mid-way instead: at most 2 reconnects, then «Нет записи в это время» with «Продолжить» and live video playing (`BLOCK_PLAYBACK_STATE ... reason=silent-reseek-cap`).
requirement: SD-03 (D-04; device defect 4, W-2, plan 04-10)
result: partial (2026-10-31): no reconnect loop in any observed case (seek past the end -> Live; playback 17:15 -> 17:17 on a continuously recording camera, periodic refresh kept extending the list, never reached the end). The natural end «Записи закончились» and the SILENT cap were not reached on the device (camera records continuously); covered by unit tests only

## Summary

total: 14
passed: 9
issues: 0
pending: 0
partial: 4
skipped: 0
not_exercised: 1
blocked: 0

## Gaps

- No failures on the device. Open coverage, none of it a known code defect: H-SD1 calendar dots by eye; H-SD4 crossing a segment boundary; H-SD8 zoom gestures in the archive; H-SD12 the AuthFailed variant; H-SD14 the natural end of the last recording (needs a camera that stops recording) and the SILENT cap on a device. TNP over relay (LIVE-07) and a Wi-Fi -> 4G switch need a phone with mobile data. Unit tests cover the logic behind each.
