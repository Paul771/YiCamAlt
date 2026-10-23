# Phase 3 — Device verification (partial)

**Date:** 2026-10-23 · **Device:** Mi 9 (MIUI, API 30), Wi-Fi/LAN · **Build:** debug APK from `gsd/phase-3-live-view` (installed via `adb install -r`, SHA-256 prefix matches the built APK)
**RESULT: PARTIAL** — TUTK camera only. The TNP camera ("Спальня") was powered off / unreachable. Phase stays `human_needed`; LIVE-04..11 are NOT marked complete.

Values (uid, DID, passwords, tokens) are deliberately not recorded here.

## Verified on the device (evidence: screenshots + logcat `[LiveView]/[LiveSession]/[P2P]` blocks)

| Check | Result | Evidence |
|---|---|---|
| H-L1 TextureView on MIUI (TUTK) | PASS | White surface, black text, video visible, controls drawn over the video; no window blackening |
| H-L2 Audio, TUTK (receive + decode) | PASS (playback audibility NOT confirmed by a human) | `BLOCK_AUDIO_FIRST codecId=138 rate=8000 ch=2`, `BLOCK_AUDIO_DECODER_CREATE`, `BLOCK_AUDIO_TRACK` after unmute; mute is default |
| H-L4 HD/SD switch, TUTK | PASS | `4881` sent, `4882` reply; decoder recreated at 1280x720 (HD) and 640x360 (SD); button label follows |
| H-L7 Fullscreen (button) | PASS | Landscape, system bars hidden, controls over video, `BLOCK_FULLSCREEN on=true source=button` |
| H-L8 Snapshot | PASS | `result=Saved`, file in `Pictures/YiCamAlt`, JPEG 640x360 = stream resolution at that moment |
| H-L6 Background (Home, quick return) | PASS | `BLOCK_STOP reason=background`, link closed in ~1.7 s (1.5 s IOCtrl abort), registry freed; on return `BLOCK_RESUME resumed=true`, reconnect OK; audio decoder not recreated after resume (muted again) |
| Reconnect backoff (offline camera) | PASS | TNP `DEVICE_NOT_ONLINE` (−3006): attempts at 2/5/10/20 s, badge "Повтор (n/5)", then "Камера не в сети" + "Повторить" (`result=exhausted attempt=5`) |
| H-L9 Redaction sweep | PASS | `RealLogScanTest` over the pulled 849-line diag log: BUILD SUCCESSFUL |

## Not verified (pending)

- Human confirmation that sound is actually audible (unmute only checked via logs).
- Gestures: pinch / pan / double tap (could not be driven reliably via `adb input`).
- Rotation-triggered fullscreen, keep-screen-on, exit by Back.
- H-L5 network changes (Wi-Fi off / Wi-Fi→4G / camera power cycle), H-L6 lock-screen and >5 min return, parallel use with the official app.
- H-L3 and TNP half of H-L4 (TNP audio / HD-SD) — blocked on Phase 2 H1 (TNP camera online).
- Phase 2 H1–H7 on the TNP camera.

## Observations

- TUTK camera audio is 8 kHz stereo AAC, not the 16 kHz mono the research assumed; the decoder follows the stream format.
- The cloud list marks "Спальня" online while PPPP reports `DEVICE_NOT_ONLINE`: the cloud flag is not a reachability signal.
