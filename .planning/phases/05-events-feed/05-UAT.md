---
status: complete
phase: 05-events-feed
source: [05-VERIFICATION.md, 05-HUMAN-UAT.md]
started: 2026-11-04
updated: 2026-11-21
---

## Current Test

[testing complete]

## Tests

### 1. H-EV1 — events tab and feed
expected: «События» third tab; events of all cameras with preview, name, time, grouped by day, newest first.
result: pass
evidence: 2026-11-04 — 23 events of two days in the feed, grouping and order confirmed

### 2. H-EV2 — camera chips
expected: chip filters events; «Все» restores; empty filter text when no events.
result: pass
evidence: 2026-11-04 — chip «Спальня» 23, «YI Home Camera» empty filter text, «Все» 23

### 3. H-EV3 — day picker and day button label
expected: picker is the archive month grid; marked days; «Сбросить» restores all days; label «Все дни ▾» accepted or changed by user.
result: pass
evidence: 2026-11-04 — picker marks two days, «Вчера» filters to 2 events, «Сбросить» restores all; label decision tracked as test 11

### 4. H-EV4 — clip in the dialog
expected: spinner then 6-second clip plays; «Повторить» replays; «Закрыть» closes; controlled error states instead of hang.
result: pass
evidence: 2026-11-04 — after fixes a43316d/f8ff4af/4f97df7 clip plays, controls work; cosmetic green strip tracked as test 12

### 5. H-EV5 — older event stale link
expected: clip plays after one link refresh, no second refresh or loop.
result: pass
evidence: 2026-11-04 — BLOCK_CLIP_REFRESH exactly once per open, then BLOCK_FETCH_CLIP magic=mp4

### 6. H-EV6 — jump to the SD recording
expected: camera screen opens with archive at the event moment; scene matches the clip; back returns to feed.
result: pass
evidence: 2026-11-04 — twice on TNP «Спальня», BLOCK_ARCHIVE_OPEN_AT result=seek, BLOCK_SEEK result=Archive, scene matched

### 7. H-EV7 — jump without SD or without a recording
expected: camera screen opens with live video and honest Phase 4 empty-state text; no hang; back works.
result: pass
evidence: user-attested 2026-11-21 on Mi 9 — event of a camera without SD / moment without recording opened live with the honest empty-state text, no hang, back returned to feed

### 8. H-EV8 — at most one feed refresh per 5 minutes
expected: no feed request within 5 minutes on re-entry or pull; after 5 minutes one pull makes exactly one refresh; force-stop preserves gate.
result: pass
evidence: 2026-11-04 — BLOCK_REFRESH_GATE too-early, one BLOCK_FETCH_PAGE page=1 after 5 min, gate kept after force-stop

### 9. H-EV9 — offline cache with previews
expected: cached cards shown WITH previews in airplane mode; subtitle accepted either way.
result: pass
evidence: 2026-11-04 — Wi-Fi off, force-stop, reopen: all cards with cached previews, «Обновлено 1 мин назад»

### 10. H-EV10 — log sweep and forbidden paths
expected: RealLogScanTest 0 skipped / 0 failures; forbidden-path grep 0; media link count 0.
result: pass
evidence: 2026-11-04 — 738-line log, tests=1 skipped=0 failures=0, both greps 0; earlier exception-message leak fixed in 5b47900/de20d23/c6163c4

### 11. H-EV3 decision — day button label
expected: user accepts «Все дни ▾» (deviation from D-03's «Сегодня ▾») or asks to change it; a change is a UI follow-up, not a filter failure.
result: pass
evidence: user decided 2026-11-21 to keep «Все дни ▾» — the honest label for an unfiltered (all-days) feed; D-03 wording deviation accepted, no UI change requested

### 12. Clip picture edge — cosmetic green strip
expected: no green strip at the bottom edge of the played clip, or user accepts it as decoder padding.
result: pass
evidence: user accepted 2026-11-21 as decoder padding, no fix requested (cosmetic, non-blocking)

## Summary

total: 12
passed: 12
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

none
