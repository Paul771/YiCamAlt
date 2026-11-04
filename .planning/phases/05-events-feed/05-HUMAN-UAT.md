---
status: pending
phase: 05-events-feed
source: [05-CONTEXT.md, 05-RESEARCH.md, 05-PROBE.md, 05-00-SUMMARY.md .. 05-05-SUMMARY.md, 05-06-PLAN.md]
started: 2026-11-04
updated: 2026-11-04
---

## Current Test

[plan 05-07: not started. Install the 05-06 debug APK on the Mi 9 and run H-EV1..H-EV10 in order.]

## Pre-condition

- Mi 9 over USB (adb), the debug APK of this branch installed, the user logged in.
- The official Yi Home app shows **at least one motion alert in the last days** (the feed needs real events; the 05-00 probe saw events of one camera only).
- **H-EV6** needs a camera with a microSD card that has a recording at the moment of one of the events. If there is none, H-EV6 is `blocked (no SD card)`, not failed, and **H-EV7** checks the «Нет SD-карты» path instead.
- Do not clear the app data before H-EV8 / H-EV9: they rely on the gate mark and the cache of the previous steps.

Log pull (no `sh -c`, see verification-plan Gate-Phase-2 note-1):

```
adb exec-out run-as com.yicamalt cat files/diag_log.txt.1 > build/device-logs/diag_log.txt
adb exec-out run-as com.yicamalt cat files/diag_log.txt >> build/device-logs/diag_log.txt
```

Markers to look for: `[Event][sync][BLOCK_REFRESH_GATE]`, `[Event][sync][BLOCK_FETCH_PAGE]`, `[Event][sync][BLOCK_SYNC_RESULT]`, `[Event][media][BLOCK_REFRESH_MEDIA]`, `[Event][map][BLOCK_MAP_ALERTS]`, `[EventMedia][thumb][BLOCK_FETCH_THUMB]`, `[EventMedia][thumb][BLOCK_PREFETCH_PASS]`, `[EventMedia][clip][BLOCK_FETCH_CLIP]`, `[EventMedia][clip][BLOCK_CLIP_REFRESH]`, `[Events][vm][BLOCK_REFRESH]`, `[Events][vm][BLOCK_FILTER]`, `[Events][vm][BLOCK_OPEN_CLIP]`, `[Events][clip][BLOCK_CLIP_LOAD]`, `[Events][clip][BLOCK_CLIP_PLAYER]`, `[Events][clip][BLOCK_CLIP_OPEN_SD]`, `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT]`, `[LiveView][archive][BLOCK_ARCHIVE_LOAD]`, `[Shell]` lines with `tab=live source=event`.

Evidence is value-free: screenshots stay on the phone or in `build/` (not committed), the log lines quoted here carry only markers, counts and result classes; no camera ids, event keys, links, hosts or times of recordings.

## Tests

### H-EV1. Events tab and the feed
requirement: EVT-01 (D-01, D-02)
steps: open the app; look at the bottom bar; tap «События»; wait for the subtitle; scroll the list.
expected: «События» is the third tab (after «Камеры» and «Настройки»); events of all cameras are listed with a preview, the camera name and the time, grouped under «Сегодня» / «Вчера» / «d MMMM» headers, newest first. Compare the number of events and the newest event time with Yi Home by eye (not recorded).
evidence: screenshot of the feed; `[Events][vm][BLOCK_REFRESH] trigger=enter result=Fetched`, `[Event][sync][BLOCK_SYNC_RESULT]`, `[EventMedia][thumb][BLOCK_PREFETCH_PASS]`; user report "matches Yi Home" / "differs".
result: pending

### H-EV2. Camera chips
requirement: EVT-02 (D-03)
steps: tap a camera chip; then another one; then «Все».
expected: only the events of the selected cameras stay; «Все» shows every camera again; the empty text «Нет событий для выбранного фильтра» appears if a selected camera has no events.
evidence: screenshot with one chip selected; `[Events][vm][BLOCK_FILTER] cameras=<n> day=false shown=<n>`.
result: pending

### H-EV3. Day picker and the day button label
requirement: EVT-02 (D-03)
steps: look at the day button with no day selected; tap it; look at the marked days; pick a day with events; then «Сбросить». Repeat with today and with yesterday.
expected: the picker is the archive month grid; days with events are marked; picking a day shows only that day; «Сбросить» returns to all days. Button label: «Все дни ▾» with no day selected, «Сегодня ▾» / «Вчера ▾» / «d MMMM ▾» with a day selected.
note: отклонение от буквы D-03 (там «Сегодня ▾»), подтвердить глазами. The user confirms the label «Все дни ▾» or asks to change it (then it is a follow-up, not a failure of the filter).
evidence: screenshots of the picker and of the filtered list; `[Events][vm][BLOCK_FILTER] ... day=true`; the user's decision on the label.
result: pending

