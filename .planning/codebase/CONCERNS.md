---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
# Codebase Concerns

**Analysis Date:** 2026-09-28

Scope: full repository (`E:/Dev/YiCamAlt`). 57 Kotlin source files + 14 unit-test files, 42 tracked `_apk/` binaries, `docs/*.xml` governance artifacts.

**How to read this document.** Every concern is tagged:

- **[CONFIRMED]** — verified by reading the actual code in this pass. File + line given.
- **[DOCUMENTED]** — asserted by `docs/PROJECT_STATUS.md` or `docs/*.xml`, not independently re-derivable from code (needs a device, an emulator, or a live capture).
- **[NEW]** — not present in the supplied concern list or in `docs/PROJECT_STATUS.md`; surfaced by this analysis.

---

## Tech Debt

### Unconfirmed vendor endpoints and duplicated request signing

**Status: [CONFIRMED] paths/signer are code-visible · [DOCUMENTED] they are wrong-or-unproven against the live API**

- Files: `app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt:46-49` (`EventPaths`), `app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt:80-90` (`EventSigner`)
- Contract that admits the guess: `YiCloudEventApi.kt:28-36` (`START_CHANGE_SUMMARY`) states outright that `v1/camera/event/list` is a guess and that `EventSigner` is deliberate debt
- Also unconfirmed, same class of debt: `app/src/main/java/com/yicamalt/camera/YiCloudCommandApi.kt:31` (`POST v8/device/control`) and `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:43-44`. `v8/device/control` does **not** appear anywhere in the dex path inventory in `docs/PROJECT_STATUS.md:228-248` — this endpoint was never observed, only invented. `CommandTransportRetrofit` treats `code == "20000"` as acknowledgement, so an unconfirmed path plus an unconfirmed success code means M-CAMERA-CMD cannot fail correctly.
- Impact: M-EVENT and M-CAMERA-CMD are wired end to end against endpoints that have never answered. A green 91/91 suite proves the plumbing, not the protocol.
- Fix approach: the isolation is already correct — `EventPaths` (`YiCloudEventApi.kt:45`) is the single edit point. Add a `CameraCommandPaths` object mirroring it so `YiCloudCommandApi.kt:31` is also a constant, and make `CommandTransportRetrofit` (`CameraCommandService.kt:46-54`) treat *any* unrecognised `code` as failure rather than only `20000` as success.

### HMAC-SHA1 signing scheme duplicated between M-CAMERA-LIST and M-EVENT

**Status: [CONFIRMED]**

- Copy A: `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:87-92` — `internal fun hmacSha1Base64(key, msg)`
- Copy B: `app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt:81-86` — `internal fun hmacSha1Base64(key, msg)`
- The two are byte-for-byte equivalent (`Mac.getInstance("HmacSHA1")` → `SecretKeySpec` → `Base64.getEncoder()`); neither calls the other.
- A **third** copy of the same algorithm exists at `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt:51-55` (local `fun mac(key, msg)`).
- The canonical signed string is also duplicated: `CameraRegistry.kt:63` builds `"seq=1&userid=" + s.userId` inline; `EventServiceModule.kt:93` calls `EventSigner.canonicalMessage`; `DeviceSignProbe.kt:57` builds `"seq=1&userid=$uid"` inline.
- Impact: if a live capture changes the canonical string (e.g. key ordering, or a `token_secret`-derived key), three sites must be found by hand. `docs/knowledge-graph.xml:120` and `docs/verification-plan.xml:352` both already carry the "collapse into M-HTTP" instruction — it is recorded but not done.
- Fix approach: one `internal object YiRequestSigner` in the `network` package owning `hmacSha1Base64` + `canonicalMessage`, and delete copies B and C. Tests pin the wire format, so this is a pure move.

### Stale MODULE_CONTRACT / MODULE_MAP text after path corrections

**Status: [CONFIRMED]**

| File | Line | Stale text | Actual code |
|---|---|---|---|
| `app/src/main/java/com/yicamalt/camera/YiCloudDeviceApi.kt` | 5-6 | `deviceList() hits /v8/cloud/deviceList` | `v5/devices/list` (line 40) |
| `app/src/main/java/com/yicamalt/camera/CameraInfo.kt` | 4, 25 | `/v8/cloud/deviceList` | `v5/devices/list` |
| `docs/knowledge-graph.xml` | 83 | `YiCloudDeviceApi GET v8/cloud/deviceList` | corrected in commit `1afa9ea` |
| `app/src/test/java/com/yicamalt/test/FakeYiApi.kt` | 53, 56 | `/v1/account/login` shape | `YiCloudAuthApi` is `v4/users/login` + `v4/users/auth_token` |
| `app/src/test/java/com/yicamalt/test/TestInfrastructureSmokeTest.kt` | 49, 51 | stubs `/v1/account/login` | path no longer exists in production |

- AGENTS.md Core Principle 3 makes `docs/knowledge-graph.xml` the navigation source of truth. A wrong path in it misroutes the next agent.
- Fix approach: correct the five sites; add a grep recipe to `AGENTS.md` that cross-checks every `v[0-9]/` literal in a `MODULE_CONTRACT` against the Retrofit annotation in the same file.

### Orphaned test scaffolding providing false confidence

**Status: [CONFIRMED]**

- `app/src/test/java/com/yicamalt/test/FakeYiApi.kt` — `respond(path, body)` is a plain suspend function. It implements no Retrofit interface, is not stubbed into any `MockWebServer`, and no test other than the smoke test touches it. It models an API shape (`code: 200` numeric, `code: 401`) that the real client never produces (Yi returns `code` as a **string**, e.g. `"20000"`, `"20201"`).
- `app/src/test/java/com/yicamalt/test/FakeAuthStore.kt` — exposes `putString/getString` keyed on `access_token`/`refresh_token`. It does **not** implement `com.yicamalt.auth.AuthStore` (`app/src/main/java/com/yicamalt/auth/AuthStore.kt:30-34`), which is `putSession/getSession/clear`. It cannot be substituted anywhere; `AuthRepositoryTest` therefore tests against a different fake.
- `app/src/test/java/com/yicamalt/test/FakeCameraStreamServer.kt` + `StreamHandleFake.kt` — hardcode `18 fps` and a `CONNECTING → PLAYING` state machine for M-STREAM, which does not exist yet.
- Impact: `TestInfrastructureSmokeTest` is green, so `V-M-SCAFFOLD` evidence-1 passes, while four of the six harnesses it certifies are unused. The gate measures compilation, not usefulness.
- Fix approach: either implement the production interfaces (`FakeAuthStore : AuthStore`) or delete the four files and shrink the smoke test to `TraceRecorder` + `RedactionScanner`, which are genuinely used.

### Device-list endpoint returns success with an empty device list

**Status: [DOCUMENTED] (needs a device to reproduce)**

- Evidence: `docs/PROJECT_STATUS.md:401-405` — `/v5/devices/list` returns code `20200` with a 16-byte (empty) body after 5 probe rounds; `/v2/devices/list` → `20230`, `/v5/devices/deviceinfo` → `20240`, `relations`/`owners` → `-10003`, `/v8/cloud/*` → 404. Git history confirms the 5 rounds: `fd9d19a`, `686a961`, `ad4806e`, `1afa6ca`, `fad7d4c`.
- Consequence in code, [CONFIRMED]: `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:73-78` treats `20200` as success, parses zero devices, and `cacheCameraList` at line 100-104 early-returns on an empty list — so the UI renders `Камеры не найдены` (`app/src/main/java/com/yicamalt/ui/cameras/CameraListScreen.kt:57`) with no error and no diagnostic. A successful-signature-but-empty-device-list is indistinguishable from "user has no cameras" in the shipped UI.
- Governance drift, [CONFIRMED]: `docs/knowledge-graph.xml:71` records `M-CAMERA-LIST STATUS="implemented-provisional"` and `docs/verification-plan.xml:247` records `V-M-CAMERA-LIST STATUS="passed"` — but the only record of the empty-list blocker lives in `docs/PROJECT_STATUS.md:401-405`, outside the canonical artifacts. The verification gate is green because every scenario runs against `MockWebServer` fixtures (`CameraRegistryModuleTest.kt:88`), never the real envelope.
- Fix approach: add a `NoDevices` state distinct from `Content(emptyList())` in `CameraListUiState` (`CameraListViewModel.kt:30-34`) so an empty 20200 is surfaced as a diagnostic rather than as a legitimate empty list; move the blocker into `docs/development-plan.xml` and flip `V-M-CAMERA-LIST` to `blocked`.

### Base-URL rebuild does not reach the already-created API interfaces

**Status: [CONFIRMED] — and broader than documented**

- Root cause: `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:158` holds `private var retrofit`, rebuilt at line 174-177. But `NetworkModule.provideRetrofit` (`HttpClientModule.kt:60-62`) is `@Singleton`, so every `retrofit.create(...)` provider captures the *first* instance:
  - `app/src/main/java/com/yicamalt/auth/AuthModule.kt:51-54` — `YiCloudAuthApi`
  - `app/src/main/java/com/yicamalt/camera/CameraModule.kt:38-41` — `YiCloudDeviceApi`
  - `app/src/main/java/com/yicamalt/camera/CameraModule.kt:43-46` — `YiCloudCommandApi`
  - `app/src/main/java/com/yicamalt/event/EventModule.kt:38-41` — `YiCloudEventApi`
