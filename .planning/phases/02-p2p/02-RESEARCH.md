# Phase 2: Нативный P2P и первый кадр - Research

**Researched:** 2026-10-12
**Domain:** Driving the vendor CS2-PPPP ("TNP") and TUTK/Kalay native stacks from our own Android app via JNI; TNP framing/auth/decrypt in pure Kotlin; raw MediaCodec decode to a SurfaceView
**Confidence:** MEDIUM-HIGH overall. Everything marked VERIFIED-LOCAL was read this session from the git-ignored decompile (`_re/jadx2|6|7|8`, Yi Home 6.9.8) or from ELF dynsym of the `.so` files in `_apk/extracted/split_config.*.apk`. PUBLIC = open-source prior art (Dmxsir yi-cam-integration / yi-rtsp, MIT). INFERENCE = reasoning, not observed.


<user_constraints>
## User Constraints (no CONTEXT.md for this phase — constraints from the orchestrator brief, ROADMAP and Phase 1 evidence)

### Locked decisions
- Roadmap scope of Phase 2 is the TNP transport first (LIVE-01, LIVE-02, LIVE-03); success criteria 1-5 in ROADMAP.md.
- Vendor native libs come ONLY from the user's local APK under `_apk/` (git-ignored). Extract at build time with a SHA-256 manifest; never commit `.so`, APK, decompiled code.
- ABIs: arm64-v8a and armeabi-v7a. Target phone: Xiaomi Mi 9 (arm64, Android 11, MIUI 12.5). AGP 8.2.2, `jniLibs.useLegacyPackaging = true`.
- No emulator (no VT-x). Everything native is verified on the physical phone. `adb install` is blocked by MIUI: the user installs the APK by hand. Logs are pulled with `adb exec-out run-as com.yicamalt cat files/diag_log.txt`.
- Real camera secrets (DID, InitString, License, passwords, nonces) never appear in repo, logs or chat.
- GRACE protocol (AGENTS.md): module contracts, MODULE_MAP, semantic blocks, `docs/*.xml` updates; supersede `M-STREAM-LIVE` / `M-STREAM-DECODE`.
- Pure Kotlin protocol core tested on the JVM with known-answer vectors (`YiP2pAuth`, `TnpCodec`, `IFrameDecryptor`, `NalUtils`); one P2P session per camera; dedicated native threads; abort-based cancellation; must not block the user's official Yi app.

### Measured facts (Phase 1, binding)
- Account has TWO cameras on gw-us: one resolved TNP (`/v4/tnp/device_info` -> DID, InitString, License) with `mismatch=true`, one resolved TUTK (no license).
- `ipcParam` arrives as a JSON object (ip, mac, p2p_encrypt, signal_quality, ssid).

### Claude's discretion
- TUTK in this phase or next (recommendation below: plan split 2A TNP / 2B TUTK inside Phase 2).
- Transport decision for the mismatch camera; shim design; Gradle task design; test split.

### Deferred (out of scope for Phase 2)
- LIVE-04..11 (state badge UI, reconnect/backoff, audio playback, HD/SD switch, snapshot, fullscreen, lifecycle) — Phase 3. Phase 2 only needs the connection mode visible in debug info (criterion 3).
- SD playback (ch4/5/7, 9030) — Phase 4.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| LIVE-01 | Live video on the same Wi-Fi | TNP call sequence (§Architecture 1-4), connect flag, burst, frame pipeline, MediaCodec (§Pattern 6) |
| LIVE-02 | Live video from another network (cellular) via P2P or relay | `PPPP_Connect` auto-falls back to relay inside the lib; mode read via `PPPP_Check` -> `st_PPPP_Session.bMode` (0 P2P, 1 Relay, 2 TCP relay, 3 SDev relay); `relayFlag` -> force relay |
| LIVE-03 | H.264 and H.265 | codec id 78/81 in `TNPFrameHead`/FRAMEINFO; `video/avc` vs `video/hevc` MediaCodec; Mi 9 HW HEVC decoder check via `MediaCodecList` |
</phase_requirements>

## Summary

The vendor app drives TNP with a very small native surface: `PPPP_Initialize(new byte[]{0}, 12)` + `PPPP_Config_Debug(0, 4194311)` once per process, then per camera `PPPP_Connect(did, flag, 0, initString, licenseDeviceKey)` (or `PPPP_WakeUp_And_Connect` for `wakeup` cameras), `PPPP_Check(handle, st_PPPP_Session)` for the connection mode, and from then on only `PPPP_Write(handle, 0, …)` for commands and `PPPP_Read(handle, ch, …)` on eight channels (0 cmd, 1 audio, 2/3 live I/P, 4/5 record I/P, 6 new-live, 7 new-record). Teardown is `PPPP_Connect_Break(did)` + `PPPP_ForceClose(handle)`. Everything above that — the 8-byte `TNPHead`, 40-byte `TNPIOCtrlHead` with per-command nonce/HMAC auth, the 24-byte frame head, I-frame AES decrypt — is plain Java in the vendor dex and is reproduced in pure Kotlin (VERIFIED-LOCAL, all offsets below). The arm64 `libPPPP_API.so` in our 6.9.8 APK is byte-identical (SHA-256 `8C53F2EC…86EB`) to the 6.9.7 library that prior art reports as PPPP API `0xA2050401`.

Three findings change the plan. (1) **Transport is NOT the device-list `type`.** The vendor app ignores the server `type` for transport: it calls `/v4/tnp/device_info` for every camera and switches a camera to TNP only when the returned `DID` starts with `TNP` and splits into exactly three parts on `-`; otherwise the transport comes from the cloud `uid` shape (all lower-case -> Langtao, else TUTK) and `P2PID = uid`. Our `mismatch=true` is simply the list `did` (a display DID) failing the TNP-shape regex — the TNP P2P id must be the `device_info` DID. The second camera ("TUTK") was never asked for `device_info`, so it may in fact also be TNP; the resolver must be changed before any connect work. (2) **`libPPPP_API.so` has a `JNI_OnLoad`** that looks up `com/p2p/pppp_api/PPPP_APIs` and its instance callbacks `onIncomingConnectionCallback()V` / `onR2PMPCallback(I)V`, and native code reads fields of `st_PPPP_Session` / `st_PPPP_NetInfo` by name — our Java shim must keep those exact FQCNs, method names and field names (earlier STACK.md said "no JNI_OnLoad"; that was wrong for PPPP, right for IOTC/AV). (3) **Prior art that drove this library from its own client hit `-3012` (remote close) when it wrote one command and blocked on a read**; the working sequence is: start the channel-0 reader first, then write `4881`, `9029`, `768` back to back with no wait. The official 6.9.7 client also sends its first commands within 3 ms of each other.

TUTK costs little extra on the native side (3 libs, ~12 natives, one `avRecvFrameData2` loop that already returns the same 24-byte FRAMEINFO and the same I-frame AES rule), and the user's second camera may need it. Recommended split inside Phase 2: **2A = TNP end to end (all 5 criteria on the TNP camera)**, **2B = TUTK adapter behind the same `CameraLink` port** (reuses FramePipeline/decoder; only if the corrected resolver still says TUTK for camera 2).

**Primary recommendation:** Build a Java shim with the vendor FQCNs, a pure-Kotlin TNP core pinned by JVM tests, and a `TnpCameraLink` that mirrors the vendor call order exactly (init {0},12 → Config_Debug(0) → Connect(flag 0x49 / force-relay 0xDF) → Check → start readers on ch0..3,6 → burst 4881/9029/768) — and fix `TransportResolver` to the vendor's `device_info`-DID rule first.

## Standard Stack

