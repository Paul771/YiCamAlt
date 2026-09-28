---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
# External Integrations

**Analysis Date:** 2026-09-28

> **Read this first.** Every vendor endpoint below was recovered by reverse-engineering the official Yi Home APK (`_apk/`, package `com.ants360.yicamera.international` 6.9.8), not from vendor documentation. Confidence is recorded per call: **CONFIRMED** = reproduced on a real device with a real account; **PROVISIONAL** = a plausible path/scheme that has never returned a real payload. Nothing here is contractual, and the vendor can change any of it without notice (`docs/requirements.xml` `<risk-1>`).

## APIs & External Services

### Yi Cloud — Authentication (CONFIRMED on device)

| | |
|---|---|
| **Purpose** | Email + password login against a Yi account; token issuance and refresh |
| **Implementation** | `app/src/main/java/com/yicamalt/auth/YiCloudAuthApi.kt` (Retrofit interface), `app/src/main/java/com/yicamalt/auth/AuthRepository.kt` (facade) |
| **Base URL** | Runtime-configurable, default placeholder `https://api.yicamalt.local/v1/` — see *Environment Configuration* |

**`GET /v4/users/login`** — `YiCloudAuthApi.login()` (`YiCloudAuthApi.kt:62-70`).
Query parameters: `seq` (=1), `account`, `password`, `dev_name`, `dev_type`, `dev_os_version`. Despite the `@GET` verb, a `POST /v4/users/login` with a JSON body also works and returns the same API-level code ladder (`docs/PROJECT_STATUS.md` §14).

- `account` is the correct parameter name. Renaming it to `login`/`email`/`username` yields code `20250` (param unrecognized) — a regression that was introduced and reverted (`AuthRepository.kt:36-37`).
- The password is **never sent in plaintext**. See *Request Signing* below.
- Success is `code = "20000"`. Verified end-to-end on a real device 2026-09-09 (`docs/PROJECT_STATUS.md` §14 status block).

**`POST /v4/users/auth_token`** — `YiCloudAuthApi.refresh()` (`YiCloudAuthApi.kt:72-73`), body `{"refresh_token": "..."}`.

**Observed API code ladder** (`docs/PROJECT_STATUS.md` §14) — the most useful integration knowledge in the repo:

| Request shape | Code | Meaning |
|---|---|---|
| Unrecognized account param name | `20250` | account param not recognized |
| `account`, no password | `20260` | password missing |
| `account` + plaintext password | `20261` | account found, password wrong |
| `account` + password (HMAC) | `20253` | account not found |
| GET with no params | `20203` | nothing sent |
| POST with no body | `-10003` | body required |

Success codes for signed APIs are `{20000, 20200}`; auth-failure codes are `{20201, 20202, 20203, 20205, 20253, 40110}` — duplicated as constants in `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:83-84` and `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:252-253`.

### Yi Cloud — Device List (signing CONFIRMED, payload still missing)

**`GET /v5/devices/list?seq=1&userid=<uid>&hmac=<base64>`** — `app/src/main/java/com/yicamalt/camera/YiCloudDeviceApi.kt:39-45`.

- The request signature is **CONFIRMED on device**: the server returns `20200` (success) for a correctly signed request. `20201` = no signature, `20202` = wrong signature. A valid `Authorization: Bearer` header is **explicitly ignored** (returns `20201`) — bearer auth is used for `/v4/*` but not for `/v5/*`.
- **But the successful response is empty** (16-byte body, `docs/PROJECT_STATUS.md` §14 / 2026-09-10 entry). The real home device list endpoint has *not* been found. Probe results: `/v5/devices/list` → `20200` empty; `/v2/devices/list` → `20230`; `/v5/devices/deviceinfo` → `20240`; `relations`/`owners` → `-10003`; `/v8/cloud/*` → `404`.
- So `CameraRegistry.getCameraList()` is a working, correctly-signed call that has never returned a real camera. Treat "camera list works" as **unproven**.

### Yi Cloud — Camera Control (PROVISIONAL — unverified guess)

**`POST /v8/device/control`** — `app/src/main/java/com/yicamalt/camera/YiCloudCommandApi.kt:30-33`, body `CommandPayload(device_id, command, params)`.

