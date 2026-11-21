---
status: complete
phase: 07-regression-fixes
source: [07-01-SUMMARY.md, 07-02-SUMMARY.md, 07-03-SUMMARY.md, 07-04-SUMMARY.md, 07-VERIFICATION.md]
started: 2026-11-19T12:00:00Z
updated: 2026-11-19T12:20:00Z
---

## Current Test
<!-- OVERWRITE each test - shows where we are -->

[testing complete]

## Tests

### 1. Адаптивная иконка приложения
expected: Иконка на рабочем столе и в меню Xiaomi Mi 9 соответствует палитре Yi Home, адаптивная круглая форма корректна.
result: pass
coverage_id: D3

### 2. H-R3 (LIVE-06): звук TNP через медиа-динамик
expected: Открыть камеру «Спальня» (TNP), включить звук — звук с микрофона камеры слышен через медиа-динамик (не разговорный, не тишина).
result: pass

### 3. H-R9 (SD-01): индекс SD-архива TUTK
expected: Открыть камеру «YI Home Camera» (TUTK), открыть SD-архив — индекс загружается без ошибки LIST_FAILED, на таймлайне есть полосы записей, воспроизведение запускается.
result: pass

### 4. H-R10 (EVT-04): плавность event→SD
expected: Во вкладке События открыть событие и нажать «Открыть запись на SD» — архив открывается в момент события, видео идёт плавно, без слайд-шоу с паузами 3–5 с.
result: pass

### 5. LIVE-07 (H-R6): Relay по умолчанию SD
expected: Подключиться через мобильный hotspot/Relay, открыть поток — индикатор качества сразу показывает SD, ручной переключатель HD остаётся доступным.
result: pass

### 6. Календарь (H-R8/9): выбор любого прошедшего дня
expected: В диалоге выбора дня архива доступен любой прошедший день вплоть до сегодняшнего, даже без точки записи; пустые дни показывают «Нет записей за выбранный день», не блокируя интерфейс.
result: pass

### 7. H-R14: проверка logcat на redaction
expected: После прогона всех сценариев `adb logcat -d` не содержит строк с внутренними маркерами `[BLOCK_`, паролей камер, DID, токенов и P2P-лицензий.
result: pass

### A1. Версия и GRACE-документация (автоматически)
expected: tools/gradle.ps1 :app:testDebugUnitTest — 1111 тестов, 0 падений.
result: pass
source: automated
coverage_id: D1

### A2. Release-проверка APK (автоматически)
expected: 07-DEVICE-VERIFICATION.md — RESULT=PASS commit=f77223c; versionName 1.0.1 versionCode 10001, v2/v3 ok, 8 libs ok.
result: pass
source: automated
coverage_id: D2

### A3. UAT-скрипт и шаблон доказательств (автоматически)
expected: 07-DEVICE-SCRIPT.md и 07-DEVICE-VERIFICATION.md exist with the H-R1..H-R14 matrix.
result: pass
source: automated
coverage_id: D4

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]

---

Примечание: исходный пошаговый сценарий проверки на устройстве сохранён в
`07-DEVICE-SCRIPT.md` (детали установки APK, обход INSTALL_FAILED_USER_RESTRICTED,
порядок шагов H-R1..H-R14).
