---
phase: 03-live-view
verified: 2026-10-25T00:00:00Z
status: human_needed
score: 13/14 plan must-have sets verified in code; 8/8 requirements implemented, 4 fully met with device evidence, 4 partial (device evidence open)
re_verification: false
human_verification:
  - test: "TNP camera: unmute and listen for 30 s"
    expected: "Camera sound is audible with low delay; mute stops it (decode path already proven: AAC 16 kHz mono enc=true, AudioTrack created)"
    why_human: "Audibility cannot be checked from logs"
  - test: "Wi-Fi -> 4G handover on the USB phone (or any phone with logs pulled)"
    expected: "BLOCK_NETWORK_EVENT generationChanged=true, reconnect after the 1.5 s debounce, Live again without a tap, no reconnect storm"
    why_human: "Only a user report without logs exists; the USB test phone has no 4G"
  - test: "TNP over mobile data / RELAY mode"
    expected: "Badge shows Relay; stream starts in SD (D-06); HD/SD toggle still works or hides"
    why_human: "Relay path and relay-SD default are JVM-tested only; never exercised on a device"
  - test: "Parallel use / hand-over with the official Yi app on the TNP camera (and TUTK with logs)"
    expected: "Our app in background frees the camera; official app gets video; ours resumes on return within 5 min"
    why_human: "TUTK only user-reported without logs; TNP not checked"
  - test: "15+ minutes of uninterrupted live viewing"
    expected: "No stop, prompt or reconnect caused by a timer; no ads"
    why_human: "Unit test proves no timer ends a healthy stream; no long device run is recorded in 03-DEVICE-VERIFICATION.md"
  - test: "H.265 camera"
    expected: "Video decodes (LIVE-03, Phase 2 scope, listed for completeness)"
    why_human: "No H.265 camera available"
---

> **Update 2026-10-25 (after the report):** the user confirmed TNP sound is audible, so LIVE-06 is met. Remaining partial: LIVE-07 (SD by default on relay is JVM-only), LIVE-10 (parallel use with the official app not checked on TNP).

# Phase 3: Live View — Verification Report

**Phase Goal:** Живое видео ведёт себя как в нормальном приложении: видно состояние связи, само восстанавливается, управляется звуком, качеством, снимком и жестами, не держит камеру в фоне.
**Verified:** 2026-10-25
**Status:** human_needed
**Re-verification:** No — initial verification

## Method

- All 14 PLAN must-have artifacts and key-link patterns grepped in the tree on branch `gsd/phase-3-live-view` (clean). All 55 artifact/link patterns found (counts >= 1).
- Unit tests not re-run: the latest results (`app/build/test-results/testDebugUnitTest`, 2026-10-23 19:35) show **686 tests, 0 failures, 0 errors, 1 skipped** (the skipped one is the parameterised `RealLogScanTest`, run separately for H-L9). The only code commit after that run (`4e958b0`) changes 2 lines in `LiveViewScreen.kt`, which has no JVM test (Compose + TextureView, compile-only on the JVM) and was verified on the device afterwards. The results are therefore current for all JVM-tested code.
- Device evidence taken from `03-DEVICE-VERIFICATION.md` (Mi 9, MIUI, API 30; TUTK and TNP over LAN).

## Goal Achievement — Observable Truths (per plan)

