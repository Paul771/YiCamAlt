# Project Research Summary

**Project:** YiCamAlt
**Domain:** Alternative Android client for Yi Home / Yi Dome / Kami cameras on stock firmware — Yi Cloud HTTP API + vendor P2P transports (TNP/CS2-PPPP and TUTK/Kalay) for live view and SD-card playback, no paid subscription.
**Researched:** 2026-10-10
**Confidence:** MEDIUM (HIGH for facts read directly from the APK binaries; MEDIUM for protocol details from one production-validated prior-art project; LOW for SD-playback payloads and event-media decryption)

## Executive Summary

YiCamAlt is a protocol client for a closed camera ecosystem, built on an Android app shell that already works. Prior art (Dmxsir yi-cam-integration / yi-rtsp, LocalCameraHub, docker-wyze-bridge for the same TUTK family) follows one recipe: fetch the device list and per-camera P2P credentials from the vendor cloud with the vendor's HMAC signature; load the vendor's own native P2P library through JNI shims that keep the exact vendor package names; do framing, light AES I-frame decryption and MediaCodec decoding in your own code. Nobody reimplements the Yi PPPP fork in pure code. Calling the vendor `.so` files extracted from the user's local APK at build time is the established approach, and it fits the public-repo constraint because no vendor binary is ever committed.

Research changes the plan in three ways:
1. **TNP is the default transport, not TUTK.** The transport is chosen per camera (device-list `type`: 0 = TUTK, 1 = Langtao, 2 = TNP). A 2026 production account had 7/7 cameras on TNP; TUTK is the fallback for older models.
2. **The empty camera list probably has a cheap fix.** `/v5/devices/list` returned a 16-byte body — exactly `{"code":"20200"}`, an error envelope. Prior art gets `code 20000` + `data[]` from `/v4/devices/list` with the same signature. One on-device probe comes before any apk-mitm capture; the capture shrinks to confirmation + events-API discovery.
3. **The existing `M-STREAM-LIVE` / `M-STREAM-DECODE` contracts are wrong** (cloud HLS, ExoPlayer, cloud key handshake). They are superseded by a `CameraLink` port with TNP/TUTK adapters, a pure-Kotlin JVM-testable protocol core, and a thin MediaCodec/AudioTrack layer.

Main risks: undocumented SD-playback IOCTRL payloads; event thumbnails/clips encrypted per event (`pic_pwd` / `video_pwd`); secrets in a **public** repo (redaction hardening is a precondition); account/ToS exposure (no payment endpoints, no timed re-logins, no reconnect storms, one P2P session per camera); slow device iteration (USB phone only, no emulator).

## Resolved Researcher Conflicts (binding)

### A: why the camera list is empty
STACK says wrong endpoint (`/v4` vs `/v5`); PITFALLS says shared-vs-owned cameras (`/v5/deviceshare/*`) or another region (gw-eu / gw-sg). Not mutually exclusive — test all cheaply with the existing signer.

**First roadmap task: one on-device probe** (debug screen, in the style of `DeviceSignProbe`) calling `/v4/devices/list`, `/v5/devices/list`, `/v5/deviceshare/friends`, `/v5/deviceshare/shareddetail` on gw-us, gw-eu, gw-sg, then `/v4/tnp/device_info` (`seq=1&userid=<uid>&uid=<cloud uid>`) for the first device, with the app headers from STACK §2a. Logs **only** HTTP status, `code`, body length, `data[]` count and field *names* — never values.

Decision tree:
- `/v4` has data → build the parser on `/v4`; capture becomes events-API discovery only.
- only `deviceshare` has data → cameras are shared; merge both sources, respect live-view-only permission.
- only another region has data → fix region selection (and the bug where `YiCloudAuthApi` is not rebuilt after a base-URL change).
- everything empty → apk-mitm capture is the critical path again.

### B: TNP vs TUTK default
Build TNP first. Choose per camera from `type`, cross-checked with the `p2pid` prefix (`TNP…` with three dash-separated parts → TNP). Write JNI shims for both (cheap); build the TUTK link adapter only if the probe finds a `type 0` camera.

