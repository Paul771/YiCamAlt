# Phase 3 — Device verification (partial)

**Date:** 2026-10-23 · **Device:** Mi 9 (MIUI, API 30), Wi-Fi/LAN · **Build:** debug APK from `gsd/phase-3-live-view` (installed via `adb install -r`, SHA-256 prefix matches the built APK)
**RESULT: PARTIAL** — TUTK camera only. The TNP camera ("Спальня") was powered off / unreachable. Phase stays `human_needed`; LIVE-04..11 are NOT marked complete.

Values (uid, DID, passwords, tokens) are deliberately not recorded here.

## Verified on the device (evidence: screenshots + logcat `[LiveView]/[LiveSession]/[P2P]` blocks)

| Check | Result | Evidence |
|---|---|---|
| H-L1 TextureView on MIUI (TUTK) | PASS | White surface, black text, video visible, controls drawn over the video; no window blackening |
| H-L2 Audio, TUTK (receive + decode) | PASS (sound confirmed audible by the user, TUTK camera) | `BLOCK_AUDIO_FIRST codecId=138 rate=8000 ch=2`, `BLOCK_AUDIO_DECODER_CREATE`, `BLOCK_AUDIO_TRACK` after unmute; mute is default |
| H-L4 HD/SD switch, TUTK | PASS | `4881` sent, `4882` reply; decoder recreated at 1280x720 (HD) and 640x360 (SD); button label follows |
| H-L7 Fullscreen (button) | PASS | Landscape, system bars hidden, controls over video, `BLOCK_FULLSCREEN on=true source=button` |
| H-L8 Snapshot | PASS | `result=Saved`, file in `Pictures/YiCamAlt`, JPEG 640x360 = stream resolution at that moment |
| H-L6 Background (Home, quick return) | PASS | `BLOCK_STOP reason=background`, link closed in ~1.7 s (1.5 s IOCtrl abort), registry freed; on return `BLOCK_RESUME resumed=true`, reconnect OK; audio decoder not recreated after resume (muted again) |
| Reconnect backoff (offline camera) | PASS | TNP `DEVICE_NOT_ONLINE` (−3006): attempts at 2/5/10/20 s, badge "Повтор (n/5)", then "Камера не в сети" + "Повторить" (`result=exhausted attempt=5`) |
| H-L7 Gestures (double tap, pinch) | PASS (user-confirmed by hand; not driven by adb) | User report |
| H-L5 Wi-Fi off (user report, second phone with 4G, no logs) | REPORTED OK | A couple of reconnect attempts, then the camera came back in P2P mode; not corroborated by logs (the USB phone has no 4G) |
| H-L6 Parallel use with the official app (user report, second phone, no logs) | REPORTED OK | Both apps showed video at the same time; not corroborated by logs |
| H-L6 Screen lock, return after >5 min | PASS | Lock: `BLOCK_STOP reason=background`, closed in ~1.7 s. Return after 446 s: `BLOCK_RESUME resumed=false`, no new link, badge "Не подключено" + "Подключить"; pressing it connected in ~1.2 s (LAN) |
| Keep screen on while video renders | PASS | `dumpsys power`: SCREEN_BRIGHT_WAKE_LOCK held with WorkSource com.yicamalt during live video |
| Fullscreen exit by Back | PASS | Back leaves fullscreen, camera stays open, stream not interrupted |
| H-L5 Wi-Fi off/on on the USB phone (`svc wifi`, with logs) | PASS | `BLOCK_NETWORK_EVENT available=false` then `available=true generationChanged=true`; supervisor reconnected at once, Live ~1.2 s after Wi-Fi returned; no manual action |
| H-L5 Camera power cycle (TUTK, with logs) | PASS | 10 s stall detected (`BLOCK_STALL`), then attempts 2-4 (connect timeouts 30 s / 30 s / 10 s, 5/10 s pauses), attempt 5 hit transient `WRONG_PASSWORD -20009` right after the camera boot, one automatic retry (attempt 6) -> Live at 10:49:13, ~2 min after the stall, no user action |
| TNP camera "Спальня" (Phase 2 H1/H2 + H-L3 + TNP half of H-L4), 2026-10-25 | PASS (LAN, no RELAY) | `BLOCK_CONNECT result=ok`, `BLOCK_AUTH_REPLY authResult=0 version=2`, burst 4881,9029,768; first video 875 ms, decoder 1920x1080 HW H.264; audio AAC 16000 Hz mono `enc=true` (AES), AAC decoder + AudioTrack created after unmute; HD->SD (decoder 640x360) and back (1920x1080) via 4881; snapshot saved (281 KB); Home: closed in 374 ms, return `resumed=true`, Live again in ~0.7 s; audio not recreated after resume |
| H-L9 Redaction sweep | PASS | `RealLogScanTest` over the pulled 849-line diag log: BUILD SUCCESSFUL |

## Not verified (pending)

- Keep-screen-on, exit by Back. (Fullscreen by phone rotation: confirmed working by the user.)
- H-L5 Wi-Fi→4G (the USB phone has Wi-Fi only). 
- TNP: sound audibility (human), RELAY mode, TNP over mobile data, H.265, parallel use with the official app on TNP.
- Phase 2 H1-H7: TNP LAN connection is now proven (above); the rest of H1-H7 still to be walked through and recorded in 02-DEVICE-VERIFICATION.md.

## Observations

- Unprompted TUTK/LAN stream drops recovered by the supervisor in 2-4 s (2026-10-25: REMOTE_CLOSED -20015 and a 10 s stall; 2026-10-23: a stall and a REMOTE_CLOSED). Cause not established (camera or LAN session); the reconnect path handled each one.
- A TUTK connect attempt can hang for 30 s (`UNKNOWN_-27`, timeout) when the camera is unreachable, so exhausting 5 attempts takes minutes; TNP reports DEVICE_NOT_ONLINE in ~2.4 s.
- The TNP camera has pan/tilt (PTZ) but the live screen has no PTZ controls: out of Phase 3 scope (LIVE-xx); REQUIREMENTS lists it as CTRL-01 (v2).
- Both cameras were unreachable at the start of the 2026-10-25 session (TNP `DEVICE_NOT_ONLINE`, TUTK connect timeout); TUTK came back after the user powered it on. TNP still offline.
- UX defect found and FIXED (LiveViewScreen: video layer alpha 0 while Connect/Retry is shown): after a stop the last frame no longer stays behind "Не подключено"; verified on the device (black box, no frozen frame, return after 36 min, `resumed=false`).
- TUTK camera audio is 8 kHz stereo AAC, not the 16 kHz mono the research assumed; the decoder follows the stream format.
- The cloud list marks "Спальня" online while PPPP reports `DEVICE_NOT_ONLINE`: the cloud flag is not a reachability signal.
