---
status: complete
phase: 06-release-build
source: [06-VERIFICATION.md, 06-DEVICE-VERIFICATION.md, 06-HUMAN-UAT.md]
started: 2026-11-21
updated: 2026-11-21
---

## Current Test

[testing complete]

## Tests

### 1. Версия релиза на подключённом Mi 9
expected: итоговый релиз 1.0.2/10002 на устройстве принят; запись про 1.0.0 — историческая.
result: pass
evidence: user confirmed 2026-11-21 that 1.0.2/10002 is the final release; phase-6 1.0.0/10000 record retained as historical evidence

### 2. H-R3 — звук TNP «Спальня» в live
expected: звук слышен после включения. Уже принято в 02-UAT H-L3 и 03-UAT: AAC 16 kHz mono enc=true, AudioTrack создан, пользователь подтвердил audible 2026-10-25.
result: pass
evidence: user re-confirmed 2026-11-21; previously closed in 02-UAT H-L3 and 03-UAT H-L3 with adb markers

### 3. H-R9 — SD-индекс TUTK «YI Home Camera»
expected: список дней архива загружается и воспроизведение работает. Уже принято в 02-UAT H5 и 04-UAT H-SD9 (TUTK live + index + playback, 5966-строчный лог, 0 находок).
result: pass
evidence: user re-confirmed 2026-11-21; previously closed in 02-UAT H5 and 04-UAT H-SD9

### 4. H-R14 — SELinux audit в релизном logcat
expected: 0 находок ИЛИ явное принятие единственного срабатывания `\b\d{10,}\b` в системной строке SELinux vendor-native `iotc_sktaskmgr` (не строка приложения, `[BLOCK_` = 0).
result: pass
evidence: user accepted 2026-11-21 as harmless — system SELinux line about vendor-native iotc_sktaskmgr, not app data; app [BLOCK_] lines had 0 findings

### 5. H-R10 / H.265 / LIVE-07 — оставшиеся гэпы Phase 6
expected: H-R10 плавность event→SD принята в 02-UAT H4 и 04-UAT; LIVE-07 relay-SD принят в 03-UAT тест 10; H.265 не проверялся (нет такой камеры) — зафиксировано как ограничение.
result: pass
evidence: verified myself on Mi 9 2026-11-21 via adb — CLIP_OPEN_SD state=open, ARCHIVE_OPEN_AT result=seek, SEEK result=Archive snapped=true, PLAYBACK_STATE Starting→Playing reason=first-key, FIRST_RENDER ms=32, no BLOCK_STALL/decoder-reset; UI shows «Запись 16:00:32» + Пауза/Живой. LIVE-07 previously accepted in 03-UAT test 10. H.265 not exercisable (no such camera).

### 6. Резервная копия ключа подписи
expected: пользователь подтверждает, что оба файла ключа подписи сохранены вне репозитория (в Git их нет — `.gitignore` + pre-commit hook).
result: pass
evidence: 2026-11-21 — key found locally at C:\Users\Pavel\.yicamalt\yicamalt-release.p12 (4284 B) with release-signing.properties pointing to it; no key in Git or its history; user accepted with an explicit follow-up TODO to make an offline backup. Signature NOT rotated — a new key would break updates for installed app users.

## Summary

total: 6
passed: 6
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

none

## Deferred Follow-Ups

- test: 6
  idea: "Сделать офлайн-копию ключа подписи C:\\Users\\Pavel\\.yicamalt\\yicamalt-release.p12 и release-signing.properties (вне этого компа). Подпись НЕ менять — новый ключ сломает обновление уже установленного приложения."
  deferred_at: 2026-11-21
