# Roadmap: YiCamAlt

## Overview

Путь от уже работающей Android-оболочки (логин, подпись, Room, UI) до клиента, который без подписки показывает живое видео, архив SD-карты и ленту событий с камер Yi. Единственное узкое место — список камер и их P2P-реквизиты (Phase 1). Дальше идут две независимые линии: P2P-линия (Phase 2 → 3 → 4: первый кадр → полноценный live-view → архив SD) и линия событий (Phase 5, параллельно с 2–4, кроме перехода к записи на SD). Phase 6 — подписанная сборка и проверка всех сценариев на телефоне пользователя.

**Нумерация.** Номера фаз здесь относятся к GSD-роадмапу и не связаны с `Phase-N` в GRACE-документах (`docs/development-plan.xml`, `docs/verification-plan.xml`). В каждой фазе обязательно обновлять GRACE-артефакты: MODULE_CONTRACT затронутых модулей, `docs/development-plan.xml`, `docs/knowledge-graph.xml`, `docs/verification-plan.xml`.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: Секреты и реальный список камер** - Санитария секретов, проба эндпоинтов/регионов, разбор списка камер и P2P-реквизитов
- [ ] **Phase 2: Нативный P2P и первый кадр** - Вендорские .so из локального APK, JNI-шимы, чистое Kotlin-ядро протокола, первое видео по LAN и удалённо
- [x] **Phase 3: Полноценный live-view** - Супервизор сессии, переподключение, звук, HD/SD, снимок, полноэкранный режим, корректная остановка
 (completed 2026-10-30)
- [ ] **Phase 4: Архив SD-карты** - Индекс дней, 24-часовая шкала, воспроизведение и перемотка через ту же P2P-сессию
- [ ] **Phase 5: Лента событий** - Реальный эндпоинт алертов, превью и 6-секундные клипы, фильтры, офлайн-кэш, переход к записи SD
- [ ] **Phase 6: Релизная сборка и проверка на телефоне** - Подписанный ARM-APK, проверка всех v1-сценариев на реальном устройстве

## Phase Details

### Phase 1: Секреты и реальный список камер
**Goal**: После логина пользователь видит свои реальные камеры, а приложение знает P2P-реквизиты и транспорт каждой из них, не допуская утечки секретов в логи и Git
**Depends on**: Nothing (first phase; логин и HMAC-подпись уже подтверждены)
**Requirements**: SEC-01, SEC-02, SEC-03, SEC-04, CAM-01, CAM-02, CAM-03, CAM-04, CAM-05
**Success Criteria** (what must be TRUE):
  1. Пользователь логинится и видит список своих камер (свои и расшаренные) с названием, моделью и статусом онлайн/офлайн, без ручного выбора сервера gw-us / gw-eu / gw-sg
  2. При отсутствии камер или ошибке сервера пользователь видит понятное состояние («нет камер» / «ошибка сервера» / «повторить»); pull-to-refresh обновляет список, а при повторном запуске кэшированный список показывается сразу
  3. Для каждой камеры известен транспорт (TNP / TUTK) и получены P2P-реквизиты (p2pid, пароль, InitString, License через `/v4/tnp/device_info`); реквизиты лежат только в памяти / EncryptedSharedPreferences, не в открытом Room
  4. Расширенный `RedactionScanner` на реалистичных фикстурах не находит в логах hmac, токены, пароли, License, InitString, DID/p2pid, nonce и серийники; диагностический лог пишется во внутреннее хранилище
  5. `.gitignore` и проверка перед коммитом не пропускают APK, .so, декомпилированный код и flow-файлы захвата
**Plans:** 13 plans

