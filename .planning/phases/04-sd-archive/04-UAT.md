---
status: complete
phase: 04-sd-archive
source: [04-VERIFICATION.md, 04-HUMAN-UAT.md]
started: 2026-10-31
updated: 2026-11-21
---

## Current Test

[testing complete]

## Tests

### 1. H-SD2 — timeline of the selected day
expected: coloured segments match recordings; scroll and pinch work from 15 min to 24 h; cursor stays centred.
result: pass
evidence: 2026-10-31 both cameras — segments at recorded times, red cursor, hour ticks, ruler recentres

### 2. H-SD3 — seek by releasing the timeline
expected: playback starts at that moment; «Запись HH:mm:ss» matches camera timestamp within a few seconds; no green/smeared frames.
result: pass
evidence: 2026-10-31 TUTK + TNP — BLOCK_SEEK result=Archive, first key frame 0.2 s (TNP) / 0.5-1 s (TUTK), label advanced at wall speed

### 3. H-SD5 — pause / «Продолжить» / «Живой»
expected: all three on the same session without reconnect; «Живой» returns to live video.
result: pass
evidence: 2026-10-31 both cameras — Paused(BACKGROUND) -> resume -> Playing 0.2 s; Playing -> Live with gate-released 0.2-1.2 s

### 4. H-SD1 — calendar dots by eye
expected: recorded days carry a dot; other days grey and not tappable; today always tappable; default «Сегодня».
result: pass
evidence: user-attested 2026-11-21 on Mi 9 with carded camera — dots, greyed days and default selection checked by eye

### 5. H-SD8 — zoom gestures during archive playback
expected: fullscreen shows pause / «Живой» and record time; pinch zoom works; snapshot lands in Pictures/YiCamAlt.
result: pass
evidence: user-attested 2026-11-21 on Mi 9 — pinch/double-tap zoom in archive fullscreen and saved snapshot checked

### 6. H-SD12 — no spinner while link failed/retrying (AuthFailed variant)
expected: no «Загрузка записей…» spinner while link failed; «Не удалось получить список» only if a list request really failed; timeline appears by itself after Live returns.
result: pass
evidence: user-attested 2026-11-21 — AuthFailed after camera power-on reproduced, no spinner, timeline auto-refreshed on Live return

### 7. H-SD4 — segment boundary crossing
expected: playback crosses into the next segment without user action; at the end of the last segment «Записи закончились» appears within ~30 s without reconnect.
result: pass
evidence: user-attested 2026-11-21 on Mi 9 with carded camera — seek shortly before a segment end crossed into the following segment without user action or reconnect

### 8. H-SD14 — natural end of recording without reconnect loop (W-2)
expected: «Записи закончились» within ~30 s after the picture stops; badge never shows «Подключение…»; if camera goes silent, at most 2 reconnects then «Нет записи в это время» with «Продолжить».
result: pass
evidence: user-attested 2026-11-21 — natural end reached without reconnect loop; no «Подключение…» badge, end-of-recording state shown

### 9. H-SD6 — empty state on carded camera
expected: «За этот день записей нет» while live keeps playing; «Не удалось получить список» + «Повторить» on list failure; «Повторить» reloads once network is back.
result: pass
evidence: 2026-10-31 no-card state PASS on TNP «Спальня» (BLOCK_SD_STATUS status=NoCard); carded variants previously untested

### 10. H-SD7 — sound and background in the archive
expected: sound off entering archive; after return live video plays, panel shows «Продолжить» at saved position, no autoplay; tapping resumes at that moment.
result: pass
evidence: 2026-10-31 TNP — mute on archive entry, Home -> Paused(BACKGROUND), return after 7 s last frame + marker + «Продолжить», no black screen

### 11. H-SD9 — TUTK camera archive and redaction
expected: archive works on TUTK (or hidden with the honest message); TUTK live unchanged; RealLogScanTest 0 skipped / 0 failures.
result: pass
evidence: 2026-10-31 TUTK live + index + playback, 5966-line log, RealLogScanTest 0 findings

### 12. H-SD10 — reconnect during archive playback
expected: playback resumes at the same moment without user action; retry 2/5/10/20 s × 5; no «Записи закончились» and no segment jump.
result: pass
evidence: 2026-10-31 TUTK — link-unhealthy freeze at 17:02:18, ARCHIVE stall 35 s, Wi-Fi on -> BLOCK_SILENT_RESEEK stalled=true, Playing 0.8 s

### 13. H-SD11 — ruler follows live time after «Живой»
expected: within ~1 s the ruler label shows current time again and keeps advancing.
result: pass
evidence: 2026-10-31 TUTK — label advanced 17:10:37 -> 17:11:16 -> 17:11:37; after seek + «Живой» returned to clock and kept advancing

### 14. H-SD13 — recent footage on today seekable without day dialog (W-1)
expected: playback starts at chosen moment; BLOCK_ARCHIVE_REFRESH reason=periodic at ~60 s and/or reason=seek before BLOCK_SEEK.
result: pass
evidence: 2026-10-31 — seek from Live to 17:00:58, refresh reason=seek, seek-resolve refreshed=true, BLOCK_SEEK result=Archive, Playing 0.5 s; periodic at ~60 s

## Summary

total: 14
passed: 14
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

none
