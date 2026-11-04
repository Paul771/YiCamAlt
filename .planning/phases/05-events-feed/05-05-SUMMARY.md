---
phase: 05-events-feed
plan: 05
subsystem: ui-events, ui-live
tags: [events, clip, mediaplayer, textureview, sd-archive, open-at]
requires: [05-03 EventMediaRepository.clip, 05-04 EventsScreen + clipKey, Phase 4 ArchiveCoordinator]
provides: [ArchiveCoordinator.openAt, LiveViewModel.openAt, LiveViewScreen(initialArchiveMs), ClipViewModel, ClipDialog, shell wiring]
affects: [AppShellScreen, LiveViewScreen]
tech-stack:
  added: []
  patterns: [one-shot pending jump resolved after a load, platform MediaPlayer on TextureView in a Compose Dialog]
key-files:
  created:
    - app/src/main/java/com/yicamalt/ui/events/ClipViewModel.kt
    - app/src/main/java/com/yicamalt/ui/events/ClipDialog.kt
    - app/src/test/java/com/yicamalt/ui/events/ClipViewModelTest.kt
  modified:
    - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
    - app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt
    - app/src/main/java/com/yicamalt/ui/live/LiveViewModel.kt
    - app/src/test/java/com/yicamalt/ui/live/LiveViewModelTest.kt
    - app/src/main/java/com/yicamalt/ui/live/LiveViewScreen.kt
    - app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt
decisions:
  - "openAt outcome enum QUEUED / FIRED / HIDDEN / IGNORED; a jump that was resolved, dropped (no card) or cancelled by the user never runs again on that coordinator (one coordinator per screen entry)"
  - "A jump to a day without recordings shows «Нет записи в это время» as the Live hint (openAtHint) and keeps today's panel; any user archive action clears it"
  - "ClipSource returns a public ClipLoad mirror of the internal ClipResult so the Hilt-bound port has no internal types"
  - "Shell resolves the camera title for the SD jump from the EventsViewModel chips (same cached camera list that enables the button) and builds a minimal CameraInfo; LiveViewScreen uses only deviceId and name"
  - "LiveViewScreen: the Toast collector LaunchedEffect now starts before open/openAt, because the hidden-archive Toast is emitted synchronously and LiveViewModel.events has no replay"
metrics:
  duration: 33min
  completed: 2026-11-04
  tasks: 3
  files: 9
---

# Phase 5 Plan 05: Clip dialog and event -> SD jump Summary

Tapping an event opens a Compose Dialog that loads the decrypted local MP4 through EventMediaRepository.clip and plays it with the platform MediaPlayer on a TextureView. «Открыть запись на SD» opens that camera's live screen with `initialArchiveMs`, and `ArchiveCoordinator.openAt` runs one seek to the exact event time (EVENT_SD_PREROLL_MS = 0) once the archive index has loaded.

## What was built