- So **four** interfaces are stale after a base-URL change, not one. `docs/PROJECT_STATUS.md:360-361` names only `YiCloudAuthApi`.
- Aggravating: `app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt:46-52` (`saveBaseUrl`) reports success and updates the displayed URL, so the user is told the change took effect when only `HttpClientModule.createRetrofit()` callers would see it. No test covers this — `HttpClientModuleTest` has no `rebuild()` scenario at all.
- Fix approach: make the Retrofit providers non-singleton and depend on `HttpClientModule` directly (`fun provideYiCloudAuthApi(client: HttpClientModule) = client.createRetrofit().create(...)`) so each injection point re-resolves after a rebuild; or terminate the process on base-URL change and tell the user to restart.

---

## Known Bugs

### `refreshGuard` latches `true` forever if the refresh call throws

**Status: [NEW]**

- File: `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:113-126`
- ```kotlin
  if (!refreshGuard.compareAndSet(false, true)) return@Authenticator null
  val refreshed = authProvider.get().refreshBlocking()   // can throw
  refreshGuard.set(false)                              // never reached on throw
  ```
- `refreshBlocking()` is `kotlinx.coroutines.runBlocking { refresh() }` (`HttpClientModule.kt:190-191`). Any throw from `AuthRepository.doRefresh()` — including the `catch (t: Throwable)` path being exceeded, or a `CancellationException`, or an OOM — leaves `refreshGuard == true` permanently. After that single failure the app **never** attempts another 401 refresh for the rest of the process lifetime; every expired session degrades to a hard 401.
- Fix approach: wrap in `try { ... } finally { refreshGuard.set(false) }`.

### `androidTest` source set cannot compile

**Status: [NEW]**

- `app/src/androidTest/java/com/yicamalt/ui/login/LoginScreenTest.kt:21` imports `org.robolectric.RobolectricTestRunner` and line 24 uses `@RunWith(RobolectricTestRunner::class)`.
- Robolectric is declared **only** as `testImplementation("org.robolectric:robolectric:4.11.1")` at `app/build.gradle.kts:108`. The `androidTestImplementation` block (`app/build.gradle.kts:115-120`) contains `androidx.test.ext:junit`, `espresso-core`, `compose ui-test-junit4`, `mockk-android` — no Robolectric.
- Consequence: `:app:assembleDebugAndroidTest` and `:app:connectedAndroidTest` fail at compile time. `:app:testDebugUnitTest` does not compile `androidTest`, which is why the reported 91/91 green does not surface it.
- Second defect in the same file: `LoginScreenTest.kt:33` calls `LoginScreen(onLoggedIn = ...)` without a Hilt entry point, but `LoginScreen.kt:51` defaults `viewModel: LoginViewModel = hiltViewModel()`. Even once it compiles, `hiltViewModel()` throws in this test. The file's own trailing comment (`LoginScreenTest.kt:46-49`) acknowledges the test was never executed.
- Fix approach: either move the file to `src/test` and add `testImplementation("org.robolectric:robolectric")` (already present) plus a `HiltTestApplication` rule, or convert it to a real instrumented test with `@HiltAndroidTest` and drop the Robolectric runner.

### Cancel-safe error handling is broken by a broad `catch (Throwable)`

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/ui/cameras/CameraListViewModel.kt:59-61` — `catch (e: Throwable) { _state.value = CameraListUiState.Error(...) }`
- The same shape at `app/src/main/java/com/yicamalt/ui/login/LoginViewModel.kt:71-76`.
- `CancellationException` is a `Throwable`. When the `viewModelScope` is cancelled (screen popped, config change), the catch swallows the cancellation and writes an `Error` state into a scope that is already dead — the classic symptom being a one-frame "Не удалось загрузить камеры" flash on navigation.
- Fix approach: add `catch (e: CancellationException) { throw e }` before the broad catch in both view-models.

### Settings tab's back button is a no-op

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt:88` — `ShellTab.Settings -> com.yicamalt.ui.settings.SettingsScreen(onBack = {})`
- `SettingsScreen.kt:62-65` always renders an `IconButton` with `Icons.AutoMirrored.Filled.ArrowBack` wired to `onBack`. Inside the shell tab that callback is an empty lambda, so the arrow renders, is tappable, and does nothing. Users tap it repeatedly.
- Fix approach: pass `onBack = { selected = 0 }` (switch to the Cameras tab) or hide the navigation icon when the callback is a no-op.

### `saveBaseUrl` can crash the app instead of showing an error

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/config/ConfigModule.kt:85-87` (`setApiBaseUrl`) only trims and blanks-checks. There is no scheme check and no trailing-slash normalisation.
- `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:160-164` passes the raw string to `Retrofit.Builder().baseUrl(...)`, which throws `IllegalArgumentException("baseUrl must end in /")` for any URL with a non-empty path segment — e.g. a user typing `https://gw-us.xiaoyi.com/v1` (no trailing slash) inside the `OutlinedTextField` at `SettingsScreen.kt:79-85`.
- `HttpClientModule` is a `@Singleton` built during Hilt graph construction, so the throw happens inside `SettingsViewModel.saveBaseUrl` (`SettingsViewModel.kt:49`) and propagates out of the click handler with no try/catch anywhere in the UI layer. The documented happy path in `docs/PROJECT_STATUS.md:370-377` (enter `https://gw-us.xiaoyi.com`, no path) happens to parse, which is why this has not surfaced.
- Compounding: `targetSdk = 34` (`app/build.gradle.kts:17`) blocks cleartext by default and there is no `android:usesCleartextTraffic` or network-security-config in `app/src/main/AndroidManifest.xml`, so an `http://` override fails silently with no diagnostic.
- Fix approach: validate in `setApiBaseUrl` — reject non-`http(s)` schemes, append a trailing `/` when the path is non-empty, and surface a `UiState.Error` from `SettingsViewModel` instead of letting the exception escape.

### `runBlocking` on the OkHttp dispatcher thread

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:186-191` — both `getAccessTokenBlocking()` and `refreshBlocking()` are `runBlocking`.
- `authInterceptor` (`HttpClientModule.kt:82-93`) calls `getAccessTokenBlocking()` on the OkHttp worker thread for **every** request. `AuthRepository.getAccessToken()` (`app/src/main/java/com/yicamalt/auth/AuthRepository.kt:142-149`) checks expiry and, when expired, calls `doRefresh()` → `api.refresh(...)` — i.e. a nested network call issued from inside an interceptor, on the same `OkHttpClient`.
- The v0.4.1 change recorded at `AuthRepository.kt:25-31` fixed the *recursion* (bypassing the interceptor for `auth_token` paths, line 87) but not the thread-blocking. A refresh occupying a dispatcher worker while `runBlocking` parks another is the same starvation class as the spinner deadlock that fix addressed; it is now merely less likely.
- Fix approach: pre-resolve the token and stash it via `setAuthToken` (`HttpClientModule.kt:183`) so the interceptor reads `currentToken.get()` without ever calling `runBlocking`; reserve `runBlocking` for the `Authenticator` path only, or move refresh to a dedicated OkHttp client.

### Swallowed database write failures in the event sync

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:211-224` — each insert is wrapped in `runCatching { ... }.isSuccess` and the failures are folded into a counter:
  ```kotlin
  val written = events.count { event -> runCatching { eventDao.insertEvent(...) }.isSuccess }
  Timber.d("[Event][syncToDb][BLOCK_SYNC_TO_DB] written=$written of=${events.size}")
  ```
- A `SQLiteConstraintException`, a disk-full condition, or a corrupted payload is reported as a debug-level count and nothing else. `getPlaybackUrl` at line 191 does the same thing without even a counter — a failed playback-URL cache write is invisible.
- Fix approach: collect the throwables and log at `WARN` with the failure class; the redaction scanner will still pass because the message carries no payload.

### `cacheCameraList` is a non-atomic delete-then-insert

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:100-104`:
  ```kotlin
  if (cameras.isEmpty()) return
  cameraDao.deleteAll()
  cameras.forEach { cameraDao.insertCamera(it.toEntity(System.currentTimeMillis())) }
  ```
- `CameraDao` (`app/src/main/java/com/yicamalt/database/CameraDao.kt:22-45`) declares no `@Transaction` wrapper, and `CameraRegistry` is `@Singleton` with no mutex. A process kill, a low-memory kill of the app mid-loop, or two concurrent `getCameraList()` calls (e.g. `CameraListViewModel.kt:44-46` `init { load() }` racing a manual retry at line 53) leaves the camera cache empty or half-written. `getCameraById` (line 95-97) and `getLiveStreamUrl` (line 115-123) both read only from this cache, so a partial write silently degrades the whole app to `CAMERA_NOT_FOUND` / `CAMERA_NO_STREAM_URL`.
- The `if (cameras.isEmpty()) return` guard prevents the *empty-response* wipe, but not the *interrupted-write* wipe. `EventServiceModule.kt:122` has the analogous guard and the same exposure in `syncToDb`.
- Fix approach: add a `@Transaction` DAO method `replaceAll(List<CameraEntity>)`, and guard the facade with a `Mutex`.

### Room schema export is declared but not wired

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/database/YiDatabase.kt:23` sets `exportSchema = true`.
- `app/build.gradle.kts` has **no** `ksp { arg("room.schemaLocation", ...) }` block — the KSP args at lines 5, 78, 84 only add processors.
- Room therefore cannot emit `schemas/`, emits a compiler warning, and the only schema record is a hand-kept `version = 1` constant. `YiDatabaseTest.kt:91-97` asserts only that `version == 1` — it cannot detect a schema change.
- The migration story is a silent one: `app/src/main/java/com/yicamalt/database/DatabaseModule.kt:45` calls `.fallbackToDestructiveMigration()`, so the first `version = 2` bump **wipes the entire camera and event cache on upgrade** with no user-visible warning. For an app whose stated purpose includes offline event browsing (`EventServiceModule.kt:147-156`), that is a data-loss bug waiting for the first schema edit.
- Fix approach: add the `room.schemaLocation` KSP argument, commit `app/schemas/`, and replace `fallbackToDestructiveMigration()` with explicit `Migration` objects once a v2 lands.

