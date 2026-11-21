---
phase: 05-events-feed
plan: 00
subsystem: events
tags: [alert-list, media-crypto, probe, debug]
requires: []
provides:
  - AlertWire (LIST_PATH v2/alert/list, SEQ 0, PAGE_LIMIT, EXPIRES, SUCCESS, signedExtra, window, refreshWindow)
  - MediaCrypto (u32 LE + AES-128/ECB/NoPadding decrypt, magic, codec, tarPreview)
  - MediaHttp.plainClient (no interceptors, no authenticator, no logger)
  - AlertProbeShape (value-free list / URL-form / uid / field-kind / media line formatter, httpsVariant)
  - debug AlertProbe + Settings button «Проба событий (debug)»
  - LogRedactor masks from / to query keys
  - 05-PROBE-RESULTS.txt (value-free lines of the user's run)
affects: [05-01, 05-02, 05-03, 05-04, 05-05, 05-07]
tech-stack:
  added: []
  patterns:
    - "Shape-only device probe: counts, classes, digit counts, buckets; never values"
    - "Separate plain OkHttpClient for signed CDN media URLs"
key-files:
  created:
    - app/src/main/java/com/yicamalt/event/MediaCrypto.kt
    - app/src/main/java/com/yicamalt/event/AlertWire.kt
    - app/src/main/java/com/yicamalt/event/MediaHttp.kt
    - app/src/main/java/com/yicamalt/debug/AlertProbeShape.kt
    - app/src/main/java/com/yicamalt/debug/AlertProbe.kt
    - app/src/test/java/com/yicamalt/event/MediaCryptoTest.kt
    - app/src/test/java/com/yicamalt/event/AlertWireTest.kt
    - app/src/test/java/com/yicamalt/debug/AlertProbeShapeTest.kt
    - app/src/test/java/com/yicamalt/debug/AlertProbeShapeFormsTest.kt
    - .planning/phases/05-events-feed/05-PROBE-RESULTS.txt
  modified:
    - app/src/main/java/com/yicamalt/network/LogRedactor.kt
    - app/src/test/java/com/yicamalt/network/LogRedactorTest.kt
    - app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/yicamalt/ui/settings/SettingsScreen.kt
    - docs/verification-plan.xml
decisions:
  - "Probe v2 reports URL field forms (string / array, elems, scheme, host labels, query), uid match per id type (deviceId, p2pId, userId) and field kinds, because v1 left the URL form and the uid meaning ambiguous"
  - "An http media URL gets one https retry on the same path; the app network policy stays cleartext-off (the probe only reports)"
  - "Request cap raised 3 -> 5 per press (list + preview + clip, each media request + one https retry)"
metrics:
  completed: 2026-11-03
  tasks: 3
---

# Phase 5 Plan 00: Alert probe and pure media core Summary

The pure pieces of the events feed (vendor-ordered signed parameters for `GET v2/alert/list`, media decrypt u32 LE + AES-128-ECB, magic / codec / tar classes, a plain media client) are built test-first, and a debug-only one-press probe read the real alert feed on the user's account as value-free shape lines. The user ran it on the Mi 9; the result is recorded in `05-PROBE-RESULTS.txt` and turned into decisions in 05-01 (`05-PROBE.md`).

## Tasks

| # | Task | Commits |
|---|------|---------|
| 1 | Pure core: MediaCrypto, AlertWire, AlertProbeShape (RED -> GREEN) | fbf61ae, df8636a |
| 2 | AlertProbe runner, MediaHttp plain client, from / to masking, Settings button, build + install | 96fe96b |
| 2b | Probe v2 (deviation): URL forms, uid id-type match, field kinds, https variant (RED -> GREEN), https retry + cleartext policy line | 33f2329, 0da7463, 91e83e6 |
| 3 | User run on the Mi 9 (checkpoint), lines recorded value-free | b32ac08 (docs) |

## Verification

- Full JVM suite `:app:testDebugUnitTest --rerun :app:assembleDebug`: BUILD SUCCESSFUL, exit=0, 977 tests, 0 failures / errors.
- The probe ran on the Mi 9 twice (v1, then v2 after the extension). The v2 lines are in `05-PROBE-RESULTS.txt` (13 lines, requests=5); they contain no ids, URLs, hosts, keys or absolute times.

## Probe result (value-free, from 05-PROBE-RESULTS.txt)

- `code=20000`, 18 events, all from one camera (`c1(shared=false):18`), `oldestAgeDays=0`.
- `timeDigits=13`, `expireDigits=13` (ms); `expireInMin=5..1436` (media links live about a day).
- uid == deviceId 18/18 (also == p2pId); `idBlank=0`.
- `pic_urls` / `video_urls` are single strings (one element, not arrays), scheme http, with query, one host; `video02_urls` empty.
- `file_type` mp4 for all 18 (no ts); previews jpg (no tar / yitar); `pic_pwd` / `video_pwd` 16 chars.
- http media GET fails with `UnknownServiceException` (cleartext is off in the app); the https variant of the same path works: preview http=200, decrypt ok, JPEG <16KB; clip http=200, decrypt ok, MP4, AVC, 64-256KB.
- No `play_time` key in the response.

## Deviations from Plan

**1. [Rule 3 - Blocking] Probe v2 (extended shape lines)**
- Found during: Task 3 (first user run). The v1 run could not tell whether the URL fields are strings or arrays, why media failed, or what id type `uid` matches.
- Fix: AlertProbeShape gained URL field form, uid id-type and field-kind lines and `httpsVariant`; AlertProbe emits them. Commits 33f2329, 0da7463, 91e83e6.

**2. [Rule 3 - Blocking] https retry of http media URLs**
- Found during: Task 3. Media URLs are http and the app forbids cleartext (`UnknownServiceException`).
- Fix: one https retry on the same path per media request; the network security config was NOT changed.

**3. Request cap 5 instead of 3**
- Consequence of deviation 2: list + preview + clip + two https retries. Still no loops, no auth calls.

**4. docs/verification-plan.xml touched in 91e83e6** (plan reserved docs/*.xml for 05-06): small probe entry; 05-06 consolidates.

## Known Stubs

None (the probe is debug-only by design).