### Smaller contradictions

| Topic | Resolution |
|---|---|
| `libc++_shared.so` | Do not ship — ELF NEEDED lists only system libs |
| `libkami_core.so` | Not needed — phone-as-camera product; viewer path is `libPPPP_API.so` only |
| Certificate pinning | Low risk; apk-mitm also disables OkHttp `CertificatePinner`; confirm on first patched launch |
| Native packaging | `jniLibs.useLegacyPackaging = true` on AGP 8.2.2 |
| UDP pcap of P2P | Optional diagnostic only (PCAPdroid); auth/decrypt tests use vectors from the documented algorithm |
| `/v4/tnp/device_info` | Per-device source of `InitString` and `License` — never hard-code |

## Key Findings

### Recommended Stack
Existing stack unchanged (Kotlin 1.9.22, Compose, Hilt 2.51, Retrofit/OkHttp, Room 2.6.1, validated login + HMAC signing). **No new Gradle dependencies for P2P.** Detail: [STACK.md](STACK.md).
- Vendor `libPPPP_API.so` (arm64-v8a, armeabi-v7a; extracted from local APK at build time) — TNP transport.
- Vendor `libIOTCAPIs.so` + `libAVAPIs.so` + `libsCHL.so` — TUTK fallback (AVAPIs NEEDS sCHL); `IOTC_Initialize2(0)`, no license key.
- Java JNI shims at exact vendor FQCNs (`com.p2p.pppp_api.PPPP_APIs`, `com.tnp.model.st_PPPP_Session`, `com.tutk.IOTC.*`) + R8 keep rules; `libPPPP_API.so` has `JNI_OnLoad` → class must exist at load.
- Async MediaCodec → SurfaceView for H.264 (78) / H.265 (81) Annex-B; AAC via MediaCodec or G.711 table → AudioTrack. Media3 1.11.1 only for HTTP event clips.
- Capture toolchain (no admin): apk-mitm 1.3.0 `--apktool apktool_2.12.1.jar`, `mitmproxy==11.0.2` on Python 3.11, `adb reverse tcp:8080`, `adb install-multiple`. jadx 1.5.6 per dex (classes7 TNP, classes8 AntsCamera*/P2PDevice, classes6 TUTK/PPPP wrappers, classes2 DeviceInfo).

### Expected Features
Detail: [FEATURES.md](FEATURES.md). Official free tier: 6 s cloud clips, 24 h–7 d alert window, free SD playback with "View SD card playback" from an alert, time-limited live preview, ads. Paid: full cloud video, AI detection, fast-forward.

**v1 table stakes:** real camera list (name, model, online, parsed P2P credentials); live view LAN + remote with relay fallback, H.264/H.265, auto-reconnect with visible states; mute (default muted), HD/SD (SD default on relay), snapshot, fullscreen + pinch-zoom; stop stream on leave/background; SD archive (day picker, 24 h timeline, seek, play/pause, back-to-live, empty/error states); cloud alerts feed (thumbnails, camera/day filters, 6 s clip, "play full video from SD" at event time, Room cache).

**Differentiators:** no live-time limit (if client-enforced), no ads/upsell, LAN/P2P/Relay badge, longer event history via local cache.

**Defer:** v1.x — SD event list as second feed, WorkManager polling (≥15 min), clip download, grid, PiP, two-way talk, playback speed. v2+ — PTZ/settings, merged cloud+SD timeline.

**Never:** `/orderpay/*`, `/vas/*/cloud/*`, entitlement/trial endpoints, `/v5/alert/push_prop`, spoofing identity/region, aggressive polling or re-login loops, destructive camera commands in v1.

### Architecture Approach
Detail: [ARCHITECTURE.md](ARCHITECTURE.md). Layered transport port: logic-free Java JNI shim in vendor packages; `p2p/` (only package importing the shim) with `NativeLibs` (load order IOTC → sCHL → AVAPIs, then PPPP_API; `NativeAvailability` probe), `PpppRuntime`/`TutkRuntime` (init once), `TnpCameraLink`/`TutkCameraLink`; pure-Kotlin `stream/` above the `CameraLink` port, tested with `FakeCameraLink`; thin Android-only `media/`. Native reads on dedicated named threads with finite timeouts; cancellation via `abort()`. One session per camera shared by live, audio and SD playback.

