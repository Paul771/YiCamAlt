---
phase: 04-sd-archive
verified: 2026-10-31T09:30:00Z
status: human_needed
score: 4/4 success criteria implemented and wired in code (JVM-tested); device-verified 0/4 fully, 1/4 partially (SC-4 no-card state only)
re_verification: false
human_verification:
  - test: "H-SD1 day dialog (SD-01): on a camera with a microSD card that has recorded for at least 1 h, tap the day button under the video"
    expected: "Recorded days have a dot and can be tapped; other days are grey and cannot be tapped (today always can); «Сегодня ▾» is selected by default; picking a recorded day changes the button label and the timeline"
    why_human: "Needs a real 9032/793 index; no reachable camera has a card (TNP tfstat=5, TUTK unreachable)"
  - test: "H-SD2 timeline (SD-02): choose a day with recordings, scroll the ruler, pinch in and out"
    expected: "The coloured segments match the official app's SD timeline for that day (no 3 h shift); scroll and pinch work from 15 min to 24 h; the cursor stays in the centre; a pinch alone never starts playback"
    why_human: "TimelineRuler gestures and drawing are covered only by compile-time checks and TimelineMath unit tests"
  - test: "H-SD3 seek and wire-time base (SD-03): drag onto a segment and release; then drag into a gap and release"
    expected: "Playback starts within a few seconds; «Запись HH:mm:ss» matches the camera's on-screen timestamp within about 5 s (checks SdTime.WIRE_IS_UTC; an offset of about 3 h is a FAIL); a gap snaps to the next segment start; no green or smeared frames after the seek; log BLOCK_SEEK result=Archive and BLOCK_PLAYBACK_STATE to=Playing reason=first-key"
    why_human: "Camera-side semantics of STimeDay (UTC or local) can only be confirmed against real recordings"
  - test: "H-SD3b seek into the last minutes of today (W-1): keep the camera screen open for more than 10 min, then drag the ruler to 3-5 min before now without opening the day dialog"
    expected: "Playback starts at the chosen moment. Known risk: with the current code the timeline of today is frozen at load time and the seek may be clamped to the old end; record the result"
    why_human: "Confirms or rules out finding W-1 on the device"
  - test: "H-SD4 segment boundary and end of recording (SD-03, D-04): start shortly before the end of a segment that has a following segment; then seek near the end of the last segment"
    expected: "Playback crosses into the next segment without user action; at the end of the last segment the panel shows «Записи закончились» within about 30 s, without a reconnect («Подключение…») and without a reconnect loop (finding W-2)"
    why_human: "Whether the camera crosses file boundaries by itself and what it sends at the end of a record is unknown offline"
  - test: "H-SD5 pause / «Продолжить» / «Живой» (SD-04): during playback press pause, wait 60 s, press «Продолжить», then «Живой»"
    expected: "Pause freezes the frame and no BLOCK_STALL appears; «Продолжить» resumes from the same moment (within a few seconds); «Живой» returns to live video in one tap and the badge never shows «Подключение…» (same session, no reconnect)"
    why_human: "Same-session mode switching (9030 / stop record / 9029, TUTK 12718 / 8190) is only proven with fakes"
  - test: "H-SD6 remaining empty states (SD-05): on a carded camera choose a day without recordings (if every day has recordings, use a freshly formatted card on day 1); then provoke a list failure (for example turn Wi-Fi off right after the camera screen opens)"
    expected: "«За этот день записей нет» while live keeps playing; «Не удалось получить список» with «Повторить», and «Повторить» loads the list once the network is back"
    why_human: "The no-card state already passed on the device; the other two need a carded camera"
  - test: "H-SD7 sound and background in the archive (D-05): unmute live, enter the archive, press Home, return within 5 min"
    expected: "Sound goes off when the archive starts; after the return live video plays, the panel shows «Продолжить» at the saved position and nothing plays by itself; «Продолжить» seeks to that moment"
    why_human: "Lifecycle plus real camera"
  - test: "H-SD8 fullscreen, zoom and snapshot during archive playback (D-01)"
    expected: "Fullscreen shows pause / «Живой» and the recording time; pinch zoom works; the snapshot lands in Pictures/YiCamAlt"
    why_human: "UI on the device"
  - test: "H-SD9 TUTK camera (D-06): power on the TUTK camera, open it, and if it has a card repeat H-SD1, H-SD3 and H-SD5; also check that TUTK live still works (live-regression check of the 04-02 link changes)"
    expected: "The archive works on TUTK, or is hidden with «Архив SD на этой камере недоступен» only when playback is really rejected; live video on TUTK unchanged from Phase 3; RealLogScanTest over the pulled log: skipped=0 failures=0"
    why_human: "The TUTK camera was unreachable in the 04-09 session; the TUTK link changed in 04-02 (generic IOCTRL, 793 pages, -20012 limit only in LIVE)"
  - test: "H-SD10 reconnect during playback (D-04): while a recording plays, turn Wi-Fi off, wait for the retry badge, turn it back on"
    expected: "Playback resumes at the same moment (within a few seconds) without user action, retry schedule 2/5/10/20 s with 5 attempts, no «Записи закончились», no jump to another segment"
    why_human: "Real network and camera"
