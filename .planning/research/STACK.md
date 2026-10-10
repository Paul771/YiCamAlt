# Stack Research

**Domain:** Alternative Android client for Yi Home / Kami cameras: cloud API capture plus vendor P2P (TNP/PPPP and TUTK/Kalay) live view and SD playback
**Researched:** 2026-10-10
**Confidence:** MEDIUM-HIGH overall. Facts taken from the local APK are HIGH. Facts from the Dmxsir prior-art docs are MEDIUM: they are one independent source, but it was validated against production in 2026. Decode details are MEDIUM.

> Scope: this covers only what the milestone adds. The existing Kotlin 1.9.22 / Compose / Hilt 2.51 / Retrofit 2.9 / OkHttp 4.12 / Room 2.6.1 stack, login, and HMAC-SHA1 signing are already validated and are not re-researched. See `.planning/codebase/STACK.md`.

---

## 0. Findings that change the plan (read first)

1. **Most Yi cameras today are not TUTK. They use TNP, which is CS2 PPPP (`libPPPP_API.so`).** The app chooses the transport per camera from the device-list field `type`: 0 = TUTK (`AntsCameraTutk`), 1 = Langtao, 2 = TNP (`AntsCameraTnp` -> `com.tnp.TnpCamera`). In a 2026 production account, all 7 cameras were `type 2` (TNP). The client must implement **TNP first**. TUTK is the fallback for old models such as the Y20 (model `6`). (HIGH for the APK structure. MEDIUM for "most cameras", which rests on one 7-camera account plus palant.info's prefix table.)
2. **The camera list is probably `/v4/devices/list`, not `/v5/devices/list`.** The prior art gets HTTP 200 with `code:"20000"` and a `data[]` array from `gw-eu.xiaoyi.com/v4/devices/list` (2026). It uses the same signature this project already has (`seq=1&userid=…`, key `token&token_secret`). Our `/v5` probe returned code `20200` with a 16-byte body. That is exactly the length of `{"code":"20200"}`, so it is an error or empty envelope, not success. P2P credentials then come from `GET /v4/tnp/device_info` (signed `seq=1&userid=<uid>&uid=<cloud uid>`), which returns `DID`, `InitString` and `License`. **Try these two calls before investing in traffic capture.** Capture then becomes a confirmation step instead of the critical path. (MEDIUM: one source, but production-validated.)
3. **Flutter is not on the critical path.** `libapp.so` is the `ring_flutter` module (Kami Ring, `dio` client). The device-list, P2P and event APIs are Java/OkHttp in the dex files. You do not need reFlutter or VPN-mode capture to see the camera API. (HIGH: package list extracted from `libapp.so`.)
4. **No certificate pins for Yi hosts.** `res/xml/network_security_config.xml` has only cleartext domain configs (xiaoyi.com, yitechnology.com, kamihome.com, …) and no `<pin-set>`. The dex files contain no `sha256/` pin strings. OkHttp `CertificatePinner` exists only as library code. Making the app trust user CAs is therefore enough. That is apk-mitm's core function, and even a plain `apktool --net-sec-conf` rebuild would do it. (HIGH for the static evidence. A runtime TrustManager check is still possible: verify on the first capture.)
5. **The native libs are 16 KB-page safe.** All `.so` files have 64 KB LOAD alignment, are stored uncompressed, and use static `Java_*` exports with no `JNI_OnLoad`/`RegisterNatives`. The Java binding classes must therefore live at the exact FQCNs. (HIGH: ELF headers and symbol scan.)

---

## Recommended Stack

### Core technologies (new for this milestone)

| Technology | Version | Purpose | Why recommended | Conf. |
|---|---|---|---|---|
| Vendor `libPPPP_API.so` (arm64-v8a, armeabi-v7a), taken from the user's own APK | from Yi Home 6.9.8 (PPPP API `0xA2050401` in 6.9.7) | TNP/PPPP P2P transport for `type==2` cameras: LAN, P2P hole-punch, relay | The protocol is a Yi fork of CS2 PPPP with a Yi-specific init-string table and an F2 signed header. No open reimplementation handles the Yi fork reliably. `aiopppp` targets generic CS2 cameras. Calling the vendor lib is how the official app, yi-rtsp and LocalCameraHub all do it. | HIGH |
| Vendor `libIOTCAPIs.so` + `libAVAPIs.so` + **`libsCHL.so`** | IOTC 2.16.x (string `2.16.0.0`), same APK | TUTK/Kalay transport for `type==0` cameras | `libAVAPIs.so` has a `NEEDED` entry for `libsCHL.so` (DTLS). Ship all three or `System.loadLibrary("AVAPIs")` fails. | HIGH |
| Java (not Kotlin) JNI binding classes: `com.p2p.pppp_api.PPPP_APIs`, `com.tnp.model.st_PPPP_Session`, `com.tutk.IOTC.IOTCAPIs`, `com.tutk.IOTC.AVAPIs`, `com.tutk.IOTC.St_SInfoEx` (and the other `St_*`/`st_*` structs) | n/a | JNI symbol binding | The exports are named `Java_com_p2p_pppp_1api_PPPP_1APIs_PPPP_1Connect`, `Java_com_tutk_IOTC_AVAPIs_avRecvFrameData2`, and so on. Package, class and method names, plus struct **field names** (native code uses `GetFieldID` on `st_PPPP_Session.bMode`, …), must match byte for byte, and the methods must be `static native`. Use plain Java so they are real static methods and no Kotlin `object` instance receiver is involved. Copy signatures from jadx output (see section 4) or from the Dmxsir oracle `PPPP_APIs.java`. | HIGH |
| Android `MediaCodec` (async mode) + `SurfaceView` | platform API, minSdk 26 | Decoding H.264 (codec id 78) / H.265 (id 81) Annex-B frames from P2P | Frames arrive as discrete access units with a 24-byte frame header that you strip. Raw MediaCodec gives the lowest latency and lets you control I-frame gating and decrypt-before-queue. Media3 has no public raw-ES source (see "What NOT to use"). | MEDIUM-HIGH |
| `MediaCodec` (audio, `audio/mp4a-latm`) + `AudioTrack` | platform | Live audio. Yi TNP audio is **AAC** in the prior art (yi-rtsp asserts "AAC codec id 138"), on PPPP channel 1, AES-decrypted per 16-byte block when TNP header version ≥2 | AAC decodes natively. For G.711 A/µ (TUTK legacy ids 137/138), decode in about 20 lines of Kotlin with a lookup table, no library. G.726 appears only in the app's *encoder* (`libG726Android.so` exports only `g726_encode`, so it is talk-back only). | MEDIUM |
| `javax.crypto` (JDK) | platform | `AES/ECB/PKCS5Padding` camera-password recovery, `AES/ECB/NoPadding` I-frame and audio block decrypt, `HmacSHA1` for nonce auth | Everything Yi needs is in the JCA. No BouncyCastle or Tink needed. | HIGH |

### Traffic-capture toolchain (PC: Windows 10, no admin; phone: physical, not rooted)

| Tool | Version | Purpose | Why | Conf. |
|---|---|---|---|---|
| **apk-mitm** | 1.3.0 (npm, 2024-04-21, the latest) | Patch the base APK: user-CA trust via NSC, Java-layer unpinning, re-sign **all** splits | Handles `.xapk`/`.apks` natively: it unzips, patches `base.apk`, signs every `*.apk` with uber-apk-signer under one key, and re-zips. `.apkm` is not accepted by extension, but APKMirror's `.apkm` here is a plain zip with `base.apk` at the root, so a rename to `.apks` works. | HIGH (source read) |
| **Apktool** jar passed through `--apktool` | **2.12.1** | Decode and re-encode inside apk-mitm | apk-mitm bundles Apktool **2.9.3** (2023), which predates Android 15/16 resource changes. Use 2.12.1 (aapt2 from Android 16 sources). Do **not** use 3.0.x with apk-mitm: Apktool 3 removed aapt1 and changed CLI flags, and apk-mitm passes v2-style flags (`--frame-path`, `--use-aapt2`). | MEDIUM |
| uber-apk-signer | 1.3.0 (bundled by apk-mitm) | Signing all splits under one debug key | Splits with different signatures fail `install-multiple`. | HIGH |
| **mitmproxy** | **11.0.2** on Python 3.11, or **12.2.3** on Python ≥3.12 | HTTPS interception and flow dump | 11.0.2 is the **last** release that supports Python 3.10–3.11. 11.1.0+ and 12.x require Python ≥3.12. PROJECT.md says "Python 3.11 for mitmproxy 11", so pin `mitmproxy==11.0.2`. This PC also has Python 3.12, where 12.2.3 works. | HIGH (PyPI metadata) |
| **adb reverse** (platform-tools) | current | Phone reaches the PC proxy over USB, so no Windows firewall rule or admin is needed | `adb reverse tcp:8080 tcp:8080`, then set the phone's proxy to `127.0.0.1:8080`. This avoids the inbound-firewall prompt that a non-admin user cannot approve. | HIGH |
| jadx | **1.5.6** (2026-07-10) | Decompiling targeted classes | Reads `.apkm`/`.xapk`/`.dex` directly. Use the per-dex and single-class modes from section 4. | HIGH |
| androguard | 4.1.4 | Scripted xref/string queries without a full decompile | Optional. Python 3.9+. | MEDIUM |

### Supporting libraries (Android app)

| Library | Version | Purpose | When |
|---|---|---|---|
| (none new for transport) | — | — | P2P, crypto and decode all use the platform or vendor `.so` files |
| `androidx.media3:media3-exoplayer` | 1.11.1 (latest on Google Maven) | Only for **cloud/HLS** event-clip playback, if the events API returns HLS/MP4 URLs | Not for live P2P. The existing `docs/technology.xml` lists `com.google.android.exoplayer:2.39+`, which is the deprecated artifact; replace it with media3 if needed. |
| kotlinx-coroutines | 1.7.3 (existing) | Reader loops on `Dispatchers.IO`: one per PPPP channel (0 cmd, 1 audio, 2/3 live I/P, 4/5 playback I/P) | Always |

### Development tools

| Tool | Purpose | Notes |
|---|---|---|
| Python 3.11 `-I` scripts in `tools/` | Offline cloud probe (`/v4/devices/list`, `/v4/tnp/device_info`) before writing Kotlin | Reuse the HMAC code from the app. Never print DID, InitString, License, or the decrypted password (GRACE redaction rule) |
| Wireshark + `adb shell` not needed | — | PPPP is UDP 32100 (server) and 32108 (LAN search). Capture on the phone requires root or a VPN app, which is out of scope. Use native return codes and log anchors instead |

---

## 1. Traffic capture: exact procedure

**Purpose:** confirm the `/v4/devices/list`, `/v4/tnp/device_info` and `/v5/alert/events` request and response shapes, plus the SD/event endpoints. Because of finding 2, start with direct probing and use capture to confirm and to discover the events API.

```powershell
# 0) Tools (user-level, no admin)
npm install -g apk-mitm@1.3.0
# Apktool 2.12.1 jar -> E:\Dev\YiCamAlt\_tools\apktool_2.12.1.jar
#   https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar
py -3.11 -m venv E:\Dev\YiCamAlt\.venv-mitm
E:\Dev\YiCamAlt\.venv-mitm\Scripts\python -m pip install "mitmproxy==11.0.2"
# (or on Python 3.12: pip install mitmproxy==12.2.3)

# 1) Patch the bundle. .apkm is a plain zip with base.apk at root, so rename to .apks
Copy-Item _apk\*.apkm _apk\yihome-6.9.8.apks
apk-mitm _apk\yihome-6.9.8.apks --apktool _tools\apktool_2.12.1.jar
#   -> _apk\yihome-6.9.8-patched.apks  (zip of re-signed base + all splits)

# 2) Install: unzip, then install only the splits this phone needs
Expand-Archive _apk\yihome-6.9.8-patched.apks _apk\patched -Force
adb uninstall com.ants360.yicamera.international      # signature differs; login state is lost
adb install-multiple _apk\patched\base.apk `
    _apk\patched\split_config.arm64_v8a.apk `
    _apk\patched\split_config.xxhdpi.apk `            # pick: adb shell wm density -> 480=xxhdpi, 420/440~xxhdpi, 320=xhdpi
    _apk\patched\split_config.en.apk _apk\patched\split_config.ru.apk
#   The manifest declares requiredSplitTypes, so ABI + density splits are mandatory.
#   Passing every split also works.

# 3) mitmproxy CA as a USER CA on the phone (no root)
.\.venv-mitm\Scripts\mitmdump --version   # first run creates %USERPROFILE%\.mitmproxy\
adb push $env:USERPROFILE\.mitmproxy\mitmproxy-ca-cert.cer /sdcard/Download/
#   Phone: Settings > Security > Encryption & credentials > Install a certificate > CA certificate

# 4) Route over USB, no firewall/admin needed
adb reverse tcp:8080 tcp:8080
adb shell settings put global http_proxy 127.0.0.1:8080
.\.venv-mitm\Scripts\mitmdump -p 8080 -w captures\yihome-$(Get-Date -f yyyyMMdd-HHmm).flows `
    --set flow_detail=2 "~d xiaoyi.com | ~d yitechnology.com | ~d kamihome.com"
#   (use mitmweb for the UI)

# 5) ALWAYS clean up (otherwise the phone has no internet after unplugging)
adb shell settings put global http_proxy :0
adb reverse --remove-all
```

**Fallback if apk-mitm's smali rebuild fails** (10 dex files; Apktool is "known to fail on large apps" per the apk-mitm README): Yi has no pins, so skip smali entirely.

```powershell
java -jar _tools\apktool_2.12.1.jar d -s -f -o _work\base _apk\extracted\base.apk     # -s = no smali, dex copied as-is
java -jar _tools\apktool_2.12.1.jar b --net-sec-conf -o _work\base-nsc.apk _work\base     # injects permissive NSC (user CAs)
java -jar uber-apk-signer-1.3.0.jar --allowResign -a _work\base-nsc.apk -a _apk\extracted\split_config.arm64_v8a.apk ... -o _work\signed
```

All splits must be signed with the same key. uber-apk-signer accepts multiple `-a`.

**If the patched app refuses to work** (detection of re-signing via `getPackageInfo` signatures, or Play Integrity in `classes3.dex`): fall back to `objection patchapk` (frida-gadget 17.x, objection 1.12.5). It injects the gadget into `base.apk` and then runs `android sslpinning disable`. Treat this only as a fallback (LOW confidence it is needed).

---

## 2. P2P: what to call, with exact facts

### 2a. Credential pipeline (cloud → P2P)

| Value | Source | Transform | Conf. |
|---|---|---|---|
| transport | `/v4/devices/list` `data[].type` | 0 TUTK, 1 Langtao (ignore), 2 TNP | MEDIUM-HIGH |
| camera password | `data[].password` (hex) | `AES/ECB/PKCS5Padding`, key = `uid.substring(0,16)` bytes; empty → literal `"888888"`; `hasPincode==true` → `GET /v5/devices/password` with `pincode = Base64(HMAC-SHA1(key=uid, msg=PIN))` | MEDIUM-HIGH |
| `p2p_encrypt` | `data[].ipcParam.p2p_encrypt` (production: **string** `"true"`, and `ipcParam` is already an object, not a JSON string) | strict bool parse | MEDIUM |
| TNP DID / InitString / device key | `GET /v4/tnp/device_info?seq=1&userid=…&uid=…&hmac=…` → `data.DID`, `data.InitString`, `data.License` | device key = `License.split(':')[0]` | MEDIUM |
| `wakeup` | `ipcParam.wakeup` | selects `PPPP_WakeUp_And_Connect` | MEDIUM |
| production `code` | top-level `"20000"` as a **string**, `Content-Type: text/html` | normalize. The existing `JsonElement` envelope already tolerates this | MEDIUM |

Headers the app sends: `x-kamihome-appType: ANDROID`, `x-kamihome-packageType: RELEASE`, `x-xiaoyi-appCountryCode`, `x-xiaoyi-appVersion: android;<code>;<name>`, `User-Agent: yihome/<name> (...)`.

### 2b. TNP (type 2): the primary path

- Init: `PPPP_Initialize(new byte[]{0}, 12)` once per process.
- Connect: `PPPP_Connect(did, (byte)0x4B /*0x5F = force relay*/, 0, initString, deviceKey)` returns a handle ≥0. Then call `PPPP_Check(handle, st_PPPP_Session)`; `bMode` gives P2P/relay/LAN.
- Framing is all **big-endian**: `TNPHead` (8 B: ver, ioType 1=video/2=audio/3=cmd, 2 reserved, u32 size) + `TNPIOCtrlHead` (40 B: u16 cmd, u16 seq, u16 extSize=0, u16 payloadSize, 32 B authInfo) + payload, written to **channel 0**.
- authInfo when `p2p_encrypt`: `nonce = prefix7 + rand8` (prefix fixed per connection), `derived = Base64(HMAC-SHA1(key=password, "user=xiaoyiuser&nonce="+nonce))[:15]`, `authInfo = nonce + "," + derived`, zero-padded to 32. Otherwise `"admin,"+password`. This matches the `xiaoyiuser`/`nonce` strings in our 6.9.8 dex.
- **Startup must be a burst.** The working sequence writes `4881` (set resolution, 8 B `>II res,useCount-1`), `9029` (START_REALTIME, 4 B `[useCount,res,1,0]`) and `768` (start audio, 8 zero bytes) back to back, then reads. A single `4881` followed by a blocking read made the camera close the session (`-3012`). The 6.9.7 client sends `9031` (event list) first. The header version starts at 2 and is re-detected from the first response.
- Receive: ch2 = live I-frames, ch3 = live P-frames, ch4/5 = **SD playback** I/P, ch1 = audio. Each unit is 8 B TNPHead, then a 24 B `TNPFrameHead` (codec u16: 78 = H.264, 81 = H.265; flags bit0 = I; seq u16; w/h; ts), then the payload.
- I-frame decrypt (when `p2p_encrypt`): key = `(password + "0")` bytes. Decrypt payload bytes `[4,20)` and `[20,36)` as two independent `AES/ECB/NoPadding` blocks. P-frames are clear.
- SD card: `IOTYPE_USER_IPCAM_TNP_EVENT_LIST_REQ/RESP`, `SMsgAVIoctrlTnpPlayRecord`, `getTnpSdEventDays`, `IOTYPE_USER_IPCAM_TNP_START_RECORD` (the strings are in our dex). LocalCameraHub uses **9030 (0x2346)** for SD clip streaming. The exact payloads are **not documented anywhere**, so decompile `classes7.dex` and `classes8.dex` (LOW, needs phase research).

### 2c. TUTK (type 0): the fallback path

`IOTC_Initialize2(0)`, `avInitialize(32)`, then `IOTC_Get_SessionID()`, `IOTC_Connect_ByUID_Parallel(uid, sid)`, `IOTC_Session_Check_Ex`, then `avClientStart2(sid, user, pass, 15, int[1], 0, int[1])` with `user = nonce15`, `pass = Base64(HMAC-SHA1(password, "user=xiaoyiuser&nonce="+nonce))[:15]` when encrypted (`"admin"`/password otherwise). Start live with IOCTRL **8190** (START2, encrypted) or 511, payload = little-endian u32 useCount. Receive with `avRecvFrameData2(avIdx, byte[300000], …, byte[24] frameInfo, …)`. Same I-frame AES rule. SD playback uses `RECORD_PLAYCONTROL2` **12718** (encrypted) or 794. TUTK integers are **little-endian**. No master-server or authkey is supplied by the app (internal to the lib). (MEDIUM-HIGH: Dmxsir `tutk-flow.md`, consistent with our symbol table.)

### 2d. Packaging the native libs

```kotlin
// app/build.gradle.kts
android {
  defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
  packaging { jniLibs { useLegacyPackaging = true } }   // AGP 8.2.2 does not 16KB-align uncompressed .so in the zip; extracting avoids that. Alternative: AGP >= 8.5.1
}
// R8 (app/proguard-rules.pro):
// -keep class com.tutk.IOTC.** { *; }
// -keep class com.p2p.pppp_api.** { *; }
// -keep class com.tnp.model.** { *; }
```

Do **not** commit the vendor `.so` files. Use a Gradle `Copy` task, or `tools/extract_native.py`, that pulls `lib/<abi>/{libPPPP_API,libIOTCAPIs,libAVAPIs,libsCHL}.so` from the git-ignored `_apk/extracted/split_config.<abi>.apk` into a git-ignored `app/src/main/jniLibs/`. The yi-rtsp precedent does the same: "imports the library from the user's own APK", MIT-licensed wrapper, proprietary lib never redistributed. Declare the callback stubs that the oracle declares (`onIncomingConnectionCallback()`, `onR2PMPCallback(int)` on `PPPP_APIs`), because native code may look them up.

---

## 3. Decode on Android

- **Video:** `MediaCodec.createDecoderByType("video/avc" | "video/hevc")`, configured with the `TNPFrameHead` w/h and **no csd** at first. Queue nothing until the first I-frame. If the I-frame is Annex-B with in-band SPS/PPS (00 00 00 01), MediaCodec accepts it directly. LocalCameraHub feeds `raw_frame[24:]` to `ffmpeg -f h264`, which is Annex-B evidence (MEDIUM). Decrypt before `queueInputBuffer`. Use `presentationTimeUs = timestamp_ms*1000` and render with `releaseOutputBuffer(idx, true)` (render immediately, no A/V sync; live latency beats lip-sync). Keep a small reorder buffer by `seq`, capped at 100 like the official app. On a P-frame gap, drop frames until the next I-frame.
- **H.265:** only if the codec is 81. Check `MediaCodecList` for `video/hevc`. The bundled `libh265decoder.so` (`com.decoder.xiaomi.AntsDecoder`) is the app's software fallback; avoid it unless a target phone lacks a HEVC decoder.
- **Audio:** AAC uses `audio/mp4a-latm` and needs csd-0 (AudioSpecificConfig). If frames are ADTS, parse the 7-byte header to build csd-0 and strip it. G.711 is a table decode into `AudioTrack` (8 kHz mono, ENCODING_PCM_16BIT). The exact sample rate and codec id need a live check (yi-rtsp asserts AAC id 138; in the classic TUTK enum, 138 is G.711A, so trust the live value).
- **Media3:** do not use it for the live P2P path. It would need a custom `Extractor`/`DataSource` and adds buffering latency.

---

## 4. Decompiling the 10-dex APK (jadx hangs on the whole APK)

Class-to-dex map, computed from the 6.9.8 `class_defs`:

| dex | key classes |
|---|---|
| `classes2.dex` | `com.ants360.yicamera.bean.DeviceInfo` (device-list parser target) |
| `classes6.dex` | `com.tutk.IOTC.{IOTCAPIs, AVAPIs, TutkCamera, AVFrame, AVIOCTRLDEFs}`, `com.p2p.pppp_api.PPPP_APIs` |
| `classes7.dex` | `com.tnp.TnpCamera`, `com.tnp.model.*` (38 classes) |
| `classes8.dex` | `com.xiaoyi.camera.sdk.{AntsCameraTnp, AntsCameraTutk, AntsCameraManage, P2PDevice}`, `com.xiaoyi.camera.util.AESIPC` |

```powershell
# jadx 1.5.6 zip -> _tools\jadx ; call java directly (bin\jadx.bat may be blocked by AppLocker)
$J = "java -Xmx6g -cp `"_tools\jadx\lib\*`" jadx.cli.JadxCLI"
Expand-Archive _apk\extracted\base.apk _work\dex -Force   # or python zipfile
# Per-dex, sources only (no resources), 4 threads, keep going on bad methods:
& java -Xmx6g -cp "_tools\jadx\lib\*" jadx.cli.JadxCLI -r -j 4 --show-bad-code -d _work\jadx\c7 _work\dex\classes7.dex
& java -Xmx6g -cp "_tools\jadx\lib\*" jadx.cli.JadxCLI -r -j 4 --show-bad-code -d _work\jadx\c8 _work\dex\classes8.dex
& java -Xmx6g -cp "_tools\jadx\lib\*" jadx.cli.JadxCLI -r -j 4 --show-bad-code -d _work\jadx\c6 _work\dex\classes6.dex
# Single class with full-APK type resolution (loads all dex but decompiles one class):
& java -Xmx8g -cp "_tools\jadx\lib\*" jadx.cli.JadxCLI -r --single-class com.tnp.TnpCamera --single-class-output _work\jadx\TnpCamera.java _apk\extracted\base.apk
# Cross-dex references are unresolved in per-dex mode (shown as raw types); that is fine for protocol reading.
```

Grep-first alternative (no decompile; finds the obfuscated cloud-API classes such as `com.ants360.yicamera.e.a.d`):

```powershell
java -jar _tools\apktool_2.12.1.jar d -r -f -o _work\smali _apk\extracted\base.apk    # -r: no resources -> fast
rg -l "tnp/device_info|devices/list|alert/events" _work\smali
rg -n "const/16 v\d+, 0x2346|0x2345|0x234[0-9a-f]" _work\smali\smali_classes7 _work\smali\smali_classes8   # TNP IOCTRL ids
```

androguard 4.1.4 for scripted xrefs: `androguard analyze _apk/extracted/base.apk` (IPython session; `dx.find_strings("tnp/device_info")`, then `.get_xref_from()`).

---

## Alternatives Considered

| Recommended | Alternative | When the alternative fits |
|---|---|---|
| Direct cloud probe of `/v4/devices/list` + `/v4/tnp/device_info` first | Full MITM capture first | Only if the v4 calls fail (e.g. a region needs different params), or to discover the events/SD endpoints |
| apk-mitm 1.3.0 + Apktool 2.12.1 | HTTP Toolkit (Android app v1.7.0, VPN-mode) | Same need for user-CA trust on non-root. Its benefit is capturing proxy-unaware traffic (Flutter `dio`), which we do not need. Its ADB setup does work without admin. |
| apk-mitm | `apktool d -s` + `b --net-sec-conf` + uber-apk-signer | When apk-mitm's smali stage crashes on 10 dex files. Valid here because there are no pins |
| apk-mitm | objection 1.12.5 `patchapk` (frida-gadget 17.x) + `android sslpinning disable` | When there is runtime pinning or a signature check that apk-mitm does not cover |
| mitmproxy regular mode + `adb reverse` | mitmproxy `--mode wireguard` | Needs inbound UDP 51820 through the Windows firewall, which needs admin, and `adb reverse` cannot tunnel UDP. Only on a machine with admin |
| reFlutter 0.9.8 (patch `split_config.arm64_v8a.apk`) | — | Only if the Kami Ring/Flutter screens' traffic is ever needed. Not for this milestone |
| Vendor `libPPPP_API.so` via JNI | Pure-Kotlin PPPP (porting aiopppp / palant.info analysis) | Long-term independence from the vendor lib. Today the Yi fork (F2 header, different init-string table, HMAC key of unknown origin) is not documented well enough |
| Raw MediaCodec | Media3 1.11.1 custom Extractor | If you later need ExoPlayer UI/seek for SD playback. SD playback over P2P is still frame-push, so raw MediaCodec stays simpler |

## What NOT to Use

| Avoid | Why | Use instead |
|---|---|---|
| `/v5/devices/list` as the camera source | Returns code `20200` with no data (16-byte body) | `/v4/devices/list` (code `"20000"`, `data[]`) |
| Assuming every camera is TUTK | 2026 production accounts are TNP (`type 2`). The TUTK path would never connect | Branch on `type`; implement TNP first |
| mitmproxy ≥11.1 on Python 3.11 | Fails to install (`requires_python >=3.12`) | `mitmproxy==11.0.2` on 3.11, or 12.2.3 on 3.12 |
| apk-mitm's bundled Apktool 2.9.3 | Old resource decoder for a 2026 build that targets SDK 34+ | `--apktool apktool_2.12.1.jar` |
| Apktool 3.0.x with apk-mitm | Breaking CLI changes (aapt1 removed, short flags removed); apk-mitm passes v2 flags | 2.12.1 (3.0.3 is fine for standalone `d -r`/`d -s`) |
| Kotlin `object` + `external fun` for the JNI shims | Not truly static, and name or field-layout drift breaks `GetFieldID` | Java classes with exact FQCN, field names and `static native` |
| Shipping only `libAVAPIs.so` + `libIOTCAPIs.so` | `libAVAPIs.so` NEEDs `libsCHL.so` | Ship all three |
| Running the libs on x86/emulator | ARM-only ELF; no emulator on this PC anyway | arm64-v8a phone over USB |
| `com.google.android.exoplayer:*` (listed in `docs/technology.xml`) | Deprecated, superseded by Media3 | Raw MediaCodec (live), `androidx.media3` 1.11.1 (HTTP clips only) |
| `libh265decoder.so` / `libijkplayer` from the APK | Extra proprietary surface, unknown JNI contracts | Platform MediaCodec |
| A Wi-Fi proxy left set after capture | The phone loses internet when the USB is unplugged | `adb shell settings put global http_proxy :0` |

## Stack Patterns by Variant

**If the camera is `type==2` (TNP):** PPPP_APIs JNI, big-endian TNP framing, channel-per-stream readers, startup burst 4881+9029+768.
**If the camera is `type==0` (TUTK):** IOTCAPIs/AVAPIs JNI, little-endian IOCTRL, `avRecvFrameData2` loop, START2 = 8190.
**If `p2p_encrypt` is false:** auth is `admin,<password>` and frames are clear.
**If `ipcParam.wakeup` is true (battery cams):** `PPPP_WakeUp_And_Connect` instead of `PPPP_Connect`.

## Version Compatibility

| A | Works with | Notes |
|---|---|---|
| mitmproxy 11.0.2 | Python 3.10–3.11 | Last 3.11-compatible release (2024-12-05) |
| mitmproxy 12.2.3 | Python ≥3.12 | 2026-05-12 |
| apk-mitm 1.3.0 | Node ≥14 (22.22.2 present), Java ≥8 (JDK 17 present) | Windows supported (no chmod step) |
| Apktool 2.12.1 | Java 8+ | Accepts `--use-aapt2`/`--frame-path` as apk-mitm passes them |
| jadx 1.5.6 | Java 11+ | `-Xmx6g`–`8g` for single-class on the full APK |
| Vendor .so (64 KB-aligned) | 4 KB and 16 KB page devices | Zip alignment is the remaining risk; use `useLegacyPackaging=true` on AGP 8.2.2 |
| AGP 8.2.2 / compileSdk 34 | MediaCodec/AudioTrack APIs at minSdk 26 | No new Gradle deps required for P2P |

## Sources

- Local APK `_apk/extracted` (Yi Home 6.9.8): ELF `Java_*` exports, NEEDED, LOAD alignment; dex `class_defs` map; NSC XML; `libapp.so` package list. **HIGH**
- https://github.com/Dmxsir/yi-cam-integration (`docs/device-list.md`, `tnp-cloud-info.md`, `tnp-native-api.md`, `tnp-protocol.md`, `tnp-video-flow.md`, `tutk-flow.md`, `phase-2c-report.md`, `phase-2c-failure-analysis.md`, `current-native-parity.md`, `oracle/android/.../PPPP_APIs.java`, pushed 2026-09-06). **MEDIUM** (one source, production-validated)
- https://github.com/Dmxsir/yi-rtsp (`run_phase3e_tnp.py` startup burst and auth builder; `yi_native_av_relay_stable.py` H.264 78 / AAC 138; pushed 2026-09-07). **MEDIUM**
- https://github.com/christophermonterocortes-cr/LocalCameraHub (`yi_p2p_bridge.py`: PPPP ctypes, I-frame AES at `[28:44]`/`[44:60]` of the TNP unit, 9030 SD playback). **MEDIUM-LOW**
- https://palant.info/2025/11/05/an-overview-of-the-pppp-protocol-for-iot-cameras/ (Yi fork, TNP prefixes, app-level AES, ports 32100/32108). **MEDIUM**
- https://github.com/niklashigi/apk-mitm README and `src/patch-app-bundle.ts`, `src/tools/apktool.ts`; npm registry times (1.3.0 on 2024-04-21). **HIGH**
- PyPI JSON for mitmproxy (requires_python per release), frida 17.23.3, objection 1.12.5, androguard 4.1.4, reflutter 0.9.8. **HIGH**
- GitHub releases API: jadx v1.5.6, Apktool v3.0.3 / v2.12.1; https://apktool.org/blog/apktool-3.0.0 (breaking changes). **HIGH**
- https://raw.githubusercontent.com/skylot/jadx/master/README.md (CLI flags `-r`, `-j`, `--single-class`, `--single-class-output`, `.apkm` input). **HIGH**
- https://github.com/mrlt8/docker-wyze-bridge `app/wyzecam/tutk/tutk.py` (ctypes TUTK reference; Wyze-specific auth, not applicable to Yi). **MEDIUM**
- Google Maven metadata: `androidx.media3:media3-exoplayer` 1.11.1. **HIGH**
- Media3-vs-MediaCodec reasoning: training knowledge; WebSearch found no authoritative raw-ES source. **MEDIUM**

---
*Stack research for: Yi Home / Kami alternative Android client (P2P milestone)*
*Researched: 2026-10-10*
