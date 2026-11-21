---
phase: 07-regression-fixes
plan: 02
subsystem: archive-tutk
tags: [android, archive, sd, tutk, ioctrl, calendar]

# Dependency graph
requires:
  - phase: 04 (plans 04-02, 04-03, 04-07)
    provides: TutkCameraLink, SdIndexRepository, CalendarGrid, ArchiveCoordinator
provides:
  - TutkCameraLink expanded IOCTRL buffer (8192 bytes) preventing reply truncation and AV_ER_BUFTOOSMALL
  - SdIndexRepository logging diagnostics for malformed/rejected 793 index pages
  - CalendarGrid and ArchiveCoordinator enabling selection of any past day up to today
affects:
  - app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt
  - app/src/main/java/com/yicamalt/archive/SdIndexRepository.kt
  - app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt
  - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
  - app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt

# Tech tracking
tech-stack:
  added: []
  patterns: [Expanded 8192-byte P2P IOCTRL buffers for large multi-record pages, inclusive past-date calendar selection]

key-files:
  created:
    - .planning/phases/07-regression-fixes/07-02-SUMMARY.md
  modified:
    - app/src/main/java/com/yicamalt/p2p/tutk/TutkCameraLink.kt
    - app/src/main/java/com/yicamalt/archive/SdIndexRepository.kt
    - app/src/main/java/com/yicamalt/ui/live/CalendarGrid.kt
    - app/src/main/java/com/yicamalt/ui/live/ArchiveCoordinator.kt
    - app/src/main/java/com/yicamalt/ui/live/ArchivePanel.kt
    - app/src/test/java/com/yicamalt/archive/SdIndexRepositoryTest.kt
    - app/src/test/java/com/yicamalt/ui/live/CalendarGridTest.kt
    - app/src/test/java/com/yicamalt/ui/live/ArchiveCoordinatorTest.kt

key-decisions:
  - "Expanded IOCTRL_BUF_SIZE in TutkCameraLink from 1024 to 8192 bytes so multi-entry 793 index replies from physical TUTK cameras are received intact without buffer overflow or payload truncation (SD-01, H-R9)"
  - "Updated CalendarGrid.month cell enablement from `marked || date == today` to `!date.isAfter(today)` so users can select any past day even if recordings are not yet loaded or marked with dots"
  - "Updated ArchiveCoordinator.selectDay gate from `day != today() && day !in recordedDays` to `day.isAfter(today())` so picking past empty days shows empty segments timeline and NO_RECORDINGS panel instead of ignoring click"

requirements-completed: [SD-01]

# Metrics
duration: ~20 min
completed: 2026-11-14
---

# Phase 7 Plan 02: Fix TUTK SD Archive Index Buffer and Calendar Date Selection Summary

**Устранены дефект SD-01 (H-R9, ошибка получения индекса архива на камере TUTK «YI Home Camera», LIST_FAILED) и ограничение выбора дней в календаре (H-R8/9), препятствовавшее выбору вчерашних и более ранних дат без точек.**

## Причины дефектов и исправления
1. **TUTK SD Archive Index (SD-01 / H-R9):**
   - В `TutkCameraLink.kt` константа `IOCTRL_BUF_SIZE` составляла 1024 байта. Физическая TUTK-камера на запрос 792 возвращает ответ 793 со списком 12-байтных интервалов. При наличии множества записей размер ответа превышает 1024 байта, что вызывало `AV_ER_BUFTOOSMALL` в нативном слое или обрезку буфера до 1024 байт. В результате `SdArchiveCodec.parseTutkIndexPage` отвергал пакет из-за несовпадения размера, вызывая таймаут и статус `LIST_FAILED`.
   - Буфер `IOCTRL_BUF_SIZE` увеличен до 8192 байт. В `SdIndexRepository.kt` добавлено предупреждающее логирование при получении нераспознанных страниц 793.
2. **Calendar Day Selection (H-R8/9):**
   - В `CalendarGrid.kt` свойство `DayCell.enabled` вычислялось как `marked || date == today`. Если дата не имела записей (или индекс ещё не подгрузился), ячейка была заблокирована для клика.
   - Изменено на `enabled = !date.isAfter(today)`. Все дни вплоть до сегодняшнего активны для клика, точки наличия записей по-прежнему отображаются по условию `marked`, будущие дни остаются отключенными.
   - В `ArchiveCoordinator.kt` проверка в `selectDay` изменена с `if (day != today() && day !in recordedDays) return` на `if (day.isAfter(today())) return`. При выборе прошедшего дня без записей координатор корректно отображает пустой таймлайн и статус `NO_RECORDINGS`.

## Результаты верификации
- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.archive.SdIndexRepositoryTest" --tests "com.yicamalt.ui.live.CalendarGridTest" --tests "com.yicamalt.ui.live.ArchiveCoordinatorTest" --tests "com.yicamalt.p2p.tutk.TutkCameraLinkTest"` — `BUILD SUCCESSFUL` (exit 0).
- Добавлен тест `TUTK large page exceeding 1024 bytes is parsed and accumulated` в `SdIndexRepositoryTest`.
- Обновлены тесты `CalendarGridTest` и `ArchiveCoordinatorTest` на кликабельность прошедших дат и фильтрацию будущих.