- Acknowledgement is treated as `code == "20000"` (`app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:45-55`). **Neither the path nor the ack semantics have ever been confirmed against a live camera.** The module contract says so at `CameraCommandService.kt:43-44`.
- `docs/development-plan.xml:229` planned `POST /v1/camera/control`; the code uses `/v8/device/control`. Neither is verified. Paths matching `/v8/cloud/*` returned 404 in probing.

### Yi Cloud — Event Timeline & Playback (PROVISIONAL — likely wrong)

`app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt`, paths isolated in `object EventPaths` (`:46-49`):

| Constant | Value | Status |
|---|---|---|
| `EVENT_LIST` | `v1/camera/event/list` | **Guess.** `docs/development-plan.xml:269` specified `/v1/...`, but reverse-engineering showed real Yi Cloud paths are versioned `/v4`, `/v5`, `/vas/v8` with **no** `/v1` prefix. The project's own `docs/PROJECT_STATUS.md` §2b says "the path is probably wrong". |
| `PLAYBACK` | `v1/camera/event/playback` | Same guess. Only the *parsing* path has been tested (`FakeYiApi.kt`), never the endpoint. |

Both signatures follow the confirmed device-API scheme (`EventSigner`, `YiCloudEventApi.kt:80-89`) but **that scheme is not confirmed for the event API** — it is a deliberate copy, documented as debt at `YiCloudEventApi.kt:32-35`.

The positive design choice: all guesses live in `EventPaths`/`EventSigner` constants, so a real capture requires editing constants, not restructuring code.

### Request Signing — three schemes, two of them confirmed

**1. Password digest (CONFIRMED, login only)** — `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:47-48, 176-181`:

```
password_param = Base64(HMAC-SHA256(key = "KXLiUdAsO81ycDyEJAeETC$KklXdz3AC", msg = password_UTF8))
```

Recovered from the official dex: login-request builder `Lva/h` → hasher `Lmc/d2.a`. The key is a **compile-time constant embedded in the client** — a static secret, not a per-device or server-negotiated one. Pinned by a unit test so the wire format cannot drift silently.

**2. Device API signature (CONFIRMED)** — `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:88-92`:

```
hmac = Base64(HMAC-SHA1(key = "<token>&<token_secret>", msg = "seq=1&userid=<uid>"))
```

where `token_secret` is the login response's second token, mapped onto `AuthSession.refreshToken` (`AuthRepository.kt:201`, `REFRESH_KEYS`). The server validates against a fixed `seq`+`userid` set — extra query params do not break the signature.

**3. Debug probe signing** — `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt:51-58`. Same SHA-1 scheme, used to try eight candidate endpoint shapes on a live device. Debug-only, and it logs **only** response codes, lengths, and masked bodies.

> **Duplication debt:** the SHA-1 scheme exists in three independent copies — `CameraRegistry.hmacSha1Base64`, `EventSigner.hmacSha1Base64`, and `DeviceSignProbe.mac`. `EventServiceModule.kt` deliberately avoids depending on `CameraRegistry` to keep M-EVENT free of M-CAMERA-LIST coupling; all three should collapse into `M-HTTP` after a capture confirms the event API.

### Vendor Host Inventory (discovered, NOT all wired)

From `docs/PROJECT_STATUS.md` §8.3. Only one of these is reachable today, and only because the user types it into the settings screen:

| Role | Hosts | Probe result |
|---|---|---|
| Regional gateways | `gw-us.xiaoyi.com`, `gw-eu.xiaoyi.com`, `gw-sg.xiaoyi.com` | 200 (Tomcat). **The real API entry point.** |
| API | `api.xiaoyi.com`, `api.eu.xiaoyi.com` | 200 |
| API (US) | `api.us.xiaoyi.com` | no response |
| OAuth | `api-oauth-us.xiaoyi.com`, `kami-api-oauth-us.xiaoyi.com` | 404 at root; `/oauth2/authorize` and `/oauth2/token` are live (Spring Security OAuth2) |
| Load balancers | `us-lb.`, `eu-lb.`, `sg-lb.`, `test-lb.xiaoyi.com` | 403 |
| Telemetry hosts | `touch-us.`, `touch-eu.`, `touch-sg.xiaoyi.com` | 404 |
| Web/support | `kamiapp.kamihome.com`, `kamicloud.kamihome.com`, `kamicloud-api.kamihome.com`, `app.xiaoyi.com`, `h5.xiaoyi.com`, `log*.xiaoyi.com`, `faq*.xiaoyi.com` | discovered in dex, unused |
| Third-party analytics | `api-sg.mentamob.com` | discovered; `docs/PROJECT_STATUS.md` §8.8 explicitly says to **ignore** |