Plans:
- [x] 01-00-PLAN.md — Wave 0: починка tools/gradle.ps1, компилируемая базовая линия, GRACE-контракты новых модулей (planned)
- [x] 01-01-PLAN.md — Редакция логов: RedactionScanner + фикстуры, shape-only LogRedactor, BASIC-логгер HttpClientModule (wave 1)
- [x] 01-02-PLAN.md — Диагностический лог только во внутреннем хранилище; серийники/uid/epoch убраны из логов (wave 1)
- [x] 01-03-PLAN.md — .gitignore + pre-commit хук (в т.ч. режим --range) против бинарников, захватов и секретов (wave 1)
- [x] 01-04-PLAN.md — Единый YiRequestSigner (wave 1)
- [x] 01-05-PLAN.md — Base URL на каждый запрос, один refresh на истечение сессии, заголовки приложения, проба-матрица (wave 2)
- [x] 01-06-PLAN.md — Синхронизация GRACE, запуск пробы на телефоне, выбор ветки дерева решений (wave 3, checkpoint)
- [x] 01-07-PLAN.md — Толерантный парсер списка устройств и tnp device_info, DevicePaths (wave 4)
- [x] 01-08-PLAN.md — YiRegion, автоопределение региона при логине, флаг ручного сервера (wave 4)
- [x] 01-12-PLAN.md — TransportType/P2pCredentials, расшифровка пароля, шифрованное хранилище, Room v2 (wave 4)
- [x] 01-09-PLAN.md — CameraRegistry: источники по пробе, shared, регион, P2P-реквизиты, кэш (wave 5)
- [x] 01-10-PLAN.md — UI списка камер: состояния, pull-to-refresh, кэш при старте (wave 6)
- [x] 01-11-PLAN.md — Синхронизация GRACE и финальная проверка на телефоне (wave 7, checkpoint)
**UI hint**: yes
**Notes**:
  - Первая задача — одна проба-матрица на устройстве (`/v4/devices/list`, `/v5/devices/list`, `/v5/deviceshare/*` на трёх регионах + `/v4/tnp/device_info`), логирующая только статус/код/длину/имена полей; дальше — по дереву решений из research/SUMMARY.md.
  - Захват трафика (apk-mitm + mitmproxy) становится критическим путём только если все пробы пусты; иначе он переносится в Phase 5 как поиск API событий.
  - Попутный техдолг: пересоздание `YiCloudAuthApi` после смены base URL, единый HMAC-подписчик, лог device_id в `CameraDao.insertCamera`.

### Phase 2: Нативный P2P и первый кадр
**Goal**: Пользователь открывает камеру и видит живое видео (H.264/H.265) через вендорский TNP-транспорт — и в своей Wi-Fi сети, и через мобильный интернет
**Depends on**: Phase 1 (нужны транспорт и P2P-реквизиты камер)
**Requirements**: LIVE-01, LIVE-02, LIVE-03
**Success Criteria** (what must be TRUE):
  1. Сборка извлекает `libPPPP_API.so` (и TUTK-библиотеки) из локального APK с SHA-256 манифестом; на arm64-телефоне проверка `PPPP_GetAPIVersion` проходит, и ни один вендорский бинарник не попадает в Git
  2. Пользователь в той же Wi-Fi сети, что и камера, открывает камеру и видит движущееся видео
  3. Пользователь на мобильном интернете (Wi-Fi выключен) открывает ту же камеру и видит видео через P2P или relay; режим соединения виден в отладочной информации
  4. Камера с H.265 показывает видео так же, как камера с H.264 (или явно сообщает об отсутствии аппаратного декодера)
  5. Чистое Kotlin-ядро (`YiP2pAuth`, `TnpCodec`, `IFrameDecryptor`, `NalUtils`) покрыто JVM-тестами на эталонных векторах; устаревшие контракты `M-STREAM-LIVE` / `M-STREAM-DECODE` заменены в GRACE-документах
**Plans:** 6/13 plans executed

Plans:
- [x] 02-00-PLAN.md — Wave 0: базовая линия, контракты портов (CameraLink, PpppNative, StreamTypes), FakePpppNative, формы секретов в RedactionScanner, GRACE-узлы (M-STREAM-LIVE/DECODE → superseded)
- [x] 02-01-PLAN.md — Вендорское правило транспорта: /v4/tnp/device_info для каждой камеры, TNP по форме DID, иначе по регистру uid; P2pCredentialsSource (wave 1)
- [x] 02-02-PLAN.md — Gradle-извлечение вендорских .so из локального APK + SHA-256 манифест, abiFilters, R8 keep, HANDOFF (wave 1)
- [x] 02-03-PLAN.md — Java JNI-шим с вендорскими FQCN, NativeLibs, PpppRuntime, AndroidPpppNative, DecoderCatalog, «Проверить P2P» в настройках (wave 1)
- [x] 02-04-PLAN.md — TDD: YiP2pAuth, TnpCodec, IoCtrlMessages, FrameHeadCodec на эталонных векторах (wave 1)
- [x] 02-05-PLAN.md — TDD: IFrameDecryptor, NalUtils, FrameAssembler, FramePipeline (wave 1)
- [x] 02-06-PLAN.md — Проверка на телефоне №1: загрузка libPPPP_API, PPPP_GetAPIVersion, транспорт обеих камер, HEVC-декодер, гейт 2B (wave 2, checkpoint)
- [x] 02-07-PLAN.md — TnpCameraLink + фабрика (одна сессия на камеру, порядок вызовов вендора, без повторов) (wave 3)
- [x] 02-08-PLAN.md — VideoDecoder (MediaCodec async → Surface) + DecoderPolicy (wave 3)
- [x] 02-09-PLAN.md — Отладочный экран live из списка камер: режим, кодек, декодер, fps, decryptOk (wave 4)
- [x] 02-10-PLAN.md — 2B часть A: гейт; при TUTK-камере — TUTK-шим, TutkNative, TutkRuntime, иначе задокументированный пропуск (wave 5)
- [x] 02-11-PLAN.md — 2B часть B: TutkCameraLink + диспетчер фабрик по транспорту, или пропуск (wave 6)
- [ ] 02-12-PLAN.md — Синхронизация GRACE и проверка на телефоне: Wi-Fi, мобильная сеть, H.264/H.265, передача сессии официальному приложению (wave 7, checkpoint)
**Research**: done — `.planning/phases/02-p2p/02-RESEARCH.md`
**UI hint**: yes

