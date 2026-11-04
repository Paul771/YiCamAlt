# Phase 5: Лента событий - Research

**Researched:** 2026-10-31
**Domain:** Yi Cloud motion-alert feed (Yi Home 6.9.8): which list endpoint the official app actually calls, request signing, response shape, encrypted previews and 6-second clips, URL expiry, a safe request rate, and mapping event time onto the SD archive from Phase 4.
**Confidence:** HIGH for the endpoint, parameters, signing, field names and the media decryption algorithm. All of these were read this session in the git-ignored decompile of 6.9.8 (`_re/jadx2`). MEDIUM for value semantics that only a live response can confirm: time units, `expires`, server retention, `file_type` values, and whether motion previews are plain JPEG or a tar. **Nothing in this document has been checked against the user's real account.**

Evidence tags:
- **VERIFIED**: read this session in `_re/jadx2/sources/...`. The file is cited.
- **PROJECT**: read in this repository's code or docs.
- **ASSUMED**: inference that must be confirmed on the device.

No secret values (tokens, hmac, uid, DID, passwords, media keys, hosts of signed URLs) appear here.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
### Вход (EVT-01)
- **D-01:** Лента живёт в третьей вкладке нижней навигации «События» рядом с «Камеры» и «Настройки». Одна общая лента по всем камерам; отдельного блока событий на экране камеры нет.

### Карточка и клип (EVT-01, EVT-03, EVT-04)
- **D-02:** Лента - список карточек: превью слева, название камеры и время справа, сгруппировано по дням. Тап открывает 6-секундный клип в диалоге поверх списка с кнопкой «Открыть запись на SD» (переход на экран этой камеры в архив на момент события). Если у камеры нет SD или записи в этот момент нет, кнопка ведёт в архив с обычным текстом состояния из Phase 4 (D-07 там).

### Фильтры (EVT-02)
- **D-03:** Чипы камер сверху (по умолчанию все камеры) и кнопка «Сегодня ▾» с тем же календарём дней, что в архиве (CalendarGrid), по умолчанию последние дни без выбора конкретного.

### Обновление и кэш (EVT-05, EVT-06)
- **D-04:** Лента обновляется при входе во вкладку и жестом «потянуть вниз», не чаще раза в 5 минут; если рано - показывается кэш с подписью «обновлено N мин назад». Автоопроса в фоне и на открытой вкладке нет. Кэш (события и превью) хранится 30 дней и виден без сети.

### Claude's Discretion
- Визуальное оформление карточек, размеры превью, тексты пустого состояния («Событий пока нет», «Нет сети, показан кэш»), анимации.
- Способ хранения кэша (Room + файлы превью), очистка по возрасту, формат разбора ответа - по результатам исследования.
- Реализация плеера клипа (тот же декодер или MediaPlayer), если клип отдаётся готовым видеофайлом.

### Deferred Ideas (OUT OF SCOPE)
- Фоновый автоопрос и push-уведомления о событиях - отдельная фаза по запросу.
- События с SD как второй источник ленты (SD-06), скачивание клипа (SD-07) - v2.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| EVT-01 | Пользователь видит ленту событий движения по всем камерам с превью-картинкой и временем | §1 endpoint `/v2/alert/list`: one request covers every camera of the account. §2 preview = HTTP GET plus AES-128-ECB decrypt with `pic_pwd`. §5 Room cache and preview files |
| EVT-02 | Фильтрует события по камере и по дню | §1 the server filters only by account and time window, so filter by camera locally in Room (the official app does the same). Day = local-zone day bounds via `SdTime.dayBounds` |
| EVT-03 | Открывает короткий клип события (6 с из облака, бесплатный) | §2 clip = `video_urls` → download → decrypt with `video_pwd` → local MP4 → MediaPlayer on a TextureView. Refresh expired URLs with a ±5 s window query |
| EVT-04 | Из события переходит к полной записи на SD в момент события | §4 `time` is absolute epoch ms. Pass it into `LiveViewScreen` → `ArchiveCoordinator.selectDay` + `seek`. `SdTime` already converts to wire UTC |
| EVT-05 | Ранее загруженные события доступны офлайн из кэша | §5 events in Room plus decrypted previews in `filesDir`. Purge after 30 days. Never cache media keys or URLs on disk |
| EVT-06 | Опрос не чаще раза в 5 минут; оплата/подписки и `/v5/alert/push_prop` не вызываются | §3 deny list (write/delete/push/paid endpoints), a 5-minute gate persisted in `app_meta`, a page cap per refresh, one request in flight |
</phase_requirements>

## Project Constraints (from AGENTS.md / CLAUDE.md / PROJECT.md)