### Redundant single-column index

**Status: [NEW] (minor)**

- `app/src/main/java/com/yicamalt/database/EventEntity.kt:23-26` declares both `Index(value = ["device_id"])` and `Index(value = ["device_id", "timestamp"])`. The first is a strict prefix of the second and is served by it; it costs write amplification and storage on every `insertEvent` for no query benefit.
- Fix approach: drop `Index(value = ["device_id"])`.

### `getLiveStreamUrl` returns a server-supplied URL with no validation or expiry

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:115-123` reads `cam.streamUrl` from the Room cache and returns it if non-blank. There is no scheme check, no freshness check against `last_seen`, and no re-resolution path.
- The URL is persisted in `CameraEntity.stream_url` at write time, so a cached URL can be arbitrarily stale by the time M-STREAM-LIVE consumes it. `CameraEntity.last_seen` (`app/src/main/java/com/yicamalt/database/CameraEntity.kt:27`) exists but is never consulted.
- Fix approach: gate on `last_seen` freshness and re-resolve on `NoStreamUrl` before the stream handshake starts.

### Semantically wrong error for a missing camera row

**Status: [NEW] (minor)**

- `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:83` — `cameraDao.getById(deviceId) ?: throw CameraCommandError.UnsupportedModel`
- A device absent from the cache is reported to the user as "command not supported by this camera" (`CameraCommandService.kt:31`). `CameraError.NotFound` (`CameraRegistry.kt:41`) already models the real condition and is never used from this path.
- Fix approach: add a `CameraCommandError.NotFound` and throw that.

---

## Security Considerations

### Response-body logger does not mask tokens in the JSON-encoded-`data` envelope

**Status: [NEW] — HIGH — verified by executing the exact regex from source**

- File: `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:133-145`
- The masker at line 141 is:
  ```kotlin
  Regex("(\"[^\"]*(?:token|password|secret|authorization)[^\"]*\"\\s*:\\s*\")([^\"]*)(\")", IGNORE_CASE)
  ```
  applied to `response.peekBody(4096L).string()`, then `.take(600)`, then `TimberLog.d(...)`.
- The escape hatch at `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:241-249` explicitly supports a JSON-encoded `data` string — `{"data":"{\"access_token\":...}"}` — and `ResponseTokens.walk` parses it. The regex cannot mask that shape, because the escaped inner quotes (`\"`) break the `key"\s*:\s*"` match, and the outer key `"data"` contains none of the trigger words.
- Executed against the production regex:
  ```
  INPUT : {"code":"20000","data":"{\"access_token\":\"SEKRET_TOKEN_VALUE\",\"token_secret\":\"SEKRET_SECRET\"}"}
  OUTPUT: {"code":"20000","data":"{\"access_token\":\"SEKRET_TOKEN_VALUE\",\"token_secret\":\"SEKRET_SECRET\"}"}
  token leaked: True | secret leaked: True
  ```
  For contrast, the flat shape masks correctly: `{"code":"20000","data":{"access_token":"<masked>","token_secret":"<masked>"}}`.
- Impact: when the server uses the encoded-string envelope — a shape the client explicitly handles and therefore a shape the vendor may well use — the raw `access_token` and `token_secret` are written to `auth_log.txt`. `app/src/main/java/com/yicamalt/YiCamAltApp.kt:46-51` plants that file into **both** internal storage and `getExternalFilesDir(null)`, which is readable over USB MTP with no root. This is the same leak class as the v0.3.2 incident recorded at `docs/PROJECT_STATUS.md:352` (plaintext password → `auth_log.txt` → chat), and it recurs because the fix was a query-param masker, not a body masker.
- Current mitigation: `if (BuildConfig.DEBUG)` at `YiCamAltApp.kt:40` — release builds plant no tree, so nothing is written. The exposure is debug-device only, which is exactly where reverse-engineering evidence is collected.
- Recommendation: parse the body with `Json.parseToJsonElement` and walk it, masking by **key name** at any depth, rather than regexing raw text. Delete the raw body from the log entirely once the key names are known to be stable — `YiCloudAuthApi.kt:5-8` already documents that the envelope shape is the only thing needed from these logs.

### `hmac` query parameter is logged verbatim and is a credential

**Status: [NEW] — HIGH**

- `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:95-111` — `redactedLogger` runs at `HttpLoggingInterceptor.Level.HEADERS` (line 107), which logs the full request line including the query string. Its two mask regexes (lines 101-102) cover only `password=` and `account=`.
- Every device and event request carries `hmac=` in the query: `YiCloudDeviceApi.kt:43`, `YiCloudEventApi.kt:57`, `YiCloudEventApi.kt:69`, and the raw probe URLs at `DeviceSignProbe.kt:73`.
- `hmac` is `Base64(HMAC-SHA1(key="<token>&<token_secret>", msg="seq=1&userid=<uid>"))` (`CameraRegistry.kt:62-64`, `CameraRegistry.kt:87-92`). Per `docs/PROJECT_STATUS.md:402`, a valid hmac is what the server accepts in place of a Bearer token — **it is a credential, not a signature of one**, and it is replayable for the signed parameter set until the underlying tokens rotate.
- `CameraRegistry.kt:120` masks the *stream* URL (`url=***`) but nothing masks the hmac, so the one field that matters most is the one left in the clear.
- The `responseBodyLogger` mask (`HttpClientModule.kt:141`) has the same gap: `hmac` is not in its `(?:token|password|secret|authorization)` trigger set, so an echoed hmac in a body is also unmasked.
- Current mitigation: none. `RedactionScanner.kt:26` (`\b\d{10,}\b`) would not catch a Base64 hmac, and `RedactionScanner.kt:28` requires a key from the fixed list `password|authorization|secret|aeskey|aes_key|accesstoken|refreshtoken` — `hmac` is not in it.
- Recommendation: add `hmac` to every masker in `HttpClientModule.kt`, add it to the `RedactionScanner` key list, and add `RedactionScanner` coverage that asserts no Base64 hmac appears in the `BLOCK_PROBE` / `BLOCK_FETCH_CAMERA_LIST` buffers.

### `CameraDao.insertCamera` logs the raw `device_id`

**Status: [CONFIRMED] — the documented defect, confirmed at source**

- `app/src/main/java/com/yicamalt/database/CameraDao.kt:42`
  ```kotlin
  DbLog.d("[DB][cache][BLOCK_DB_WRITE] camera=${entity.device_id} online=${entity.online}")
  ```
- The fixed counterpart is `app/src/main/java/com/yicamalt/database/EventDao.kt:58-67`, whose `START_CHANGE_SUMMARY` (line 23-28) records exactly this defect class and why: real Yi serials are 10+ digit runs and trip `RedactionScanner.kt:26`. `docs/PROJECT_STATUS.md:60-64` flags `CameraDao` as "the same class of defect, not yet fixed, outside the M-EVENT perimeter".
- Impact: the raw serial lands in `auth_log.txt` in external MTP-readable storage (see `YiCamAltApp.kt:46-51`).
- Fix approach: mirror `EventDao.kt:62-65` — log `online=` and a field-presence boolean, never the identifier.

### Four *additional* raw-identifier log sites, none of them previously recorded

**Status: [NEW]**

| File:line | Emits | Notes |
|---|---|---|
| `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:120` | `device=$deviceId` in `BLOCK_RESOLVE_STREAM_URL` | same file that fixed nothing; `url=***` is masked but the serial next to it is not |
| `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:82` | `device=$deviceId cmd=...` in `BLOCK_VALIDATE_COMMAND` | |
| `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:91` | `device=$deviceId cmd=... pan=... tilt=...` in `BLOCK_SEND_COMMAND` | |
| `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt:48` | `uid=$uid len=${token.length}/${secret.length}` in `BLOCK_PROBE` | `uid` is 6 digits in the confirmed case (167315) so it slips past `\b\d{10,}\b`, but it is a user identifier |
| `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt:53` | `user=$userId` in `BLOCK_RENDER_SHELL` | also a compose-purity defect — see Fragile Areas |

- Together with `CameraDao.kt:42` that is **six** live sites, not one. `docs/PROJECT_STATUS.md:63-64` names only `CameraDao`.
- Also in the same family, [CONFIRMED]: `app/src/main/java/com/yicamalt/ui/cameras/CameraListScreen.kt:88` renders `camera.deviceId` into the visible row subtitle, so any screenshot, screen recording, or `adb shell screencap` of the camera list exfiltrates serials without touching the log pipeline at all.
- Fix approach: introduce one `internal fun String.maskDeviceId(): String` in the `database` package (a 4-char hash is enough for correlation without disclosure) and route all six sites through it; drop `deviceId` from `CameraListScreen`.

