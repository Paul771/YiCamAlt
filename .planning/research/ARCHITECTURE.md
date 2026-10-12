# Architecture Research

**Domain:** Native P2P camera video pipeline (TUTK Kalay / CS2 PPPP "TNP") inside an existing Kotlin + Compose + Hilt Android app governed by GRACE
**Researched:** 2026-10-10
**Confidence:** HIGH for JNI surface, packaging and the vendor's own session flow (verified by direct inspection of the shipped `.so` files and jadx decompilation of Yi Home 6.9.8 dex); MEDIUM for decoder/lifecycle patterns (standard Android practice, not device-verified here); LOW for which transport the user's specific cameras use (unknown until a real `/v5/devices/list` body is captured)

---

## 0. Headline findings (read first — they change the plan)

1. **Yi Home has three P2P transports, not one, and picks one per camera from the `p2pid` string.**
   Decompiled `com.xiaoyi.camera.sdk.P2PDevice` / `AntsCameraManage.getAntsCamera()` (classes8.dex):
   - `p2pid` starts with `TNP` and splits into exactly 3 parts on `-` → `TYPE_TNP = 2` → `AntsCameraTnp` → **`com.tnp.TnpCamera` over CS2 `libPPPP_API.so`** (`com.p2p.pppp_api.PPPP_APIs`).
   - `p2pid` is all lower-case → `TYPE_LANGTAO = 1` → `com.langtao.LangtaoCamera` (old models).
   - otherwise (upper-case, TUTK 20-char UID) → `TYPE_TUTK = 0` → `AntsCameraTutk` → **`com.tutk.IOTC.TutkCamera` over `libIOTCAPIs.so` + `libAVAPIs.so`**.
   The project assumes TUTK. Many Yi Home / Dome models of the last ~6 years are believed to be TNP (UIDs like `TNPUSAQ-123456-ABCDE`) — **LOW confidence, must be confirmed from the user's own device list.** The architecture below therefore puts a transport port (`CameraLink`) between the session logic and the native libraries, with TUTK and TNP adapters. Langtao is out of scope unless the user owns such a camera.
2. **Both transports carry the same media/command vocabulary.** `TnpCamera` imports `com.tutk.IOTC.AVFrame` and `AVIOCTRLDEFs`: frames carry the same 24-byte FRAMEINFO header and codec ids (`H264=78`, `H265=81`, `AAC=138`, `G726=143`), and IOCTRL types are shared (`IOTYPE_USER_IPCAM_START=511`, `STOP=767`, `AUDIOSTART=768`, `LISTEVENT_REQ/RESP=792/793`, `RECORD_PLAYCONTROL=794`, Yi-encrypted variant `RECORD_PLAYCONTROL2=12718`). Everything above the link layer (decrypt, decode, IOCTRL codec, SD playback) is transport-agnostic and pure Kotlin → JVM-testable.
3. **Yi's video encryption is local and simple, not a cloud handshake.** `AntsUtil.decryptIframe`: for I-frames only, AES/ECB/NoPadding with key = ASCII(`pwd + "0"`) (pwd is the 15-char camera password from the cloud), decrypting two 16-byte blocks at payload offsets 4..20 and 20..36. Channel auth: `nonce = genNonce(15)` (TUTK) / `noncePrefix(7)+genNonce(8)` (TNP), `password = Base64(HMAC-SHA1(key=pwd, "user=xiaoyiuser&nonce=" + nonce)).take(15)`. The existing `M-STREAM-DECODE` contract in `docs/development-plan.xml` (ExoPlayer, HLS URL, `POST /v1/camera/live/key`) is **wrong and should be superseded.**
4. **The vendor TUTK build needs no license key.** `TutkCamera.init()` calls `IOTC_Initialize2(0)` then `avInitialize(maxCameras*16)`. Unlike docker-wyze-bridge (newer SDK → `TUTK_SDK_Set_License_Key`), no key is involved.
5. **Packaging is easy.** The four libs we need depend only on each other and system libs; `libc++_shared.so` is **not** a dependency. All LOAD segments are 64 KB aligned (16 KB-page-safe).

---

## Standard Architecture

### System Overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ PRESENTATION  ui/liveview, ui/playback, ui/events                             │
│  LiveViewScreen ── AndroidView(SurfaceView) ──► SurfaceHolder callbacks       │
│  LiveViewModel (StateFlow<LiveUiState>)  ◄── LiveStreamPort (domain port)      │
└──────────────┬──────────────────────────────────────────────┬────────────────┘
               │ start(cameraId, quality) / stop / attachSurface│ state Flow
