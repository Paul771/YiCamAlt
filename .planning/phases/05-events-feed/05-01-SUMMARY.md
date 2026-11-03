---
phase: 05-events-feed
plan: 01
subsystem: events
tags: [alert-list, mapper, deny-list, probe-gate]
requires: [05-00]
provides:
  - 05-PROBE.md (D-PROBE-1..8, Gate CONTINUE)
  - AlertListApi (GET v2/alert/list, no defaults) + AlertEnvelope (code string or number)
  - AlertMapper.page -> AlertPage(records: AlertRecord, media: MediaRef)
  - AlertRecord / MediaRef / AlertPage (EventInfo.kt)
  - MediaHttp.secureUrl (http -> https for media URLs only)
  - ForbiddenEndpointsTest (deny list over app/src/main/java)
affects: [05-02, 05-03, 05-04, 05-05, 05-06]
tech-stack:
  added: []
  patterns:
    - "Custom KSerializer for a code that arrives as string or number"
    - "Media URL scheme upgrade in the mapper instead of a cleartext exception"
    - "Source-walking deny-list test with a planted-literal self check and a minimum file count"
key-files:
  created:
    - .planning/phases/05-events-feed/05-PROBE.md
    - app/src/main/java/com/yicamalt/event/AlertListApi.kt
    - app/src/main/java/com/yicamalt/event/AlertMapper.kt
    - app/src/test/java/com/yicamalt/event/AlertMapperTest.kt
    - app/src/test/java/com/yicamalt/event/ForbiddenEndpointsTest.kt
  modified:
    - app/src/main/java/com/yicamalt/event/EventInfo.kt
    - app/src/main/java/com/yicamalt/event/EventModule.kt
    - app/src/main/java/com/yicamalt/event/MediaHttp.kt
    - app/src/main/java/com/yicamalt/event/AlertWire.kt
    - app/src/main/java/com/yicamalt/network/HttpClientModule.kt
    - .planning/phases/05-events-feed/05-02-PLAN.md
    - .planning/phases/05-events-feed/05-03-PLAN.md
  deleted:
    - app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt
    - app/src/main/java/com/yicamalt/event/EventServiceModule.kt
    - app/src/test/java/com/yicamalt/event/EventServiceModuleTest.kt
decisions:
  - "Gate CONTINUE: code 20000, 18 events, 13-digit ms times, uid == deviceId 18/18, decrypt ok on preview and clip over https"
  - "pic_urls / video_urls (plural) carry ONE string each; the mapper keeps the comma split for tolerance"
  - "Media URLs are rewritten http -> https in AlertMapper via MediaHttp.secureUrl; cleartext stays off (no network-security-config change)"
  - "play_time is absent on real data: playTimeS defaults to 0"
  - "expires=10 is not a 10-minute lifetime: links live up to about a day; server retention unknown (oldestAgeDays=0)"
metrics:
  completed: 2026-11-03
  tasks: 2
---

# Phase 5 Plan 01: Probe facts and real alert wire layer Summary

05-PROBE.md records the device run (value-free) with Gate CONTINUE. The guessed event client (`/v5/alert/events`, the recording playback path, the guessing parser and its 23 tests) is gone; the event layer is now `AlertListApi` (GET v2/alert/list, caller-supplied signed values), `AlertMapper` (strict, opt-tolerant, plural URL fields as single strings, https media URLs) and `ForbiddenEndpointsTest`.

## Tasks

| # | Task | Commits |
|---|------|---------|
| 1 | 05-PROBE.md from 05-PROBE-RESULTS.txt, D-PROBE-1..8, Gate | 0f6bc5d |
| 2 | RED: AlertMapperTest (14), ForbiddenEndpointsTest (3), new types / API, old client removed | 045ff31 |
| 2 | GREEN: AlertMapper, comment cleanups (HttpClientModule, AlertWire) | dbf82fc |
| - | Plan alignment 05-02 / 05-03 with probe facts | ce981d8 |

Also on this branch for plan 05-00: 4ad3865 (05-00-SUMMARY.md).

## Verification (actually run)

- RED: `:app:testDebugUnitTest --rerun --tests "com.yicamalt.event.*"`: 32 tests, 12 failed (AlertMapper stub); ForbiddenEndpointsTest 3/3 already green after the deletion.
- Full suite `:app:testDebugUnitTest --rerun :app:assembleDebug`: first run 971 tests, 1 failure in `TutkCameraLinkTest` "registry entry is released only after a stuck connect thread exits" (thread timing, file not touched); rerun BUILD SUCCESSFUL, `--- gradle exit=0`, **971 tests, 0 failures, 0 errors, 1 skipped** (counted from JUnit XML). 977 - 23 removed + 17 new = 971.
- `grep -rn "alert/events\|bs/v8/recording\|EVENT_LIST_V4\|EventTimelineParser\|getPlaybackUrl" app/src/main/java` = 0 lines.
- `@Test` count: AlertMapperTest 14, ForbiddenEndpointsTest 3; `@GET(AlertWire.LIST_PATH)` = 1 line; the three deleted files no longer exist.
- 05-PROBE.md: 9 `D-PROBE-` lines; no URL and no 10+ digit run.

## Deviations from Plan

**1. [Rule 1 - Probe fact] https rewrite of media URLs (MediaHttp.secureUrl, used by AlertMapper)**
- Probe: URLs are http, cleartext is off, https on the same path works. MediaRef stores the https form; MediaHttp.kt (not in the plan's file list) got the pure `secureUrl`. No cleartext exception was added. Extra tests: `http media urls are rewritten to https with path and query kept`, `secure url keeps https and non default port and leaves unparseable text`.

**2. [Rule 1 - Probe fact] play_time absent** — mapper defaults `playTimeS` to 0; test `absent play_time maps to 0`.

**3. Extra mapper test** `data that is not an array yields no records` (14 tests instead of 10).

**4. Deletions moved into the RED commit** — the rewritten EventInfo.kt would not compile next to the old client, so `git rm` happened in 045ff31 instead of the feat commit.

**5. Own comments reworded** — new CHANGE_SUMMARY lines must not name removed paths / types (strict acceptance grep); AlertWire's comment that referred to the old EventPaths was updated too (comment-only, v0.1.1).

**6. Log not re-pulled over adb** — 05-PROBE.md uses the already value-free `05-PROBE-RESULTS.txt` (user-run, swept lines) as its source; the number of alerts seen in Yi Home was not reported (count 18 > 0, so the gate condition does not depend on it).

**7. Plan edits (small)** — the plans never used singular `pic_url` / `video_url` (05-01 and research already had the plural names); no array assumption existed outside "comma list". Added probe facts to 05-02 (interfaces note: plural single-string fields, https MediaRef, play_time 0, day-long link lifetime, unknown retention) and 05-03 (PROBE-GATE confirmed; fetcher applies `MediaHttp.secureUrl` defensively, never enables cleartext; `secureUrl` in interfaces). 05-04 / 05-05 needed no change.

## Deferred Issues

- `TutkCameraLinkTest` "registry entry is released only after a stuck connect thread exits" failed once on thread timing and passed on rerun; pre-existing, out of scope.
- Server retention of events and behaviour of shared cameras remain open (D-PROBE-4 / D-PROBE-8) for device UAT 05-07.

## Known Stubs

None.

## Self-Check: PASSED