### Why the redaction gate cannot catch this class of leak

**Status: [NEW] — root cause of the `CameraDao` defect surviving**

- `app/src/test/java/com/yicamalt/test/RedactionScanner.kt:26` — `Regex("\\b\\d{10,}\\b")`, described at line 26 as "long digit runs (tokens / serials)".
- Every redaction scenario feeds it **short, non-numeric** fixtures:
  - `app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt:45-49` — `device_id = "cam-1"`
  - `app/src/test/java/com/yicamalt/camera/CameraRegistryModuleTest.kt:88` — `"device_id":"cam-1"`, `"cam-2"`
  - `app/src/test/java/com/yicamalt/event/EventServiceModuleTest.kt:400-409` — `extraLiterals = listOf("ev-1", "ev-2", "cam-1", "t-1", "s-1")`
- `cam-1` is 4 characters and contains no digits, so `\b\d{10,}\b` never fires and `CameraDao.kt:42` passes the gate. `V-M-EVENT scenario_6` caught the `EventDao` leak only because someone noticed manually; `V-M-LOCAL-DB scenario_5` (`YiDatabaseTest.kt:99-103`) is green **today, with the defect live**.
- Secondary hazard in the same harness: the `extraLiterals` list contains 3-character substrings like `"s-1"` and `"ev-1"`, matched as raw substrings anywhere in the buffer (`RedactionScanner.kt:39-42`). Any unrelated log containing `s-1` produces a false positive. Short literals are the wrong tool; use a realistic serial fixture instead.
- Fix approach: add one shared fixture — `const val REALISTIC_SERIAL = "1234567890123"` — and route every redaction scenario through it, so the `\d{10,}` rule is actually exercised. Then fix all six sites together and re-run.

### `DeviceSignProbe` ships in release builds

**Status: [NEW]**

- `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt` is a plain `@Singleton` (line 31) injected unconditionally into `SettingsViewModel` (`app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt:33`), which `SettingsScreen` (`SettingsScreen.kt:50`) instantiates, which `MainActivity.kt:37` and `AppShellScreen.kt:88` both reach.
- There is no `BuildConfig.DEBUG` guard anywhere in the class, in the view-model, or in the screen. `buildConfigField("boolean", "LOCAL_RTSP_ENABLED", "false")` for release (`app/build.gradle.kts:34`) gates a *different* feature.
- Consequences in a release build:
  1. The button `Отладить подпись API` (`SettingsScreen.kt:94-98`) is present and live — a reverse-engineering harness exposed to end users.
  2. `runAll()` puts the raw access token in a **URL query string** at `DeviceSignProbe.kt:64-69, 73`. URLs land in server access logs, proxy logs, and any intermediary — a materially worse place for a token than a header.
  3. It builds its **own** `OkHttpClient` (`DeviceSignProbe.kt:36-39`) with no redacting logging interceptor, no `Authenticator`, and no timeouts beyond 10s — a second, unguarded egress path from the app.
  4. `out` (`DeviceSignProbe.kt:79-82`) is rendered directly into the UI at `SettingsScreen.kt:99-101`, so response bodies surface on screen even where Timber is a no-op.
  5. `DeviceSignProbe.kt:58` URL-encodes the hmac and passes the token in the clear — the same credential-exposure class as above.
- `app/proguard-rules.pro:2` (`-keep class com.yicamalt.** { *; }`) guarantees R8 will **not** strip it in release either.
- Fix approach: move the class into `app/src/debug/java`, or gate `runSignProbe()` and the button on `BuildConfig.DEBUG`; drop the `&token=$token` variants (`DeviceSignProbe.kt:64-69`) now that `/v5/devices/list` is confirmed — they were probe scaffolding for a solved question.

### Release minification is disabled by a blanket keep rule

**Status: [NEW]**

- `app/build.gradle.kts:31` sets `isMinifyEnabled = true` for release.
- `app/proguard-rules.pro:2` — `-keep class com.yicamalt.** { *; }` keeps every class and member in the app's own package. Result: the release APK ships effectively unshrunk and unoptimised, and none of the reflective/serialization work in lines 3-6 is doing anything the blanket rule is not already doing.
- Fix approach: replace the blanket keep with the generated kotlinx-serialization and Retrofit/Hilt consumer rules; keep only the `@Serializable` DTOs by name.

### Theme mismatch: Material 3 UI on a platform theme

**Status: [NEW]**

- `app/src/main/AndroidManifest.xml:13,19` — `android:theme="@android:style/Theme.Material.Light.NoActionBar"`.
- The entire UI is Compose Material 3 (`CameraListScreen.kt:25-30`, `SettingsScreen.kt:22-29`, `AppShellScreen.kt:21-26`, `LoginScreen.kt:22-30`), which reads its colour scheme from `MaterialTheme.colorScheme` and expects a `Theme.Material3.*` host to seed it.
- There is no XML theme resource, no `MaterialTheme(...)` wrapper in `MainActivity.kt:33-53`, and no `values/themes.xml`. Material 3 components fall back to their default (baseline purple) scheme instead of the app's intent, and the platform theme also controls the status bar and system bar behaviour the Compose `Scaffold` does not.
- Fix approach: add a `Theme.Material3.DayNight.NoActionBar` theme (or the `androidx.compose.material3` XML parent) and wrap `setContent` in `MaterialTheme { }`.

### Truststore path and password committed to the build

**Status: [CONFIRMED] — and there are two, not one**

- `app/build.gradle.kts:127-131` — `tasks.withType<Test> { jvmArgs("-Djavax.net.ssl.trustStore=C:/Users/pvanyushkin/Android/Sdk/cacerts.jks", "-Djavax.net.ssl.trustStorePassword=changeit", "-Djavax.net.ssl.trustStoreType=JKS") }`
- `gradle.properties:7-9` — the **same three properties** as `systemProp.*`, at repo root, applying to *every* Gradle task and every test JVM in the repo.
- Both point at a path that exists only on the original author's machine. `docs/PROJECT_STATUS.md:117` explains why (Axiom JDK ships an incomplete `cacerts`; a corporate MITM CA had to be added). `docs/PROJECT_STATUS.md:134-136` documents the SDK layout as a `C:\Users\pvanyushkin\...` tree, and the actual `local.properties` on this machine points somewhere else entirely (`C:\Users\Pavel\AppData\Local\Android\Sdk`) — so the two files already disagree about which machine is real.
- Impact: on any other machine the property points at a nonexistent file. The JDK then either falls back to the default truststore or fails every HTTPS fetch in the test JVM. Because `gradle.properties:7-9` is global, this affects the *build* JVM too, not just tests — and it is the very reason a fresh checkout cannot run `:app:testDebugUnitTest` unaided.
- Additional note: `changeit` is the JDK truststore default password, so this is not a secret. The concern is portability, not confidentiality — but the truststore also carries a **corporate MITM CA**, so any machine that has the file trusts that CA for all repo traffic.
- Fix approach: move both blocks behind a property with a sane default and a skip:
  ```kotlin
  tasks.withType<Test> {
      val trustStore = providers.gradleProperty("test.trustStore").orNull
      if (trustStore != null) jvmArgs("-Djavax.net.ssl.trustStore=$trustStore", /* ... */)
  }
  ```
  and document `-Ptest.trustStore=...` in `AGENTS.md`. Do **not** commit the MITM truststore itself.

### Vendor APK bundle and third-party signing material committed to git

**Status: [NEW]**

- All 42 files under `_apk/` are **tracked** (`git ls-files _apk`). Sizes:
  - `_apk/Yi+Home_6.9.8_20260826105838_APKPure.xapk` — 270,603,742 bytes
  - `_apk/com.ants360.yicamera.international_..._apkmirror.com.apkm` — 137,579,511 bytes
  - `_apk/extracted/base.apk` — 128,006,101 bytes
  - `_apk/extracted/split_config.arm64_v8a.apk` — 63,271,310 bytes
  - `_apk/extracted/split_config.armeabi_v7a.apk` — 53,106,218 bytes
  - plus 26 more language/density splits and `docs/logs/classes8.dex.jadx` (477 bytes of extracted dex strings)
- Total ≈ 570 MB of third-party vendor binaries in the repository history. `.gitattributes:1-3` routes `*.apk`/`*.apkm`/`*.xapk` through Git LFS, so this is 570 MB of LFS objects plus pointer files, and every future `git clone` is a multi-hundred-megabyte operation (`INSTRUCTIONS.md:22-23` already tells contributors to run `git lfs pull`).
- `_apk/extracted/META-INF/APKMIRRO.RSA` (1,597 bytes) and `APKMIRRO.SF` / `MANIFEST.MF` are tracked and are **not** covered by any `.gitignore` or LFS rule. These are the APKMirror signing certificate and JAR signature files redistributed inside the repo. Redistributing a third party's signing material is both a legal exposure and an unnecessary one — nothing in the build or the tests reads them.
- `.gitignore:1-8` covers `build/`, `.gradle/`, `.idea/`, `local.properties`, `*.iml`, `captures/`, `.cxx` — and nothing in `_apk/`. The deliberate `captures/` exclusion (`INSTRUCTIONS.md:209,236`: flow files contain tokens and cookies) is good hygiene that was never extended to the APK tree.
- Fix approach: keep the capture *instructions* (`INSTRUCTIONS.md`, already good — §1 requires Git LFS, §9.1 tells the user to install manually) and drop the binaries: `git rm -r --cached _apk`, add `/_apk/**` to `.gitignore` with an explicit exception for `_apk/extracted/info.json` if any tooling needs it, and add an `APKMIRROR`/`META-INF` exclusion. Redistributing the vendor APK is also a licensing question the project should settle before any public release — this is a reverse-engineering project, so shipping the competitor's binaries is the kind of thing that gets a repo taken down.

