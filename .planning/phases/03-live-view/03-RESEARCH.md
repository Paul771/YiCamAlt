# Phase 3: Полноценный live-view - Research

**Researched:** 2026-10-22
**Domain:** Android live-view UX over the existing vendor P2P links: reconnect supervisor, AAC audio, HD/SD switch, snapshot, fullscreen/zoom, lifecycle
**Confidence:** MEDIUM-HIGH. Vendor behaviour was read this session from the git-ignored decompile (`_re/jadx2|6|7|8`, Yi Home 6.9.8) and is cited as `file:line` (VERIFIED-LOCAL). Native exports were checked in the local `split_config.arm64_v8a.apk`. Android API facts come from official docs and AOSP (cited). Anything that only a real camera can confirm is marked DEVICE and listed under Human checks.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Автопереподключение и состояние связи (LIVE-04, LIVE-05)
- **D-01:** Переподключением владеет супервизор сессии поверх порта `CameraLink`. Сами звенья (`TnpCameraLink`, `TutkCameraLink`) по-прежнему не повторяют и не переподключаются (инвариант фазы 2).
- **D-02:** Backoff: паузы 2 / 5 / 10 / 20 с, не более 5 попыток подряд (около 40 с суммарно). После пятой неудачи экран показывает «Нет связи» и кнопку «Повторить»; автопопытки прекращаются до нажатия.
- **D-03:** Сетевые ошибки (таймаут, обрыв, камера офлайн, отказ сессии) повторяются по графику D-02. «Неверный пароль» - ровно один автоповтор с обновлением пароля из облака (как сейчас в `LiveProbeViewModel`) и стоп. «Камера занята / сессия закрыта камерой» (kick) - одна попытка через 10 с, дальше только вручную, чтобы не воевать с официальным приложением.
- **D-04:** Состояние связи - небольшая цветная плашка поверх видео: «Подключение…», LAN, P2P, Relay, «Повтор (n/5)», «Офлайн», «Ошибка пароля». Технические данные (кадры, fps, декодер, auth) - в раскрывающейся строке по нажатию на плашку (то, что сейчас видно на отладочном экране).

#### Звук и качество HD/SD (LIVE-06, LIVE-07)
- **D-05:** Звук при каждом открытии экрана выключен и не запоминается между запусками.
- **D-06:** Качество по сети: HD на LAN и P2P, SD на relay (требование LIVE-07). Пользователь может сменить качество вручную на время сеанса; выбор не сохраняется.
- **D-07:** Кнопки «Звук» и «HD/SD» скрываются, если камера этого не умеет (определяется по потоку/ответу камеры). Смена HD/SD выполняется командой камере в той же сессии, без переподключения; декодер перенастраивается по новому SPS (в `VideoDecoder` уже есть сброс при смене формата).

#### Полный экран, жесты, фон (LIVE-09, LIVE-10, LIVE-11)
- **D-08:** Полный экран: кнопка «На весь экран» плюс поворот телефона в альбомную даёт полный экран, возврат в портрет - обычный вид; выход - «назад» или кнопка. В полноэкранном режиме системные панели скрыты, управление (плашка связи, звук, HD/SD, снимок) появляется по тапу. Экран не гаснет, пока идёт видео.
- **D-09:** Жесты: pinch до 4x, перетаскивание увеличенного кадра в пределах границ, двойной тап - сброс или переключение «вписать/заполнить». Реализация на уровне преобразования поверхности без перекодирования.
- **D-10:** Фон: уход с экрана, сворачивание и блокировка закрывают сессию сразу (камера свободна для официального приложения, LIVE-10). Если пользователь вернулся в течение нескольких минут и экран камеры ещё открыт, поток запускается сам. Если вернулся надолго или вышел с экрана - остаётся кнопка «Подключить». Порог «нескольких минут» - на усмотрение Claude (ориентир 5 минут).
- **D-11:** Лимита времени просмотра и рекламы нет (LIVE-11): ничто в приложении не прерывает поток по таймеру.

### Claude's Discretion
- **Снимок кадра (LIVE-08):** галерея (MediaStore, папка Pictures/YiCamAlt), кадр из SurfaceView через PixelCopy, краткое подтверждение пользователю. Формат и имя файла - на усмотрение.
- Визуальное оформление, тексты, точный порог возврата в D-10, расположение кнопок.

### Deferred Ideas (OUT OF SCOPE)
None - discussion stayed within phase scope. Снимок кадра не обсуждался и оставлен на усмотрение Claude.

### Phase boundary notes (from CONTEXT.md)
- Архив SD (фаза 4) и лента событий (фаза 5) не входят.
- TNP-камера на реальной камере ещё не проверялась (02-VERIFICATION.md H1-H7); планы фазы 3 не должны предполагать, что TNP уже работает.
- Established patterns: белый `Surface` с чёрным текстом на экране камеры; секреты и идентификаторы никогда в логи, `toString`, строки UI.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| LIVE-04 | Состояние соединения видно (подключение / LAN / P2P / relay / офлайн / ошибка авторизации) | `LinkInfo.mode`/`lan` already exist (`CameraLink.kt:41-43`); badge mapping in Pattern 1; supervisor state machine adds "Повтор (n/5)", "Офлайн", "Ошибка пароля", "Нет связи" |
| LIVE-05 | Автопереподключение с ограниченным backoff | Supervisor (Pattern 1), error-class table (D-03 mapping), `NetworkMonitor` (Pattern 2), stall watchdog, drain-before-reopen rule (Pitfall 3) |
| LIVE-06 | Звук вкл/выкл, по умолчанию выкл | TNP ch1 / TUTK `avRecvAudioData` -> AAC-LC ADTS (codec 138), TNP AES-ECB decrypt of every full block (Pattern 3); MediaCodec `audio/mp4a-latm` + `AudioTrack`; local mute |
| LIVE-07 | HD/SD, SD на relay по умолчанию | 4881 SET_RESOLUTION `[u32 res, u32 ++useCount]` in transport byte order, reply 4882 (Pattern 4); `ResolutionPolicy.forMode` already HD on P2P/LAN, SD otherwise (`IoCtrlMessages.kt:148-160`); decoder re-creates on new size (`VideoDecoder.kt:209-237`) |
| LIVE-08 | Снимок в галерею | `TextureView.getBitmap(w,h)` (vendor does the same, `AntsVideoPlayer4.java:1726`) -> JPEG -> MediaStore `Pictures/YiCamAlt` with `IS_PENDING` (API 29+), `WRITE_EXTERNAL_STORAGE maxSdkVersion=28` for API 26-28 (Pattern 6) |
| LIVE-09 | Полный экран + жесты | `TextureView` instead of `SurfaceView` (overlays + transform + clipping), Compose `graphicsLayer` zoom up to 4x with clamped pan, `configChanges` on `MainActivity`, `WindowInsetsControllerCompat`, `keepScreenOn` (Pattern 5) |
| LIVE-10 | Поток останавливается в фоне / при уходе; одна сессия; официальное приложение не блокируется | ON_STOP / dispose -> supervisor.stop (existing close path, `LiveProbeScreen.kt:84-93`); auto-resume window 5 min (Pattern 7); one-session registry + `watchDrain` kept |
| LIVE-11 | Без лимита времени и рекламы | No timers after the first frame: link watch ends on the first video unit (`TnpCameraLink.kt:478-500`, `TutkCameraLink.kt:382-404`); the only new timer is the stall watchdog, which reconnects, never ends viewing |
</phase_requirements>

## Project Constraints (from AGENTS.md; no CLAUDE.md in repo)

