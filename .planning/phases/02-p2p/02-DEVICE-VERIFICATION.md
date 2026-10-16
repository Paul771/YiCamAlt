# Phase 2 device verification (PARTIAL, in progress)

Date: 2026-10-16 (user was away from home, remote session). Commit: a90e4dc.
Evidence source so far: status line read by the user from the on-screen debug live screen. No device log pulled yet,
so the redaction sweep (RealLogScanTest on a pulled log) has NOT been run for this session.

## Recorded so far

TUTK camera (account camera of transport TUTK), user away from home, no LAN:
- First press of "Подключить": state=FAILED, auth=fail, error=APP_WRONG_PASSWORD, mode=P2P (session up, auth refused).
- Later press, same credentials, no code change: transport=TUTK state=STREAMING mode=P2P lan=no relayForced=no codec=H264
  decoder=c2.qti.avc.decoder hw=yes fps=21.9 frames=342 keyFrames=10 decryptOk=yes auth=ok error=-
- HEVC decoder line: c2.qri.hevc.decoder (hw) (present on the phone; no camera has produced H.265 yet).
- Observation: one transient APP_WRONG_PASSWORD followed by success with the same credentials. Cause unknown without the log
  (BLOCK_CLIENT_START encrypted=/result= marker). Not a criterion; repeat at home with USB attached and pull the log.

## Criteria status

| Criterion | Status |
|---|---|
| 1 native load + extraction + manifest (see 02-DEVICE-CHECK-1.md) | PASS (device check 1) |
| 2 / LIVE-01 video on home Wi-Fi (lan=yes) | PENDING (needs user at home) |
| 3 / LIVE-02 video off the home network, mode shown | PARTIAL: TUTK camera PASS (mode=P2P, lan=no, moving). TNP camera PENDING (camera offline). Relay-only mode NOT exercised. |
| 4 / LIVE-03 codec path | PARTIAL: H.264 hw decode PASS on TUTK camera. H.265 NOT exercised on device (no H.265 camera yet). |
| 5 JVM contracts + GRACE | PASS at HEAD (490 tests, 0 failures) |

## Not yet done

- TNP camera live session (never run against a real camera).
- A (home Wi-Fi, lan=yes) for both cameras; B relay-only; C parallel use with the official app.
- Log pull and redaction sweep (SEC-01) for the live sessions.

## Update 2026-10-16 evening (phone attached by USB, at home on Wi-Fi)

TUTK camera on the LAN (log markers, value-free), build with the review fixes and the SurfaceView z-order fix:
- connect ok 365-1518 ms, check mode=LAN lan=true, client start ok, cmd 8190 ok, first video frame (key, codecId 78) after ~0.9-2.3 s.
- Decoder created: video/avc, OMX.qcom.video.decoder.avc hw=true, size=640x360; first render 31-67 ms after create.
- One BLOCK_DECODER_RESET reason=queue-overflow resets=1 right before the decoder is created on every start (frames queued while
  the codec is created); harmless so far, worth a look in Phase 3.
- Automatic WRONG_PASSWORD retry observed once on the LAN: first press -> AV_ER_WRONG_VIEWACCorPWD, retry after 2 s with refreshed
  credentials (refreshed=true) -> auth ok and streaming. Supports "stale stored password" as the cause of the intermittent error.
- Criterion 2 / LIVE-01: PASS for the TUTK camera on the LAN (user confirmed the picture moves).
- Mobile-data test was INVALID: the phone had no data service at that moment (mobile data off, operator data out of service,
  Wi-Fi off); connect timed out with no network. Must be repeated.
- UI defects found on the phone and fixed: live screen was unreadable (no Surface, dark/black rendering) and the whole window turned black
  with a default-z-order SurfaceView on this MIUI phone; fixed with a white Surface and SurfaceView.setZOrderOnTop(true) (verified on screen).

Still open: TNP camera on a real camera (never run), relay-only and mobile-data run, parallel use with the official app, H.265 on device,
log pull + redaction sweep for the final evidence.

RESULT: PARTIAL (pending TNP camera, home Wi-Fi, relay, parallel-use checks and log sweep)
