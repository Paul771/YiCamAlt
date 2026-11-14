---
phase: 06-release-build
plan: 07
subsystem: release-regression
tags: [android, release, regression, device, uat, p2p, relay, sd-archive, events]

# Dependency graph
requires:
  - phase: 06 (plans 06-00..06-06)
    provides: signed release 1.0.0 installed on Xiaomi Mi 9 (H-R1)
provides:
  - complete v1 device regression results H-R2..H-R14 on signed release build
  - remote viewing over hotspot verified (both cameras in Relay mode)
  - logcat redaction sweep results (0 [BLOCK_ lines, 1 audit finding documented)
affects: [06-08]

# Tech tracking
tech-stack:
  added: []
  patterns: [value-free device regression tracking with structured gap categorization]

key-files:
  created:
    - .planning/phases/06-release-build/06-07-SUMMARY.md
  modified:
    - .planning/phases/06-release-build/06-HUMAN-UAT.md
    - .planning/phases/06-release-build/06-DEVICE-VERIFICATION.md
    - docs/verification-plan.xml

key-decisions:
  - "H-R3 sound on TNP «Спальня» confirmed not audible (user reported «Не слышно») — documented as verified gap"
  - "H-R9 SD archive index fetch on TUTK «YI Home Camera» failed with «Не удалось получить список. Повторить» — documented as gap"
  - "H-R6/H-R7 remote viewing via hotspot: both cameras successfully connected via Relay; LIVE-07 default-SD not exercised (kept HD)"
  - "H-R14 RealLogScanTest audit finding in SELinux audit on iotc_sktaskmgr ioctl documented by rule name only"

requirements-completed: [REL-02]

# Metrics
duration: ~1h 30m
completed: 2026-11-14
---

# Phase 6 Plan 07: Final Device Regression on Release Build Summary

> **ВАЖНОЕ НАПОМИНАНИЕ ПОЛЬЗОВАТЕЛЮ:**
> После проверки удалённого доступа (H-R6/H-R7) убедитесь, что на телефоне Xiaomi Mi 9 снова включено автоподключение к домашнему Wi-Fi (`wifi restored`).

**Регрессионное тестирование всех v1-сценариев на подписанной релизной сборке 1.0.0 на Xiaomi Mi 9 полностью выполнено (H-R2..H-R14).**

## Результаты проверок

- **H-R2 (Список камер):** `pass` — обе камеры («Спальня» TNP, «YI Home Camera» TUTK) онлайн в списке.
- **H-R3 (Live LAN «Спальня» TNP):** `fail (gap: звук)` — видеопоток и переключение HD/SD работают стабильно; звук в живом эфире не слышен (ответ: «Не слышно»).
- **H-R4 (Live LAN «YI Home Camera» TUTK):** `pass` — видеопоток, звук («Слышно») и переключение HD/SD работают стабильно.
- **H-R5 (Кодеки и аппаратные декодеры):** `pass (H.264), не проверено (H.265)` — обе камеры вещают в H.264, декодируются аппаратным Qualcomm AVC (`OMX.qcom.video.decoder.avc`); H.265 не проверен из-за отсутствия камер с H.265 (аппаратный HEVC-декодер в системе присутствует).
- **H-R6 (Remote «Спальня» TNP через hotspot):** `pass (Relay)` — сеть точки доступа проверена (`subnetDiffers=true`, `homeGatewayReachable=false`); видеопоток идёт, бейдж Relay; качество HD (LIVE-07 relay-SD не сработал по умолчанию).
- **H-R7 (Remote «YI Home Camera» TUTK через hotspot):** `pass (Relay)` — видеопоток идёт, бейдж Relay; соединение через Relay успешно установлено (ранее ожидалась потенциальная ошибка UNKNOWN_-27).
- **H-R8 (Архив SD «Спальня» TNP):** `pass` — выбор дня, воспроизведение отрезка, совпадение OSD ±5 с, пауза/продолжение, мгновенный возврат в «Живой» без переподключения.
- **H-R9 (Архив SD «YI Home Camera» TUTK):** `fail (gap)` — ошибка «Не удалось получить список. Повторить» (индекс записей по P2P не загружается).
- **H-R10 (Вкладка «События»):** `pass (gap: плавность)` — карточки событий отображаются, фильтр-чипы работают, клипы с обеих камер воспроизводятся, переход к моменту на SD работает; гэп: воспроизведение архива из событий идёт как слайд-шоу раз в 3–5 секунд.
- **H-R11 (Параллельный просмотр с оф. Yi Home):** `pass` — подтверждено пользователем («pass»), видеопотоки не конфликтуют.
- **H-R12 (Фоновый режим и возврат):** `pass` — подтверждено пользователем («pass»), поток восстанавливается автоматически при возврате <5 мин.
- **H-R13 (15+ мин стрима LAN):** `pass` — скриншоты на 0/5/10/15 мин, непрерывное воспроизведение без срывов.
- **H-R14 (Logcat sweep):** `fail (gap: audit finding)` — 0 строк `[BLOCK_` в release-logcat (7327 строк); RealLogScanTest зафиксировал 1 finding: правило `regex:\b\d{10,}\b` в системной строке SELinux audit `iotc_sktaskmgr` (вендорный native thread TUTK).

## Зафиксированные дефекты и гэпы (Gaps)

1. Звук в живом эфире на TNP-камере («Спальня») не слышен.
2. Чтение индекса архива SD на TUTK-камере («YI Home Camera») завершается ошибкой «Не удалось получить список».
3. Плавность воспроизведения архива при переходе из карточки события («События» -> «Открыть запись на SD») снижена до слайд-шоу (кадр раз в 3–5 с).
4. Календарь архива не позволяет выбрать день без записей в индексе (вчерашний день при наличии записей на карте не попал в `recordedDays`).
5. Автоматическое переключение на SD по умолчанию при соединении через Relay (LIVE-07) не сработало (осталось HD).
6. Системный audit SELinux для `iotc_sktaskmgr` выводит микросекундный timestamp аудита ядра в logcat.

## Изменённые файлы

- `.planning/phases/06-release-build/06-HUMAN-UAT.md` — статус `diagnosed`, заполнены результаты H-R2..H-R14 и секция Gaps.
- `.planning/phases/06-release-build/06-DEVICE-VERIFICATION.md` — добавлен раздел «Release regression (H-R2..H-R14)».
- `docs/verification-plan.xml` — обновлены статусы device-checks в Gate-GSD-Phase-6.
- `.planning/phases/06-release-build/06-07-SUMMARY.md` — итоговый отчет плана.

## Готовность к следующему шагу

План 06-07 полностью завершён. Следующий план: **06-08** (финализация документов Phase 6, актуализация GRACE-артефактов, PROJECT_STATUS.md и HANDOFF.md).