- GRACE: every new/changed module gets `MODULE_CONTRACT`, `MODULE_MAP`, `CHANGE_SUMMARY`, function `START_CONTRACT`, paired `START_BLOCK_`/`END_BLOCK_` markers; contract first, then code.
- After adding/renaming modules: update `docs/knowledge-graph.xml`; after test/command/log-marker changes: update `docs/verification-plan.xml`; module records in `docs/development-plan.xml` use unique tags (`<M-...>`, `<V-M-...>`, `<Phase-N>`, `<step-N>`).
- Log format `[Module][function][BLOCK_NAME] key=value`; value-free logs (no DID, uid, password, nonce, InitString, license, IPs, camera serials). Phase 2 rule: Kotlin files in `stream`/`p2p` use `//` comments only (no block comments).
- No vendor binaries or decompiled code in git (`_re/` is read-only reference). No secrets in logs.
- Verification is only on the physical phone (no emulator). Builds/tests always through `tools/gradle.ps1` (system java is 1.8).
- Planner note: `V-M-UI-LIVEVIEW` in `docs/verification-plan.xml:1068-1090` currently names `connectedDebugAndroidTest` (emulator) and PTZ / camera selector scenarios. Both are out of reach / out of scope (CTRL-01, LIVE-13 are v2) and must be rewritten to JVM tests + device checks.

## Summary

Everything the phase needs on the wire already exists in the vendor client and is small. **Audio** is AAC (codec id 138) with an ADTS header per frame, 16 kHz mono on the prior-art TNP cameras; on TNP it arrives on PPPP channel 1 (ioType 2, 24-byte frame head) and, when the adopted header version is >= 2, every full 16-byte block of the payload is AES-ECB encrypted with the same key as I-frames (`password + "0"`). On TUTK it arrives through `avRecvAudioData` on the same AV channel and is not decrypted. The vendor turns audio on with IOCTRL 768 and off with 769, and plays only when the user unmuted. Our TNP burst already sends 768 and our TNP link already reads ch1 but throws it away; TUTK has neither an audio reader nor an IOCTRL reader yet. **HD/SD** is one command, 4881 `[u32 resolution, u32 ++useCount]` (1 HD, 2 SD), byte order of the transport, answered by 4882; the camera then emits a new GOP stamped with the new useCount, and our `VideoDecoder` already recreates the codec on a key frame with a new size/csd.

The UI side has two structural traps in the current code. First, `SurfaceView.setZOrderOnTop(true)` (needed on MIUI) puts the video surface **above the whole window**, so the D-04 badge and the D-08 tap-controls cannot be drawn over the video, and SurfaceView cannot be matrix-transformed or reliably clipped on Android 11. The vendor app itself uses a `TextureView` (`AntsVideoPlayer4`, `PhotoTextureView` with `setTransform` zoom and `getBitmap()` snapshot). Recommendation: switch the live screen to `TextureView` (MediaCodec renders into `Surface(surfaceTexture)`), zoom with Compose `graphicsLayer` inside a clipping box, snapshot with `getBitmap(videoW, videoH)`. Second, `MainActivity` has no `configChanges`, and `AppShellScreen` keeps the open camera in a plain `remember` (deliberately not saveable), so rotating the phone recreates the Activity, fires ON_STOP + dispose, closes the session and drops the user back to the camera list. `android:configChanges` on `MainActivity` is a prerequisite for D-08.

Reconnect belongs in a new pure-Kotlin `LiveSessionSupervisor` (D-01) that owns "one link at a time", the D-02/D-03 schedule, the existing single WRONG_PASSWORD retry, a `NetworkMonitor` (default-network callback), a stall watchdog, quality policy and the background-resume window. It must wait for the previous link to report a terminal state before opening the next one (Phase 2 LOW-8 invariant: the registry slot is freed only after the link threads exit), and must run all timing through an injected clock/dispatcher so it is tested in virtual time like `LiveProbeViewModelTest` already does.

**Primary recommendation:** Build `LiveSessionSupervisor` (pure Kotlin) + `NetworkMonitor` + `AudioPipeline`/`AacDecoder` + link extensions (audio sink, `requestResolution`, TUTK tx/ioctrl/audio threads) behind default methods on `CameraLink`, and a new `LiveViewScreen` on `TextureView` with `configChanges` on the Activity; keep the device-proven TNP burst and TUTK start sequence byte-for-byte unchanged.

## Standard Stack

### Core (all already in the project; no new dependency is required)
| Library / API | Version (verified in `app/build.gradle.kts`) | Purpose | Why standard |
|---|---|---|---|
| `android.media.MediaCodec` (async) | platform, minSdk 26 / target 34 | H.264/H.265 video (existing) + **AAC audio decode** (`audio/mp4a-latm`) | Platform HW/SW AAC decoder exists on every device; vendor uses a native faad wrapper (`DecodeAAC`) we must not ship |
| `android.media.AudioTrack` (Builder, `MODE_STREAM`, PCM 16-bit) | platform (Builder API 23+) | PCM playback | Same as vendor (`AntsAudioPlayer.java:243`) |
| `android.view.TextureView` + `Surface(SurfaceTexture)` | platform | Video output that composes like a normal view (overlays, transforms, clipping, `getBitmap`) | Vendor live player is TextureView-based (`AntsVideoPlayer4.java:49`, `PhotoTextureView.java:17`) |
| `ConnectivityManager.registerDefaultNetworkCallback` | API 24+ | Network lost / changed / regained for the supervisor | `ACCESS_NETWORK_STATE` already in manifest |
| `MediaStore.Images` + `RELATIVE_PATH` + `IS_PENDING` | API 29+ (legacy path for 26-28) | Snapshot into `Pictures/YiCamAlt` | Official scoped-storage path, no permission on 29+ |
| `androidx.core` `WindowCompat` / `WindowInsetsControllerCompat` | core-ktx 1.12.0 | Hide/show system bars (immersive, transient by swipe) | Backward-compatible insets API |
| Compose foundation gestures (`detectTransformGestures`, `detectTapGestures`), `graphicsLayer`, `clipToBounds` | compose-bom 2024.02.00 (ui/foundation 1.6.x) | Pinch/pan/double-tap and the zoom transform | Already on the classpath |
| kotlinx-coroutines + `kotlinx-coroutines-test` | 1.7.3 | Supervisor timers in virtual time | Already used (`RETRY_DELAY_MS` test) |

### Supporting
| Item | Purpose | When |
|---|---|---|
| JUnit 5.10.2, MockK 1.13.9, Robolectric 4.11.1 (present) | JVM tests of supervisor, ADTS parser, audio decrypt, link extensions, snapshot naming | Every plan |
| `FakePpppNative` v0.3.0 / `FakeTutkNative` v0.3.0 | Script ch1 audio units, 4882 replies, `avRecvAudioData` / `avRecvIOCtrl` results | Link extension tests (TUTK fake must gain audio/ioctrl queues) |

### Alternatives Considered
| Instead of | Could use | Tradeoff |
|---|---|---|
| TextureView | Keep SurfaceView (`setZOrderOnTop(true)`), zoom via View `scaleX/translationX` (API 24+ position sync, AOSP commit 6ee192f), snapshot via `PixelCopy.request(SurfaceView,…)` | Overlays (badge, controls) are hidden under an on-top surface; on Android 11 a scaled SurfaceView is not clipped by its parent, so a zoomed picture spills over the portrait layout. Only acceptable if overlays are moved outside the video rect. Not recommended. |
| MediaCodec AAC | Vendor `libaac`/`DecodeAAC`, FFmpeg | Extra proprietary/native surface; not needed |
| Supervisor in the ViewModel | Inline retry logic in `LiveProbeViewModel` | The VM already has 598 lines; D-01 asks for a supervisor; a pure class is testable without Android |
| `View.keepScreenOn` | `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on the Activity window | The view property is scoped to the video view and released automatically when it detaches |

**Installation:** none. Manifest changes only: `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"` on `.MainActivity`; `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />`. Shim change: three more `public static native` methods in `app/src/main/java/com/tutk/IOTC/AVAPIs.java` (see Pattern 3/4).

## Architecture Patterns

