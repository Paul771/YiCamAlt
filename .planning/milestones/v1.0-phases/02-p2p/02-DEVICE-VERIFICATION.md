# Phase 2 device verification (CLOSED 2026-11-19)

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
| 2 / LIVE-01 video on home Wi-Fi (lan=yes) | PARTIAL: TUTK camera PASS (mode=LAN, first render 31-67 ms). TNP camera PENDING. |
| 3 / LIVE-02 video off the home network, mode shown | PARTIAL: TUTK camera PASS twice (mode=P2P, lan=no, moving; second time over 4G on another phone). TNP camera PENDING. Relay-only mode NOT exercised. |
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

## Update 2026-10-16 night: cellular run on a second phone

The USB-attached test phone has no 4G, so the user ran the latest build on a different phone over 4G (no log pulled from that phone;
evidence is the on-screen status line read by the user):
- TUTK camera: transport=TUTK state=STREAMING mode=P2P lan=no relayForced=no codec=H264 decoder=c2.qti.avc.decoder hw=yes
  fps=20.2 frames=174 keyFrames=5 decryptOk=yes auth=ok error=-
- Criterion 3 / LIVE-02: PASS for the TUTK camera over mobile data (direct P2P path). Relay-only mode and the TNP camera are still NOT exercised.
- Earlier remote run (user away from home, other network) gave the same result with fps=21.9.

## Update 2026-11-19 — final UAT on Mi 9 with a real TNP camera

Full 7-item UAT completed; see `02-HUMAN-UAT.md` for per-test results and evidence.

- **H1 TNP on home Wi-Fi:** adb logcat — `BLOCK_AUTH_REPLY authResult=0 outcome=OK`,
  `BLOCK_FIRST_VIDEO codecId=78 key=true decryptOk=true 1920x1080`, `BLOCK_FIRST_KEYFRAME
  codec=H264`, `BLOCK_FIRST_RENDER ms=83`, UI badge `LAN`. Picture movement attested by user.
- **H2 TNP on mobile data:** user-attested — picture moves, `lan=no`, P2P or relay mode.
- **H3 Relay-only:** user-attested — relay mode engaged, picture moves; TUTK relay checkbox disabled.
- **H4 H.265:** no H.265 camera available — recorded "H.265 path not exercised on device".
- **H5 TUTK regression:** user-attested — connect/stop/reconnect/reopen all produce video, no queue-overflow reset.
- **H6 Hand-over with official app:** user-attested — both apps work one after the other (BUSY/KICKED is Phase 3 scope).
- **H7 Log pull + redaction sweep:** 45 010 logcat lines captured, 200 app-tagged lines;
  0 unredacted `password=` / `account=` / `token=` / `Authorization` in app messages;
  `RealLogScanTest` passes 2/2 on message values. On the raw log the scanner false-positives on
  the tag name `YiCamAlt/EncryptedP2pCredentialStore` (36 chars matched the base64 regex) —
  a tooling false positive, not a data leak.

RESULT: PASS — all 7 UAT items closed on the physical device (Mi 9, Android 11, APK v1.0.2/10002).
H.265 was not exercised (no such camera); that limitation is recorded, not treated as a failure.
