---
phase: 02-p2p
plan: 09
subsystem: ui-live-probe
tags: [p2p, live-view, compose, surfaceview, viewmodel, tdd]
requires: ["02-01", "02-05", "02-07", "02-08"]
provides:
  - "LiveProbeViewModel: credentials -> CameraLinkFactory -> FramePipeline -> VideoDecoder, one open+start per press, close on leave"
  - "LiveProbeScreen: SurfaceView debug screen with Connect / Stop / relay-only, message and value-free status line"
  - "Camera list row tap -> AppShellScreen live overlay (onOpenCamera)"
affects: [02-10, 02-11, 02-12]
tech-stack:
  added: []
  patterns: ["per-press Session (decoder + pipeline + link) guarded by a lock; late factory result closed, never started", "injectable open classes (VideoSinkProvider, HevcProbe) instead of function types through Hilt"]
key-files:
  created:
    - app/src/main/java/com/yicamalt/ui/live/LiveProbeViewModel.kt
    - app/src/main/java/com/yicamalt/ui/live/LiveProbeScreen.kt
    - app/src/test/java/com/yicamalt/ui/live/LiveProbeViewModelTest.kt
  modified:
    - app/src/main/java/com/yicamalt/ui/cameras/CameraListScreen.kt
    - app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt
    - docs/development-plan.xml
    - docs/knowledge-graph.xml
    - docs/verification-plan.xml
decisions:
  - "Each Connect press owns a Session with a new decoder and pipeline; the press guard is 'a session exists', set on the caller thread before launching"
  - "Link close runs on a 'live-probe-close' thread (TnpCameraLink.close can wait ~2.2 s); decoder release stays on Main (bounded 1 s)"
  - "Status ticker is a real-time 1 s loop on Dispatchers.Default that only exists while a session is active"
  - "Closing the session on ON_STOP is not terminal for the VM: after returning to the app the user can press Connect again (no auto-reconnect)"
metrics:
  duration: 35min
  completed: 2026-10-15
  tasks: 2
  files: 8
---

# Phase 2 Plan 09: Debug live probe screen Summary

Tapping a camera in the list now opens a debug live screen. Connect makes exactly one `factory.open` and one `start(FramePipeline)` off the Main thread, and video goes to a SurfaceView through `VideoDecoder`. The screen shows one value-free status line and an explicit Russian text for every refusal. The session closes on back, on ON_STOP and when the screen leaves composition. No camera was contacted: there are JVM tests and a debug APK only.

## Tasks

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Wave-3 gate + LiveProbeViewModel with 17 JVM tests | 06aaf15 |
| 2 | LiveProbeScreen + camera-list navigation + GRACE docs; debug APK | 9f696a0 |

## Verification

- **Wave-3 gate:** before any change, `tools/gradle.ps1 :app:testDebugUnitTest` (full suite) gave BUILD SUCCESSFUL and `--- gradle exit=0` (log gradle-19532). Results: 429 tests, 0 failures, 0 errors.
- **Task 1:** `:app:testDebugUnitTest --tests "com.yicamalt.ui.live.*"` gave BUILD SUCCESSFUL, exit=0. `TEST-com.yicamalt.ui.live.LiveProbeViewModelTest.xml` reports tests="17" failures="0".
- **Mutation evidence (in place of RED):** the write hook rejects `TODO()` stubs, so I checked the tests against four deliberate bugs:
  - start even after close
  - no press guard
  - the offline text changed
  - resetEvents not wired

  5 of 17 tests failed (exit=1). After restoring the source the suite was green again.
- **Task 2:** `:app:testDebugUnitTest --tests "com.yicamalt.ui.*" :app:assembleDebug` gave BUILD SUCCESSFUL, exit=0, "vendor natives: OK (8 libs, manifest match)" and `APK_OK`. app-debug.apk is 19 MB, built at 19:53. Test results: CameraListViewModelTest 17/0, LiveProbeViewModelTest 17/0, LoginViewModelTest 5/0.
- **Acceptance greps:**
  - `factory.open(` appears on exactly one line (280).
  - The three START_BLOCK markers OPEN_PROBE / CONNECT_PRESSED / CLOSE_PROBE are present.
  - No Timber line contains deviceId.
  - SurfaceView is present.
  - ON_STOP and onDispose appear on 3 lines.
  - onOpenCamera is in both files.
  - The RENDER_CAMERA_LIST / RENDER_SHELL / NAVIGATE_SCREEN anchors are kept (3 lines).
  - No `/*` in the new files.
- **Redaction:** after every test, the Timber buffer and every UI snapshot (status line, message, hevcInfo, title) pass `RedactionScanner.assertClean`. The scan uses `ALL_SECRETS` plus the synthetic deviceId, DID, password, InitString and license.

## On-screen strings (for the plan 02-12 checkpoint script)

Buttons and labels: title (camera name, or "Камера"), checkbox "Только relay", button "Подключить" (enabled = canConnect), button "Стоп" (enabled = canStop). Below them are the HEVC line, the message (error colour) and the status line (monospace, selectable).

HEVC line: `HEVC-декодер: <name> (hw|sw)` or `HEVC-декодер: нет` (`HEVC-декодер: проверка…` until the probe answers).