### Recommended structure (new files marked +, changed marked ~)
```
app/src/main/java/com/yicamalt/
├── stream/
│   ├── CameraLink.kt            ~ default methods: setAudioSink, requestResolution; LinkCapabilities flow; new Closed reasons
│   ├── StreamTypes.kt           ~ AudioUnit, AudioUnitSink, AudioCodec (AAC 138)
│   ├── AdtsParser.kt            + pure: split ADTS frames, sample rate / channels / object type, AudioSpecificConfig (csd-0)
│   ├── AudioPipeline.kt         + pure: decrypt (TNP v>=2), ADTS check, mute gate, availability detection, stats
│   ├── IoCtrlMessages.kt        ~ AUDIO_STOP 769, setResolution LE variant for TUTK, GET_RESOLUTION constants (unused)
│   └── LiveSession*.kt          + (or package com.yicamalt.live) supervisor, retry policy, quality policy, NetworkMonitor port
├── p2p/tnp/TnpCameraLink.kt     ~ ch1 -> AudioUnitSink; useCount counter; requestResolution via the tx queue; 4882 abilityRet
├── p2p/tutk/TutkCameraLink.kt   ~ tutk-<n>-tx (command queue), tutk-<n>-rxc (avRecvIOCtrl), tutk-<n>-rxa (avRecvAudioData); 768 after start
├── p2p/tutk/TutkNative.kt       ~ recvAudio, recvIoCtrl, cleanAudioBuf
├── p2p/tutk/AndroidTutkNative.kt~ bind the 3 new natives
├── com/tutk/IOTC/AVAPIs.java    ~ avRecvAudioData, avRecvIOCtrl, avClientCleanAudioBuf (exact vendor signatures)
├── media/
│   ├── VideoDecoder.kt          ~ publish video size from onOutputFormatChanged (crop) for aspect ratio
│   ├── AacDecoder.kt            + MediaCodec audio/mp4a-latm (own HandlerThread) -> AudioTrack
│   └── SnapshotSaver.kt         + Bitmap -> JPEG -> MediaStore Pictures/YiCamAlt
├── system/AndroidNetworkMonitor.kt + ConnectivityManager default-network callback -> StateFlow
└── ui/live/
    ├── LiveViewModel.kt         + screen state over the supervisor (badge, controls, debug row)
    ├── LiveViewScreen.kt        + TextureView, badge, controls, fullscreen, gestures, snapshot
    └── LiveProbe*.kt            = keep unchanged (debug entry, TNP H1-H7 checks need "Только relay")
```
GRACE IDs: implement the planned `M-UI-LIVEVIEW` (rewrite its contract: no PTZ/camera selector), add one new module for the supervisor (suggest `M-LIVE-SESSION`, `V-M-LIVE-SESSION`) and one for audio (`M-MEDIA-AUDIO` or fold into `M-MEDIA-RENDER`); snapshot can use the planned `M-MEDIA` record (rewrite its contract; its download/thumbnail exports belong to phase 5) or a new `M-MEDIA-SNAPSHOT`. Changed modules: `M-STREAM-CORE`, `M-TNP-LINK`, `M-TUTK-LINK`, `M-MEDIA-RENDER`, `M-UI-SHELL` (route to the new screen), possibly `M-UI-LIVE-PROBE` (entry point only).

### Pattern 1: LiveSessionSupervisor (D-01..D-04, LIVE-04/05)
**What:** one object per open camera screen; owns the current link + pipeline + decoder session and all retry decisions. Links stay one-shot.
**State machine (value-free):**
```
Idle ──start()──► Connecting(attempt n) ──Connected/Streaming──► Live(mode, lan)
Live ──link Failed / Closed(kicked) / stall / network-change──► classify ──►
   NETWORK class  : Waiting(n+1, delay = [2,5,10,20][n-1]) ──► Connecting   (n <= 5), else Exhausted("Нет связи" + Повторить)
   WRONG_PASSWORD : first time in this run -> Waiting(2 s) + refreshCredentials -> Connecting; second -> AuthFailed("Ошибка пароля")
   KICK class     : first time -> Waiting(10 s) -> Connecting; second -> Kicked (manual only)
   FATAL          : Stopped(message) (manual only)
Any state ──stop(reason)──► Idle (cancels waits; closes link; remembers reason + time for D-10)
Any Waiting/Connecting while NetworkMonitor says "no network" ──► NoNetwork (badge "Офлайн"), attempts NOT consumed;
   network back ──► Connecting immediately with the attempt counter reset to 1
retry() (button) ──► attempt counter reset, Connecting
Attempt counter resets to 0 after a session reached Streaming and stayed up >= 30 s (discretion; prevents a flapping
   link from living forever on 5 attempts, and a long-running stream from inheriting an old count)
```
Attempt n = n-th consecutive failed open. Delay list from D-02 is the pause **before** attempts 2..5; after the fifth failure stop. Note: each attempt itself can take up to `connectMs` 30 s + `firstFrameMs` 20 s (link timeouts, `TnpCameraLink.kt:94-102`), so wall time can exceed 40 s; D-02 "≈40 s" is the sum of pauses.

**Error-class table (D-03 mapping onto existing `LinkErrorKind`, `CameraLink.kt:45-48`):**
| Class | LinkErrorKind / state | Rationale |
|---|---|---|
| NETWORK (schedule D-02) | `OFFLINE`, `NETWORK`, `TIMEOUT`, `REMOTE_CLOSED`, `PROTOCOL` (-9000), `INTERNAL`, `ALREADY_OPEN`, `Closed("stall")`, `Closed("network-change")` | Vendor reconnects on -9000 after 1 s and on -3012/-3013 after 3 s (`AntsCamera.java:~300-318`); `ALREADY_OPEN` here means "previous link still reaping" (LOW-8), transient |
| WRONG_PASSWORD (one refresh + retry) | `WRONG_PASSWORD` (-20009 TNP authResult 1/2, TUTK -20009/-20040) | Existing VM behaviour (`LiveProbeViewModel.kt:433-479`), move it into the supervisor unchanged (2 s, `refreshCredentials` off Main) |
| KICK (one attempt after 10 s) | `KICKED`, `BUSY` (-3017/-3029/IOTC -18/-48), `Closed("kicked")` (TNP 9033, `TnpCameraLink.kt:403-409`) | D-03; note TNP kick ends as `Closed("kicked")`, not `Failed(KICKED)` |
| FATAL (manual only) | `NATIVE_UNAVAILABLE`, `NO_CREDENTIALS`, `UNSUPPORTED_TRANSPORT`, `CREDENTIALS` | Retrying cannot help; message tells the user what to do |
| Decoder (no reconnect) | `DecoderStatus.NoDecoder/Unsupported/Failed` | Not a link problem; show text |

**Badge mapping (D-04):** Connecting/Waiting(first attempt) -> «Подключение…»; Live: `lan==true` -> «LAN», mode `P2P` -> «P2P», `RELAY/TCP_RELAY/SDEV_RELAY` -> «Relay», TUTK `LAN` -> «LAN», `UNKNOWN` -> «P2P?» (discretion); Waiting(n>=2) -> «Повтор (n/5)»; NoNetwork or last error OFFLINE after exhaustion -> «Офлайн»; AuthFailed -> «Ошибка пароля»; Exhausted -> «Нет связи» + «Повторить». Tap expands the existing value-free status line (move `statusLine()` from `LiveProbeViewModel.kt:555-587`).

**Stall watchdog (LIVE-05):** while Live, if `pipeline.stats().framesIn` has not increased for `STALL_MS` (suggest 10 s), close the link with reason `stall` and classify as NETWORK. Needed because TNP has no post-start read timeout (a dead PPPP session only surfaces when the vendor lib reports -3013), while TUTK already ends after 300 x 30 ms of -20012 (`TutkCameraLink.kt:306-313`).

**Testability:** constructor-inject `CameraLinkFactory`, `P2pCredentialsSource`, `NetworkMonitor`, a `SessionFactory` (creates pipeline + video sink + audio pipeline; fake in tests), `CoroutineScope`, an `ioDispatcher` for `factory.open` (blocking: TUTK init up to 10 s, `TutkCameraLink.kt:583-584`) and `now: () -> Long` (`SystemClock.elapsedRealtime` in prod). All delays via `delay()` so `runTest`/`advanceTimeBy` drive them. Expose `state: StateFlow<SupervisorState>`.

**Drain rule (Pitfall 3):** before every new `factory.open` for the same camera, await `previousLink.state.first { Failed || Closed }` (bounded, e.g. 5 s), exactly what `watchDrain` does today (`LiveProbeViewModel.kt:499-510`).

