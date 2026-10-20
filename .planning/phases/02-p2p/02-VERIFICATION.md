---
phase: 02-p2p
verified: 2026-10-20T17:23:21Z
status: human_needed
score: 3/5 roadmap success criteria fully verified (criteria 2, 3, 4 partial pending the device checkpoint of plan 02-12)
interim: true
interim_reason: "Plan 02-12 Task 2 (on-device checkpoint) is incomplete: the TNP camera has never streamed on a real camera"
re_verification: false
human_verification:
  - test: "H1 - TNP camera on home Wi-Fi (criterion 2 / LIVE-01). Install the HEAD debug APK over the current app (do not clear data), close the official Yi Home app, phone on the same Wi-Fi as the cameras. Camera list -> tap the TNP camera -> 'Подключить' with 'Только relay' OFF. Wait up to 20 s."
    expected: "Picture moves. Status line: transport=TNP state=STREAMING lan=yes, mode=P2P (or LAN), codec=H264 or H265, decoder=<name> hw=yes, fps > 0, frames increasing, keyFrames >= 1, decryptOk=yes, auth=ok, error=-. Record the codec value."
    why_human: "TnpCameraLink (PPPP connect, 4881/9029/768 burst, BE frame head) is only proven against FakePpppNative on the JVM; a real TNP camera has never been connected."
  - test: "H2 - TNP camera over mobile data (criterion 3 / LIVE-02). On a phone with working 4G, Wi-Fi OFF, mobile data ON. Open the TNP camera -> 'Подключить' (relay OFF). If no picture within 20 s: 'Стоп', tick 'Только relay', 'Подключить' once more (one attempt only)."
    expected: "Picture moves; status line shows transport=TNP lan=no and mode=P2P or RELAY / TCP_RELAY / SDEV_RELAY; auth=ok; decryptOk=yes."
    why_human: "Needs a real camera, a real cellular path and the vendor P2P servers."
  - test: "H3 - Relay-only path on the TNP camera (criterion 3 'P2P или relay'). On Wi-Fi or 4G: tick 'Только relay' -> 'Подключить'."
    expected: "relayForced=yes and mode=RELAY (or TCP_RELAY / SDEV_RELAY), picture moves. If the camera/vendor refuses relay, record the error name verbatim (not a LIVE-02 failure if H2 passed over P2P). For the TUTK camera the toggle must be shown disabled (relayAvailable=false)."
    why_human: "Relay mode (flag 0xDF) never exercised on a device; the TUTK toggle change (c1ec44b) is post-device."
  - test: "H4 - H.265 (criterion 4 / LIVE-03). Read the codec= value from H1 for the TNP camera."
    expected: "If codec=H265: picture moves like H.264 and decoder is the HEVC one (c2.qti.hevc.decoder / OMX.qcom.video.decoder.hevc, hw=yes) -> PASS. If both cameras are H264: record verbatim 'H.265 path not exercised on device; covered by JVM tests; HW HEVC decoder present' (allowed by plan 02-12 truth 3)."
    why_human: "No camera has produced an H.265 stream on the device yet."
  - test: "H5 - Regression of the three post-device commits on the TUTK camera (a36b18b TUTK teardown bound, 9548600 hold frames during codec creation, c1ec44b relay toggle). On Wi-Fi: TUTK camera -> 'Подключить' -> picture moves -> 'Стоп' -> 'Подключить' again -> back out of the screen -> reopen and connect."
    expected: "Video every time, Stop returns within ~2 s, reconnect not blocked, no BLOCK_DECODER_RESET reason=queue-overflow right after decoder creation in the log, relay checkbox disabled for TUTK."
    why_human: "These changes were made after the last on-device run (24ca07e) and are covered only by JVM tests."
  - test: "H6 - Hand-over with the official Yi Home app (plan 02-12 step C, observation). Try split screen; if impossible: connect camera in YiCamAlt -> back; open the camera in Yi Home -> video?; return to YiCamAlt -> connect again. Do this for both cameras."
    expected: "Official app shows video after our session closed; our app reconnects without 'Камера занята' / 'Сессию закрыла камера'. A BUSY/KICKED here is recorded as a Phase 3 gap, not a LIVE-01..03 failure."
    why_human: "Parallel use with the vendor app cannot be simulated; FakePpppNative/FakeTutkNative only script it."
  - test: "H7 - Log pull and redaction sweep for the live sessions (criterion 5 / SEC-01). With USB attached after H1-H6: pull files/diag_log.txt(.1) via run-as into build/device-logs, run RealLogScanTest with -PredactionLogFile=... --rerun-tasks, delete the pulled log."
    expected: "TEST-com.yicamalt.test.RealLogScanTest.xml: tests=1 skipped=0 failures=0. Also confirm BLOCK_CONNECT / BLOCK_CHECK_SESSION / BLOCK_START_BURST markers for the TNP camera (value-free)."
    why_human: "The only sweep on record is from device check 1 (no P2P session); live-session logs were never swept."
