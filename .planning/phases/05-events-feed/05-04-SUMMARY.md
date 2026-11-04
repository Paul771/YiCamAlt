---
phase: 05-events-feed
plan: 04
subsystem: ui-events
tags: [events-tab, feed, filters, day-picker, pull-to-refresh, compose]
requires: [05-02, 05-03]
provides:
  - EventsFeed (pure grouping / filters / labels / texts)
  - EventsViewModel (EventsUi, onTabEntered, onPullRefresh, filters, OpenClip / SessionExpired)
  - EventFeedSource / EventSyncSource ports + EventsUiModule (@Binds)
  - EventsScreen and the third bottom tab «События»
  - DayPickerDialog(today, selected?, markedDays) overload
affects: [05-05, 05-06, 05-07]
tech-stack:
  added: []
  patterns:
    - "Window StateFlow + flatMapLatest over EventDao.observeFeed; the window moves on every tab entry"
    - "Freshness label recomputed from fetchAtMs only on tab entry / pull / filter change / DB emission (no ticker)"
key-files:
  created:
    - app/src/main/java/com/yicamalt/ui/events/EventsFeed.kt
    - app/src/main/java/com/yicamalt/ui/events/EventsViewModel.kt
    - app/src/main/java/com/yicamalt/ui/events/EventsScreen.kt
    - app/src/test/java/com/yicamalt/ui/events/EventsFeedTest.kt
    - app/src/test/java/com/yicamalt/ui/events/EventsViewModelTest.kt
  modified:
    - app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt
    - app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt
    - app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt
decisions:
  - "Day button: «Все дни ▾» with no day selected, ArchiveTexts.dayLabel with a day selected (deliberate deviation from the letter of D-03, confirmed by eye in H-EV3)"
  - "Ports bound in EventsUiModule inside EventsViewModel.kt (SingletonComponent), not in EventModule"
  - "Ports are public interfaces (not internal) to avoid Hilt / Kotlin internal-name issues"
  - "EventFeedSource has no times(fromMs): calendar marks come from the rows of the 30-day window"
  - "SessionExpired is emitted once per Unauthorized streak (reset by Fetched / TooEarly); NoSession only sets the subtitle «Сессия истекла»"
  - "CalendarGrid.month(selected) is nullable so the events picker highlights nothing when no day is selected"
metrics:
  duration: ~60 min
  completed: 2026-11-04
  tasks: 2
  files: 8
---

# Phase 5 Plan 04: Events Tab Summary

Third bottom tab «События»: one feed of all cameras from the Room cache (30-day window), cards with a 128x72 preview, camera name and time grouped under «Сегодня» / «Вчера» / «d MMMM» headers, camera chips, the archive month-grid day picker, and a refresh only on tab entry and pull with «Обновлено N мин назад» / «Нет сети, показан кэш» subtitles.

## Tasks

| Task | Name | Commits |
| ---- | ---- | ------- |
| 1 | EventsFeed (pure) and EventsViewModel | a0c1a7b (RED), cb0ff90 (GREEN) |
| 2 | EventsScreen, reusable day picker, third tab | 58ecb9d |

## What was built