### Pattern 2: NetworkMonitor
```kotlin
// Source: ConnectivityManager.registerDefaultNetworkCallback (API 24+), Android docs
interface NetworkMonitor { val state: StateFlow<NetState> }      // NetState(available: Boolean, generation: Int)
class AndroidNetworkMonitor @Inject constructor(@ApplicationContext ctx: Context) : NetworkMonitor {
    // onAvailable(network): available=true; if network != last -> generation++ (default network changed)
    // onLost(network): if network == last -> available=false
    // register once (Singleton); onAvailable fires immediately when a default network already exists
}
```
Supervisor rules: `available=false` -> pause (NoNetwork, no attempts consumed, do not close a streaming link yet: let the link fail by itself or the stall watchdog fire, because a short Wi-Fi blip may recover); `generation` changed while Live -> debounce ~1.5 s, then close(`network-change`) and reconnect at once (old UDP sockets are bound to the old network). Do not gate on `NET_CAPABILITY_VALIDATED`: a LAN-only Wi-Fi without internet can still reach the camera (INFERENCE; vendor LAN search). Never log network identifiers.

### Pattern 3: Audio (LIVE-06)
**Wire facts (VERIFIED-LOCAL):**
| Item | TNP | TUTK |
|---|---|---|
| Source | PPPP channel 1, `ThreadRecvAudio extends ThreadRecv((byte)1)` (`TnpCamera.java:1208-1211`) | `AVAPIs.avRecvAudioData(av, buf[1024], 1024, frameInfo[24], 24, int[1] frameIndex)` loop (`TutkCamera.java:894-969`, signature `AVAPIs.java:146`) |
| Unit | TNPHead ioType **2** and size > 24, then 24-byte frame head (same layout as video, BE) + payload (`TnpCamera.java:1215-1219`) | return value = payload size, 24-byte FRAMEINFO (LE) |
| Codec | `AUDIO_CODEID_AAC = 138` (`AntsAudioPlayer.java:22`); `AVFrame.MEDIA_CODEC_AUDIO_AAC = 138`, `..._PCM = 140` (`AVFrame.java`) | same |
| Encryption | if `tnpHeaderVersion >= 2`: AES-ECB decrypt of **every full 16-byte block** of the payload, tail left as is, key `password + "0"` (`TnpCamera.java:1220-1222`, `AntsUtil.java:52-63`); fail-open (helper returns empty on bad key -> data unchanged) | none (`TutkCamera.java:953-959` builds the frame without decrypt) |
| Payload format | ADTS AAC-LC, 16000 Hz, mono, 1024 samples (64 ms) per frame (PUBLIC prior art: Dmxsir yi-rtsp `yi_native_av_relay.py` `decrypt_audio_unit` checks `0xFFF` sync after decrypt and logs `native AAC-LC/16000/mono`) | INFERENCE: same AAC (vendor feeds all audio to the same AAC decoder) - DEVICE |
| Start / stop | 768 AUDIOSTART / 769 AUDIOSTOP, 8 zero bytes (`TnpCamera.java:2288-2294`) | 768 + start the reader + `avClientCleanAudioBuf` first; 769 + stop the reader (`TutkCamera.java:924, 1868-1885, 1997-2010`) |
| No-data | - | -20012 -> sleep 120 ms; -20013/-20014 skip; -20015/-20016/-20010 end the audio loop (`TutkCamera.java:936-951`) |

**Vendor mute behaviour:** at connect `setEnableListening(false)` (`CameraPlayerFragment.java:3731`); unmute -> `startListening()` (768) (`:4127-4134`); mute -> `stopListening()` (769) unless recording (`:4466-4469`); frames are queued for playback only when sound is on (`:6035-6041`). The player drops its queue when more than 5 frames are waiting (`AntsAudioPlayer.java:182-186`) - latency over completeness.

**Our state:** TNP burst already sends 768 (`TnpCameraLink.kt:247-258`) and ch1 is read but discarded (`TnpCameraLink.kt:379-385`). TUTK sends no 768 and has no audio reader. `FramePipeline` treats any non-video codec as unsupported and flips the decoder to `Unsupported` (`FramePipeline.kt:76-86`) - **audio must never be sent to the VideoUnitSink.**

**Recommended design:**
1. Keep the TNP burst byte-for-byte (device risk: it is the only start sequence prior art proved). Route ch1 units (ioType 2, body > 24) to a new `AudioUnitSink` with `encrypted = (headerVersion >= 2)` captured at that moment.
2. TUTK: after the 8190 start succeeds, queue 768 on the new single writer `tutk-<n>-tx`, call `avClientCleanAudioBuf`, start `tutk-<n>-rxa` with the vendor poll rule; `encrypted = false`.
3. Mute is **local** (D-05): `AudioPipeline` drops units while muted; no 769 is sent (avoids a protocol change on an unverified TNP path; audio costs ~2-4 KB/s). Unmute creates `AacDecoder` + `AudioTrack`; mute releases them.
4. `AudioPipeline` (pure, JVM-tested): decrypt full blocks when `encrypted`; `AdtsParser` walks `aac_frame_length`, validates sync `0xFFF`, layer 0, `number_of_raw_data_blocks == 0`, strips the 7- or 9-byte header (protection_absent); builds csd-0 (AudioSpecificConfig `objType<<11 | freqIdx<<7 | chanCfg<<3`, 2 bytes). Availability: `audioAvailable = true` after the first valid ADTS frame; `false` if no valid audio unit within `AUDIO_PROBE_MS` (suggest 6 s) after Streaming, or codec != 138 -> hide the sound button (D-07).
5. `AacDecoder` (Android): MediaCodec `audio/mp4a-latm` configured from the first frame (sample rate, channel count, csd-0); on `onOutputFormatChanged` (re)create `AudioTrack` (USAGE_MEDIA, PCM 16-bit, rate/channels from the **output** format, buffer = max(minBuffer, ~250 ms)); drop input when more than ~6 frames are queued (vendor rule). Own HandlerThread "media-audio". No A/V sync (vendor has none either).

**Shim additions (VERIFIED-LOCAL exports in arm64 `libAVAPIs.so`: `Java_com_tutk_IOTC_AVAPIs_avRecvAudioData`, `..._avRecvIOCtrl`, `..._avClientCleanAudioBuf`, `..._avCheckAudioBuf`):**
```java
// Exact vendor signatures, AVAPIs.java:98, 146, 153 (jadx6). Add only these three.
public static native int avClientCleanAudioBuf(int av);
public static native int avRecvAudioData(int av, byte[] buf, int bufSize, byte[] frameInfo, int infoSize, int[] frameIndex);
public static native int avRecvIOCtrl(int av, int[] ioType, byte[] buf, int bufSize, int timeoutMs);
```
armeabi-v7a exports were not checked this session (same build, expected identical) - check with the same script before the plan relies on it.

### Pattern 4: HD/SD switch (LIVE-07, D-06, D-07)
**Wire facts (VERIFIED-LOCAL):**
- Manual switch = `CameraCommandHelper.setResolution(i)`: `addUseCount()`, `setResolutionType(i)`, payload 8 bytes `[int resolution, int useCount]` in the camera byte order (BE TNP, LE TUTK), sent as IOCTRL **4881**, reply **4882** carrying a 4-byte int (`CameraCommandHelper.java:3435-3470`). Values: 0 Auto, 1 HD, 2 SD, 4 4K (`CameraPlayerFragment.java:1285-1335`).
- TNP start uses the resolution inside 9029 (`AntsCameraTnp.java:96-103`); for TNP the fragment only sets `resolutionType` before play, for TUTK it sends 4881 (`h8`) before `startPlay` (`CameraPlayerFragment.java:4040-4046`). A manual switch on either transport is 4881 only, no 9029, no reconnect.
- On Relay/TCP the vendor forces SD at connect progress >= 25 % (`CameraPlayerFragment.java:6062-6080`). Vendor's own "current HD?" test is `videoWidth >= 1280` (`CameraPlayerFragment.java:4363-4366`).
- Frames carry `useCount` in head byte 5; vendor drops frames whose useCount is older than its counter and, on a new useCount, waits for an I-frame (`TnpCamera.java:1702-1725`, `AntsCamera.java:344-375`, which also fires `receiveVideoInfoChanged` on a width change).
- IOCTRL 4883/4884 GET_RESOLUTION exists (`AVIOCTRLDEFs.java:158-159`) but the player never calls it. TNPHead `abilityRet == 1` marks a command as unsupported by the device (vendor checks it for 4962, `TnpCamera.java:1258-1260`).