- GRACE is mandatory. Every new or changed module needs a MODULE_CONTRACT, MODULE_MAP, CHANGE_SUMMARY and `START_BLOCK_`/`END_BLOCK_` markers. Log tags use the form `[Module][fn][BLOCK]`. Update `docs/development-plan.xml` (M-EVENT, M-EVENT-PLAYBACK, M-UI-EVENTS), `docs/knowledge-graph.xml` and `docs/verification-plan.xml`.
- Logs never contain tokens, hmac, uid/DID, userid, media keys (`pic_pwd`/`video_pwd`), or signed media URLs. `LogRedactor.maskQuery` covers `pwd`, `secret` and `signature` keys. Media URLs still must not be logged: log the host class and extension at most.
- Vendor binaries and decompiled output (`_apk/`, `_re/`) are git-ignored. Do not commit them and do not copy code from them verbatim. Re-implement from the algorithm description.
- Gradle runs only through `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 ...`. AppLocker blocks `.bat`. Use JDK 17 by full path.
- Verify on the physical phone only (Xiaomi Mi 9, MIUI). The emulator is impossible.
- Account safety: rare requests, no re-login loops, never call paid, subscription or push endpoints.
- Stack: Kotlin, Compose, Material3 (BOM 2024.02.00, pull-to-refresh already used in `CameraListScreen`), Hilt, Retrofit 2.9 / OkHttp 4.12, kotlinx-serialization, Room 2.6.1 with `fallbackToDestructiveMigration` (cache-only DB, version 2).

## Summary

The official 6.9.8 client does **not** use `/v5/alert/events` for its alert feed. The feed is `GET /v2/alert/list`, called through the "AlertRepo" sync (`com/ants360/yicamera/alert/a.java` → `ab/f.J` → `wa/d.H`). It is signed with the same Base64(HMAC-SHA1, key `token&token_secret`) scheme that `/v5/devices/list` already uses on the device, with an insertion-ordered message. The request is per **account** (`userid`), not per camera: one call returns events of all cameras, and the official app filters by camera locally in its own DB. `/v5/alert/events` exists, but the app only calls it per device with `per_page=1` to get the latest event ("WarnMode"). `/v4/alert/events` is called with **HTTP DELETE** to delete events and must never be called.

Each event carries `uid` (camera cloud uid, the same id as our `CameraInfo.deviceId`), `time` (epoch **milliseconds**, VERIFIED by how the app does arithmetic on it), `type`/`sub_type`, `pic_urls` + `pic_pwd`, `video_urls` + `video_pwd`, `expire_time` (epoch ms after which the URLs are stale), `file_type` and `id`. Media is encrypted per event. The download body is `[u32 little-endian plaintext length][AES-128/ECB/NoPadding ciphertext]`. The key is the first 16 ASCII characters of the password. Bytes past the declared length are padding. The decrypted clip is written to a local `.mp4` and played as a file. HLS (`file_type == "ts"`) is a separate path. The decrypted preview is a JPEG. If the URL contains `.tar`, the preview is a tar archive whose non-face `.jpg` entry is the preview. `.yitar` is the face-recognition bundle and is out of scope.

Mapping to the SD archive is simple, because `time` is an absolute instant. Pass the epoch ms to the camera screen; `SdTime.toWire` already handles the UTC wire format. The official app seeks exactly to the alert time, except two specific models (−3 s / −6 s).

**Primary recommendation:**
- Re-point M-EVENT from the guessed `/v5/alert/events` (and its invented `device_id/start_time/page_size` params) to `GET /v2/alert/list` with the vendor parameter order.
- Decrypt media in pure Kotlin (`javax.crypto`, no new dependency).
- Download through a separate plain OkHttp client.
- Cache metadata in Room and downscaled previews in files.
- Play the decrypted MP4 with platform `MediaPlayer` on a `TextureView`.
- Gate the first device run behind a one-shot, shape-only alert probe in the app (the user consents and runs it), not mitmproxy.

## 1. The alert list endpoint (VERIFIED)

### Call chain in 6.9.8
`NewAlertFragment` → `alert/a.java` (`C0082a.H/G/l/m` → `M(userid, from, to)`) → `ab/f.J(userid, from, to, limit, expires)` → `wa/d.H(...)` → `GET /v2/alert/list`. The response is parsed by `Alert.i2(userid, data[])`. Results go into the app's local DB; the feed UI pages from the DB, filtered by the selected device ids and categories.

### Request (`wa/d.java` method `H`)
Method `GET`. Query parameters in this exact insertion order, which is also the signed order:

| # | Key | Value in the official app | Notes |
|---|-----|---------------------------|-------|
| 1 | `seq` | `"0"` | **Not "1"**, unlike devices/list. The signature must use the same value |
| 2 | `userid` | account user id | |
| 3 | `type` | `""` (empty) | still present in the signed string as `type=` |
| 4 | `sub_type` | `""` (empty) | signed as `sub_type=` |
| 5 | `from` | epoch **seconds** (10 digits) | client guard: `from > 1704067200`, `from < to`, 10 digits; otherwise no request. Clamped to ≥ now − 30 days (`Alert.X` = 30 d in ms) |
| 6 | `to` | epoch **seconds** | first call: local start of tomorrow (`b0.i0() + 86400000`) / 1000 |
| 7 | `limit` | `100` (`alert/a.g`) | page size |
| 8 | `fromDB` | `"true"` | |
| 9 | `expires` | `10` (`Alert.U`) | probably the signed-URL lifetime (minutes?). ASSUMED |
| - | `hmac` | `Base64(HMAC-SHA1(token&token_secret, "seq=0&userid=…&type=&sub_type=&from=…&to=…&limit=100&fromDB=true&expires=10"))` | appended after signing, not signed |

