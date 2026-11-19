---
status: complete
phase: 03-live-view
source: [03-VERIFICATION.md, 03-HUMAN-UAT.md]
started: 2026-10-23
updated: 2026-11-19
---

## Current Test

[testing complete]

## Tests

### 1. H-L1 — TextureView rendering on MIUI (both cameras)
expected: picture visible with decoded aspect ratio, no black window; controls and LAN/P2P/Relay badge drawn over video.
result: pass
evidence: TUTK 2026-10-23, TNP 2026-10-25 — white surface, video + controls, no window blackening (03-HUMAN-UAT.md)

### 2. H-L2 — Audio on the TUTK camera
expected: sound off on open; unmute plays camera sound with low delay; mute stops it; AAC codec logged.
result: pass
evidence: TUTK AAC 8 kHz stereo, sound audible per user (03-HUMAN-UAT.md)

### 3. H-L3 — Audio on the TNP camera
expected: decrypted ADTS audio, no BAD marker, audible after unmute.
result: pass
evidence: TNP AAC 16 kHz mono enc=true, AudioTrack created, sound audible per user 2026-10-25

### 4. H-L4 — HD / SD switch on both cameras
expected: frame width changes without reconnect; relay starts in SD; control hides when camera refuses.
result: pass
evidence: 4881, 1920x1080/1280x720 <-> 640x360, no reconnect (03-HUMAN-UAT.md)

### 5. H-L5 — Wi-Fi → 4G handover with logs
expected: badge goes Офлайн/Повтор → Подключение… → Live without a tap; ≤5 attempts, no reconnect storm, sound muted after return.
result: pass
evidence: user-attested 2026-11-19 with the Mi 9 connected; previous session had only an unlogged user report, now accepted on device

### 6. H-L6 — Background / hand-over with official Yi app
expected: our session stops in background, official app gets video, ours resumes ≤5 min or waits for tap after; rotation does not restart.
result: pass
evidence: Home/lock/>5 min PASS both cameras; official app hand-over confirmed on TNP 2026-10-30 and TUTK earlier (03-HUMAN-UAT.md)

### 7. H-L7 — Fullscreen, gestures, rotation, keep-screen-on
expected: no session restart on rotation/fullscreen, zoom clamped 1..4x, controls auto-hide only in fullscreen, screen stays on.
result: pass
evidence: fullscreen by button/rotation, Back, pinch, double tap, wake lock (03-HUMAN-UAT.md)

### 8. H-L8 — Snapshot in the gallery
expected: full-frame JPEG under Pictures/YiCamAlt, toast confirms, no partial gallery entry.
result: pass
evidence: both cameras at stream resolution 1920x1088 on TNP, zoomed snapshot confirmed 2026-10-30

### 9. H-L9 — Log pull and redaction sweep of Phase 3 lines
expected: RealLogScanTest 0 skipped / 0 failures, Phase 3 markers present, no DID/password/nonce/IP/SSID.
result: pass
evidence: RealLogScanTest over pulled diag log, 849 lines (03-HUMAN-UAT.md)

### 10. TNP over mobile data / Relay — SD by default
expected: badge shows Relay; stream starts in SD; HD/SD toggle still works or hides.
result: pass
evidence: user-attested 2026-11-19 — relay badge and SD-by-default observed on device (previously JVM-tested only, LIVE-07)

### 11. 15+ minutes uninterrupted live viewing
expected: no stop, prompt or reconnect caused by a timer; no ads.
result: pass
evidence: user-attested 2026-11-19 — long live session without timer-driven stop, prompt, reconnect or ads

### 12. H.265 camera path
expected: video decodes if an H.265 camera is available.
result: skipped
reason: no H.265 camera available (device limitation, not a product failure)

## Summary

total: 12
passed: 11
issues: 0
pending: 0
skipped: 1
blocked: 0

## Gaps

none