---

# Phase 4: Архив SD-карты — Verification Report

**Phase goal:** «Пользователь находит и смотрит записи с SD-карты камеры за любой день через ту же P2P-сессию»
**Verified:** 2026-10-31T09:30:00Z
**Status:** human_needed
**Re-verification:** No, this is the initial verification.

## Summary

SD-01 to SD-05 all have real, wired implementations: no stubs, no placeholder UI, no hardcoded empty data. The wire format matches 04-RESEARCH byte for byte. D-01 to D-07 are honoured in code. The tests check behaviour (bytes, state transitions, sent commands, gates, log markers), not just that files exist.

Device evidence is still partial. The TNP camera has no SD card (tfstat=5) and the TUTK camera was unreachable. On the phone, only the no-card state and the live-with-panel check passed. So the status is `human_needed`, not `passed`. Missing hardware is not a code gap, so it is not `gaps_found` either.

The review found two medium-severity robustness defects (W-1 and W-2 below). Neither blocks the human UAT, but both should be fixed before it, or checked explicitly during it.

## Gradle evidence (run by the verifier)

`powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug --rerun`
- Result: BUILD SUCCESSFUL, exit 0. `:app:testDebugUnitTest` actually executed (not UP-TO-DATE; the result XMLs are timestamped 2026-10-31 12:19 local).
- JUnit totals from `app/build/test-results/testDebugUnitTest/*.xml`: **903 tests, 0 failures, 0 errors, 1 skipped**. The skipped test is `RealLogScanTest`, which only runs when a log file is passed.
- `app/build/outputs/apk/debug/app-debug.apk` was rebuilt at 12:20.

## Goal Achievement

### Observable Truths (ROADMAP success criteria)