---

# Phase 2: Нативный P2P и первый кадр — Verification Report (INTERIM)

**Phase Goal:** Пользователь открывает камеру и видит живое видео (H.264/H.265) через вендорский TNP-транспорт — и в своей Wi-Fi сети, и через мобильный интернет
**Verified:** 2026-10-20T17:23:21Z, branch `gsd/phase-2-p2p`, HEAD `c1ec44b`
**Status:** human_needed (interim; plan 02-12 checkpoint open)
**Re-verification:** No — initial verification

## Summary

The code for the whole phase exists, is substantive and is wired end to end: camera list row -> `LiveProbeScreen` -> `LiveProbeViewModel` -> `CameraLinkFactory` (Hilt-bound to `TransportLinkFactory`, dispatching TNP -> `TnpLinkFactory`/`TnpCameraLink`, TUTK -> `TutkLinkFactory`/`TutkCameraLink`) -> `FramePipeline` (decrypt, NAL, assembler) -> `VideoDecoder` (MediaCodec -> SurfaceView). JVM suite at HEAD: 41 suites, 526 tests, 0 failures, 1 skipped (RealLogScanTest without a log file).

On a real device the **TUTK** camera is proven on LAN and over P2P/4G (H.264, hw decoder, decryptOk=yes, auth=ok). The **TNP** transport — which the goal names explicitly — has never streamed from a real camera. Relay mode, H.265 decode and parallel use with the official app are proven only on JVM fakes. No code gap was found; what remains is device evidence.

## Goal Achievement — Roadmap Success Criteria

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | .so extracted from the local APK with a SHA-256 manifest; `PPPP_GetAPIVersion` passes on arm64; no vendor binary in Git | VERIFIED | `app/build.gradle.kts` extract/verify tasks (`vendor-natives.sha256` referenced 3x, jniLibs srcDir `generated/vendorJniLibs`); manifest holds the pinned hash; `git ls-files` has no .so/.apk; `.gitignore` blocks `*.so`, `*.apk`. Device check 1: `pppp=ok init=0 api=0xA2050401`, `tutk=ok`, APK holds exactly the 4 libs per ABI, R8 keeps the shim. |
| 2 | Same Wi-Fi: user opens the camera and sees moving video | PARTIAL (human_needed) | TUTK camera: PASS on device (mode=LAN lan=true, OMX.qcom avc hw, first render 31-67 ms, user confirmed motion). TNP camera: never run (H1). |
| 3 | Mobile data: video via P2P or relay, mode visible | PARTIAL (human_needed) | TUTK camera: PASS twice (mode=P2P lan=no; once over 4G on a second phone, fps 20.2, decryptOk=yes). TNP camera not run (H2); relay mode not exercised on any camera (H3). Mode shown in the status line (code and device). |
| 4 | H.265 plays like H.264 or explicit "no decoder" | PARTIAL (human_needed) | H.264 hw decode PASS on device. H.265 path in code: codec 81 -> H265 (`FramePipeline`), VPS/SPS/PPS csd (`NalUtils`, `DecoderPolicy.CsdBuilder`), `DecoderStatus.NoDecoder(mime)` (`VideoDecoder.kt:301`); HW HEVC decoder present on the phone. Never fed a real H.265 stream (H4). |
| 5 | Pure Kotlin core JVM-tested on reference vectors; M-STREAM-LIVE / M-STREAM-DECODE superseded in GRACE | VERIFIED (sweep part pending, H7) | 526 tests / 0 failures at HEAD (results newer than HEAD commit, task UP-TO-DATE). `M-STREAM-LIVE` and `M-STREAM-DECODE` carry `STATUS="superseded"` in development-plan.xml and knowledge-graph.xml; M-P2P-NATIVE, M-TNP-LINK, M-STREAM-CORE, M-MEDIA-RENDER, M-UI-LIVE-PROBE, M-TUTK-LINK are `implemented`; V-M-* entries exist in verification-plan.xml and reference 02-DEVICE-VERIFICATION. Live-session redaction sweep not done (02-12 truth 5). |