### Phase 3: Полноценный live-view
**Goal**: Живое видео ведёт себя как в нормальном приложении: видно состояние связи, само восстанавливается, управляется звуком, качеством, снимком и жестами, не держит камеру в фоне
**Depends on**: Phase 2
**Requirements**: LIVE-04, LIVE-05, LIVE-06, LIVE-07, LIVE-08, LIVE-09, LIVE-10, LIVE-11
**Success Criteria** (what must be TRUE):
  1. Пользователь видит состояние соединения (подключение / LAN / P2P / relay / офлайн / ошибка авторизации); после кратковременного обрыва сети видео возвращается само, с ограниченным backoff, без нажатий
  2. Пользователь включает и выключает звук (по умолчанию выключен) и переключает HD/SD (на relay по умолчанию SD)
  3. Пользователь сохраняет снимок кадра в галерею, разворачивает видео на весь экран и масштабирует его жестом
  4. При уходе с экрана или сворачивании приложения поток останавливается; на камеру открыта одна P2P-сессия, и официальное приложение продолжает показывать видео параллельно
  5. Пользователь смотрит видео дольше официального лимита превью, без рекламы и прерываний
**Plans:** 14/14 plans complete

Plans:
- [x] 03-00-PLAN.md — Wave 0: порт-типы (AudioUnit, CameraLink.setAudioSink/requestResolution/capabilities, SupervisorState, MediaSession, NetworkMonitor), configChanges + WRITE_EXTERNAL_STORAGE max 28, GRACE-узлы (wave 1)
- [x] 03-01-PLAN.md — TDD: AdtsParser + AudioPipeline (расшифровка TNP-аудио, mute, доступность звука) (wave 2)
- [x] 03-02-PLAN.md — TNP: ch1-аудио в AudioUnitSink, requestResolution через txQueue, abilityRet 4882 (wave 2)
- [x] 03-03-PLAN.md — TUTK-шим: avRecvAudioData / avRecvIOCtrl / avClientCleanAudioBuf, проверка экспортов обеих ABI (wave 2)
- [x] 03-05-PLAN.md — TDD: QualityController (HD/SD, D-06/D-07) + ResumePolicy (5 мин, D-10) (wave 2)
- [x] 03-08-PLAN.md — NetStateTracker + AndroidNetworkMonitor, VideoDecoder.videoSize (wave 2)
- [x] 03-09-PLAN.md — Снимок в галерею (MediaStore Pictures/YiCamAlt) + ZoomMath (wave 2)
- [x] 03-04-PLAN.md — TUTK: потоки tx/rxc/rxa, 768, 4881 LE, расширенный teardown (wave 3)
- [x] 03-06-PLAN.md — TDD: LiveSessionSupervisor (backoff 2/5/10/20, пароль/kick, сеть, stall, drain, resume) (wave 3)
- [x] 03-07-PLAN.md — AacDecoder (MediaCodec → AudioTrack) + AudioFrameQueue (wave 3)
- [x] 03-10-PLAN.md — DefaultMediaSession + LiveViewModel (плашка, звук, HD/SD, снимок, жизненный цикл) (wave 4)
- [x] 03-11-PLAN.md — LiveViewScreen на TextureView, полный экран, жесты; тап → live, long-press → отладочный экран (wave 5)
- [x] 03-12-PLAN.md — Синхронизация GRACE, тройной прогон тестов, 03-HUMAN-UAT.md (wave 6)
- [x] 03-13-PLAN.md — Проверка на телефоне H-L1..H-L9 (wave 7, checkpoint)
**Research**: done — `.planning/phases/03-live-view/03-RESEARCH.md`
**UI hint**: yes

### Phase 4: Архив SD-карты
**Goal**: Пользователь находит и смотрит записи с SD-карты камеры за любой день через ту же P2P-сессию
**Depends on**: Phase 3 (общая P2P-сессия и пайплайн декодирования)
**Requirements**: SD-01, SD-02, SD-03, SD-04, SD-05
**Success Criteria** (what must be TRUE):
  1. Пользователь выбирает день в календаре, где отмечены дни с записями на SD-карте
  2. Пользователь видит 24-часовую шкалу выбранного дня с записанными отрезками, запускает воспроизведение с выбранного момента и перематывает по шкале
  3. Пользователь ставит паузу, продолжает и одним действием возвращается к живому видео
  4. Если SD-карты нет или за день нет записей, пользователь видит понятное состояние, а не пустую шкалу или зависание