- **ArchiveCoordinator.openAt(targetMs)** (BLOCK_ARCHIVE_OPEN_AT): if the archive is hidden it returns HIDDEN. Otherwise the jump stays queued until the index is known; the check runs on the call and after every load. Today or a day with recordings: `selectedDay = day` + `seekNow(target)` outside the lock, so seek's refresh-first rules still apply. Any other day: hint «Нет записи в это время», panel unchanged. NO_CARD / CARD_ERROR: the jump is dropped and the panel text explains why. LIST_FAILED: the jump stays queued until a retry or a liveEpoch reload succeeds. A user seek / selectDay / togglePause / goLive cancels a queued jump. Logs contain the result only.
- **LiveViewModel.openAt** forwards the call (action `open-at`). If the archive is hidden it shows Toast «Архив SD на этой камере недоступен».
- **LiveViewScreen(initialArchiveMs: Long? = null)**: `LaunchedEffect(deviceId, initialArchiveMs)` calls open() and then openAt once per screen entry. Logs `hasOpenAt` only.
- **ClipViewModel**: `ClipSource` port (RepositoryClipSource over EventMediaRepository.clip + EventDao.getByKey, bound in ClipUiModule). Sets camera name and «d MMMM, HH:mm:ss» label. The SD button is on only for a camera in the cached list, otherwise it reads «Камера недоступна». Failure texts: «Клип недоступен» / «Клип устарел, обновите ленту» / «Не удалось загрузить клип» / «Не удалось воспроизвести клип». Opening the same key while it is shown does nothing; a new key cancels the old load; dismiss cancels; `OpenSd` has a redacted toString.
- **ClipDialog**: 16:9 black box showing a spinner, a TextureView with MediaPlayer (setDataSource(file.path), setSurface, prepareAsync, start on prepared, onError -> onPlayerError), or a message. The player is released in onSurfaceTextureDestroyed and in DisposableEffect. Buttons: «Повторить» (seekTo(0) + start), «Закрыть», «Открыть запись на SD».
- **AppShellScreen**: ClipDialog is shown for `clipKey` on the Events tab. onOpenSd sets `liveCamera` + `liveArchiveMs` (plain remember; no rememberSaveable in the file). Closing the live screen clears both and returns to the Events tab. A camera-list open resets liveArchiveMs. Logs `tab=live source=event|list`.

## Verification

- Task 1: `:app:testDebugUnitTest --rerun --tests "com.yicamalt.ui.live.*"`: BUILD SUCCESSFUL, 164 tests (12 new).
- Task 2: `--tests "com.yicamalt.ui.events.*" :app:assembleDebug`: BUILD SUCCESSFUL, 33 tests (11 new).
- Full gate: `:app:testDebugUnitTest --rerun :app:assembleDebug`: **BUILD SUCCESSFUL, `--- gradle exit=0`, 1089 tests, 0 failures, 1 skipped**. No flaky reruns were needed.
- Acceptance greps: `EVENT_SD_PREROLL_MS = 0L` 1 line; `openAt` in ArchiveCoordinatorTest 22; `@Test` in ClipViewModelTest 11; TextureView in ClipDialog yes; SurfaceView/VideoView/exoplayer/media3 0; `release()` 8; `initialArchiveMs` in LiveViewScreen 7; `ClipDialog(` in shell 1; rememberSaveable in shell 0.
- No phone was attached, so the adb install was skipped. Device behaviour (codec, real SD seek) is left for 05-07.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] RED stub hook on LiveViewModel.openAt**
- **Found during:** Task 1 RED
- **Issue:** The PreToolUse hook blocked `error("RED stub")` in LiveViewModel.kt.
- **Fix:** Wrote the real one-line forwarding implementation in the RED commit. The tests still failed through the ArchiveCoordinator.openAt stub (12 failing). The ClipViewModel stubs were not blocked.
- **Commit:** f7fad69

**2. [Rule 1 - Bug] Hidden-archive Toast lost at screen entry**
- **Found during:** Task 3
- **Issue:** `LiveViewModel.events` is a SharedFlow without replay. open() + openAt() run synchronously in the first LaunchedEffect, so the HIDDEN Toast was emitted before the Toast collector subscribed.
- **Fix:** The collector LaunchedEffect is now declared before the open/openAt effect.
- **Commit:** 2064fa1

**3. [Rule 1 - Test bug] OpenSd collector in backgroundScope**
- **Found during:** Task 2 GREEN
- **Issue:** `advanceUntilIdle` does not run tasks that exist only in backgroundScope, so the collected events list stayed empty.
- **Fix:** Drain with `runCurrent()` after openSd. This is test-only; no assertion was weakened.
- **Commit:** 48d000f

**4. [Plan wording] Shell camera lookup**
- The plan offered several options. The shell uses `hiltViewModel<EventsViewModel>()` (the same activity-scoped instance as EventsScreen) and its camera chips for the title, and passes it explicitly to EventsScreen.

## Known Stubs

None.

## Self-Check: PASSED