Path prefixes confirmed to work on the gateways: `/v4/...`, `/v5/...`, `/v8/...`, `/vas/v8/...`. Prefixes `/api/` and `/newapi/` return 404 (`docs/PROJECT_STATUS.md` §9).

### Third-Party Login (DISCOVERED, NOT IMPLEMENTED)

Yi Home supports Google / Facebook / Amazon / Xiaomi sign-in via an OAuth2 authorization-code flow on `api-oauth-us.xiaoyi.com`, with region→server selection in `LoginAreaSelectActivity` / `ServerInfo$ServerLocation` (`docs/PROJECT_STATUS.md` §8.6-8.7).
In the codebase this exists only as an unused enum value: `AuthMethod.CLOUD_OAUTH` in `app/src/main/java/com/yicamalt/auth/AuthSession.kt:26`. No `client_id`, `redirect_uri`, or `grant_type` has been extracted, and `docs/PROJECT_STATUS.md` §10 still lists OAuth flow verification as an open next step.

### Push Notifications (PLANNED ONLY — nothing implemented)

- Specified by `docs/requirements.xml` UC-005 (motion push within 5 s) and module M-PUSH in `docs/development-plan.xml:389-424`.
- **Not implemented.** No `com.google.firebase:firebase-messaging` dependency, no `google-services` plugin, no `FirebaseMessagingService`, no notification channel, no token registration call.
- The only trace in the code is a doc comment at `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:106` ("e.g. from an FCM status push") and the orphaned `POST_NOTIFICATIONS` permission in `app/src/main/AndroidManifest.xml:6`.
- `FeatureFlag.PUSH_FOREGROUND_SERVICE` exists and is hardcoded `true` (`app/src/main/java/com/yicamalt/config/ConfigModule.kt:64`) despite no push code existing — a flag that lies about the app's capabilities.
- M-PUSH depends on M-EVENT, so it is genuinely downstream work, not just unwritten boilerplate.

### Media Playback & Streaming (PLANNED ONLY — nothing implemented)

| Integration | Plan | Reality |
|---|---|---|
| ExoPlayer 2.39 | M-STREAM-LIVE, M-STREAM-DECODE, M-EVENT-PLAYBACK, M-MEDIA | No `ExoPlayer` dependency, no reference in `app/src/` |
| Cloud stream URL | `GET /v1/camera/live/stream` (`development-plan.xml:310`) | Path almost certainly wrong (same `/v1` issue as events) |
| AES stream key handshake | `POST /v1/camera/live/key` (`development-plan.xml:350`) | Not implemented. `docs/development-plan.xml:6` calls M-STREAM-DECODE the single riskiest module |
| Local RTSP | `rtsp://<camera_ip>/live`, same-LAN only (`development-plan.xml:311`) | No RTSP client. Gated by `FeatureFlag.LOCAL_RTSP` ← `BuildConfig.LOCAL_RTSP_ENABLED` (true debug / false release) |
| mDNS/Bonjour discovery | service type `_yi-camera._tcp` (`development-plan.xml:618`) | No `NsdManager` usage. M-CAMERA-DISCOVERY is **deferred** pending a real capture |
| UDP broadcast discovery | port 6090 (`development-plan.xml:619`) | Not implemented. Also deferred |

`CameraRegistry.getLiveStreamUrl()` exists but is a **cache read only** — it returns the `stream_url` column from Room and throws `NoStreamUrl` if blank (`CameraRegistry.kt:115-123`). Nothing fetches a live URL. The log line emits `url=***`, redacting the URL as if it were a secret.