The signer is `wa/d.m0`: it joins the LinkedHashMap as `k=v&k=v` in insertion order and runs `mc/c1.b` (HmacSHA1, UTF-8, Base64). The existing `network/YiRequestSigner` already produces exactly this when called as:
```kotlin
YiRequestSigner.sign(token, tokenSecret, userId, seq = "0", extra = listOf(
    "type" to "", "sub_type" to "", "from" to "$fromSec", "to" to "$toSec",
    "limit" to "100", "fromDB" to "true", "expires" to "10"))
```
The `@Query` order in Retrofit does not affect the server. Only the signed string order matters, and Retrofit URL-encodes the Base64 `hmac` value. The headers are the normal app GET headers (`x-kamihome-appType`, `x-kamihome-packageType`, `User-Agent`, `x-xiaoyi-appCountryCode`, `x-xiaoyi-appVersion`). Phase 1 `YiAppHeaders` already sends them. The base URL is the detected region (gw-us for this user) via the existing placeholder-host interceptor.

### Pagination (alert/a.java `M` / `N`)
The server returns newest first (inferred from the loop). The official loop:
1. Request `[from, to]`.
2. If the page size ≥ `limit`, set `to = min(time)/1000` and repeat with the same `from`. The boundary is inclusive, so expect one duplicate and de-duplicate by key.
3. Stop when the page size < `limit`.

Incremental sync starts `from` at the newest cached event time (`aVarN.j(dids)`), floored at the window start. Add a guard the vendor lacks: stop if the new `to` ≥ the previous `to` (no progress).

### Response (`ab/f.d`, `Alert.b.H0`)
- The envelope is `{"code": 20000, "data": [ … ]}`. Success is **`code == 20000`**; anything else is treated as empty. `code` may arrive as a string (the project envelope already models it as String). `20202` = session expired (global handler).
- Per element (all `opt*`, so every field is optional):

| Field | Type | Meaning / use |
|-------|------|---------------|
| `id` | string | server event id (`k2`). Can be empty |
| `uid` | string | camera cloud uid, the same id as `CameraInfo.deviceId` (vendor `L()` uses `DeviceInfo.UID`). ASSUMED equal; verify |
| `time` | long | **epoch ms** (vendor computes `(time − 5000)/1000` for `from` and `min(time)/1000` for `to`) |
| `type`, `sub_type` | int | category. `type 0` with several sub_types = camera detections; 1/2/3/256/512/1024/1000 = other device families (sensors, doorbell, baby, gateway, custom). The UI label mapping lives in `Alert.b.a0` (motion / human / sound / baby crying / vehicle / pet / face …) |
| `pic_urls` | string | preview URL; may be a comma-separated list. Use the first non-empty |
| `pic_pwd` | string | preview key; comma list aligned with `pic_urls` |
| `video_urls` | string | clip URL (6 s on the free tier) |
| `video_pwd` | string | clip key |
| `video02_urls` | string | second clip (dual-lens models); ignore in v1 |
| `expire_time` | long | epoch ms. URLs are stale when `now > expire_time + 10 s` (`Alert.d2`) |
| `file_type` | string | `"ts"` means HLS (`Alert.f2`); otherwise a single file (MP4) |
| `play_time` | int | clip duration hint |
| `category` | int | `0` sets a flag (`A2`). Semantics unclear; keep raw |
| `push_type`, `event_source`, `event_desc`, `ai_alert_level`, `incorrect_desc`, `no_risk`, `content`/`message` | mixed | AI and custom-alert extras; keep raw, do not interpret in v1 |

The official de-duplication key is `(uid, time)`. Its delete payload is `[{uid, time}]`. The local identity string is `"<userid>_<uid>_<time>"`.

**Our primary key:** `id` if non-blank, else `"<uid>_<time>"`. It is stable across refreshes.

### Other alert endpoints and what they are (VERIFIED in `wa/d.java`)

| Path | Method | Used for in 6.9.8 | Our use |
|------|--------|-------------------|---------|
| `/v2/alert/list` | GET | the feed sync (above) | **use** |
| `/v5/alert/events` | GET | per-device latest event: `user_id, seq=1, cur_time, uid, start_time=now−1y, end_time=now, page=0, per_page=1`. **`time` in seconds** in this response (multiplied by 1000) | not needed; do not use as a list |
| `/v5/alert/events/top` | GET | `user_id, seq, cur_time, state=1, limit=1`: latest pinned or unread "WarnMode" | not needed |
| `/v4/alert/events` | **DELETE** | deletes up to 20 events `[{uid,time}]` | **NEVER** |
| `/v2/alert/get_push`, `/v4/alert/push_prop`, `/v5/alert/push_prop` | GET / PUT | push settings (PUT changes push flags of the camera) | **NEVER** |
| `/v8/monitor/alarm/*`, `data/dto/response/Alarm` (`equPicUrls`, `equVideoUrls`, …) | - | Pro Monitoring (paid alarm service) | **NEVER** |