| # | Truth | Code status | Device status | Evidence |
|---|-------|-------------|---------------|----------|
| 1 | The user picks a day in a calendar where days with SD recordings are marked | ✓ VERIFIED (code) | ? not exercised | `SdIndexRepository.fetchIndex` (9031→9032 / 792→793 paged) → `SdTimeline.fromEntries` → `daysWithRecordings` (a segment that crosses midnight marks both days) → `ArchiveCoordinator.recordedDays` → `DayPickerDialog` + `CalendarGrid.month` (dot on recorded days; enabled = recorded or today). `selectDay` rejects days without recordings. Tests: `CalendarGridTest`, `SdTimelineTest`, `ArchiveCoordinatorTest.selectDay…` |
| 2 | 24 h timeline of the chosen day with segments; play from a moment; seek along the timeline | ✓ VERIFIED (code), caveat W-1 | ? not exercised | `SdTimeline.segmentsForDay` (DST-safe bounds via `atStartOfDay`) → `ArchiveUi.segments/dayStartMs/dayEndMs` → `TimelineRuler` (Canvas, pan, pinch 15 min to 24 h, default 3 h, centre cursor, seek on release via `shouldSeekAfterGesture`) → `LiveViewModel.archiveSeek` → `ArchiveCoordinator.seek` → `PlaybackController.seek` → `resolveSeek` → `startAt`: gate `expectGeneration(uc)` set BEFORE the 9030 / 12718-cmd-16 is sent; Playing on the first recorded key frame of that uc |
| 3 | Pause, continue, and back to live in one action | ✓ VERIFIED (code) | ? not exercised | `pause` = PAUSED mode + 767 + stop record; `resume` = fresh seek at the saved position with a new uc; `goLive` = gate(new uc) + stop record + 9029 / 8190\|511 on the same link, gate released on the first live key frame or after 20 s (fail-open). Buttons: `ArchivePanel` («Пауза»/«Продолжить», «Живой») and the fullscreen overlay in `LiveViewScreen` |
| 4 | No card or no recordings for the day gives a clear state, not an empty timeline or a hang | ✓ VERIFIED (code) | ✓ partial: no-card state PASS | 816→817 `sdStatusOf` (5 NoCard, 2/3/8/9 or 0-with-total-0 Abnormal) → NO_CARD / CARD_ERROR (index never requested); empty day → NO_RECORDINGS; index timeout or malformed reply → LIST_FAILED + «Повторить»; 10 s timeout on every exchange. Device: «Нет SD-карты» under live video, `BLOCK_SD_STATUS tfstat=5 status=NoCard`, `index=skipped` |

**Score:** 4/4 truths implemented and wired in code. Device: 1/4 partial (SC-4, no-card state only).

### Requirements Coverage

| Req | Description | Code status | Device-verified | Evidence / caveat |
|-----|-------------|-------------|-----------------|-------------------|
| SD-01 | Picks a day and sees which days have SD recordings | **SATISFIED-in-code** | No (H-SD1) | Index, then days, then a dotted month grid. The dialog itself opened on the device in the no-card case (only today tappable) |
| SD-02 | 24 h timeline with the recorded segments of the chosen day | **SATISFIED-in-code** (caveat W-1) | No (H-SD2) | Clipping per local day, 23/25 h DST days tested. Today's segments are frozen at index-load time (W-1) |
| SD-03 | Plays from a chosen moment and seeks on the timeline | **SATISFIED-in-code** (caveats W-1, W-2) | No (H-SD3/H-SD4/H-SD10) | 9030 / 12718 bytes match research §P4. useCount equality gate. Gap snap, end clamp −6 s, live if within 10 s of now. Boundary jump after 8 s, ENDED after 30 s. Reconnect re-seeks the frozen position. `SdTime.WIRE_IS_UTC` is an unconfirmed assumption |
| SD-04 | Pauses, continues, returns to live | **SATISFIED-in-code** | No (H-SD5/H-SD7) | Same session, no reconnect. Supervisor stall is off in PAUSED and 35 s in ARCHIVE; TUTK -20012 limit applies only in LIVE. Background → Paused(BACKGROUND), never auto-resumes (D-05) |
| SD-05 | Clear state when there is no SD card or no recordings for the day | **SATISFIED-in-code** | Partial: «Нет SD-карты» PASS; «За этот день записей нет» and «Не удалось получить список»/«Повторить» not exercised | D-07 texts are exact. Live keeps playing under every list state |

No orphaned requirements. REQUIREMENTS.md maps SD-01..SD-05 to Phase 4, and every one is claimed by the plans. SD-06..SD-08 are explicitly deferred.

### Locked decisions D-01..D-07