### Wi-Fi Pairing (PLANNED ONLY — deferred)

M-CAMERA-SETUP (`docs/development-plan.xml:624-648`): ez-setup / WPS / cloud-assisted pairing, cloud-mediated credential push. Status `deferred` — blocked on a protocol capture. `docs/requirements.xml` ranks it `low` priority (UC-006).

### Reverse-Engineering Source Artifact

- `_apk/` holds the official app: `Yi+Home_6.9.8_..._APKPure.xapk` and `com.ants360.yicamera.international_6.9.8_..._apkmirror.com.apkm`, with `_apk/extracted/base.apk` plus config splits (10 dex files, ~8768 entries).
- **This directory is NOT in `.gitignore`** (which lists only `build/`, `.gradle/`, `.idea/`, `local.properties`, `*.iml`, `captures/`, `.cxx`). A ~270 MB vendor APK is sitting in the working tree, potentially committed and redistributable. `docs/requirements.xml` `<risk-5>` flags the legal exposure of reverse-engineering; shipping the vendor's own binary compounds it. Recommend adding `_apk/` to `.gitignore` and purging it from history.
- Working notes: `docs/PROJECT_STATUS.md` §8 (methodology, host table, dex string dumps).

## Data Storage

**Databases:**

- **Room / SQLite** — `app/src/main/java/com/yicamalt/database/YiDatabase.kt`, version **1**, `exportSchema = true`, 3 entities, 3 DAOs. Built in `app/src/main/java/com/yicamalt/database/DatabaseModule.kt:42-46` with `fallbackToDestructiveMigration()` — a schema bump silently destroys the offline cache.

| Table | File | Key | Notes |
|---|---|---|---|
| `cameras` | `database/CameraEntity.kt` | `device_id` | name, model, online, `stream_url`, `last_seen`, `cached_at` |
| `events` | `database/EventEntity.kt` | `event_id` | `device_id` + `timestamp`; indices on `device_id` and on `(device_id, timestamp)` for windowed timeline reads. Raw cloud payload preserved in `payload_json` for lossless re-parse |
| `app_meta` | `database/AppMetaEntity.kt` | `key` | generic key/value; **no DAO method currently writes it** — scaffolding only |