Components:
1. `CameraRegistry` + `P2pCredentialsPort` — p2pid, transport, encrypted flag, password, InitString, License device key, model, byte order, wakeup flag; secrets in memory / EncryptedSharedPreferences, never plain Room.
2. `CameraLink` port — `TnpCameraLink` first (`PPPP_Connect` / `PPPP_WakeUp_And_Connect`; ch0 cmd, ch1 audio, ch2/3 live I/P, ch4/5 SD I/P; big-endian framing in pure `TnpCodec`), `TutkCameraLink` fallback.
3. `StreamSessionSupervisor` — `Idle → ResolvingCredentials → P2pConnecting → ChannelStarting → Negotiating → Streaming ↔ Reconnecting → Failed(reason)`; pure error classifier; exponential backoff with jitter (cap 30 s, ~5 attempts); network-change and background-grace handling; `BLOCK_SESSION_TRANSITION` logging.
4. `IoCtrlMux` + `FramePipeline` — response-type matching with timeouts; per-message TNP auth header with auto-detected version; keyframe gating; I-frame AES-ECB decrypt (key = password + "0", two blocks at payload offsets 4 and 20); NAL split. TNP start is a **burst** 4881 → 9029 → 768 back to back (single command + blocking read → remote close `-3012`).
5. `VideoDecoder`/`AudioRenderer` (lifecycle follows Surface; request I-frame on new Surface), `SdPlaybackService` (client of shared session), UI.

### Critical Pitfalls
Full list: [PITFALLS.md](PITFALLS.md).
1. **Endless blind probing** → single probe matrix + decision tree; distinct `NoDevices`/diagnostic UI state.
2. **Secret leakage (public repo + logs)** → extend maskers/scanner for hmac, password, License, InitString, DID, p2pid, nonce; realistic serial fixtures; logs to internal storage; never show serials; treat capture flows as toxic, rotate Yi password after capture; never commit decompiled vendor code; check Dmxsir licence before copying.
3. **Wrong P2P stack or auth scheme** → per-camera selection, TNP first, auth + I-frame decrypt pinned by unit tests before connect code; watch for native `Invalid Nonce` / `Bad password`.
4. **Native bring-up failures** (missing `libsCHL.so`, x86 ABI, wrong FQCN/field names abort natively, non-exported natives, infinite read timeouts) → gate streaming on `PPPP_GetAPIVersion` / `IOTC_Get_Version` sanity on the arm64 phone.
5. **Capture expectations** → mitmproxy never sees video (UDP 32100/32108); splits via `install-multiple` under one key; Flutter (Kami Ring) bypass irrelevant; fallback `apktool d -s` + `b --net-sec-conf` + uber-apk-signer; turn phone proxy off after capture.

Other: viewer-slot limits (`-48`, TNP kick) with the official app open; H.265 without HW decode; account flagging from cadence (alerts ≤ every 5 min foreground, 15 min background).

## Implications for Roadmap

The device list is the single choke point. After it, the pure-Kotlin core and native bring-up overlap, and the events feed runs in parallel with the P2P phases.

