# Phase 5 — Device verification (events feed)

**Date:** 2026-11-04 (UAT session), probe sessions 2026-11-02 and 2026-11-03 · **Device:** Xiaomi Mi 9 (MIUI, API 30) over USB, Wi-Fi only (no mobile data) · **Build:** debug APK from `gsd/phase-5-events` including the session fixes (a43316d, f8ff4af, 4f97df7, 2658424, 5b47900, de20d23), installed via `adb install -r` (data kept) · **JVM gate on that build:** 1102 tests, 0 failures

**RESULT: PASS WITH OPEN ITEMS** — 9 of 10 checks passed, H-EV7 not exercised, 0 failed. Two of the passes (H-EV4, H-EV10) needed fixes that were made and re-checked in the same session. One open question for the user (H-EV3 day button label).

Values deliberately not recorded here: uid, DID, p2p id, user id, tokens, keys, passwords, media and API URLs, hosts, IPs, and the absolute times of events and recordings. The pulled diag log and the screenshots stayed in the session scratchpad (not committed). The only camera names written are the ones already used in the docs («Спальня», «YI Home Camera»).

## Probe sessions (2026-11-02 and 2026-11-03, the user pressed the probe button)

Debug probe «Проба событий (debug)» from Настройки; details in `05-PROBE.md`.

| Fact | Result |
|---|---|
| Feed request (`v2/alert/list`, signed GET) | `code=20000`, 18 events in the window |
| Time units | `time` and `expire_time` both 13 digits (milliseconds) |
| Link lifetime | 5..1436 min left, so links live up to about 24 h after the event |
| Camera id | event `uid` equals the camera `deviceId` 18/18 (and the p2p id); one source camera |
| Shared cameras | both cameras `shared=false`; no shared camera on this account |
| Media fields | `pic_urls` / `video_urls` hold one string each, scheme http; the same link over https works |
| Preview | https 200, AES decrypt ok, magic JPEG |
| Clip | https 200, AES decrypt ok, magic MP4, codec AVC (H.264) |
| `video02_*` | present but empty |
| `file_type` | mp4 on all events; no ts / tar / yitar (no HLS seen) |
| Retention | the feed held events of at least 2 days (today and yesterday, 23 events in the UAT session); longer retention not checked |

## Results (UAT session 2026-11-04)

| Item | Result | Evidence |
|---|---|---|
| H-EV1 Tab and feed (EVT-01) | PASS | Third tab «События» (Камеры, Настройки, События); cards with preview, camera name and time, groups «Сегодня» / «Вчера», 23 events; screenshots |
| H-EV2 Camera chips (EVT-02) | PASS | «YI Home Camera» (no events) -> «Нет событий для выбранного фильтра»; «Спальня» 23; «Все» 23; `[Events][vm][BLOCK_FILTER]` in the log |
| H-EV3 Day picker and label (EVT-02) | PASS, open question | Calendar dots on the two days with events; «Вчера» -> 2 events of that day; «Сбросить» returns all. Label «Все дни ▾» with no day selected deviates from the letter of D-03 («Сегодня ▾»): user decision pending |
| H-EV4 Clip in the dialog (EVT-03) | PASS after 2 fixes | Fresh event: clip plays, title «Спальня», time, «Повторить», «Закрыть», SD button enabled. Fixes: main-thread Room read (a43316d, f8ff4af), dead pooled connection (4f97df7). Cosmetic: thin green strip at the bottom edge |
| H-EV5 Clip link refresh (EVT-03) | PASS (log) | `[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=missing result=ok` once per open, then `[EventMedia][clip][BLOCK_FETCH_CLIP] status=200 magic=mp4`; 3 opens; the first, before fix 4f97df7, gave status=-1 |
| H-EV6 Jump to SD (EVT-04) | PASS (2 events) | «Спальня» (TNP, microSD): `[Events][clip][BLOCK_CLIP_OPEN_SD]`, `[LiveView][archive][BLOCK_ARCHIVE_OPEN_AT] result=seek`, `[Archive][playback][BLOCK_SEEK] result=Archive`, Starting -> Playing first-key; archive at «Запись HH:mm:ss» 8-12 s after the event time, same scene as the clip; back returns to «События» |
| H-EV7 Jump without SD / recording (EVT-04) | NOT EXERCISED | The only camera with events has a card; no event at a moment without a recording. JVM: ArchiveCoordinatorTest (QUEUED / FIRED / HIDDEN / IGNORED) |
| H-EV8 5-minute gate (EVT-06) | PASS | `[Event][sync][BLOCK_REFRESH_GATE] decision=too-early` on re-entry and pull, no `BLOCK_FETCH_PAGE`; after 5 min decision=fetch with exactly one `[Event][sync][BLOCK_FETCH_PAGE] page=1`; force-stop + reopen within 5 min -> too-early (gate mark in app_meta). Clip link refreshes not counted |
| H-EV9 Offline cache (EVT-05) | PASS | Wi-Fi off, force-stop, reopen: all cards with previews from the cache, subtitle «Обновлено 1 мин назад» (TooEarly, accepted); Wi-Fi back on |
| H-EV10 Log sweep (EVT-06, SEC-01) | PASS after fix | Forbidden-path grep 0 lines; media links in the log 0; RealLogScanTest over the pulled log (738 lines): tests=1 skipped=0 failures=0 |