### Core
| Library / API | Version | Purpose | Why standard | Conf. |
|---|---|---|---|---|
| Vendor `libPPPP_API.so` (arm64-v8a 243,264 B; armeabi-v7a 177,412 B) | Yi Home 6.9.8; PPPP API `0xA2050401` | TNP/CS2 transport | The only working implementation of the Yi PPPP fork; used by the official app, yi-rtsp, the Dmxsir Android oracle | HIGH (VERIFIED-LOCAL hashes; version via PUBLIC hash match) |
| Vendor `libIOTCAPIs.so` + `libAVAPIs.so` + `libsCHL.so` | IOTC 2.x, same APK | TUTK transport (plan 2B) | `libAVAPIs.so` NEEDs `libIOTCAPIs.so` + `libsCHL.so`; no license-key API exported; master IPs compiled in | HIGH (VERIFIED-LOCAL ELF) |
| Java JNI shim at vendor FQCNs: `com.p2p.pppp_api.PPPP_APIs`, `com.tnp.model.st_PPPP_Session`, `com.tnp.model.st_PPPP_NetInfo`; (2B) `com.tutk.IOTC.IOTCAPIs`, `AVAPIs`, `St_SInfoEx` + every class native code FindClass-es | n/a | JNI binding by static symbol name / field name | Exports are `Java_com_p2p_pppp_1api_PPPP_1APIs_*`; struct fields read by name | HIGH |
| `android.media.MediaCodec` (async callback mode) + `SurfaceView` | platform, minSdk 26 | H.264 (`video/avc`, id 78) / H.265 (`video/hevc`, id 81) decode | Frames are discrete access units; raw codec gives keyframe gating + decrypt-before-queue | MEDIUM-HIGH |
| `javax.crypto` (JDK) | platform/JVM | `AES/ECB/NoPadding` I-frame + audio blocks; `HmacSHA1` command auth | Identical on JVM and Android → JVM tests are authoritative | HIGH |
| `java.util.Base64` | JDK 8+/API 26+ | Base64 of the HMAC | Vendor uses a standard-alphabet encoder (`+/`), first 15 chars only, so padding/line breaks never matter | HIGH |

### Supporting
| Tool | Version | Purpose | When |
|---|---|---|---|
| Gradle task in `app/build.gradle.kts` (Kotlin DSL, `java.util.zip`, `java.security.MessageDigest`) | Gradle 8.5 wrapper, AGP 8.2.2 | Extract `lib/<abi>/lib{PPPP_API,IOTCAPIs,AVAPIs,sCHL}.so` from the local APK into `app/build/generated/vendorJniLibs/<abi>/`, verify SHA-256 manifest | Every build |
| `MediaCodecList(REGULAR_CODECS)` | platform | Detect HW `video/hevc` decoder (criterion 4) | At decoder creation |
| JUnit5 + existing `TraceRecorder`, `RedactionScanner`, `FakeCameraStreamServer` | existing | JVM tests for core + link logic with a fake native port | Always |

### Alternatives considered
| Instead of | Could use | Tradeoff |
|---|---|---|
| Vendor `libPPPP_API.so` | Pure-Kotlin CS2 PPPP (cs2pppp / aiopppp ports) | Yi fork adds signed `_EX` messages (nonce/Signature keyed by license) and a Yi init-string table; no public implementation proves it. Not for v1. |
| Java shim | Kotlin `object` + `@JvmStatic external` | Works, but adds `INSTANCE`, and field layout of struct classes is less obvious; Java is 1:1 with the vendor binding. |
| Java shim | Our own C/C++ JNI library calling the vendor C exports (`PPPP_Connect`, …) via `dlopen` | Avoids FQCN coupling but needs the NDK toolchain + guessed C prototypes (`_Z14PPPP_DoConnectPKcctS0_S0_c` etc.) — strictly more risk. Not needed: the static `Java_*` trampolines exist. |
| In-process native code | `android:process=":p2p"` service + Surface over Binder | Isolates native SIGSEGV from the UI; costs IPC for state/frames. Defer; keep the native boundary in one package so it can move later. |
| MediaCodec | Media3/ExoPlayer custom extractor | Adds buffering latency; no raw-ES source. Not for live. |
| `libh265decoder.so` (vendor SW HEVC) | — | Unknown JNI contract, extra proprietary surface. Use platform decoder; report "no HW HEVC" instead. |

**Installation:** no new Gradle dependencies. Vendor `.so` come from `_apk/` at build time (see Pattern 1).

**Version verification:** vendor libs are pinned by SHA-256 (table in Pattern 1), not by registry version. AGP/Gradle unchanged (8.2.2 / 8.5).

## Architecture Patterns

### Recommended project structure
```
app/
├── vendor-natives.sha256          # committed manifest: "<sha256>  <abi>/<lib>.so" (hashes are not secrets)
├── build.gradle.kts               # extractVendorNatives / verifyVendorNatives tasks
└── src/main/java/
    ├── com/p2p/pppp_api/PPPP_APIs.java        # JNI shim, Java, no logic
    ├── com/tnp/model/st_PPPP_Session.java     # exact field names/types
    ├── com/tnp/model/st_PPPP_NetInfo.java     # exact field names/types (FindClass target)
    ├── com/tutk/IOTC/…                        # (2B) IOTCAPIs, AVAPIs (+$av*Fn interfaces), St_SInfoEx, Region, st_LanSearchInfo(2), st_SearchDeviceInfo, St_AvIdentity
    └── com/yicamalt/
        ├── p2p/                   # M-P2P-NATIVE: only package importing the shim
        │   ├── NativeLibs.kt      # load + availability probe (BLOCK_LOAD_NATIVE)
        │   ├── PpppNative.kt      # interface over the 9 PPPP calls we use (fake on JVM)
        │   ├── PpppRuntime.kt     # init once, Config_Debug(0), version
        │   ├── tnp/TnpCameraLink.kt   # connect/check/readers/write/abort/close
        │   └── tutk/…             # (2B)
        ├── stream/                # pure Kotlin, JVM-tested
        │   ├── CameraLink.kt      # port + LinkInfo(mode) + LinkError
        │   ├── YiP2pAuth.kt       # nonce, HMAC password, authInfo bytes
        │   ├── TnpCodec.kt        # TNPHead / TNPIOCtrlHead / frame head encode/parse, readExactly logic
        │   ├── IoCtrlMessages.kt  # 4881, 9029, 768, 767 payload builders
        │   ├── FrameInfo.kt       # 24-byte frame head (endianness flag)
        │   ├── IFrameDecryptor.kt # AES two blocks @4,@20, vendor fail-open semantics
        │   ├── NalUtils.kt        # Annex-B / length-prefixed detect, NAL types, SPS/PPS/VPS
        │   ├── FrameAssembler.kt  # useCount filter, keyframe gate, P-seq reorder (cap 100)
        │   └── TransportDecision.kt  # vendor rule (DID shape / uid case)
        ├── media/VideoDecoder.kt  # Android-only, MediaCodec async → Surface
        └── debug/LiveProbeScreen  # phone checkpoint UI: Connect / mode / codec / fps / first frame
```

### Pattern 1: Build-time extraction with SHA-256 manifest (criterion 1)
**What:** A Gradle task reads the vendor `.so` straight out of the local APK (zip-in-zip for bundles), writes them to `app/build/generated/vendorJniLibs/<abi>/`, and checks every file against `app/vendor-natives.sha256`. `android.sourceSets["main"].jniLibs.srcDir(...)` points at that build dir, so nothing lands in `app/src` (also git-ignored as a second fence: `app/src/main/jniLibs/`, `*.so`).

**Inputs it must accept (search order):**
1. `-PvendorApk=<path>` (explicit),
2. `_apk/extracted/split_config.arm64_v8a.apk` + `split_config.armeabi_v7a.apk` (what exists today),
3. any `_apk/*.apkm|*.xapk|*.apks` (zip containing `split_config.<abi>.apk`) or a universal `*.apk` with `lib/<abi>/` inside.

**Behaviour (fresh clone without APK):**
- `extractVendorNatives` never fails the configuration phase and never fails JVM tests: if no source is found it writes an empty output + a marker and logs one line.
- `verifyVendorNatives` (wired as a dependency of `assembleDebug`/`assembleRelease`/`installDebug` only) fails with an actionable message: *"Vendor native libs missing: put your own Yi Home APK (or its split_config.arm64_v8a.apk) under _apk/ or pass -PvendorApk=…; see HANDOFF.md. Vendor binaries are never committed."*
- Hash mismatch (e.g. the user drops in 6.9.9): fail with the observed hashes printed and the override `-PvendorNativesAccept=true`, which writes the new manifest for review. Never silently accept.

