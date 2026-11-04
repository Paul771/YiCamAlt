---
status: partial
phase: 05-events-feed
source: [05-CONTEXT.md, 05-RESEARCH.md, 05-PROBE.md, 05-00-SUMMARY.md .. 05-05-SUMMARY.md, 05-06-PLAN.md]
started: 2026-11-04
updated: 2026-11-04
---

## Current Test

[plan 05-07: device session done 2026-11-04 on the Mi 9 (build with the 05-07 fixes, full JVM gate 1102 tests, 0 failures). 9 passed, 1 not exercised (H-EV7), 0 failed; one open question (H-EV3 day button label). Evidence: 05-DEVICE-VERIFICATION.md.]

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
result: passed (2026-11-04): «События» is the third tab (Камеры, Настройки, События); cards with preview, camera name and time, grouped under «Сегодня» / «Вчера»; 23 events of two days in the feed

### H-EV2. Camera chips
requirement: EVT-02 (D-03)
steps: tap a camera chip; then another one; then «Все».
expected: only the events of the selected cameras stay; «Все» shows every camera again; the empty text «Нет событий для выбранного фильтра» appears if a selected camera has no events.
evidence: screenshot with one chip selected; `[Events][vm][BLOCK_FILTER] cameras=<n> day=false shown=<n>`.
result: passed (2026-11-04): chip «YI Home Camera» (no events) -> «Нет событий для выбранного фильтра»; chip «Спальня» -> 23; «Все» -> 23; `[Events][vm][BLOCK_FILTER]` lines in the log

### H-EV3. Day picker and the day button label
requirement: EVT-02 (D-03)
steps: look at the day button with no day selected; tap it; look at the marked days; pick a day with events; then «Сбросить». Repeat with today and with yesterday.
expected: the picker is the archive month grid; days with events are marked; picking a day shows only that day; «Сбросить» returns to all days. Button label: «Все дни ▾» with no day selected, «Сегодня ▾» / «Вчера ▾» / «d MMMM ▾» with a day selected.
note: отклонение от буквы D-03 (там «Сегодня ▾»), подтвердить глазами. The user confirms the label «Все дни ▾» or asks to change it (then it is a follow-up, not a failure of the filter).
evidence: screenshots of the picker and of the filtered list; `[Events][vm][BLOCK_FILTER] ... day=true`; the user's decision on the label.
result: passed, with an open question (2026-11-04): the calendar marks the two days with events; picking «Вчера» shows only that day's 2 events; «Сбросить» returns all. The label «Все дни ▾» with no day selected deviates from the letter of D-03 («Сегодня ▾»): waiting for the user's decision (follow-up, not a filter failure)

### H-EV4. Clip in the dialog
requirement: EVT-03 (D-02)
steps: tap a card with the play icon; watch the clip; tap «Повторить»; tap «Закрыть».
expected: the dialog shows a spinner, then plays the 6-second clip (picture, no black box); «Повторить» plays it again from the start; «Закрыть» returns to the list. If a clip cannot play, one of «Клип недоступен» / «Клип устарел, обновите ленту» / «Не удалось загрузить клип» / «Не удалось воспроизвести клип» is shown instead of a hang.
evidence: screenshot during playback; `[Events][vm][BLOCK_OPEN_CLIP]`, `[EventMedia][clip][BLOCK_FETCH_CLIP]` (status, size bucket, magic mp4), `[Events][clip][BLOCK_CLIP_PLAYER]` prepared / started.
result: passed after two fixes (2026-11-04): (1) the clip dialog read the event row from Room on the main thread (IllegalStateException), fixed in a43316d / f8ff4af; (2) a dead pooled connection of the media client failed the first clip download, fixed with retryOnConnectionFailure in 4f97df7. Now a fresh event's clip plays in the dialog: title «Спальня», time, «Повторить», «Закрыть», SD button enabled. Cosmetic: a thin green strip at the bottom edge of the clip (likely decoder padding), not fixed

### H-EV5. Clip of an older event (stale link)
requirement: EVT-03
steps: open the clip of the oldest event in the feed (the signed link of an event older than about a day has expired), or of an event whose clip was not opened before after the app has been in the background for a long time.
expected: the clip plays after one link refresh; no second refresh, no loop.
evidence: `[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=stale result=ok` exactly once for that open, then `BLOCK_FETCH_CLIP` with magic mp4.
result: passed by log (2026-11-04): `[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=missing result=ok` exactly once per open (media keys live only in memory, so after a restart the refresh reason is `missing`, not `stale`), then `[EventMedia][clip][BLOCK_FETCH_CLIP] status=200 magic=mp4`; three opens, the first one before fix (2) gave status=-1

### H-EV6. Jump to the SD recording
requirement: EVT-04 (D-02)
steps: in the clip dialog of an event of a camera with a microSD card tap «Открыть запись на SD»; wait for the archive panel; close the camera screen.
expected: the camera screen opens with the archive at the event moment: the ruler / cursor stands at the event time and the recorded picture matches the scene of the clip. Closing the camera screen returns to the «События» tab.
evidence: screenshot of the archive at the event moment; `[Events][clip][BLOCK_CLIP_OPEN_SD]`, `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT] result=FIRED`, `[Archive][playback][BLOCK_SEEK] result=Archive`.
note: no camera with a recording at the event moment -> `blocked (no SD card)`, not failed.
result: passed twice (2026-11-04, «Спальня», TNP with microSD, two different events): `[Events][clip][BLOCK_CLIP_OPEN_SD]`, `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT] result=seek`, `[Archive][playback][BLOCK_SEEK] result=Archive`, Starting -> Playing first-key; the archive opened at «Запись HH:mm:ss» 8-12 s after the event time, the scene matched the clip; back returns to the «События» tab

