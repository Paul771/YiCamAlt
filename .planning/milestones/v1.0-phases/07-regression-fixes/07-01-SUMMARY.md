---
phase: 07-regression-fixes
plan: 01
subsystem: media-audio
tags: [android, media, aac, audio-track, miui, live-audio]

# Dependency graph
requires:
  - phase: 03 (plan 03-07)
    provides: AacDecoder, AudioPipeline, AudioFrameQueue
provides:
  - AudioTrack USAGE_MEDIA / CONTENT_TYPE_MUSIC configuration resolving MIUI mono earpiece routing bug
  - Non-blocking PCM write tracking (pcmBytesWritten, pcmBytesDropped) and error handling
  - Verified TNP encrypted mono AAC unit processing and stats tracking
affects:
  - app/src/main/java/com/yicamalt/media/AacDecoder.kt
  - app/src/main/java/com/yicamalt/stream/AudioPipeline.kt
  - app/src/test/java/com/yicamalt/stream/AudioPipelineTest.kt

# Tech tracking
tech-stack:
  added: []
  patterns: [AudioAttributes.CONTENT_TYPE_MUSIC for mono playback on MIUI, non-blocking AudioTrack diagnostics]

key-files:
  created:
    - .planning/phases/07-regression-fixes/07-01-SUMMARY.md
  modified:
    - app/src/main/java/com/yicamalt/media/AacDecoder.kt
    - app/src/main/java/com/yicamalt/stream/AudioPipeline.kt
    - app/src/test/java/com/yicamalt/stream/AudioPipelineTest.kt

key-decisions:
  - "Configured AudioTrack AudioAttributes with USAGE_MEDIA and CONTENT_TYPE_MUSIC instead of CONTENT_TYPE_SPEECH to prevent MIUI platform bug that routes mono speech streams to telecommunication earpiece receiver instead of loudspeaker"
  - "Tracked pcmBytesWritten and pcmBytesDropped monotonic counters in AacDecoder writePcm to provide full visibility into AudioTrack non-blocking buffer status"
  - "Added test in AudioPipelineTest for TNP encrypted mono audio unit delivery and unmute behavior"

requirements-completed: [LIVE-06]

# Metrics
duration: ~15 min
completed: 2026-11-14
---

# Phase 7 Plan 01: Fix TNP Live Audio Routing Summary

**Устранён регрессионный дефект LIVE-06 (H-R3), из-за которого звук live-стрима на камере TNP ("Спальня") был неслышим на устройствах Xiaomi под управлением MIUI.**

## Причина дефекта и исправление
1. **Причина:** В `AacDecoder.kt` объект `AudioTrack` инициализировался с атрибутами `USAGE_MEDIA` и `CONTENT_TYPE_SPEECH`. В MIUI (в частности, на Xiaomi Mi 9) mono-поток с типом содержимого `CONTENT_TYPE_SPEECH` ошибочно направляется платформенным аудиосервером в голосовой разговорный динамик (earpiece receiver) с громкостью голосового вызова, из-за чего звук через основной мультимедийный динамик казался полностью отсутствующим при просмотре стрима.
2. **Исправление:**
   - В `AacDecoder.kt` атрибут типа содержимого изменён на `AudioAttributes.CONTENT_TYPE_MUSIC`, что форсирует корректную маршрутизацию в мультимедийный динамик смартфона.
   - В метод `writePcm` добавлен учёт возвращаемого значения вызова `AudioTrack.write(..., WRITE_NON_BLOCKING)`: ведутся счётчики `pcmBytesWritten` и `pcmBytesDropped`, логируются и обрабатываются ошибки `AudioTrack`.
   - В `AudioPipelineTest.kt` добавлен тест `tnp encrypted mono unit preserves stats when muted and delivers immediately upon unmute`, подтверждающий корректность дешифрования и выдачи mono-аудиопотока 16 кГц.

## Результаты верификации
- `tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.stream.AudioPipelineTest" --tests "com.yicamalt.media.AudioFrameQueueTest"` — `BUILD SUCCESSFUL` (exit 0).
- Проверена чистота логов через `RedactionScanner` (0 утечек ключей и паролей).