**Manifest (verified this session, Yi Home 6.9.8):**
```
8C53F2ECC7CE6C362960AF29A5D8DE8396347A120DC10E88B24928437C4286EB  arm64-v8a/libPPPP_API.so    (243264)
AFFC4BBD9995F3466CBC5519200045D329B869693EC5D7FAE9C7DE69C9A51EB1  arm64-v8a/libIOTCAPIs.so    (334040)
C70A304726B8197C0198A3C16BC02480E6D5FDB09E92912D7210660C0EE4DADD  arm64-v8a/libAVAPIs.so      (210616)
DCC0934D71356C6B00CEF1A6C381EB3FA456A32FC300DBDD9648FDCF251D4D3F  arm64-v8a/libsCHL.so        (14000)
C18CD5756BF6FDA64BFC5498CF2B2F8ED899732A111D873DD90ACF6C00DB9A1D  armeabi-v7a/libPPPP_API.so  (177412)
A291233D2B5557EBE4FD48A7E5F240E92252F9B9843663D801FAFB081E520660  armeabi-v7a/libIOTCAPIs.so  (210832)
546C37A824B9A54EF0B48367119C8B62BED4229B731BC96BDB8799A9787C3C68  armeabi-v7a/libAVAPIs.so    (148952)
66AE1344F1635FD0D99B49E7AD67A72F3E8FCBF8552C99A88C259CF0F8220AB2  armeabi-v7a/libsCHL.so      (17872)
```
Do NOT extract `libc++_shared.so`, `libkami_core.so` (phone-as-camera, NEEDs vendor libssl/libcrypto), `libIOTCAPIsT/AVAPIsT` (v7a-only variants not used by the Java binding). All four libs NEED only system `liblog/libc/libm/libdl/libstdc++` + each other (VERIFIED-LOCAL).

### Pattern 2: Minimal Java shim with exact vendor names (decision: required)
**Decision (HIGH):** the `.so` export static `Java_com_p2p_pppp_1api_PPPP_1APIs_*` trampolines, and `libPPPP_API.so` has `JNI_OnLoad` that references `com/p2p/pppp_api/PPPP_APIs`, `onIncomingConnectionCallback`, `onR2PMPCallback`, `(I)V` (VERIFIED-LOCAL strings). Therefore our app MUST contain its own class `com.p2p.pppp_api.PPPP_APIs` (written by us, not copied) with:
- `public static native` methods for only what we call (natives bind lazily per method; undeclared natives are harmless),
- public instance methods `void onIncomingConnectionCallback()` and `void onR2PMPCallback(int)` (looked up by JNI_OnLoad),
- `com.tnp.model.st_PPPP_Session` with fields in this exact name/type set (native `PPPP_Check` writes them): `int Skt; byte[] RemoteIP(16); int RemotePort; byte[] MyLocalIP(16); int MyLocalPort; byte[] MyWanIP(16); int MyWanPort; int ConnectTime; int ConnectTimeP2P; int ConnectTimeRelay; byte[] DID(24); byte bCorD; byte bMode;`
- `com.tnp.model.st_PPPP_NetInfo`: `byte bFlagInternet, bFlagHostResolved, bFlagServerHello; byte NAT_Type; byte[] MyLanIP(16); byte[] MyWanIP(16)` (only needed if `PPPP_NetworkDetect` is called; keep the class anyway — cheap).
- No `static { System.loadLibrary }` inside the shim; loading is done by `NativeLibs` so failures become a state, not an `ExceptionInInitializerError`.

PPPP natives to declare (signatures VERIFIED-LOCAL from vendor binding, `classes6.dex`):
```java
public static native int PPPP_Initialize(byte[] initString, int maxSessions);
public static native int PPPP_DeInitialize();
public static native int PPPP_GetAPIVersion();
public static native int PPPP_Config_Debug(byte enable, int level);
public static native int PPPP_Connect(String did, byte flag, int udpPort, String serverString, String licenseDeviceKey);
public static native int PPPP_WakeUp_And_Connect(String did, byte flag, int udpPort, String serverString, String licenseDeviceKey);
public static native int PPPP_Connect_Break(String did);
public static native int PPPP_Check(int handle, st_PPPP_Session out);
public static native int PPPP_Read(int handle, byte channel, byte[] buf, int[] inOutSize, int timeoutMs);
public static native int PPPP_Write(int handle, byte channel, byte[] buf, int size);
public static native int PPPP_Check_Buffer(int handle, byte channel, int[] writeSize, int[] readSize);
public static native int PPPP_ForceClose(int handle);
public static native int PPPP_Close(int handle);
```
Prior art (Dmxsir `oracle/android/.../PPPP_APIs.java`, MIT) used exactly this minimal shape (11 natives + the two callbacks, private ctor) and the untouched ARM64 lib initialized, connected and returned mode 0 under Android (PUBLIC, MEDIUM).

**Legal/licence note (brief):** the vendor `.so` remain proprietary; we neither commit nor redistribute them — each user extracts from their own APK, the same model as yi-rtsp ("imports the library from the user's own APK"). Our shim is our own code that only re-declares names/signatures needed for interoperability (no vendor method bodies copied). Residual risk: Yi ToS (reverse engineering/third-party clients) and account flagging — mitigated by one session per camera, no reconnect storms, no payment endpoints. Hashes of vendor files in the manifest are not secrets and not vendor code. Prior-art repos are MIT; still, derive our code from the local analysis and cite, do not copy files.

### Pattern 3: TNP session — exact call order (LIVE-01/02)
VERIFIED-LOCAL from `fa/u1.java` (init), `com/tnp/TnpCamera.java` (connect/threads), `AntsCameraTnp` (play), `AntsCameraManage`:

```
Process-once (PpppRuntime, @Singleton, Mutex):
  NativeLibs.load("PPPP_API")                          -> JNI_OnLoad runs here
  r = PPPP_Initialize(byteArrayOf(0), 12)              // vendor default path (newP2PStrategy pref is false by default)
      r == 0 or r == -3002 (ALREADY_INITIALIZED) -> OK
  PPPP_Config_Debug(0, 4194311)                        // vendor value: native debug OFF (native logs may contain DID/server string)
  apiVersion = PPPP_GetAPIVersion()                    // expect 0xA2050401 for the pinned lib; log hex only (criterion 1)
  never PPPP_DeInitialize() during the app's life (vendor only on logout/teardown)

Per camera (TnpCameraLink, dedicated thread "p2p-<n>-connect"):
  flag = forceRelay ? 0xDF.toByte() : 0x49.toByte()    // 6.9.8: (relay<<7)|(tryTime<<1)|0x41, tryTime 4 (P2P) / 15 (relay)
  h = wakeup ? PPPP_WakeUp_And_Connect(did, flag, 0, initString, licenseDeviceKey)
             : PPPP_Connect(did, flag, 0, initString, licenseDeviceKey)
      did              = /v4/tnp/device_info data.DID   (NOT the list `did`)
      initString       = data.InitString                 (the "server string": encoded P2P-server list)
      licenseDeviceKey = data.License.split(":")[0]       (component 2 = "group key", unused for connect)
  h < 0  -> LinkError(code=h)   (table in Pitfalls)
  s = st_PPPP_Session(); PPPP_Check(h, s) != 0 -> close + LinkError
  mode = s.bMode & 0xFF: 0 "P2P", 1 "Relay", 2 "TCP" (TCP relay), 3 "SDEV" (relay via device server)   // vendor mapping
  debug display: mode + ConnectTime/ConnectTimeP2P/ConnectTimeRelay (ints, safe); "LAN" = mode 0 AND RemoteIP is RFC1918
      (computed in-process, never logged as an address)  [INFERENCE for LAN]
  start reader threads: ch0 (cmd), ch1 (audio, drain/discard in Phase 2), ch2 (live I), ch3 (live P), ch6 (new-live)
  THEN write burst on ch0, back to back, no reads in between:
      4881 SET_RESOLUTION     payload 8 B: u32 BE resolution, u32 BE useCount   (useCount++ before building)
      9029 TNP_START_REALTIME payload 4 B: [useCount, resolution, 1, 0]           (useCount++ before building)
      768  AUDIOSTART         payload 8 zero bytes
      resolution values (vendor UI, VERIFIED-LOCAL CameraPlayerFragment): 0 Auto, 1 High/HD, 2 Normal/SD, 4 4K.
      Phase 2: 1 when mode == P2P, 2 when relay (matches the Phase 3 rule "SD on relay").
      Frames carry useCount in FrameHead[5]; drop frames whose useCount != current (stale generation).
  first ch0 reply of interest: 4882 with authResult (int at IOCtrl-head offset 8) == 0 -> "auth ok"
  frames on ch2/ch3 (or ch6) -> FramePipeline

Teardown (close(), idempotent; abort() callable from any thread):
  write 767 STOP (8 zero bytes) if streaming, best effort
  stop flags -> PPPP_Connect_Break(did) -> PPPP_ForceClose(h) (unblocks reads with -3014) -> join readers (bounded)
```
Mode semantics for `bMode` and the flag bit layout are VERIFIED-LOCAL as the vendor's own mapping; the meaning of individual flag bits (bit0 = LAN search, bits1-5 = P2P try seconds, bit6 = relay enabled, bit7 = TCP relay) is INFERENCE from the formula — mirror the vendor bytes, do not "tune" them.