| Plan | Truths (summary) | Status | Evidence |
|---|---|---|---|
| 03-00 | Port types, default CameraLink members, configChanges, GRACE modules | VERIFIED | `SupervisorState` sealed interface, `setAudioSink(sink: AudioUnitSink?)` / `requestResolution` defaults in CameraLink.kt, `AudioUnitSink` fun interface, manifest `android:configChanges` + `maxSdkVersion`; M-LIVE-SESSION / M-MEDIA-SNAPSHOT in development-plan and knowledge-graph |
| 03-01 | ADTS parse, TNP decrypt fail-open, muted default, availability probe 6 s | VERIFIED | AdtsParser.kt, AudioPipeline.kt (`AdtsParser.parse`, `onAacFrame`); AudioPipelineTest: muted-by-default, decrypt full blocks/tail, wrong key unchanged, 6 s probe; device: TUTK `BLOCK_AUDIO_FIRST codecId=138`, TNP `enc=true` |
| 03-02 | TNP ch1 audio to sink, 4881 via txQueue, abilityRet hide | VERIFIED | TnpCameraLink `override fun requestResolution`, `onAudioUnit`, `txQueue.offer`; device: TNP 4881 HD->SD->HD, AES audio decoded |
| 03-03 | 3 TUTK natives in shim + TutkNative | VERIFIED | AVAPIs.java avClientCleanAudioBuf / avRecvAudioData / avRecvIOCtrl; `fun recvIoCtrl`; AndroidTutkNative calls `AVAPIs.avRecvAudioData`; TutkShimContractTest; works on device (TUTK audio) |
| 03-04 | TUTK rxa/rxc/tx threads, LE 4881, bounded teardown | VERIFIED | `setResolution(resolution, uc, bigEndian = false)` at TutkCameraLink.kt:181, `recvAudio`; device: TUTK 1280x720 <-> 640x360, background close ~1.7 s, registry freed |
| 03-05 | Quality target/observed/8 s hide, ResumePolicy 5 min | VERIFIED | `Quality.ofWidth`, `RESUME_WINDOW_MS = 300_000L`; QualityControllerTest (relay/unknown SD, 8 s hide), ResumePolicyTest |
| 03-06 | Backoff 2/5/10/20 s x5, auth/kick single retry, NoNetwork, debounce 1.5 s, stall 10 s, one link at a time, background resume | VERIFIED | Timing defaults in LiveSessionSupervisor.kt:70-80; 38 supervisor tests incl. "opens happen at 0 2000 7000 17000 37000", "stalled stream is closed after 10 s", "healthy stream is never ended by a timer", "next open waits for the previous link up to 5 s"; device: backoff to exhausted, stall recovery, Wi-Fi off/on recovery |
| 03-07 | MediaCodec AAC -> AudioTrack from output format, 6-frame drop-oldest, idempotent release | VERIFIED | AacDecoder.kt `audio/mp4a-latm`, `AudioTrack.Builder`, `queue.offer`; AudioFrameQueueTest; device: decoder follows 8 kHz stereo (TUTK) and 16 kHz mono (TNP) |
| 03-08 | Default-network callback, no VALIDATED gate, crop-aware video size | VERIFIED | AndroidNetworkMonitor `registerDefaultNetworkCallback` + `tracker.on*`, Hilt binding in LiveModule; NetStateTrackerTest, VideoSizeTest; device `BLOCK_NETWORK_EVENT available=false/true generationChanged=true` |
| 03-09 | Snapshot to Pictures/YiCamAlt, named outcomes, zoom 1..4x | VERIFIED | SnapshotSaver.kt `Pictures/YiCamAlt`, `SnapshotSpec.target`; ZoomMath `MAX_SCALE = 4f`; SnapshotSaverTest, ZoomMathTest; device `result=Saved` |
| 03-10 | Auto-connect, badge texts, muted on open/after background, HD/SD observed, snapshot messages, screen-left release | VERIFIED | LiveViewModel.kt `supervisor?.start()` (l.368), `badgeFor`, `muted = true` on open/onStop, REASON_BACKGROUND / REASON_SCREEN_LEFT; DefaultMediaSession `pipeline.onVideoUnit`; LiveViewModelTest (23 tests), DefaultMediaSessionTest |
| 03-11 | TextureView screen, fullscreen/rotation, 4 s auto-hide, gestures, keep-screen-on, getBitmap snapshot, lifecycle | VERIFIED (device) | LiveViewScreen.kt: TextureView, `attachSurface(Surface(`, ON_STOP/ON_START, BackHandler, systemBars hide, `detectTransformGestures`, `onDoubleTap`, `keepScreenOn = ui.videoRendering`, `getBitmap(videoW, videoH)`, API 26-28 permission; AppShellScreen `LiveViewScreen(`, CameraListScreen `onLongClick`. No JVM test (by design); device PASS H-L1, H-L7, H-L8, keep-screen-on, Back |
| 03-12 | GRACE docs updated, verification records list real tests, UAT file H-L1..H-L9 | VERIFIED | verification-plan.xml has V-M-LIVE-SESSION / V-M-MEDIA-AUDIO / V-M-MEDIA-SNAPSHOT / V-M-UI-LIVEVIEW and LiveSessionSupervisorTest; 03-HUMAN-UAT.md lists H-L1..H-L9 |
| 03-13 | Device results recorded from real evidence; UAT and verification-plan updated from them | PARTIAL | Results are recorded in 03-DEVICE-VERIFICATION.md (real logs/screenshots). Key link "03-HUMAN-UAT.md -> verification-plan.xml status updated" NOT done: every H-L in 03-HUMAN-UAT.md is still `result: [pending]` (summary passed: 0) and all H-L device scenarios in verification-plan.xml are `status="pending"`; no 03-13-SUMMARY.md. Several items still open (see human verification) |