- **EventsFeed** (`internal object`, M-UI-EVENTS): `build(rows, cameras, selectedCameras, selectedDay, zone, nowMs)` filters (empty set = all cameras; day bounds from `SdTime.dayBounds`, DST-safe) and groups newest first; unknown uid -> «Камера», `knownCamera=false`, appears only under «Все»; blank names also fall back to «Камера». `daysWithEvents`, `subtitle(result, previous)`, `freshness(minutes)`, `emptyText(total, shown)`. `EventCard.toString` hides key / deviceId / name.
- **EventsViewModel** (`@HiltViewModel`): window `now - 30 d .. now + 1 d` in a StateFlow, `flatMapLatest` over `EventFeedSource.observe`; `onTabEntered` moves the window, reloads cached camera names, one `refresh()`; `onPullRefresh` one `refresh()` per gesture with a `refreshing` counter; filters `toggleCamera`, `selectAllCameras`, `openDays`, `closeDays`, `selectDay`, `clearDay`; `onCardTap` -> `OpenClip(key)`; Unauthorized -> `SessionExpired`. `thumbFile(path)` over `EventMediaFiles.thumbFile`. No `delay` / `while (true)`. Logs: `[Events][vm][BLOCK_REFRESH] trigger=enter|pull result=<class>`, `[Events][vm][BLOCK_FILTER] cameras=%d day=%b shown=%d`, `[Events][vm][BLOCK_OPEN_CLIP] tap`.
- **EventsScreen**: `LaunchedEffect(Unit) { onTabEntered() }`; FilterChip row («Все» + cameras); OutlinedButton with `ui.dayLabel` -> `DayPickerDialog(today, selectedDay, daysWithEvents)`, «Сбросить» when a day is selected, bodySmall subtitle; material3 1.2 pull-to-refresh around a LazyColumn of day headers and cards; previews decoded with `BitmapFactory` (inSampleSize 2) on `Dispatchers.IO` via `produceState`, kept in a 60-entry LRU keyed by the relative path; placeholder icon otherwise; play icon when `hasClip`; centered empty text.
- **ArchivePanel**: `DayPickerDialog(today, selected?, markedDays, onSelect, onDismiss)`; the `ArchiveUi` version delegates with `(ui.today, ui.selectedDay, ui.recordedDays)`; archive behaviour unchanged.
- **AppShellScreen**: `ShellTab` = Cameras, Settings, Events(«События», Notifications); `ShellTab.Events -> EventsScreen(onSessionExpired = onLogout, onOpenClip = { clipKey = it })`; `clipKey` is a plain `remember`, cleared on tab switch; 05-05 renders the clip dialog for it.

## Verification

- `:app:testDebugUnitTest --rerun --tests "com.yicamalt.ui.events.*"`: RED 22/22 failed; GREEN 22 passed (EventsFeedTest 11, EventsViewModelTest 11).
- Full gate `:app:testDebugUnitTest --rerun :app:assembleDebug`: `BUILD SUCCESSFUL`, `--- gradle exit=0`; **1066 tests, 0 failures, 1 skipped** (pre-existing).
- Greps: `Events("События"` = 1 line; `EventsScreen(` in AppShellScreen = 1; `fun DayPickerDialog` = 2 lines; `while (true)|delay(` in EventsViewModel.kt = 0; `rememberSaveable` appears only in a comment of EventsScreen.kt (no key / deviceId state).

## Deviations from Plan

**1. [Rule 3 - Blocking] CalendarGrid.month(selected) made nullable** (file not in the plan list): the overload needs "no selected day" without a sentinel date. Archive callers pass a non-null day, so nothing changes for them; VERSION 0.1.1 + CHANGE_SUMMARY.
- Commit: 58ecb9d

**2. [Plan detail] EventFeedSource without `times(fromMs)`**: marks come from the window rows (same 30-day span as the cache); the port would have had no caller.

**3. [Plan detail] Ports are public, bound in EventsUiModule** (in EventsViewModel.kt, SingletonComponent) instead of internal interfaces; avoids Hilt codegen issues with Kotlin `internal`.

**4. [Plan detail] EventsUi has an extra `today` field** for the day picker; the VM takes an optional `thumbResolver` (default none in tests) and exposes `thumbFile(path)` so the screen can find preview files without a Hilt entry point.

**5. [Rule 2] NoSession** maps to the «Сессия истекла» subtitle without a logout event (AppShell is only shown after login; a transient race must not wipe the cache).

**6. Extra tests**: `card toString hides key device and name`, `tab entry moves the window to the current day`.

**RED stubs**: the hook did not block `error("RED stub")` in the two new main files this time; RED was real (22/22 failing).

**Flaky full runs (not caused by this plan)**: the first two full gates ran under heavy machine load (3-4 min instead of ~1 min) and failed on `LiveViewModelTest > snapshot messages` (runTest 10 s wall timeout, `UncompletedCoroutinesError`) and, in the second run, `TutkCameraLinkTest > tx blocked in sendIoCtrl does not block close and the registry is deferred until it exits`. LiveViewModelTest passed alone and together with all `com.yicamalt.ui.*` tests; the third full gate on an idle machine was green in 57 s. Neither test touches the files of this plan.

## Known Stubs

- `clipKey` in AppShellScreen is set by a card tap but not rendered yet: intentional, the clip dialog and SD jump are plan 05-05.

## Self-Check: PASSED