┌──────────────┴──────────────────────────────────────────────┴────────────────┐
│ DOMAIN  stream/  (M-STREAM-SESSION, M-STREAM-IOCTRL, M-STREAM-MEDIA)           │
│ ┌──────────────────┐  ┌───────────────────┐  ┌──────────────────────────────┐ │
│ │ StreamSession     │  │ IoCtrlMux         │  │ FramePipeline                │ │
│ │ Supervisor        │─►│ (req/resp by type,│  │ FrameAssembler→IFrameDecryptor│ │
│ │ (state machine,   │  │  timeouts)        │  │ →KeyframeGate→VideoSink/Audio│ │
│ │  backoff, network)│  └─────────┬─────────┘  └──────────┬───────────────────┘ │
│ └────────┬──────────┘            │                       │                     │
│          │ P2pCredentials (from CameraRegistry)          │ MediaFrame          │
│ ┌────────┴────────────────────── CameraLink (port) ──────┴───────────────────┐ │
│ │ connect(creds) / readFrame() / readIoCtrl() / writeIoCtrl() / close()       │ │
│ └────────┬───────────────────────────────────────────────┬───────────────────┘ │
└──────────┼───────────────────────────────────────────────┼────────────────────┘
┌──────────┴──────────────┐                 ┌──────────────┴─────────────────────┐
│ p2p/tutk  TutkCameraLink │                 │ p2p/tnp  TnpCameraLink              │
│  (IOTC+AV, framing native)│                │  (PPPP read/write; TNP framing in   │
│  TutkRuntime (init once)  │                │   Kotlin: TNPHead/IOCtrlHead/Frame) │
└──────────┬──────────────┘                 └──────────────┬─────────────────────┘
┌──────────┴───────────────────────────────────────────────┴────────────────────┐
│ JNI SHIM (Java, exact vendor package names; no logic)                          │
│  com.tutk.IOTC.{IOTCAPIs, AVAPIs(+avXxxFn), St_*, st_*, Region}                │
│  com.p2p.pppp_api.PPPP_APIs, com.tnp.model.{st_PPPP_Session, st_PPPP_NetInfo}  │
├────────────────────────────────────────────────────────────────────────────────┤
│ jniLibs/{arm64-v8a,armeabi-v7a}: libIOTCAPIs.so libsCHL.so libAVAPIs.so        │
│                                  libPPPP_API.so                                 │
└────────────────────────────────────────────────────────────────────────────────┘
        decode/render (Android-only, thin):  media/  H264/H265 MediaCodec → Surface,
                                              AAC MediaCodec / G711 table → AudioTrack
```

### Component Responsibilities

| Component | Responsibility | Implementation |
|-----------|----------------|----------------|
| JNI shim (`com.tutk.IOTC`, `com.p2p.pppp_api`, `com.tnp.model`) | Exact class/field/method names the `.so` files bind to. Zero logic. | Java sources under `app/src/main/java/com/tutk/IOTC/...` (Java, not Kotlin — static `native` methods and public mutable fields map 1:1 to JNI) |
| `TutkRuntime` | Process-global `IOTC_Initialize2(0)` + `avInitialize(n)` exactly once; refcounted deinit (or never deinit) | `@Singleton` Kotlin, guarded by a `Mutex`; logs `BLOCK_TUTK_INIT` |
| `PpppRuntime` | `PPPP_Initialize(serverString bytes, ...)` once per server string | `@Singleton`; serverString from cloud device record |
| `CameraLink` (port) | Transport-neutral blocking I/O: connect, read frame, read/write IOCTRL, close/abort | Kotlin interface in `stream/`; adapters in `p2p/tutk`, `p2p/tnp`; `FakeCameraLink` in tests |
| `TutkCameraLink` | `IOTC_Get_SessionID` → `IOTC_Connect_ByUID_Parallel` → `avClientStart2(nonce, hmacPwd)` → `avRecvFrameData2` / `avRecvIOCtrl` / `avSendIOCtrl`; teardown | Kotlin, mirrors decompiled `TutkCamera` |
| `TnpCameraLink` | `PPPP_Connect(p2pid, mode, 0, serverString, licenseKey)` → per-channel `PPPP_Read/Write` (ch 0 cmd, 1 audio, 2/3 live I/P, 4/5 record I/P, 6 new-live, 7 new-record); TNP header parse | Kotlin; framing in pure `TnpCodec` object |
| `StreamSessionSupervisor` | Session state machine, reconnect/backoff, network-change and app-background reactions, single owner of the link per camera | Kotlin class per active camera, coroutine-based, `StateFlow<SessionState>` |
| `IoCtrlMux` | Serialize/deserialize AVIOCTRL structs, correlate requests to responses by expected response type, timeouts | Pure Kotlin; port of wyzecam `TutkIOCtrlMux` idea using `CompletableDeferred` |
| `FramePipeline` | FRAMEINFO parse, I-frame AES decrypt, frame-gap → drop until next keyframe, codec detection, SPS/PPS extraction | Pure Kotlin (`javax.crypto` works on JVM) |
| `VideoDecoder` / `AudioRenderer` | MediaCodec (async) → `Surface`; AAC → MediaCodec → `AudioTrack`; G.711 → PCM table | Android-only, thin, behind `VideoSink`/`AudioSink` interfaces |
| `SdPlaybackService` | `LISTEVENT_REQ/RESP` for record list; `RECORD_PLAYCONTROL(2)` start/seek/stop over the same session; record channels | Pure Kotlin on top of `IoCtrlMux` + link |
| `CameraRegistry` (existing) | Supplies `P2pCredentials` (p2pid, transport type, account, pwd/enc flag, tnp server string, license key, model) | Extend `CameraInfo`/parser; new `P2pCredentialsPort` |

---

## Recommended Project Structure

```
app/src/main/
├── jniLibs/                      # NOT hand-copied: produced by Gradle task from _apk splits (see §Packaging)
├── java/com/tutk/IOTC/           # JNI shim (vendor package, Java)
│   ├── IOTCAPIs.java  AVAPIs.java (+ inner interfaces avPasswordAuthFn … avTokenRequestFn)
│   ├── St_SInfo.java St_SInfoEx.java St_AvStatus.java St_AvIdentity.java
│   ├── St_IOTCConnectInput.java St_AVClientStartInConfig.java St_AVClientStartOutConfig.java
│   ├── st_ConnectOption.java st_LanSearchInfo.java st_LanSearchInfo2.java st_SearchDeviceInfo.java
│   └── Region.java
├── java/com/p2p/pppp_api/PPPP_APIs.java   # JNI shim (vendor package, Java)
├── java/com/tnp/model/st_PPPP_Session.java, st_PPPP_NetInfo.java
└── java/com/yicamalt/
    ├── p2p/                      # M-P2P-NATIVE  (adapters; only package allowed to import the shim)
    │   ├── NativeLibs.kt         # loadLibrary order, availability probe (BLOCK_LOAD_NATIVE)
    │   ├── tutk/TutkRuntime.kt, TutkCameraLink.kt, TutkErrors.kt
    │   └── tnp/PpppRuntime.kt, TnpCameraLink.kt, TnpCodec.kt (pure)
    ├── stream/                   # M-STREAM-SESSION, M-STREAM-IOCTRL, M-STREAM-MEDIA (pure Kotlin)
    │   ├── CameraLink.kt         # port + MediaFrame + IoCtrlPacket + LinkError
    │   ├── P2pCredentials.kt     # value + TransportType.fromP2pId()
    │   ├── YiP2pAuth.kt          # genNonce, hmac password, decrypt key derivation
    │   ├── FrameInfo.kt, FramePipeline.kt, IFrameDecryptor.kt, NalUtils.kt
    │   ├── IoCtrlMux.kt, IoCtrlMessages.kt (START/STOP/AUDIOSTART/LISTEVENT/PLAYCONTROL)
    │   ├── StreamSessionSupervisor.kt, SessionState.kt, Backoff.kt
    │   ├── LiveStreamService.kt  # facade implementing LiveStreamPort
    │   └── StreamModule.kt       # Hilt
    ├── playback/                 # M-SD-PLAYBACK (record list + playback over StreamSession)
    ├── media/                    # M-MEDIA-RENDER (Android-only): VideoDecoder, AudioRenderer
    └── ui/liveview/, ui/playback/