## Requirements Coverage

All 8 phase IDs (LIVE-04..LIVE-11) are claimed by plans (03-00, 03-06, 03-10, 03-12, 03-13 cover all; others subsets). REQUIREMENTS.md maps exactly LIVE-04..LIVE-11 to Phase 3 — no orphaned IDs.

| Requirement | Description | Status | Evidence |
|---|---|---|---|
| LIVE-04 | Состояние соединения (подключение / LAN / P2P / relay / офлайн / ошибка авторизации) | MET (relay label JVM-only) | `badgeFor` + LiveViewModelTest "badgeFor connecting and waiting / live modes / final and idle states"; device: LAN badge, "Повтор (n/5)", "Камера не в сети" + "Повторить", "Не подключено" + "Подключить". Relay badge not seen on a device (no relay session yet) |
| LIVE-05 | Автопереподключение с ограниченным backoff | MET (Wi-Fi->4G unlogged) | Supervisor backoff/stall/NoNetwork/debounce tests; device with logs: Wi-Fi off/on recovered ~1.2 s, camera power cycle recovered without action (~2 min, incl. single WRONG_PASSWORD retry), 5-attempt exhaustion on offline TNP, spontaneous drops recovered in 2-4 s. Wi-Fi->4G only user-reported without logs |
| LIVE-06 | Звук вкл/выкл, по умолчанию выключен | PARTIAL | Code + tests (AudioPipelineTest, LiveViewModelTest mute tests, DefaultMediaSessionTest); TUTK: sound audible (user), mute default; TNP: AES audio decrypted and decoded, AudioTrack created, but audibility not confirmed |
| LIVE-07 | HD/SD, SD по умолчанию на relay | PARTIAL | Switch proven on both cameras on device (TUTK 1280x720/640x360, TNP 1920x1080/640x360) without reconnect; "SD by default on relay" is only JVM-tested (QualityControllerTest "target is HD on direct paths and SD on relays or unknown") — no relay session on a device |
| LIVE-08 | Снимок в галерею | MET | SnapshotSaver + SnapshotSaverTest; device: `result=Saved`, JPEG in Pictures/YiCamAlt at stream resolution (TUTK) and on TNP (281 KB) |
| LIVE-09 | Полный экран и масштаб жестом | MET | ZoomMath + ZoomMathTest; LiveViewScreen gestures/fullscreen; device: fullscreen by button (logs) and rotation, Back exits, pinch/double tap user-confirmed by hand |
| LIVE-10 | Стоп при уходе/сворачивании; одна сессия на камеру; официальное приложение не блокируется | PARTIAL | Code + tests (supervisor drain/one-link tests, ResumePolicyTest, VM lifecycle tests, TUTK teardown tests); device with logs: Home/lock stop (TUTK ~1.7 s, TNP 374 ms), resume within 5 min, no resume after 446 s / 36 min. Official-app coexistence: TUTK only user-reported without logs; TNP not checked |
| LIVE-11 | Без ограничения по времени и без рекламы | MET in code/tests; long device run not recorded | Supervisor test "healthy stream is never ended by a timer"; no ad SDK in app/build.gradle.kts; no timer-based stop in the supervisor (only the 10 s stall watchdog on a non-growing frame counter). No 15+ min viewing run is recorded in the device log |