### H-EV7. Jump without SD or without a recording
requirement: EVT-04 (Phase 4 D-07)
steps: tap «Открыть запись на SD» for an event of a camera without a microSD card, or of an event at a moment without a recording.
expected: the camera screen opens with live video and the Phase 4 text («Нет SD-карты», or the hint «Нет записи в это время»; «Архив SD на этой камере недоступен» for a hidden archive); nothing hangs, the back button works.
evidence: screenshot; `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT]` with the result class (dropped / hint / HIDDEN).
result: not exercised (2026-11-04): the only camera with events has a microSD card, and no event at a moment without a recording was found; the logic (QUEUED / FIRED / HIDDEN / IGNORED) is covered by ArchiveCoordinatorTest on the JVM

Note before H-EV8: opening a clip may make one `v2/alert/list` request with a ±5 s window (link refresh, at most 6 per minute). It is user-initiated and does not count against the 5-minute feed gate. Only feed refreshes (30-day window, `[Event][sync][BLOCK_FETCH_PAGE]`) count in H-EV8.

### H-EV8. At most one feed refresh per 5 minutes
requirement: EVT-06 (D-04)
steps: right after a successful refresh leave the tab and come back; pull the list down; wait until 5 minutes have passed and pull again; then force-stop the app (Settings > Apps) and reopen it within 5 minutes, open «События» and pull.
expected: within 5 minutes neither re-entry nor pull makes a feed request and the subtitle reads «Обновлено N мин назад»; after 5 minutes one pull makes exactly one refresh (at most 10 pages); after force-stop and reopen within 5 minutes still no request (the gate is kept in app_meta).
evidence: `[Event][sync][BLOCK_REFRESH_GATE]` TooEarly lines with no `BLOCK_FETCH_PAGE` between them; after 5 minutes one run of `BLOCK_FETCH_PAGE` lines (count <= 10) ending in `BLOCK_SYNC_RESULT`; screenshot of the subtitle.
result: passed (2026-11-04): re-entry and pull within 5 minutes -> `[Event][sync][BLOCK_REFRESH_GATE] decision=too-early`, no `BLOCK_FETCH_PAGE`; after 5 minutes one pull -> decision=fetch with exactly one `[Event][sync][BLOCK_FETCH_PAGE] page=1`; force-stop + reopen within 5 minutes -> too-early again (mark kept in app_meta). Clip link refreshes are not counted

### H-EV9. Offline cache with previews
requirement: EVT-05 (D-04)
steps: with previews visible in the feed, turn on airplane mode; force-stop the app; reopen it and open «События».
expected: the cached cards are shown WITH their preview images (no placeholders for events that had previews before). Either subtitle is accepted: «Нет сети, показан кэш» (a refresh was attempted) or «Обновлено N мин назад» (the 5-minute gate skipped the request). Pass = cards + previews visible.
evidence: screenshot in airplane mode; `[Events][vm][BLOCK_REFRESH] result=Offline` or `result=TooEarly`.
result: passed (2026-11-04): Wi-Fi off (no mobile data on the phone), force-stop, reopen, «События»: every card with its preview from the cache, subtitle «Обновлено 1 мин назад» (TooEarly, accepted by the plan); Wi-Fi back on

### H-EV10. Log sweep and forbidden paths
requirement: EVT-06 + security (SEC-01)
steps: after H-EV1..H-EV9 pull the diag log (commands above); run `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=E:/Dev/YiCamAlt/build/device-logs/diag_log.txt --rerun-tasks`; then `grep -E "alert/events|push_prop|get_push|del_list|orderpay|/vas/|monitor/alarm|ai/alert" build/device-logs/diag_log.txt`; then `grep -cF "://" build/device-logs/diag_log.txt` (no link of any scheme in the log).
expected: RealLogScanTest executed (skipped=0) with failures=0; the forbidden-path grep prints 0 lines; the link grep counts 0, so no media URL host is in the log.
evidence: test report counts; both grep counts 0.
result: passed after a fix (2026-11-04; partial before): forbidden-path grep 0 lines, media links in the log 0; RealLogScanTest over the pulled log (738 lines) tests=1 skipped=0 failures=0. On the raw log it first failed on 2 old `HTTP FAILED` lines: the OkHttp logger wrote the exception message with the server and phone IPv4 (a Phase 1 hole); fixed in 5b47900 / de20d23 / c6163c4 (now only the exception simpleName and `after Nms`). Those 2 old lines from an earlier day are still in the device log and were excluded from the sweep; new lines were checked

## Summary

total: 10
passed: 9
issues: 0
pending: 0
partial: 0
skipped: 0
not_exercised: 1
blocked: 0
open_questions: 1

Passed after fixes made during the session: H-EV4 (two clip-dialog defects) and H-EV10 (HTTP FAILED log redaction). Also fixed during the session: the idle grey pull-to-refresh indicator hung over the filter row (2658424, checked by screenshots).

## Gaps

- No failures on the device. Open items, none a known functional defect:
  - H-EV3: the user has to confirm the day button label «Все дни ▾» (D-03 says «Сегодня ▾»).
  - H-EV4: thin green strip at the bottom edge of the clip (cosmetic, likely decoder padding).
  - H-EV7: the jump for a camera without SD or a moment without a recording, not exercised on the device (JVM-covered).
  - Event retention beyond 2 days is unknown (the feed held events of 2 days).
  - Events of shared cameras: none on this account. HLS clips: none seen (all mp4).
  - deferred-items.md: `EventMediaFiles.wipeAll` ignores the result of the delete (flaky once on Windows).
