# Phase 4 — Device verification (partial: no SD card, TUTK unreachable)

**Date:** 2026-10-31 · **Device:** Mi 9 (MIUI, API 30) over USB, Wi-Fi/LAN · **Build:** debug APK from `gsd/phase-4-sd-archive` at the 04-07 code (only docs commits after it), installed via `adb install -r` (data kept)
**RESULT: PARTIAL** — only the no-card state (H-SD6) and the redaction sweep are verified on the device. No reachable camera has a microSD card with recordings: the TNP camera «Спальня» reports no card (`tfstat=5`, same as the 04-04 probe), the TUTK camera «YI Home Camera» was unreachable (connect timeout). SD-01..SD-04 stay unverified on a device; the phase stays `human_needed`.

Values (uid, DID, passwords, tokens, IPs, absolute dates of recordings) are deliberately not recorded here. The pulled diag log stays in `build/device-logs/` (git-ignored); screenshots were not committed.

## Results

| Item | Result | Evidence |
|---|---|---|
| H-SD1 Day dialog with recorded days | NOT EXERCISED: no SD card | Dialog itself opens and works in the no-card case (see H-SD6), but no recorded days exist to show a dot |
| H-SD2 24 h timeline | NOT EXERCISED: no SD card | Timeline is not shown without a card (panel `NO_CARD` by design) |
| H-SD3 Play from a moment / wire-time base | NOT EXERCISED: no SD card | No `BLOCK_SEEK` / `BLOCK_PLAYBACK_STATE` in the log; `SdTime.WIRE_IS_UTC` still unverified with real recordings |
| H-SD4 Segment boundary / «Записи закончились» | NOT EXERCISED: no SD card | — |
| H-SD5 Pause / «Продолжить» / «Живой» | NOT EXERCISED: no SD card | — |
| H-SD6 Empty and error states | PASS (no-card state, TNP) | Screen: «Сегодня ▾» and «Нет SD-карты» under the live video, badge LAN Live; log `[Archive][status][BLOCK_SD_STATUS] transport=TNP tfstat=5 status=NoCard ms=537`, `[LiveView][archive][BLOCK_ARCHIVE_LOAD] status=NoCard index=skipped days=0 panel=NO_CARD`; no `BLOCK_FETCH_INDEX` in this session (no index request); day dialog opened (`BLOCK_ARCHIVE_ACTION action=open-days` / `close-days`) |
| H-SD7 Sound + background in the archive | NOT EXERCISED: no SD card | Needs an archive position; the live-only background return was checked separately (see "Live regression") |
| H-SD8 Fullscreen / zoom / snapshot in the archive | NOT EXERCISED: no SD card | — |
| H-SD9 TUTK camera | NOT EXERCISED: TUTK camera unreachable (connect timeout) | D-06 NOT applied (no evidence that TUTK playback fails); redaction sweep half: PASS (below) |
| H-SD10 Reconnect during archive playback | NOT EXERCISED: no SD card | — |
| Live regression with the archive panel present | PASS | Home then return within 5 min: `BLOCK_STOP reason=background`, `BLOCK_RESUME resumed=true ageMs=3994`, badge Connecting then Live in ~0.6 s; «Нет SD-карты» still shown; picture not black |
| Redaction sweep (`RealLogScanTest`) | PASS | Pulled 4645-line diag log: `tests=1 skipped=0 failures=0 errors=0`, BUILD SUCCESSFUL; no line flagged |

## Details

### H-SD1
Not exercised: no SD card. What was seen in the no-card case: the dialog shows a month grid starting on Monday, only today is selectable and highlighted, every other day is greyed; «Закрыть» closes it. Dots for recorded days and picking another recorded day need a camera with recordings.

### H-SD2
Not exercised: no SD card. Scroll, pinch (15 min..24 h), the centre cursor and coloured segments are unverified on a device (the ruler composable remains compile-only evidence).

### H-SD3
Not exercised: no SD card. Release-to-seek and the comparison of «Запись HH:mm:ss» with the camera's on-picture time (the check for `SdTime.WIRE_IS_UTC`, a 3 h offset = fail) are open.

### H-SD4
Not exercised: no SD card (needs two adjacent segments and the last recording).

### H-SD5
Not exercised: no SD card. Pause without `BLOCK_STALL`, «Продолжить» from the same moment and «Живой» without «Подключение…» are open.

### H-SD6
PASS for the no-card state on the TNP camera «Спальня»: the camera screen opened by tap, live video played with the LAN Live badge, under it «Сегодня ▾» and «Нет SD-карты». Only the status exchange was sent; the index request was skipped (`index=skipped`). The panel went `LOADING` -> `NO_CARD`. Not covered: «За этот день записей нет» (needs a carded camera with an empty day) and «Не удалось получить список» + «Повторить» (needs a carded camera and a list failure).