### No CI

**Status: [CONFIRMED]**

- There is no `.github/`, `.gitlab-ci.yml`, `azure-pipelines.yml`, `.circleci/`, or any other CI definition anywhere in the repository (`Get-ChildItem -Recurse -Directory -Depth 2` finds only `.git`, `.gradle`, `.planning`, `app/build/.transforms`).
- Consequences: the 91/91 suite, the redaction gate, the trace-marker gates, and the "assembly of `app-debug.apk`" evidence in `docs/PROJECT_STATUS.md:383-385` are all produced manually and are reproducible by nobody but the machine that produced them. `docs/verification-plan.xml` is the project-wide verification contract and has no automated enforcement point.
- The `gradle.properties:7-9` and `app/build.gradle.kts:127-131` hardcoded truststore paths are exactly the class of defect a first CI run would have caught on day one.
- Fix approach: add a GitHub Actions workflow that (a) installs JDK 17 + Android SDK 34, (b) sets the truststore from a repo secret or skips it via the property above, (c) runs `:app:testDebugUnitTest` and `:app:assembleDebug`, and (d) **fails if `_apk/` is non-empty or if `assembleDebugAndroidTest` fails** — the latter would have surfaced the `androidTest` compile break immediately.

### No `POST_NOTIFICATIONS` runtime request

**Status: [NEW] (minor)**

- `app/src/main/AndroidManifest.xml:6` declares `android.permission.POST_NOTIFICATIONS`, required from API 33 for the FCM/push work in `M-PUSH` (`docs/knowledge-graph.xml:164`).
- No `rememberLauncherForActivityResult` / `ActivityResultContracts.RequestPermission` call exists anywhere in `app/src/main/java`. When M-PUSH lands, notifications will silently not appear on API 33+ unless the request is added.
- Fix approach: add the request in `MainActivity` at the point M-PUSH is implemented; it is a one-liner today and a debugging session later.

### Login view-model exposes mutable state to the view

**Status: [NEW] (minor)**

- `app/src/main/java/com/yicamalt/ui/login/LoginViewModel.kt:42-43` — `val email = MutableStateFlow("")` and `val password = MutableStateFlow("")` are **public mutable** flows.
- `app/src/main/java/com/yicamalt/ui/login/LoginScreen.kt:85, 92` write them directly: `viewModel.email.value = it`.
- Consequence: the password lives in a public `MutableStateFlow` for the screen's lifetime, is writeable from anywhere holding the view-model, and the `canSubmit()` gate (`LoginViewModel.kt:49-50`) reads `_state.value` which any caller can also perturb. The password should never be a public field on a view-model.
- Fix approach: expose `StateFlow` (read-only) plus `onEmailChange` / `onPasswordChange`, and clear `password` in `reset()`.

### `ResponseTokens` can latch onto unrelated short key names

**Status: [NEW] (minor, latent)**

- `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:200-203`:
  ```kotlin
  private val ACCESS_KEYS  = setOf("access_token", "accessToken", "token", "at")
  private val REFRESH_KEYS = setOf("refresh_token", "refreshToken", "rt", "token_secret", "tokenSecret")
  ```
- `ResponseTokens.walk` (line 219-254) traverses the whole envelope to depth 4 and takes `access.firstOrNull()` / `refresh.firstOrNull()` (line 216). A field named `at` or `rt` **anywhere** in that subtree — a timestamp abbreviation, a locale, a nested config object — is accepted as the access or refresh token. The result is a session built from the wrong string that then fails silently at the first signed request.
- `USER_KEYS` at line 203 is `setOf("user_id", "userId", "uid", "userid", "userid")` — `"userid"` is listed twice. A `Set` silently drops the duplicate, so it is harmless today, but it is a signal that the list was edited without a compile-time check.
- The tolerance is deliberate and documented (`YiCloudAuthApi.kt:5-8`: the envelope shape is unconfirmed). The risk is the *breadth* of the walk, not the tolerance.
- Fix approach: keep the tolerance but bound the key candidates to the envelope's top level and one `data` level rather than a blind depth-4 walk, and drop `at`/`rt` or require them to be non-empty and 20+ chars.

---

## Performance Bottlenecks

### `ResponseTokens.walk` on every login and refresh

**Problem:** unbounded-by-shape tree walk with string allocation.
**Files:** `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:205-217` (extract) and `219-254` (walk).
**Cause:** `walk` recurses over every `JsonObject`/`JsonArray` member to depth 4, allocating a `LinkedHashMap`-free but still per-key `String` for every primitive, and `extract` collects **all** candidate access and refresh tokens into two `MutableList<String>` only to read `.firstOrNull()`. The `onScalar` lambda allocation at line 210 is per-call. `login` and `doRefresh` each invoke this once, so it is not a throughput problem — it is a correctness problem (see Security) with a memory profile attached.
**Improvement path:** collapse the two lists to a single `String?` each, break out of the walk on first match, and drop the depth-4 recursion to a bounded two-level scan. The current shape is only justified by an unconfirmed envelope, and that justification should be re-checked at the next capture.

### Per-request `runBlocking` on the dispatcher

**Problem:** every outbound request blocks an OkHttp worker thread.
**Files:** `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:82-93` and `186-191`.
**Cause:** the `authInterceptor` resolves the token synchronously on every call, and `AuthRepository.getAccessToken()` may issue a nested network call while the worker is parked.
**Improvement path:** see the fix in Known Bugs — pre-resolve into `currentToken` via `setAuthToken` (line 183), which already exists and is already used by `HttpClientModuleTest.kt:151`. The interceptor then becomes a pure `AtomicReference` read.

### `CameraRegistry` and `EventServiceModule` hop to `Dispatchers.IO` for cache-only reads

**Problem:** disk-bound work queued on the shared IO pool behind unrelated network work.
**Files:** `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:95-97` (`getCameraById`), `100-104` (`cacheCameraList`), `107-112` (`updateCameraStatus`); `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:142-156` (`getEventById`, `getLocalTimeline`), `203-205` (`getLocalEventCount`).
**Cause:** `getLocalEventCount` is a `SELECT COUNT(*)` behind a `withContext(Dispatchers.IO)` that serves a UI badge; `getCameraById` is a primary-key lookup. Room's own executor already handles this.
**Improvement path:** drop the `withContext` wrappers on the pure-DAO paths and let Room's dispatcher do its job; keep `withContext(Dispatchers.IO)` only around the actual network calls. Low impact at current data volumes, but it compounds once M-STREAM adds high-frequency reads.

---

## Fragile Areas

### `HttpClientModule` owns four responsibilities and two of them are load-bearing hacks

**Files:** `app/src/main/java/com/yicamalt/network/HttpClientModule.kt`
**Why fragile:** the class is simultaneously the OkHttp factory (147-156), the auth interceptor (82-93), the token redactor (95-111), the response-body evidence logger (133-145), the 401 authenticator (115-126), *and* a mutable singleton whose `retrofit` field is swapped at runtime (158, 174-177). The `auth_token` path check at line 87 and the `refreshGuard` at line 113 are both bug-fix scar tissue for the deadlock recorded in `AuthRepository.kt:25-31`.
**Also fragile:** the file carries **two** `START_MODULE_MAP` blocks — `HttpClientModule.kt:36-39` and again at `52-54` for `NetworkModule`. AGENTS.md Core Principle 2 requires anchors to be uniquely named; a duplicate anchor makes the first one unreachable by a naive parser and will confuse the next `grep`-driven agent.
**Safe modification:** never add a fourth interceptor to the `Builder` chain at 147-155 without re-reading the redaction rules — the chain order is what makes the token redaction work, and `HttpLoggingInterceptor` at position 2 sees the request the interceptor at position 1 just mutated.
**Test coverage:** `HttpClientModuleTest` has 6 scenarios covering header injection, single-retry, no-loop, and header redaction. It has **no** `rebuild()` scenario and **no** `responseBodyLogger` scenario — the two places where the two confirmed security findings live.

### `ConfigModule` reads a value that changes underneath it

**Files:** `app/src/main/java/com/yicamalt/config/ConfigModule.kt:56`, `75-82`; `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:160-177`
**Why fragile:** `ConfigModule` snapshots `BuildConfig.YI_API_BASE_URL` once at construction (line 56, with an explicit `initializedOnce` marker at line 58) but reads the user override on every `getApiBaseUrl()` call (line 77). So the effective URL is correct at read time and **stale at Retrofit-build time** — exactly the split that produces the `rebuild()` defect. The two-layer read is correct; the coupling to a memoized Retrofit is what breaks.
**Safe modification:** treat `getApiBaseUrl()` as the single source of truth and never cache its result in a field. Any new `val cachedUrl = config.getApiBaseUrl()` anywhere reintroduces the bug.
**Test coverage:** `ConfigModuleTest` (86 lines) covers the override-preferred and blank-fallback paths. `HttpClientModuleTest.kt:158-160` uses a `TestConfig` subclass overriding `getApiBaseUrl()` — so the tests exercise a *different* code path from production and would not catch the override/Retrofit split.

