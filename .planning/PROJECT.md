# YiCamAlt

## What This Is

Альтернативный Android-клиент для камер Yi Home / Yi Dome (Kami), работающий без платной подписки официального приложения. Владелец камер логинится своим Yi-аккаунтом, видит список своих камер, смотрит живое видео (дома и удалённо), архив с SD-карты камеры и ленту событий движения.

## Core Value

Открыть приложение → увидеть свои камеры → смотреть живое видео с любой из них, из любой сети, без подписки.

## Requirements

### Validated

- ✓ Логин email/пароль в Yi Cloud (`GET /v4/users/login`, пароль = Base64(HMAC-SHA256)), сессия в EncryptedSharedPreferences — Phase-1 GRACE, подтверждено на реальном устройстве (user 167315)
- ✓ Refresh токена (`/v4/users/auth_token`) без дедлока — Phase-1 GRACE
- ✓ Runtime-настройка base URL (gw-us / gw-eu / gw-sg) через экран настроек — existing
- ✓ HMAC-SHA1 подпись device API (`key = token&token_secret`, `msg = seq=1&userid=<uid>`) → код 20200 на `/v5/devices/list` — подтверждено зондом на устройстве
- ✓ UI-оболочка: bottom-nav (Камеры / Настройки), logout — existing
- ✓ Room-БД (камеры, события, мета) и redaction логов (пароли, токены, серийники) — existing
- ✓ Реальный список камер: регион определяется автоматически при входе (gw-us/eu/sg по непустому списку), `/v5/devices/list`, P2P-реквизиты через `/v4/tnp/device_info` в зашифрованном хранилище; две камеры (TNP + TUTK) совпали с официальным Yi Home на телефоне — Phase 1 (2026-10-12)
- ✓ Гигиена секретов: редакция логов, внутренний лог, pre-commit защита от вендорских бинарников — Phase 1
- ✓ Live-view через вендорский P2P: TNP (PPPP) и TUTK, .so извлекаются из APK при сборке, H.264 hw-декод и звук — v1.0, Phase 2/3, подтверждено на Mi 9
- ✓ Live-view в LAN и удалённо (P2P/Relay), авто-reconnect и SD по умолчанию на Relay — v1.0, Phase 2/3
- ✓ Архив SD-карты: индекс 792/793, таймлайн, seek, переходы и плавное воспроизведение из событий — v1.0, Phase 4
- ✓ Лента событий `/v2/alert/list`: превью, клипы, фильтры, 5-минутный гейт, офлайн-кэш — v1.0, Phase 5
- ✓ Подписанный релиз 1.0.2/10002 (v2/v3, 8 vendor libs, debuggable=false), проверен на Mi 9 — v1.0, Phase 6

### Active

Новых требований для v1.1 не определено. Запустите `/gsd-new-milestone` для формулировки.

### Current State

Shipped v1.0 MVP (2026-11-21): 7 phases, 75 plans, 1111 unit tests / 0 failures,
UAT фаз 2–6 закрыты на физическом Xiaomi Mi 9, security audit всех фаз `threats_open: 0`.
Signed release v1.0.2 / versionCode 10002. Известный техдолг см. в
`milestones/v1.0-MILESTONE-AUDIT.md` и STATE.md `## Deferred Items` (6 требований с оговорками,
одной отложенный пункт `EventMediaFiles.wipeAll`).

### Out of Scope

- Облачное хранилище Yi (платные cloud-записи) — это и есть платная подписка, цель проекта её не требовать
- Прошивка yi-hack / RTSP — пользователь выбрал P2P с заводской прошивкой
- Добавление/первичная настройка новых камер (pairing, Wi-Fi provisioning) — делается в официальном приложении один раз
- PTZ и настройки камеры — не входят в первую рабочую версию (модуль M-CAMERA-CMD остаётся, но эндпоинт не подтверждён)
- iOS / десктоп — только Android
- Эмулятор Android на этой машине — VT-x выключен, прав администратора нет; захват идёт через физический телефон

## Context

- **Brownfield.** Проект начат в opencode по протоколу GRACE (`AGENTS.md`): контракты модулей, семантическая разметка `START_BLOCK_*`, `docs/*.xml` (requirements, development-plan, verification-plan, knowledge-graph). Новые модули обязаны следовать GRACE: MODULE_CONTRACT, обновление knowledge-graph и verification-plan.
- Карта кодбазы: `.planning/codebase/` (ARCHITECTURE, STACK, CONCERNS, …). Статус и реверс-находки: `docs/PROJECT_STATUS.md`, `docs/reference/yi-cloud-endpoints.md`.
- **Почему opencode застрял:** эмулятор не стартует (VT-x/SVM выключен в BIOS, `HypervisorPresent=False`, нет админа для AEHD/WHPX). Перебор эндпоинтов вслепую (5 раундов зонда) не нашёл непустой список камер: `/v5/devices/list` отвечает 20200, тело 16 байт.
- **Протокол видео:** в `split_config.arm64_v8a.apk` лежат `libIOTCAPIs.so`, `libAVAPIs.so` (TUTK Kalay P2P), а также `libPPPP_API.so`, `libyimedia.so`, `libkami_core.so`. Живого RTSP на заводской прошивке нет. Прецедент: docker-wyze-bridge использует те же TUTK-библиотеки для камер Wyze.
- Известный техдолг (CONCERNS.md): HMAC-подпись продублирована в 3 местах; выдуманные пути `v1/camera/event/*` и `v8/device/control`; `YiCloudAuthApi` не пересоздаётся после смены base URL; `CameraDao.insertCamera` логирует device_id.
- Оригинальный APK: `_apk/extracted/` (Yi Home 6.9.8, `com.ants360.yicamera.international`), 10 dex. jadx на полном APK зависает — декомпилировать по одному dex.

## Constraints

- **Tech stack**: Kotlin, Jetpack Compose, Material 3, Hilt, Retrofit/OkHttp, Room; minSdk 26 / target 34 — уже в кодбазе
- **Окружение**: Windows 10, без прав администратора, AppLocker блокирует `.bat/.cmd` — Gradle только через `tools/gradle.ps1`; JDK 17 полным путём; Python 3.11 (не 3.14) для mitmproxy
- **Устройство**: проверка и захват только на физическом Android-телефоне пользователя по USB (adb); эмулятор недоступен
- **ABI**: TUTK-библиотеки есть только для arm64-v8a и armeabi-v7a → приложение собирается под ARM, на x86 live-view не работает
- **Безопасность**: логин/пароль Yi вводит только пользователь; токены, flow-файлы mitmproxy (`captures/`), P2P-ключи и серийники камер не попадают в Git, логи и чат
- **Протокол GRACE**: любое изменение кода сопровождается контрактом модуля и обновлением `docs/*.xml`

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Захват API: физический телефон + apk-mitm + mitmproxy | Эмулятор невозможен (нет VT-x и админа); apk-mitm не требует root | — Pending |
| Live-view через вендорский P2P: TNP первым, TUTK фолбэк (JNI к .so из локального APK) | Заводская прошивка, локально и удалённо; yi-hack отклонён; research: 7/7 камер в 2026-аккаунте — TNP | — Pending |
| Удалённый просмотр обязателен в v1 | Требование пользователя | — Pending |
| v1 = список камер + live-view + архив SD + события | Требование пользователя; PTZ и настройки — позже | — Pending |
| Продолжаем GRACE-разметку и docs/*.xml поверх GSD-планирования | Проект уже построен на GRACE; GSD управляет фазами, GRACE — контрактами в коде | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-10-12 after Phase 1*