### H-EV4. Clip in the dialog
requirement: EVT-03 (D-02)
steps: tap a card with the play icon; watch the clip; tap «Повторить»; tap «Закрыть».
expected: the dialog shows a spinner, then plays the 6-second clip (picture, no black box); «Повторить» plays it again from the start; «Закрыть» returns to the list. If a clip cannot play, one of «Клип недоступен» / «Клип устарел, обновите ленту» / «Не удалось загрузить клип» / «Не удалось воспроизвести клип» is shown instead of a hang.
evidence: screenshot during playback; `[Events][vm][BLOCK_OPEN_CLIP]`, `[EventMedia][clip][BLOCK_FETCH_CLIP]` (status, size bucket, magic mp4), `[Events][clip][BLOCK_CLIP_PLAYER]` prepared / started.
result: pending

### H-EV5. Clip of an older event (stale link)
requirement: EVT-03
steps: open the clip of the oldest event in the feed (the signed link of an event older than about a day has expired), or of an event whose clip was not opened before after the app has been in the background for a long time.
expected: the clip plays after one link refresh; no second refresh, no loop.
evidence: `[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=stale result=ok` exactly once for that open, then `BLOCK_FETCH_CLIP` with magic mp4.
result: pending

### H-EV6. Jump to the SD recording
requirement: EVT-04 (D-02)
steps: in the clip dialog of an event of a camera with a microSD card tap «Открыть запись на SD»; wait for the archive panel; close the camera screen.
expected: the camera screen opens with the archive at the event moment: the ruler / cursor stands at the event time and the recorded picture matches the scene of the clip. Closing the camera screen returns to the «События» tab.
evidence: screenshot of the archive at the event moment; `[Events][clip][BLOCK_CLIP_OPEN_SD]`, `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT] result=FIRED`, `[Archive][playback][BLOCK_SEEK] result=Archive`.
note: no camera with a recording at the event moment -> `blocked (no SD card)`, not failed.
result: pending

### H-EV7. Jump without SD or without a recording
requirement: EVT-04 (Phase 4 D-07)
steps: tap «Открыть запись на SD» for an event of a camera without a microSD card, or of an event at a moment without a recording.
expected: the camera screen opens with live video and the Phase 4 text («Нет SD-карты», or the hint «Нет записи в это время»; «Архив SD на этой камере недоступен» for a hidden archive); nothing hangs, the back button works.
evidence: screenshot; `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT]` with the result class (dropped / hint / HIDDEN).
result: pending

Note before H-EV8: opening a clip may make one `v2/alert/list` request with a ±5 s window (link refresh, at most 6 per minute). It is user-initiated and does not count against the 5-minute feed gate. Only feed refreshes (30-day window, `[Event][sync][BLOCK_FETCH_PAGE]`) count in H-EV8.

### H-EV8. At most one feed refresh per 5 minutes
requirement: EVT-06 (D-04)
steps: right after a successful refresh leave the tab and come back; pull the list down; wait until 5 minutes have passed and pull again; then force-stop the app (Settings > Apps) and reopen it within 5 minutes, open «События» and pull.
expected: within 5 minutes neither re-entry nor pull makes a feed request and the subtitle reads «Обновлено N мин назад»; after 5 minutes one pull makes exactly one refresh (at most 10 pages); after force-stop and reopen within 5 minutes still no request (the gate is kept in app_meta).
evidence: `[Event][sync][BLOCK_REFRESH_GATE]` TooEarly lines with no `BLOCK_FETCH_PAGE` between them; after 5 minutes one run of `BLOCK_FETCH_PAGE` lines (count <= 10) ending in `BLOCK_SYNC_RESULT`; screenshot of the subtitle.
result: pending

### H-EV9. Offline cache with previews
requirement: EVT-05 (D-04)
steps: with previews visible in the feed, turn on airplane mode; force-stop the app; reopen it and open «События».
expected: the cached cards are shown WITH their preview images (no placeholders for events that had previews before). Either subtitle is accepted: «Нет сети, показан кэш» (a refresh was attempted) or «Обновлено N мин назад» (the 5-minute gate skipped the request). Pass = cards + previews visible.
evidence: screenshot in airplane mode; `[Events][vm][BLOCK_REFRESH] result=Offline` or `result=TooEarly`.
result: pending

### H-EV10. Log sweep and forbidden paths
requirement: EVT-06 + security (SEC-01)
steps: after H-EV1..H-EV9 pull the diag log (commands above); run `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=E:/Dev/YiCamAlt/build/device-logs/diag_log.txt --rerun-tasks`; then `grep -E "alert/events|push_prop|get_push|del_list|orderpay|/vas/|monitor/alarm|ai/alert" build/device-logs/diag_log.txt`; then `grep -cF "://" build/device-logs/diag_log.txt` (no link of any scheme in the log).
expected: RealLogScanTest executed (skipped=0) with failures=0; the forbidden-path grep prints 0 lines; the link grep counts 0, so no media URL host is in the log.
evidence: test report counts; both grep counts 0.
result: pending