### Pattern 4: TNP framing and per-command auth (pure Kotlin, JVM-tested)
All big-endian for TNP (`AntsCamera.isByteOrderBig()` is true for type 2/3, false for 0/1 — VERIFIED-LOCAL).

```
TNPHead (8):        [0] version (2; 1/3 also defined) [1] ioType (1 video, 2 audio, 3 command)
                    [2] abilityRet (rx only) [3] reserved  [4..8) u32 dataSize (bytes after this head)
TNPIOCtrlHead (40): [0..2) u16 commandType [2..4) u16 commandNumber (starts at 1, ++ per command)
                    [4..6) u16 exHeaderSize (0) [6..8) u16 payloadSize
                    tx: [8..40) authInfo, 32 B, ASCII, zero-padded
                    rx: [8..12) s32 authResult (0 ok; 1|2 -> wrong password (-20009); 4 -> retry)
Command unit:       TNPHead(version, 3, 40 + payload.size) + IOCtrlHead + payload   (written with PPPP_Write ch0)
authInfo:           encrypted (ipcParam.p2p_encrypt, default true):
                       nonce   = noncePrefix7 + random8          (alphabet A-Z a-z 0-9; prefix fixed per connection)
                       pass15  = Base64(HmacSHA1(key = UTF8(cameraPassword), msg = UTF8("user=xiaoyiuser&nonce=" + nonce))).take(15)
                       authInfo = nonce + "," + pass15        (31 ASCII bytes + zero)
                    not encrypted: "admin," + cameraPassword
Frame unit (ch2/3/6): TNPHead(ioType 1) + FrameHead(24) + payload
FrameHead (24):     [0..2) u16 codec_id (78 H.264, 81 H.265) [2] flags (bit0 = I-frame) [3] liveFlag
                    [4] onlineNum [5] useCount [6..8) u16 seq [8..10) u16 width [10..12) u16 height
                    [12..16) u32 timestamp (s) [16] isDay [17] ref/cover [18] outloss [19] inloss
                    [20..24) u32 timestamp_ms
Audio unit (ch1):   TNPHead(ioType 2) + FrameHead(24) + payload; if header version >= 2 decrypt every full
                    16-byte block of payload (AES-ECB, same key) — Phase 3
Header version:     start with 2; if the first ch0 reply carries a different version, adopt it and resend the first command
                    (vendor mNeedDetectHeaderVersion). Captures of 6.9.7 and older clients show 2.
```
Reading a channel is a byte stream: vendor reads exactly 8 bytes (head), validates `0 < dataSize <= maxBuf(ch)` (cmd 204800, audio 5120, I 1048576, P 307200), then reads exactly `dataSize`. A head failing the size check = dirty stream → reconnect (vendor -9000).

### Pattern 5: Transport decision (fixes `mismatch=true`)
VERIFIED-LOCAL (`fa/u1.java` c2()/l0()/class i; `P2PDevice.getDeviceType`):
```
for every camera: GET /v4/tnp/device_info (seq, userid, uid)   // vendor does this for ALL devices
  if code 20000 and isTnpDid(data.DID):                         // startsWith("TNP") && split("-").size == 3
        transport = TNP, p2pId = data.DID, initString = data.InitString, license = data.License
  else: p2pId = uid; transport = if (uid.lowercase() == uid) LANGTAO else TUTK
the device-list `type` and `did` fields are NOT used for transport
```
Consequences: (a) the current resolver's `mismatch` flag compares the list display `did` with the TNP regex → meaningless; replace with the rule above and drop the regex's digits-only middle part (vendor accepts any 3-part `TNP…` id). (b) Call `device_info` once for the "TUTK" camera too (cheap, already signed); if it returns a TNP DID, both cameras are TNP and plan 2B shrinks to "nice to have". (c) Keep the list `type` only as a logged hint. No "try both transports" connect loop is needed.