```

### Structure Rationale

- **Shim in vendor packages, in Java.** JNI symbol names are fixed (`Java_com_tutk_IOTC_IOTCAPIs_IOTC_1Connect_1ByUID_1Parallel`). Java static `native` methods and public fields are the least surprising mapping; Kotlin `object`+`@JvmStatic external` also works but adds an `INSTANCE` and makes field layout less obvious.
- **`p2p/` is the only package that touches the shim.** Keeps `UnsatisfiedLinkError` / native crashes contained and lets everything above run on the JVM with `FakeCameraLink`.
- **`stream/` is pure Kotlin** (no `android.*` imports) so JUnit5 tests run without Robolectric — matching the project's "construct directly, fake by hand" convention.
- **`media/` is the only Android-media package** and is intentionally thin; it is verified on device, not on the JVM.

---

## JNI Shim and Packaging (verified)

### Native surface (from ELF dynsym of `split_config.arm64_v8a.apk`, Yi Home 6.9.8)

| Library | NEEDED | JNI class | Notes |
|---|---|---|---|
| `libIOTCAPIs.so` (334 KB) | liblog, libc, libm, libstdc++, libdl | `com.tutk.IOTC.IOTCAPIs` (66 exports) | No `JNI_OnLoad`; natives resolved lazily by name. Native code references `com/tutk/IOTC/Region`, `st_LanSearchInfo`, `st_LanSearchInfo2`, `st_SearchDeviceInfo` and callback signatures `(II)V`, `(I[B)V` |
| `libsCHL.so` (14 KB) | libIOTCAPIs, libc, libm, libstdc++, libdl | none | DTLS helper (`IOTC_sCHL_*`); must be packaged, never loaded by name from Java is fine |
| `libAVAPIs.so` (210 KB) | **libIOTCAPIs, libsCHL**, libc, libm, libstdc++, libdl | `com.tutk.IOTC.AVAPIs` (41 exports) | Native references `Lcom/tutk/IOTC/AVAPIs$avPasswordAuthFn;` (+6 other `$av*Fn`) and `com/tutk/IOTC/St_AvIdentity` |
| `libPPPP_API.so` (243 KB) | liblog, libstdc++, libm, libc, libdl | `com.p2p.pppp_api.PPPP_APIs` (26 exports) | **Has `JNI_OnLoad`** referencing `com/p2p/pppp_api/PPPP_APIs` → class must exist at load time; `PPPP_Check` fills `com.tnp.model.st_PPPP_Session` by field name |

- armeabi-v7a split additionally ships `libIOTCAPIsT.so`/`libAVAPIsT.so`/`libsCHLT.so` variants — not used by the Java wrapper (it loads `"AVAPIs"`); ignore.
- **Declared-but-not-exported natives** in the vendor Java wrapper (calling them throws `UnsatisfiedLinkError`): `IOTC_Connect_ByUID2`, `IOTC_IsDeviceSecureMode`, `IOTC_Listen2`, `IOTC_Sessioin_Channel_Check_ON_OFF` (typo'd), `avClientCleanVideoBufNB`, `avClientDeleteIdentity`. **Do not declare them in our shim.**
- Declare **only** the natives we call (≈15 IOTC/AV + ≈10 PPPP). JNI resolves lazily per method, so a minimal shim is safe — but keep every *class* the native code `FindClass`es (`Region`, `st_*`, `St_AvIdentity`, `AVAPIs$av*Fn`) with exact field names/types copied from the decompiled vendor classes, because a missing class/field surfaces as a native abort, not a Java exception.

### Load order

```kotlin
// p2p/NativeLibs.kt — START_BLOCK_LOAD_NATIVE
System.loadLibrary("IOTCAPIs")  // explicit: ART resolves IOTCAPIs natives against loaded handles
System.loadLibrary("sCHL")      // optional (linker resolves NEEDED from the app lib dir), explicit for clarity
System.loadLibrary("AVAPIs")
System.loadLibrary("PPPP_API")  // only when a TNP camera is used; JNI_OnLoad runs here
```
The vendor app only calls `System.loadLibrary("AVAPIs")` (in `AVAPIs.<clinit>`) and relies on the dependency chain; loading `IOTCAPIs` explicitly first removes any reliance on ART's dependency-symbol lookup. Wrap in `runCatching` and expose `NativeAvailability { OK, MISSING_ABI, LINK_ERROR }` so x86 emulators/JVM tests degrade to an error state instead of crashing.

### Packaging

- `android { defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } } }` — no NDK toolchain needed (prebuilt `.so` only).
- Provide `.so` via a **Gradle task that extracts from `_apk/extracted/split_config.{arm64_v8a,armeabi_v7a}.apk`** into `build/generated/jniLibs/<abi>/` and registers it with `sourceSets["main"].jniLibs.srcDir(...)`, verifying SHA-256 against a committed manifest. Rationale: no vendor binaries in `app/src` or in git. Note (2026-10-10): `_apk/` is no longer committed (commit `b804e33`, public repo), so a fresh clone must first obtain the APK locally; the SHA-256 manifest is the only committed artifact.
- Default modern packaging (uncompressed, page-aligned, `extractNativeLibs=false`) works: verified LOAD `p_align = 0x10000` for all four libs → loadable on 16 KB-page devices.
- R8: `-keep class com.tutk.IOTC.** { *; }`, `-keep class com.p2p.pppp_api.** { *; }`, `-keep class com.tnp.model.** { *; }` (field names are read by native code).

---

## Architectural Patterns

### Pattern 1: Transport port (`CameraLink`) with blocking primitives, owned by one thread

**What:** A small synchronous interface that hides TUTK vs TNP. All calls are blocking and are made only from the session's dedicated thread(s); abort is the one method callable from any thread.

```kotlin
interface CameraLink {
    fun connect(creds: P2pCredentials, timeoutMs: Int): LinkInfo          // throws LinkError
    fun readFrame(buf: ByteArray, timeoutMs: Int): ReadResult              // Frame(info, len) | NotReady | Lost | Closed
    fun readIoCtrl(buf: ByteArray, timeoutMs: Int): IoCtrlPacket?          // null = timeout
    fun writeIoCtrl(type: Int, payload: ByteArray): Int
    fun abort()        // thread-safe: IOTC_Connect_Stop_BySID / avClientExit / avSendIOCtrlExit / PPPP_Connect_Break / PPPP_ForceClose
    fun close()        // ordered teardown, idempotent
}
```
**When:** Always — it is the seam that makes `stream/` JVM-testable and lets a TNP camera and a TUTK camera share one supervisor.
**Trade-offs:** Blocking API (not `suspend`) is deliberate: native calls cannot be cancelled by coroutine cancellation; cancellation is implemented as `abort()` from another thread, which makes the blocked native call return an error.

### Pattern 2: Dedicated native threads, coroutines above

**What:** Per active camera, a small fixed executor (`Executors.newFixedThreadPool(3)` named `p2p-<n>-{connect,video,ioctrl}` → `asCoroutineDispatcher()`); audio adds one more. Never run native reads on `Dispatchers.IO` (shared pool, threads parked for minutes; also native libs keep thread-affine state in some builds).
- Video reader loop: `avRecvFrameData2` is non-blocking and returns `AV_ER_DATA_NOREADY (-20012)`; poll with `delay(10–30 ms)` like the vendor (`Thread.sleep(30)`), give up after N consecutive (vendor: 300 → reconnect).
- IOCTRL reader: `avRecvIOCtrl(..., timeoutMs=1000)` / `PPPP_Read(..., timeout)` — **always pass a finite timeout** (vendor TNP passes `-1` = infinite; we must not, or shutdown hangs until `PPPP_ForceClose`).
- Emit into `Channel(capacity = small, onBufferOverflow = DROP_OLDEST)` for video so a slow decoder never back-pressures the network thread; drop policy re-arms the keyframe gate.

### Pattern 3: Session state machine (adapted from docker-wyze-bridge)

wyzecam's `WyzeIOTCSessionState` is `DISCONNECTED → IOTC_CONNECTING → AV_CONNECTING → CONNECTED → AUTHENTICATING → AUTHENTICATION_SUCCEEDED` with terminal `CONNECTING_FAILED` / `AUTHENTICATION_FAILED`; the session itself has **no reconnect** — the supervisor (`WyzeStream.health_check`) restarts it, refreshes camera credentials on `-13/-19/-68`, and marks offline on `-90`. Recommended Kotlin shape:

```
Idle ─start()─► ResolvingCredentials ─► P2pConnecting ─► ChannelStarting(auth) ─► Negotiating(IOCTRL START/quality)
   ▲                                                                                   │ first keyframe
   │                                                                                   ▼
