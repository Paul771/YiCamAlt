---
status: pending
phase: 03-live-view
source: [03-RESEARCH.md "Human checks", 03-12-PLAN.md]
started: 2026-10-23
updated: 2026-10-23
---

## Current Test

[awaiting the phone session (plan 03-13): Mi 9 over USB, HEAD APK installed over the current one (data kept), official Yi Home app closed]

## Dependencies

- Phase 2 checks H1-H7 (`.planning/phases/02-p2p/02-HUMAN-UAT.md`) are still open. Every TNP-specific Phase 3 behaviour is JVM-only until Phase 2 **H1** (TNP camera on home Wi-Fi) passes.
- **H-L3** and the **TNP half of H-L4** need Phase 2 H1 first. If H1 is still failing, record them as `blocked (H1)`, not as failures.
- **H-L6** extends Phase 2 **H6** (hand-over with the official app).
- **H-L9** repeats Phase 2 **H7** (log pull + redaction sweep) over the new Phase 3 log lines.

Log pull (no `sh -c`, see verification-plan Gate-Phase-2 note-1):

```
adb exec-out run-as com.yicamalt cat files/diag_log.txt.1 > build/device-logs/diag_log.txt
adb exec-out run-as com.yicamalt cat files/diag_log.txt >> build/device-logs/diag_log.txt
```

Markers to look for, by check: `[LiveSession][supervisor][BLOCK_*]` (OPEN_LINK, CLASSIFY, SCHEDULE, DRAIN, STALL, NETWORK, STOP, QUALITY, RESUME), `[LiveSession][network][BLOCK_NETWORK_EVENT]`, `[P2P][tutk][BLOCK_AUDIO_LOOP]`, `[P2P][tnp][BLOCK_AUDIO_UNIT]`, `[P2P][tutk|tnp][BLOCK_SET_RESOLUTION]`, `[Stream][audio][BLOCK_AUDIO_FIRST]`, `[Media][audio][BLOCK_AUDIO_TRACK]`, `[LiveView][render][BLOCK_RENDER_VIDEO_SURFACE]`, `[LiveView][render][BLOCK_FULLSCREEN]`, `[LiveView][vm][BLOCK_*]`, `[Media][snapshot][BLOCK_SAVE_SNAPSHOT]`.

## Tests

### H-L1. TextureView rendering on MIUI (both cameras)
steps: camera list, tap the TUTK camera, then the TNP camera (tap opens the live screen; long press still opens the debug probe).
expected: the picture is visible with the decoded aspect ratio, no black window; the colour badge and the sound / HD-SD / snapshot / fullscreen controls are drawn over the video; badge shows LAN / P2P / Relay per camera.
markers: `[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=live`, `[LiveView][render][BLOCK_RENDER_VIDEO_SURFACE]`, `[LiveSession][supervisor][BLOCK_OPEN_LINK]`, `[Media][decoder][BLOCK_FIRST_RENDER]`, `[Media][decoder][BLOCK_FORMAT_CHANGED]`.
result: [pending]

### H-L2. Audio on the TUTK camera
steps: open the TUTK camera, wait for video, press the sound button, listen 30 s, press it again.
expected: sound is off on open; after unmute the camera's sound plays with low delay; mute stops it; the debug row (debug toggle) shows `audio=playing` with a growing `audioUnits=`; if the camera sends no AAC the sound button stays hidden. Record codec id and ADTS format from `[Stream][audio][BLOCK_AUDIO_FIRST] codecId= rate= ch= enc=` (research expected AAC 16000 Hz mono). Note: the research draft named the debug fields `audio=AAC/16000/1` / `audioOk`; the built debug row uses `audio=<muted|idle|playing|failed> audioUnits=<n>`.
markers: `[P2P][tutk][BLOCK_AUDIO_LOOP]`, `[Stream][audio][BLOCK_AUDIO_FIRST]` or `[Stream][audio][BLOCK_AUDIO_BAD]`, `[Media][audio][BLOCK_AUDIO_DECODER_CREATE]`, `[Media][audio][BLOCK_AUDIO_TRACK]`, `[Media][audio][BLOCK_AUDIO_RELEASE]`, `[LiveView][vm][BLOCK_TOGGLE_AUDIO]`.
result: [pending]

### H-L3. Audio on the TNP camera (needs Phase 2 H1)
steps: only after H1 passes: open the TNP camera, unmute, listen 30 s.
expected: ch1 audio decrypted with header version >= 2 gives valid ADTS (`[Stream][audio][BLOCK_AUDIO_FIRST] ... enc=true`, no `BLOCK_AUDIO_BAD`; debug row `audio=playing`) and audible sound; no decoder failure.
markers: `[P2P][tnp][BLOCK_AUDIO_UNIT]`, `[Stream][audio][BLOCK_AUDIO_FIRST]` (enc=true), `[Media][audio][BLOCK_AUDIO_TRACK]`.
result: [pending]