### `TraceRecorder.unplant()` uproots the entire Timber forest

**Files:** `app/src/test/java/com/yicamalt/test/TraceRecorder.kt:40`; callers at `YiDatabaseTest.kt:42`, `CameraRegistryModuleTest.kt:83`, `HttpClientModuleTest.kt:62`, `EventServiceModuleTest`
**Why fragile:** `unplant()` is `Timber.forest().forEach { Timber.uproot(it) }` — it removes every planted tree, not just the one `plant()` added. With JUnit 5's default sequential execution this is safe today, but the moment `junit.jupiter.execution.parallel.enabled` is turned on, one class's `tearDown` silently kills another class's recorder mid-assertion, producing intermittent `Expected BLOCK ... to appear in trace` failures that look like product bugs.
**Test coverage:** itself. `TestInfrastructureSmokeTest.kt:22-32` exercises plant/assert/unplant but not concurrent instances.
**Safe modification:** keep a reference to the tree `plant()` created and uproot only that. Also note the suite mixes JUnit 4 (`YiDatabaseTest.kt:22-26`, `LoginScreenTest.kt:22-24`) and JUnit 5 (`CameraRegistryModuleTest.kt:24-29`) in the same source set, bridged by `junit-vintage-engine` (`app/build.gradle.kts:104`) — the vintage engine's `org.junit.Assert` and Jupiter's `org.junit.jupiter.api.Assertions` both throw `AssertionError`, so `TraceRecorder`'s `check(...)`-based assertions work under both, but the two engines do not share lifecycle ordering guarantees.

### The phase-3 streaming surface is fiction with a green test suite

**Status: [DOCUMENTED] (module absence is [CONFIRMED])**

- `docs/knowledge-graph.xml:123` `M-STREAM-LIVE STATUS="planned"`, `docs/knowledge-graph.xml:138` `M-STREAM-DECODE STATUS="planned"`, plus `M-PUSH` (164), `M-MEDIA` (178), `M-ONBOARDING` (269), `M-EVENT-PLAYBACK` (150) — all `planned`.
- [CONFIRMED] there is no live-view code: `AppShellScreen.kt:43-46` defines only `Cameras` and `Settings`; the file's own contract comment at `AppShellScreen.kt:6` says "Live-View / Events tabs arrive in Phase-3; only Cameras + Settings are wired now". No `M-STREAM-*` file exists under `app/src/main/java/com/yicamalt/`.
- Meanwhile `app/src/test/java/com/yicamalt/test/FakeCameraStreamServer.kt` and `StreamHandleFake.kt` model a 18fps `CONNECTING → PLAYING` stream that nothing consumes, and `TestInfrastructureSmokeTest.kt:65-84` asserts those fakes work. Green gate, zero product surface.
- `docs/verification-plan.xml:389` records the stop condition: if the `/v1/camera/live/key` handshake shape changes, halt and replan, do not guess key derivation. `docs/verification-plan.xml:728` has the equivalent for pairing.
- Prerequisites, [DOCUMENTED]: `INSTRUCTIONS.md` is the capture runbook (mitmproxy 11.0.2 on port 8080, mitm CA pushed to both `/system/etc/security/cacerts/` and `/apex/com.android.consecrypt/cacerts/` at lines 132-152, AVD `yicap` with `-writable-system` at 107-111, manual credential entry only per line 13). The `_apk/` bundle and the LFS pull (`INSTRUCTIONS.md:22-23`) are the inputs. `docs/PROJECT_STATUS.md:184` records that jadx **does not complete** in this environment (85,973 classes), so the dex work needed a custom string-pool parser (`docs/PROJECT_STATUS.md:183`).
- `docs/PROJECT_STATUS.md:29` records that Evidence-3 (Login → shell navigation on an emulator) is **deferred** because no emulator or device is available. So no end-to-end user journey has been verified on real hardware since the v0.4.2 login confirmation on 2026-09-09.

### `EventServiceModule.toPayloadJson` hand-rolls JSON

**Files:** `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:229-239`
**Why fragile:** the cache payload is built with string concatenation and one `Json.encodeToString(String.serializer(), value)` per field (line 247). It happens to be correct today because `quote()` is the only escaping path, but any new field added to `EventInfo` must be appended to *both* `toPayloadJson` and `EventTimelineParser`'s key lists in `EventInfo.kt:73-95`, and a miss is a silent data loss — the row is written, the field is invisible on read, and `extractOne` returns an `EventInfo` with a null the caller treats as "no recording".
**Safe modification:** add a field to `EventInfo` and you must touch three places: `EventInfo.kt:38-45` (the data class), `EventInfo.kt:119-130` (`toInfo`), and `EventServiceModule.kt:230-239` (`toPayloadJson`). There is no test that fails if you miss the third.
**Test coverage:** `EventServiceModuleTest` covers the timeline window, pagination, cache round-trip, and the empty-page invariant, but not a field-completeness assertion.

### Two structurally identical tolerant parsers

**Files:** `app/src/main/java/com/yicamalt/camera/CameraInfo.kt:40-112` (`CameraListParser`) and `app/src/main/java/com/yicamalt/event/EventInfo.kt:71-202` (`EventTimelineParser`)
**Why fragile:** `collectCameraObjects` (`CameraInfo.kt:63-79`) and `collectEventObjects` (`EventInfo.kt:132-147`) are the same 17-line algorithm with different key lists, both capped at `depth > 3` and both falling back to "first array we find" — a heuristic that will happily parse a thumbnail array as a camera list. The next capture will likely need the same "add a key" edit in two places, and the two will drift.
**Safe modification:** if you change the depth cap or the array-selection heuristic, change it in both files, or extract the shared walker.

---

## Scaling Limits

### Room `fallbackToDestructiveMigration()` is the schema's only exit

**Current capacity:** `version = 1`, 3 tables, no migration objects (`app/src/main/java/com/yicamalt/database/YiDatabase.kt:20-29`; `app/src/main/java/com/yicamalt/database/DatabaseModule.kt:45`).
**Limit:** the first `version = 2` bump deletes `cameras`, `events`, and `app_meta` on every existing install. For an app whose Phase-2/3 purpose is offline-first camera and event browsing, that is total cache loss with no in-app signal.
**Scaling path:** add the `room.schemaLocation` KSP argument now, commit `app/schemas/`, and write explicit `Migration` objects as part of the first version bump rather than after a data-loss report.

### No pagination, no retention policy, unbounded local event growth

**Current capacity:** `EventEntity` is written by `EventServiceModule.syncToDb` (`EventServiceModule.kt:208-227`) with `OnConflictStrategy.REPLACE` on `event_id` — so re-fetching is idempotent — but nothing ever prunes. `EventDao.clearOlderThan` (`EventDao.kt:52-53`) exists and `YiDatabaseTest.kt:80-89` proves it works, but **no production caller exists**. `AppMetaDao` (36 lines) is fully implemented and entirely unused.
**Limit:** `payload_json` stores the whole event object as text (`EventServiceModule.kt:230-239`). At 20 events per page (`EventServiceModule.kt:250`), unbounded `getLocalTimeline` windows, and a full video-event payload each, the `events` table grows without limit on a device with no storage pressure signal — there is no cache-size cap and no `PRAGMA` tuning.
**Scaling path:** wire `clearOlderThan` into a `syncEventsToDb` completion step with a retention window; add a byte-budget eviction over `payload_json`; start using `AppMetaDao` to persist the `last_sync` watermark the table was designed for.

### Full-envelope logging with a 4 KB peek and a 600-char take

**Current capacity:** `HttpClientModule.kt:139-142` peeks 4 KB and takes 600 chars per matching request, matching any URL containing `/login`, `/auth_token`, `/devices`, or `/device`.
**Limit:** `peekBody(4096L)` on a streaming or chunked response buffers 4 KB per call on the interceptor thread, and `/device` matches a broad URL family. With M-STREAM adding a stream handshake under a similar path, this becomes per-frame overhead on a hot path.
**Scaling path:** narrow the URL match to the exact confirmed paths, drop the peek once the envelope shape is captured, and remove the interceptor entirely once the capture work is done — it exists to gather evidence, not to run in production.

### `CameraListViewModel.load()` in `init` plus a manual retry

**Current capacity:** `app/src/main/java/com/yicamalt/ui/cameras/CameraListViewModel.kt:44-46` fires `load()` from `init`, and `CameraListScreen.kt:53` exposes a `Повторить` button that calls it again.
**Limit:** no request coalescing and no cancellation of the in-flight `port.getCameraList()`. A fast double-tap issues two `getCameraList()` calls, each of which runs the non-atomic `deleteAll()` + insert loop described in Known Bugs.
**Scaling path:** hold a `Job` handle in the view-model and cancel the previous load in `load()`; guard the registry facade with a `Mutex`.

---

## Dependencies at Risk

### `androidx.security:security-crypto:1.1.0-alpha06`

**Risk:** the token store (`app/src/main/java/com/yicamalt/auth/AuthStore.kt:41-50`) depends on `EncryptedSharedPreferences` and `MasterKey` from an **alpha** dependency. `security-crypto` has been alpha since 1.0.0-alpha01; the 1.1.0 line is deprecated for new projects by its own maintainers, and the whole `MasterKey`/`EncryptedSharedPreferences` surface has no stable successor on the AndroidX roadmap.
**Impact:** every Yi access token, refresh token (`token_secret`), and user id passes through this code path. A breaking alpha change is an app-wide auth failure.
**Migration plan:** isolate the dependency behind the existing `AuthStore` interface (`AuthStore.kt:30-34`) — which is already done — and evaluate a direct `AndroidKeyStore` AES-GCM envelope, or the `keystore-android` + DataStore path, behind that same interface. No call site outside `AuthStore.kt` needs to change.

