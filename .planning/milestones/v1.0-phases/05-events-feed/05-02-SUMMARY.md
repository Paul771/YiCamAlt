---
phase: 05-events-feed
plan: 02
subsystem: events
tags: [room-v3, alert-sync, rate-limit, media-refs, logout-wipe]
requires: [05-01]
provides:
  - Room v3 events table (event_key PK, time_ms, no URL / key column) + EventDao feed / calendar / thumbnail queries
  - EventCacheCleaner (wipeAll, EventMetaKeys, generation + writeIfCurrent) wired into the CameraRegistry wipe
  - AlertSync (RefreshGate, PagePlanner, MAX_PAGES 10, gap resume)
  - AlertSyncService (refresh, refreshMedia, mediaFor, lastFetchMs) + MediaRefStore + SyncResult
affects: [05-03, 05-04, 05-05, 05-06]
tech-stack:
  added: []
  patterns:
    - "Persisted rate gate in app_meta: newer of last-success and last-answered-failure marks"
    - "Wipe generation + writeIfCurrent: a sync started before logout writes nothing afterwards"
    - "Bounded do/while paging driven by a pure planner (no open loop)"
key-files:
  created:
    - app/src/main/java/com/yicamalt/event/AlertSync.kt
    - app/src/main/java/com/yicamalt/event/AlertSyncService.kt
    - app/src/main/java/com/yicamalt/event/EventCacheCleaner.kt
    - app/src/test/java/com/yicamalt/event/AlertSyncTest.kt
    - app/src/test/java/com/yicamalt/event/AlertSyncServiceTest.kt
    - app/src/test/java/com/yicamalt/test/FakeEventCache.kt
  modified:
    - app/src/main/java/com/yicamalt/database/EventEntity.kt
    - app/src/main/java/com/yicamalt/database/EventDao.kt
    - app/src/main/java/com/yicamalt/database/YiDatabase.kt
    - app/src/main/java/com/yicamalt/camera/CameraRegistry.kt
    - app/src/main/java/com/yicamalt/event/EventModule.kt
    - app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt
    - app/src/test/java/com/yicamalt/camera/CameraRegistryModuleTest.kt
    - app/src/test/java/com/yicamalt/camera/CameraRegistryCredentialsTest.kt
decisions:
  - "Gate reads the newer of events_last_fetch_ms and events_last_attempt_ms; Offline (IOException) writes neither"
  - "One saved gap boundary (events_gap_to_sec): a capped run stores it, the next refresh does the newer part first, then window.from..gap, and clears it when that ends Done"
  - "The event wipe runs inside CameraRegistry.wipeLocked, so logout, the start-up purge and the no-cameras answer all wipe the alert cache"
  - "MediaRefStore is a public @Singleton (not internal): the public AlertSyncService @Inject constructor takes it"
  - "HttpException other than 401 -> Failed(http-<code>), unreadable body -> Failed(malformed); both count as answered (attempt mark)"
metrics:
  completed: 2026-11-03
  tasks: 2
---

# Phase 5 Plan 02: Event cache v3 and account-safe alert sync Summary

Room v3 stores value-free alert rows (event_key, time_ms, type, sub_type, file_type, has_clip, play_time_s, thumb_path, cached_at). `AlertSyncService.refresh()` is single flight and gated by a 5-minute mark kept in app_meta, so the gate survives a restart. It pages backward through signed `v2/alert/list` requests, at most 10 pages per refresh, and resumes a capped run from a saved gap. Rows are de-duplicated by key and anything older than 30 days is purged. Signed media links and keys stay in an in-memory `MediaRefStore`. `refreshMedia(key)` makes one ±5 s request, limited to once per 60 s per key and 6 per rolling minute overall. Logout wipes rows, the event meta keys and the media refs.

## Tasks

| # | Task | Commits |
|---|------|---------|
| 1 | Room v3 events table, DAO, EventCacheCleaner, CameraRegistry logout hook | fc29ceb |
| 2 | RED: AlertSyncTest (13), AlertSyncServiceTest (24), stubs | 7d80a94 |
| 2 | GREEN: AlertSync, AlertSyncService, EventModule comment | b0a0c28 |

## PROBE-GATE

D-PROBE-7: links live 5..1436 minutes, not under 1 minute, so there was no stop. D-PROBE-8: count was 18, below the probe limit of 20, so paging was not proven mandatory. The multi-page tests exist anyway: paging until a short page, the boundary duplicate, the 10-page cap with gap resume.