- Cache file: `yicamalt.db` (default `/data/data/com.yicamalt/databases/yicamalt.db`).
- **Config gap:** `exportSchema = true` with no `room.schemaLocation` KSP argument anywhere in `app/build.gradle.kts`. Room emits a build warning and places schemas outside the conventional `app/schemas` location, so migration history is not being version-controlled. Add `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
- Cache-fill invariant worth preserving: an empty page from the cloud must **not** wipe the cache. Enforced at `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:121-122` and asserted by V-M-EVENT scenario_4/4b/4c.

**Key-value / secret storage:**

- **EncryptedSharedPreferences** — `app/src/main/java/com/yicamalt/auth/AuthStore.kt`. Pref file `yicamalt_auth`; `MasterKey` with `AES256_GCM`; key scheme `AES256_SIV`; value scheme `AES256_GCM`; backed by Android Keystore. Stored keys: `access_token`, `refresh_token`, `expires_at`, `user_id`, `auth_method`.
  - Uses the **alpha** `security-crypto:1.1.0-alpha06`. This artifact is deprecated in newer AndroidX; plan a migration to plain Keystore + `Cipher` or DataStore.
- **Plain SharedPreferences** — pref file `yicamalt_settings`, key `api_base_url` (`app/src/main/java/com/yicamalt/config/SettingsStore.kt:41`). Not sensitive.
- **No DataStore, no ProtoBuf, no SQLCipher.** The Room cache is plaintext on disk (device ids, event metadata, stream URLs). `docs/requirements.xml` `<constraint-2>` only mandates Keystore for tokens, so this is compliant as written, but the cache is a fingerprint of the user's household.

**Caching / eviction:** none. No LRU, no TTL, no size bound. `getLocalEventCount` exists to advertise offline availability but nothing prunes.

**File logs (debug builds only):** `YiCamAltApp.kt:46-51` plants two `FileLogTree` instances writing `auth_log.txt` to internal `filesDir` **and** to `getExternalFilesDir(null)` (`/sdcard/Android/data/com.yicamalt/files/`), so logs can be pulled over USB MTP without root.

## Authentication & Identity

**Provider:** custom, direct-to-vendor. There is no third-party identity SDK and no OAuth client in the app.

- Session model: `app/src/main/java/com/yicamalt/auth/AuthSession.kt` — `accessToken`, `refreshToken`, `expiresAt`, `userId`, `authMethod`.
- Token extraction is **shape-tolerant by design**: the success envelope of `/v4/users/login` is not fully confirmed (code `20000` with a 407-byte body was seen; error bodies are 16 bytes), so `LoginResponse.data` is kept as a raw `JsonElement` and `ResponseTokens` walks it to depth 4, trying alternate key spellings and re-parsing JSON-encoded strings (`app/src/main/java/com/yicamalt/auth/AuthRepository.kt:198-262`).
- `token_secret` is mapped to `refreshToken` — confirmed working 2026-09-09 (`AuthRepository.kt:201`).
- `DEFAULT_EXPIRES_SECONDS = 24h` because the login response carries **no** `expires_in`; treating the token as immediately expired previously caused a refresh loop. Real expiry is still caught by the 401 path (`AuthRepository.kt:51-55`).

**Token attachment and refresh:**

- `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:82-93` — `authInterceptor` adds `Authorization: Bearer <token>` to every request.
- **Recursion guard:** the interceptor bypasses token lookup for any path containing `auth_token` (line 87). Without it, `getAccessTokenBlocking()` → refresh → same OkHttp dispatcher deadlocks; this manifested as an infinite login spinner and is covered by a regression test that exercises the *production* `HttpClientModule` (`AuthRepository.kt:26-31`).
- `HttpClientModule.kt:115-126` — `Authenticator` performs a single-flight 401 refresh-and-retry, guarded by an `AtomicBoolean refreshGuard`.
- Coroutine bridging: the synchronous interceptor and authenticator call suspend functions through `runBlocking` (`HttpClientModule.kt:186-191`). Correct but a known contention risk.

**Identity:** `userId` is passed as a query parameter (`userid`) and folded into the HMAC canonical string. There is no per-device identity, no device attestation, and no `client_id` on the main API — the HMAC key is a static constant shared by every installation of the official app.

## Monitoring & Observability

**Error tracking:** none. No Crashlytics, Sentry, or Bugsnag. Crashes are silent in release.

**Logging:** Timber 5.0.1 only.

- Debug (`BuildConfig.DEBUG`) plants a `DebugTree` plus two `FileLogTree`s. **Release plants no tree at all** — release logging is a no-op by construction, which also means no production diagnostics exist.
- Format is mandatory: `[Module][function][BLOCK_NAME] message` (`docs/technology.xml:74`). These `BLOCK_` tokens are asserted by `app/src/test/java/com/yicamalt/test/TraceRecorder.kt`, so renaming one breaks verification.
- **Redaction is a hard gate**, enforced by `app/src/test/java/com/yicamalt/test/RedactionScanner.kt`:
  - URL query values `password=` and `account=` are replaced with `██` (U+2588) before logging (`HttpClientModule.kt:95-111`). This was a **live incident**: the plaintext password leaked into `auth_log.txt` because `HttpLoggingInterceptor` logged the full login URL. Fixed in v0.3.2; the user was advised to rotate their Yi password and delete the old log file (`docs/PROJECT_STATUS.md` §14).
  - Response bodies for `/login`, `/auth_token`, `/devices`, `/device` are peeked (≤4096 bytes) with token/password/secret-valued fields masked to `██`, field *names* left readable for envelope diagnosis (`HttpClientModule.kt:133-145`).
  - `Authorization`, `Set-Cookie`, `Cookie` headers are redacted via OkHttp's `redactHeader`.
  - URL userinfo is stripped in config logs (`ConfigModule.kt:103-104`).
- **Open redaction defect:** `CameraDao.insertCamera` still logs `device_id` in `BLOCK_DB_WRITE`. Yi serials are 10+ digit runs, which trips the scanner's long-digit-run rule. The identical defect in `EventDao.insertEvent` was found and fixed during M-EVENT (`docs/PROJECT_STATUS.md` §2b); the camera-side one is still open.
- `RedactionScanner` only flags secret words in `key=value` assignment form, and treats `<redacted>` and `██` as allowed.

**Analytics:** none in the app. MentaMob was seen in the vendor dex and is explicitly out of scope.

## CI/CD & Deployment

**Hosting:** distributed as an APK. No backend, no server component, no admin surface — the client is the whole product.

**CI:** **absent.** There is no `.github/` directory, no CI config of any kind. `docs/technology.xml:39` names "GitHub Actions / Gradle CI" as intended tooling — it is aspirational.

- The practical gap: `docs/PROJECT_STATUS.md` §2b and the `tools/gradle.ps1` header document that a fresh Gradle daemon inherits the caller's stdout pipe, so naive CI invocation hangs after a successful build. The `tools/gradle.ps1` redirect wrapper is the fix and would be the mandatory CI entry point.
- The hardcoded truststore path in `gradle.properties:7-9` and `app/build.gradle.kts:127-131` must be parameterized before any CI runner can build this.
- The AppLocker workarounds documented in `docs/PROJECT_STATUS.md` §5 are Windows-specific and would not apply on a Linux CI runner.

**Verification commands** (from `docs/technology.xml` §Testing, all via the wrapper):

```powershell
.\tools\gradle.ps1 :app:testDebugUnitTest
.\tools\gradle.ps1 :app:testDebugUnitTest --tests "*CameraModule*"
.\tools\gradle.ps1 :app:connectedDebugAndroidTest
```

Current state: 91 unit tests / 0 failures (2026-09-28). Instrumented `connectedDebugAndroidTest` has never been run — no emulator in the environment, and Evidence-3 is explicitly deferred.

**Release:** `isMinifyEnabled = true`, `proguard-android-optimize.txt` + `app/proguard-rules.pro`. No signing config, no `google-services.json`, no `firebase` wiring, no `versionCode` bump automation.

## Environment Configuration

**Build-time (`app/build.gradle.kts:27, 28, 33, 34`):**

- `YI_API_BASE_URL` — placeholder `https://api.yicamalt.local/v1/`, a non-resolving host. Overridden at runtime.
- `LOCAL_RTSP_ENABLED` — `true` (debug) / `false` (release).