### `de.mannodermaus.android-junit5:1.10.0.0`

**Risk:** the JUnit 5 bridge plugin. `app/build.gradle.kts:7` applies it and `app/build.gradle.kts:123-124` configures `tasks.withType<Test> { useJUnitPlatform() }` globally. The whole verification contract in `docs/verification-plan.xml` — 91 tests, the redaction gate, the trace-marker gates — runs on this plugin. It is a third-party (non-AGP, non-Google) plugin with a small maintainer base and no first-party CI in this repo (see No CI), so a regression surfaces as a red suite with no upstream signal.
**Impact:** losing the plugin means losing the entire automated gate.
**Migration plan:** the suite is JUnit 4 + Jupiter mixed (`YiDatabaseTest.kt:22-26` is JUnit 4; `CameraRegistryModuleTest.kt:24-29` is Jupiter). Migrating the two JUnit 4 classes to Jupiter and dropping the plugin is a small, mechanical change that removes a supply-chain dependency. Do it while the suite is 91 tests, not at 400.

### `org.robolectric:robolectric:4.11.1` downloads a JVM over HTTPS at test time

**Risk:** this is the *only* reason the hardcoded truststore exists. `app/build.gradle.kts:125-126` says so explicitly: "Kaspersky MITM CA must be trusted by the test JVM so Robolectric can fetch the android-all-instrumented jar over HTTPS from Maven Central." Robolectric fetches its android-all jars at runtime, on a network, using the JVM's default truststore — which the Axiom JDK does not ship complete (`docs/PROJECT_STATUS.md:117`).
**Impact:** every `:app:testDebugUnitTest` on a machine without that exact truststore file either fails or falls back to an incomplete CA set. The dependency is a hardcoded absolute path in two files.
**Migration plan:** the `-Progressive` Robolectric mode (`robolectric.offline=true` + a `dependencyDirectories` block) pins the android-all jars as ordinary Gradle dependencies, removing the runtime download and with it the truststore requirement entirely. This is the clean fix for the portability problem, not a `-P` escape hatch.

---

## Missing Critical Features

### No live view: the core product value is absent

- Problem: the app's entire premise — watching your Yi camera without a subscription — has no implementation. `docs/knowledge-graph.xml:123` `M-STREAM-LIVE` and `138` `M-STREAM-DECODE` are `planned`; [CONFIRMED] no source file exists for either. `AppShellScreen.kt:6` states it in the file's own contract.
- Blocks: every downstream Phase-3/Phase-4 module — `M-MEDIA` (`knowledge-graph.xml:178`), `M-UI-LIVEVIEW` (referenced at `337`), `M-ONBOARDING` (`269`), `M-PUSH` (`164`).
- Blocked on: a live protocol capture. `docs/PROJECT_STATUS.md:398-405` shows the pattern — 5 probe rounds resolved the signing scheme but not the endpoint; the device-list half of the same problem is still open. The same capture would presumably resolve `/v1/camera/live/key` (`docs/verification-plan.xml:389`).
- Prerequisites available: the full runbook in `INSTRUCTIONS.md` (mitmproxy + mitm CA into both Android cert stores + AVD + manual login) and the vendor bundle in `_apk/`. **Blocked on environment, not on code** — `docs/PROJECT_STATUS.md:29` records no emulator or device in the current environment.
- `docs/verification-plan.xml:389` carries the correct stop condition and it should be honoured: if the handshake shape cannot be confirmed, halt and replan rather than guessing key derivation.

### The home camera list is unresolved

- Problem: [DOCUMENTED] 5 probe rounds; `/v5/devices/list` returns success code `20200` with an empty device list (`docs/PROJECT_STATUS.md:401-405`).
- Blocks: the camera list UI, camera commands (`CameraCommandService` gates on a cache row at `CameraCommandService.kt:83-84`), and therefore all streaming (you cannot stream what you cannot list).
- Consequence in code, [CONFIRMED]: [see Tech Debt → Device-list endpoint] the failure is invisible in the UI.
- The blocker is recorded only in `docs/PROJECT_STATUS.md:401-405`, outside the canonical `docs/*.xml`. `docs/knowledge-graph.xml:71` says `implemented-provisional` and `docs/verification-plan.xml:247` says `passed` — the graph and the verification plan are **optimistic relative to reality**.

### No discovery / camera pairing

**Status: [DOCUMENTED]** — `docs/PROJECT_STATUS.md:25` defers discovery/setup out of Phase-2. The consequence is that the app can only ever see cameras already bound to the logged-in Yi account; there is no in-app path to add one. For a "no subscription" alternative client, this is a significant gap in the product surface, not just a missing screen. `docs/knowledge-graph.xml` has no discovery module node at all, and `docs/verification-plan.xml:728` shows the pairing stop condition exists but the module does not.

### No logout-triggered cache eviction

**Problem:** [CONFIRMED] `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:158-162` (`logout()`) clears the session and the encrypted store. It does **not** touch `CameraDao` or `EventDao` — the Room cache is not a dependency of `AuthRepository` (constructor at line 68-72 takes only api, store, clock).
**Impact:** after logout, a second user signing in on the same device sees the previous account's camera names, models, and event thumbnails in the list before the network refresh lands — and if the network fails, indefinitely, because `CameraListViewModel.kt:59-61` renders the `Error` state *instead of* falling back to the cache. Room holds `stream_url` and event `playback_url` values, which are credential-adjacent.
**Fix approach:** inject a cache-clearing port into the logout path, or move logout orchestration up to a use-case that owns both the session and the DAOs.

### No offline fallback for the camera list

**Problem:** `CameraRegistry.getCameraList()` (`CameraRegistry.kt:58-80`) always calls the network. `CameraDao.getAll()` (`CameraDao.kt:28-29`) exists and `CameraListViewModel` has an `Error` state, but there is no path that renders the cache when the fetch fails.
**Impact:** the app has an offline-first *architecture* (Room cache, `getCameraById`, `getLocalTimeline`) but no offline-first *behaviour*. Offline, the user gets an error screen instead of their cached cameras. `EventServiceModule.getLocalTimeline` (`EventServiceModule.kt:147-156`) was explicitly added for exactly this reason and has no caller.

---

## Test Coverage Gaps

### The redaction fixtures cannot detect the defect class they exist to detect

**What's not tested:** realistic device identifiers in the log-redaction gate.
**Files:** `app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt:45-49` and `:99-103`; `app/src/test/java/com/yicamalt/camera/CameraRegistryModuleTest.kt:88` and `:229-237`; `app/src/test/java/com/yicamalt/event/EventServiceModuleTest.kt:400-409`; the rule at `app/src/test/java/com/yicamalt/test/RedactionScanner.kt:26`.
**Risk:** HIGH. `V-M-LOCAL-DB scenario_5` is green while `CameraDao.kt:42` logs the raw serial, and it will stay green until a real 10+ digit serial is used. Six live leak sites (`CameraDao.kt:42`, `CameraRegistry.kt:120`, `CameraCommandService.kt:82`, `CameraCommandService.kt:91`, `DeviceSignProbe.kt:48`, `AppShellScreen.kt:53`) are all invisible to the current gate. See the Security entry for the full analysis.
**Priority:** HIGH.

### `HttpClientModule`'s response-body logger has zero coverage

**What's not tested:** the body masker and the two confirmed credential leaks.
**Files:** `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:133-145` (masker, line 141) and `:95-111` (query masker, lines 101-102).
**Risk:** HIGH. `HttpClientModuleTest` builds a client with a `logSink` (line 73) and asserts header redaction in `scenario_4` (line 136-145) — but never triggers `responseBodyLogger`, because no test enqueues a login/device URL through the client. Both the JSON-encoded-`data` mask failure and the unmasked `hmac` are reachable in production and untested.
**Priority:** HIGH.

### `HttpClientModule.rebuild()` has zero coverage

**What's not tested:** that a base-URL change actually reaches the created API interfaces.
**Files:** `app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt:46-52`; the four `@Singleton` providers at `AuthModule.kt:51-54`, `CameraModule.kt:38-46`, `EventModule.kt:38-41`.
**Risk:** MEDIUM-HIGH. The defect is documented at `docs/PROJECT_STATUS.md:360-361` but has no regression test, so the four-module scope is unguarded and any future provider refactor is invisible.
**Priority:** HIGH.

### No test asserts `EventInfo` → `payload_json` → `EventInfo` field completeness

**What's not tested:** that a round-trip through the hand-rolled JSON writer preserves every field.
**Files:** `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:230-239` (`toPayloadJson`); `app/src/main/java/com/yicamalt/event/EventInfo.kt:38-45`.
**Risk:** MEDIUM. Adding a field to `EventInfo` without updating `toPayloadJson` silently drops it from the cache with no test failure — the failure would only appear as a null playback URL in production, surfacing as `EVENT_NO_RECORDING` (`EventServiceModule.kt:188`).
**Priority:** MEDIUM.

### No test covers graceful degradation paths

