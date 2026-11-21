---
phase: 06-release-build
plan: 06
subsystem: release-build
tags: [android, release, signing, adb, miui]

# Dependency graph
requires:
  - phase: 06 (plans 06-00..06-05)
    provides: signed app-release.apk 1.0.0/10000, checker tools, H-D1 sweep, debug build on the Mi 9
provides:
  - signed release 1.0.0 installed on the Mi 9 with the debug build removed (H-R1 pass)
  - proof of signer mismatch before removal and signer match after install
  - enlarged+cleared logcat buffer ready for the release regression
affects: [06-07, 06-08]

# Tech tracking
tech-stack:
  added: []
  patterns: [value-free device evidence: booleans, version strings, failure code names only]

key-files:
  created: []
  modified:
    - .planning/phases/06-release-build/06-HUMAN-UAT.md
    - .planning/phases/06-release-build/06-DEVICE-VERIFICATION.md
    - docs/verification-plan.xml

key-decisions:
  - "MIUI отменяет установку по USB (INSTALL_FAILED_USER_RESTRICTED для adb install, shell pm install и после перетумблирования «Установка через USB») — release установлен самим пользователем с телефона из Загрузок"

patterns-established:
  - "H-R evidence stays value-free: signer booleans, refusal code names, version strings; digests/uid/APK paths live only in the scratchpad"

requirements-completed: [REL-01, REL-02]

# Metrics
duration: 30 min
completed: 2026-11-12
---

# Phase 6 Plan 06: Release install on the Mi 9 (H-R1) Summary

**Signed release 1.0.0 replaced the debug build on the Mi 9 after an explicit user «да», with signer mismatch proven before removal and signer match proven after install.**

## Performance

- **Duration:** ~30 min (с учётом пользовательских ответов на чекпоинтах)
- **Started:** 2026-11-12T18:25:00Z (ориентировочно, сессия 06-06)
- **Completed:** 2026-11-12T18:55:02Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments
- signerSame=false между установленной debug-сборкой и собранным release; `adb install -r` отказал с INSTALL_FAILED_UPDATE_INCOMPATIBLE, данные не тронуты
- После «да» на чекпоинте (с предупреждением о сбросе входа и данных): debug удалён, release установлен, versionName=1.0.0 versionCode=10000, signerSame=true (RESULT=PASS), run-as отказал («package not debuggable»)
- Logcat подготовлен к регрессии (буфер 16M разрешён, очищен после установки); пользователь сам вошёл в аккаунт (исполнитель не логинился)
- Gate-GSD-Phase-6: H-R1 pending -> passed; H-R1 записан в 06-HUMAN-UAT.md и 06-DEVICE-VERIFICATION.md

## Task Commits

1. **Task 1-2:** build + signer mismatch + checkpoint «да» — без коммита (нет файловых изменений)
2. **Task 3:** docs(06-06): release 1.0.0 installed on the phone (H-R1) - `725df5f`

## Files Created/Modified
- `.planning/phases/06-release-build/06-HUMAN-UAT.md` — H-R1 result: pass с доказательствами
- `.planning/phases/06-release-build/06-DEVICE-VERIFICATION.md` — раздел «H-R1 release install» (таблица проверок, отклонение, value-free)
- `docs/verification-plan.xml` — H-R1 status="passed" в Gate-GSD-Phase-6 (XML проверен парсером)

## Decisions Made
- Установка release переведена на пользовательский путь (с телефона из Загрузок): оба adb-пути заблокированы MIUI, состояние конечных критериев не изменилось; согласие пользователя на удаление debug получено до любых действий.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Установка release выполнена пользователем вместо `adb install`**
- **Found during:** Task 3 (install release)
- **Issue:** MIUI отменила установку по USB: INSTALL_FAILED_USER_RESTRICTED для `adb install`, для `pm install` из shell и после перетумблирования «Установка через USB»
- **Fix:** APK перенесён в Загрузки телефона, пользователь запустил установку на устройстве; после успеха APK удалён из Загрузок
- **Files modified:** (телефон только; документы — штатные файлы Task 3)
- **Verification:** dumpsys versionName=1.0.0/versionCode=10000; signerSame=true RESULT=PASS; run-as refused
- **Committed in:** 725df5f (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** механизм установки изменён, итоговые must_haves (версия, подпись, не-debuggable, согласие до удаления) выполнены полностью.

## Issues Encountered
- Телефон был в состоянии adb «unauthorized» — пользователь подтвердил RSA-диалог (human-action gate, ожидаемо).
- Первый запуск через monkey попал в Chrome (пользователь был в браузере) — повторный запуск после KEYCODE_HOME показал экран «YiCamAlt — Вход» (скриншот h-r1-launch).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Телефон на signed release 1.0.0, вход выполнен пользователем, logcat готов — можно выполнять 06-07 (H-R2..H-R14).
- Оговорка: резервная копия ключа подписи пользователем не подтверждена (напоминание показано; см. docs/RELEASE.md).

---
*Phase: 06-release-build*
*Completed: 2026-11-12*
