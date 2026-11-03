---
phase: 05-events-feed
plan: 03
subsystem: events
tags: [event-media, previews, clips, decrypt, file-cache, prefetch]
requires: [05-02]
provides:
  - EventMediaRepository (prefetchThumbs, clip -> ClipResult) over MediaHttp.plainClient via the MediaFetcher port
  - EventMediaFiles (hashed paths, atomic writes, thumb purge, 50 MB clip LRU, wipe)
  - ThumbEncoder port + BitmapThumbEncoder (480 px wide, JPEG q80)
  - ThumbPrefetcher port in AlertSyncService (prefetch pass after Fetched, outside the Mutex)
  - Logout wipe of preview / clip files (EventCacheCleaner BLOCK_WIPE_FILES)
affects: [05-04, 05-05, 05-06]
tech-stack:
  added: []
  patterns:
    - "Ports for I/O edges (MediaFetcher, MediaRefSource, ThumbEncoder, ThumbPrefetcher) so the media layer is plain-JVM tested"
    - "dagger.Lazy<ThumbPrefetcher> breaks the AlertSyncService <-> EventMediaRepository cycle"
    - "Media file writes under EventCacheCleaner.writeIfCurrent: a logout wipe wins over a late download"
key-files:
  created:
    - app/src/main/java/com/yicamalt/event/EventMediaRepository.kt
    - app/src/main/java/com/yicamalt/event/EventMediaFiles.kt
    - app/src/main/java/com/yicamalt/event/ThumbEncoder.kt
    - app/src/test/java/com/yicamalt/event/EventMediaRepositoryTest.kt
  modified:
    - app/src/main/java/com/yicamalt/event/AlertSyncService.kt
    - app/src/main/java/com/yicamalt/event/EventCacheCleaner.kt
    - app/src/main/java/com/yicamalt/event/EventModule.kt
    - app/src/test/java/com/yicamalt/event/AlertSyncServiceTest.kt
    - app/src/test/java/com/yicamalt/test/FakeEventCache.kt
decisions:
  - "Fake MediaFetcher port instead of MockWebServer: media URLs are https and the fetcher is the only network edge; the real PlainMediaFetcher is a thin OkHttp wrapper over MediaHttp.plainClient"
  - "At most ONE refreshMedia per clip() call, whatever the trigger (missing ref, stale ref, first 403); a 403 after a refresh -> Failed(http)"
  - "A cached clip file (cacheDir/event_clips/<hash>.mp4) is returned without network and its lastModified is bumped for the LRU"
  - "Prefetch batch = records of this refresh without a thumb (newest first), then older thumb-less rows of the last 30 days that still have a MediaRef, capped at 100; rows without a MediaRef are skipped (no refresh from prefetch)"
  - "purgeThumbsOlderThan also deletes day folders strictly before the cutoff day (orphans of a crash between file write and row purge)"
  - "EventCacheCleaner gets EventMediaFiles; rows and files are wiped independently (runCatching), a Room error is rethrown after the file wipe so CameraRegistry still sees events=failed"
  - "ClipResult and clip() are internal (Ready exposes the internal MediaCrypto.Codec); all callers live in the app module"
metrics:
  duration: ~45 min
  completed: 2026-11-03
  tasks: 2
  files: 9
---

# Phase 5 Plan 03: EventMediaRepository Summary

Decrypted, 480 px JPEG previews saved under `filesDir/event_thumbs/<yyyyMMdd>/<sha256(key)[0..32]>.jpg` by a capped (100, newest first, 2 concurrent) prefetch pass that AlertSyncService launches after `Fetched` outside its Mutex, plus decrypted local MP4 clips in a 50 MB `cacheDir/event_clips` LRU with a single stale / missing / 403 refresh rule; purge and logout remove the files with their rows.

## Tasks

| Task | Name | Commits |
| ---- | ---- | ------- |
| 1 | EventMediaFiles, ThumbEncoder port, EventMediaRepository (thumbs + clips) | 9d643fc (RED), 285ed71 (GREEN) |
| 2 | Prefetch wired into sync, file purge and wipe | 8c1096c |

## What was built