### H-SD7
Not exercised: no SD card. The muting on entering the archive, «Продолжить» at the saved position after Home, no auto-play and the hidden HD/SD button in that state are open. The live-only Home / return path still works with the archive panel present (row "Live regression"); that is Phase 3 behaviour, not H-SD7.

### H-SD8
Not exercised: no SD card (fullscreen overlay buttons in the archive, pinch zoom and snapshot during playback).

### H-SD9
Not exercised: the TUTK camera «YI Home Camera» was unreachable (connect timeout), so neither its SD status nor TUTK playback could be observed. D-06 (remove TUTK from `ArchiveSupport.transports`) requires evidence that a carded TUTK camera cannot play recordings; there is none, so `SdTypes.kt` is unchanged. Redaction sweep part: PASS (below).

### H-SD10
Not exercised: no SD card (Wi-Fi off / on during archive playback).

## Redaction sweep

`powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=E:/Dev/YiCamAlt/build/device-logs/diag_log.txt --rerun-tasks` -> BUILD SUCCESSFUL; JUnit report `tests=1 skipped=0 failures=0 errors=0`. The log covers the sessions since 2026-10-23 (4645 lines), including today's archive markers.

## Marker counts in today's session

`BLOCK_SD_STATUS` 1 (NoCard) · `BLOCK_ARCHIVE_LOAD` 1 (index=skipped) · `BLOCK_ARCHIVE_PANEL` 2 (LOADING, NO_CARD) · `BLOCK_ARCHIVE_ACTION` 2 (open-days, close-days) · `BLOCK_FETCH_INDEX` 0 · `BLOCK_SEEK` 0 · `BLOCK_PLAYBACK_STATE` 0 · `BLOCK_STREAM_MODE` 0 · `BLOCK_STALL` 0 · `BLOCK_STOP` 1 (background) · `BLOCK_RESUME` 1 (resumed=true).

## Not verified (pending, needs a microSD card with recordings)

- SD-01..SD-04: H-SD1..H-SD5, H-SD7, H-SD8, H-SD10 on a camera with a microSD card that has recorded for at least an hour.
- H-SD6 remaining states: «За этот день записей нет», «Не удалось получить список» + «Повторить».
- H-SD9: the TUTK camera powered on and reachable; D-06 decided from that evidence.
- Not verified at all: pinch / pan feel of the ruler, fullscreen overlay buttons in the archive, day dots, `WIRE_IS_UTC` with real recordings.

## Observations

- Six `BLOCK_DECODER_RESET reason=queue-overflow` at stream start in today's session: the known decoder startup hold (codec creation), live video then played normally.


## Session 2026-10-31, part 2: cameras with SD cards (orchestrator, adb + screenshots)

Both cameras now have a card. Values are not recorded here.

| Check | Result | Evidence |
|---|---|---|
| TUTK camera, SD status and index | PASS | `BLOCK_SD_STATUS transport=TUTK tfstat=0 status=Ok`, `BLOCK_FETCH_INDEX transport=TUTK result=Ok(count=57) pages=3 rejected=0 ms=282` (792/793 paging works, pages of 972, 972, 912 bytes) |
| H-SD2 timeline (TUTK) | PASS (look only) | Segment drawn at the recorded time, red cursor, hour ticks; scroll/pinch feel not judged beyond one drag |
| H-SD3 play from a moment (TUTK) | PASS on timing, content compare inconclusive | One drag of the ruler, `BLOCK_SEEK result=Archive snapped=false healthy=true`, `Live -> Starting (uc=3) -> Playing` in 1.0 s on first key frame; label «Запись» advanced 12:26:00 -> 12:26:29 in ~29 s. The recording sits at the wall-clock time the camera was recording, so no 3 h offset: `WIRE_IS_UTC = true` is consistent. The scene is static, so the picture could not be compared with the official app |
| H-SD5 pause / resume / live (TUTK) | PASS on logic | Pause sent 767 + 12718, position froze (12:26:55); «Продолжить» -> `Paused(USER) -> Starting (uc=4) -> Playing` in 1.0 s and the position continued; «Живой» sent 12718 + 8190, `Playing -> Live`, `gate-released` 0.7 s later, live picture and HD button back, no «Подключение…» |
| TNP camera with a fresh card (tfstat=4) | SD-05 empty day PASS | `BLOCK_SD_STATUS transport=TNP tfstat=4 status=Ok`, `BLOCK_FETCH_INDEX Ok(count=0)`; panel «За этот день записей нет», live video unaffected. No recordings yet, so TNP playback is still untested |
| H-SD4 boundary, H-SD7 background marker, H-SD8 fullscreen/zoom/snapshot, H-SD10 reconnect | NOT EXERCISED | Not driven yet |
| H-SD9 TUTK live regression | PASS | TUTK live worked after the 04-02 link changes (video, LAN badge) |
| Post-boot password rejection | OBSERVATION | After a power-on or reboot both cameras answered `WRONG_PASSWORD (-20009)` for a few minutes: TUTK about 9 min after the user switched it on, TNP about 2.5 min after the reboot, then accepted the same credentials with no code change. The supervisor retries a wrong password once, then asks the user to tap «Повторить» (D-03) |