Stopped ◄─stop()── any                                Streaming ──link error──► Reconnecting(attempt n, backoff)
                                                          │                         │ n > max or terminal error
                                                          ▼                         ▼
                                                     Paused(background)          Failed(reason: Offline | WrongPassword |
                                                                                   CredentialsStale | NativeUnavailable | Timeout)
```
Rules:
- **Terminal vs retryable classification lives in one pure function** (`classify(code): Retry | RefreshCredentials | Offline | Fatal`). TUTK: `IOTC_ER_DEVICE_OFFLINE -90` → Offline; `IOTC_ER_TIMEOUT -13`, `IOTC_ER_CAN_NOT_FIND_DEVICE -19`, `IOTC_ER_DEVICE_REJECT_BY_WRONG_AUTH_KEY -68` → RefreshCredentials (re-fetch device list once, then Fail); `AV_ER_WRONG_VIEWACCorPWD` → RefreshCredentials then `WrongPassword`; `AV_ER_SESSION_CLOSE_BY_REMOTE -20015`, `AV_ER_REMOTE_TIMEOUT_DISCONNECT -20016`, `AV_ER_INVALID_SID -20010` → Retry; `AV_ER_LOSED_THIS_FRAME -20014`, `AV_ER_INCOMPLETE_FRAME -20013` → in-stream (drop to keyframe), not a state change. TNP: `PPPP_Read` `-3003`/`-3014` tolerated by vendor; others → Retry. Exact PPPP code table: research flag.
- Exponential backoff with jitter (1 s, 2 s, 4 s … cap 30 s, max ~5 attempts in foreground) — wyzecam only has a fixed cooldown, which is fine for a server but not for a phone on flaky cellular.
- Exactly one supervisor per camera id (`ConcurrentHashMap<String, Supervisor>` inside `LiveStreamService`, same idea as Yi's `AntsCameraManage.mAntsCameraMap`). Live view and SD playback **share** the supervisor's session (playback uses the record channels / PLAYCONTROL), they do not open a second P2P session (cameras cap sessions: `IOTC_ER_DEVICE_EXCEED_MAX_SESSION -48`).
- Every transition logs `[Stream][transition][BLOCK_SESSION_TRANSITION] from=X to=Y code=N` — gives `TraceRecorder.assertSequence(...)` and the long-unused `assertMaxRetries("BLOCK_HANDLE_RECONNECT", n)` a real target.

### Pattern 4: IOCTRL mux (request/response by expected type)

From wyzecam `TutkIOCtrlMux`: one listener thread reads `avRecvIOCtrl` with a 1000 ms timeout and routes each packet to a per-response-type queue; a request returns a future bound to the queue of its `expected_response_code`. Kotlin port: `ConcurrentHashMap<Int, Channel<IoCtrlPacket>>` + `suspend fun <R> request(msg: IoCtrlRequest<R>, timeout: Duration): R` using `withTimeout`. Fix wyzecam's two known flaws: keep the real error code when send fails, and never hold a lock across `join()`. Unsolicited messages (online status, kick `SMsgAUIoctrlTNPIpcamKickResp`, statistics) go to a `SharedFlow`.
For TNP, the mux sits on channel 0 and each outgoing command carries per-message auth in `TNPIOCtrlHead` (40 bytes: type, cmdNum, len, nonce-account, hmac-password); the header version is auto-detected from the first response (`mNeedDetectHeaderVersion`) and commands resent once if it differs — model that explicitly in `TnpCodec`.

### Pattern 5: Frame pipeline as pure stages

```
link.readFrame ─► FrameInfo.parse(24 B, byteOrder) ─► KeyframeGate (drop P after frmNo gap until next I)
   ─► IFrameDecryptor (if enc && I-frame: AES/ECB/NoPadding key=pwd+"0", blocks @4 and @20)
   ─► NalUtils (Annex-B split, SPS/PPS/VPS capture, codec 78→avc / 81→hevc)
   ─► VideoSink (MediaCodec)            audio: codec 138→AAC MediaCodec | G711 → PCM table ─► AudioTrack