## Details

### H-EV4: two defects found and fixed
1. Feed tap -> clip dialog failed: the dialog view model read the event row from Room on the main thread and Room threw `IllegalStateException`. Reproduced by a JVM test (a43316d), fixed by reading off the main thread (f8ff4af).
2. A clip opened a few minutes after the previous one failed in about 10 ms with status -1: the media OkHttp client was built with `retryOnConnectionFailure(false)`, so it reused a keep-alive connection that the CDN had closed while idle instead of reconnecting. Fixed in 4f97df7 (`retryOnConnectionFailure(true)`, OkHttp connection recovery only, no app-level retry; `error=<SimpleName>` added to the fetch lines when status=-1).

After both fixes the clip of a fresh event plays in the dialog. Remaining cosmetic issue: a thin green strip along the bottom edge of the clip, probably decoder padding shown by MediaPlayer on the TextureView. Not investigated, not fixed.

### H-EV5
The refresh reason is `missing`, not `stale` as the UAT text expected: media keys and links are kept only in memory (by design), so after a process restart every first open refreshes once. One refresh per open, no loop, so the check passes.

### H-EV6
Checked on two events of «Спальня». In both cases the archive started 8-12 s after the event time (the nearest recorded key frame), and the picture matched the scene of the clip.

### H-EV10: Phase 1 log hole found and fixed
The first sweep over the raw log failed on 2 `HTTP FAILED` lines: the OkHttp logging interceptor wrote the exception message, which carried the server IPv4 and the phone IPv4. This hole dates from Phase 1. Reproduced by a test (5b47900), fixed in de20d23 (only the exception `simpleName` and `after Nms` are logged), verification docs in c6163c4. The 2 old lines (from an earlier day) remain in the device log and were excluded from the sweep; all lines written after the fix passed.

## Other fixes and observations

- **Pull-to-refresh indicator:** the idle grey indicator hung over the filter row on the events tab. Fixed in 2658424, checked by screenshots.
- **Network:** on the nights of 2-4 November the phone's connection to the Yi cloud was unstable (DNS failures, timeouts). Not related to the code.
- **Login session:** the session lives 24 hours; after it expires the probe and the feed get session-expired and the user has to log in again.

## Player

MediaPlayer on a TextureView plays the clips (mp4, H.264) on MIUI. No unsupported-codec error, so there is no reason to switch to Media3 ExoPlayer.

## Gaps and open risks

No failed checks. Open items:

| Item | Observed | Likely area |
|---|---|---|
| D-03 day label | «Все дни ▾» with no day selected; D-03 says «Сегодня ▾» | user decision; `ui/events` day button text |
| Green strip under the clip | thin green line along the bottom edge | ClipDialog TextureView sizing / decoder crop |
| H-EV7 on the device | not exercised | needs an event of a camera without SD, or at a moment without a recording |
| Retention > 2 days | unknown | server side; the feed must not assume more |
| Shared cameras | not visible on this account (none shared) | AlertMapper / camera matching |
| HLS clips | never seen (all mp4) | HLS stays «Клип недоступен» |
| `wipeAll` delete result | ignored (deferred-items.md) | EventMediaFiles.wipeAll |