**Score:** 2/5 criteria fully verified, criterion 5 verified except the live-log sweep, criteria 2-4 partial.

## Plan must_haves (truths) — summary

| Plan | Truths | Status | Notes |
|------|--------|--------|-------|
| 02-00 ports, fake, scanner, GRACE | 5 | VERIFIED | `CameraLink.kt` `fun open(credentials: P2pCredentials`, `FakePpppNative : PpppNative`, base file present |
| 02-01 vendor transport rule | 6 | VERIFIED | `TransportResolver.decide(` in CameraRegistry (2x); `bindP2pCredentialsSource` in `CameraModule.kt:52` (plan named CameraBindsModule, which lives in that file); device check 1 shows via=deviceInfo / via=uid, verified=2 |
| 02-02 extraction + manifest | 5 | VERIFIED | see criterion 1 |
| 02-03 JNI shim, runtime, Settings check | 5 | VERIFIED | `PPPP_APIs.java`, `st_PPPP_Session.java`, `configDebug(0, 4194311)`, `runNativeCheck`; device: BLOCK_NATIVE_CHECK line value-free |
| 02-04 auth, codec, IOCtrl, frame head | 5 | VERIFIED (JVM) | vector tests green; TNP byte layout never confirmed by a real camera (H1) |
| 02-05 decrypt, NAL, assembler, pipeline | 5 | VERIFIED | `IFrameDecryptor.decryptInPlace` + `onUnsupportedCodec` in pipeline; decryptOk=yes on device (TUTK) |
| 02-06 device check 1 | 6 | VERIFIED | `GATE_2B: needed`, redaction sweep of that log passed |
| 02-07 TnpCameraLink | 6 | VERIFIED (JVM only) | `native.connect(` with FLAG_P2P 0x49 / FLAG_RELAY 0xDF, BLOCK_CHECK_SESSION mode/lan, BLOCK_START_BURST 4881/9029/768, `onVideoUnit(`; never run on a camera |
| 02-08 VideoDecoder | 5 | VERIFIED (H.264 device, H.265 JVM) | `queueInputBuffer`, `DecoderCatalog.find`, NoDecoder state |
| 02-09 debug live screen | 5 | VERIFIED | `factory.open(`, `FramePipeline(`, `onOpenCamera`, `BLOCK_CLOSE_PROBE`, SurfaceView; truth "no automatic reconnect" intentionally amended by MED-4 (one WRONG_PASSWORD retry, user-approved, 02-REVIEW-FIXES.md) |
| 02-10 TUTK natives | 3 | VERIFIED | `com/tutk/IOTC/*` shim, `AndroidTutkNative` calls IOTCAPIs/AVAPIs (16 sites), TutkRuntime |
| 02-11 TutkCameraLink + dispatcher | 4 | VERIFIED (device) | `TransportType.TUTK` in TransportLinkFactory, `bigEndian = false` in TutkCameraLink, Hilt binds TransportLinkFactory; streams on device |
| 02-12 GRACE sync + device check | 5 | PARTIAL | GRACE sync done; 02-DEVICE-VERIFICATION.md exists with `RESULT: PARTIAL`; truths 1-4 pending for the TNP camera, truth 5 sweep pending |