```
All stages except the sinks are plain functions/classes over `ByteArray` → unit-testable with golden vectors (encrypt a known NAL with the same AES, feed it, assert bytes). `isByteOrderBig` is a per-camera flag in the vendor code (`TutkCamera(..., z2)`) — carry it in `P2pCredentials`.

### Pattern 6: Decoder ↔ Surface lifecycle decoupled from session lifecycle

- `LiveViewScreen` hosts `AndroidView(factory = { SurfaceView(it) })` and forwards `surfaceCreated/Destroyed` to the ViewModel → `LiveStreamPort.attachSurface(cameraId, surface)` / `detachSurface()`. (`AndroidExternalSurface` from Compose foundation 1.6 — available in the project's BOM 2024.02 — is an equivalent alternative; SurfaceView via AndroidView is the more widely documented path and gives hardware overlay composition.)
- `VideoDecoder` (MediaCodec async callback mode) is created on first keyframe with resolution from SPS, released on surface destroy; on new surface, reconfigure and **request a keyframe** (`IOTYPE_USER_IPCAM_I_FRAME_REQ = 916` exists in vendor `TutkCamera`) instead of waiting for the next GOP. API 30+: set `KEY_LOW_LATENCY=1` when `isFeatureSupported(FEATURE_LowLatency)`.
- Session survives surface churn (rotation, brief Compose recomposition); session is paused/stopped by lifecycle, not by surface.

### Pattern 7: Lifecycle and network

- Owner of "should be streaming" = ViewModel (screen visible) ∩ `ProcessLifecycleOwner` STARTED. On ON_STOP: send `IOTYPE_USER_IPCAM_STOP`, then close the session after a grace period (~10–15 s) so a quick app switch resumes instantly; on ON_START within grace: resume without reconnect.
- `ConnectivityManager.registerDefaultNetworkCallback` → `NetworkMonitor` port emitting `NetworkChanged(transport, id)`. On default-network change while Streaming: proactively `abort()` + reconnect (P2P/UDP hole punches are bound to the old interface; waiting for `-20016` costs 10–30 s). TUTK exposes `IOTC_ReInitSocket` for this — try it first only after device verification.
- No foreground service in v1 (live view is foreground-only); add one only if background audio monitoring becomes a requirement.

---

## Data Flow

### Live view (TUTK camera)

```
LiveViewModel.start(camId)
 → LiveStreamService.open(camId)                        [BLOCK_OPEN_STREAM]
 → P2pCredentialsPort.credentials(camId)  (CameraRegistry: p2pid, type, account, pwd, enc, model, byteOrder)
 → TutkRuntime.ensureInit()  IOTC_Initialize2(0); avInitialize(32)      [BLOCK_TUTK_INIT, once]
 → TutkCameraLink.connect:
      sid0 = IOTC_Get_SessionID()
      sid  = IOTC_Connect_ByUID_Parallel(p2pid, sid0)                   [BLOCK_P2P_CONNECT]
      IOTC_Session_Check_Ex(sid, info) → mode P2P/Relay/LAN (log only)
      nonce = genNonce(15); pw = hmacPwd(pwd, nonce)
      av = avClientStart2(sid, nonce, pw, timeout, serviceType[], ch=0, resend[])   [BLOCK_AV_AUTH]
         (unencrypted cameras: avClientStart2(sid, account, pwd, ...))
 → IoCtrlMux.start(); send IOTYPE_USER_IPCAM_START(511) [+ AUDIOSTART 768]          [BLOCK_START_LIVE]
 → video thread: avRecvFrameData2 → FramePipeline → VideoDecoder → Surface           [BLOCK_DECODE_FRAME]
 → state Streaming on first rendered keyframe