### Pattern 6: Frame pipeline → MediaCodec (LIVE-01, LIVE-03)
Pure stages (JVM-tested) then one thin Android sink:
```
reader ch2 / ch3 / ch6 ──► TnpCodec.parseUnit(head8 + body)       (ioType must be 1, body > 24)
   ──► FrameInfo.parse(body[0..24), bigEndian = true)
   ──► FrameAssembler: drop useCount != current; I-frame (flags bit0) resets the decode point and is emitted;
        P-frame emitted only if seq == last+1 (u16 wrap); future P buffered (cap 100, drop oldest);
        P with seq <= last dropped; P before the first I dropped        (vendor rule, Dmxsir tnp-video-flow)
   ──► IFrameDecryptor (only I-frames, only if p2pEncrypt): payload = body[24..]; if payload.size >= 36:
        AES/ECB/NoPadding decrypt payload[4..20) and payload[20..36) independently, key = ASCII(password + "0")
        FAIL-OPEN like the vendor: key length not 16/24/32 (e.g. default "888888" -> 7 B) => leave bytes as is
   ──► NalUtils: detect Annex-B (00 00 00 01 / 00 00 01) else 4-byte length-prefixed -> convert to Annex-B;
        NAL type = H.264 b & 0x1F, H.265 (b >> 1) & 0x3F; collect SPS/PPS (7/8) or VPS/SPS/PPS (32/33/34)
        self-check: after decrypt the first NAL of an I-frame must be SPS/VPS/IDR (5 / 19,20) -> "decryptOk=true"
   ──► VideoSink (Android): MediaCodec
```
MediaCodec rules (Android API, MEDIUM-HIGH; device-verified only in this phase's checkpoint):
- Create the decoder on the first I-frame: mime from codec id (78 → `video/avc`, 81 → `video/hevc`, else `UnsupportedCodec(id)`), size from FrameHead w/h. Set csd from the I-frame: AVC `csd-0` = SPS, `csd-1` = PPS (each with start code); HEVC `csd-0` = VPS+SPS+PPS concatenated. Also queue the whole I-frame (decoders accept in-band parameter sets).
- Async mode: `setCallback()` before `configure(format, surface, null, 0)`; `onInputBufferAvailable` pulls from a small bounded frame queue (drop-oldest → re-arm keyframe gate); `onOutputBufferAvailable` → `releaseOutputBuffer(index, true)` (render now, no A/V sync in Phase 2).
- API 30+ (Mi 9 is Android 11 = API 30): set `KEY_LOW_LATENCY = 1` if `codecInfo.getCapabilitiesForType(mime).isFeatureSupported(FEATURE_LowLatency)`.
- H.265 (criterion 4): pick the codec with `MediaCodecList(REGULAR_CODECS).findDecoderForFormat(format)`; check `MediaCodecInfo.isHardwareAccelerated()` (API 29+). If no decoder → UI shows "H.265: no decoder on this phone" (explicit message, criterion 4). Mi 9 (Snapdragon 855) ships a Qualcomm HW HEVC decoder (`c2.qti.hevc.decoder` / `OMX.qcom.video.decoder.hevc`) — MEDIUM (training knowledge); the debug screen must print the chosen codec name so the checkpoint proves it.
- `presentationTimeUs` = monotonic counter derived from `timestamp_ms` (fallback: arrival `SystemClock.elapsedRealtimeNanos()/1000`); never decreasing.
- Surface destroyed → stop+release codec, keep the P2P session; new Surface → new codec on the next I-frame (Phase 3 adds an I-frame request).
- `MediaCodec.CodecException` → release, wait for next I-frame, count it (`decoderResets`); 3 resets in a row → error state, not a loop.
- `SurfaceView` via Compose `AndroidView(factory = { SurfaceView(it) })` with `SurfaceHolder.Callback` → `attachSurface/detachSurface`.

### Pattern 7: Threads, timeouts, cancellation (pure logic testable with a fake native)
- One `TnpCameraLink` per camera; native work on named platform threads: `p2p-<cam>-connect`, `p2p-<cam>-rx<ch>` for ch 0,1,2,3,6, `p2p-<cam>-tx` (single writer; burst written from it). Never `Dispatchers.IO` for native calls.
- `readExactly(ch, n)`: loop `PPPP_Read(h, ch, buf, size=[remaining], timeoutMs=300)`; each call may return `-3003` (TIME_OUT) with a partial `size[0]` > 0 → accumulate and continue; check `stopping` flag between calls; `-3014` (closed by us) → exit quietly; any other negative → `LinkError(code)`. The vendor uses `-1` (infinite) and the same "continue on -3003/-3014" rule (VERIFIED-LOCAL `handleTNPResult`). INFERENCE: partial-read semantics with finite timeout follow the CS2 API ("size is updated to bytes actually read") — the JVM fake must model it and the device checkpoint must show frames still arrive with a finite timeout.
- `abort()` from any thread: set `stopping`, `PPPP_Connect_Break(did)` (unblocks a connect in progress), `PPPP_ForceClose(handle)` (unblocks reads), then join readers with a 2 s bound. Idempotent.
- `PpppNative` interface (9 methods) wraps the shim so `TnpCameraLink` logic runs on the JVM against `FakePpppNative` (scripted returns, partial reads, -3012 after N ms).
- One session per camera: a `ConcurrentHashMap<deviceId, TnpCameraLink>`; a second open for the same camera reuses or refuses, never connects twice.

### Pattern 8: TUTK adapter (plan 2B) — same port, different native calls
VERIFIED-LOCAL (`com/tutk/IOTC/TutkCamera.java`, `AntsCameraTutk.java`), little-endian everywhere:
```
once:   loadLibrary IOTCAPIs, sCHL, AVAPIs (dependency order)
        IOTC_Initialize2(0)                         // no license key: none exported in this lib build
        avInitialize(32)                            // vendor: maxCameras(2) * 16
        IOTC_Get_Version(int[1]); avGetAVApiVer()   // criterion 1 sanity for TUTK
connect: sid0 = IOTC_Get_SessionID()
        sid  = IOTC_Connect_ByUID_Parallel(p2pId = cloud uid, sid0)       (abort: IOTC_Connect_Stop_BySID(sid0))
        IOTC_Session_Check_Ex(sid, St_SInfoEx) -> Mode: 0 P2P, 1 Relay, 2 LAN   (vendor mapping)
auth:   encrypted: nonce = genNonce(15); pass = Base64(HmacSHA1(pwd, "user=xiaoyiuser&nonce="+nonce)).take(15)
        av = avClientStart2(sid, nonce, pass, 15 /*s*/, int[1] servType, 0 /*channel*/, int[1] resend)
        plain:     avClientStart2(sid, "admin", pwd, 15, …)
        av == -20009 (AV_ER_WRONG_VIEWACCorPWD) -> WrongPassword
start:  avSendIOCtrl(av, encrypted ? 8190 : 511, u32 LE useCount, 4)
recv:   avRecvFrameData2(av, buf[300000], 300000, actualFrameSize int[1], expectedFrameSize int[1], frameInfo byte[24], 24, actualInfoSize int[1], frameIndex int[1])
        > 0 = bytes; -20012 DATA_NOREADY -> sleep 30 ms (vendor reconnects after 300 in a row);
        -20014 LOSED / -20013 INCOMPLETE -> drop to next I-frame; -20015/-20016/-20010 -> session lost
        frameInfo = same 24-byte layout as TNP FrameHead, little-endian; same I-frame AES rule, same key
stop:   avSendIOCtrl(767 STOP) -> avSendIOCtrlExit(av) -> avClientStop(av) -> avClientExit(sid, 0) -> IOTC_Session_Close(sid)
```
Shim classes the TUTK libs FindClass (VERIFIED-LOCAL strings): `com/tutk/IOTC/Region` (enum REGION_ALL/CN/EU/US), `st_LanSearchInfo{UID,IP,port}`, `st_LanSearchInfo2{+DeviceName}`, `st_SearchDeviceInfo{Reserved,UID,IP,port,DeviceName,nNew}`, `St_AvIdentity{identity,length}`, `AVAPIs$avPasswordAuthFn` and six sibling `$av*Fn` interfaces; `St_SInfoEx` fields: `byte CorD, Mode, LocalNatType, RemoteNatType, RelayType, isSecure; int GID, IOTCVersion, NetState, PID, RX_count, TX_count, VID, RemotePort, RemoteWANPort, size; byte[] UID(21), RemoteIP(17), RemoteWANIP(17)`. None of the TUTK libs has `JNI_OnLoad`.

**Cost of 2B** (estimate): shim ≈ 10 small Java files (names/fields only), `TutkRuntime` + `TutkCameraLink` ≈ 250 lines, LE variants of the already-tested `FrameInfo`/payload builders, one phone checkpoint. Everything from `FrameAssembler` down is shared. ≈ 1 plan, 1 device round trip. Do it only if the corrected resolver (Pattern 5) still classifies camera 2 as TUTK.

### Anti-patterns to avoid
- **Blocking read after the first command:** write the startup burst first with readers already running (prior-art `-3012`).
- **Infinite `PPPP_Read` timeouts (`-1`, as the vendor does):** use a finite timeout (200-500 ms) and loop; treat `-3003` (TIME_OUT) and partial reads as "continue", `-3014` as "closed by us".
- **Logging vendor values:** never log DID, InitString, License, password, nonce, authInfo, remote/WAN IPs. Native debug must stay off (`PPPP_Config_Debug(0, …)`).
- **`System.loadLibrary` in a shim `static {}`:** a missing/wrong-ABI lib becomes an `ExceptionInInitializerError` on first class touch; load explicitly in `NativeLibs` and expose a state.
- **Coroutine cancellation as stop:** JNI calls are not interruptible; stop = flag + `PPPP_ForceClose`.

### Recommended plan split
| Plan | Content | Device? |
|---|---|---|
| 2A-0 | Fix `TransportResolver` to the vendor rule (device_info for every camera, DID shape, uid case); log transport + `tnpDid=shape-ok` only | JVM + 1 phone run (camera list refresh) |
| 2A-1 | Gradle extract/verify task + manifest; Java shim; `NativeLibs` + `PpppRuntime`; debug "Native" row: libs loaded, `PPPP_GetAPIVersion` hex | phone checkpoint #1 (criterion 1) |
| 2A-2 | Pure core: `YiP2pAuth`, `TnpCodec`, `IoCtrlMessages`, `FrameInfo`, `IFrameDecryptor`, `NalUtils`, `FrameAssembler`, `CameraLink` + `FakePpppNative` tests (parallel with 2A-1) | JVM only (criterion 5) |
| 2A-3 | `TnpCameraLink` (connect/check/readers/burst/abort) + `VideoDecoder` + debug Live screen (mode, codec, decoder name, fps, frames, decryptOk) | phone checkpoints #2 Wi-Fi, #3 cellular (criteria 2, 3, 4) |
| 2A-4 | GRACE: supersede `M-STREAM-LIVE`/`M-STREAM-DECODE`, add `M-P2P-NATIVE`, `M-STREAM-CORE`, `M-MEDIA-RENDER`, `M-UI-LIVE-PROBE`; verification-plan entries | docs |
| 2B | TUTK adapter (only if camera 2 is still TUTK after 2A-0) | phone checkpoint #4 |

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---|---|---|---|
| CS2/Yi PPPP transport (hole punching, relay, DRW reliability, signed `_EX` login) | A Kotlin PPPP client | Vendor `libPPPP_API.so` via the shim | Yi fork details (signatures keyed by license, server-string decoding) are unpublished; prior art that tried still uses the vendor lib |
| TUTK/Kalay transport + DTLS | Anything | Vendor `libIOTCAPIs/AVAPIs/sCHL` | Same reason; DTLS in `libsCHL` |
| AES, HMAC-SHA1, Base64 | Own crypto | `javax.crypto`, `java.util.Base64` | Identical on JVM and ART |
| H.264/H.265 decoding | Software decoder, vendor `libh265decoder.so`, FFmpeg | Platform `MediaCodec` | HW path, no extra binaries |
| DRW packet framing / coalescing | Splitting/merging UDP packets | `PPPP_Write`/`PPPP_Read` byte streams per channel | The lib does DRW; one app message can span many datagrams (9032 spans 6) |
| Native "connect timeout" logic | Watchdog threads that kill sockets | `PPPP_Connect_Break` + `PPPP_ForceClose` | The only safe way to unblock vendor calls |
| ZIP-in-ZIP reading / hashing in Gradle | Shelling out to unzip/certutil | `java.util.zip.ZipInputStream` + `MessageDigest` in the build script | Works on Windows without admin/AppLocker issues |

**Key insight:** our code owns only bytes that the vendor app builds in Java (headers, auth, decrypt, ordering). Everything the vendor does in C stays in the vendor `.so`.

## Common Pitfalls

### Pitfall 1: Wrong transport/P2P id for the "mismatch" camera
**What goes wrong:** connecting with the list `did` (display DID) or trusting `type`. **Why:** vendor uses `device_info.DID` for TNP and `uid` for TUTK; `type`/`did` are not used. **Avoid:** Pattern 5 before any connect. **Warning sign:** `PPPP_Connect` → `-3004` INVALID_ID / `-3008` INVALID_PREFIX.

### Pitfall 2: `JNI_OnLoad` / FindClass aborts
**What goes wrong:** `System.loadLibrary("PPPP_API")` throws, or `PPPP_Check` aborts the process (`JNI DETECTED ERROR`/`NoSuchFieldError` → abort). **Why:** missing `PPPP_APIs` class/callbacks or a renamed/minified field in `st_PPPP_Session`. **Avoid:** exact names; R8 keep rules `-keep class com.p2p.pppp_api.** { *; }`, `-keep class com.tnp.model.** { *; }`, `-keep class com.tutk.IOTC.** { *; }` (release is minified: `isMinifyEnabled = true`). **Warning sign:** works in debug, dies in release.

### Pitfall 3: Stop-and-wait first command → `-3012`
**Avoid:** readers first, burst `4881 → 9029 → 768` back to back (PUBLIC, MEDIUM: Dmxsir phase-2c-failure-analysis + golden trace). **Warning sign:** `PPPP_Read` on ch0 returns `-3012` (SESSION_CLOSED_REMOTE) ~11 s after connect with zero bytes.

### Pitfall 4: Secrets leaking through native logs or our diagnostics
**Why:** the lib's own debug output includes server-string decode and DID (strings like `Decode P2PServerString:%s`, `DID:` in session dumps); our own temptation to log `st_PPPP_Session` IPs. **Avoid:** `PPPP_Config_Debug(0, 4194311)` right after init (vendor value); log only mode, codes, sizes, counts, booleans; extend `RedactionScanner` with fixtures for a TNP DID shape (`TNPXXXX-123456-ABCDE`), a 15-char password, a 31-char authInfo, InitString-like 80+ uppercase strings, private/WAN IPv4. **Note:** logcat (not our diag file) is outside the scanner — the checkpoint must not ask the user to send logcat.

### Pitfall 5: I-frame decrypt "succeeds" silently with a wrong key
**Why:** the vendor's AES helper swallows key-length errors and returns the input unchanged; a wrong password just yields garbage NALs. **Avoid:** fail-open to match vendor, but compute `decryptOk` (first NAL after decrypt is SPS/VPS/IDR) and show it on the debug screen. **Warning sign:** frames arrive, decoder outputs nothing/green; `decryptOk=false`.

### Pitfall 6: MediaCodec stalls / black screen
**Causes:** feeding P-frames before an I-frame; missing csd for some decoders; length-prefixed NALs; a dropped I-frame under a slow consumer; decoder released with the Surface but never recreated. **Avoid:** Pattern 6 rules; queue drop re-arms the keyframe gate; counters for `framesIn/framesRendered/decoderResets` on the debug screen.

### Pitfall 7: ABI and packaging
- Only arm64-v8a + armeabi-v7a exist; `ndk.abiFilters += listOf("arm64-v8a","armeabi-v7a")` so an x86 build never ships a half set. Mixing 32/64-bit: Android loads one ABI per process — all four libs must come from the same ABI dir (the task extracts per ABI; never copy a v7a lib into arm64).
- `packaging.jniLibs.useLegacyPackaging = true` (AGP 8.2.2 does not page-align uncompressed `.so` for 16 KB devices; extraction to disk avoids it; ELF LOAD alignment is 64 KB = fine).
- Do not package `libc++_shared.so`; NEEDED lists `libstdc++.so` (system).

### Pitfall 8: Duplicate init / deinit
`PPPP_Initialize` twice → `-3002` (treat as OK); `PPPP_DeInitialize` while a session/reader is alive → native crash risk. Init once in `PpppRuntime`; never deinit during the app's life. TUTK: `IOTC_Initialize2` once; `avInitialize` once (vendor refcounts).

### Pitfall 9: Sessions vs the official app
Camera-side session caps exist: TNP `-3029` DEVICE_MAX_SESSION, kick command `9033` (reason 1 → max sessions `-3101`, else `-3100`), TUTK `-48`. Whether opening our session kicks the official app is UNKNOWN (no public data; Yi supports several viewers per camera in practice). **Avoid:** one session per camera, close on leaving the screen, handle 9033 as a clean "kicked" state (no auto-reconnect loop). Checkpoint step: official app live on the same camera while ours connects.

### Pitfall 10: Android 11 / MIUI
Foreground-only in Phase 2 (no service), so background limits/MIUI battery killing do not apply yet; close the session in `onStop` of the debug screen. No `MulticastLock` needed (the vendor app has none). Native SIGSEGV in a vendor lib kills our whole process — acceptable for Phase 2; keep `p2p/` isolated so a `:p2p` process is possible later.

### Pitfall 11: Header version / byte order
TNP is big-endian, TUTK little-endian (`isByteOrderBig` = type 2/3). Header version: send 2; if the first reply's `TNPHead.version` differs, adopt it and resend the first command (vendor logic). Never hard-code 1.

### PPPP error table (VERIFIED-LOCAL, `PPPP_APIs` constants + `TnpCamera`)
| Code | Name | Classify |
|---|---|---|
| -3001 | NOT_INITIALIZED | bug (init first) |
| -3002 | ALREADY_INITIALIZED | OK |
| -3003 | TIME_OUT | read: continue; connect: retryable |
| -3004 / -3008 | INVALID_ID / INVALID_PREFIX | credentials (wrong DID) |
| -3005 | INVALID_PARAMETER | bug |
| -3006 | DEVICE_NOT_ONLINE | offline |
| -3007 | FAIL_TO_RESOLVE_NAME | network |
| -3009 | ID_OUT_OF_DATE | refresh credentials |
| -3010 / -3026 | NO_RELAY_SERVER / NO_AVAILABLE_P2P_SERVER | network/relay |
| -3011 | INVALID_SESSION_HANDLE | bug/closed |
| -3012 | SESSION_CLOSED_REMOTE | retryable (see Pitfall 3) |
| -3013 | SESSION_CLOSED_TIMEOUT | retryable |
| -3014 | SESSION_CLOSED_CALLED | closed by us |
| -3015 | REMOTE_SITE_BUFFER_FULL | back-pressure |
| -3017 / -3029 | MAX_SESSION / DEVICE_MAX_SESSION | busy (official app?) |
| -3019 | USER_CONNECT_BREAK | aborted by us |
| -3021 | INVALID_APILICENSE | license/InitString mismatch |
| -3023 | INVALID_SERVER_STRING | InitString bad |
| -3031..-3038 | INVALID_NONCE / TICKET / TIMESTAMP / SIGNATURE / PROXY / TOKEN / TOKEN_TIME | credentials/clock |
| -3100 / -3101 | (app) DEVICE_KICK / KICK_MAX_SESSION (from 9033) | kicked |
| -20009 | (app) authResult 1/2 in IOCtrl reply | wrong password |
| -9000 | (app) MANUAL_RETRY (dirty head / authResult 4) | reconnect |

## Code Examples

### Command unit + auth (pure Kotlin)
```kotlin
// Source: layout VERIFIED-LOCAL (TNPHead/TNPIOCtrlHead/AntsUtil.getPassword); our own code.
object YiP2pAuth {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    fun nonce(len: Int, rnd: java.util.Random): String = buildString { repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
    fun hmacPassword(cameraPassword: String, nonce: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(cameraPassword.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val b64 = java.util.Base64.getEncoder().encodeToString(mac.doFinal("user=xiaoyiuser&nonce=$nonce".toByteArray(Charsets.UTF_8)))
        return b64.take(15)
    }
    fun authInfo(encrypted: Boolean, password: String, noncePrefix7: String, rnd: java.util.Random): ByteArray {
        val text = if (encrypted) { val n = noncePrefix7 + nonce(8, rnd); "$n,${hmacPassword(password, n)}" } else "admin,$password"
        return text.toByteArray(Charsets.US_ASCII).copyOf(32)   // zero-padded
    }
}

object TnpCodec {
    fun commandUnit(version: Int, type: Int, number: Int, auth: ByteArray, payload: ByteArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(8 + 40 + payload.size).order(java.nio.ByteOrder.BIG_ENDIAN)
        bb.put(version.toByte()).put(3).put(0).put(0).putInt(40 + payload.size)          // TNPHead
        bb.putShort(type.toShort()).putShort(number.toShort()).putShort(0).putShort(payload.size.toShort())
        bb.put(auth, 0, 32).put(payload)
        return bb.array()
    }
}
```

### I-frame decrypt (vendor-compatible, fail-open)
```kotlin
// Source: AntsUtil.decryptIframe / AESIPC (VERIFIED-LOCAL); our own code.
object IFrameDecryptor {
    fun decryptInPlace(payload: ByteArray, password: String): Boolean {
        if (payload.size < 36) return false
        val key = (password + "0").toByteArray(Charsets.US_ASCII)
        if (key.size != 16 && key.size != 24 && key.size != 32) return false      // vendor silently leaves data
        val c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        c.doFinal(payload.copyOfRange(4, 20)).copyInto(payload, 4)
        c.doFinal(payload.copyOfRange(20, 36)).copyInto(payload, 20)
        return true
    }
}
```

### Startup burst (link, readers already running)
```kotlin
// Source: order from Dmxsir phase-2c-failure-analysis (PUBLIC) + AntsCameraTnp/CameraCommandHelper payloads (VERIFIED-LOCAL)
useCount++; tx.write(cmd(4881, be32(resolution) + be32(useCount)))
useCount++; tx.write(cmd(9029, byteArrayOf(useCount.toByte(), resolution.toByte(), 1, 0)))
            tx.write(cmd(768, ByteArray(8)))
// no read between writes; ch0 reader routes 4882 (authResult) and 9033 (kick)
```

### Gradle extraction sketch (Kotlin DSL)
```kotlin
// Our own code; hashes from app/vendor-natives.sha256
val vendorOut = layout.buildDirectory.dir("generated/vendorJniLibs")
val extractVendorNatives by tasks.registering {
    inputs.files(fileTree(rootDir.resolve("_apk")) { include("*.apk", "*.apkm", "*.xapk", "*.apks", "extracted/split_config.*.apk") })
    inputs.file("vendor-natives.sha256"); outputs.dir(vendorOut)
    doLast { /* for abi in [arm64-v8a, armeabi-v7a]: find split/universal apk (zip-in-zip via ZipInputStream),
               copy lib/<abi>/{libPPPP_API,libIOTCAPIs,libAVAPIs,libsCHL}.so, sha256 each, compare with manifest;
               missing source -> write MISSING marker + logger.warn; mismatch -> throw GradleException(observed hashes) */ }
}
val verifyVendorNatives by tasks.registering { dependsOn(extractVendorNatives); doLast { /* fail if MISSING marker */ } }
android.sourceSets["main"].jniLibs.srcDir(vendorOut)
tasks.named("preBuild") { dependsOn(extractVendorNatives) }
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("install") }.configureEach { dependsOn(verifyVendorNatives) }
```

## State of the Art

| Old approach (in repo / earlier research) | Current approach | Impact |
|---|---|---|
| `M-STREAM-LIVE`: cloud HLS / local RTSP via ExoPlayer | P2P via vendor PPPP/TUTK libs, `CameraLink` port | Supersede both GRACE nodes |
| `M-STREAM-DECODE`: cloud AES key handshake | Local key = password + "0", two AES blocks per I-frame | Supersede |
| Transport from list `type` + p2pid regex | Vendor rule: `device_info.DID` shape / uid case | Fix resolver (2A-0) |
| "No JNI_OnLoad" (STACK.md) | `libPPPP_API.so` HAS `JNI_OnLoad`; IOTC/AV do not | Shim must exist before load |
| Flag 0x4B (6.9.7 analysis) | 6.9.8 computes 0x49 (P2P) / 0xDF (force/TCP relay) / 0x5F (UDP relay) | Mirror 6.9.8 |
| `IOTC_Session_Channel_Check_ON_OFF` "not exported" (ARCHITECTURE.md) | It is exported in 6.9.8 | Irrelevant; we do not call it |

## Open Questions

1. **Is camera 2 really TUTK?** Known: list says type 0; it was never asked for `device_info`. Unclear: whether `device_info` returns a TNP DID for it. Recommendation: plan 2A-0 asks once; 2B only if still TUTK.
2. **Does the account use the cloud "new P2P strategy"?** Known: `p2p_strategy_android` cloud flag switches init to `PPPP_Initialize(InitString bytes, 12)` and flag try-time 3 (0x47). Unclear: value for this account (we do not call that config endpoint). Recommendation: default path `{0}`; if connect returns `-3023/-3026/-3021`, the checkpoint tries init-with-InitString as a one-shot diagnostic (requires process restart because init is once).
3. **Concurrent sessions with the official app.** Unknown camera cap / kick policy. Recommendation: phone checkpoint step with the official app live in parallel; handle 9033 as terminal "kicked".
4. **Annex-B vs length-prefixed, SPS/PPS placement, actual codec ids of the user's cameras.** Unknown until first frames. `NalUtils` handles both; debug screen prints framing + NAL types (no payload bytes).
5. **Finite-timeout `PPPP_Read` partial semantics.** INFERENCE from CS2 API; verify on device (frames keep flowing with 300 ms timeout). Fallback: vendor-style `-1` timeout with `ForceClose` for stop.
6. **License component 2 ("group key")** has no known consumer for viewing. Ignore.

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|---|---|---|---|---|
| Local Yi Home APK splits (`_apk/extracted/split_config.arm64_v8a.apk`, `…armeabi_v7a.apk`) | criterion 1 extraction | ✓ | 6.9.8 (versionCode 454) | user's own APK via `-PvendorApk` |
| Decompile `_re/jadx2,6,7,8` | reference only | ✓ | jadx 1.5.6 output | — |
| Gradle wrapper via `tools/gradle.ps1` (JDK 17 inside) | build/tests | ✓ | Gradle 8.5, AGP 8.2.2 | — (system `java` on PATH is 1.8: always go through `tools/gradle.ps1`) |
| adb (USB, second cable) | log pull `run-as` | ✓ | 1.0.41 | user reads values from the debug screen |
| Physical Mi 9, arm64, Android 11 | all native checks | ✓ (user) | MIUI 12.5 | none (no emulator) |
| Android NDK | — | not needed | — | prebuilt `.so` only |
| Python 3.12 | optional offline helpers (ELF listing) | ✓ | 3.12.1 | — |

Missing with no fallback: none. Note: `adb install` blocked by MIUI → every checkpoint = user installs the APK by hand.

## Validation Architecture

> `workflow.nyquist_validation` is `false` in `.planning/config.json`, but the orchestrator requested this section; it maps each success criterion to JVM tests vs phone checks.

### Test framework
| Property | Value |
|---|---|
| Framework | JUnit 5.10.2 (+ vintage), MockK 1.13.9, kotlinx-coroutines-test 1.7.3; existing `TraceRecorder`, `RedactionScanner`, `FakeCameraStreamServer` |
| Config | `app/build.gradle.kts` (`de.mannodermaus.android-junit5`) |
| Quick run | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.stream.*"` |
| Full suite | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest` |
| Log sweep after a phone run | `… tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=<pulled diag_log.txt> --rerun-tasks` |

### Known-answer sources
| Component | Vector source | Confidence |
|---|---|---|
| AES-128-ECB primitive | FIPS-197 App. C.1: key `000102…0f`, pt `00112233…ff` → ct `69c4e0d86a7b0430d8cdb78070b4c55a` | HIGH (public standard) |
| HMAC-SHA1 primitive | RFC 2202 test cases 1-2 (`key=0x0b*20, "Hi There"` → `b617318655057264e28bc0b6fb378c8ef146be00`) | HIGH |
| `IFrameDecryptor` offsets | Synthetic: encrypt `00 00 00 01 + bytes(0..31) + "tail"` blocks [4,36) with key `"123456789012345"+"0"`, assert round trip; same construction as Dmxsir `test_encrypted_iframe_two_blocks_decrypt` (MIT) | HIGH |
| `YiP2pAuth` | Fixed nonce + fixed fake password → pinned 15-char output computed once with JDK and frozen (protocol pinning); plus structural asserts (31 ASCII bytes + zeros, comma at 15) matching the official capture shape (Dmxsir golden trace) | HIGH structure / MEDIUM content (no real capture) |
| `TnpCodec` | Byte-exact expected arrays for 4881 (56 B total, size field 48), 9029 (52 B), 768 (56 B) — sizes match official captures (PUBLIC) | HIGH |
| `FrameInfo` | Hand-built 24-byte heads, BE and LE | HIGH |
| `NalUtils` | Annex-B and 4-byte length-prefixed SPS/PPS/IDR and VPS/SPS/PPS/IDR samples (synthetic NAL headers) | HIGH |
| `FrameAssembler` | Scripted sequences: P before I, out-of-order P, u16 wrap, stale useCount, cap 100 | HIGH |
| `TransportDecision` | Table: TNP DID shapes (3 parts / 2 parts / lowercase), uid all-lowercase → LANGTAO, mixed → TUTK, device_info failure | HIGH |
| `TnpCameraLink` logic | `FakePpppNative`: connect codes, partial reads with -3003, -3012 mid-stream, abort during connect/read, burst order trace | MEDIUM (fake models inferred semantics) |

### Phase requirements → test map
| Criterion / Req | Behavior | Type | How |
|---|---|---|---|
| C1 | Extraction + manifest; no vendor binary in git | build + script | `tools/gradle.ps1 :app:extractVendorNatives` passes; with `_apk` renamed: JVM tests still pass, `assembleDebug` fails with the message; `git ls-files | grep -E "\.so$"` empty; pre-commit hook unchanged |
| C1 | `PPPP_GetAPIVersion` on arm64 phone | phone | Checkpoint #1: debug screen shows `libs=OK abi=arm64-v8a ppppApi=0xA2050401` |
| C2 / LIVE-01 | Wi-Fi live video | phone | Checkpoint #2: moving picture; screen shows `mode=P2P lan=yes codec=H264|H265 decoder=<name> fps>0 decryptOk=true` |
| C3 / LIVE-02 | Cellular live video + mode | phone | Checkpoint #3: Wi-Fi off; `mode=P2P|Relay|TCP|SDEV`; moving picture |
| C4 / LIVE-03 | H.265 like H.264 or explicit message | JVM + phone | JVM: mime mapping 78/81/other; phone: codec row shows id + decoder name; if the user has no H.265 camera, record "H.265 path untested on device" honestly |
| C5 | Pure core + GRACE | JVM + docs | `--tests "com.yicamalt.stream.*"` green; `docs/*.xml` contain no live `M-STREAM-LIVE`/`M-STREAM-DECODE` (status superseded) and new module IDs with `V-M-*` |
| SEC (standing) | No secrets in diag log | JVM sweep | `RealLogScanTest` on the pulled log with new P2P fixtures |

### Phone checkpoint script (what the user does — minimal)
1. Install the APK by hand (MIUI). Open app → camera list → long-press/“Debug live” on the TNP camera (Wi-Fi ON).
2. Press **Connect**. Wait ≤ 20 s. Report: picture moves yes/no + the one-line status (mode, codec, fps, decryptOk) — the screen shows only non-secret values.
3. Turn Wi-Fi OFF (mobile data ON), press **Reconnect**, report the same line.
4. Open the official Yi app on the same camera while ours shows video; report whether either one stops.
5. Connect the cable; the agent pulls `files/diag_log.txt` with `adb exec-out run-as com.yicamalt cat files/diag_log.txt` and runs the redaction sweep, then deletes the raw log.

### Wave 0 gaps
- [ ] `app/src/test/java/com/yicamalt/stream/` test package (new) + `FakePpppNative`
- [ ] Redaction fixtures for TNP DID / InitString / authInfo / IPv4 shapes in `RedactionFixtures.kt`
- [ ] `app/vendor-natives.sha256` + extraction task (none today)

## Sources

### Primary (HIGH)
- Local decompile, Yi Home 6.9.8 (git-ignored): `_re/jadx6/.../com/p2p/pppp_api/PPPP_APIs.java` (natives, error constants); `_re/jadx7/.../com/tnp/TnpCamera.java` (connect flag formula, mode mapping, readers, auth, header-version detection, buffer sizes, teardown), `com/tnp/model/{TNPHead,TNPIOCtrlHead,TNPFrameHead,st_PPPP_Session,st_PPPP_NetInfo}.java`; `_re/jadx8/.../com/xiaoyi/camera/sdk/{P2PDevice,AntsCameraManage,AntsCameraTnp,AntsCameraTutk,AntsCamera,CameraCommandHelper}.java`, `com/xiaoyi/camera/util/{AntsUtil,AESIPC,Base64}.java`; `_re/jadx6/.../com/tutk/IOTC/{TutkCamera,AVFrame,AVAPIs,IOTCAPIs,AVIOCTRLDEFs,St_SInfoEx,...}.java`; `_re/jadx2/.../fa/u1.java` (PPPP init, transport decision), `va/h.java` (device_info), `bean/DeviceInfo.java` (license split), `AntsApplication.java` (lib list), `fragment/CameraPlayerFragment.java` (resolution values).
- ELF dynsym/NEEDED/strings of `lib{PPPP_API,IOTCAPIs,AVAPIs,sCHL}.so` from `_apk/extracted/split_config.{arm64_v8a,armeabi_v7a}.apk` (parsed this session; SHA-256 above).

### Secondary (MEDIUM, PUBLIC, MIT)
- https://github.com/Dmxsir/yi-cam-integration — `docs/phase-2c-failure-analysis.md` (-3012, burst), `docs/tnp-golden-trace.md` (official 6.9.7 sequence, v2 header, 31-byte auth), `docs/current-native-parity.md` (lib hash ↔ API `0xA2050401`), `docs/tnp-native-api.md`, `docs/tnp-video-flow.md`, `docs/tutk-flow.md`, `oracle/android/src/com/p2p/pppp_api/PPPP_APIs.java` (minimal shim), `tests/test_yi_tnp_oracle.py`.
- https://github.com/Dmxsir/yi-rtsp — README (vendor lib imported from user's APK; "working end-to-end"), `tools/phase3_pppp_probe/run_phase3e_tnp.py` (flag 0x4B, burst, resolution 1), `yi_live_relay.py` / `yi_tnp_oracle.py` (H.264 78, AAC 138 with ADTS, Annex-B/length-prefixed detection).
- https://palant.info/2025/11/05/an-overview-of-the-pppp-protocol-for-iot-cameras/ — PPPP background (via earlier project research).

### Tertiary (LOW)
- Concurrent viewers per Yi camera: no authoritative source found (web search, 2026-10-12: surveillanceguides.com blog posts only). Device check required.
- CS2 `PPPP_Connect` flag-bit meaning and finite-timeout partial-read semantics: no public CS2 documentation found; inference.
- Mi 9 HEVC hardware decoder name: training knowledge; confirm on device.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — libs, exports, NEEDED, hashes and Java binding read locally.
- Architecture (call order, framing, auth, decrypt, transport rule): HIGH for what the vendor code does (VERIFIED-LOCAL); MEDIUM that our replay works (prior art reached mode 0 with a minimal shim; startup-burst causality is MEDIUM).
- MediaCodec/decoder details: MEDIUM — standard API, not yet device-verified here.
- Pitfalls: MEDIUM-HIGH.

**Research date:** 2026-10-12
**Valid until:** 2026-11-12 (vendor libs pinned by hash; re-check if the user's APK changes to 6.9.9+).