### H-L4. HD / SD switch (TUTK now; TNP half needs Phase 2 H1)
steps: on each camera press HD/SD a few times; note the connection mode the badge shows (on mobile data the link may be relay).
expected: the frame width changes without a reconnect (badge stays Live); relay starts in SD; if the camera refuses (abilityRet 1 / no confirmation in 8 s) the HD/SD control disappears. Record frame widths for HD and SD per camera.
markers: `[LiveView][vm][BLOCK_QUALITY_SWITCH]`, `[LiveSession][supervisor][BLOCK_QUALITY]`, `[P2P][tutk][BLOCK_SET_RESOLUTION]` / `[P2P][tnp][BLOCK_SET_RESOLUTION]`, `[P2P][tnp][BLOCK_AUTH_REPLY] abilityRet=`.
result: [pending]

### H-L5. Reconnect: Wi-Fi toggle, Wi-Fi -> 4G, camera power cycle
steps: while streaming: Wi-Fi off and on; Wi-Fi off with 4G on; unplug the camera for 30 s and plug it back. Do not tap anything.
expected: the badge goes Офлайн / Повтор (n/5) / Подключение… and back to Live without a tap; at most 5 attempts with 2/5/10/20 s pauses, then «Нет связи» with «Повторить»; no reconnect storm in the diag log; record recovery time per case. Звук снова выключен после возврата из фона (строже D-05, подтвердить).
markers: `[LiveSession][network][BLOCK_NETWORK_EVENT]`, `[LiveSession][supervisor][BLOCK_NETWORK]`, `[LiveSession][supervisor][BLOCK_CLASSIFY]`, `[LiveSession][supervisor][BLOCK_SCHEDULE]`, `[LiveSession][supervisor][BLOCK_DRAIN]`, `[LiveSession][supervisor][BLOCK_STALL]`, `[LiveView][vm][BLOCK_BADGE]`.
result: [pending]

### H-L6. Background: lock / unlock, Home, return after > 5 min (extends Phase 2 H6)
steps: while streaming: lock and unlock within 1 min; Home and back within 1 min; Home, open the official Yi Home app on the same camera, then return to ours after more than 5 min.
expected: our session stops on background so the official app gets video; within 5 min ours resumes on its own once; after more than 5 min it waits for a tap (Connect); rotation does not restart the session. Звук снова выключен после возврата из фона (строже D-05, подтвердить).
markers: `[LiveView][lifecycle][BLOCK_LIFECYCLE]`, `[LiveView][vm][BLOCK_LIFECYCLE]`, `[LiveSession][supervisor][BLOCK_STOP]`, `[LiveSession][supervisor][BLOCK_RESUME]`.
result: [pending]

### H-L7. Fullscreen, gestures, rotation, keep-screen-on
steps: fullscreen by button and by rotation (also with auto-rotate off); back exits fullscreen; pinch to 4x, pan, double tap; tap shows controls; leave the screen on for 2+ minutes without touching; check the notch side in landscape.
expected: no session restart on rotation or fullscreen; zoom clamped to 1..4x and pan bounded; double tap toggles fit / fill; controls auto-hide after 4 s only in fullscreen; system bars hidden in fullscreen and restored after; screen stays on while video renders; nothing hidden under the notch.
markers: `[LiveView][render][BLOCK_FULLSCREEN]`, `[LiveView][render][BLOCK_RENDER_VIDEO_SURFACE]`.
result: [pending]

### H-L8. Snapshot in the gallery
steps: zoom to 2x, press the snapshot button; open the gallery.
expected: a JPEG `YiCamAlt_yyyyMMdd_HHmmss.jpg` appears under Pictures/YiCamAlt at the full frame resolution (not the zoomed crop); a toast confirms; no half-written gallery entry on failure.
markers: `[LiveView][vm][BLOCK_SNAPSHOT]`, `[Media][snapshot][BLOCK_SAVE_SNAPSHOT] result=Saved sdk=30 bytes=<n>` (no URI, path, file or camera name).
result: [pending]

### H-L9. Log pull and redaction sweep of the new log lines (as Phase 2 H7)
steps: pull the diag log after H-L1..H-L8 (commands above); run `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.test.RealLogScanTest" -PredactionLogFile=E:/Dev/YiCamAlt/build/device-logs/diag_log.txt --rerun-tasks`.
expected: RealLogScanTest executed (skipped=0) with failures=0; the Phase 3 markers listed above are present; no DID, password, nonce, InitString, IP, SSID or network handle in the log.
result: [pending]

## Summary

total: 9
passed: 0
issues: 0
pending: 9
skipped: 0
blocked: 0

## Gaps