## Verification (actually run)

- Task 1: `:app:testDebugUnitTest --rerun --tests "com.yicamalt.database.*" --tests "com.yicamalt.camera.*" :app:assembleDebug` gave BUILD SUCCESSFUL, `--- gradle exit=0` (114 tests, 0 failures).
- RED: event + CameraRegistryModuleTest gave 93 tests, 36 failed, all on `RED stub`. The one passing new test was `gate interval is five minutes` (constant only).
- Full suite `:app:testDebugUnitTest --rerun :app:assembleDebug`: BUILD SUCCESSFUL, `--- gradle exit=0`, **1013 tests, 0 failures, 0 errors, 1 skipped** (71 suites, counted from JUnit XML; ForbiddenEndpointsTest included). The count is 971 + 37 new sync tests + 3 net YiDatabaseTest + 2 CameraRegistryModuleTest. The TutkCameraLinkTest flake did not occur, so no rerun was needed.
- Greps: `version = 3` gives 1 line. `payload_json|pwd|url` in EventEntity.kt gives 0. `wipeAll` in CameraRegistry gives 1. `@Test` counts are AlertSyncTest 13 and AlertSyncServiceTest 24. `events_last_fetch_ms` in EventCacheCleaner gives 1. `WorkManager|PeriodicWork|while (true)` in event/ gives 0. `MAX_PAGES = 10` gives 1.
- `app/schemas` does not exist: no `room.schemaLocation` is configured, so there is no 3.json to commit.

## Deviations from Plan

**1. [Rule 2 - Security] Logout race guard.** EventCacheCleaner has a wipe `generation` and `writeIfCurrent`. Every write in the sync (page upserts, media puts, marks, purge) runs under that guard and also checks the session. A refresh that started before logout therefore writes nothing afterwards and returns NoSession. Test: `a logout during a refresh writes nothing afterwards`. wipeAll also clears MediaRefStore, which was one of the options the plan offered.

**2. PagePlanner API shape.** `first(nowMs, zone, newestCachedMs)` handles the newer part. A separate `gap(nowMs, zone, gapToSec)` returns the older range and replaces the `gapToSec` parameter of `first`. Clearing the gap after a Done run is tested at service level: `ten pages cap saves the gap and the next refresh resumes and clears it`.

**3. MediaRefStore is public, not internal.** It is a constructor type of the public `@Inject` constructor.

**4. Wipe placement.** The event wipe is in `wipeLocked()`, which is shared by logout, the start-up purge and the 20200 / empty-list path, under `runCatching`. The log line is `[CameraRegistry][wipe][BLOCK_WIPE] events=wiped|failed`.

**5. Test infrastructure.** The CameraRegistry tests are plain JVM (JUnit 5, no Room). They use the new `test/FakeEventCache.kt` (FakeEventDao, FakeAppMetaDao, cleaner). CameraRegistryCredentialsTest's 4 constructor calls were updated (it was not in the plan's file list).

**6. Extra tests beyond the plan list.**
- YiDatabaseTest: `events table has no url or key column`.
- CameraRegistryModuleTest: `scenario_14b a failing event wipe does not block the credential and camera wipe`.
- AlertSyncTest: `gate interval is five minutes`, `full page without usable records stops without progress`, `a gap boundary older than the window is dropped`.
- AlertSyncServiceTest: `http 401 maps to Unauthorized without retry`, `incremental refresh starts from the newest cached time`, `no session makes no request`, `refreshMedia of an unknown key makes no request`, `lastFetchMs reads the persisted mark`, `ten pages cap ...`, `a logout during a refresh ...`.

**7. RED commit carried minimal real code.** EventCacheCleaner's MediaRefStore parameter and generation guard, and the MediaRefStore class, were implemented in the RED commit because the tests had to compile against them. Only `AlertSync` and `AlertSyncService` were `error("RED stub")`, both in new files.

**8. EventDao.** `insertEvent`, `getByDevice`, `getByDeviceInWindow`, `getById` and `countByDevice` were removed; grep found no callers. `insertAllRaw` and the `thumbsFor` projection (`KeyThumb`) were added to support the thumbnail-preserving upsert.

## Known Stubs

None. `START_BLOCK_WIPE_FILES` in EventCacheCleaner is a comment-only placeholder for 05-03 (file deletion), as the plan specified.

## Deferred Issues

None new. Server retention and shared-camera behaviour remain open for UAT 05-07 (from 05-01).

## Self-Check: PASSED