**What's not tested:** `AuthError.TokenExpired` / `RefreshFailed` / `StoreFailed` (declared at `app/src/main/java/com/yicamalt/auth/AuthSession.kt:29-32`), `CameraError.NotFound` (`CameraRegistry.kt:41`), `CameraCommandError.Timeout` (`CameraCommandService.kt:34`), `EventError.NoRecording` (`EventServiceModule.kt:54`), and `HttpError.TlsFailed` / `Unauthorized` (`HttpClientModule.kt:47-50`).
**Files:** the error hierarchies above; `app/src/main/java/com/yicamalt/ui/cameras/CameraListViewModel.kt:57-61` and `app/src/main/java/com/yicamalt/ui/login/LoginViewModel.kt:66-76` are the only consumers.
**Risk:** MEDIUM. Nine error types exist, the UI maps two of them, and nothing asserts the mapping. `CameraCommandError.Timeout` in particular has no producer — nothing can currently throw it.
**Priority:** MEDIUM.

### Compose effects are asserted only by a test that has never run

**What's not tested:** any Compose UI behaviour.
**Files:** `app/src/androidTest/java/com/yicamalt/ui/login/LoginScreenTest.kt` (does not compile — see Known Bugs), `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt:53` (side effect in composition), `app/src/main/java/com/yicamalt/ui/login/LoginScreen.kt:85,92` (external state mutation).
**Risk:** MEDIUM. The only Compose test is unrunnable. `CameraListScreen`, `SettingsScreen`, and `AppShellScreen` have zero UI coverage — including the `Камеры не найдены` empty state that currently masks the unresolved device-list blocker.
**Priority:** MEDIUM.

### The base-URL validation path is untested

**What's not tested:** `ConfigModule.setApiBaseUrl` (`app/src/main/java/com/yicamalt/config/ConfigModule.kt:85-87`) with a URL that has a path segment, an `http://` scheme, or trailing whitespace.
**Files:** `app/src/test/java/com/yicamalt/config/ConfigModuleTest.kt`; `app/src/main/java/com/yicamalt/ui/settings/SettingsScreen.kt:79-85`.
**Risk:** MEDIUM. A user-entered `https://host/v1` crashes the app (see Known Bugs) and no test exercises that input.
**Priority:** MEDIUM.

### Gradle-daemon stdout inheritance is worked around but unverified

**What's not tested:** the `tools/gradle.ps1` redirect path itself.
**Files:** `tools/gradle.ps1` (untracked in git — `git status` shows `?? tools/`), `docs/PROJECT_STATUS.md:69-77`.
**Risk:** MEDIUM and under-appreciated. The documented hang — a fresh Gradle daemon holding the client's stdout pipe so EOF never arrives after `BUILD SUCCESSFUL` — is real and costs tens of minutes per occurrence. The workaround exists **only as an untracked file**. A fresh clone has no `tools/`, so `docs/PROJECT_STATUS.md:71` instructs every future agent to run a command that does not exist in the repository. The `? :` status output also means the module `app/src/main/java/com/yicamalt/event/` and its test are untracked, so `docs/PROJECT_STATUS.md:26`'s "suite 91/91 green" describes a working tree that git does not know about.
**Priority:** HIGH — this is a one-command fix (`git add tools/`) and it gates every other verification activity in the project.

---

## Summary — Additional Findings Not in the Supplied Concern List

| # | Finding | File(s) | Severity |
|---|---|---|---|
| 1 | Response-body masker misses the JSON-encoded-`data` envelope; tokens written to external-storage `auth_log.txt` (regex verified by execution) | `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:141` | **HIGH** |
| 2 | `hmac` query param is a replayable credential, logged verbatim; absent from every masker and from `RedactionScanner` | `HttpClientModule.kt:101-102,141`; `RedactionScanner.kt:26,28` | **HIGH** |
| 3 | 5 further raw-identifier log sites beyond `CameraDao` | `CameraRegistry.kt:120`, `CameraCommandService.kt:82,91`, `DeviceSignProbe.kt:48`, `AppShellScreen.kt:53` | HIGH |
| 4 | `refreshGuard` latches `true` on throw → 401 refresh permanently disabled | `HttpClientModule.kt:113-126` | HIGH |
| 5 | `androidTest` source set cannot compile (Robolectric not on the classpath) | `LoginScreenTest.kt:21,24` vs `app/build.gradle.kts:108,115-120` | HIGH |
| 6 | `tools/gradle.ps1` and the whole `event/` module are untracked — the documented build command and 23 tests are absent from the repo | `git status`: `?? tools/`, `?? app/src/main/java/com/yicamalt/event/` | HIGH |
| 7 | `DeviceSignProbe` ships in release, puts tokens in URL query strings, and has its own unguarded OkHttpClient | `DeviceSignProbe.kt:31,36-39,64-69,73` | HIGH |
| 8 | `saveBaseUrl` can throw `IllegalArgumentException` out of the click handler | `ConfigModule.kt:85-87` → `HttpClientModule.kt:161` | HIGH |
| 9 | ~570 MB of vendor APKs + third-party signing material committed to git | `_apk/**` (42 tracked files), `.gitignore` | MEDIUM-HIGH |
| 10 | Second hardcoded machine-specific truststore path at repo root (the brief cited only `app/build.gradle.kts`) | `gradle.properties:7-9` | MEDIUM-HIGH |
| 11 | `runBlocking` on the OkHttp dispatcher thread on every request | `HttpClientModule.kt:82-93,186-191` | MEDIUM |
| 12 | `cacheCameraList` delete-then-insert is non-atomic and unmutexed | `CameraRegistry.kt:100-104` | MEDIUM |
| 13 | Room `exportSchema = true` with no `room.schemaLocation`; destructive migration is the only exit | `YiDatabase.kt:23`; `DatabaseModule.kt:45`; `app/build.gradle.kts` | MEDIUM |
| 14 | `EventDao` write failures swallowed into a debug counter | `EventServiceModule.kt:211-224` | MEDIUM |
| 15 | Logout does not evict the Room cache; second user sees the first user's cameras and stream URLs | `AuthRepository.kt:158-162` | MEDIUM |
| 16 | `catch (Throwable)` swallows `CancellationException` | `CameraListViewModel.kt:59-61`, `LoginViewModel.kt:71-76` | MEDIUM |
| 17 | Duplicate `START_MODULE_MAP` anchor in one file (violates AGENTS.md Principle 2) | `HttpClientModule.kt:36-39` and `52-54` | MEDIUM |
| 18 | Orphaned `FakeYiApi` / `FakeAuthStore` model a `/v1/account/login` API that no longer exists; `FakeAuthStore` does not implement `AuthStore` | `FakeYiApi.kt:53,56`, `FakeAuthStore.kt`, `AuthStore.kt:30-34` | MEDIUM |
| 19 | Release minification disabled by `-keep class com.yicamalt.** { *; }` | `app/proguard-rules.pro:2` | MEDIUM |
| 20 | Material 3 UI hosted on a platform theme, no `MaterialTheme {}` wrapper | `AndroidManifest.xml:13,19`; `MainActivity.kt:33-53` | MEDIUM |
| 21 | `TraceRecorder.unplant()` uproots the whole forest; suite mixes JUnit 4 and 5 | `TraceRecorder.kt:40`; `YiDatabaseTest.kt:22-26` | MEDIUM |
| 22 | Hand-rolled `toPayloadJson` with no completeness test | `EventServiceModule.kt:230-239` | MEDIUM |
| 23 | `ResponseTokens` accepts `"at"`/`"rt"` anywhere to depth 4; `USER_KEYS` lists `"userid"` twice | `AuthRepository.kt:200-203` | LOW-MEDIUM |
| 24 | Settings tab's back arrow is a no-op lambda | `AppShellScreen.kt:88`; `SettingsScreen.kt:62-65` | LOW-MEDIUM |
| 25 | `getLiveStreamUrl` serves an unvalidated, arbitrarily stale cached URL | `CameraRegistry.kt:115-123` | LOW-MEDIUM |
| 26 | `CameraCommandService` reports a missing row as `UnsupportedModel`; `Timeout` error has no producer | `CameraCommandService.kt:83,34` | LOW |
| 27 | Redundant `Index(["device_id"])` shadowed by the composite index | `EventEntity.kt:24` | LOW |
| 28 | `LoginViewModel` exposes public mutable `MutableStateFlow` for the password | `LoginViewModel.kt:42-43`; `LoginScreen.kt:85,92` | LOW |
| 29 | `POST_NOTIFICATIONS` declared, never requested at runtime | `AndroidManifest.xml:6` | LOW |
| 30 | `DeviceListViewModel.load()` in `init` plus retry, no cancellation or coalescing | `CameraListViewModel.kt:44-46`; `CameraListScreen.kt:53` | LOW |
| 31 | No offline fallback for the camera list despite an offline-first architecture | `CameraRegistry.kt:58-80` vs `CameraDao.kt:28-29` | LOW-MEDIUM |
| 32 | `security-crypto:1.1.0-alpha06` is a long-standing alpha on the token path | `AuthStore.kt:41-50`; `app/build.gradle.kts:95` | LOW-MEDIUM |
| 33 | `EventDao.clearOlderThan` and the entire `AppMetaDao` have no production caller — unbounded event growth | `EventDao.kt:52-53`; `AppMetaDao.kt`; `EventServiceModule.kt:208-227` | LOW-MEDIUM |

---

*Concerns analysis: 2026-09-28*
