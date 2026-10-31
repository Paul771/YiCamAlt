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
