# Phase 4 — SD archive device probe (plan 04-04)

**Date:** 2026-10-30
**Device:** Mi 9 (USB, adb), debug APK built from commit b0ad96d
**Branch:** gsd/phase-4-sd-archive
**Run by:** the orchestrator (adb-driven), not by a person watching the picture
**Log:** pulled diag log, git-ignored (`build/device-logs/`), never committed

Values (uid, DID, passwords, tokens, IPs, absolute dates/times, payload bytes) are deliberately not recorded. Every number below is a count, a code, a size or a duration copied from value-free `[Archive]` / `[P2P]` log lines.

## Result in one line

Partial probe: the TNP camera «Спальня» was probed and has **no SD card** (tfstat=5); the TUTK camera was unreachable (powered off). The 816/817 and 9031/9032 exchanges are confirmed on a real TNP camera, but only their empty-result paths. Playback, pause, live return, boundary and wire time were **not exercised**.

## Camera «Спальня» (TNP, Wi-Fi LAN P2P)

| Item | Observed | Evidence (log anchor) |
|------|----------|-----------------------|
| Session | connected, video streaming H264 ~19.5 fps, decryptOk | LiveProbe session lines |
| SD info round trip (816 -> 817) | works: request 4 B, reply 344 B in 41 ms | `[P2P][tnp][BLOCK_SEND_IOCTRL] type=816`, `BLOCK_IOCTRL_REPLY type=817 bytes=344` |
| SD card (tfstat) | **5 = NoCard** (iface=9 hw=40 totalMb=0 freeMb=0) | `[Archive][probe][BLOCK_SD_INFO] transport=TNP replyLen=344 ... tfstat=5 ... isUtc=1 ms=41` |
| isUtc flag in 817 | 1 | same line |
| Index round trip (9031 -> 9032) | works: request 24 B, reply 12 B, `Ok(count=0)`, pages=1, rejected=0, 62 ms | `[Archive][index][BLOCK_FETCH_INDEX]` |
| Index content | count=0, sumDurS=0, maxDurS=0, days=0, newestAgoMin=-1 | `[Archive][probe][BLOCK_SD_INDEX] result=ok count=0 ... ms=86` |
| Record channel (ch4/ch5/ch6/ch7) | none: ch4=ch5=ch6=ch7=0, recorded=0, otherUc=0 over 8 ticks (~40 s); only live ch2/ch3 and audio flowed | `[Archive][probe][BLOCK_SD_CHANNELS]` |
| deltaS (wire time) | not observed (no recording to seek) | — |
| First recorded frame ms | not observed | — |
| Pause stops frames (framesAfterS5) | not exercised | — |
| Resume works | not exercised | — |
| Live return ms | not exercised | — |
| Boundary (jumpS / resumedAfterS) | not exercised | — |
| Audio units during playback | not exercised (live audio flowed: audioUnits grew ~78 per 5 s) | `BLOCK_SD_CHANNELS audioUnits=` |
| Probe steps run | 1-3 (connect, SD info, SD index); 4-8 skipped because there is nothing to play | orchestrator report |

## Camera «YI Home Camera» (TUTK)

| Item | Observed | Evidence (log anchor) |
|------|----------|-----------------------|
| Session | **not reachable**: connect timeout after ~30 s (IOTC_ER_TIMEOUT), camera apparently powered off | `[P2P][tutk][BLOCK_CLOSE] cam=1 reason=timeout units=0` |
| SD card / index / playback | not probed | — |

## Redaction sweep

`RealLogScanTest` over the pulled diag log (4548 lines): `tests=1 skipped=0 failures=0 errors=0` — no secrets found. Command: `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=<pulled log> --rerun-tasks`.

## Decisions

### D-PROBE-1 — which camera has an SD card
- TNP «Спальня»: **no card — VERIFIED** (tfstat=5, totalMb=0, index empty; consistent with the greyed SD tab in the official app).
- TUTK camera: **UNVERIFIED** (unreachable during the probe).
- No camera is known to have a card with recordings.

### D-PROBE-2 — no-card path and what device verification is possible
- The no-card path is real on TNP: 816 answers with NoCard, 9031 answers with an empty list. Phase 4 continues with JVM verification.
- **No camera reports an Ok card.** SD-01..SD-04 device scenarios stay "not exercised: no SD card" — device verification of SD-01..SD-05 is human_needed: the user must insert a microSD card (and let the camera record for at least an hour) before 04-09.
- H-SD6 (no-card state «Нет SD-карты») **can** be checked on the TNP camera «Спальня» in 04-09.

### D-PROBE-3 — segment-boundary behaviour
- **UNVERIFIED** (no recordings, boundary step not run). PlaybackController default `BOUNDARY_MS = 8000` (client fallback after 8 s) stays.

### D-PROBE-4 — wire time base (WIRE_IS_UTC) and TUTK feasibility
- Wire time: **UNVERIFIED** — no deltaS observation. `SdTime.WIRE_IS_UTC = true` stays (not flipped).
- Supporting, not conclusive: the TNP 817 reply carried `isUtc=1`, which is consistent with the default.
- TUTK archive feasibility (D-06): **UNVERIFIED** (camera unreachable). `ArchiveSupport` keeps both transports; 04-09 decides per D-06.

### Confirmed by this run
- The 816/817 and 9031/9032 wire formats (request sizes 4 B / 24 B, reply parsing) and the probe tooling (`SdProbe`, `SdProbeTee` channel counters, value-free logs) work on a real TNP camera — empty-result paths only.

### Numbering note
Plan 04-04 Task 3 listed the decisions in a different order (wire time first). The orchestrator's checkpoint result fixed the order used above; D-PROBE-2 keeps the meaning that 04-08 / 04-09 read ("no camera has an SD card").
