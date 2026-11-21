# Phase 4: Архив SD-карты - Research

**Researched:** 2026-10-30
**Domain:** Undocumented Yi SD-card playback protocol over the two P2P transports we already drive (TNP = CS2 PPPP, TUTK = IOTC/AV): recording index, start-at-time / seek, pause / resume, return to live, SD status; timeline mapping; reuse of the Phase 2/3 frame pipeline.
**Confidence:** MEDIUM-HIGH for byte layouts and vendor call order (read in the git-ignored decompile of Yi Home 6.9.8, cross-checked with a public pcap of the official 6.9.7 client); MEDIUM for camera-side behaviour that only a device can show (gaps, end of record, audio in playback); LOW for which of the user's cameras has an SD card (not knowable offline).

Evidence tags: **VERIFIED** = read this session in the decompile (`_re/jadx2|6|7|8`, plus `com.xiaoyi.yiplayer.f` decompiled this session from `classes9.dex` into the session scratchpad, outside the repo) or in the public trace; **PUBLIC** = Dmxsir/yi-cam-integration docs (MIT, pcap of the official client); **ASSUMED** = inference, must be confirmed on the device. No secret values (DID, uid, passwords, InitString, tokens) appear here.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
#### Вход и компоновка (SD-01, SD-02, SD-04)
- **D-01:** Архив живёт на экране камеры, под видео, как в официальном приложении: шкала времени, кнопка выбора дня и кнопка «Живой». Отдельного экрана «Архив» нет; переключение Live и архива не покидает экран. Видеослой, снимок, полный экран и жесты из Phase 3 работают и в архиве.

#### День и шкала (SD-01, SD-02, SD-03)
- **D-02:** День выбирается кнопкой «Сегодня ▾» с диалогом-календарём. Дни с записями отмечены точкой, дни без записей недоступны. По умолчанию выбран сегодняшний день.
- **D-03:** 24-часовая шкала прокручивается по горизонтали и масштабируется щипком. В поле зрения порядка 2-3 часов, записи показаны цветными отрезками, указатель текущей позиции по центру. Перетаскивание шкалы - перемотка, на отпускание воспроизведение стартует с выбранного момента (SD-03).

#### Поведение воспроизведения (SD-03, SD-04)
- **D-04:** Конец отрезка - переход к следующему без остановки. Конец последнего отрезка - пауза с подсказкой. Обрыв связи - повтор по графику live (2 / 5 / 10 / 20 с, до 5 попыток) с того же места записи. Пауза и продолжение - кнопкой; «Живой» возвращает к live одним действием.
- **D-05:** Звук в архиве по тем же правилам, что в live: при открытии и при каждом входе в архив выключен, та же кнопка. Сворачивание, блокировка и уход с экрана закрывают сессию сразу (D-10 Phase 3); при возврате архив сам не продолжается, показывается «Продолжить» с того же места.

#### Охват камер и пустые состояния (SD-05)
- **D-06:** Цель - оба семейства, TNP и TUTK. Если протокол одного семейства не поддаётся разбору за разумные усилия, выпускается работающее, у второго архив скрыт с понятным текстом, фаза закрывается с оговоркой. Фазу не блокируем.
- **D-07:** Нет SD-карты, нет записей за день, список не получен: понятный текст на месте шкалы («Нет SD-карты», «За этот день записей нет», «Не удалось получить список» с кнопкой «Повторить»). Видео live при этом продолжает идти. Никаких пустых шкал и зависаний (SD-05).

### Claude's Discretion
- Визуальное оформление шкалы и календаря, цвета отрезков, тексты, точный масштаб по умолчанию, размеры кнопок.
- Как именно запрашивать индекс (по дням или целиком) и как кэшировать его в сессии - по результатам исследования протокола.
- Поведение зума видео в архиве (то же, что в live).

### Deferred Ideas (OUT OF SCOPE)
- События с SD как второй источник ленты (SD-06), скачивание клипа (SD-07), скорость воспроизведения (SD-08) - v2.
- PTZ для поворотных камер (CTRL-01) - отдельная фаза по запросу.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SD-01 | Day picker shows which days have SD recordings | Index request 9031/9032 (TNP) or 792/793 (TUTK) for the last 30 days in one request (§Protocol P2, P3); days derived client-side from segments in phone local time (§Pattern 4) |
| SD-02 | 24 h timeline with recorded segments | Same index: entries are `(STimeDay start, duration s)` merged runs (public trace: 472 entries for 30 days in one 5676-byte reply) (§P2, P3) |
| SD-03 | Play from a chosen moment, seek on the timeline | TNP 9030 `[useCount, res, 1, 0, STimeDay]`; TUTK 12718/794 `PlayRecord(cmd 16, STimeDay, useCount)`; each seek = new useCount on the SAME session; frames on TNP ch4/ch5 (and ch7), on TUTK the same AV channel (§P4, Pattern 2) |
| SD-04 | Pause, resume, back to live | Pause = 767 + stop-record (12718/794 cmd 1); resume = seek to the last frame time with a new useCount; live = stop-record + 9029 (TNP) / 8190|511 (TUTK) with a new useCount, no reconnect (§P5, P6) |
| SD-05 | Clear state for no SD card / no recordings | 816 → 817 `tfstat` byte 2: 5 = no card (vendor hides SD); 2/3/9, 8, or 0 with total 0 = card problem; index count 0 = no recordings; request timeout = "Не удалось получить список" (§P1, Pattern 5) |
</phase_requirements>

## Summary

The SD archive needs no new transport, no new native call and no new crypto. In the official client the archive runs **on the same P2P session as live** and is driven by four or five extra IOCTRL commands on the command channel; recorded frames come back with exactly the same 24-byte frame head, the same useCount generation byte and the same I-frame AES rule as live, and the vendor even routes them through the same Java method (`TnpCamera.handleVideoFrame` is called for ch2/ch3 live and ch4/ch5 record; its `isLive` argument is unused). The only differences are: (1) TNP recorded video arrives on PPPP channels **4 (I) / 5 (P)** (and 7 for the "new" channel layout, as 6 is for live), which our link does not read yet; TUTK recorded video arrives on the **same AV channel** through the same `avRecvFrameData2` loop; (2) the frame head byte 3 (`liveFlag`) has bit 0 = 1 for recorded frames; (3) the frame head timestamp (seconds) is the recording time, which drives the timeline cursor.

Commands (all VERIFIED in the vendor code): **index** TNP 9031 → 9032 (`SMsgAVIoctrlListEventReq` 24 B → `SMsgAVIoctrlTnpListEventResp`: u16 count, 10 reserved, count × {STimeDay 8 B, s32 duration seconds}), TUTK 792 → 793 (same 24-byte request, paginated replies with `endflag`, 12-byte entries {STimeDay, event, status, u16 duration}); **start / seek** TNP 9030 (12 B: `[useCount, resolution, 1, 0]` + STimeDay), TUTK 12718 (encrypted cameras; 794 plain) `SMsgAVIoctrlPlayRecord` 24 B with command 16 and the useCount in byte 20; **stop record** 12718/794 with command 1 on both transports; **pause** = 767 + stop record; **resume** = seek to the last received frame time; **live** = stop record + 9029 (TNP) or 8190/511 (TUTK) with a new useCount. **SD status** comes from device info 816 → 817, `tfstat` = byte 2 (5 = no card). The public pcap of the official 6.9.7 client on a TNP camera shows exactly these commands at startup (9031 first, 12718 with a 24-byte payload, 816 → 817) and a 9032 reply of 5676 bytes = 12 + 472 × 12, which confirms the index layout byte for byte.