**Plans**: 10 plans
Plans:
- [x] 04-00-PLAN.md — CameraLink archive port (sendIoCtrl, replies, useCount, stream mode) + TNP ch4/5/7, generic IOCTRL
- [x] 04-01-PLAN.md — SdArchiveCodec + SdTime: byte-exact archive payloads/parsers, UTC wire time (TDD)
- [x] 04-02-PLAN.md — TUTK link: generic IOCTRL, 793 replies, shared useCount, -20012 limit only in LIVE
- [x] 04-03-PLAN.md — Archive core: port contract, SdTimeline (days/segments/seek), PlaybackCommands, SdIndexRepository
- [x] 04-04-PLAN.md — Device probe on both cameras (SD status, index, play/pause/live), 04-PROBE.md, wire-time decision
- [x] 04-05-PLAN.md — useCount generation gate, media-session frame observation, PlaybackController state machine
- [x] 04-06-PLAN.md — Mode-aware supervisor + SupervisorArchivePort, ArchiveCoordinator, LiveViewModel archive API
- [ ] 04-07-PLAN.md — Archive UI under the video: timeline ruler, day month-grid dialog, pause/«Живой», D-07 texts
- [ ] 04-08-PLAN.md — GRACE docs sync (M-SD-ARCHIVE, V-M-SD-ARCHIVE, graph) + 04-HUMAN-UAT.md
- [ ] 04-09-PLAN.md — Device verification H-SD1..H-SD9, D-06 decision for TUTK
**Research**: needs `/gsd:research-phase` (HIGH) — недокументированные IOCTRL-пакеты SD-воспроизведения (9030, ch4/5), формат индекса дней/отрезков
**UI hint**: yes

### Phase 5: Лента событий
**Goal**: Пользователь видит события движения по всем камерам с превью, смотрит бесплатные клипы и переходит к полной записи на SD, а аккаунт не рискует из-за частых запросов
**Depends on**: Phase 1 (список камер). Может идти параллельно с Phases 2–4; EVT-04 (переход к SD) замыкается после Phase 4
**Requirements**: EVT-01, EVT-02, EVT-03, EVT-04, EVT-05, EVT-06
**Success Criteria** (what must be TRUE):
  1. Пользователь видит ленту событий движения по всем камерам с превью-картинкой и временем, и фильтрует её по камере и по дню
  2. Пользователь открывает 6-секундный бесплатный клип события, и он воспроизводится
  3. Из события пользователь одним действием открывает запись SD-карты этой камеры в момент события
  4. Без сети ранее загруженные события и превью видны из локального кэша
  5. На переднем плане лента опрашивает сервер не чаще раза в 5 минут; эндпоинты оплаты/подписок и `/v5/alert/push_prop` не вызываются
**Plans**: TBD
**Research**: needs `/gsd:research-phase` — реальный эндпоинт алертов (кандидат `/v5/alert/events`, при необходимости захват apk-mitm + mitmproxy), расшифровка превью/клипов (`pic_pwd` / `video_pwd`), глубина хранения
**UI hint**: yes

### Phase 6: Релизная сборка и проверка на телефоне
**Goal**: У пользователя на телефоне стоит подписанный APK, в котором все v1-сценарии подтверждены на реальных камерах
**Depends on**: Phase 4, Phase 5
**Requirements**: REL-01, REL-02
**Success Criteria** (what must be TRUE):
  1. Из чистого клона с локальным APK Yi Home собирается подписанный APK для arm64-v8a / armeabi-v7a; вендорские .so извлекаются при сборке и не лежат в репозитории
  2. На телефоне пользователя пройдены все v1-сценарии: список камер, live-view в LAN и удалённо, H.264/H.265, архив SD, лента событий — в том числе при одновременно открытом официальном приложении
  3. Финальный прогон `RedactionScanner` по логам реальной сессии не находит секретов; по итогам проверки GRACE `docs/verification-plan.xml` отражает пройденные гейты
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 6. Phase 5 can run in parallel with 2–4 after Phase 1 (EVT-04 finishes after Phase 4).

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Секреты и реальный список камер | 0/TBD | Not started | - |
| 2. Нативный P2P и первый кадр | 6/13 | In Progress|  |
| 3. Полноценный live-view | 0/TBD | Complete    | 2026-10-30 |
| 4. Архив SD-карты | 6/10 | In Progress|  |
| 5. Лента событий | 0/TBD | Not started | - |
| 6. Релизная сборка и проверка на телефоне | 0/TBD | Not started | - |