## 2. Preview and clip: download, decrypt, play (VERIFIED algorithm, ASSUMED container details)

### Decryption (`mc/c.java` + `mc/b.java` + `com/tutk/IOTC/Packet.byteArrayToInt(..., false)`)
```
key        = password.take(16) as ASCII bytes      // AES-128; pwd longer than 16 -> truncated
plainLen   = u32 little-endian from body[0..4)
plain      = AES/ECB/NoPadding decrypt(body[4..end), key)
bytes past plainLen are zero-filled (vendor keeps them; we should TRUNCATE to plainLen)
```
- Preview: `pic_urls` + `pic_pwd`. Glide fetches via the pseudo-URL `shttp://local?secret=<pwd>&origin=<base64(url)>` (`mc/c2`, `base/f0`): plain HTTP GET, connect 5 s / read 20 s, then the decrypt above. If `pic_pwd` is empty, the body is used as-is.
- Preview container:
  - Plain URL → JPEG/PNG bytes after decrypt.
  - URL contains `.tar` → decrypted bytes are a tar. The preview is the `.jpg` entry that does **not** match `face_detect/face_.*jpg` (`base/k0` GlideTarDecoder).
  - URL contains `.yitar` → face bundle, handled by the face module. Skip it and show a placeholder.
- Clip: `video_urls` + `video_pwd` (`task/a.java`): GET (connect 5 s, read 15 s, 2 attempts), decrypt if a key is present, write to `ivCache/<date>/<…>_<time>.mp4`, then play the **local file** (`BaseAlertVideoPlayFragment` → `setVideoPath(localPath)` on an ijkplayer view). If `file_type == "ts"` → HLS → the vendor uses `Hls2Mp4`. **Out of scope for v1:** show "клип недоступен".
- Expiry: before downloading, if `now > expire_time + 10 s`, the official app re-queries `/v2/alert/list` with `from = (time−5000)/1000`, `to = (time+5000)/1000`, `limit=100`, takes the element with the same `uid` and `time`, and uses its fresh URLs and keys (`ab/f.M`, `alert/a.O`). That is one signed request per stale clip open.

### Where the clip lives and how it is played
The decrypted output is an MP4 file (ASSUMED from the `.mp4` file name and the file-path player; the codec inside is unknown, probably H.264, maybe H.265 on newer models). Use **platform `MediaPlayer` on a `TextureView`** inside the Compose dialog:
- It needs no new dependency, and 6-second local files need no adaptive streaming.
- Our Phase 2/3 decoder takes raw NAL frames from P2P, not MP4 containers, so reusing it would need a demuxer (`MediaExtractor`). `MediaPlayer` does that internally.
- Use `TextureView`, not `VideoView`/`SurfaceView`. On this MIUI phone a SurfaceView renders black unless `setZOrderOnTop(true)` (Phase 3 finding), and z-order-on-top inside a dialog would draw over the dialog buttons.
- If `MediaPlayer` reports an error (codec unsupported), show "Не удалось воспроизвести клип" and keep the «Открыть запись на SD» button.

Alternative (not recommended unless MediaPlayer misbehaves): Media3 ExoPlayer. It is a new dependency, and GRACE M-EVENT-PLAYBACK mentions ExoPlayer, but that contract is planned and can be updated.