### Defects found (to close with `--gaps` plans)

1. **Ruler stays on the old archive position after «Живой».** The video is live (`Playing -> Live`), but the ruler cursor and label stay at the last archive time (12:27:05) while the real time is later, and it does not follow live again. Expected: the cursor follows the current time in live, as before the first seek.
2. **«Загрузка записей…» spinner while the link is down or failed.** With a failed or retrying link (AuthFailed, retry badge) the archive panel shows the loading spinner indefinitely next to the red error.
3. **Stale recording list (verifier W-1).** The index is fetched once on connect; it is refreshed only when the day dialog is opened after 60 s. Today's timeline stops at the time it was loaded and later seeks are clamped to `last end - 6 s`.
4. **Reconnect loop after silence outside a known segment (verifier W-2).** See 04-VERIFICATION.md.

## Session 2026-10-31, part 3: gap-closure build (04-10..04-12) on the TUTK camera with a card

Build: 944 JVM tests, 0 failures, 1 skipped; installed with `adb install -r`. Values are not recorded here.

| Check | Result | Evidence |
|---|---|---|
| H-SD11 ruler follows the clock in Live and after «Живой» | PASS | Label 17:10:37 -> 17:11:16 -> 17:11:37 across screenshots; `Playing -> Live`, `gate-released` ~1.2 s later; the label stayed on the clock |
| H-SD13 recent footage seekable without the day dialog | PASS | `BLOCK_ARCHIVE_REFRESH reason=seek` -> `seek-resolve refreshed=true` -> `BLOCK_SEEK result=Archive` -> Playing 0.5 s; `reason=periodic` ~every 60 s; a seek past the end resolved to Live |
| H-SD10 reconnect during archive playback | PASS | Wi-Fi off: position frozen (`link-unhealthy`); `BLOCK_STALL mode=ARCHIVE keepsAttempt=false` at 35 s; Wi-Fi on: playback resumed from the same moment in 0.8 s (`BLOCK_SILENT_RESEEK count=1`); no SILENT, no Exhausted |
| H-SD12 no spinner on a failed link | PARTIAL | No spinner with Wi-Fi off, timeline kept; list reloaded on return. AuthFailed variant not reproduced |
| H-SD14 end of recording, no loop | PARTIAL | No loop observed; the camera records continuously so the natural end was not reached on the device; unit tests cover it |
| Observation | LIVE stall | 10 s LIVE stall at 17:13:47 about 30 s after returning from archive to Live; supervisor reconnected in ~3 s by itself (`attempt=2`). Not investigated; Phase 3 stall behaviour is unchanged |
| Redaction sweep (`RealLogScanTest`) | PASS | Log pulled after the session to a temporary file outside the repo (5966 lines); `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=<temp file> --rerun-tasks` -> BUILD SUCCESSFUL, JUnit `tests=1 skipped=0 failures=0 errors=0`; no line flagged. Temp file deleted after the run |

## Session 2026-10-31, part 4: TNP camera «Спальня» with recordings

The camera was already recording to the card (7 segments in today's index, `BLOCK_SD_STATUS tfstat=0`); nothing had to be switched on.

| Check | Result | Evidence |
|---|---|---|
| TNP index and timeline | PASS | `BLOCK_FETCH_INDEX transport=TNP result=Ok(count=7) pages=1`, segments drawn from ~16:50 |
| TNP play from a moment | PASS | `BLOCK_SEEK result=Archive`, `Live -> Starting (uc=7) -> Playing` in 0.2 s, label 18:11:26 -> 18:12:24 in ~58 s; picture is greyscale (night mode on the recording; not compared with the official app) |
| Snapshot and fullscreen in the archive | PASS | `BLOCK_SAVE_SNAPSHOT result=Saved`, `BLOCK_FULLSCREEN on=true source=button`, label kept |
| H-SD7 background / «Продолжить» | PASS | `Playing -> Paused(BACKGROUND)`, `BLOCK_CLOSE reason=background`, return 7 s later: `BLOCK_RESUME resumed=true`, panel shows «Продолжить» at 18:13:11 (no autoplay); tap -> Playing 0.2 s, label 18:13:19 |
| «Живой» on TNP | PASS | `Playing -> Live`, `gate-released` 0.2 s, ruler on the clock (18:29:08) |
| Not exercised | | segment boundary crossing (H-SD4), zoom gestures in the archive, calendar dots by eye, picture comparison with the official app, TNP over relay |