stop: IOTYPE_USER_IPCAM_STOP(767) → avSendIOCtrlExit → avClientStop(av) → avClientExit(sid,0)
      → IOTC_Session_Close(sid)  (abort during connect: IOTC_Connect_Stop_BySID(sid0))
```

### Live view (TNP camera)

```
PpppRuntime.ensureInit(serverString) → PPPP_Connect(p2pid, mode, 0, serverString, licenseDeviceKey)
 (or PPPP_WakeUp_And_Connect for battery models) → handle
 → IOCTRL on ch0: TNPHead(8) + TNPIOCtrlHead(40, per-message nonce/hmac) + payload
 → video: ch6 (new live) or ch2/3 (I/P) → TNPHead + TNPFrameHead(24) + payload → same FramePipeline
 → audio ch1 (decryptAudioFrame for encrypted cameras); SD record: ch7 or ch4/5
stop: PPPP_Connect_Break(p2pid) during connect; PPPP_Close / PPPP_ForceClose(handle)
```

### SD-card playback

```
PlaybackViewModel → SdPlaybackService.list(camId, day)
 → (shared session) IoCtrlMux.request(LISTEVENT_REQ 792 → LISTEVENT_RESP 793) → List<SdEvent>
 → play(t): RECORD_PLAYCONTROL (794; encrypted cams 12718 = PLAYCONTROL2) with STime
 → frames arrive on record path (TNP ch7/ch4-5; TUTK via a play channel/avIndex — research flag)
 → same FramePipeline/VideoDecoder; pause/seek = further PLAYCONTROL commands