(gsd-tools `verify artifacts/key-links` could not parse these frontmatters; all artifacts and key-link patterns were checked by hand with grep — every artifact exists with substantive size and every pattern matched.)

## Required Artifacts

All 34 artifacts listed across plan frontmatters exist with substantive content (e.g. TnpCameraLink.kt 654 lines, TutkCameraLink.kt 622, LiveProbeViewModel.kt 598, VideoDecoder.kt 462, CameraRegistry.kt 509). No TODO/FIXME/NotImplemented in `p2p/`, `stream/`, `media/`, `ui/live/`.

## Key Link Verification

| From | To | Status |
|------|----|--------|
| CameraListScreen row | AppShellScreen live overlay (`onOpenCamera`) | WIRED |
| LiveProbeViewModel.connect | `factory.open(` + `FramePipeline(` -> VideoDecoder | WIRED |
| P2pLinkModule | `bindCameraLinkFactory(impl: TransportLinkFactory)` | WIRED |
| TransportLinkFactory | TnpLinkFactory / TutkLinkFactory by `TransportType` | WIRED |
| TnpCameraLink | `native.connect` -> `native.check` -> readers -> `TnpCodec.commandUnit` burst -> `onVideoUnit` | WIRED (JVM-proven only) |
| TutkCameraLink | FrameHeadCodec `bigEndian = false` -> FramePipeline | WIRED (device-proven) |
| FramePipeline | `IFrameDecryptor.decryptInPlace`, `onUnsupportedCodec` | WIRED |
| VideoDecoder | `DecoderCatalog.find`, `queueInputBuffer` | WIRED |
| CameraModule | CameraRegistry as P2pCredentialsSource | WIRED |
| verification-plan V-M-TNP-LINK / V-M-UI-LIVE-PROBE | 02-DEVICE-VERIFICATION | WIRED |

## Data-Flow Trace (Level 4)

| Artifact | Data | Source | Real data | Status |
|----------|------|--------|-----------|--------|
| LiveProbeScreen SurfaceView | decoded frames | VideoDecoder <- FramePipeline <- CameraLink (TUTK) | yes, on device (frames=342, fps 21.9) | FLOWING (TUTK) |
| same, TNP | decoded frames | TnpCameraLink <- AndroidPpppNative | only FakePpppNative so far | UNVERIFIED on device |
| Status line | LinkInfo/DecoderStatus/pipeline stats | link + decoder + pipeline StateFlows | yes (device readings) | FLOWING |
| Credentials | P2pCredentials | CameraRegistry <- /v4/tnp/device_info + device list | yes (device check 1: verified=2) | FLOWING |

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| JVM suite green at HEAD | `tools/gradle.ps1 :app:testDebugUnitTest` + aggregate result XMLs | BUILD SUCCESSFUL; 41 suites, 526 tests, 0 failures, 0 errors, 1 skipped | PASS |
| No vendor binaries tracked | `git ls-files \| grep -E '\.(so\|apk\|dex\|flow)$'` | no output | PASS |
| Live video on a device | - | cannot run without the phone and cameras | SKIP -> H1-H6 |

## Device evidence: proven vs JVM-only

| Area | Real device | JVM fakes only |
|------|-------------|----------------|
| Native load (PPPP + TUTK libs, JNI_OnLoad, API 0xA2050401) | yes (device check 1) | |
| Transport decision for both cameras | yes (tnp=1 tutk=1, verified=2) | |
| TUTK link on LAN | yes (mode=LAN, motion confirmed) | |
| TUTK link over P2P / 4G | yes (two runs, two phones) | |
| H.264 hw decode, Surface render | yes | |
| I-frame decrypt (decryptOk) | yes (TUTK) | |
| One WRONG_PASSWORD auto-retry with refreshed credentials | yes (observed once on LAN) | |
| TNP link (PPPP connect, burst, BE frame head, auth 4882) | no | yes (TnpCameraLinkTest 27, TnpLinkFactoryTest) |
| Relay mode (0xDF / RELAY / TCP_RELAY / SDEV_RELAY) | no | yes |
| H.265 decode | no (decoder present only) | yes (NalUtils/DecoderPolicy tests) |
| Parallel use / hand-over with the official app | no | scripted only |
| Post-device commits a36b18b, 9548600, c1ec44b | no | yes |
| Live-session log redaction sweep | no (only device-check-1 log) | scanner tests yes |

## Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|--------------|-------------|--------|----------|
| LIVE-01 | 02-00..02-12 | Live video on the same Wi-Fi | NEEDS HUMAN (partial) | TUTK PASS on device; TNP pending (H1) |
| LIVE-02 | 02-00..04, 06, 07, 09..12 | Live video from another network via P2P or relay | NEEDS HUMAN (partial) | TUTK P2P over 4G PASS; TNP and relay pending (H2, H3) |
| LIVE-03 | 02-00, 03, 05, 06, 08..12 | H.264 and H.265 | NEEDS HUMAN (partial) | H.264 device PASS; H.265 JVM-only (H4) |

All three phase IDs are claimed by plans and mapped to Phase 2 in REQUIREMENTS.md; no orphaned IDs. They stay `Pending` (correct for an interim verification).

## Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| - | - | No TODO/FIXME/stub/empty handler in phase code | - | - |
| 02-DEVICE-VERIFICATION.md | 14, 40 | Intermittent first-press APP_WRONG_PASSWORD on TUTK (stale stored password suspected; auto-retry recovers) | Info | Not a criterion; watch in H5/H7 |
| 02-DEVICE-VERIFICATION.md | 38 | `BLOCK_DECODER_RESET reason=queue-overflow` before codec creation | Info | Addressed by 9548600, not re-checked on device (H5) |

## Human Verification Required

1. **H1 TNP camera on home Wi-Fi** — HEAD APK, official app closed, same Wi-Fi; TNP camera -> "Подключить" (relay off). Expected: moving picture; transport=TNP state=STREAMING lan=yes auth=ok decryptOk=yes fps>0; note codec.
2. **H2 TNP camera on mobile data** — 4G phone, Wi-Fi off; "Подключить" (relay off), one relay-only retry if no picture in 20 s. Expected: moving picture, lan=no, mode P2P or a relay mode.
3. **H3 relay-only** — tick "Только relay" on the TNP camera. Expected: relayForced=yes, mode=RELAY/TCP_RELAY/SDEV_RELAY, picture moves (or record the refusal name). TUTK camera: checkbox disabled.
4. **H4 H.265** — from H1's codec value: H265 must play like H264; if no camera is H.265, record "H.265 path not exercised on device".
5. **H5 regression of post-device commits on TUTK** — connect / stop / reconnect / leave and reopen. Expected: video each time, stop within ~2 s, no spurious queue-overflow reset, relay checkbox disabled.
6. **H6 hand-over with the official app** — split screen or sequential hand-over for both cameras. Expected: both apps get video in turn; BUSY/KICKED recorded as a Phase 3 item.
7. **H7 log pull + RealLogScanTest** on the live-session log. Expected: skipped=0 failures=0; TNP markers BLOCK_CONNECT/BLOCK_CHECK_SESSION/BLOCK_START_BURST present.

## Gaps Summary

No code gaps: every artifact, key link and JVM contract of the 13 plans is present and wired, and the suite is green at HEAD. The phase goal names the vendor TNP transport, and that transport has never streamed from a real camera; relay mode, H.265 and coexistence with the official app also lack device proof. Phase 2 must not be marked complete and LIVE-01..03 must stay Pending until H1-H4 and H7 pass (H5, H6 are regression/observation items). If H1 fails, the likely areas are the TNP byte layout (02-04 vectors are synthetic/public) and the connect flags/InitString in TnpCameraLink; that would turn this report into gaps_found.

---

_Verified: 2026-10-20T17:23:21Z_
_Verifier: Claude (gsd-verifier), interim_