| Decision | Status | Evidence |
|----------|--------|----------|
| D-01 archive under the video on the camera screen; fullscreen, zoom and snapshot still work | ✓ code | `LiveViewScreen` hosts `ArchivePanel` under the video plus fullscreen pause / «Живой» / «Запись HH:mm:ss». There is no separate archive screen. Fullscreen use in the archive is not device-checked (H-SD8) |
| D-02 «Сегодня ▾» + calendar; dots; days without recordings unavailable; today by default | ✓ code | `ArchiveTexts.dayLabel`, `CalendarGrid` (enabled = recorded or today, `marked` = recorded), `selectedDay` = today at start |
| D-03 horizontal scroll, pinch, about 2-3 h visible, centre cursor, seek on release | ✓ code | `TimelineMath.DEFAULT_SPAN_MS` = 3 h, range 15 min to 24 h; a pure pinch never seeks |
| D-04 continue across segments; pause with a hint at the end; reconnect on the live schedule from the same place; pause button; «Живой» in one action | ✓ code, robustness caveat W-2 | `tickPlaying` boundary jump / ENDED; supervisor 2/5/10/20 s × 5; `onEpoch` re-seeks the frozen position |
| D-05 muted when entering the archive and on «Живой»; background closes the session; «Продолжить» from the same place, no auto-play | ✓ code | `forceMute` on seek-from-Live and on goLive; `onStop` → `coordinator.onBackground()` → Paused(BACKGROUND), then `sup.stop("background")`; a new liveEpoch while Paused only clears the gate |
| D-06 both families; hide TUTK with a text only on evidence | ✓ code | `ArchiveSupport.transports` = {TNP, TUTK}; `BLOCK_ARCHIVE_TUTK_HIDE` fires only on Failed(NO_FRAMES), when playback never reached Playing, the link stayed healthy on the same epoch, and units kept arriving. Six negative tests. Not applied, correctly, because there is no TUTK evidence |
| D-07 the three texts in place of the timeline, «Повторить», live keeps going | ✓ code | `ArchiveTexts` constants, `ArchivePanel` 64 dp state boxes, `texts are the D-07 wording` test; plus a fourth state, CARD_ERROR «SD-карта не готова» (left to Claude's discretion) |

### Wire-format consistency with 04-RESEARCH

| Item | Research | Code | Match |
|------|----------|------|-------|
| STimeDay | u16 year (BE/LE), month, day, wday Sunday=1, h, m, s | `SdArchiveCodec.putTime/readTimeDay`, `SdTime.toWire` (`dayOfWeek % 7 + 1`) | ✓ |
| 9031/792 request | 24 B: ch 0, start, end (sec=0), event 0, status 0 | `listEventsRequest` | ✓ |
| 9032 parse | u16 count, 10 reserved, count × {STimeDay, s32 dur}; short payload → 0 entries; <12 B → malformed | `parseTnpIndex`; 472-entry/5676-B fixture | ✓ |
| 793 page | ch, total, index, endflag, count; accepted only if size/12−1 == count; u16 duration | `parseTutkIndexPage`; repository loops until endflag or total reached, total 0 means empty | ✓ |
| 9030 | `[uc, res, 1, 0] + STimeDay` BE 12 B | `tnpPlayRecord` | ✓ |
| 12718/794 | 24 B: ch, cmd 16/1, param, STimeDay or zeros, uc at [20] | `playControl`, `playControlType(encrypted)` | ✓ |
| Pause | 767 + stop record | `PlaybackCommands.pause` | ✓ |
| Live | stop record + 9029 / 8190\|511, new uc | `PlaybackCommands.live` | ✓ |
| 817 | [0] iface, [2] tfstat, [8] hw, isUtc [9] or [14] when hw==1, total [40], free [44] | `parseDeviceInfo`, `sdStatusOf` | ✓ |
| TNP channels | 4/5/7 readers, limits 1 MiB / 300 KiB / 1 MiB; ch0 200 KiB | `TnpCodec.maxDataSize`, `READER_CHANNELS` 0..7 | ✓ |
| useCount | one link-wide counter, equality on the low byte, gate before the command | TNP/TUTK `nextUseCount`, `FrameAssembler.expectGeneration`, `DefaultMediaSession` tee | ✓ |
| Commands after burst / version check | Pitfall 6 | archive sends only after liveEpoch > 0 (link Streaming); TNP `sendIoCtrl` refuses before the burst | ✓ |

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `archive/SdArchiveCodec.kt` (247 l) | ✓ VERIFIED | Byte layer; 21 byte-fixture tests |
| `archive/SdTime.kt` (95 l) | ✓ VERIFIED | UTC wire seam; DST day bounds (NY 23 h / 25 h tests) |
| `archive/SdTimeline.kt` (123 l) | ✓ VERIFIED | Merge ≤ 2 s, days, clipping, resolveSeek; 18 tests |
| `archive/SdIndexRepository.kt` (136 l) | ✓ VERIFIED | Subscribes before sending, 10 s timeout, TUTK paging; 17 tests |
| `archive/PlaybackCommands.kt`, `PlaybackController.kt` (462 l) | ✓ VERIFIED | State machine; 10 + 28 tests |
| `archive/SdTypes.kt` | ✓ VERIFIED | Port, statuses, ArchiveSupport |
| `live/SupervisorArchivePort.kt`, `LiveSessionSupervisor.kt` v0.3.0 | ✓ VERIFIED / WIRED | `archivePort()` used by `LiveViewModel.open`; mode-aware stall |
| `live/DefaultMediaSession.kt`, `stream/FrameAssembler.kt`, `FramePipeline.kt` | ✓ VERIFIED / WIRED | Generation gate + FrameObservation (recorded = liveFlag bit 0) |
| `p2p/tnp/TnpCameraLink.kt`, `p2p/tutk/TutkCameraLink.kt` | ✓ VERIFIED / WIRED | sendIoCtrl, ioCtrlReplies, nextUseCount, setStreamMode |
| `ui/live/ArchiveCoordinator.kt`, `ArchivePanel.kt`, `TimelineRuler.kt`, `TimelineMath.kt`, `CalendarGrid.kt` | ✓ VERIFIED / WIRED | Coordinator created per open in `LiveViewModel.open`; panel and dialog rendered by `LiveViewScreen` |
| `ui/live/SdProbe.kt` | ✓ (debug only) | 04-04 probe over `ProbeArchivePort` |
| GRACE docs | ✓ | `M-SD-ARCHIVE` in knowledge-graph (11 refs) and development-plan (12); `V-M-SD-ARCHIVE` in verification-plan |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| LiveViewModel.open | ArchiveCoordinator | `sup.archivePort(transport, p2pEncrypt)`, `coord.start()`, `coord.ui.collect → _archive` | ✓ WIRED |
| LiveViewScreen | ArchivePanel / DayPickerDialog / overlay | `viewModel.archive.collectAsState()`, `archiveSeek/TogglePause/GoLive/Retry/OpenDays/SelectDay` | ✓ WIRED |
| ArchiveCoordinator | SdIndexRepository | `load()` on the first liveEpoch > 0, on retry, on openDays when the index is stale | ✓ WIRED (refresh coverage thin, W-1) |
| PlaybackController | link | `port.expectGeneration` → `port.setStreamMode` → `port.sendIoCtrl` (PlaybackCommands) | ✓ WIRED |
| SupervisorArchivePort | live link / media session | `liveLinkOrNull()?.sendIoCtrl / nextUseCount`, `sessionOrNull()?.expectGeneration / lastFrame / framesIn` | ✓ WIRED |
| TNP/TUTK replies | repository | link `ioCtrlReplies` → supervisor `_replies` (forwarded per watched link) → `exchange()` | ✓ WIRED |
| Recorded frames | decoder | TNP ch4/5/7 → `onVideoBody` → `videoSink` (tee) → FramePipeline; TUTK same AV channel | ✓ WIRED (device-unproven) |
| onStop | Paused(BACKGROUND) | `coordinator.onBackground()` before `sup.stop("background")` | ✓ WIRED |

### Data-Flow Trace (Level 4)

| Artifact | Data | Source | Real data | Status |
|----------|------|--------|-----------|--------|
| DayPickerDialog dots | `ui.recordedDays` | 9032/793 reply → fromEntries → daysWithRecordings | Yes (camera reply) | ✓ FLOWING (no recordings on device yet) |
| TimelineRuler segments | `ui.segments` | same index, `segmentsForDay(selectedDay)` | Yes, but today's segments are a load-time snapshot | ⚠️ FLOWING, stale (W-1) |
| Cursor / «Запись HH:mm:ss» | `cursorMs` | `lastFrame().timestampSec` of the gated recorded generation | Yes | ✓ FLOWING |
| Empty-state text | `panel` | 817 tfstat / index result | Yes (device: tfstat=5 → «Нет SD-карты») | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Unit suite incl. 200+ archive / link / supervisor / UI-logic tests | `tools/gradle.ps1 :app:testDebugUnitTest … --rerun` | 903 tests, 0 failed, 1 skipped | ✓ PASS |
| Debug APK builds | same invocation, `:app:assembleDebug` | BUILD SUCCESSFUL, APK 12:20 | ✓ PASS |
| Device no-card state | 04-DEVICE-VERIFICATION H-SD6 | «Нет SD-карты», `index=skipped` | ✓ PASS (taken from the device record, not re-run) |
| Live playback / seek on real recordings | — | needs a microSD card | ? SKIP → human |

### Anti-Patterns Found

No TODO / FIXME / placeholder / empty-handler patterns in the phase files. `emptyList()` / `emptySet()` initial values are overwritten by the load path, so they are not stubs. Logs are value-free (counts, types, booleans, tfstat, durations); `IndexResult.Ok.toString()` prints only the count; `ArchiveUi.toString()` omits times; `PlaybackCmd.toString()` prints only type and size. RealLogScanTest passed over the 4645-line device log.

## Findings (by severity)

### W-1 (Warning, medium): the index goes stale during a session, so today's recent footage cannot be reached by drag
- **Where:** `ui/live/ArchiveCoordinator.kt`. The index is loaded only on the first liveEpoch (and on later epochs only while LOADING or LIST_FAILED), on «Повторить», and in `openDays()` when older than `refreshMs` = 60 s (line 243). There is no refresh on entering the archive, after a reconnect, or periodically while the TIMELINE is visible. Research Pattern 4 recommended all three (the vendor polls every 60 s).
- **Effect:** today's timeline stops at the load time. `SdTimeline.resolveSeek` clamps any target at or after the last known end to `last.end − 6 s` (line 119). A user who opened the screen 30 min ago and drags to 5 min ago is sent back to roughly 30 min ago, unless they first open and close the day dialog. That weakens SD-02/SD-03 for the most common case (recent events on today).
- **Fix:** refresh the index (`launchLoad(full = false)`) when it is older than `refreshMs` and (a) the first seek from Live happens, (b) a seek on today targets beyond `all.last().endMs`, then resolve after the refresh, (c) a new liveEpoch arrives, and/or (d) every 60 s while the panel is TIMELINE. Add a test in `ArchiveCoordinatorTest`.

### W-2 (Warning, medium): unbounded reconnect loop when archive silence falls outside a known segment
- **Where:** `archive/PlaybackController.kt` `tickPlaying` (lines 407-420) and `tickStarting` (384-392), plus `live/LiveSessionSupervisor.kt` `tick` (lines 594-602).
- **Mechanism:** ENDED requires `nearEnd`, which requires the position to be *inside* a known segment. Once the position reaches or passes `last.endMs` (typically because of W-1: playback continued past the stale end and the recording then stops), `seg == null` and `next == null`, so nothing ever fires. The start timeout counts only while units of any generation arrive, so a camera that goes fully silent after a seek never reaches Failed(NO_FRAMES). In both cases the supervisor's 35 s ARCHIVE stall closes the link. But `w.healthy` is already true after 30 s of Streaming, so `attempt` resets to 1 and the retry budget never exhausts. The next liveEpoch re-seeks the same position, and the cycle repeats indefinitely: «Подключение…» about every 37 s on a frozen frame.
- **Tests:** `mid-segment silence waits for the stall and reconnect` and `camera loss while starting … waits for the stall` assert the first hop only. No test covers repeated hops or a position past the last segment.
- **Fix (one or more):** treat `pos >= last.endMs && next == null` as end-near; count consecutive re-seeks of the same position without a recorded key frame and after 2 go to Paused(ENDED) or Failed(NO_FRAMES); or do not mark a link healthy when it ends with an ARCHIVE stall.

### I-1 (Info): a failed start leaves a frozen frame instead of live
`tickStarting` timeout → `sendPause()` → Failed(NO_FRAMES): the camera is told to stop, so live does not resume until the user taps «Живой» or «Продолжить». The hint «Не удалось начать воспроизведение» is shown. This is acceptable, but D-07's "live keeps going" spirit could suggest going live automatically. That is a product choice.

### I-2 (Info): wire-time base is an assumption
`SdTime.WIRE_IS_UTC = true` follows the research's reading of the vendor code for gw-us; the single seam exists. A 3 h offset (Moscow) would place segments and seeks wrongly. H-SD3 decides it.

### I-3 (Info): TUTK live regression not re-checked on the device
04-02 changed the TUTK link (rxc publishes every reply, 793 paging, the -20012 limit only in LIVE, a shared useCount). It is JVM-tested (46 tests), but the TUTK camera was unreachable on 2026-10-31. TNP live with the new ch4/5/7 readers *was* re-checked (LAN Live, background resume in 0.6 s).

### I-4 (Info): no explicit decoder reset per seek
The design relies on the generation gate and re-arming on a key frame (the assembler disarms on a new uc) rather than resetting MediaCodec as the research suggested. This is fine for H.264/H.265 in principle; H-SD3 should watch for smearing right after a seek.

## Human Verification Required

These checks need a camera with a microSD card that has recorded for at least 1 h, plus the TUTK camera powered on. They are listed in the frontmatter and in `04-HUMAN-UAT.md`: H-SD1, H-SD2, H-SD3 (+ H-SD3b for W-1), H-SD4 (include the W-2 check: no reconnect loop at the end of the last recording), H-SD5, H-SD6 (empty-day and list-failure states), H-SD7, H-SD8, H-SD9 (TUTK archive + TUTK live regression), H-SD10. After each session, pull `files/diag_log.txt`, run `RealLogScanTest -PredactionLogFile=…`, and check the markers `BLOCK_SEEK`, `BLOCK_PLAYBACK_STATE`, `BLOCK_STREAM_MODE`, `BLOCK_STALL`, `BLOCK_FETCH_INDEX`.

## Gaps Summary

There is no code gap that blocks the phase goal: every requirement has a substantive, wired, behaviour-tested implementation that matches the vendor wire format. The phase cannot be `passed` because SD-01..SD-04 (and two of the three SD-05 states) have never run against a camera with real recordings. That is a hardware/coverage gap, not a defect. W-1 and W-2 are real robustness defects in edge paths (a stale index and silence outside known segments). They are recommended fixes before the human UAT; otherwise H-SD3b and H-SD4 will show whether they bite on real hardware.

---

_Verified: 2026-10-31T09:30:00Z_
_Verifier: Claude (gsd-verifier)_