```

### Events feed (cloud, not P2P)

Unchanged HTTP path (`M-EVENT`), just a confirmed endpoint. Tapping an event may jump into SD playback at the event timestamp — the only coupling point; keep it a navigation argument, not a module dependency.

---

## Hilt Wiring and CameraRegistry Integration

| Binding | Scope | Notes |
|---|---|---|
| `TutkRuntime`, `PpppRuntime`, `NativeLibs` | `@Singleton` | process-global native state |
| `CameraLinkFactory` (`@Provides`, returns `(TransportType) -> CameraLink`) | `@Singleton` | picks `TutkCameraLink` / `TnpCameraLink`; tests pass a lambda returning `FakeCameraLink` |
| `LiveStreamService` → `LiveStreamPort`, `SdPlaybackPort` (`@Binds`) | `@Singleton` | holds supervisors map |
| `NetworkMonitor` (`@Binds` `AndroidNetworkMonitor`) | `@Singleton` | fake in tests |
| `P2pCredentialsPort` (`@Binds` `CameraRegistry`) | `@Singleton` | **new port on the existing facade**, per the codebase rule "add method to facade, then port" |
| `VideoSinkFactory` | `@Singleton` | Android MediaCodec impl; fake records frames in tests |

`CameraRegistry`/`CameraListParser` must grow: `p2pid`, `transportType` (derived via `TransportType.fromP2pId` — port of `P2PDevice.getDeviceType`), `account` (default `"admin"` as vendor does), `p2pPassword` (secret), `isEncrypted`, `tnpServerString`, `tnpLicenseDeviceKey`, `model`, `byteOrderBig`. **Do not persist the P2P password in plain Room** (`CameraEntity` is unencrypted, and `CameraDao` already logs device ids — a known redaction issue): keep secrets in memory or in `EncryptedSharedPreferences` keyed by device id; add the new field names (`pwd`, `p2p`, `license`, `nonce`) to `RedactionScanner` patterns.

---

## JVM Testability (fits the project's conventions)

| Layer | Test approach | Fakes/harness |
|---|---|---|
| `YiP2pAuth`, `IFrameDecryptor`, `FrameInfo`, `NalUtils`, `TnpCodec`, `IoCtrlMessages` | Pure JUnit5, golden vectors; "protocol pinning" by recomputing HMAC/AES in the test (same style as `scenario_1d`) | none |
| `TransportType.fromP2pId` | table test: `TNPUSAQ-123456-ABCDE`→TNP, lowercase→LANGTAO, 20-char upper→TUTK, blank→UNKNOWN | none |
| `IoCtrlMux` | scripted `FakeCameraLink` delivering responses out of order, timeouts, send errors | `FakeCameraLink` (new, in `test/`) |
| `StreamSessionSupervisor` | `runTest` + `StandardTestDispatcher` virtual time for backoff; inject dispatchers (don't hardcode thread pools in the class — pass a `LinkDispatchers` provider); assert `BLOCK_SESSION_TRANSITION` sequence and `assertMaxRetries` | `FakeCameraLink` scripted per-call results (`connect` throws `LinkError(-90)` etc.), `FakeNetworkMonitor`, `FakeVideoSink` |
| `LiveViewModel` | existing ViewModel pattern with a `FakeLiveStreamPort` (repurpose/replace `StreamHandleFake`) | — |
| Redaction | every stream test ends with `RedactionScanner.assertClean(extraLiterals = listOf(p2pid, pwd, nonce, hmacPw))` | existing |
| Native adapters (`TutkCameraLink`, `TnpCameraLink`), `VideoDecoder` | **device-only** smoke via a debug screen (like `DeviceSignProbe`): load libs, init, connect to the user's camera, log mode/first-frame latency. Mark `STATUS="deferred"` integration evidence in `verification-plan.xml` | `FakeCameraStreamServer` (existing, AES frames) can be repurposed as a TNP/TUTK byte-stream generator for `TnpCodec` |

Rule: if a class imports `com.tutk.*`, `com.p2p.*` or `android.media.*`, it must be a thin adapter with no branching worth unit-testing; all decisions sit one layer up.

---

## Anti-Patterns

### Anti-Pattern 1: Running native reads on `Dispatchers.IO` / relying on coroutine cancellation
**What:** `withContext(Dispatchers.IO) { avRecvIOCtrl(...) }` and `job.cancel()` to stop.
**Why bad:** cancellation does not interrupt JNI; IO pool threads are pinned; stop appears to work but sockets stay open → `-48` max-session errors on the next connect.
**Instead:** dedicated named threads + `CameraLink.abort()` from the stopping thread + ordered `close()`.

### Anti-Pattern 2: Copying the vendor `TutkCamera` threading verbatim
**What:** 6+ threads per camera with `Thread.sleep` polling and `volatile` flags, state spread across fields.
**Why bad:** untestable, races on `mSID`, no single source of state.
**Instead:** keep the vendor *call sequence and arguments*, replace the structure with one supervisor + state machine.

### Anti-Pattern 3: Opening a second P2P session for SD playback or audio
**Why bad:** cameras limit sessions; vendor reuses one session (AV channel / PPPP channels).
**Instead:** supervisor owns the session; playback/audio are clients of it.

### Anti-Pattern 4: Decoder tied to session
**What:** releasing/reconnecting the P2P session when the Surface is destroyed (rotation).
**Instead:** surface events only (re)create the MediaCodec and request an I-frame.

### Anti-Pattern 5: Keeping the planned `M-STREAM-LIVE`/`M-STREAM-DECODE` contracts
They describe cloud HLS + ExoPlayer + cloud AES handshake, which contradicts the decompiled reality. Supersede them (GRACE: mark old module records superseded, add new `M-P2P-NATIVE`, `M-STREAM-SESSION`, `M-STREAM-IOCTRL`, `M-STREAM-MEDIA`, `M-MEDIA-RENDER`, `M-SD-PLAYBACK`, `M-UI-LIVEVIEW` revised).

---

## Suggested Build Order (dependencies)

1. **Credentials & transport detection** (blocks everything): capture real `/v5/devices/list` (or Yi's P2P-info endpoint) → extend parser with p2pid/pwd/enc/tnp fields → `TransportType.fromP2pId`. *Exit:* we know if the user's cameras are TUTK or TNP. This decides which adapter is built first.
2. **Native shim + packaging + load probe** (device): Gradle extraction task, Java shim classes (both stacks — cheap), `NativeLibs` probe on a debug screen; verify `IOTC_Get_Version` / `PPPP_GetAPIVersion` return on the phone.
3. **Pure protocol core** (JVM, parallel with 2): `YiP2pAuth`, `FrameInfo`, `IFrameDecryptor`, `NalUtils`, `TnpCodec`, `IoCtrlMessages`, `CameraLink` port + `FakeCameraLink`.
4. **Link adapter for the user's transport** (device): connect + auth + receive raw frames; debug screen dumps first I-frame size/codec, connection mode (P2P/Relay/LAN). *Exit:* frames arrive locally and over cellular (relay).
5. **Session supervisor + IoCtrlMux** (JVM-first, then device): state machine, backoff, START/STOP, network monitor.
6. **Media render + Live view UI** (device): MediaCodec → SurfaceView, keyframe request, audio (AAC/G711) → AudioTrack, lifecycle pause/resume.
7. **SD playback** (depends on 5,6): LISTEVENT, PLAYCONTROL(2), record channels, timeline UI.
8. **Events feed** (independent of P2P; can run in parallel with 2–6 once the endpoint is captured).
9. Second transport adapter (only if the user owns cameras of the other type).

Ordering rationale: the transport question (1) is the single biggest unknown and changes which native code path matters; pure-Kotlin core (3) de-risks the device-heavy phases because device iterations on this machine are slow (USB phone only, no emulator).

---

## Scalability Considerations

| Concern | 1 camera | 2–4 cameras (grid) | Notes |
|---|---|---|---|
| Native sessions | 1 IOTC session + 1 AV channel | `avInitialize(n*16)` sized for max; vendor default 2 cameras | Raise `avInitialize` arg if grid view added |
| Threads | 3–4 | 3–4 per camera | Acceptable; cap concurrent live tiles |
| Decoders | 1 HW decoder | HW decoder count is device-limited (often 4–16) | Use sub-stream/SD quality for grid tiles |
| Battery/data | foreground only | same | Stop sessions on background after grace period |

---

## Sources

- Direct inspection (HIGH): ELF `.dynsym`/`.dynamic`/program headers of `libIOTCAPIs.so`, `libAVAPIs.so`, `libsCHL.so`, `libPPPP_API.so` extracted from `E:/Dev/YiCamAlt/_apk/extracted/split_config.arm64_v8a.apk` and `split_config.armeabi_v7a.apk` (Yi Home 6.9.8).
- jadx 1.5.6 decompilation (HIGH, reproducible): `base.apk` classes6.dex (`com.tutk.IOTC.*`, `com.p2p.pppp_api.PPPP_APIs`), classes7.dex (`com.tnp.TnpCamera`), classes8.dex (`com.xiaoyi.camera.sdk.{P2PDevice,AntsCameraManage,AntsCameraTutk,AntsCameraTnp}`, `com.xiaoyi.camera.util.{AntsUtil,AESIPC}`). Re-run: `java -cp jadx-1.5.6-all.jar jadx.cli.JadxCLI --no-res -d out classesN.dex` with JDK 17 (JDK 8 on PATH fails).
- docker-wyze-bridge `app/wyzecam/iotc.py` — session states, connect/auth/disconnect, frame error handling (MEDIUM, fetched 2026-10-10): https://github.com/mrlt8/docker-wyze-bridge/blob/main/app/wyzecam/iotc.py
- docker-wyze-bridge `app/wyzecam/tutk/tutk_ioctl_mux.py` — IOCTRL mux design (MEDIUM, fetched 2026-10-10): https://github.com/mrlt8/docker-wyze-bridge/blob/main/app/wyzecam/tutk/tutk_ioctl_mux.py
- docker-wyze-bridge `app/wyzebridge/wyze_stream.py` — supervisor, StreamStatus, error→status mapping (MEDIUM, fetched 2026-10-10): https://github.com/mrlt8/docker-wyze-bridge/blob/main/app/wyzebridge/wyze_stream.py
- Existing project docs: `.planning/codebase/{ARCHITECTURE,STRUCTURE,TESTING}.md`, `docs/development-plan.xml` (planned M-STREAM-* contracts).
- Android MediaCodec low-latency, SurfaceView/AndroidView, ConnectivityManager network callbacks, ProcessLifecycleOwner: standard platform APIs (MEDIUM, training knowledge, not re-fetched this session).
