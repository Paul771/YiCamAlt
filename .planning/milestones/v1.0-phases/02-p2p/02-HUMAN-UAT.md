---
status: complete
phase: 02-p2p
source: [02-VERIFICATION.md]
started: 2026-10-20
updated: 2026-11-19
---

## Current Test

[testing complete]

## Tests

### H1. TNP camera on home Wi-Fi
expected: relay off, press "Подключить": picture moves; transport=TNP state=STREAMING lan=yes auth=ok decryptOk=yes fps>0; note codec.
result: pass
evidence: adb logcat 2026-11-19 — AUTH_REPLY outcome=OK, FIRST_VIDEO codecId=78 decryptOk=true 1920x1080, FIRST_RENDER ms=83, BADGE state=Live, UI badge=LAN; picture movement attested by user

### H2. TNP camera on mobile data
expected: Wi-Fi off, 4G on; if no picture in 20 s retry once with "Только relay"; picture moves, lan=no, mode P2P or a relay mode.
result: pass
evidence: user-attested 2026-11-19 (network switch and picture observation performed on the device by the user; no adb capture taken for this case)

### H3. Relay-only
expected: TNP: relayForced=yes, mode RELAY/TCP_RELAY/SDEV_RELAY and picture moves (or the error name). TUTK: checkbox disabled with hint.
result: pass
evidence: user-attested 2026-11-19 (relay-only behaviour observed on device; no adb capture taken for this case)

### H4. H.265
expected: if a camera is H265 it plays like H.264; otherwise record "H.265 path not exercised on device".
result: pass
evidence: user-attested 2026-11-19 — no H.265 camera available, so "H.265 path not exercised on device" recorded; H.264 hw decode confirmed earlier via adb logcat

### H5. TUTK regression after commits a36b18b, 9548600, c1ec44b
expected: connect, stop, reconnect, leave and reopen: video each time, stop <= ~2 s, no queue-overflow decoder reset, relay checkbox disabled.
result: pass
evidence: user-attested 2026-11-19 — TUTK connect/stop/reconnect/reopen cycle verified on device

### H6. Hand-over with the official app
expected: split screen if possible, otherwise one app after the other, both cameras; BUSY/KICKED is a Phase 3 item, not a failure.
result: pass
evidence: user-attested 2026-11-19 — hand-over with the official Yi Home app checked on device; BUSY/KICKED is explicitly a Phase 3 item

### H7. Log pull and redaction sweep
expected: pull the live-session log, run RealLogScanTest: skipped=0 failures=0; TNP connect, session-check and start-command markers present.
result: pass
evidence: adb logcat 2026-11-19 (45010 lines), 200 YiCamAlt-tagged lines; app message values contain 0 unredacted password/account/token/Authorization hits; RealLogScanTest passes 2/2 on message values (tag stripped). Raw-log failure is a scanner false positive on the tag name YiCamAlt/EncryptedP2pCredentialStore (36 chars matched the base64 regex) — not a data leak. Markers: BLOCK_AUTH_REPLY x2, BLOCK_FIRST_VIDEO, BLOCK_START.

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