**Our state:** TNP burst already applies `ResolutionPolicy.forMode` (HD on P2P/LAN, SD otherwise, `IoCtrlMessages.kt:148-160`, `TnpCameraLink.kt:233-243`) with fixed useCounts 1/2 (`TnpCameraLink.kt:639-640`). TUTK starts with 8190 useCount 1 and never sends 4881 (`TutkCameraLink.kt:252-256`). `FrameAssembler` follows the generation of whatever key frame arrives (`FrameAssembler.kt:65-79`), so a new useCount GOP is picked up without help. `VideoDecoder` releases and recreates the codec when a key frame has a new mime/size/csd (`VideoDecoder.kt:209-212, 230-237`) and holds frames during creation (startup hold).

**Recommended design:**
1. `CameraLink.requestResolution(res: Int): Boolean` (default `false` = unsupported). TNP: link-owned `useCount` counter (continue from 2), build 4881 with the current header version and queue it on the existing single writer (`txQueue`). TUTK: the new `tutk-<n>-tx` writer sends `avSendIOCtrl(av, 4881, LE[res, ++useCount])` - every native call stays on a link thread (TutkNative invariant).
2. **Do not** add the vendor "drop older useCount" filter: if a camera ignores 4881's useCount it would freeze the video. Keep the assembler as is.
3. Success detection in the supervisor (pure): after a request, success = a key frame whose `useCount == sent` **or** whose width class changed to the requested one (HD: width >= 1280); timeout `QUALITY_SWITCH_MS` (suggest 8 s) -> revert UI to the observed quality and set `qualitySwitchSupported = false` (hide HD/SD, D-07). TNP extra signal: a 4882 with `abilityRet == 1` -> unsupported immediately. The displayed quality is always the **observed** one (frame width), not the requested one.
4. Policy: start quality = `forMode(mode)` (TNP: inside the burst, unchanged). TUTK: keep the proven start; after the first key frame, if the observed quality differs from `forMode(mode)`, issue one `requestResolution`. Manual choice lives in the supervisor for the screen's lifetime (survives auto-reconnects, not persisted - D-06).
5. Phase 2 had no SET_RESOLUTION on TUTK at all; whether the TUTK camera honours 4881 is DEVICE.

