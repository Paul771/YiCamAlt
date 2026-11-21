---
phase: 01-secrets-camera-list
plan: 10
subsystem: ui
tags: [camera-list, compose, material3, pull-to-refresh, cam-01, cam-04, cam-05, sec-01]

requires:
  - phase: 01-09
    provides: CameraRegistry v0.5.0 (getCameraList with distinct outcomes empty / Unauthorized / ServerRejected / FetchFailed, cachedCameras), CameraInfo.transport / shared
provides:
  - CameraListUiState (Loading | Content(cameras, refreshing, staleError) | Empty(refreshing) | Error(kind, message))
  - ErrorKind NETWORK / SERVER / UNAUTHORIZED with Russian messages
  - cache-first, single-flight CameraListViewModel (no timers, refresh only on open and explicit pull/retry)
  - CameraListScreen with PullToRefreshContainer, state screens, stale banner, transport + shared mark, no device id
  - CameraListScreen(onSessionExpired) wired to AppShellScreen onLogout
affects: [01-11 on-device verification, phase-02 live view (camera row tap target)]

tech-stack:
  added: []
  patterns:
    - "Every UI state rendered as LazyColumn items so pull-to-refresh works from Loading / Empty / Error too"
    - "One pull = one refresh: LaunchedEffect -> refresh() -> state.first { !isRefreshing() } -> endRefresh()"
    - "refresh() sets the transitional state synchronously, so observers never see a stale non-refreshing state"

key-files:
  created: []
  modified:
    - app/src/main/java/com/yicamalt/ui/cameras/CameraListViewModel.kt
    - app/src/main/java/com/yicamalt/ui/cameras/CameraListScreen.kt
    - app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt
    - app/src/test/java/com/yicamalt/ui/cameras/CameraListViewModelTest.kt
    - docs/knowledge-graph.xml
    - docs/development-plan.xml
    - docs/verification-plan.xml

key-decisions:
  - "Empty network list (20200) => Empty state even if the cache had cameras (server is the source of truth)"
  - "Failed refresh over a non-empty list keeps the list and shows a stale banner instead of an error screen"
  - "UNAUTHORIZED: primary button 'Войти снова' calls onSessionExpired = AppShell onLogout (session + camera data wiped, login screen); 'Повторить' stays as secondary"
  - "Single-flight via the active refresh Job (the init refresh counts as in flight)"

requirements-completed: [CAM-01, CAM-04, CAM-05]

duration: 25min
completed: 2026-10-11
---

# Phase 01 Plan 10: Camera list states, pull-to-refresh, cache-first start Summary

**The Cameras tab now shows the Room cache instantly, refreshes once from `/v5/devices/list`, distinguishes Loading / Content / Empty ("Камеры не найдены") / three typed errors with "Повторить", keeps the old list under a stale banner when a refresh fails, supports material3 1.2.0 pull-to-refresh, and no longer renders the device id.**

## Screens and states

| State | When | What the user sees |
|---|---|---|
| Loading | empty cache, first network call running; retry from Error | centered spinner |
| Content(refreshing=true) | cache has cameras, network running | cached list at once |
| Content | network returned cameras | rows: name (or "Камера"), subtitle `model · онлайн/офлайн · TNP/TUTK/Langtao/транспорт ? · общая` |
| Content(staleError) | refresh failed over a non-empty list | list stays; banner "Не удалось обновить: <message>" + "Повторить" (+ "Войти" for UNAUTHORIZED) |
| Empty | network list empty (code 20200) | "Камеры не найдены", hint "Камеры добавляются в официальном приложении Yi Home. Потяните вниз, чтобы обновить.", "Повторить" |
| Error NETWORK | FetchFailed or any other Throwable | "Нет связи с сервером Yi. Проверьте интернет." + "Повторить" |
| Error SERVER | ServerRejected | "Сервер Yi вернул ошибку. Попробуйте позже." + "Повторить" |
| Error UNAUTHORIZED | Unauthorized | "Сессия истекла. Войдите снова." + "Войти снова" (-> logout -> login screen) + "Повторить" |