### Network client for media
Download media with a **separate plain `OkHttpClient`** (timeouts like the vendor's, no interceptors), not the shared Retrofit client:
- The shared client's auth interceptor would add our `Authorization` header to a third-party CDN request. That leaks the token, and presigned object-store URLs often reject unexpected auth headers.
- The BASIC logger would print the signed URL. Only `pwd/secret/signature` keys get masked, and vendor-specific signature keys may not.
- Log only `status`, `bytes`, `ext` (jpg/tar/mp4/m3u8) and `decryptOk`.

## 3. What must never be called, and a safe rate (VERIFIED list + policy)

**Deny list (enforce in code review and with a unit test that greps the event package's path constants):**
- `/v4/alert/events` (DELETE).
- `/v2/alert/get_push`, `/v4/alert/push_prop`, `/v5/alert/push_prop`.
- `/v5/alert/del_list`.
- `/v8/monitor/alarm/*`, `/v8/ai/alert/*` (AI credit and config).
- `/orderpay/*`, `/vas/*` (cloud, entitlement, trial, banner, `orderPayRemindAndBanner`), `/v1/equipment/cloud/isGive`.
- `/vas/v8/cloud/videos*` (paid cloud video).

Delete `EventPaths.PLAYBACK = "/bs/v8/recording/{id}"` and `EVENT_LIST_V4`. The clip URL comes from the list item itself. `/v4/alert/events` is a delete endpoint, so keeping it as a constant is a footgun.

**Rate:**
- The official app re-syncs on alert-screen entry and keeps a `last_alert_sync_time` gate. The free tier also has a camera cooldown of up to 10 minutes between alerts (FEATURES.md strings), so a typical refresh returns 0–few events in **one page**.
- Persist `events_last_fetch_ms` in `app_meta` (Room), not in memory, so a process restart cannot bypass it.
- If `now − last < 5 min`, show the cache and «обновлено N мин назад».
- One request in flight (Mutex).
- At most **10 pages per refresh**. If capped, keep the gap boundary (oldest fetched time) and continue on the next allowed refresh.
- The ±5 s URL refresh on clip open is user-initiated and does not count toward the 5-minute feed gate. Throttle it to at most 1 per event per 60 s.
- On `20202`/auth codes, do not retry in a loop. Let the existing refresh path run once, then show the error.
- No WorkManager or background polling (D-04).

## 4. Event → SD archive moment (VERIFIED + PROJECT)

- `time` is an absolute instant in **epoch ms**, independent of time zone. Phase 4 `SdTime.toWire(epochMs)` breaks it into wire UTC fields (`WIRE_IS_UTC = true` for gw-us). Day grouping and the day filter use `SdTime.localDate(epochMs, ZoneId.systemDefault())` and `SdTime.dayBounds`.
- The official SD jump (`BaseAlertVideoPlayActivity.o2`, `MessageAlertVideoPlayBallActivity.n3`) passes `alert_time` = the alert's time unchanged, except −3000 ms for model H21 and −6000 ms for model H18. It also refuses when the camera is offline ("camera_hint_noConnection").
- Recommendation: a named constant `EVENT_SD_PREROLL_MS = 0` (vendor parity).
- Integration:
  1. Add `initialArchiveMs: Long? = null` to `LiveViewScreen` and lift `AppShellScreen` state to carry `(camera, initialArchiveMs)`.
  2. Once the archive index is loaded, call `ArchiveCoordinator.selectDay(localDate)` then `seek(ms)`. `seek` already handles pending refresh, waits at most 3 s, and shows the Phase 4 D-07 texts (no card / no recordings / list failed).
  3. Expose a one-shot "open at" entry on the coordinator or view model, so the seek fires after `start()` and the first index load and does not race the Live start.
- `selectDay` rejects days without recordings. For a missing day, the archive shows "За этот день записей нет" (D-02 of this phase accepts that).

## 5. Retention, sizes, cache design

- **Server retention: unknown (ASSUMED 7 days free; strings conflict between 24 h and 7 d, FEATURES.md).** The client clamps requests to ≤ 30 days. Our cache keeps 30 days (D-04), so it can hold more than the cloud.
- **Preview size: unknown.** Typical Yi motion stills are a few tens of KB, a guess to confirm with the probe. Downscale on save to ~480 px wide JPEG q80 (≈15–30 KB). 2 cameras × ~150 events/day max × 30 d ≈ ≤ 9 000 files ≈ ≤ 250 MB worst case; realistic far less because of the 10-minute cooldown.
- **Room (DB version 2 → 3; destructive migration is fine because this is a cache):** extend `EventEntity` or replace it:
  - `event_id` PK (rule above), `device_id`, `time_ms` (indexed), `type`, `sub_type`, `file_type`, `has_clip`, `thumb_path` (nullable), `cached_at`.
  - Index `(device_id, time_ms)` and `(time_ms)` for the all-cameras feed.
- **Do not persist `pic_pwd`/`video_pwd` or signed URLs in Room or logs.** Keep them in an in-memory map `eventId → MediaRef(picUrl, picPwd, videoUrl, videoPwd, expireMs)` filled by the last sync. When the map misses or is stale, use the ±5 s refresh query. Previews are decrypted and saved right after sync (bounded parallelism, 2), so offline display never needs the keys.
- **Files:**
  - Previews in `filesDir/event_thumbs/<yyyyMMdd>/<hash(event_id)>.jpg` (hash, because ids and uids must not appear in paths that may be logged).
  - Clips in `cacheDir/event_clips/` with a small LRU (~50 MB). Clip download for keeping is SD-07, deferred.
  - Purge rows and files older than 30 days at refresh time (`EventDao.clearOlderThan` exists).
- **Existing code to replace (PROJECT):**
  - `YiCloudEventApi.eventList` sends invented params (`device_id/start_time/end_time/page/page_size`) to `/v5/alert/events`, signed only over `seq=1&userid`. That is wrong on path, params, seq and signed string.
  - `EventTimelineParser` guesses keys. Replace it with a strict mapper for the fields above, still `opt`-tolerant.
  - `EventInfo` needs `subType`, `expireMs`, `fileType`, media refs.
  - `EventServiceModule.getPlaybackUrl` (calls `/bs/v8/recording/{id}`) goes away.
  - `EventServiceModuleTest` (23 tests) must be rewritten alongside.

## Standard Stack

### Core (all already in the project)
| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| Retrofit + kotlinx-serialization converter | 2.9.0 / 1.0.0 | signed `GET /v2/alert/list` | same path as devices/list |
| OkHttp | 4.12.0 | separate plain client for media download | no auth header or URL logging leak to the CDN |
| `javax.crypto` (JDK) | platform | AES/ECB/NoPadding decrypt | the exact vendor primitive; no dependency |
| Room | 2.6.1 | event cache, `app_meta` gate | already wired, destructive migration |
| `android.graphics.BitmapFactory` | platform | decode and downscale previews, `ImageBitmap` in Compose | no image library needed for local files |
| `android.media.MediaPlayer` + `TextureView` | platform | 6 s local MP4 | demuxes internally; avoids the MIUI SurfaceView issue |
| Compose Material3 pull-to-refresh | BOM 2024.02.00 | D-04 gesture | already used in `CameraListScreen` |

### Supporting
| Item | Purpose |
|------|---------|
| Minimal tar reader (own ~40 lines: 512-byte ustar headers, octal size) | only for `.tar` previews. Apache commons-compress is overkill for one entry |
| `ui/live/CalendarGrid.kt` | D-03 day picker reuse |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| MediaPlayer/TextureView | Media3 ExoPlayer | better error reporting and formats, but a new dependency and version check needed; use only if MediaPlayer fails on the device |
| BitmapFactory | Coil | memory cache and lifecycle for free; a new dependency for local files we already decode once |

Installation: none for the recommended path.

## Architecture Patterns

### Recommended structure (re-uses the existing `event/` package; GRACE IDs in brackets)
```
event/
├── AlertListApi.kt          # [M-EVENT] Retrofit GET /v2/alert/list + EventPaths (only LIST)
├── AlertMapper.kt           # [M-EVENT] JSON -> EventRecord + MediaRef (pure, JVM-tested)
├── AlertSyncService.kt      # [M-EVENT] 5-min gate, paging loop, dedupe, purge, Room write
├── MediaCrypto.kt           # [M-EVENT-PLAYBACK] u32LE len + AES-128-ECB, tar preview extract (pure)
├── EventMediaRepository.kt  # [M-EVENT-PLAYBACK] plain OkHttp, URL refresh ±5 s, thumbs/clips files
database/EventEntity.kt, EventDao.kt   # v3 schema
ui/events/EventsScreen.kt, EventsViewModel.kt, ClipDialog.kt   # [M-UI-EVENTS]
ui/shell/AppShellScreen.kt  # third tab «События»; carries (camera, initialArchiveMs)
ui/live/LiveViewScreen.kt / LiveViewModel / ArchiveCoordinator   # openAt(ms) entry
```

### Pattern: pure core + thin I/O shell
- `AlertMapper`, `MediaCrypto`, the paging decision ("next `to`, stop?") and the gate decision ("fetch or show cache") are pure functions with JVM tests.
- Network and files sit behind small ports (`AlertListPort`, `MediaFetcher`) for MockWebServer and fake tests, the same style as Phase 4's `SdTimeline`/`ArchiveCoordinator`.

### Anti-patterns
- Calling the list once per camera. The endpoint is per account, so per-camera calls multiply the request count by the number of cameras.
- Retrying list or media requests in a loop on failure. Show the cache and an error instead.
- Persisting `*_pwd` or signed URLs.
- Sending media requests through the authenticated Retrofit client.
- Adding a periodic ticker on the open tab (D-04 forbids it).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HMAC signing | a new signer | `YiRequestSigner.sign(..., seq="0", extra=...)` | already device-verified for devices/list |
| MP4 demux and playback | feeding MP4 into the P2P decoder | `MediaPlayer` | container parsing, A/V sync |
| Day bounds / DST | manual offset math | `SdTime.dayBounds`, `SdTime.localDate` | already tested |
| Calendar UI | new picker | `CalendarGrid` | D-03 |
| AES | custom cipher code | `Cipher.getInstance("AES/ECB/NoPadding")` | exact vendor primitive |

## Common Pitfalls

### P1: Wrong signed string (seq and empty params)
**What goes wrong:** `/v2/alert/list` returns an auth or hmac error code.
**Why:** `seq` is `"0"` here, and the empty `type=` and `sub_type=` are part of the signed string. Dropping empty params (OkHttp drops null, not empty) or reordering the signed string breaks the hmac.
**Avoid:** a unit test against a fixed vector built with dummy key and values, asserting the canonical string exactly.

### P2: Mixed time units
`from`/`to` are seconds. `time` and `expire_time` in the response are ms. `/v5/alert/events` returns seconds. Normalize once in the mapper. Assert on a fixture that `time` > 10^12. The probe must log only the digit count, not the value.

### P3: Decrypt length handling
The body must be ≥ 4 bytes and `(len−4) % 16 == 0`, or NoPadding throws. Truncate the output to the declared length. The vendor zero-fills instead, which would leave trailing zeros in an MP4 or JPEG. Check magic bytes after decrypt (`FF D8` JPEG, `ftyp` at offset 4 for MP4, `ustar` at offset 257 for tar). Treat a mismatch as "decrypt failed", not a crash.

### P4: Expired URLs
URLs live minutes, not days (ASSUMED from `expires=10` and the refresh path).
- Download previews during sync.
- For clips, check `expireMs` and refresh with the ±5 s window first.
- A 403 from the CDN also means stale: refresh once, then error.

### P5: Token leak to CDN / logs
Media URLs are on a foreign host. Use the plain client and never log the URL. `LogRedactor` masks `pwd` and `secret` key names only inside query strings. That does not cover keys inside JSON bodies, so `responseBodyLogger` paths must not include `/alert` (or must stay shape-only: it already masks values).

### P6: Camera id mismatch
The alert `uid` is expected to equal our `deviceId` (cloud uid). If the probe shows otherwise (e.g. a DID), map through `CameraRegistry`. Events of a camera missing from the list (removed or unshared) are shown with the label «Камера» and are not filterable.

### P7: MIUI SurfaceView black / dialog z-order
Use `TextureView` (see §2).

### P8: Cooldown makes the feed look empty
The free tier sends at most about one alert per 10 minutes per camera, and motion alerts must be enabled in the official app. An empty feed is not a bug. Show «Событий пока нет».

## Code Examples

### Media decrypt (re-implementation of the vendor algorithm; pure Kotlin)
```kotlin
// MediaCrypto.decrypt — body = u32LE plainLen + AES-128-ECB(NoPadding) ciphertext; key = pwd.take(16) ASCII
fun decrypt(body: ByteArray, pwd: String): ByteArray? {
    if (body.size < 4 || (body.size - 4) % 16 != 0) return null
    val plainLen = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8) or
        ((body[2].toInt() and 0xFF) shl 16) or ((body[3].toInt() and 0xFF) shl 24)
    val key = pwd.take(16).toByteArray(Charsets.US_ASCII)
    if (key.size != 16) return null // vendor would throw; AES-128 needs 16 bytes
    val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
    val plain = c.doFinal(body, 4, body.size - 4)
    return if (plainLen in 0..plain.size) plain.copyOf(plainLen) else plain
}
```

### List request (Retrofit)
```kotlin
@GET("/v2/alert/list")
suspend fun alertList(
    @Query("seq") seq: String = "0", @Query("userid") userId: String,
    @Query("type") type: String = "", @Query("sub_type") subType: String = "",
    @Query("from") fromSec: Long, @Query("to") toSec: Long,
    @Query("limit") limit: Int = 100, @Query("fromDB") fromDb: String = "true",
    @Query("expires") expires: Int = 10, @Query("hmac") hmac: String,
): AlertEnvelope   // code: String, data: JsonArray?
```

## State of the Art (project-local)

| Old approach (in repo) | Current approach | Impact |
|------------------------|------------------|--------|
| `/v5/alert/events` + invented params, signed over `seq=1&userid` | `/v2/alert/list`, vendor params, `seq=0`, full signed string | M-EVENT API, signer call, tests rewritten |
| `/bs/v8/recording/{id}` "playback URL" | `video_urls` from the list item + decrypt | delete PLAYBACK path and `getPlaybackUrl` |
| Guessing parser (`EventTimelineParser`) | strict mapper with known keys | simpler, testable |
| `EventPaths.EVENT_LIST_V4` kept "for capture" | removed (it is a DELETE endpoint) | safety |

## Open Questions

1. **Is the response shape exactly as parsed in 6.9.8 on the user's account?** (code type, `data` array, `time` in ms, `uid` = deviceId, `expire_time` in ms)
   - Known: the vendor parsing code.
   - Unclear: whether the server returns these for this account and region.
   - Recommendation: Wave 0 probe (below).
2. **Media format:** plain JPEG or `.tar` preview? MP4 or `ts` clips? H.264 or H.265?
   - Recommendation: the probe downloads 1 preview and 1 clip, decrypts in memory and logs only the magic bytes class and size bucket.
3. **Server retention** (24 h / 7 d / 30 d).
   - Recommendation: the probe requests a 30-day window, limit 100, and logs only the age in days of the oldest returned event and the count.
4. **Meaning of `expires=10`** (minutes?).
   - Recommendation: the probe logs `(expire_time − now)` rounded to minutes.
5. **Do shared cameras' events appear in the owner-userid list?**
   - Known: the vendor uses the same call (its separate shared-sync branch calls the same `M`).
   - Recommendation: the probe logs the count per camera index (no ids).

### Device verification plan (needs the user's phone and consent; NOT run in this research)
**Preferred: a one-shot debug probe inside our app** (like the Phase 1 `EndpointProbe`). It is triggered from Settings → Debug by the user and makes exactly one `/v2/alert/list` call (30-day window, limit 20). At most one preview GET and one clip GET go to the CDN. It logs shape only:
- `code`, element count, the set of keys;
- digit count of `time`/`expire_time` and `(expire_time−now)` in minutes;
- whether each `uid` matches a known `deviceId` (boolean);
- `pic_urls` extension class (jpg / tar / yitar / other) and `file_type` values;
- `pic_pwd`/`video_pwd` lengths only;
- decrypt result plus the magic class (jpeg / png / tar / mp4 / unknown) and size bucket;
- age in days of the oldest event.

It must pass `RedactionScanner`. This is three network requests in total, well within account-safety limits.

**Fallback only if the probe returns non-20000 or empty while the official app shows alerts:** apk-mitm patched Yi Home 6.9.8 + mitmproxy 11 (Python 3.11 venv) on the Mi 9, as in INSTRUCTIONS.md:
1. Open the Alerts tab once, pull to refresh, and open one alert clip.
2. Capture the `/v2/alert/list` request (param names and order, `seq`), the response JSON shape, and the CDN request and response headers (content-type, length) for one preview and one clip.
3. Save the flows under the git-ignored `captures/`. Record only the shape in docs.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Decompiled 6.9.8 (`_re/jadx2`) | this research | ✓ (git-ignored) | 6.9.8 | — |
| Gradle via `tools/gradle.ps1`, JDK 17 | build/tests | ✓ (Phase 4) | Gradle 8.5, AGP 8.2.2 | — |
| Physical phone (Mi 9) + USB adb | probe, UAT | ✓ per memory (adb install works now) | Android 11 | — |
| User consent for a live cloud call | Wave 0 probe | ✗ until asked | — | plan as a checkpoint task |
| mitmproxy + apk-mitm | fallback only | not checked this session | — | probe first |

## Verification notes (nyquist_validation = false; the Validation Architecture section is skipped)
- Quick: `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.event.*"`
- Full: `powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest`
- JVM-testable:
  - signed string vector (P1);
  - mapper on a synthetic fixture (ms `time`, comma lists, missing fields);
  - `MediaCrypto` round-trip (encrypt in test with a dummy key: length prefix, truncation, bad length → null, magic check);
  - tar entry pick;
  - paging stop and no-progress guard;
  - 5-minute gate with a fake clock;
  - deny-list test over event path constants;
  - purge > 30 d;
  - `RedactionScanner` on new log lines.
- Device-only: the real feed, preview decrypt, clip playback on MIUI, the SD jump.

## Sources

### Primary (HIGH, read this session; git-ignored decompile of Yi Home 6.9.8)
- `_re/jadx2/sources/wa/d.java`: methods `H` (`/v2/alert/list`), `W` (`/v5/alert/events`), `V` (`/events/top`), `B` (`/v4/alert/events` DELETE), `t1`/`u1` (`push_prop` PUT), `m0` (signer), `t` / `C0193d` (GET executor), `s0` (devices/list, for comparison).
- `_re/jadx2/sources/za/a.java`: GET builder and headers.
- `_re/jadx2/sources/mc/c1.java`: HmacSHA1 Base64.
- `_re/jadx2/sources/com/ants360/yicamera/alert/Alert.java`: field parser `b.H0`, `a2()` URL/pwd pairing, `d2()` expiry, `f2()` ts, `X` = 30 d, `U` = 10, category labels.
- `_re/jadx2/sources/com/ants360/yicamera/alert/a.java`: sync loop `L/M/N`, limit 100, URL refresh `O`, delete `h`.
- `_re/jadx2/sources/ab/f.java`: `J`, `M` (±5 s), response handlers `c`/`d`/`C0001f`/`y`.
- `_re/jadx2/sources/mc/c.java`, `mc/b.java`, `jadx6/.../com/tutk/IOTC/Packet.java`: AES-128-ECB-NoPadding, u32 LE length.
- `_re/jadx2/sources/mc/c2.java`, `com/ants360/yicamera/base/f0.java`, `base/k0.java`, `task/f.java`: preview fetch, decrypt, tar decoder.
- `_re/jadx2/sources/com/ants360/yicamera/task/a.java`, `activity/message/BaseAlertVideoPlayFragment.java`, `fragment/PlayerMessageFragment.java`: clip download, decrypt to `.mp4`, local-file playback, refresh-before-download.
- `_re/jadx2/sources/com/ants360/yicamera/activity/message/BaseAlertVideoPlayActivity.java` (`o2`), `MessageAlertVideoPlayBallActivity.java` (`n3`): SD jump time.

### Project
- `app/src/main/java/com/yicamalt/event/*`, `network/YiRequestSigner.kt`, `network/HttpClientModule.kt`, `network/LogRedactor.kt`, `database/Event*.kt`, `archive/SdTime.kt`, `ui/live/ArchiveCoordinator.kt`, `ui/shell/AppShellScreen.kt`.
- `.planning/research/FEATURES.md` (free-tier strings: 6 s clips, cooldown, retention conflict), `docs/reference/yi-cloud-endpoints.md`.

### Tertiary
- A web search (2026-10-31) for public documentation of Yi `pic_pwd`/`video_pwd` found nothing Yi-specific. Other vendors use different schemes, e.g. [Tuya encrypted images](https://developer.tuya.com/en/docs/app-development/encryptimage?id=Kceuhc84acev1) and [EZVIZ image encryption](https://support.ezviz.com/zh-cn/faq/article/What-is-Image-Encryption-Password-How-to-decryt-image-or-video). The algorithm here rests on the decompile alone.

## Metadata
**Confidence breakdown:**
- Endpoint, params, signing: HIGH (read in code; same signer as the device-verified devices/list).
- Response field names: HIGH; units and semantics: MEDIUM.
- Decryption algorithm: HIGH; container per camera model: MEDIUM.
- Retention and sizes: LOW.
- SD mapping: HIGH.

**Research date:** 2026-10-31
**Valid until:** about 30 days, or until Yi Home updates past 6.9.9 (re-check `wa/d.H` if the APK changes).
