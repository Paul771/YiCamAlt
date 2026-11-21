# Milestones

## v1.0 MVP (Shipped: 2026-11-21)

> Closeout type: `override_closeout`. Known verification overrides: 1 newly acknowledged,
> 0 carried forward from a prior close (see STATE.md Deferred Items). Milestone audit was
> `gaps_found` (27/33 requirements fully satisfied, 6 with documented caveats, 0 blockers);
> gaps accepted as technical debt by explicit user decision on 2026-11-21.

**Phases completed:** 7 phases, 75 plans, 12 tasks

**Key accomplishments:**

- Debug log moved to filesDir/diag_log.txt with 1 MiB rotation and legacy auth_log.txt purge; DAO, command, shell and auth logs no longer carry serials, user ids or epoch-millis
- Retrofit now sits on a placeholder host and the real gw-us/gw-eu/gw-sg base URL is resolved from ConfigModule on every request, with official Yi Home 6.9.8 headers. A debug-only, value-free probe covers 4 list endpoints x 3 regions plus one tnp device_info call, on a client that cannot refresh the token.
- Shape-tolerant `DeviceRecordParser` for `/v5/devices/list` and `/v4/tnp/device_info`, with `DevicePaths`, `signedList(@Url)` and `tnpDeviceInfo` on the Retrofit interface, pinned by 14 synthetic-fixture tests.
- The app now starts on the real US gateway. An explicit login finds the account's gateway by itself: it hops on code 20253, then checks the device list on each region (US, EU, SG) and keeps the first one that has cameras. One login press makes at most 3 login and 3 device-list requests. A server the user types in Settings turns all of this off.
- CameraRegistry now reads `/v5/devices/list` on the login region, treats 20200 as "no cameras", stores transport + P2P credentials (decrypted password, TNP DID/InitString/License from `/v4/tnp/device_info`) only in the encrypted store, writes Room atomically, and wipes both on logout.
- The Cameras tab now shows the Room cache instantly, refreshes once from `/v5/devices/list`, distinguishes Loading / Content / Empty ("Камеры не найдены") / three typed errors with "Повторить", keeps the old list under a stale banner when a refresh fails, supports material3 1.2.0 pull-to-refresh, and no longer renders the device id.
- Transport resolver (type 0/1/2 cross-checked with the DID shape), AES-ECB camera-password recovery keyed by the first 16 chars of the cloud uid, an EncryptedSharedPreferences credential store, and a secret-free Room cameras row v2 with a transactional replaceAll.
- Physical-device UAT closed on Xiaomi Mi 9: TNP and TUTK live streaming verified on LAN, mobile data and relay, with a redaction sweep of the live logcat
- TnpCameraLink v0.3.0 routes ch1 audio to an AudioUnitSink (encrypted = header version >= 2), queues in-session 4881 resolution requests through the existing single writer (useCount 3, 4, ...), and exposes 4882 abilityRet 1 as `resolutionSwitch=false`, with the start burst unchanged.
- Signed release 1.0.0 replaced the debug build on the Mi 9 after an explicit user «да», with signer mismatch proven before removal and signer match proven after install.
- Документы Phase 6 и канонические GRACE-артефакты синхронизированы с результатами регрессии: обновлены гейты верификации, дерево разработки, граф знаний, статус проекта и bootstrap-инструкция HANDOFF.
- Устранён регрессионный дефект LIVE-06 (H-R3), из-за которого звук live-стрима на камере TNP ("Спальня") был неслышим на устройствах Xiaomi под управлением MIUI.
- Устранены дефект SD-01 (H-R9, ошибка получения индекса архива на камере TUTK «YI Home Camera», LIST_FAILED) и ограничение выбора дней в календаре (H-R8/9), препятствовавшее выбору вчерашних и более ранних дат без точек.
- Устранены дефект EVT-04 (H-R10, слайд-шоу с задержкой 3-5 секунд на кадр при переходе к архиву из событий) и проверено требование LIVE-07 (установка качества SD по умолчанию при подключении через Relay).
- Release tooling and v1.0.1 bump shipped with a version-aware APK checker and adaptive launcher icon; physical device regression remains at a blocking human checkpoint

---