## Key Link Verification

All key-link patterns from 03-00..03-12 found (see Method). Notable wiring confirmed: VM -> supervisor start on open; supervisor -> `factory.open` and `framesIn()` watchdog; DefaultMediaSession video tee -> `pipeline.onVideoUnit`, audio -> AudioPipeline only (test "audio units reach only the audio pipeline"); TextureView listener -> `attachSurface(Surface(...))`; lifecycle ON_STOP/ON_START -> VM; AndroidNetworkMonitor -> NetStateTracker. One unwired link: 03-13 "03-HUMAN-UAT.md -> verification-plan.xml" (status not propagated).

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Unit suite green | parse `app/build/test-results/testDebugUnitTest/*.xml` | 686 tests, 0 failures, 0 errors, 1 skipped | PASS |
| Results current vs code | `git log -- app/src` after the run | only `4e958b0` (2 lines, LiveViewScreen.kt, no JVM test, device-verified) | PASS |
| No ad SDK | grep admob/ads/applovin/unity in build files | none | PASS |

## Anti-Patterns / Documentation Findings

| File | Finding | Severity |
|---|---|---|
| .planning/phases/03-live-view/03-HUMAN-UAT.md | All H-L1..H-L9 still `result: [pending]`, summary `passed: 0`, despite recorded device results | Warning (documentation drift) |
| docs/verification-plan.xml | All H-L device scenarios `status="pending"`; evidence lines say "Device evidence pending" | Warning (documentation drift) |
| .planning/phases/03-live-view/03-DEVICE-VERIFICATION.md | Header still says "TUTK camera only, TNP unreachable" while row 27 records the TNP LAN PASS; "Not verified" still lists keep-screen-on and Back, which are PASS in the table | Info (stale text) |
| .planning/phases/03-live-view/ | No 03-13-SUMMARY.md | Info |

No code stubs found in the phase artifacts; the scan of the plan artifact files found no placeholder implementations.

## Human Verification Required

1. **TNP sound audibility** — unmute on the TNP camera, listen 30 s; expect audible sound, mute stops it.
2. **Wi-Fi -> 4G with logs** — expect network generation change, debounced reconnect, Live without a tap, no storm.
3. **TNP over mobile data / RELAY** — expect Relay badge and SD at start (closes the relay half of LIVE-04 and LIVE-07).
4. **Official Yi app hand-over on TNP (and TUTK with logs)** — expect the official app to get video while ours is in background (LIVE-10).
5. **15+ minutes continuous viewing** — expect no interruption (LIVE-11 device evidence).
6. **H.265 camera** — Phase 2 scope (LIVE-03), no camera available.

## Gaps Summary

No code gaps: every planned module exists, is substantive, wired, covered by JVM tests (686 green) and most behaviours are proven on a real device for both camera families over LAN. What remains is device evidence on paths not yet exercised (TNP audibility, relay/mobile data, Wi-Fi->4G with logs, official-app coexistence on TNP, a long viewing run) plus documentation drift: 03-HUMAN-UAT.md and verification-plan.xml were not updated from 03-DEVICE-VERIFICATION.md. LIVE-04, LIVE-05, LIVE-08, LIVE-09, LIVE-11 can be considered met (with the residual notes above); LIVE-06, LIVE-07, LIVE-10 are partial until the human items close.

---

_Verified: 2026-10-25_
_Verifier: Claude (gsd-verifier)_