**Runtime:** the only real setting is the API base URL override, stored under key `api_base_url` in `yicamalt_settings` and edited on the Settings screen (gear icon on the login screen). `HttpClientModule.rebuild()` recreates Retrofit when it changes.

> **Open defect — settings change requires an app restart.** `YiCloudAuthApi` and `YiCloudDeviceApi` are `@Singleton`s created from the *old* Retrofit instance, so `rebuild()` never reaches them. A newly entered endpoint appears to have no effect until the process is killed (`docs/PROJECT_STATUS.md` §14). Any integration testing workflow depends on this being understood.

**Secrets:** none. There is no `.env`, no `secrets.xml`, no `local.properties` secret entries, and `local.properties` (which holds only the SDK path) is gitignored. The one credential-shaped value in the repo is the static HMAC key `AUTH_HMAC_KEY` in `AuthRepository.kt:48` — it is a *reverse-engineered constant from the vendor's published APK*, not a private key, but it must stay `internal` and must never be logged.

**No env-var indirection anywhere:** the TLS truststore path and the Axio JDK path are absolute literals in `gradle.properties` and `app/build.gradle.kts`.

## Webhooks & Callbacks

**Incoming:** none implemented. No webhook receiver, no deep-link intent filter beyond the launcher, no notification receiver. The `POST_NOTIFICATIONS` permission is declared with no consumer.

**Outgoing:** not applicable — the app has no server counterpart. The closest analogue is `EventServiceModule.syncEventsToDb()` (`:197-200`), explicitly made public so a future push-driven sync can reuse the cache-write path; today it is called only by the paginated fetch.

---

*Integration audit: 2026-09-28*