- **EventMediaRepository** (`@Singleton`, M-EVENT-PLAYBACK): `prefetchThumbs(records)` with `Semaphore(2)`: `.yitar` skipped, `.tar` -> `MediaCrypto.tarPreview`, blank `pic_pwd` -> raw body, JPEG/PNG magic required, `ThumbEncoder.encode`, atomic write, `EventDao.setThumb(rel)`; every failure logged once and skipped. `clip(key)`: `hasClip` false / no row -> `Failed(no-clip)`, `file_type ts` -> `Unsupported` (no download), cached file -> `Ready`, missing / stale ref -> `refreshMedia` (null -> `Expired`), first 403 -> one refresh + one retry, MP4 magic required, write + `trimClips()`. `MediaHttp.secureUrl` applied before every GET; caps 2 MB thumbs / 8 MB clips.
- **EventMediaFiles**: SHA-256 hashed names, `thumbFile` refuses paths outside `event_thumbs` (Room value), tmp + `Files.move` writes, purge, LRU by `lastModified`, `wipeAll`. Hilt provider in EventModule.
- **AlertSyncService**: records collected per refresh; after `mutex.unlock()` and only on `Fetched`, `launchPrefetch` starts one pass in an application `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (AtomicBoolean guard; errors logged by class name). Commit block reads `thumbPathsOlderThan(cutoff)` before `clearOlderThan(cutoff)`; files deleted after the commit (`BLOCK_PURGE_FILES files=<n>`).
- **EventCacheCleaner.wipeAll**: `BLOCK_WIPE_FILES` -> `EventMediaFiles.wipeAll()` + `MediaRefStore.clear()`, log `[Event][wipe][BLOCK_WIPE_FILES] rows=ok|failed files=ok|failed`.
- Logs: `[EventMedia][thumb|clip][BLOCK_FETCH_THUMB|BLOCK_FETCH_CLIP] status bytes=<bucket> ext decryptOk magic`, `[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=missing|stale|403 result=ok|none`, `[EventMedia][thumb][BLOCK_PREFETCH_PASS] total saved skipped failed`. No URL, host, key, pwd, uid or hashed path in any line (asserted with RedactionScanner + literals).

## Verification

- `:app:testDebugUnitTest --rerun :app:assembleDebug` -> `BUILD SUCCESSFUL`, `--- gradle exit=0`; 1044 tests, 0 failures, 1 skipped (pre-existing). TutkCameraLinkTest was not flaky in this run.
- EventMediaRepositoryTest: 22 tests (>= 14 required). AlertSyncServiceTest: 33 tests (+9).
- Greps: `Semaphore(2)` = 1 line, `Retrofit|HttpClientModule` = 0 lines, `MediaHttp.plainClient` present in EventMediaRepository.kt; `prefetch` in AlertSyncService.kt; `START_BLOCK_WIPE_FILES` = 1 line and calls `files.wipeAll()`.

## Deviations from Plan

### Auto-fixed / adjusted

**1. [Rule 3 - Blocking] RED stub hook on EventMediaRepository.kt**
- **Found during:** Task 1 (RED)
- **Issue:** the stub hook blocked `error("RED stub")` bodies in the new EventMediaRepository.kt (it allowed them in EventMediaFiles.kt / ThumbEncoder.kt).
- **Fix:** the repository was written with its real implementation in the RED commit; RED was still real (17 of 22 tests failed on the EventMediaFiles / ThumbEncoder stubs, which were replaced in the GREEN commit).
- **Commit:** 9d643fc

**2. [Plan detail] `MediaFetcher.get(url, maxBytes)`**: the size cap is a parameter (2 MB thumbs / 8 MB clips) instead of a per-fetcher constant; `FetchResponse` carries `CODE_IO` / `CODE_TOO_LARGE`.

**3. [Plan detail] No `EventMediaRepository.purge(nowMs)`**: the purge path is AlertSyncService (paths read before the rows are deleted, as Task 2 requires); a second entry point would have no caller. The plan's purge test targets `EventMediaFiles.purgeThumbsOlderThan` directly.

**4. [Plan detail] EventMediaFiles has no clock parameter** (only `zone`): nothing in it needs the current time; cutoff is passed in.

**5. [Rule 2 - Critical] Logout race for media writes**: thumb and clip writes run under `EventCacheCleaner.writeIfCurrent(generation)` (the repository takes the cleaner), so a download that finishes after a logout writes nothing. `thumbFile` also rejects relative paths that leave `event_thumbs`.

**6. [Scope] EventModule touched in Task 1** (EventMediaFiles provider) and FakeEventCache.kt in Task 2 (cleaner constructor gained EventMediaFiles; temp folders).

## Known Stubs

None. `BitmapThumbEncoder` is Android-only and not JVM-tested (tests use a pass-through encoder); it is exercised on device in 05-07 UAT.

## Self-Check: PASSED