### Pattern 5: Video view, fullscreen, gestures (LIVE-09, D-08, D-09)
**Why TextureView (decision recommended, Claude's discretion area):**
- `setZOrderOnTop(true)` places the surface above the host window, hiding window content where it overlaps (AOSP SurfaceView docs) -> the badge and the tap-controls would be invisible over the video. Without it MIUI blacked the whole window (`LiveProbeScreen.kt:58-63`, memory note "MIUI SurfaceView black").
- SurfaceView cannot take a `Matrix`; `scaleX/translationX` are position-synced since API 24 (AOSP commit 6ee192f), but parent clipping of a scaled SurfaceView is not reliable on Android 11 (INFERENCE, LOW) -> a zoomed image spills outside the portrait video box.
- TextureView is drawn as a normal view: overlays, `graphicsLayer`, `clipToBounds` all work; `getBitmap()` gives the snapshot; MediaCodec renders to `Surface(surfaceTexture)` (AOSP "TextureView" graphics doc). The vendor live player is TextureView-based with `setTransform` zoom (`PhotoTextureView.java:52-109`) and `getBitmap()` snapshot (`AntsVideoPlayer4.java:1726`).
- Cost: one extra GPU composition pass and possibly +1 frame latency; irrelevant at 1080p/20 fps.

**Layout rules (Compose):**
- One `AndroidView(factory = { TextureView(it) })` in a **stable composition slot**; portrait/fullscreen differ only by modifiers on its container (`fillMaxWidth().aspectRatio(videoAspect)` vs `fillMaxSize()`). Moving it to another branch destroys the view -> SurfaceTexture destroyed -> codec released -> black until the next key frame.
- Aspect ratio from the decoded size: publish `videoSize` from `VideoDecoder` (`onOutputFormatChanged` is a no-op today, `VideoDecoder.kt:365`; use `KEY_WIDTH/HEIGHT` and crop keys; fallback `DecodableFrame.width/height`). Today the probe hard-codes 16:9 (`LiveProbeScreen.kt:112`).
- Zoom: `Modifier.graphicsLayer { scaleX = s; scaleY = s; translationX = tx; translationY = ty }` on the AndroidView, parent `Box(Modifier.clipToBounds())`. Clamp `s` to 1..4 and `|tx| <= (s-1)*w/2`, `|ty| <= (s-1)*h/2`. "Fill" = `s_fill = max(containerW/videoW, containerH/videoH) / fitScale`. Double tap: `s == 1 ? s_fill (or 2x at the tap point) : 1`. Gestures on a **transparent overlay Box** above the AndroidView (`pointerInput { detectTransformGestures }` + `pointerInput { detectTapGestures(onTap = toggle controls, onDoubleTap = …) }`) so the interop view never swallows events.
- `MainActivity` gets `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"`: rotation then only changes `LocalConfiguration`, no ON_STOP/dispose, no lost `liveCamera` (`AppShellScreen` uses plain `remember` on purpose), and the TextureView survives (`onSurfaceTextureSizeChanged`).
- Fullscreen = `isLandscape || userRequestedFullscreen`. Button: `activity.requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. Exit (button/back): set `SCREEN_ORIENTATION_PORTRAIT`, then an `OrientationEventListener` restores `UNSPECIFIED` once the phone is physically portrait (otherwise a landscape-held phone re-enters fullscreen instantly). `onDispose` of the screen always restores `UNSPECIFIED` and shows the bars.
- System bars: `WindowCompat.getInsetsController(window, view).apply { systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars()) }`; `show(...)` on exit. Optional: `layoutInDisplayCutoutMode = SHORT_EDGES` while fullscreen (Mi 9 has a notch) - DEVICE.
- Keep screen on: `AndroidView(update = { it.keepScreenOn = videoRendering })`.
- Controls auto-hide after ~4 s in fullscreen; tap toggles. BackHandler order: fullscreen -> exit fullscreen; else close screen.

### Pattern 6: Snapshot (LIVE-08)
```kotlin
// Main thread (TextureView requirement), only while DecoderStatus.Rendering:
val bmp = textureView.getBitmap(videoW, videoH) ?: return SnapshotResult.NotReady
// graphicsLayer zoom is a view property, so getBitmap() returns the full unzoomed frame.
// Off Main (Dispatchers.IO): JPEG q=90, name "YiCamAlt_yyyyMMdd_HHmmss.jpg" (local time; never camera id/name)
val values = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, name)
    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    if (Build.VERSION.SDK_INT >= 29) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YiCamAlt")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
}
val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
// write via resolver.openOutputStream(uri); on API 29+ update IS_PENDING=0; on failure delete(uri)
```
- API 29+: no permission for own media (Android docs "Access media files from shared storage"). API 26-28: needs `WRITE_EXTERNAL_STORAGE` (declare with `maxSdkVersion="28"`; request at the first snapshot) and has no `RELATIVE_PATH` (save to `Pictures/YiCamAlt` via a file + `MediaScannerConnection.scanFile`, or insert into Pictures root). The test phone is API 30, so the legacy branch is JVM/Robolectric-only evidence.
- `Bitmap.compress` writes no EXIF -> no metadata leak. Confirm with a short Toast/Snackbar «Снимок сохранён в галерею». Errors are named: `NotReady` (no frame yet), `PermissionDenied` (API < 29), `WriteFailed(className)`.
- Fallback if SurfaceView is kept: `PixelCopy.request(surfaceView, bitmap, listener, handler)` (API 24+).

### Pattern 7: Lifecycle (LIVE-10, D-10)
- Keep Phase 2's triggers: ON_STOP -> `supervisor.stop("background")`; composable dispose -> `stop("screen-left")`; back -> `stop("user")` (`LiveProbeScreen.kt:79-93`). Screen lock and Home both produce ON_STOP. Split-screen with the official app only pauses (multi-resume, API 29+), so our stream stays - that is H6's territory.
- Resume rule: on ON_START, if the last stop reason was `background`, the user had the stream running (not user-stopped, not exhausted), and `now - stoppedAt <= RESUME_WINDOW_MS` (5 min, injectable clock) -> `start()` (attempt counter fresh). Otherwise show «Подключить».
- Interaction with closing invariants: `close()` returns after <= ~2.2 s and the registry slot is freed only after all link threads exited (`TnpCameraLink.kt:516-577`, LOW-8). A user who returns within 2 s must not get `ALREADY_OPEN`: the supervisor's drain rule (Pattern 1) waits for the old link's terminal state, and `ALREADY_OPEN` (a `-reap` still pending) is retried as NETWORK class.
- Auto-connect on screen open (LIVE-01 "открывает камеру и видит видео"): recommended; D-10's «Подключить» applies only to the return-after-window case. Flag for the planner (not explicitly decided in CONTEXT).
- The ViewModel is Activity-scoped today (`hiltViewModel()` without a NavHost); the supervisor must be created per `open(deviceId)` and fully stopped on `screen-left`, not only in `onCleared`.

### Anti-Patterns to Avoid
- **Retry inside a link** (violates D-01/Phase 2 invariant). All retry lives in the supervisor.
- **Opening a new link before the old one reported Closed/Failed** -> `ALREADY_OPEN`, or two sessions to one camera.
- **Feeding audio units into `FramePipeline`** -> decoder flips to `Unsupported(138)`.
- **Changing the TNP start burst** (adding 769, reordering) while TNP has never streamed on a device.
- **Native calls from the UI/supervisor thread** (TUTK `sendIoCtrl` for 4881/768 must go through a link thread).
- **Different composable branches for portrait/fullscreen video** (view recreated, black gap).
- **Timers that end viewing** (LIVE-11): no "session length" timeout; the stall watchdog reconnects only.
- **Logging network handles, SSIDs, IPs, uid, or the snapshot file path** (path includes the user name on some devices).

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---|---|---|---|
| AAC decoding | Own AAC/faad port, vendor `DecodeAAC` | MediaCodec `audio/mp4a-latm` + csd-0 from ADTS | Platform decoder, HW/SW, no native blobs |
| Sample-rate conversion / channel mixing | Manual resampler (vendor plays at `rate/2` stereo, `AntsAudioPlayer.java:243,566`) | Configure `AudioTrack` from the decoder **output** format | The decoder reports the real rate/channels; vendor's `/2` stereo trick is tied to its faad wrapper |
| Network change detection | Polling `getActiveNetworkInfo` | `registerDefaultNetworkCallback` | Event-driven; deprecated API avoided |
| Matrix zoom math for a SurfaceView | Custom GL renderer | TextureView + `graphicsLayer` | Clipping and overlays for free |
| Gallery insertion | Writing to `/sdcard/Pictures` directly on API 29+ | MediaStore + `RELATIVE_PATH` + `IS_PENDING` | Scoped storage forbids raw paths |
| Backoff timers on threads | `Thread.sleep` loops | coroutines `delay` with injected dispatcher | Virtual-time tests |
| Immersive mode flags | `SYSTEM_UI_FLAG_*` | `WindowInsetsControllerCompat` | Deprecated flags; compat handles API 26-34 |

**Key insight:** the protocol additions are three IOCTRL types (768 already sent, 4881/4882) and one more channel; everything risky is lifecycle and composition (who owns the link, when the view is recreated), not bytes.

## Common Pitfalls

### Pitfall 1: Overlays invisible over the video
**What goes wrong:** badge/controls drawn by Compose do not appear over the picture. **Why:** `setZOrderOnTop(true)` surface sits above the window. **Avoid:** TextureView (Pattern 5). **Warning sign:** controls visible only outside the video rect.

### Pitfall 2: Rotation kills the session and the screen
**Why:** no `configChanges`; rotation = Activity recreate = ON_STOP + dispose = `close("background"/"screen-left")`; `liveCamera` in plain `remember` is lost (`AppShellScreen.kt`). **Avoid:** `configChanges` on `MainActivity`. **Warning sign:** `BLOCK_CLOSE_PROBE reason=background` right after turning the phone.

### Pitfall 3: Reopen before drain -> ALREADY_OPEN
**Why:** registry slot freed only after link threads exit; a native connect may outlive `Connect_Break` (`registry=deferred`). **Avoid:** await terminal state of the previous link; treat `ALREADY_OPEN` as NETWORK class. **Warning sign:** «Сессия уже открыта» after a quick background/foreground.

### Pitfall 4: Reconnect storm / account flagging
**Why:** network flaps, kick wars with the official app. **Avoid:** D-02 cap, kick = one attempt, network-change debounce, no attempts while offline, attempt reset only after 30 s of healthy streaming. **Warning sign:** many `BLOCK_OPEN_LINK` lines per minute in the diag log.

### Pitfall 5: Audio routed or decrypted wrong
**Why:** decrypt only when TNP header version >= 2 (not `p2pEncrypt`), full blocks only; TUTK audio is not encrypted; unknown codec ids. **Avoid:** `AudioPipeline` checks ADTS sync after decrypt and reports `audioOk`; never send audio to the video sink. **Warning sign:** noise or silence with frames counted; `audioOk=no` in the debug row.

### Pitfall 6: Audio latency creep
**Why:** network bursts fill the AudioTrack buffer; no A/V sync. **Avoid:** drop input when > ~6 frames are queued (vendor drops at > 5), small AudioTrack buffer (~250 ms). **Warning sign:** sound lags video by seconds after a relay hiccup.

### Pitfall 7: HD/SD request that the camera ignores
**Why:** a camera that does not honour 4881 (or stamps frames with another useCount). **Avoid:** success by useCount **or** width class; timeout -> hide control; never filter frames by expected useCount. **Warning sign:** button pressed, quality label never changes.

### Pitfall 8: Codec recreated on every layout change
**Why:** AndroidView moved/recreated -> SurfaceTexture destroyed -> `detachSurface` releases the codec; next picture only on the next key frame (GOP ~2 s). **Avoid:** stable slot; optionally return `false` from `onSurfaceTextureDestroyed` and re-attach the kept SurfaceTexture (AOSP "Grafika double decode" technique) if a recreate is unavoidable.

### Pitfall 9: Fullscreen exit loop
**Why:** exit sets orientation UNSPECIFIED while the phone is held landscape -> landscape again -> fullscreen again. **Avoid:** force PORTRAIT on exit, restore UNSPECIFIED via `OrientationEventListener` when physically portrait; restore on dispose.

### Pitfall 10: TUTK teardown with new threads
**Why:** LOW-9 invariant: readers must leave before the AV channel is released. New `-rxa`, `-rxc`, `-tx` threads must be joined (bounded) before `releaseOnce()` (`TutkCameraLink.kt:428-469` joins only `-rx` today). **Avoid:** extend the io-join list; keep `close()` bounded by `joinMs`; add FakeTutkNative in-flight tracking for the new calls.

### Pitfall 11: Snapshot on API < 29 without permission
**Why:** MediaStore insert needs `WRITE_EXTERNAL_STORAGE` on API 26-28. **Avoid:** declare with `maxSdkVersion="28"`, request only on those versions.

### Pitfall 12: Old tests encode "never retry"
**Why:** `LiveProbeViewModelTest` scenarios 5/12 assert TIMEOUT/PROTOCOL/REMOTE_CLOSED never retry (`verification-plan.xml` V-M-UI-LIVE-PROBE). **Avoid:** keep `LiveProbeViewModel` unchanged (debug screen), put new behaviour into new classes with new tests; if the probe is replaced instead, those scenarios must be rewritten deliberately, not loosened.

## Code Examples

### ADTS -> csd-0 (pure Kotlin, JVM-testable)
```kotlin
// Source: ISO/IEC 14496-3 ADTS header layout; prior art yi_native_av_relay.py decrypt_audio_unit (MIT) for the checks.
data class AdtsFrame(val objectType: Int, val freqIndex: Int, val channels: Int, val headerLen: Int, val frameLen: Int)
fun parseAdts(b: ByteArray, off: Int): AdtsFrame? {
    if (b.size - off < 7) return null
    if ((b[off].toInt() and 0xFF) != 0xFF || (b[off + 1].toInt() and 0xF6) != 0xF0) return null   // sync, layer 0
    val protectionAbsent = b[off + 1].toInt() and 0x01
    val objectType = ((b[off + 2].toInt() shr 6) and 0x03) + 1
    val freqIndex = (b[off + 2].toInt() shr 2) and 0x0F
    val channels = ((b[off + 2].toInt() and 0x01) shl 2) or ((b[off + 3].toInt() shr 6) and 0x03)
    val frameLen = ((b[off + 3].toInt() and 0x03) shl 11) or ((b[off + 4].toInt() and 0xFF) shl 3) or ((b[off + 5].toInt() and 0xFF) shr 5)
    val rawBlocks = b[off + 6].toInt() and 0x03
    val headerLen = if (protectionAbsent == 1) 7 else 9
    if (freqIndex > 12 || rawBlocks != 0 || frameLen < headerLen || off + frameLen > b.size) return null
    return AdtsFrame(objectType, freqIndex, channels, headerLen, frameLen)
}
fun audioSpecificConfig(f: AdtsFrame): ByteArray {
    val v = (f.objectType shl 11) or (f.freqIndex shl 7) or (f.channels shl 3)
    return byteArrayOf((v shr 8).toByte(), v.toByte())                         // 16 kHz mono AAC-LC -> 0x14 0x08
}
```

### TNP audio decrypt (vendor-compatible, fail-open)
```kotlin
// Source: AntsUtil.decryptAudioFrame (AntsUtil.java:52-63) - every full 16-byte block, tail untouched.
fun decryptAudioInPlace(payload: ByteArray, password: String): Boolean {
    val key = (password + "0").toByteArray(Charsets.US_ASCII)
    if (key.size != 16 && key.size != 24 && key.size != 32) return false
    val c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
    c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
    val full = payload.size / 16 * 16
    if (full > 0) c.doFinal(payload, 0, full, payload, 0)
    return true
}
```

### 4881 payload in both byte orders
```kotlin
// Source: CameraCommandHelper.setResolution (CameraCommandHelper.java:3435-3442)
fun setResolution(resolution: Int, useCount: Int, bigEndian: Boolean): ByteArray =
    java.nio.ByteBuffer.allocate(8).order(if (bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)
        .putInt(resolution).putInt(useCount).array()
// existing IoCtrlMessages.setResolution(res, useCount) is the BE variant - keep it, add the LE one for TUTK.
```

### Supervisor schedule (shape only)
```kotlin
private val NETWORK_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000)   // before attempts 2..5 (D-02)
private const val MAX_ATTEMPTS = 5
private const val KICK_DELAY_MS = 10_000L                                   // D-03, one attempt
private const val WRONG_PASSWORD_DELAY_MS = 2_000L                          // existing RETRY_DELAY_MS
private const val RESUME_WINDOW_MS = 5 * 60_000L                            // D-10
private const val STALL_MS = 10_000L
private const val HEALTHY_RESET_MS = 30_000L
```

## State of the Art

| Old approach (repo today) | Phase 3 approach | Impact |
|---|---|---|
| Debug probe, Connect press, one WRONG_PASSWORD retry | Supervisor with D-02/D-03 classes, network events, stall watchdog, auto-resume | New module + tests; probe kept as debug screen |
| SurfaceView `setZOrderOnTop(true)`, fixed 16:9 | TextureView, decoded aspect ratio, graphicsLayer zoom | Device re-check of rendering on MIUI (H-L1) |
| ch1 discarded, no TUTK audio | AudioPipeline + AacDecoder, local mute | New TUTK threads/natives |
| Resolution only in the TNP burst | `requestResolution` on both transports + observed-quality UI | TUTK gets 4881 for the first time |
| Activity recreated on rotation | `configChanges` | Required for D-08 |

**Deprecated/outdated:** `V-M-UI-LIVEVIEW` scenarios (PTZ, camera selector, `StreamHandleFake`, emulator androidTest) and the `M-UI-LIVEVIEW` exports `PtzJoystick` / `CameraSelector` are out of scope (v2: CTRL-01, LIVE-13) and should be rewritten in this phase's GRACE sync.

## Existing-code reuse map

| File | Action | Notes |
|---|---|---|
| `stream/CameraLink.kt` (v0.2.0) | modify | add default methods `setAudioSink(sink)`, `requestResolution(res): Boolean`, `val capabilities: StateFlow<LinkCapabilities>` (defaults keep test fakes compiling); add Closed reasons `stall`, `network-change` to the documented set (`CameraLink.kt:66-68`) |
| `stream/StreamTypes.kt` | modify | `AudioUnit(info, payload, encrypted)` (toString without bytes), `AudioUnitSink` |
| `stream/IoCtrlMessages.kt` | modify | `AUDIO_STOP = 769`, LE `setResolution`, `SET_RESOLUTION_RESP` handling w/ abilityRet; `ResolutionPolicy` reused as is |
| `stream/FramePipeline.kt`, `FrameAssembler.kt` | keep | no change (do not add useCount filter) |
| `p2p/tnp/TnpCameraLink.kt` (654 lines) | modify | ch1 routing (`onUnit` line 379-385), useCount counter, `requestResolution` via `txQueue`, pass `head.abilityRet` to 4882 handling; burst untouched |
| `p2p/tutk/TutkCameraLink.kt` (622 lines) | modify | `-tx`, `-rxc`, `-rxa` threads; 768 after 8190; teardown joins new threads before `releaseOnce` |
| `p2p/tutk/TutkNative.kt`, `AndroidTutkNative.kt`, `com/tutk/IOTC/AVAPIs.java` | modify | 3 natives; `TutkShimContractTest` pins names (update) |
| `media/VideoDecoder.kt` | modify | `videoSize` flow from `onOutputFormatChanged` |
| `media/DecoderPolicy.kt` | keep | `Ready`/`Rendering` unchanged (or add size with defaults) |
| `ui/live/LiveProbeViewModel.kt`, `LiveProbeScreen.kt` | keep | debug entry (e.g. long-press on a camera row or Settings); needed for H1-H3 "Только relay" |
| `ui/shell/AppShellScreen.kt` | modify | camera tap -> `LiveViewScreen`; keep plain `remember` |
| `MainActivity` / `AndroidManifest.xml` | modify | `configChanges`, storage permission (max 28) |
| new | create | `LiveSessionSupervisor` (+ policy/state types), `NetworkMonitor` + Android impl + Hilt binding, `AdtsParser`, `AudioPipeline`, `AacDecoder`, `SnapshotSaver`, `LiveViewModel`, `LiveViewScreen` |

**Tests that must stay green (41 suites, 526 tests at Phase 2 HEAD):** in particular `TnpCameraLinkTest` (27; burst order/bytes, teardown order), `TutkCameraLinkTest` (28; teardown, fatal codes, deferred registry), `TnpLinkFactoryTest`, `TransportLinkFactoryTest`, `TutkShimContractTest`, `ShimContractTest`, `FramePipelineTest`, `FrameAssemblerTest`, `IoCtrlMessagesTest`, `DecoderPolicyTest`, `LiveProbeViewModelTest` (25), `RedactionScannerTest`. Full command: `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug`.

## Verification notes (nyquist_validation=false; brief map for the planner)

| Req | JVM evidence | Device (human) evidence |
|---|---|---|
| LIVE-04 | supervisor state -> badge text table, every LinkErrorKind | badge shows LAN / P2P / Relay correctly per camera |
| LIVE-05 | virtual-time tests: 2/5/10/20 s, 5 attempts then Exhausted; WRONG_PASSWORD once; kick once after 10 s; no attempts while offline; drain before reopen; stall -> reconnect; network generation change -> reconnect | Wi-Fi off/on and Wi-Fi->4G while streaming; video returns without a tap |
| LIVE-06 | ADTS parser vectors (synthetic 16 kHz mono header -> csd `14 08`), decrypt round trip (full blocks + tail), mute gate, availability timeout; link routes ch1 only to the audio sink; TUTK `-rxa` loop codes | sound on/off on both cameras; off by default; button hidden if no audio |
| LIVE-07 | 4881 bytes BE/LE, useCount increments, request goes through link writer thread; success by useCount or width; timeout hides control | relay -> SD at start; manual HD/SD switch changes width without reconnect (both cameras) |
| LIVE-08 | file-name format, MediaStore values per API (Robolectric optional) | snapshot appears in the gallery under YiCamAlt, full frame while zoomed |
| LIVE-09 | zoom clamp math (pure function), fullscreen state rules | rotate / button / back; pinch 4x; pan bounded; double tap; controls on tap; screen stays on |
| LIVE-10 | ON_STOP -> stop, resume within 5 min only, not after `user`/`screen-left` | lock phone -> official app shows video; unlock within 5 min -> our stream resumes |
| LIVE-11 | no timer after Streaming except stall | 15+ min continuous viewing |

## Human checks (device only; phone not attached now)

- **H-L1** TextureView rendering on MIUI (Mi 9, Android 11): picture visible, no black window, badge and controls visible over the video, both cameras.
- **H-L2** Audio TUTK: unmute -> sound; codec id and ADTS format recorded in the debug row (`audio=AAC/16000/1` or `none`).
- **H-L3** Audio TNP (after H1 passes): ch1 decrypt with header version >= 2 gives valid ADTS (`audioOk=yes`).
- **H-L4** HD/SD: TUTK camera honours 4881 (width changes, no reconnect); TNP camera after H1. Record frame widths for HD and SD.
- **H-L5** Reconnect: Wi-Fi toggle, Wi-Fi -> 4G, camera power cycle; badge sequence and recovery time; no storm in the diag log.
- **H-L6** Background: lock/unlock, Home, return after > 5 min; official Yi app gets video while ours is in background (extends H6).
- **H-L7** Fullscreen/gestures/rotation incl. auto-rotate off; notch handling; keep-screen-on over 2+ minutes.
- **H-L8** Snapshot visible in the gallery (Pictures/YiCamAlt), full resolution, taken while zoomed.
- **H-L9** Redaction sweep of the new log lines (`RealLogScanTest` on the pulled diag log), as in H7.
- Phase 2 items H1-H7 remain open; every TNP-specific Phase 3 behaviour is JVM-only until H1 passes.

## Open Questions

1. **TUTK audio format and whether the TUTK camera honours 4881.** Known: vendor uses one AAC decoder for both transports; TUTK audio not decrypted. Unclear: actual codec id/rate on the user's TUTK camera; 4881 effect. Recommendation: AudioPipeline/quality logic detect and hide; record on device (H-L2, H-L4).
2. **Do TNP frames carry the 4881 useCount or the 9029 useCount?** Recommendation: success detection accepts useCount OR width change; no frame filtering.
3. **REMOTE_CLOSED (-3012/-20015) caused by the official app taking the camera?** If H6 shows the official app's start makes our link fail with REMOTE_CLOSED, retrying on the network schedule would fight it 5 times. Recommendation: NETWORK class now; revisit after H6 (could move to KICK class).
4. **Auto-connect on open** is implied by LIVE-01/the phase goal but not written in CONTEXT; recommend auto-connect, planner confirms.
5. **Where the debug probe stays reachable** (long-press on a camera row vs Settings). Needed for TNP H1-H3 with "Только relay".
6. **armeabi-v7a exports** of the three new AV natives not checked this session (arm64 checked).

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|---|---|---|---|---|
| Gradle via `tools/gradle.ps1` (JDK 17 inside) | build/tests | ✓ | Gradle 8.5, AGP 8.2.2 | - |
| adb | log pull, install | ✓ | 1.0.41; **no device attached now** | user installs APK by hand |
| Physical Mi 9 (Android 11, MIUI) | all human checks | ✗ now | - | none: human checks deferred to a device session |
| Local vendor APK splits `_apk/extracted/` | native libs at build | ✓ | 6.9.8 | - |
| Decompile `_re/jadx*` | reference | ✓ | - | - |

**Missing with no fallback:** the phone (only for human checks; all plans can be built and JVM-verified without it).

## Sources

### Primary (HIGH, VERIFIED-LOCAL, read this session)
- `_re/jadx7/sources/com/tnp/TnpCamera.java` - 1208-1235 (audio ch1 + decrypt rule), 1237-1298 (IOCTRL reply, abilityRet, kick), 1695-1725 (useCount filter), 2288-2298 (768/769/767)
- `_re/jadx6/sources/com/tutk/IOTC/TutkCamera.java` - 894-969 (audio loop), 994-1013 (avRecvIOCtrl loop), 1070-1090 (TUTK I-frame decrypt only), 1814-1823 (IOCTRL send queue), 1868-1885 / 1997-2010 (start/stopListening)
- `_re/jadx6/sources/com/tutk/IOTC/AVAPIs.java` - 98, 146, 153 (native signatures); `AVFrame.java` (codec ids, `getSamplerate` 256-277); `AVIOCTRLDEFs.java` 120-121, 158-159, 257-258, 293-313
- `_re/jadx8/sources/com/xiaoyi/camera/sdk/AntsAudioPlayer.java` - 22, 168, 182-186, 243, 566; `AntsCamera.java` 300-375, 871-972; `AntsCameraTnp.java` 96-103, 111-190, 210-223; `AntsCameraTutk.java` 76-80, 207-224; `CameraCommandHelper.java` 1441, 3435-3470; `AntsVideoPlayer4.java` 49, 1726; `PhotoTextureView.java` 17-109; `com/xiaoyi/camera/util/AntsUtil.java` 52-82
- `_re/jadx2/sources/com/ants360/yicamera/fragment/CameraPlayerFragment.java` - 1285-1335, 3707-3735, 3774-3788, 3834-3912, 4040-4046, 4127-4134, 4363-4366, 4466-4469, 6035-6080
- `lib/arm64-v8a/libAVAPIs.so` in `_apk/extracted/split_config.arm64_v8a.apk` - exported `Java_com_tutk_IOTC_AVAPIs_{avRecvAudioData,avRecvIOCtrl,avClientCleanAudioBuf,avCheckAudioBuf}`
- Project code: files and lines cited inline (CameraLink, StreamTypes, FramePipeline, FrameAssembler, IoCtrlMessages, TnpCodec, TnpCameraLink, TutkCameraLink, TutkNative, VideoDecoder, DecoderPolicy, LiveProbeViewModel/Screen, AppShellScreen, AndroidManifest, app/build.gradle.kts)
- AOSP commit 6ee192f "Document that SurfaceView is synchronous in N" - https://android.googlesource.com/platform/frameworks/base/+/6ee192f%5E%21/
- Android docs, shared media storage (permissions per API, IS_PENDING, RELATIVE_PATH) - https://developer.android.com/training/data-storage/shared/media

### Secondary (MEDIUM)
- AOSP graphics "TextureView" architecture (decoder -> SurfaceTexture, `setTransform`, Grafika keep-alive) - https://source.android.com/docs/core/graphics/arch-tv
- Dmxsir/yi-rtsp `yi_home/rootfs/opt/yi-home/app/yi_native_av_relay.py` (MIT): TNP audio = codec 138, ADTS after AES-ECB of full blocks, AAC-LC 16000 Hz mono - https://github.com/Dmxsir/yi-rtsp
- SurfaceView `setZOrderOnTop` semantics (surface above the window) - AOSP SurfaceView source/docs via search results

### Tertiary (LOW, needs device)
- Parent clipping of a scaled SurfaceView on Android 11 (not verified; only relevant if SurfaceView is kept)
- TUTK camera audio format and 4881 behaviour (inference from vendor code paths)

## Metadata

**Confidence breakdown:**
- Wire behaviour (audio channel/codec/decrypt, 768/769, 4881/4882, useCount): HIGH for what the vendor does; MEDIUM that our cameras behave identically (TNP unverified on device).
- Architecture (supervisor, TextureView, configChanges, lifecycle): HIGH for the Android facts; MEDIUM for MIUI rendering until H-L1.
- Pitfalls: MEDIUM-HIGH (most derived from code read this session).

**Research date:** 2026-10-22
**Valid until:** 2026-11-22 (vendor APK pinned 6.9.8; re-check if it changes)