Status line (one line, no values beyond enums, numbers, decoder names and booleans):
```
transport=TNP state=STREAMING mode=P2P lan=yes relayForced=no codec=H264 decoder=c2.qti.avc.decoder hw=yes fps=14.8 frames=312 keyFrames=6 decryptOk=yes auth=ok error=-
```
- `state`: IDLE | CONNECTING | CONNECTED | STREAMING | FAILED | CLOSED
- `mode` / `lan`: from LinkInfo (`-` before Connected; kept after a failure)
- `codec`: H264 | H265 | `-`
- `decoder`: the MediaCodec name, `none` for NoDecoder, `-` before the first key frame
- `fps`: from decoder Rendering
- `frames` / `keyFrames`: FramePipeline emitted / keyFrames
- `decryptOk`: yes | no | `-`
- `auth`: ok | fail | `-`
- `error`: `PpppCodes.name(code)` when code != 0, else the LinkErrorKind name

Messages:

| Condition | Text |
| --- | --- |
| no credentials (on open) | Нет P2P-данных камеры: обновите список камер |
| transport UNKNOWN | Транспорт камеры не определён: обновите список камер |
| transport LANGTAO | Транспорт Langtao не поддерживается |
| TUTK + Failed(UNSUPPORTED_TRANSPORT) | TUTK-камеры пока не поддерживаются |
| NATIVE_UNAVAILABLE | Нативные библиотеки не загружены |
| NO_CREDENTIALS | Нет P2P-данных камеры (пароль/лицензия): обновите список камер |
| ALREADY_OPEN | Сессия уже открыта |
| OFFLINE | Камера не в сети |
| CREDENTIALS | P2P-данные камеры устарели: выйдите и войдите снова |
| NETWORK | Нет связи с P2P-серверами |
| BUSY | Камера занята (много клиентов) |
| WRONG_PASSWORD | Неверный пароль камеры |
| REMOTE_CLOSED | Камера закрыла соединение |
| KICKED / Closed("kicked") | Сессию закрыла камера (другой клиент) |
| TIMEOUT | Нет ответа камеры |
| PROTOCOL | Ошибка протокола |
| INTERNAL | Внутренняя ошибка P2P (код <NAME>) |
| decoder NoDecoder / Unsupported / Failed | `DecoderStatus.userText()`, e.g. "H.265: на этом телефоне нет декодера" |

Log markers:
- `[LiveProbe][vm][BLOCK_OPEN_PROBE] transport=<T> verified=<b> hasCreds=<b>`
- `[LiveProbe][vm][BLOCK_CONNECT_PRESSED] relayForced=<b> press=<n>`
- `[LiveProbe][vm][BLOCK_STATUS] <status line>` (logged when the state name changes)
- `[LiveProbe][vm][BLOCK_CLOSE_PROBE] reason=<user|screen-left|background> frames=<n>`
- `[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=live`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] A RED commit was not possible (write hook rejects `TODO()` stubs)**
- **Fix:** I wrote the implementation and the tests together, then checked the tests with mutations (5/17 failed). This is the same approach as plans 02-05, 02-07 and 02-08. Task 1 has one commit.

**2. [Rule 1 - Bug] The 1 s ticker on the Main test dispatcher hung runTest**
- **Found during:** Task 1. The first run hung because runTest drains the virtual-time scheduler and an endless `delay` loop never lets it go idle.
- **Fix:** the ticker now runs in real time on Dispatchers.Default and posts the refresh to Main. It lives only inside the session scope.
- **Commit:** 06aaf15

**3. [Design] Session object instead of separate `pressing` / `closed` AtomicBooleans**
- The plan named two flags. Instead, the guarantees come from a per-press `Session`:
  - `ended` and `link` are guarded by the session lock.
  - `linkClosed` and `released` are AtomicBooleans.
- Effects:
  - A press with an existing session is ignored.
  - close() ends the session.
  - A factory result that arrives after close() is closed with "screen-left" and never started.
  - The link is closed exactly once and the decoder released exactly once.
- ON_STOP closes the session but not the VM, so after returning to the app the user can press Connect again. Nothing reconnects on its own.

**4. [Additions]**
- `LiveProbeScreen` takes an extra `title` parameter (the camera name from the list).
- `LiveProbeViewModel.onStop()` and `LiveProbeTexts` were added.
- A terminal link state (Failed/Closed) also ends the session: the link is closed once and the decoder released, so the TnpLinkFactory registry entry is freed.

**5. [Rule 2 - GRACE] Docs updated**
- development-plan.xml and knowledge-graph.xml list the new exports.
- New `CrossLink M-UI-SHELL -> M-UI-LIVE-PROBE`.
- V-M-UI-LIVE-PROBE has scenarios 6-9.
- The module STATUS stays `planned` until the device checkpoint.

## Notes

- `hiltViewModel()` in LiveProbeScreen is scoped to the host owner, the Activity. The VM therefore survives between cameras. `open(deviceId)` rebinds it, and every leave closes the session.
- While the probe is shown, AppShellScreen hides the top and bottom bars. Back returns to the list.
- I did NOT mark requirements LIVE-01, LIVE-02 or LIVE-03 complete. They close after the device checkpoint (02-12).

## Known Stubs

None.

## Self-Check: PASSED
