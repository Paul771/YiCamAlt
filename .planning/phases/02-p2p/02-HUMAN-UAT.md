---
status: partial
phase: 02-p2p
source: [02-VERIFICATION.md]
started: 2026-10-20
updated: 2026-10-20
---

## Current Test

[awaiting the TNP camera ("Спальня") to be found and powered; USB phone with the HEAD APK]

## Tests

### H1. TNP camera on home Wi-Fi
expected: relay off, press "Подключить": picture moves; transport=TNP state=STREAMING lan=yes auth=ok decryptOk=yes fps>0; note codec.
result: [pending]

### H2. TNP camera on mobile data
expected: Wi-Fi off, 4G on; if no picture in 20 s retry once with "Только relay"; picture moves, lan=no, mode P2P or a relay mode.
result: [pending]

### H3. Relay-only
expected: TNP: relayForced=yes, mode RELAY/TCP_RELAY/SDEV_RELAY and picture moves (or the error name). TUTK: checkbox disabled with hint.
result: [pending]

### H4. H.265
expected: if a camera is H265 it plays like H.264; otherwise record "H.265 path not exercised on device".
result: [pending]

### H5. TUTK regression after commits a36b18b, 9548600, c1ec44b
expected: connect, stop, reconnect, leave and reopen: video each time, stop <= ~2 s, no queue-overflow decoder reset, relay checkbox disabled.
result: [pending]

### H6. Hand-over with the official app
expected: split screen if possible, otherwise one app after the other, both cameras; BUSY/KICKED is a Phase 3 item, not a failure.
result: [pending]

### H7. Log pull and redaction sweep
expected: pull the live-session log, run RealLogScanTest: skipped=0 failures=0; TNP connect, session-check and start-command markers present.
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