1. **Secrets Hygiene + Device-List Resolution** — redaction hardening, probe matrix, `P2pCredentials`/`TransportType` parser + `/v4/tnp/device_info`, `NoDevices` state, path tech-debt (auth API rebuild on base-URL change, single HMAC signer, `CameraDao` log). Exit: user's camera transport known.
2. **Traffic Capture** (confirmation + events discovery) — patched bundle installs/launches, mitmproxy over `adb reverse`, redacted field-name notes, cleanup. Critical only if phase 1 probe is all-empty. Parallel with 3.
3. **Native Bring-up + Pure Protocol Core** — Gradle `.so` extraction with SHA-256 manifest, Java shims, `NativeLibs` probe, packaging; pure `YiP2pAuth`, `TnpCodec`, `FrameInfo`, `IFrameDecryptor`, `NalUtils`, `IoCtrlMessages`, `CameraLink` + fake with golden vectors; `TnpCameraLink` connect + burst; debug screen showing mode + first I-frame on Wi-Fi and cellular; GRACE contract supersession. Exit: raw frames locally and via relay.
4. **Live View** — supervisor, mux, MediaCodec, audio + mute, HD/SD, snapshot, fullscreen/zoom, badge, clean teardown.
5. **SD-Card Archive** — day index, segments, ch4/5 playback with start/seek/stop, 24 h timeline, back-to-live. After 4, not parallel.
6. **Events Feed** — cloud alerts, decrypted thumbnails/clips, filters, Room cache, event → SD jump (nav argument), rate-safe polling. Parallel with 3–5; SD jump lands after 5.
7. **Release Hardening + Device Verification** — signed APK verified on the phone (LAN/remote, H.264/H.265, official app running alongside), redaction sweep, TUTK adapter only if needed.

### Research Flags
- Needs `/gsd:research-phase`: Phase 5 (HIGH — SD payloads undocumented), Phase 6 (media decryption, alert endpoint, retention), Phase 3 (PPPP error table, JNI signatures/struct fields from local jadx + ELF, header-version detection, `PPPP_Initialize` args), Phase 2 only if apk-mitm fails.
- Standard patterns: Phases 1, 4, 7.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM-HIGH | ELF exports/NEEDED/alignment, dex class map, NSC inspected (HIGH); tool versions from registries (HIGH); P2P call sequences from one prior-art source (MEDIUM) |
| Features | MEDIUM | Free/paid from 8,340 APK strings (HIGH); retention and live-limit enforcement unverified |
| Architecture | MEDIUM-HIGH | JNI surface + vendor session flow from decompilation (HIGH); decoder/lifecycle not device-verified |
| Pitfalls | MEDIUM-HIGH | Binary facts and repo state verified; ban mechanics inferred |

**Overall:** MEDIUM — direction clear, remaining unknowns concrete and assigned to phases.

### Gaps
- Endpoint/region/source holding the user's cameras — Phase 1 probe.
- Transport type of the user's cameras — Phase 1 probe.
- Byte order/header version, `p2p_encrypt` (string `"true"`), `hasPincode` path (`/v5/devices/password`) — Phase 1 parser, Phase 3 tests.
- Audio codec 138 ambiguity (AAC vs G.711A) — trust live value, support both.
- SD payloads — Phase 5 research.
- Event media encryption, server-side live limit — Phase 2 capture, Phase 6 research.
- Concurrent sessions with the official app; whether a second login invalidates it — Phase 4 on device.
- Old vendor blobs remain in git/LFS history after `b804e33` — owner chose not to rewrite history.
- Licences of Dmxsir projects — check before reusing code; otherwise derive from local ELF/jadx.

## Sources

### Primary (HIGH)
- Local Yi Home 6.9.8 APK (local-only): ELF dynsym/dynamic/program headers, dex class_defs, NSC XML, `aapt2 dump resources`, DEX string pools, per-dex jadx.
- PyPI / npm / GitHub release metadata: mitmproxy, apk-mitm, Apktool, jadx, androguard, frida, objection, media3.

### Secondary (MEDIUM)
- github.com/Dmxsir/yi-cam-integration, github.com/Dmxsir/yi-rtsp — `/v4` device list, `tnp/device_info`, TNP flow, startup burst.
- palant.info/2025/11/05/an-overview-of-the-pppp-protocol-for-iot-cameras/
- github.com/mrlt8/docker-wyze-bridge (`iotc.py`, `tutk_ioctl_mux.py`, `wyze_stream.py`).

### Tertiary (LOW)
- LocalCameraHub (`yi_p2p_bridge.py`) — 9030 SD playback, I-frame AES offsets.
- Wyze/Yi forum posts; Cisco Talos / arXiv 2306.00610.

---
*Research completed: 2026-10-10*
*Ready for roadmap: yes*