Requests: one `getCameraList()` on screen open and one per explicit pull / retry press; concurrent presses are ignored while one runs. No timers, no polling.

## Task Commits

1. **Task 1: cache-first ViewModel, Empty/typed Error, single-flight refresh**: `e2d301f`
2. **Task 2: CameraListScreen states, pull-to-refresh, transport badge, no serial**: `d6846dd`
3. **Docs sync (knowledge-graph, development-plan, verification-plan)**: `65c4c41`

## Verification evidence

- Task 1 filter run could not finish at first: the working tree contained another agent's uncommitted edits (see below). Final gate after all commits: `:app:testDebugUnitTest :app:assembleDebug` -> `BUILD SUCCESSFUL in 20s`, `--- gradle exit=0 elapsed=21.3s`. Result: 22 suites, 278 tests, 1 skipped, 0 failures, 0 errors. CameraListViewModelTest: tests=17, failures=0. `app/build/outputs/apk/debug/app-debug.apk` exists (18 MB).
- Acceptance greps: `deviceId` in CameraListScreen.kt = 1 line (`key = { it.deviceId }`); PullToRefresh API matches = 6; `PullToRefreshBox` none; `ptr.endRefresh()` directly after `viewModel.state.first` in the same LaunchedEffect; "Камеры не найдены" = 1; `"Повторить"` = 4; `"TNP"` and `"общая"` 1 each; the three error strings in the ViewModel; `@Test` = 17; `app/build.gradle.kts` untouched. No `deviceId` rendered anywhere in `ui/`.
- Logs are value-free: `[Cameras][load][BLOCK_LOAD_CAMERAS] source=cache|network result=content|empty|error|stale count=N kind=KIND|-`.

## Deviations from Plan

1. **[Rule 2 - Missing functionality] UNAUTHORIZED leads back to login.** The executor brief for CAM-04 says "unauthorized -> back to login"; the plan only had "Повторить". `CameraListScreen` got an `onSessionExpired: () -> Unit = {}` parameter; the Error UNAUTHORIZED screen shows "Войти снова" (primary) plus "Повторить", and the stale banner shows "Войти". `AppShellScreen` (v0.1.2, not in the plan's file list) passes `onLogout`, which clears the session and camera data and shows the login screen. Commit `d6846dd`.
2. **Task 1 commit kept compilable.** The new `Empty` state made the old screen's `when` non-exhaustive. Commit `e2d301f` therefore carries a one-line `Empty` branch in the old screen (staged as a separate blob), replaced by the full screen in `d6846dd`.
3. **TDD RED/GREEN combined** into one commit per task (same as 01-09).
4. **Extra tests** beyond the behaviour list: stale banner cleared while pulling, `isRefreshing()` per state, refresh state set synchronously. Commit subject of Task 2 is the plan's `grace(M-UI-SHELL): ...` line.
5. **Trailer.** The executor brief asked for `Co-Authored-By: Claude Sonnet 5.5`; the plan's phase rules and the session's attribution instruction say `Claude Opus 5.5`, which is what the commits carry.

## Environment note (not a deviation of this plan)

The working tree contained another agent's uncommitted SEC-04 logout work during this plan (`MainActivity.kt`, `YiCamAltApp.kt`, `auth/AuthRepository.kt`, `camera/CameraModule.kt`, `camera/CameraRegistry.kt`, `CameraRegistryCredentialsTest.kt`, plus `.planning/config.json`). One gate run hit `Unresolved reference: clearAll` mid-edit and one hit a locked `test-results/.../output.bin` from a concurrent Gradle run; a re-run was green. None of those files were staged or committed here. The final green gate included those edits; `CameraListPort` is unchanged by them, and their new "session ended during fetch" branch throws `CameraError.Unauthorized`, which this UI maps to UNAUTHORIZED.

## Known Stubs

None. Tapping a camera row does nothing yet (live view is Phase 2).

## Self-Check: PASSED

- Files exist: CameraListViewModel.kt, CameraListScreen.kt, AppShellScreen.kt, CameraListViewModelTest.kt.
- Commits exist: e2d301f, d6846dd, 65c4c41.
