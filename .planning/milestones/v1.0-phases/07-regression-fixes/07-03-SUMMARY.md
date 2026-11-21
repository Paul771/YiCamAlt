---
phase: 07-regression-fixes
plan: 03
subsystem: stream-assembler
tags: [android, stream, frame-assembler, gap-recovery, playback, relay, sd-quality]

# Dependency graph
requires:
  - phase: 07 (plans 07-01, 07-02)
    provides: AacDecoder, TutkCameraLink, SdIndexRepository, ArchiveCoordinator
provides:
  - FrameAssembler resilient gap recovery (GAP_TOLERANCE = 3, consecutive run check) preventing 3-5 s playback freeze
  - PlaybackController decoder pipeline reset on seek, startAt, and goLive
  - QualityController and LiveSessionSupervisor verified SD quality enforcement on Relay connections
affects:
  - app/src/main/java/com/yicamalt/stream/FrameAssembler.kt
  - app/src/main/java/com/yicamalt/archive/PlaybackController.kt
  - app/src/main/java/com/yicamalt/archive/SdTypes.kt
  - app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt
  - app/src/main/java/com/yicamalt/live/LiveSessionTypes.kt
  - app/src/main/java/com/yicamalt/live/DefaultMediaSession.kt

# Tech tracking
tech-stack:
  added: []
  patterns: [UDP packet loss gap recovery with consecutive run detection, clean pipeline reset on seek/openAt]

key-files:
  created:
    - .planning/phases/07-regression-fixes/07-03-SUMMARY.md
  modified:
    - app/src/main/java/com/yicamalt/stream/FrameAssembler.kt
    - app/src/main/java/com/yicamalt/archive/PlaybackController.kt
    - app/src/main/java/com/yicamalt/archive/SdTypes.kt
    - app/src/main/java/com/yicamalt/live/SupervisorArchivePort.kt
    - app/src/main/java/com/yicamalt/live/LiveSessionTypes.kt
    - app/src/main/java/com/yicamalt/live/DefaultMediaSession.kt
    - app/src/test/java/com/yicamalt/stream/FrameAssemblerTest.kt
    - app/src/test/java/com/yicamalt/archive/PlaybackControllerTest.kt
    - app/src/test/java/com/yicamalt/live/QualityControllerTest.kt
    - app/src/test/java/com/yicamalt/archive/FakeArchivePort.kt

key-decisions:
  - "Implemented FrameAssembler recoverGap() triggering when 3 consecutive frames arrive after a missing sequence number (GAP_TOLERANCE = 3, MAX_SKIP_GAP = 30); accounts for skipped frames in dropped counter and drains consecutive frames immediately, completely eliminating the 3-5 s slideshow stall (EVT-04, H-R10)"
  - "Added resetPipeline() to MediaSession, DefaultMediaSession, ArchiveLinkPort, and SupervisorArchivePort; PlaybackController triggers resetPipeline() on startAt and goLive to flush residual frames and cleanly re-arm keyframe gate"
  - "Verified QualityController.target(mode) and LiveSessionSupervisor enforce Quality.SD default on Relay link modes (LIVE-07)"

requirements-completed: [EVT-04, LIVE-07]

# Metrics
duration: ~20 min
completed: 2026-11-14
---

# Phase 7 Plan 03: FrameAssembler Gap Recovery and Relay SD Default Summary

**Устранены дефект EVT-04 (H-R10, слайд-шоу с задержкой 3-5 секунд на кадр при переходе к архиву из событий) и проверено требование LIVE-07 (установка качества SD по умолчанию при подключении через Relay).**

## Причины дефектов и исправления
1. **SD Playback Slideshow (EVT-04 / H-R10):**
   - При воспроизведении архива или переходе по `openAt()` из списка событий потеря даже одного P-кадра в UDP-потоке (`distance > 1`) помещала все последующие кадры в `pendingBySeq`. Ранее `FrameAssembler` ожидал ровно отсутствующий номер кадра вплоть до достижения `MAX_PENDING = 100` кадров либо до прихода следующего ключевого кадра (I-frame), интервал между которыми составляет 3–5 секунд (60–100 кадров). Это приводило к полному замиранию декодера на 3–5 секунд.
   - В `FrameAssembler` реализован алгоритм быстрого восстановления пропусков: если после потерянного кадра накапливается последовательная цепочка из `GAP_TOLERANCE = 3` кадров (в пределах `MAX_SKIP_GAP = 30` кадров одного GOP), сборщик фиксирует пропущенные кадры в счётчике `dropped`, переставляет указатель `lastSeq` и немедленно выдаёт готовую последовательность кадров в декодер (`drainFrom`). При этом кадры, опережающие ключевой кадр из другого потока чтения, по-прежнему корректно сохраняются в буфере.
   - В интерфейсы `MediaSession` и `ArchiveLinkPort` добавлен метод `resetPipeline()`. В `PlaybackController.startAt` и `goLiveLocked` вызывается `port.resetPipeline()`, гарантирующий сброс буферов и чистый повторный захват ключевого кадра при переходе к архиву и обратно.
2. **Relay Default to SD (LIVE-07):**
   - Проверено и подтверждено тестами, что `QualityController.target(mode)` возвращает `Quality.SD` для всех режимов Relay (`RELAY`, `TCP_RELAY`, `SDEV_RELAY`, `UNKNOWN`), и `LiveSessionSupervisor` автоматически запрашивает переключение разрешения на SD при получении первого ключевого кадра.

## Результаты верификации
- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.stream.FrameAssemblerTest" --tests "com.yicamalt.archive.PlaybackControllerTest" --tests "com.yicamalt.live.QualityControllerTest"` — `BUILD SUCCESSFUL in 28s` (exit 0).
- Добавлены тесты `gap recovery skips missing P-frame after GAP_TOLERANCE and drains pending frames` и `reordered P frames within tolerance are emitted in order without loss` в `FrameAssemblerTest`.
- Проверен вызов `resetPipeline` на `PlaybackController.seek`.
- Проверен автозапрос `SD` при подключении через `LinkMode.RELAY` в `QualityControllerTest`.