Time on the wire: for accounts outside the CN/JP app regions (the user is on gw-us), the vendor's conversions reduce to **"STimeDay fields = UTC broken-down time"** in both directions, regardless of the camera's `is_utc_time` flag; the frame-head timestamp is used as epoch seconds directly. Implement this with `java.time` in UTC instead of copying the vendor's DST-fragile calendar arithmetic, and confirm on the device with one probe line.

**Feasibility:** TNP HIGH (every byte known, public capture agrees, same session/pipeline; work = 3 more reader channels + a command/reply port + archive state machine). TUTK MEDIUM-HIGH (bytes known; extra care: paginated 793 replies in 1024-byte IOCTRL buffers, and the link's `-20012` no-data limit must not kill the session while paused or between records). The real blocker is not protocol but hardware: **we do not know which camera has an SD card** (the official app shows the SD tab greyed on «Спальня»). A 816 probe answers that in one phone run.

**Primary recommendation:** Plan order = (0) value-free device probe (816 tfstat on both cameras + 9031/792 count) → (1) pure-Kotlin `SdArchiveCodec` + archive state machine with byte-fixture tests → (2) `CameraLink` port extension (generic IOCTRL request/reply, `setPlayback`, `expectedUseCount`) implemented in TNP first (ch4/5/7 readers) → (3) archive UI on `LiveViewScreen` → (4) TUTK adapter for the same port. Ship TNP alone if the TUTK camera has no SD card or misbehaves (D-06).

## Standard Stack

### Core (all already in the project; nothing new to add)
| Component | Version | Purpose | Why |
|---|---|---|---|
| Vendor `libPPPP_API.so` via `PpppNative` | Yi Home 6.9.8, pinned by SHA-256 | TNP transport; `PPPP_Read` on ch4/5/7 for recorded video | Same session, same calls as live (VERIFIED `TnpCamera.startThread` starts ch4/5/7 readers at connect) |
| Vendor `libAVAPIs.so` via `TutkNative` | same APK | TUTK: `avSendIOCtrl` / `avRecvIOCtrl` / `avRecvFrameData2` | Playback frames come on the same AV channel (VERIFIED `TutkCamera.ThreadRecvVideo`) |
| `FrameHeadCodec`, `FrameAssembler`, `IFrameDecryptor`, `NalUtils`, `FramePipeline` | project | Recorded frames | Same head, same useCount byte, same I-frame AES (VERIFIED `handleVideoFrame` shared) |
| `VideoDecoder` (MediaCodec on TextureView), `AudioPipeline` | project | Decode | Unchanged; reset on every seek/mode switch |
| `java.time` (`Instant`, `ZoneOffset.UTC`, `ZoneId.systemDefault()`, `LocalDate`) | JDK / API 26+ | STimeDay <-> epoch, local day buckets | Deterministic, testable with an injected `ZoneId`; avoids the vendor's `Calendar` offset hacks |
| `java.nio.ByteBuffer` | JDK | BE (TNP) / LE (TUTK) layouts | Same as `IoCtrlMessages` today |
| Jetpack Compose `Canvas` + `detectTransformGestures` / `detectHorizontalDragGestures` | existing Compose BOM | Timeline (scroll, pinch, centre cursor) | No timeline library needed; 24 h ruler is ~200 lines |
| Material3 `DatePicker` (`DatePickerDialog`, `SelectableDates`) | existing Material3 | D-02 calendar, disabled days without recordings | `SelectableDates.isSelectableDate(utcMillis)` gives "days without recordings unavailable" for free; the dot needs a custom day cell or a simple custom grid (see Open Question 5) |

### Alternatives considered
| Instead of | Could use | Tradeoff |
|---|---|---|
| Old index path 9031/792 for 30 days | "New SD" path 5136 (days) + 5138 (segments per day) | Vendor uses the new path only when the bundled model feature `newSdPlayback == 1` (models r44a, y624, y625) or for "Tx" devices. Not needed unless the probe shows 9031/792 unanswered. Layouts recorded below for completeness |
| Client-side UTC conversion | Literal port of `mc.b0.e/f` and `STimeDay(long)` | The vendor shifts epoch by the phone offset and then breaks it down in the phone zone: equal to UTC fields except near DST changes. Port the intent, not the bug |
| One new screen for the archive | — | Locked out by D-01 |

**Installation:** none (no new dependencies).

## Protocol (what the planner needs byte for byte)

Byte order: TNP = big-endian, TUTK = little-endian (`AntsCamera.isByteOrderBig()`: type 2/3 BE, 0/1 LE; VERIFIED). All TNP commands go through the existing `TnpCodec.commandUnit` (8-byte TNPHead + 40-byte IOCtrlHead with per-command auth); all TUTK commands through `avSendIOCtrl(av, type, payload)`. Replies: TNP ch0 `TnpCodec.parseIoCtrlReply` (authResult rule applies to every reply, as now); TUTK `avRecvIOCtrl` reader `tutk-<n>-rxc` (1024-byte buffer, same as the vendor).

### STimeDay (8 bytes, both transports) — VERIFIED `AVIOCTRLDEFs.STimeDay`
```
[0..2) u16 year (BE for TNP, LE for TUTK)  [2] month 1..12  [3] day 1..31
[4]    wday = java.util.Calendar.DAY_OF_WEEK (1 = Sunday .. 7 = Saturday)
[5] hour 0..23  [6] minute  [7] second
```
Vendor builds it from millis with `GregorianCalendar(TimeZone.getDefault())` after shifting the millis by the conversion below; parses replies the same way.

### P0. Time convention (VERIFIED code, ASSUMED camera semantics)
- `mc.b0.f(t, isUtc)` / `wn.g.b(t, isUtc)` (before sending): `t - phoneZoneOffset(t) + U*3600000`; `mc.b0.e` / `wn.g.a` (after parsing): the inverse. `U = 0` when the camera reports `is_utc_time == 1` (and not `hardware_version == 0 && interface_version < 4`), else `U = w9.n.e`, which is 8 or 9 only for the CN/JP app regions and **0 for every other region** (`w9.d` lines 103-108).
- So for this user (gw-us) `U = 0` always, and "shift by -offset, then break down in the phone zone" = **STimeDay fields are the UTC broken-down time** of the epoch instant; replies are parsed the same way (fields are UTC). Request side of the index uses `Calendar.getInstance(TimeZone.getTimeZone("gmt"))` explicitly (VERIFIED `SMsgAVIoctrlListEventReq.parseConent`), consistent with UTC.
- Frame head `timestamp` (u32 seconds, offset 12) is put on the timeline as `ts * 1000` without conversion (VERIFIED `yiplayer.f` line ~2262 `X(26, timeStamp)`, `CameraPlayerFragment.u()`): epoch seconds.
- Recommendation: `SdTime.toWire(epochMs): STimeDay` = UTC fields (wday from `DayOfWeek` mapped Sunday=1); `SdTime.fromWire(STimeDay): epochMs` = UTC. Keep a `deviceUtcOffsetHours: Int = 0` parameter as a seam (CN/JP), not exposed. **Device check:** seek to the start of a known segment and compare the first record frame's `timestamp` with the requested epoch (log `deltaS` only).
- Leap-year quirk: vendor subtracts one day for February dates when the cloud flags `leapYearFlag`/`sdIndexLeapYearFlag` are set (firmware bug workaround; flags default false; their setter is outside the decompiled dexes). Ignore; note as a known vendor workaround.

### P1. SD card status: 816 → 817 (both transports) — VERIFIED
- Request 816 (`CameraCommandHelper.getDeviceInfo`), payload 4 zero bytes (`SMsgAVIoctrlDeviceInfoReq`). Reply 817 (`SMsgAVIoctrlDeviceInfoResp.parse`, public trace: 344-byte payload on a TNP y291ga).
- Offsets used by the archive: `[0]` interface_version, `[2]` **tfstat**, `[8]` hardware_version, `[40..44)` s32 **total**, `[44..48)` s32 free (transport byte order; unit not stated in code — ASSUMED MB), is_utc_time at `[9]` when hardware_version != 1, at `[14]` when hardware_version == 1 (the v2 layout).
- Vendor interpretation (`CameraPlayerFragment` ~6830, `yiplayer.f.O0()/P0()`): `tfstat == 5` → no SD card, SD tab hidden/disabled; `tfstat in {2, 3, 9}`, `tfstat == 8`, or `tfstat == 0 && total == 0` → card present but abnormal (vendor prompts check/format); otherwise (e.g. `0` with `total > 0`) → normal. The meaning of the individual non-5 codes is ASSUMED; the branching is VERIFIED.
- The model feature `sdcardSupport` (bundled `assets/feature_config/config_<model>.json`) is 0 for d201, n10, n20, n30, w10, y32, y32gc; default 1.
- The official client sends 816 in its startup batch on TNP (PUBLIC trace) — sending it is vendor-normal.

### P2. TNP index: 9031 → 9032 — VERIFIED (`CameraCommandHelper.getTnpEvents`, `AVIOCTRLDEFs`), PUBLIC sizes
Request payload (24 B, BE):
```
[0..4)   u32 channel = 0
[4..12)  STimeDay start  (UTC fields of startMs, second forced to 0)
[12..20) STimeDay end    (UTC fields of endMs,   second forced to 0)
[20]     event  = 0 (EVENT_ALL)
[21]     status = 0 (STATUS_UNREAD)
[22..24) 0
```
Vendor range: `now - 720 h` .. `now` (30 days), one request; repeated about every 60 s while the player is open (first after 1 s). Reply payload:
```
[0..2)   u16 count        (vendor: if payload < 12 + count*12 -> treat as 0 entries)
[2..12)  reserved
[12 + 12*i ..) entry i: STimeDay start (8) + s32 duration seconds (4)
```
Public trace (official 6.9.7, TNP y291ga): request 72 bytes on the wire (8 + 40 + 24), reply 5724 bytes = 8 + 40 + 5676, and 5676 = 12 + 472 × 12. Entries are merged recording runs, not per-minute files, so 30 days fit one reply (ch0 vendor max 204800 B ⇒ up to 17065 entries). Reply command number equals the request's (useful only for logging; vendor correlates by reply type).

### P3. TUTK index: 792 → 793 (paginated) — VERIFIED (`getTutkLangtaoEvents`)
- Same 24-byte request as P2, little-endian.
- Each reply page (≤ 1024 B, the IOCTRL buffer):
```
[0..4) s32 channel  [4..8) s32 total (all pages)  [8] index  [9] endflag (1 = last page)
[10] count (entries in this page)  [11] reserved
[12 + 12*i ..) SAvEvent: STimeDay (8) + [8] event + [9] status + [10..12) u16 duration seconds
```
- Vendor accepts a page only if `payload.size / 12 - 1 == count`; `total == 0` ⇒ empty list (done); keeps waiting for 793 until a page with `endflag == 1`; completes the list when the accumulated entry count equals `total`. Duration is u16 here (s32 on TNP).

### P4. Start playback / seek
**TNP 9030 `IOTYPE_USER_IPCAM_TNP_START_RECORD`** — VERIFIED (`AntsCameraTnp.doSeekTo`, `SMsgAVIoctrlTnpPlayRecord.parseContent`):
```
payload 12 B: [0] useCount (after ++), [1] resolution (current live resolution code), [2] 1 (version), [3] 0,
              [4..12) STimeDay (BE) of the target time (UTC fields, P0)
```
Sent as a plain command on ch0; the vendor does not wait for a reply (none registered). Every seek is a new 9030 with `++useCount`; frames whose head useCount != current are dropped (P7).

**TUTK 12718 `RECORD_PLAYCONTROL2`** (when `p2pEncrypt`, 794 `RECORD_PLAYCONTROL` otherwise) — VERIFIED (`AntsCameraTutk.doSeekTo`, `SMsgAVIoctrlPlayRecord.parseContent(int,int,int,byte[],byte,boolean)`):
```
payload 24 B LE: [0..4) channel 0  [4..8) command 16 (start/seek)  [8..12) param 0
                 [12..20) STimeDay (LE)  [20] useCount (after ++)  [21..24) 0
```
Same rule: one command per seek, new useCount. (A reply 795 type exists; the vendor ignores it.)

### P5. Stop record / pause / resume — VERIFIED
- **Stop record (both):** 12718 if `p2pEncrypt` else 794, payload 24 B in transport order: channel 0, **command 1**, param 0, STimeDay all zero, byte 20 = 0 (`TnpCamera.sendStopRecordVideoCommand`, `TutkCamera.sendStopRecordVideoCommand`). PUBLIC: the official TNP client sends a 24-byte 12718 in its startup batch and again later.
- **Pause:** TNP `pause()` = 767 STOP (8 zero bytes) + stop record. TUTK `stopShow()` = stop the video/audio reader threads + 767 + stop record. UI keeps the last frame.
- **Resume:** `sendStartPlayCommand()`: if not live → `seekTo(lastFrameTime)` i.e. a new 9030 / 12718-cmd-16 at the timestamp of the last received record frame (vendor `mLastAVFrameTimeStamp`), with a new useCount; TUTK also restarts its reader threads (`startShow`).

### P6. Return to live (same session, no reconnect) — VERIFIED
`CameraPlayerFragment.s6()` / `yiplayer.f.M0()`: `stopRecordPlay()` then `goLive()`:
- TNP: stop record (12718/794 cmd 1), then **9029** `[++useCount, resolution, 1, 0]` (no 4881, no 768 again).
- TUTK: stop record, then **8190** (encrypted) / **511** `u32 LE ++useCount`.
- Vendor shortcut: a seek to a time within the last 10 s goes live instead (`u6()`); the newer player clamps a seek past the newest segment end to `end - 6 s` (`yiplayer.f.i()`).

### P7. Recorded frames — VERIFIED
- TNP channels: 4 = record I-frames (max 1 048 576 B), 5 = record P-frames (307 200 B), 7 = "new record" (1 048 576 B, the counterpart of live ch6). The vendor starts readers on all ten channels at connect (`startThread`), not on demand.
- TUTK: same `avRecvFrameData2` loop, same AV channel 0.
- Frame head identical (24 B); `liveFlag` byte 3: bit 0 = 1 → recorded (`AVFrame.isLive() = (liveFlag & 1) == 0`), bit 1 = privacy mode. I-frames decrypted with the same key rule when `p2pEncrypt` (`handleVideoFrame` shared by ch2/3/4/5). P-frame reorder by seq, queue cap, useCount gating: identical.
- Vendor useCount gate, two layers: `TnpCamera` drops frames with `useCount < current` (signed byte compare) and P-frames of the new generation until its first I-frame; `AntsCamera` handler then drops anything with `useCount != current`. Recommend the equality form (robust to byte wrap).
- Timeline cursor = head `timestamp` seconds of the last rendered frame (vendor updates the bar at most once per second).
- Audio during playback: ASSUMED on ch1 like live (no separate record-audio channel exists); confirm on device. D-05 keeps it muted by default anyway.

### P8. End of segment / end of record / errors — VERIFIED vendor behaviour, ASSUMED camera behaviour
- The vendor sends **no** command at segment boundaries: it relies on the camera to continue into the next recording file. Watchdog in `yiplayer.f`: every record I-frame re-arms a 30 s timer (`w0`); when no I-frame arrives for 30 s during playback the player shows "no more recordings" (event 29) and calls `stopPlay()`. Older `CameraPlayerFragment`: 30 s no-frame → treated as `-3003` error.
- No dedicated "no SD" / "no recording" IOCTRL error exists in the vendor code for 9030 / 12718; empty states come from 817 tfstat and from the index (count 0).
- Day crossing during playback: `yiplayer.f.x0` re-selects the day in the UI when the playing time passes midnight (3 s after each record I-frame).

### P9. "New SD" index (only for newSdPlayback models; record, do not build) — VERIFIED layouts
- 5136 → 5137 days: request 12 B `[u32 0][s32 startSec][s32 endSec]`; reply `[s32 count]` + count × 12 B `{s32 category, s32 starttime (epoch s), s32 duration}`.
- 5138 → 5139 segments of one day: request same 12 B with `[dayStartSec, dayStartSec + 86400 (-1)]`; reply = the 9032 layout (u16 count, 10 reserved, {STimeDay, s32 duration}).

## Architecture Patterns

### Recommended structure (new files marked +)
```
app/src/main/java/com/yicamalt/
├── stream/
│   ├── CameraLink.kt          # port: + sendIoCtrl / ioCtrlReplies / setPlaybackMode / currentUseCount (default bodies)
│   ├── SdArchiveCodec.kt      # + pure: STimeDay, 9031/792 request, 9032/793 parse, 9030 / PlayRecord build, 817 tfstat
│   ├── SdTime.kt              # + pure: epoch <-> wire (UTC + offset seam), local day buckets (ZoneId injected)
│   ├── FrameAssembler.kt      # + expectGeneration(useCount): drop units of any other generation (vendor equality gate)
│   └── TnpCodec.kt            # + CH_RECORD_I 4, CH_RECORD_P 5, CH_NEW_RECORD 7 in maxDataSize
├── p2p/tnp/TnpCameraLink.kt   # READER_CHANNELS 0,1,2,3,4,5,6,7; generic command queue; one link-wide useCount
├── p2p/tutk/TutkCameraLink.kt # generic IOCTRL send/recv routing; paused flag suspends the -20012 limit
├── archive/                   # + M-SD-ARCHIVE (pure Kotlin, JVM-tested)
│   ├── SdIndexRepository.kt   # request + cache index per session (30 days), timeout, retry
│   ├── SdTimeline.kt          # segments per local day, gap snap, next-segment lookup, "days with recordings"
│   └── PlaybackController.kt  # Live | Archive(Playing|Paused|Ended) state machine over the CameraLink port
├── live/LiveSessionSupervisor.kt  # stall watchdog mode-aware (paused: off; archive: 30 s / next-segment policy)
└── ui/live/                   # + ArchivePanel.kt (day button, timeline, «Живой»), TimelineRuler.kt (Canvas)
```

### Pattern 1: Same session, mode switch by useCount (vendor model)
**What:** Live and archive share one `CameraLink`. Every play command (9029 live, 9030 seek, TUTK 8190/511 live, 12718-cmd-16 seek, 4881 resolution) takes `++useCount` from ONE link-wide counter; the pipeline accepts only frames whose head `useCount` equals the counter's latest value; the decoder is reset at each switch.
**Why:** VERIFIED vendor design (`AntsCamera.useCount`, `addUseCount()` before each 9029/9030/4881). Today `TnpCameraLink` uses fixed 1/2 for the burst and 3.. for 4881, and `FrameAssembler` takes its generation from incoming I-frames; after a seek an in-flight live I-frame of the old generation would re-arm the gate. Fix: link owns `useCount` (Int, sent as the low byte), publishes `currentUseCount`, and `FramePipeline` calls `assembler.expectGeneration(uc)` before the command is written (so frames arriving after it are judged correctly). Compare as `(head.useCount and 0xFF) == (uc and 0xFF)`.
**Byte wrap:** the counter is a byte on the wire. After 255 commands it wraps; equality still works. Avoid vendor's signed `<` compare.

### Pattern 2: TNP readers for recorded video
Add ch4/ch5/ch7 to `READER_CHANNELS` and to `TnpCodec.maxDataSize` (4,7 → 1 048 576; 5 → 307 200). Route them to the same `onVideoBody` → `VideoUnitSink`. Keep the first-read latch counting all readers (now 8). The vendor starts these readers at connect; doing the same keeps live behaviour unchanged (they just block in `PPPP_Read` with the 300 ms timeout loop). `FrameAssembler` must accept I from ch4 and P from ch5 interleaved exactly as ch2/ch3 today (HIGH-1 fix already handles cross-thread ordering).

### Pattern 3: Generic IOCTRL request/reply on the port
Phase 4 is the first phase that needs replies other than 4882/9033. Extend `CameraLink` with default bodies (Phase 3 precedent, so fakes compile):
```kotlin
// Non-blocking; queues on the link's single writer. false if not connected/closing.
fun sendIoCtrl(type: Int, payload: ByteArray): Boolean = false
// Replies with authResult 0 (TNP) / every avRecvIOCtrl record (TUTK), excluding 4882/9033 which the link consumes.
val ioCtrlReplies: SharedFlow<IoCtrlReply> get() = NO_REPLIES   // IoCtrlReply(type: Int, payload: ByteArray), toString value-free
// Link-wide generation: next value used by the next play command; read by the pipeline gate.
fun nextUseCount(): Int = 0
val transportBigEndian: Boolean get() = true
```
`SdIndexRepository` builds payloads with `SdArchiveCodec`, sends, awaits the matching reply type with `withTimeout(10 s)` (vendor has no timeout; D-07 needs one), and for TUTK keeps collecting 793 pages until `endflag == 1` or `total` reached. One outstanding request per reply type (vendor keys pending messages by reply type). Use `MutableSharedFlow(extraBufferCapacity = 16, onBufferOverflow = DROP_OLDEST)`; reply payloads may be up to ~200 KB (TNP), copy once.
Alternative considered: dedicated `requestIndex()`/`seek()` methods per transport on the port — rejected: duplicates byte building in two links; the generic pair keeps links thin and lets `SdArchiveCodec` own bytes (testable).

### Pattern 4: Index → days → timeline (pure, `ZoneId` injected)
1. Fetch once per session when the camera screen is open and the link is Streaming: range `[now - 30 d, now]` (vendor 720 h). Refresh when the day dialog opens, when the user enters the archive, and after a reconnect; optionally every 60 s while the archive panel is visible (vendor polls 60 s). Cache in the VM for the session (process memory only; no Room — not needed and keeps nothing on disk).
2. Each entry → `Segment(startMs = SdTime.fromWire(stimeday), endMs = startMs + durationS * 1000)`; sort; merge overlaps/adjacent (< 2 s gap).
3. `daysWithRecordings` = every phone-local `LocalDate` a segment intersects (a segment crossing midnight marks both days).
4. Timeline for a day = segments clipped to `[localMidnight, nextLocalMidnight)` (23/25 h on DST days — compute from `ZonedDateTime`, never `+ 86 400 000`).
5. Gap snap on release: if the target is inside a gap, snap to the next segment start; if after the last segment end, clamp to `end - 6 s` (vendor `yiplayer.f.i()`); if within 10 s of now on today, go live (vendor `u6()`).

### Pattern 5: Playback state machine (owns D-04/D-05)
```
Live ──seek(t)──► Archive.Starting(t, uc) ──first record I (useCount==uc)──► Archive.Playing
Archive.Playing ──pause──► Archive.Paused(lastFrameMs)  [767 + stop-record]
Archive.Paused ──resume──► Archive.Starting(lastFrameMs, ++uc)  [seek]
Archive.* ──seek(t')──► Archive.Starting(t', ++uc)  [no stop needed; vendor sends 9030 directly]
Archive.* ──«Живой»──► Live.Starting(++uc)  [stop-record + 9029 | 8190/511]
Archive.Playing ──no record I-frame for N s──► next segment exists ? seek(nextStart) : Archive.Ended («Записи закончились»)
Link lost ──► supervisor reconnect (2/5/10/20 s, 5 tries) then re-issue seek(lastFrameMs)  (D-04)
Background / leave ──► session closed (Phase 3 D-10); on return show «Продолжить» with lastFrameMs (D-05)
```
- Recommended N: 8 s for "camera stalled at a boundary → try next segment" (our D-04 promise), with the vendor's 30 s as the absolute "ended" limit. ASSUMED: the camera normally crosses file boundaries by itself; the 8 s fallback only covers gaps.
- Supervisor stall watchdog (10 s, `BLOCK_STALL`) must be suspended in `Paused`/`Ended` and must not close the link while `Archive.Starting` waits for the first record frame (give it the 20 s first-frame budget).
- TUTK: while paused the link must not count `-20012` toward `maxNoDataPolls` (vendor stops its reader thread entirely during pause). Add `setPlaybackPaused(Boolean)` (or pause the reader loop) to `TutkCameraLink`.
- Audio: on every entry into archive (and on «Живой») force mute (D-05); AudioPipeline resets with the decoder.

### Pattern 6: SD status gate before the timeline
On connect (TNP after the burst, TUTK after the start command) send 816 once; parse tfstat/total; `SdStatus = NoCard (5) | Abnormal (2,3,8,9, or 0 with total 0) | Ok | Unknown (no reply in 10 s)`. `NoCard` → «Нет SD-карты»; `Abnormal` → «SD-карта не готова» (D-07 lists only three texts; Abnormal can reuse «Нет SD-карты» or a fourth text — Claude's discretion on wording); `Unknown` → still try the index; index timeout → «Не удалось получить список» + «Повторить»; index count 0 for the day → «За этот день записей нет».

### Anti-patterns to avoid
- **Reconnecting to switch Live↔Archive:** the vendor never does; it costs seconds and a session slot.
- **Porting `STimeDay(long)` + `mc.b0.f` literally:** DST-edge bugs, phone-zone coupling. Use UTC fields directly (P0).
- **Fixed useCount values per command (1, 2, 3...):** breaks the generation gate after the first seek.
- **Trusting `FrameAssembler`'s I-frame-driven generation alone:** an old-generation I-frame in flight would win.
- **Treating "no frames" as a link failure during pause/ended:** suspend the stall and `-20012` limits there.
- **Waiting for a 9030 / 12718 reply:** none is defined; progress is the first record I-frame with the new useCount.
- **Logging STimeDay values or index contents:** not secrets, but keep logs value-light: counts, durations, deltas, relative day indices.

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---|---|---|---|
| A separate playback session / transport | Second PPPP/IOTC session for SD | The existing link + IOCTRL commands | Vendor uses one session; a second may hit `-3029`/`-48` session caps and doubles connect time |
| Record frame decode path | A second pipeline | `FramePipeline` + `VideoDecoder` (+ `expectGeneration`) | Identical head/crypto/ordering (VERIFIED) |
| Time zone math | Manual `+offset` arithmetic | `java.time` (`ZoneOffset.UTC` for wire, `ZoneId.systemDefault()` for day buckets) | DST, 23/25 h days |
| Calendar widget | Own month grid from scratch | Material3 `DatePickerDialog` + `SelectableDates` (or a minimal custom grid only if the "dot" is required visually) | Accessibility, locale |
| Paging/correlation framework | Generic RPC layer | One `awaitReply(type, timeout, isLast)` helper over `SharedFlow` | Two reply types only |
| Gesture physics for the ruler | Custom fling engine | Compose `detectTransformGestures` / `rememberScrollableState` + `animateDecay` | Pinch + pan already solved |

**Key insight:** the archive is ~90% state machine and UI; the protocol part is five byte builders and two parsers that the vendor wrote in plain Java — exactly the kind of code that is cheap to pin with JVM byte fixtures.

## Runtime State Inventory
Not a rename/refactor phase — section omitted.

## Common Pitfalls

### Pitfall 1: No SD card in the camera you test on
**What goes wrong:** the whole phase is implemented and nothing can be verified. **Why:** the official app shows the SD tab greyed on «Спальня» (CONTEXT specifics) — consistent with `tfstat == 5`. **Avoid:** plan 04-00 = probe 816 on both cameras before UI work. **Warning sign:** probe logs `tfstat=5`.

### Pitfall 2: Generation mix after a seek
**What goes wrong:** a frame or two of live (or of the previous seek position) flashes after a seek, or the decoder gets P-frames of the new generation before its I-frame (green/smeared picture). **Avoid:** Pattern 1 equality gate, set before the command is written; reset decoder + pipeline on each switch; drop P-frames of the new generation until its first I (FrameAssembler already gates on I).

### Pitfall 3: Stall watchdogs fire during pause / end of record
**What goes wrong:** supervisor `BLOCK_STALL` (10 s) or TUTK `-20012 × 300` (~9-10 s) closes the session while the user is paused. **Avoid:** mode-aware watchdogs (Pattern 5). **Warning sign:** `reason=stall` in logs right after a pause.

### Pitfall 4: Wrong time base (off by the phone UTC offset)
**What goes wrong:** segments drawn 3 h off (Moscow UTC+3), seek lands in the wrong place. **Why:** mixing "STimeDay as local" with "STimeDay as UTC". **Avoid:** P0 rule + one device check (`deltaS` between requested time and first record frame timestamp should be a few seconds, not ±10800). **Fallback seam:** `deviceUtcOffsetHours` / a boolean "wire is local time" if the probe shows a 3 h delta.

### Pitfall 5: TUTK 793 pagination and buffer
**What goes wrong:** only the first 84 entries are shown, or pages are rejected. **Avoid:** loop until `endflag == 1` or accumulated == `total`; validate `size/12 - 1 == count`; per-page timeout reset. The 1024-byte IOCTRL buffer already matches the vendor.

### Pitfall 6: Commands raced with the startup burst
**What goes wrong:** 816/9031 sent before the burst changes the header-version detection (first ch0 reply decides the version) or the first useCount. **Avoid:** send archive commands only after `burstQueued` and after the header version was confirmed (first reply processed). The official client sends 9031 first, but our link's version-detect logic resends only the start sequence — keep archive commands strictly after.

### Pitfall 7: Big TNP replies on ch0
9032 can be tens of KB (vendor max 204 800). The reader already reads exactly `dataSize`; make sure the reply copy + SharedFlow do not log payload bytes and that `RedactionScanner` fixtures are not tripped by STimeDay-like digits (they are not secrets).

### Pitfall 8: "Go live" within the last seconds / future seek
Seeking to a time newer than the last segment end or within 10 s of now: vendor goes live or clamps to `end - 6 s`. Without this the camera may return nothing and the 30 s watchdog fires.

### Pitfall 9: Relay bandwidth
Recorded streams are at the recording resolution (often HD) regardless of the `resolution` byte (ASSUMED: the byte is probably ignored for recordings). On RELAY this may stutter. Mitigation: none required for v1; show the same stall/reconnect behaviour; note in verification. Not a feasibility blocker.

### Pitfall 10: Official app in parallel
Both apps may drive playback on the same camera; the camera stamps frames with the requesting session's useCount per session (ASSUMED per-session). If the camera supports only one playback stream, the second request may preempt the first. Treat as a verification item, not a design driver.

## Code Examples

### Byte builders (our code; layouts VERIFIED above)
```kotlin
object SdArchiveCodec {
    data class STimeDay(val year: Int, val month: Int, val day: Int, val wday: Int, val hour: Int, val minute: Int, val second: Int)

    fun putTimeDay(bb: ByteBuffer, t: STimeDay) {           // bb.order() already BE (TNP) or LE (TUTK)
        bb.putShort(t.year.toShort()).put(t.month.toByte()).put(t.day.toByte()).put(t.wday.toByte())
          .put(t.hour.toByte()).put(t.minute.toByte()).put(t.second.toByte())
    }
    fun listEventsRequest(start: STimeDay, end: STimeDay, bigEndian: Boolean): ByteArray =   // 9031 / 792
        ByteBuffer.allocate(24).order(order(bigEndian)).apply {
            putInt(0); putTimeDay(this, start.copy(second = 0)); putTimeDay(this, end.copy(second = 0))
            put(0); put(0); put(0); put(0)                        // event ALL, status, reserved
        }.array()
    fun tnpPlayRecord(useCount: Int, resolution: Int, at: STimeDay): ByteArray =              // 9030
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(useCount.toByte()); put(resolution.toByte()); put(1); put(0); putTimeDay(this, at)
        }.array()
    fun playControl(command: Int, at: STimeDay?, useCount: Int, bigEndian: Boolean): ByteArray = // 12718 / 794
        ByteBuffer.allocate(24).order(order(bigEndian)).apply {
            putInt(0); putInt(command); putInt(0)                 // channel, command (16 start, 1 stop), param
            if (at != null) putTimeDay(this, at) else put(ByteArray(8))
            put(useCount.toByte()); put(ByteArray(3))
        }.array()
    const val PLAY_START = 16
    const val PLAY_STOP = 1
    private fun order(be: Boolean) = if (be) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
}
```

### Wire time (UTC) and local day buckets
```kotlin
object SdTime {
    fun toWire(epochMs: Long): SdArchiveCodec.STimeDay {
        val z = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
        val wday = z.dayOfWeek.value % 7 + 1               // java.util.Calendar: Sunday = 1 .. Saturday = 7
        return SdArchiveCodec.STimeDay(z.year, z.monthValue, z.dayOfMonth, wday, z.hour, z.minute, z.second)
    }
    fun fromWire(t: SdArchiveCodec.STimeDay): Long =
        LocalDateTime.of(t.year, t.month, t.day, t.hour, t.minute, t.second).toInstant(ZoneOffset.UTC).toEpochMilli()
    fun dayBounds(day: LocalDate, zone: ZoneId): LongRange =
        day.atStartOfDay(zone).toInstant().toEpochMilli() until day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
```

### TNP 9032 parse (vendor-tolerant)
```kotlin
fun parseTnpIndex(p: ByteArray): List<Pair<Long, Int>>? {   // null = malformed (< 12 bytes) -> "Не удалось получить список"
    if (p.size < 12) return null
    val bb = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
    val count = bb.getShort(0).toInt() and 0xFFFF
    if (p.size < 12 + count * 12) return emptyList()       // vendor: treat as zero entries
    return (0 until count).map { i ->
        val o = 12 + i * 12
        val t = SdArchiveCodec.STimeDay((bb.getShort(o).toInt() and 0xFFFF), p[o + 2].toInt(), p[o + 3].toInt(),
            p[o + 4].toInt(), p[o + 5].toInt(), p[o + 6].toInt(), p[o + 7].toInt())
        SdTime.fromWire(t) to bb.getInt(o + 8)
    }
}
```

## Feasibility and Plan Order

| Transport | Rating | Why | Residual risk |
|---|---|---|---|
| TNP («Спальня») | **HIGH** | All commands and layouts VERIFIED in vendor code; public pcap of the official client confirms 9031/9032 sizes, 12718 (24 B) and 816/817 on a TNP camera; same session/pipeline; only 3 reader channels to add | Camera may have no SD card (greyed SD tab) → cannot verify on this camera |
| TUTK («YI Home Camera») | **MEDIUM-HIGH** | Layouts VERIFIED (792/793, 12718/794, 8190/511, 816); frames on the existing AV channel | 793 pagination; pause/`-20012` handling; intermittent `-20009` on connect (already handled); no public capture of TUTK playback |

**Recommended plan order (coarse granularity):**
1. **04-00 Probe (device, small):** debug actions on the existing `LiveProbeScreen` (long press on a camera): «SD info» (816), «SD index» (9031/792), «Play last segment» (seek to newest segment start + 30 s), «Pause», «Resume», «Live». Value-free logs (next section). One phone run decides: which camera has SD; wire-time base; TNP channel used (4/5 vs 7); audio in playback; whether the camera crosses segment boundaries itself.
2. **04-01 Pure core (JVM only, parallel with 04-00):** `SdArchiveCodec`, `SdTime`, `SdTimeline`, `PlaybackController` with fake link; `FrameAssembler.expectGeneration`. GRACE: new `M-SD-ARCHIVE`, `V-M-SD-ARCHIVE`.
3. **04-02 Port + TNP link:** `CameraLink` additions (default bodies), `TnpCameraLink` ch4/5/7 readers, generic command queue, link-wide useCount; supervisor mode-aware stall. FakePpppNative scripts.
4. **04-03 UI:** `ArchivePanel` + `TimelineRuler` + day dialog + empty states under the video on `LiveViewScreen`; `LiveViewModel` archive mode (or `ArchiveViewModel` sharing the session). Device checkpoint on the camera with SD.
5. **04-04 TUTK adapter:** generic IOCTRL routing in `TutkCameraLink`, 793 paging, paused flag; device checkpoint. Skip/hide with text per D-06 if 04-00 shows no SD or the camera rejects playback.

## Which camera has an SD card (Question 6)
- **Offline: not knowable.** The app DB stores only `name / model / transport / online`; the stored `model` values for the two cameras are opaque codes ("5" for the TNP camera, "1" for the TUTK camera — read this session from the phone's app DB, no secrets touched), not model names, so the bundled `feature_config` (`sdcardSupport`, `newSdPlayback`) cannot be matched. The official-app screenshot hint (SD tab grey on «Спальня») suggests no card or `sdcardSupport = 0` there — ASSUMED.
- **Cheapest confirmation:** the 816 probe on each camera, logging `tfstat`, `totalMb`, `freeMb`, `isUtc`, `hw`, `iface`, `replyLen` (all non-secret). Also log the device-list `model` string (e.g. `y30`, non-secret) once in `BLOCK_RESOLVE_P2P_CREDENTIALS` or the camera list parser so the feature config can be matched next time.
- If both report `tfstat=5`: the phase can still build and JVM-verify everything, but SD-01..SD-05 device verification needs the user to insert a microSD card (and wait for some recording) — flag as `human_needed`.

## Device Probe Plan (Question 7)
Debug-only actions, reusing the `LiveProbe` session (one link per press). Every line value-free; markers follow `[Module][fn][BLOCK_*]`.

| Action | Sends | Log line (fields only) |
|---|---|---|
| SD info | 816 (4 zero bytes) | `[Archive][probe][BLOCK_SD_INFO] transport=TNP replyLen=344 iface=.. hw=.. tfstat=.. totalMb=.. freeMb=.. isUtc=..` |
| SD index | 9031 (TNP) / 792 (TUTK), range now-30 d..now | `[Archive][probe][BLOCK_SD_INDEX] pages=1 total=.. count=.. sumDurS=.. maxDurS=.. days=.. newestAgoMin=.. ms=..` (no absolute times) |
| Play newest | 9030 / 12718-cmd-16 at newestStart + 30 s, `uc=N` | `[Archive][probe][BLOCK_SD_FIRST_FRAME] ch=4 liveFlag=1 uc=N key=true codec=78 size=1920x1080 deltaS=.. decryptOk=true ms=..` (`deltaS` = frame ts − requested, seconds) |
| (passive) | — | `[Archive][probe][BLOCK_SD_CHANNELS] ch4=.. ch5=.. ch7=.. ch2=.. ch3=.. otherUc=..` every 5 s (counts) + `audioUnits=..` |
| Pause | 767 + 12718/794 cmd 1 | `[Archive][probe][BLOCK_SD_PAUSE] framesAfterMs500=..` (do frames stop?) |
| Resume | seek at last frame ts, `uc=N+1` | `BLOCK_SD_FIRST_FRAME ... deltaS=..` |
| Live | stop + 9029 / 8190, `uc=N+2` | `[Archive][probe][BLOCK_SD_LIVE] firstLiveMs=.. liveFlag=0 uc=..` |
| Boundary | play at `segmentEnd − 10 s` | `[Archive][probe][BLOCK_SD_BOUNDARY] lastTsBeforeGapAgoS=.. resumedAfterS=.. jumpS=..` (does the camera continue into the next segment?) |

Run once per camera; pull `files/diag_log.txt` and run `RealLogScanTest`. Decisions it unlocks: TNP channel set, time base (P0), boundary policy N (Pattern 5), audio routing, TUTK pagination real sizes.

## State of the Art

| Old assumption (docs / earlier research) | Current finding | Impact |
|---|---|---|
| "SD playback (ch4/5/7, 9030)" as a black box (02-RESEARCH deferred) | Full command set: 9031/9032, 9030, 12718/794 (cmd 16/1), 767, 9029; TUTK 792/793, 12718/794, 8190/511; status 816/817 | Planner can schedule pure-Kotlin codec work before any device run |
| Archive might need its own session or reconnect | Same session; mode switch by useCount | No connection logic changes beyond readers |
| Recorded frames might be different | Identical head, decrypt and ordering; only `liveFlag` bit 0 and channel differ | Pipeline reused; add generation gate |
| Index format unknown / maybe per-file | Merged runs, 12-byte entries, one TNP reply for 30 days | One request per session, cheap cache |
| Vendor 6.9.8 player = `CameraPlayerFragment` | The newer `com.xiaoyi.yiplayer.f` (P2PPlayer, `classes9.dex`) is what the 6.9.7 pcap traces (`J0()` → `getEvents`); both follow the same commands | Behaviour (watchdog 30 s, clamp −6 s, live within 10 s) taken from both |

## Open Questions

1. **Does either camera have an SD card?** Known: SD tab grey on «Спальня» in the official app. Unclear: TUTK camera. Recommendation: plan 04-00 probe first; if none, ask the user to insert a card (human step).
2. **Wire time base on the real cameras.** Known: vendor arithmetic ⇒ UTC fields for gw-us. Unclear: firmware behaviour of `is_utc_time == 0` cameras. Recommendation: implement UTC with a seam; verify `deltaS` in the probe.
3. **TNP ch4/5 vs ch7.** Known: vendor reads all; which one a given camera uses is unknown (live uses ch2/3 on «Спальня» per Phase 3 logs, so ch4/5 likely). Recommendation: read all three; log counts.
4. **Segment boundaries and gaps.** Known: vendor sends nothing at boundaries; 30 s no-I-frame watchdog. Unclear: whether the camera jumps over gaps itself. Recommendation: 8 s fallback "seek to next segment" + 30 s "ended"; tune after the probe.
5. **Calendar "dot" (D-02).** Material3 `DatePicker` disables days via `SelectableDates` but has no per-day badge API in the current stable release (ASSUMED from training data; check the BOM version in `app/build.gradle.kts` during planning). Recommendation: disabled = no recordings is enough to satisfy "marked", or build a small custom month grid (≈120 lines) if the dot is required.
6. **Audio in playback.** ASSUMED on ch1 with the same AES rule. Muted by default (D-05), so low risk; probe counts audio units.
7. **Parallel playback with the official app.** Unknown camera policy; verification item only.

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|---|---|---|---|---|
| Physical phone (Mi 9) over USB, `adb` | probe + checkpoints | ✓ (`adb devices` shows the phone) | adb 1.0.41 | — |
| A camera with a microSD card and recordings | SD-01..05 device verification | **unknown** | — | user inserts a card; JVM-only verification until then |
| Local APK + decompile `_re/jadx2|6|7|8` | reference | ✓ | 6.9.8 | — |
| jadx (`~/tools/jadx`, needs JDK 11+; `JAVA_HOME=E:\tools\jdk-21`) | extra decompile if needed (one dex / one class at a time) | ✓ | 1.5.6 | — |
| Gradle via `tools/gradle.ps1` | build/tests | ✓ | Gradle 8.5, AGP 8.2.2 | — |
| Python 3 | offline helpers | ✓ | 3.12 | — |

Missing with no fallback: a camera with an SD card (to be confirmed by the probe).

## Validation Architecture

> `workflow.nyquist_validation` is `false` in `.planning/config.json`; included because the orchestrator asked (same as Phase 2).

### Test framework
| Property | Value |
|---|---|
| Framework | JUnit 5 (+ vintage), MockK, kotlinx-coroutines-test 1.7.3; existing `FakePpppNative`, `FakeTutkNative`, `LiveFakes`, `RedactionScanner`, `RealLogScanTest` |
| Quick run | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.archive.*" --tests "com.yicamalt.stream.*"` |
| Full suite | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest` |
| Log sweep | `… :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=<pulled diag_log.txt> --rerun-tasks` |

### What JVM tests can prove (byte fixtures, fakes)
| Req | Behaviour | Test | Type |
|---|---|---|---|
| SD-01/02 | 9031 request bytes (BE) and 792 (LE) for fixed instants; second forced 0; wday Sunday=1 | `SdArchiveCodecTest` | unit, byte-exact |
| SD-01/02 | 9032 parse: count 0, count N, truncated payload → empty (vendor rule), < 12 B → null; a 5676-byte synthetic reply → 472 entries | `SdArchiveCodecTest` | unit |
| SD-01/02 | 793 paging: 3 pages, endflag on the last, `size/12-1 != count` page rejected, total 0 → empty | `SdIndexRepositoryTest` (fake link replies) | unit |
| SD-01 | days with recordings: segment crossing local midnight marks two days; DST day (23/25 h) bounds; Moscow and New York zones injected | `SdTimelineTest` | unit |
| SD-03 | 9030 bytes `[uc,res,1,0]+STimeDay`; 12718 cmd 16 with uc at [20]; stop cmd 1; 794 vs 12718 by `p2pEncrypt` | `SdArchiveCodecTest` | unit |
| SD-03 | gap snap, clamp `end-6 s`, "within 10 s of now → live" | `SdTimelineTest` | unit |
| SD-03/04 | state machine: seek/pause/resume/live command sequences and useCount increments; first record I with stale uc ignored; boundary fallback after N s; ended after 30 s | `PlaybackControllerTest` (virtual time) | unit |
| SD-03 | `FrameAssembler.expectGeneration`: old-generation I/P dropped, byte wrap 255→0 | `FrameAssemblerTest` | unit |
| SD-03 | TNP link reads ch4/5/7, routes to sink; commands queued after the burst; replies to `ioCtrlReplies` | `TnpCameraLinkTest` with `FakePpppNative` | unit |
| SD-04 | TUTK pause does not trip `maxNoDataPolls`; live/seek commands LE | `TutkCameraLinkTest` with `FakeTutkNative` | unit |
| SD-05 | 817 tfstat mapping (5, 2, 3, 8, 9, 0/total 0, 0/total>0, short reply), index timeout → error state with retry | `SdStatusTest`, `SdIndexRepositoryTest` | unit |
| SEC-01 | new log lines value-free | `RedactionScanner` fixtures + `RealLogScanTest` | unit + sweep |

### What needs the phone
- Which camera has SD; real tfstat/total; index size.
- Time base (`deltaS`), channel used, record I-frame decrypt, audio in playback.
- Boundary/gap behaviour, end-of-record behaviour, pause really stops frames.
- UI: timeline scroll/pinch feel, day dialog, «Живой», fullscreen/zoom in archive, background → «Продолжить».
- RELAY playback (if a 4G phone is available; Phase 3 could not test 4G on the USB phone).

### Wave 0 gaps
- [ ] `app/src/test/java/com/yicamalt/archive/` package + fixtures builder for STimeDay/9032/793 byte arrays
- [ ] `FakePpppNative`: scripted ch0 replies for arbitrary types and ch4/5/7 video units
- [ ] `FakeTutkNative`: scripted `recvIoCtrl` pages (type 793) and `-20012` runs during pause
- [ ] `RedactionFixtures`: no new secret shapes; add a negative fixture (index log line) that must pass

## Project Constraints (from AGENTS.md / CLAUDE.md)
- GRACE: contract first (`MODULE_CONTRACT`, `MODULE_MAP`, function `START_CONTRACT`), paired `START_BLOCK_`/`END_BLOCK_` anchors, `CHANGE_SUMMARY` on edits; update `docs/development-plan.xml`, `docs/knowledge-graph.xml` (new `M-SD-ARCHIVE`, edges to `M-STREAM-CORE`, `M-TNP-LINK`, `M-TUTK-LINK`, `M-LIVE-SESSION`, `M-UI-LIVEVIEW`), `docs/verification-plan.xml` (`V-M-SD-ARCHIVE`, changed link tests and log markers). XML docs use unique tags (`<M-SD-ARCHIVE …>`).
- Logs: `[Module][fn][BLOCK_NAME]` with structured fields; never DID/uid/passwords/nonce/InitString/IPs; Phase 2 packages use `//` comments only.
- Windows; Gradle only via `tools/gradle.ps1`; verification only on the physical phone (no emulator); `adb install` works now (memory: device-test-workflow).
- Vendor binaries, decompiled code, captures never committed (pre-commit hook); decompile output stays outside the repo or in git-ignored `_re/`.
- Links never retry/reconnect (supervisor does); one session per camera; close bounded and idempotent; mute default; background closes the session.

## Sources

### Primary (HIGH, VERIFIED-LOCAL, git-ignored decompile of Yi Home 6.9.8)
- `_re/jadx8/.../com/xiaoyi/camera/sdk/AntsCameraTnp.java` — `doSeekTo` (9030), `doGoLive` (9029), `pausePlay`, `stopRecordPlay`, `receiveVideoFrameData` (liveFlag statistics)
- `_re/jadx8/.../com/xiaoyi/camera/sdk/AntsCameraTutk.java` — `doSeekTo` (12718/794 cmd 16, useCount byte 20), `doGoLive` (8190/511), `pausePlay` (`stopShow`), `doResumePlayVideo`
- `_re/jadx8/.../com/xiaoyi/camera/sdk/AntsCamera.java` — `useCount`/`addUseCount`, `seekTo`, `goLive`, `sendStartPlayCommand`, frame handler useCount equality gate, `convertLocal2Utc2Device`
- `_re/jadx8/.../com/xiaoyi/camera/sdk/CameraCommandHelper.java` — `getTnpEvents` (9031/9032), `getTutkLangtaoEvents` (792/793 paging), `getEvents`, `getTnpSdEventDays` (5136), `getTnpNewSdEvents` (5138), `getDeviceInfo` (816/817)
- `_re/jadx7/.../com/tnp/TnpCamera.java` — channel constants 4/5/7, `ThreadRecv` buffer sizes, `startThread` (all readers), `handleVideoFrame` (shared live/record), `pause`, `sendStopRecordVideoCommand`
- `_re/jadx6/.../com/tutk/IOTC/AVIOCTRLDEFs.java` — `STimeDay`, `SMsgAVIoctrlListEventReq/Resp`, `SMsgAVIoctrlTnpListEventResp`, `SAvTnpEvent`, `SAvEvent`, `SMsgAVIoctrlTnpPlay/PlayRecord`, `SMsgAVIoctrlPlayRecord`, `SMsgAVIoctrlDeviceInfoResp.parse`, `SMsgAVIoctrlTnpAlert*`; `TutkCamera.java` (`stopShow`, `startShow`, IOCTRL buffer 1024, frame loop); `AVFrame.java` (`isLive`, privacy bit); `Packet.java`; `EventInfo.java`
- `_re/jadx2/.../fragment/CameraPlayerFragment.java` — index scheduling (720 h, 1 s / 60 s), tfstat branching, `s6()` go-live, `u6()` seek, `isUtc`/`setDevice2UtcOffsetHour`; `mc/b0.java` (`e`, `f`, `U`, `j0`); `w9/d.java`, `w9/n.java` (region offset 8/9/0); `bean/DeviceInfo.java` (`isSupportNewSD`, `sdcardSupport`); `feature/DeviceFeature.java`, `feature/b.java` (bundled feature config)
- Session scratchpad decompile of `classes9.dex` class `com.xiaoyi.yiplayer.f` (P2PPlayer): watchdogs `w0` (30 s) / `x0` (day change), `seekTo`, `i()` clamp, `M0()` go-live, `O0()/P0()` tfstat, index path selection; `_re/jadx8/.../wn/g.java` (`a`, `b`, `E` = same conversions)
- `base.apk` `assets/feature_config/*.json` (61 model configs): `newSdPlayback=1` only r44a, y624, y625; `sdcardSupport=0` for d201, n10, n20, n30, w10, y32, y32gc

### Secondary (MEDIUM, PUBLIC, MIT)
- https://github.com/Dmxsir/yi-cam-integration — `docs/tnp-golden-trace.md`, `docs/tnp-network-golden-trace.md` (official 6.9.7 pcap: 9031 first, 9032 = 5676-byte payload, 12718 24-byte payload, 816/817 344 B), `docs/ioctrl-map.md` (794/795/12718), `docs/tnp-protocol.md` (ch4/5 playback, liveFlag bit 0)

### Tertiary (LOW)
- Camera behaviour at gaps / end of record, audio during playback, per-session playback with the official app: no source; device probe required.
- Material3 DatePicker per-day decoration: training knowledge; check during planning.

## Metadata

**Confidence breakdown:**
- Protocol layouts and vendor order: HIGH (vendor code + public pcap sizes agree).
- Session model / pipeline reuse: HIGH (shared vendor code paths).
- Time base: MEDIUM-HIGH (vendor arithmetic is unambiguous for gw-us; one device check).
- Camera-side boundary/end behaviour, audio in playback: MEDIUM-LOW (needs probe).
- SD presence on the user's cameras: LOW (unknown offline).

**Research date:** 2026-10-30
**Valid until:** 2026-11-30 (vendor APK pinned; re-check if the user's APK changes).

---

## RESEARCH COMPLETE (summary)
- Archive runs on the existing session: TNP 9031/9032 index, 9030 seek, 12718/794 cmd 1 stop, 767 pause, 9029 live; TUTK 792/793 (paged), 12718/794 cmd 16 seek / cmd 1 stop, 8190/511 live; SD status 816/817 `tfstat` (5 = no card).
- Recorded frames = live frames (same head, AES, ordering); TNP adds channels 4/5/7; `liveFlag` bit 0 marks recordings; every play command takes `++useCount` from one link-wide counter, frames gated by equality.
- Wire time = UTC STimeDay for gw-us; frame head timestamp = epoch seconds; build with `java.time`.
- Feasibility: TNP HIGH, TUTK MEDIUM-HIGH. Blocker: unknown which camera has an SD card → probe 816 first.
- Plan order: 04-00 probe ∥ 04-01 pure core → 04-02 port + TNP → 04-03 UI → 04-04 TUTK.
