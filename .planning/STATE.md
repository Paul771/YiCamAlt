---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: verifying
stopped_at: Completed 04-10-PLAN.md
last_updated: "2026-10-31T13:44:43.573Z"
last_activity: 2026-10-31
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 54
  completed_plans: 50
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-10)

**Core value:** Открыть приложение → увидеть свои камеры → смотреть живое видео с любой из них, из любой сети, без подписки.
**Current focus:** Phase 4 — Архив SD-карты

## Current Position

Phase: 4 (Архив SD-карты) — EXECUTING
Plan: 10 of 13
Status: Gap closure in progress — 04-10 (W-2) done; 04-11..04-13 pending; phase stays human_needed
Last activity: 2026-10-31

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**

- Total plans completed: 0
- Average duration: -
- Total execution time: 0.0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
| Phase 01 P00 | 50min | 3 tasks | 8 files |
| Phase 02 P00 | 15min | 3 tasks | 12 files |
| Phase 02 P01 | 25min | 2 tasks | 13 files |
| Phase 02-p2p P02 | 20min | 2 tasks | 4 files |
| Phase 02-p2p P03 | 30min | 3 tasks | 12 files |
| Phase 02-p2p P04 | 15min | 2 tasks | 8 files |
| Phase 02-p2p P05 | 20min | 2 tasks | 9 files |
| Phase 02 P06 | 1d | 3 tasks | 2 files |
| Phase 02-p2p P07 | 40min | 2 tasks | 7 files |
| Phase 02-p2p P08 | 25min | 2 tasks | 5 files |
| Phase 02-p2p P09 | 35min | 2 tasks | 8 files |
| Phase 02-p2p P10 | 35min | 3 tasks | 17 files |
| Phase 02-p2p P11 | 40min | 2 tasks | 9 files |
| Phase 03-live-view P00 | 20min | 2 tasks | 9 files |
| Phase 03 P01 | 25min | 2 tasks | 7 files |
| Phase 03-live-view P02 | 25min | 2 tasks | 4 files |
| Phase 03-live-view P03 | 15min | 2 tasks | 5 files |
| Phase 03 P05 | 6min | 2 tasks | 4 files |
| Phase 03 P08 | 15min | 2 tasks | 10 files |
| Phase 03 P09 | 10min | 2 tasks | 7 files |
| Phase 03 P04 | 25min | 2 tasks | 4 files |
| Phase 03 P06 | 30min | 2 tasks | 5 files |
| Phase 03 P07 | 25min | 2 tasks | 6 files |
| Phase 03 P10 | 45min | 2 tasks | 7 files |
| Phase 03 P11 | 20min | 2 tasks | 3 files |
| Phase 03 P12 | 45min | 2 tasks | 5 files |
| Phase 04 P00 | 35min | 3 tasks | 7 files |
| Phase 04 P01 | 15min | 3 tasks | 6 files |
| Phase 04 P02 | 25min | 2 tasks | 3 files |
| Phase 04 P03 | 20min | 3 tasks | 9 files |
| Phase 04 P05 | 35min | 3 tasks | 11 files |
| Phase 04 P04 | 45min | 3 tasks | 9 files |
| Phase 04 P06 | 75min | 3 tasks | 8 files |
| Phase 04 P07 | 45min | 3 tasks | 7 files |
| Phase 04 P08 | 20min | 2 tasks | 5 files |
| Phase 04 P09 | 15min | 3 tasks | 3 files |
| Phase 04 P10 | 40min | 3 tasks | 7 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Phase 1 начинается с одной пробы-матрицы эндпоинтов/регионов на устройстве (только статус/код/длина/имена полей); захват apk-mitm критичен лишь если все пробы пусты
- [Roadmap]: TNP — транспорт по умолчанию, TUTK-адаптер только при камере type 0
- [Roadmap]: Phase 5 (события) идёт параллельно с P2P-линией (Phases 2–4); EVT-04 замыкается после Phase 4
- [Roadmap]: GSD-нумерация фаз независима от GRACE `Phase-N`; каждая фаза обновляет GRACE-артефакты (контракты, development-plan, knowledge-graph, verification-plan)
- [Phase 01]: gradle.ps1 clears handle inheritance (except stdin) before spawning Gradle; unknown exit code reports 1
- [Phase 02]: RedactionScanner IPv4 pattern skips 127.x loopback (MockWebServer URLs are not peer addresses)
- [Phase 02]: Phase 2 stream/p2p packages use // doc comments only (no block comments)
- [Phase 02]: Transport = vendor rule only (device_info TNP DID, else uid case); list type/did are log hints
- [Phase 02]: device_info network failure keeps only a verified record, else UNKNOWN (never a guessed transport)
- [Phase 02-p2p]: Vendor natives: extract never fails, verify hooked by exact APK task names (assemble/install/bundle Debug|Release, merge*JniLibFolders); manifest re-pin only via -PvendorNativesAccept=true
- [Phase 02-p2p]: PPPP init state is terminal per process; timeout -> InitFailed(-3003), late result adopted, never re-initialised
- [Phase 02-p2p]: Debug P2P check line: libs/abi/ppppApi/init/tutkLibs/avc/hevc (Settings, BLOCK_NATIVE_CHECK)
- [Phase 02-p2p]: AuthOutcome is a sealed interface so Unknown keeps the raw authResult; validateHead order bad-channel, bad-iotype, zero-size, too-large
- [Phase 02-p2p]: Pinned TNP vector: hmacPassword(SYNTHpw1, SYNTHAAbcdefgh1) = C5KKYDqrtOxn15T (JDK, synthetic)
- [Phase 02-p2p]: FramePipeline is fail-open: wrong-password I-frames are forwarded, stats().decryptOk reports the check
- [Phase 02-p2p]: FrameAssembler pending overflow drops the earliest-buffered P; an I-frame clears pending
- [Phase 02]: GATE_2B: needed — real account has 1 TNP (via=deviceInfo) + 1 TUTK (via=uid) camera, verified=2; plan 02-10 required
- [Phase 02]: Device check 1: PPPP api=0xA2050401 init=0 on arm64; HW AVC+HEVC (OMX.qcom)
- [Phase 02-p2p]: TnpCameraLink teardown runs on p2p-<n>-close; external callers wait (bounded), link threads never wait
- [Phase 02-p2p]: Burst waits until all 5 readers made their first read (bounded); connect timeout covers connect+check
- [Phase 02-p2p]: Header version adopted only for 1..3; unknown authResult fails like 4 (PROTOCOL -9000, no retry)
- [Phase 02-p2p]: VideoDecoder: all MediaCodec work on one HandlerThread; key frame with new mime/size/csd recreates the codec (format-change)
- [Phase 02-p2p]: LiveProbe: per-press Session (decoder+pipeline+link); late factory result closed, never started; link close on live-probe-close thread
- [Phase 02-p2p]: LiveProbe: ON_STOP closes the session, not the VM; Connect re-enabled, no auto-reconnect
- [Phase 02-p2p]: TUTK init: IOTC_Initialize2 >=0 or -3 and avInitialize >=0 or -20031 are Ready; 10 s bound gives InitFailed(-13), late result adopted
- [Phase 02-p2p]: TUTK shim omits DeInitialize natives; names pinned from .so strings (12 classes/interfaces)
- [Phase 02-p2p]: 02-11: TransportLinkFactory bound as CameraLinkFactory; TUTK connect timer covers connect+check+clientStart; teardown frees sid0 via stop(-1, sid0) after a failed/aborted connect
- [Phase 02-p2p]: 02-11: TUTK -20012 polling bounded at 300 x 30 ms -> Failed(TIMEOUT, -20012), no vendor auto-reconnect
- [Phase 03]: Phase 3 CameraLink members have default bodies so TNP/TUTK links and fakes compile unchanged
- [Phase 03]: M-UI-LIVEVIEW rewritten: no PTZ/camera selector (v2), depends on M-LIVE-SESSION
- [Phase 03]: Audio availability: valid frame -> true; non-AAC codec -> false; else null until 6 s after streaming, then false
- [Phase 03]: AudioPipeline decryptOk only set when a decrypt ran; fail-open units leave it null
- [Phase 03-live-view]: TNP requestResolution returns null until the start burst is queued; audio sink exceptions logged once and dropped
- [Phase 03]: TUTK audio/IOCTRL readers: avRecvAudioData / avRecvIOCtrl on the same AV channel, exports verified on both ABIs; fake recvInFlight covers all readers
- [Phase 03]: QualityController: needsAutoRequest requires no pending request; beginRequest (also null useCount) marks the per-link auto request done
- [Phase 03]: ResumePolicy: shouldAutoResume consumes the pending resume even after the window expired
- [Phase 03]: NetStateTracker keeps the last network key after onLost; same network back keeps generation
- [Phase 03]: VideoDecoder.videoSize seeded from key-frame size at codec creation, then crop-aware output format size
- [Phase 03]: SnapshotStore owns the bitmap; failed insert/compress/write deletes the row and returns WriteFailed(class)
- [Phase 03]: ZoomMath.doubleTap: scale>1 -> fit; fill<=1 -> 2x
- [Phase 03]: TUTK tx and rxc start after a successful 8190/511; rxa and 768 only with an audio sink set before start; audio/IOCTRL errors end only their loop
- [Phase 03]: LiveSessionSupervisor: run token under lock closes late opens; watch ends on terminal or own close; wrong-password/kick retries advance attempt, only NETWORK checks maxAttempts; healthy 30 s resets attempt and allowances
- [Phase 03]: AacDecoder: failures terminal (Failed, no retry); no-decoder/codec/channels/<class> reasons; AudioTrack from codec output format; queue 6 drop-oldest
- [Phase 03]: 03-10: re-open of the same camera keeps the session (title only); another camera closes the screen first
- [Phase 03]: 03-10: LiveVideoOutput.resetEvents -> DefaultMediaSession.resetPipeline (decoder reset re-arms the key-frame gate)
- [Phase 03]: 03-10: WRONG_PASSWORD/KICK waits show Подключение…; NETWORK waits and Connecting(n>1) show Повтор (n/5)
- [Phase 03]: 03-11: video slot stable across portrait/fullscreen; fullscreen exit forces PORTRAIT then OrientationEventListener restores UNSPECIFIED
- [Phase 03]: 03-11: camera tap opens LiveViewScreen, long press opens the debug LiveProbeScreen
- [Phase 03]: Phase 3 GRACE V-M records stay in-progress (JVM-verified); H-L1..H-L9 pending in 03-HUMAN-UAT.md, none passed
- [Phase 04]: TNP canSend() gate shared by requestResolution, sendIoCtrl, nextUseCount; one link-wide useCount after burst 1/2
- [Phase 04]: TNP ioCtrlReplies publishes only authResult-0 ch0 replies other than 4882/9033; TNP setStreamMode stays no-op
- [Phase 04]: SD wire time = UTC fields (SdTime.WIRE_IS_UTC seam); dayBounds half-open via atStartOfDay
- [Phase 04]: parseTnpIndex: <12 B null, truncated -> empty (vendor); parseTutkIndexPage null unless size/12-1 == count
- [Phase 04]: TUTK sendIoCtrl/nextUseCount share the requestResolution guard; outside LIVE the -20012 counter resets, pause pacing unchanged
- [Phase 04]: 04-03: segments merge on gap <= 2 s inclusive; resolveSeek gives Live within 10 s of now or in the future
- [Phase 04]: 04-03: TUTK index paging capped at 1000 replies incl. rejected pages; per-reply 10 s timeout
- [Phase 04]: ArchiveLinkPort.framesIn() added: start timeout counts only while units arrive; 04-04/04-06 ports must forward MediaSession.framesIn()
- [Phase 04]: PlaybackController serialises calls/ticker/collector with one lock; timers keyed on linkHealthy() only
- [Phase 04]: 04-04: TNP camera has no SD card (tfstat=5, verified); TUTK unverified (unreachable); SD-01..SD-04 device scenarios need a microSD before 04-09, H-SD6 checkable on TNP
- [Phase 04]: 04-04: WIRE_IS_UTC stays true (no deltaS; TNP 817 isUtc=1 supporting only); BOUNDARY_MS 8000 and both transports kept (unverified)
- [Phase 04]: 04-06: supervisor stall window counts from max(last frame growth, last setStreamMode); HD/SD requests only in LIVE
- [Phase 04]: 04-06: TUTK hide needs Failed(NO_FRAMES) + no Playing ever + liveThroughout (set on entry into Starting from non-Starting, cleared by unhealthy or liveEpoch change) + units arriving
- [Phase 04]: 04-06: openDays refresh re-fetches only the index, keeps old data on failure; selected day follows the cursor only on a cursor day change
- [Phase 04]: 04-07: custom month grid (no material3 DatePicker badge); today tappable without recordings; months = contiguous recorded range incl. today
- [Phase 04]: 04-07: fullscreen-only overlay pause/Живой; ruler follows the cursor only while untouched; pure pinch never seeks
- [Phase 04]: 04-08: M-SD-ARCHIVE is GRACE Phase-3 step-17; H-SD1..H-SD10 pending in 04-HUMAN-UAT.md, need a microSD with recordings (H-SD6 checkable on TNP)
- [Phase 04]: 04-09: D-06 not applied (TUTK unreachable, no evidence); TUTK archive set unchanged
- [Phase 04]: 04-09: device run PARTIAL - H-SD6 no-card passed, other H-SD not exercised (no SD card); phase stays human_needed
- [Phase 04]: 04-10: ARCHIVE stall close counts as unhealthy only if frames stopped growing < healthyResetMs after Streaming (W-2c)
- [Phase 04]: 04-10: 2 consecutive ARCHIVE-stall reconnects at one position (+-2 s) -> Paused(SILENT) with live video; network drops never count (W-2b)
- [Phase 04]: 04-10: position at/after last known segment end with no next segment = end-near, ENDED at 30 s (W-2a)

### Pending Todos

None yet.

### Blockers/Concerns

- Phases 2, 4, 5 требуют `/gsd:research-phase` перед планированием (нативный bring-up, SD-пакеты, шифрование медиа событий)
- Проверка только на физическом телефоне по USB (эмулятор недоступен) — медленные итерации
- Старые вендорские блобы остаются в истории git/LFS (владелец решил историю не переписывать)

## Session Continuity

Last session: 2026-10-31T13:44:43.568Z
Stopped at: Completed 04-10-PLAN.md
Resume file: None
