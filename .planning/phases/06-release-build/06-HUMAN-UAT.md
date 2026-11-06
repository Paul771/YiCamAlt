---
status: pending
phase: 06-release-build
source: [06-CONTEXT.md, 06-RELEASE-BUILD.md, 06-REMOTE-PRECHECK.md, 02-VERIFICATION.md, 03-VERIFICATION.md, 04-HUMAN-UAT.md, 05-HUMAN-UAT.md]
started: 2026-11-05
updated: 2026-11-05
---

## Current Test

[H-D1 partial (plan 06-05); pending: H-R1 in plan 06-06 (uninstall debug, install release after the user's «да»), H-R2..H-R14 in plan 06-07 (release build). H-R1..H-R14 not run yet.]

## Pre-conditions

- Mi 9 over USB (adb), screen unlocked, USB debugging allowed.
- Both cameras powered and online at home: «Спальня» (TNP) with a microSD card that has recordings, «YI Home Camera» (TUTK) with a microSD card that has recordings.
- The official Yi Home app is available on the Mi 9 or on the second phone (for H-R11).
- A second phone with mobile data for the hotspot (H-R6 / H-R7).
- The user types the Yi login himself. The executor never logs in, never logs out and never touches the Yi account.
- H-R6 / H-R7: auto-join of the home Wi-Fi is switched off on the Mi 9 for the test (or the home network forgotten) and switched back on afterwards.
- Release build facts: no Timber tree, so no diag log and no `[BLOCK_` lines; not debuggable, so `run-as` fails. The long-press probe screen (codec= / decoder= / mode= line, «Только relay») exists in every build type.

## Commands

Debug build only (H-D1), log pull without `sh -c`, rotated file first:

```
adb exec-out run-as com.yicamalt cat files/diag_log.txt.1 > <scratchpad>/diag-06.txt
adb exec-out run-as com.yicamalt cat files/diag_log.txt >> <scratchpad>/diag-06.txt
powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --rerun --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=<scratchpad>/diag-06.txt
```

Screenshots (any build):

```
adb exec-out screencap -p > <scratchpad>/<id>.png
```

Release logcat (H-R14). The app uid stays in the scratchpad only, never in a document:

```
adb shell pm list packages -U com.yicamalt
adb logcat -d --uid=<uid> > <scratchpad>/logcat-release-N.txt
fallback: adb logcat -d --pid=$(adb shell pidof com.yicamalt) > <scratchpad>/logcat-release-N.txt
powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --rerun --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogcatFile=<scratchpad>/logcat-release-N.txt
```

Codec line: long-press on the camera row -> probe screen -> «Подключить» -> the codec= / decoder= / mode= line.

Evidence is value-free: screenshots stay in the scratchpad (not committed); quoted log lines carry markers, counts, enum names and result classes only; no DID, uid, password, host, address, link or time of a recording.

## Tests

### H-D1. Debug session with the diag log and RealLogScanTest
requirement: REL-02, SEC-01 (plan 06-05, debug build)
steps: очистить diag log на телефоне (перед сессией); LAN live «Спальня» 1 мин и «YI Home Camera» 1 мин, на одной включить звук на 10 с; один раз переключить HD/SD; архив SD на обеих камерах (диалог дня, перемотка по шкале, «Живой»); «События»: открыть клип и «Открыть запись на SD»; Home и возврат в пределах 5 мин; снять лог.
expected: everything works; RealLogScanTest over the pulled diag log `tests=1 skipped=0 failures=0` for the device-log test; grep for forbidden paths `alert/events|push_prop|get_push|del_list|orderpay|/vas/|monitor/alarm|ai/alert` = 0; no `HTTP FAILED` line with an address (the 2026-11-04 fix).
evidence: markers `[P2P][tnp][BLOCK_CONNECT]`, `BLOCK_AUTH_REPLY`, `[Archive][playback][BLOCK_SEEK]`, `[Events][clip][BLOCK_CLIP_OPEN_SD]`, `BLOCK_STOP reason=background`, `BLOCK_RESUME`; RealLogScanTest result XML counts; grep counts.
result: partial — sweep passed (device-log test executed, 0 failures, forbidden paths 0, HTTP FAILED 0), both cameras LAN/HD-SD/archive/background ok; звук не подтверждён пользователем, клип -> «Открыть запись на SD» в существующий момент не выполнен (событие вне хранения SD, показано «Нет записи в это время»); gaps in 06-DEVICE-VERIFICATION.md

### H-R1. Release signer, debug removal and release install
requirement: REL-01, REL-02 (plan 06-06)
steps: сравнить подпись релиза с установленной debug-сборкой; попробовать `adb install -r app-release.apk`; после ответа пользователя «да» удалить debug-сборку и поставить app-release.apk; `dumpsys package com.yicamalt`.
expected: `signerSame=false`; `adb install -r` refused with INSTALL_FAILED_UPDATE_INCOMPATIBLE; after the user's «да» the debug app is removed and app-release.apk installed; versionName=1.0.0 versionCode=10000; the installed APK signer equals the built release APK.
evidence: checker lines, install result code, dumpsys version lines.
result: pending

### H-R2. Login by the user and the camera list
requirement: REL-02, CAM-01, CAM-02, CAM-03
steps: пользователь сам вводит логин Yi; открыть список камер.
expected: both cameras with name, model and online status.
evidence: screenshot of the list.
result: pending

### H-R3. Live LAN «Спальня» (TNP)
requirement: REL-02, LIVE-01
steps: Wi-Fi дома; открыть «Спальня»; подождать картинку; включить звук; переключить HD/SD.
expected: the picture moves within 20 s; badge LAN (or P2P with lan=yes); sound after unmute (the user hears it); HD/SD toggle switches quality.
evidence: screenshots, user report on sound.
result: pending

### H-R4. Live LAN «YI Home Camera» (TUTK)
requirement: REL-02, LIVE-01
steps: as H-R3 on «YI Home Camera».
expected: as H-R3.
evidence: screenshots, user report on sound.
result: pending

### H-R5. Codec and decoder (probe screen)
requirement: REL-02, LIVE-03
steps: долгое нажатие на каждую камеру -> экран пробы -> «Подключить» -> записать codec= и decoder=.
expected: codec= and decoder= hw recorded for each camera. H.265 only if a camera shows codec=H265; otherwise record verbatim «H.265: не проверено, нет камеры с H.265; аппаратный HEVC-декодер на телефоне есть».
evidence: screenshot of the probe line per camera.
result: pending

### H-R6. Remote «Спальня» via the hotspot (badge, LIVE-07)
requirement: REL-02, LIVE-02, LIVE-04, LIVE-07 (D-03)
steps: на втором телефоне включить точку доступа; Mi 9 подключить к ней (домашний Wi-Fi выключен / забыт на время теста), камеры остаются дома; открыть «Спальня»; затем долгое нажатие -> экран пробы -> «Только relay» -> «Подключить».
expected: the picture moves; badge P2P or Relay (never LAN); if Relay -> quality label SD right after connect (LIVE-07); if P2P -> record «LIVE-07 relay-SD: не проверено (P2P)». Probe with «Только relay»: mode RELAY / TCP_RELAY / SDEV_RELAY and a moving picture (relay path), or the verbatim error name.
evidence: screenshots of the badge, quality label and probe line.
result: pending

### H-R7. Remote «YI Home Camera» via the hotspot
requirement: REL-02, LIVE-02
steps: as H-R6 on «YI Home Camera» (relay toggle is disabled for TUTK by design).
expected: the picture moves, badge P2P or Relay. Known from 06-REMOTE-PRECHECK.md (debug build, 2026-11-04): TUTK remote connect fails (`UNKNOWN_-27` after ~30 s, badge «Повтор (n/5)»), relay for TUTK is not implemented. Record the observed result; if it fails the same way mark it `fail (known limitation: TUTK remote without relay, out of Phase 6 scope)`, not a new defect. Afterwards switch the home Wi-Fi auto-join back on.
evidence: screenshot of the badge or the error state.
result: pending

### H-R8. SD archive «Спальня»
requirement: REL-02, SD-01, SD-03, SD-04
steps: открыть архив; диалог дня; выбрать день; перемотать по шкале; пауза / «Продолжить» / «Живой».
expected: day dialog with marked days; timeline segments; seek plays within a few seconds with the camera time stamp matching «Запись HH:mm:ss» within ~5 s; pause / «Продолжить» / «Живой» work without reconnect.
evidence: screenshots of the dialog, the timeline and the playing frame with the label.
result: pending

### H-R9. SD archive «YI Home Camera»
requirement: REL-02, SD-01, SD-03, SD-04
steps: as H-R8 on «YI Home Camera».
expected: as H-R8.
evidence: as H-R8.
result: pending

### H-R10. Events feed
requirement: REL-02, EVT-01, EVT-03, EVT-04
steps: вкладка «События»; чипы камер; открыть клип; «Открыть запись на SD».
expected: «События» with previews and camera chips; the clip plays; «Открыть запись на SD» opens the archive at the event moment.
evidence: screenshots of the feed, the clip and the archive cursor.
result: pending

### H-R11. Parallel use with the official Yi Home
requirement: REL-02, LIVE-10 (D-03 discretion as Phase 3)
steps: наше приложение показывает камеру X; открыть камеру X в Yi Home на втором телефоне (или split screen на Mi 9); 2 мин оба показывают видео; закрыть Yi Home; повторить для второй камеры.
expected: both show video for 2 min; ours shows no «Камера занята» / «Сессию закрыла камера» and keeps the picture; after Yi Home is closed ours keeps playing; both cameras.
evidence: screenshots of both apps, user report.
result: pending

### H-R12. Background and return
requirement: REL-02, LIVE-10
steps: Home и возврат в пределах 5 мин; затем блокировка экрана более 5 мин и возврат; «Подключить».
expected: return within 5 min resumes by itself; after a lock > 5 min «Не подключено» is shown and «Подключить» works.
evidence: screenshots.
result: pending

### H-R13. 15+ minutes of continuous LAN viewing
requirement: REL-02, LIVE-11
steps: одна камера в LAN, экран не гаснет; скриншоты на 0 / 5 / 10 / 15 мин.
expected: the camera timestamp overlay moves between screenshots; no stop, prompt or reconnect; the screen stays on.
evidence: four screenshots (scratchpad only).
result: pending

### H-R14. Release logcat sweep
requirement: REL-02, SEC-01 (release)
steps: снять logcat приложения по uid после сессий 06-07; прогнать grep и RealLogScanTest.
expected: `grep -c "\[BLOCK_"` = 0 (release writes no app log lines); RealLogScanTest `-PredactionLogcatFile` -> the logcat test passed (or skipped as empty, recorded as such); any finding is recorded by rule name only.
evidence: grep count, RealLogScanTest result XML counts.
result: pending

## Gaps

(none yet)

## Known caveats carried in

- LIVE-07 relay SD default was never proven on the device (H-R6 records it only if the badge shows Relay).
- H.265 was never seen from these cameras (H-R5 records the verbatim text).
- Wi-Fi -> 4G switch with logs (Phase 3) is not a separate test here; H-R6 covers the remote path only.
- H-EV7 (jump for a camera without SD) stays not exercised; both cameras have SD now.
- H-SD1 day dots, H-SD4 day-boundary seek, H-SD8 timeline zoom: re-checked only where H-R8 / H-R9 touch them.
