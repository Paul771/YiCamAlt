---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
<!-- refreshed: 2026-09-28 -->

# Architecture

**Analysis Date:** 2026-09-28

## System Overview

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                    Android OS / Single-Activity Process                   │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ Entry: YiCamAltApp (@HiltAndroidApp)  →  Timber + FileLogTree      │  │
│  │ Entry: MainActivity (@AndroidEntryPoint)  →  setContent { when/... } │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
                                   │ Hilt SingletonComponent
┌──────────────────────────────────┴───────────────────────────────────────┐
│  PRESENTATION — app/src/main/java/com/yicamalt/ui/                        │
│  ┌──────────────┬──────────────┬──────────────┬──────────────────────┐   │
│  │ ui/shell     │ ui/cameras   │ ui/login     │ ui/settings          │   │
│  │ AppShell     │ CameraList*  │ Login*       │ Settings*            │   │
│  │ Screen       │ ViewModel    │ ViewModel    │ ViewModel            │   │
│  │ (bottom nav) │ +UiState     │ +UiState     │ +UiState             │   │
│  └──────┬───────┴──────┬───────┴──────┬───────┴──────────┬───────────┘   │
└─────────┼──────────────┼──────────────┼──────────────────┼───────────────┘
          │  Port iface  │  CameraListPort │  LoginPort     │ injects Config +
          └──────────────┴────────────────┘  AuthRepository  │ HttpClientModule
                                                          │ DeviceSignProbe
┌──────────────────────────────────────────────────────────────────────────┐
│  DOMAIN (vertical slices) — one package per GRACE module                 │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────────────────┐  │
│  │ auth/      │ │ camera/    │ │ event/     │ │ config/ network/     │  │
│  │ AuthRepo   │ │ Camera     │ │ Event      │ │ database/            │  │
│  │ (facade)   │ │ Registry   │ │ Service    │ │ ConfigModule,        │  │
│  │ +4 Port    │ │ +Command   │ │ Module     │ │ HttpClientModule,    │  │
│  │  ifaces    │ │  Service   │ │ (facade)   │ │ Room DB + DAOs       │  │
│  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └──────────┬───────────┘  │
└────────┼───────────────┼──────────────┼───────────────────┼──────────────┘
         │ SessionSource │ CameraDao    │ EventDao         │ Retrofit +
         │ AuthProvider  │               │                   │ Room + EncryptedPrefs
┌────────┴───────────────┴──────────────┴───────────────────┴──────────────┐
│  INFRASTRUCTURE / IO                                                       │
│  ┌───────────────────────┐  ┌────────────────────┐  ┌──────────────────┐  │
│  │ retrofit2-kotlinx     │  │ androidx.room 2.6  │  │ security-crypto  │  │
│  │ Retrofit + kotlinx    │  │ yicamalt.db       │  │ EncryptedShared  │  │
│  │ serialization JSON    │  │ (3 tables, v1)    │  │ Preferences      │  │
│  └───────────┬───────────┘  └────────────────────┘  └──────────────────┘  │
└──────────────┼─────────────────────────────────────────────────────────────┘
               │ OkHttp interceptors: auth → redacted log → body capture
┌──────────────┴─────────────────────────────────────────────────────────────┐
│  EXTERNAL: Yi Cloud API (gw-*.xiaoyi.com) — v4 auth, v5 devices,           │
│  v8 control, v1 event (hmac-signed, reverse-engineered)                   │
│  Reverse-engineering evidence: _apk/extracted/, docs/logs/,               │
│  debug/DeviceSignProbe.kt                                                  │
└──────────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| App bootstrap | Hilt graph, Timber trees, dual file log sinks | `app/src/main/java/com/yicamalt/YiCamAltApp.kt` |
| Activity host | `setContent` + three-way `when` navigation (Settings / Shell / Login) | `app/src/main/java/com/yicamalt/MainActivity.kt` |
| App shell | Bottom-nav host over `ShellTab` (Cameras, Settings) + logout action | `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt` |
| Login screen/VM | Credential form, `LoginUiState`, success→host callback | `app/src/main/java/com/yicamalt/ui/login/LoginScreen.kt`, `LoginViewModel.kt` |
| Camera list UI | `CameraListUiState` rendering + retry; consumes `CameraListPort` | `app/src/main/java/com/yicamalt/ui/cameras/CameraListScreen.kt`, `CameraListViewModel.kt` |
| Settings UI/VM | API base URL override, `httpClient.rebuild()`, sign-probe trigger | `app/src/main/java/com/yicamalt/ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt` |
| Config | BuildConfig read once, feature flags, base-URL override, `SettingsStore` binding | `app/src/main/java/com/yicamalt/config/ConfigModule.kt`, `SettingsStore.kt` |
| HTTP | OkHttp + Retrofit singleton, auth interceptor, redacted logger, 401 refresh+retry | `app/src/main/java/com/yicamalt/network/HttpClientModule.kt` |
| Auth contract | `AuthProvider` — token read + refresh, consumed by the HTTP layer | `app/src/main/java/com/yicamalt/network/AuthProvider.kt` |
| Auth facade | `AuthRepository`: login/HMAC digest, refresh, session, implements 4 interfaces | `app/src/main/java/com/yicamalt/auth/AuthRepository.kt` |
| Auth transport | Retrofit `v4/users/login` (GET) + `v4/users/auth_token` (POST) | `app/src/main/java/com/yicamalt/auth/YiCloudAuthApi.kt` |
| Auth storage | `AuthStore` interface + `EncryptedAuthStore` (AES256_GCM Keystore) | `app/src/main/java/com/yicamalt/auth/AuthStore.kt` |
| Auth wiring | `@Binds` AuthProvider/AuthStore/LoginPort/SessionSource + `@Provides` API + Clock | `app/src/main/java/com/yicamalt/auth/AuthModule.kt` |
| Session model | `AuthSession`, `AuthMethod`, `AuthError` sealed hierarchy | `app/src/main/java/com/yicamalt/auth/AuthSession.kt` |
| Camera list | `CameraRegistry`: signed `v5/devices/list` fetch + Room cache refresh | `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt` |
| Camera parser | `CameraInfo` value + `CameraListParser` (candidate-key tolerant) | `app/src/main/java/com/yicamalt/camera/CameraInfo.kt` |
| Camera command | `CameraCommandService`: validate (model/online) → dispatch via `CommandTransport` | `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt` |
| Command models | `CameraCommandType`, `CameraCommand`, `PtzNormalizer`, `CommandPayload` | `app/src/main/java/com/yicamalt/camera/CommandModels.kt` |
| Command transport | Provisional `POST v8/device/control`, `code == "20000"` = ack | `app/src/main/java/com/yicamalt/camera/YiCloudCommandApi.kt` |
| Camera wiring | Provides both `YiCloud*Api`, binds `CameraRegistry`→`CameraListPort` | `app/src/main/java/com/yicamalt/camera/CameraModule.kt` |
| Event facade | `EventServiceModule`: paged fetch, cache sync, playback-URL resolution | `app/src/main/java/com/yicamalt/event/EventServiceModule.kt` |
| Event parser | `EventInfo`/`EventPage`/`ParsedTimeline` + `EventTimelineParser` | `app/src/main/java/com/yicamalt/event/EventInfo.kt` |
| Event transport | Retrofit event list + playback; `EventPaths` constants; `EventSigner` | `app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt` |
| Event wiring | Provides `YiCloudEventApi`, binds facade → `EventTimelinePort` | `app/src/main/java/com/yicamalt/event/EventModule.kt` |
| Local DB | `YiDatabase` (v1: cameras, events, app_meta) + 3 DAOs + `DatabaseModule` | `app/src/main/java/com/yicamalt/database/` |
| Debug oracle | `DeviceSignProbe`: tries 8 signed request variants, logs response codes only | `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt` |

## Pattern Overview

**Overall:** Vertical-slice feature modules + hexagonal ports, inside a single Gradle module (`app`), governed by an XML contract framework (GRACE).

**Key Characteristics:**

1. **One package per GRACE module.** `M-AUTH` → `com.yicamalt.auth`, `M-CAMERA-LIST`/`M-CAMERA-CMD` → `com.yicamalt.camera`, `M-EVENT` → `com.yicamalt.event`, `M-LOCAL-DB` → `com.yicamalt.database`. The `docs/knowledge-graph.xml` `<path>` for each module is a real file path in the matching package.
2. **Presentation is split out of the domain.** The domain packages hold facades and contracts; all Composables/ViewModels live in `com.yicamalt.ui.<screen>`. The only exception is `ui/cameras`, which is UI-only and has no domain package of its own.
3. **Ports keep the UI free of IO types.** `LoginPort`, `CameraListPort`, `EventTimelinePort` are declared *in the domain package* and implemented by the domain facade; ViewModels depend on the interface, so `CameraListViewModelTest` needs no Retrofit or DAO.
4. **Shape-tolerant parsing is the defining choice.** Every cloud response keeps `data` as a raw `kotlinx.serialization.json.JsonElement`; parsers enumerate candidate keys. Do not replace this with a typed DTO until a live capture lands in `docs/PROJECT_STATUS.md`.
5. **Semantic blocks are the observability contract.** `START_BLOCK_X` / `END_BLOCK_X` brackets every critical branch; the log line inside carries `[Module][function][BLOCK_NAME]`, and `docs/verification-plan.xml` asserts on those markers via `TraceRecorder`.
6. **Every facade owns an error hierarchy.** `ConfigError`, `HttpError`, `AuthError`, `CameraError`, `CameraCommandError`, `EventError` — all `sealed class ... : Error` with `object` cases whose messages are stable string codes.

## Layers

**Foundation (LAYER=0 in `docs/development-plan.xml`):**

- Purpose: shared config, HTTP transport, and local persistence every other module depends on.
- Location: `app/src/main/java/com/yicamalt/config/`, `network/`, `database/`
- Contains: `ConfigModule`, `SettingsStore`/`PrefsSettingsStore`, `HttpClientModule`, `NetworkModule`, `AuthProvider`, `HttpError`, `YiDatabase`, 3 entities, 3 DAOs, `DatabaseModule`.
- Depends on: nothing above Layer 0. `HttpClientModule` injects `ConfigModule` only.
- Used by: every domain facade; `MainActivity` and `SettingsViewModel` inject `ConfigModule`/`HttpClientModule` directly.

**Core domain (LAYER=1):**

- Purpose: Yi Cloud protocol logic — auth, camera list, camera commands, event timeline/playback, streaming (planned).
- Location: `auth/`, `camera/`, `event/`; planned `stream/`, `push/`, `media/` (`app/src/main/java/com/yicamalt/stream/` etc. do not exist yet).
- Contains: `@Singleton` facades with `suspend` methods, `*Port` / `*Provider` / `*Source` contracts, Retrofit `YiCloud*Api` interfaces, pure value types and parsers, `*Module.kt` Hilt wiring.
- Depends on: Layer 0 + `auth.SessionSource` for hmac signing.
- Used by: `ui` ViewModels via ports, and `MainActivity` for session state.

**Presentation (LAYER=2, UI_COMPONENT):**

- Purpose: Compose rendering, navigation host, and per-screen state machines.
- Location: `app/src/main/java/com/yicamalt/ui/{shell,cameras,login,settings}/`; planned `ui/{liveview,events,eventplayback,onboarding}/`.
- Contains: `@Composable` screens, `@HiltViewModel` ViewModels, sealed `*UiState` hierarchies, hardcoded Russian UI copy, `ShellTab` enum.
- Depends on: domain ports (`LoginPort`, `CameraListPort`) and domain value types (`CameraInfo`); `ui/settings` additionally injects `ConfigModule`, `HttpClientModule`, `DeviceSignProbe`.
- Used by: `MainActivity`, `AppShellScreen`.

**Integration (LAYER=2, INTEGRATION):**

- Purpose: local-network camera discovery and Wi-Fi pairing. Currently **planned only** — `M-CAMERA-DISCOVERY` and `M-CAMERA-SETUP` are `STATUS="planned"` in both `docs/development-plan.xml` and `docs/knowledge-graph.xml`, and `app/src/main/java/com/yicamalt/discovery/` and `setup/` do not exist.

**Debug / reverse-engineering (no GRACE module id):**

- Purpose: on-device oracle for probing unconfirmed cloud request shapes.
- Location: `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt`, reached from `ui/settings/SettingsViewModel.kt:55`.
- Contains: a *second*, independently configured `OkHttpClient` that bypasses M-HTTP interceptors on purpose, plus 8 hardcoded request variants.
- Used by: `SettingsScreen` "Отладить подпись API" button. It logs response codes and masked bodies only.

## Data Flow

### Login flow (verified on device 2026-09-09)

1. User types credentials → `LoginScreen.kt:83` writes into `LoginViewModel.email`/`password` (`MutableStateFlow`).
2. `LoginScreen.kt:117` submit → `LoginViewModel.submit()` (`LoginViewModel.kt:52`), logs `[Login][vm][BLOCK_LOGIN_SUBMIT]` with the redacted email, sets `LoginUiState.Loading`.
3. `LoginViewModel.kt:61` calls `LoginPort.login(...)` — the Hilt binding resolves to `AuthRepository.login` (`AuthRepository.kt:78`).
4. `AuthRepository.kt:84` HMAC-SHA256-digests the password (`hmacPassword`, `AuthRepository.kt:176`, vendor key `AUTH_HMAC_KEY` at `AuthRepository.kt:48`) and calls `YiCloudAuthApi.login` → `GET v4/users/login` with `seq/account/password/dev_name/dev_type/dev_os_version`.
5. `AuthRepository.kt:89` `ResponseTokens.extract` (`AuthRepository.kt:198`) walks the raw `JsonElement` for token-shaped keys; a blank access token → `AuthError.InvalidCredentials`.
6. `AuthRepository.kt:107` `store.putSession(session)` → `EncryptedAuthStore` (`AuthStore.kt:53`) writes to `EncryptedSharedPreferences`; logs `BLOCK_STORE_TOKEN`.
7. ViewModel sets `LoginUiState.Success`; `LoginScreen.kt:58` `LaunchedEffect` fires `onLoggedIn` → `MainActivity.kt:49` sets `loggedIn = true` → the `when` at `MainActivity.kt:36` swaps to `AppShellScreen`.

### Camera list flow

1. `AppShellScreen.kt:87` renders `CameraListScreen()` for `ShellTab.Cameras`; `hiltViewModel()` builds `CameraListViewModel` (`CameraListViewModel.kt:37`).
2. `CameraListViewModel.kt:45` `init { load() }` → logs `BLOCK_LOAD_CAMERAS`, state `Loading`, launches on `viewModelScope`.
3. `CameraListViewModel.kt:54` calls `CameraListPort.getCameraList()` → `CameraRegistry.getCameraList` (`CameraRegistry.kt:58`) inside `withContext(Dispatchers.IO)`.
4. `CameraRegistry.kt:64` signs the request: `Base64(HMAC-SHA1(key = "<accessToken>&<refreshToken>", msg = "seq=1&userid=<uid>"))` from `SessionSource.currentSession()` (`CameraRegistry.kt:63`, `:88`).
5. `YiCloudDeviceApi.deviceList` → `GET v5/devices/list?seq&userid&hmac`. Codes `20000`/`20200` are success; `20201/20202/20203/20205/20253/40110` → `CameraError.Unauthorized` (`CameraRegistry.kt:83`).
6. `CameraListParser.extract` (`CameraInfo.kt:49`) maps candidate keys → `List<CameraInfo>`; blank `deviceId` entries are dropped.
7. `CameraRegistry.kt:100` `cacheCameraList` → `cameraDao.deleteAll()` then `insertCamera` per row; `CameraDao.kt:42` logs `BLOCK_DB_WRITE`.
8. ViewModel maps to `CameraListUiState.Content` / `.Error`; `CameraListScreen.kt:48` renders Loading / Error+retry / empty / `LazyColumn`.

### Event timeline flow

1. `EventServiceModule.getEventTimeline` (`EventServiceModule.kt:77`) on `Dispatchers.IO`; window defaults to the last `DEFAULT_WINDOW_MS` (24 h) and page to `>= 1` (`:84-87`).
2. Signs with `EventSigner` (`YiCloudEventApi.kt:80`) — the same HMAC-SHA1 scheme as `CameraRegistry`, duplicated deliberately.
3. `YiCloudEventApi.eventList` → `GET EventPaths.EVENT_LIST` (`v1/camera/event/list` — **provisional**, `YiCloudEventApi.kt:47`).
4. `EventTimelineParser.extract` (`EventInfo.kt:100`) → `ParsedTimeline`; `resolveHasMore` (`EventServiceModule.kt:132`) prefers an explicit flag, then compares consumed offset against `total`, then falls back to "page came back full".
5. **Cache invariant:** an empty page never writes (`EventServiceModule.kt:122`), so a transient empty response cannot wipe the cache. `BLOCK_SYNC_TO_DB` is emitted only on a non-empty page.
6. Offline read: `getLocalTimeline` (`EventServiceModule.kt:148`) → `eventDao.getByDeviceInWindow` (indexed by `(device_id, timestamp)`, `EventEntity.kt:23`) → re-parse `payload_json`.

### 401 refresh-and-retry flow

1. `HttpClientModule.authInterceptor` (`HttpClientModule.kt:82`) adds `Authorization: Bearer <token>`; **`auth_token` calls bypass the token lookup** (`:87`) because `getAccessTokenBlocking()` can itself refresh and re-enter the same client — the observed infinite login spinner.
2. A 401 reaches `HttpClientModule.authenticator` (`:115`); `refreshGuard.compareAndSet` allows exactly one refresh per call (`:118`).
3. `authProvider.get().refreshBlocking()` → `AuthRepository.doRefresh` (`AuthRepository.kt:120`) → `POST v4/users/auth_token`, persists the new session, returns the new token.
4. OkHttp replays the original request once with the refreshed bearer. Failure (null token) returns `null` and the 401 surfaces to the caller.

### Base-URL reconfiguration flow

1. `SettingsScreen.kt:90` → `SettingsViewModel.saveBaseUrl` (`SettingsViewModel.kt:46`) → `ConfigModule.setApiBaseUrl` (`ConfigModule.kt:85`) → `PrefsSettingsStore` (`SettingsStore.kt:45`).
2. `SettingsViewModel.kt:49` `httpClient.rebuild()` replaces the `Retrofit` field on the `HttpClientModule` singleton (`HttpClientModule.kt:174`) so the new base URL takes effect without a restart.

### Debug sign-probe flow

`SettingsScreen.kt:95` → `SettingsViewModel.runSignProbe` (`SettingsViewModel.kt:55`) → `DeviceSignProbe.runAll` (`DeviceSignProbe.kt:43`): 8 signed variants over `v2/devices/list`, `v5/devices/list`, `v5/devices/deviceinfo` × bearer/token/both; each response is reduced to a `code` regex match and a token-masked 240-char body.

**State Management:**

- Compose `remember`/`mutableStateOf` for ephemeral UI state (active tab in `AppShellScreen.kt:51`, text field draft in `SettingsScreen.kt:55`).
- `StateFlow` of a sealed `*UiState` per ViewModel (`MutableStateFlow` + `asStateFlow()`, e.g. `LoginViewModel.kt:45-46`) as the single render source.
- Singleton mutable state in three places: `AuthRepository.sessionRef` (`@Volatile`), `HttpClientModule.retrofit`/`currentToken`/`refreshGuard`/`logSink`, and Compose's tab index.
- No repository-level `Flow` from Room (`@Query` returns `List`, not `Flow`) — caches are read on demand inside `Dispatchers.IO` blocks, not observed.

## Key Abstractions

**`<Feature>Port` (UI-facing contract):**

- Purpose: one-method-to-few-method read/use contract the ViewModel depends on instead of the facade.
- Examples: `LoginPort` at `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:58`, `CameraListPort` at `camera/CameraRegistry.kt:46`, `EventTimelinePort` at `event/EventServiceModule.kt:60`.
- Pattern: interface declared **in the domain package**, implemented by that package's `@Singleton` facade, bound with `@Binds @Singleton` inside the domain's Hilt module (`AuthModule.kt:40`, `CameraModule.kt:31`, `EventModule.kt:31`).
- **Add new ports here** whenever a ViewModel needs a capability the facade does not yet expose.

**`AuthProvider` / `SessionSource` (infrastructure-facing contract):**

- Purpose: let the HTTP layer read/refresh a token and let domain code sign requests without depending on the whole repository.
- Examples: `network/AuthProvider.kt:17`, `auth/AuthRepository.kt:63`.
- Pattern: one concrete implementation (`AuthRepository`) bound several times under different interface names in `auth/AuthModule.kt:30-44`.

**`CommandTransport` (delivery seam):**

- Purpose: keep PTZ/IR/sound dispatch testable without a live cloud control surface.
- Examples: interface at `camera/CameraCommandService.kt:38`, production impl `CommandTransportRetrofit` at `:45`, bound in `CameraModule.kt:50`.
- Pattern: interface + `@Provides` returning the concrete impl (not `@Binds`).

**Shape-tolerant parser (`*Parser` / `ResponseTokens`):**

- Purpose: tolerate an unconfirmed response envelope by enumerating candidate JSON keys, dropping entries without a usable primary key, and normalising timestamps.
- Examples: `camera/CameraInfo.kt:40` `CameraListParser`, `event/EventInfo.kt:71` `EventTimelineParser`, `auth/AuthRepository.kt:198` `ResponseTokens`.
- Pattern: `internal object` with `private val *_KEYS = listOf(...)`, `collectXObjects(element, depth)` recursion capped at depth 3–4, `firstString`/`firstInt`/`firstBool` accessors that try string primitives then numeric primitives.

**Error hierarchy (`XxxError`):**

- Purpose: typed failure surface per module; the `message` of each `object` is a stable machine-readable code, not prose.
- Examples: `config/ConfigModule.kt:27`, `network/HttpClientModule.kt:47`, `auth/AuthSession.kt`, `camera/CameraRegistry.kt:38`, `camera/CameraCommandService.kt:30`, `event/EventServiceModule.kt:52`.
- Pattern: `sealed class XxxError(message: String) : Error(message)` + `object Case : XxxError("PREFIX_STATE: description")`. Catch the specific case (`CameraListViewModel.kt:57`, `LoginViewModel.kt:66`) and fall back to `Throwable`.

**Persistence contract (`XxxStore`):**

- Purpose: an interface so tests can inject a non-Android variant.
- Examples: `SettingsStore`/`PrefsSettingsStore`/`InMemorySettingsStore` at `config/SettingsStore.kt:26-59`; `AuthStore`/`EncryptedAuthStore` at `auth/AuthStore.kt:30-39` (test double: `app/src/test/java/com/yicamalt/test/FakeAuthStore.kt`).
- Pattern: interface in the domain package, `@Binds` in the module's Hilt module, `@Singleton` impl using `@ApplicationContext`.

**`Clock`:**

- Purpose: deterministic expiry tests. `auth/AuthRepository.kt:43-44` (`Clock` / `SystemClock`), provided in `AuthModule.kt:57`.

**Semantic block (`START_BLOCK_X` / `END_BLOCK_X`):**

- Purpose: navigation anchor + log-anchor + verification-anchor triple. A block is a single critical branch sized to fit an agent working window.
- Examples: `BLOCK_VALIDATE_CREDENTIALS` (`AuthRepository.kt:79`), `BLOCK_FETCH_CAMERA_LIST` (`CameraRegistry.kt:59`), `BLOCK_REFRESH_RETRY` (`HttpClientModule.kt:116`), `BLOCK_DB_WRITE` (`EventDao.kt:58`, `CameraDao.kt:40`), `BLOCK_RENDER_SHELL` (`AppShellScreen.kt:52`).
- Pattern: one `Timber.d("[Module][function][BLOCK_NAME] …")` line inside each block; the same marker string appears in `docs/verification-plan.xml`.

## Entry Points

**Application process:**

- Location: `app/src/main/java/com/yicamalt/YiCamAltApp.kt:35`
- Triggers: Android process start.
- Responsibilities: Hilt graph bootstrap; in `BuildConfig.DEBUG` only, plant a `DebugTree` and two `FileLogTree` sinks (`filesDir/auth_log.txt` and `getExternalFilesDir(null)/auth_log.txt` for root-less USB pulls).
- Registered in `app/src/main/AndroidManifest.xml:9` as `android:name=".YiCamAltApp"`.

**Single activity:**

- Location: `app/src/main/java/com/yicamalt/MainActivity.kt:26`
- Triggers: `MAIN`/`LAUNCHER` intent (`app/src/main/AndroidManifest.xml:20-23`).
- Responsibilities: inject `AuthRepository`, seed `loggedIn` from `auth.getSession() != null`, and pick one of three roots inside `BLOCK_RENDER_HOST` (`:32`): `SettingsScreen` (pre-login gear) → `AppShellScreen` (post-login) → `LoginScreen`.

**Hilt provider entry points (all `@InstallIn(SingletonComponent::class)`):**

- `network/HttpClientModule.kt:58` `NetworkModule` — `Retrofit`, `OkHttpClient`
- `auth/AuthModule.kt:28` `AuthModule` (@Binds) and `:49` `AuthProvidersModule` (@Provides)
- `database/DatabaseModule.kt:30` `DatabaseModule` — `YiDatabase` + 3 DAOs
- `camera/CameraModule.kt:28` `CameraBindsModule` (@Binds) and `:36` `CameraModule` (@Provides)
- `event/EventModule.kt:28` `EventBindsModule` (@Binds) and `:36` `EventModule` (@Provides)
- `config/ConfigModule.kt:42` `SettingsStoreModule` (@Binds)

**Remote entry points (Retrofit):**

- `GET v4/users/login`, `POST v4/users/auth_token` — `auth/YiCloudAuthApi.kt:62,72` (confirmed on device)
- `GET v5/devices/list` — `camera/YiCloudDeviceApi.kt:40` (confirmed on device)
- `POST v8/device/control` — `camera/YiCloudCommandApi.kt:24` (**provisional**)
- `GET v1/camera/event/list`, `GET v1/camera/event/playback` — `event/YiCloudEventApi.kt:53,65` (**provisional**, constants in `EventPaths`)

**Local entry points (Room):** `CameraDao`, `EventDao`, `AppMetaDao` in `app/src/main/java/com/yicamalt/database/`.

**Debug entry point:** `debug/DeviceSignProbe.kt:43` `runAll()`, triggered from `ui/settings/SettingsViewModel.kt:55`.

## Architectural Constraints

- **Threading:** Single Android main thread plus `Dispatchers.IO` for every blocking call. Facades wrap public methods in `withContext(Dispatchers.IO)` (`CameraRegistry.kt:58`, `EventServiceModule.kt:82`, `CameraCommandService.kt:80`). Room DAOs and OkHttp are therefore never touched from the main thread. Compose `LazyColumn` and `collectAsState` are main-thread only.
- **Global state (module-level singletons):** `AuthRepository.sessionRef` (`@Volatile`, `auth/AuthRepository.kt:74`) and `AuthRepository.refreshInFlight` (`AtomicReference`, `:75`); `HttpClientModule.retrofit` (mutable, `network/HttpClientModule.kt:158`), `.currentToken` (`AtomicReference`, `:80`), `.refreshGuard` (`AtomicBoolean`, `:113`), and `.logSink` (nullable, test seam, `:76`); `YiCamAltApp`'s planted Timber trees. All are `@Singleton` — mutating them from a ViewModel (`SettingsViewModel.kt:49`) is legal but is the one place where a screen reaches past the DI boundary.
- **Hilt dependency cycle (intentional, lazily broken):** `HttpClientModule` needs `AuthProvider` (`network/HttpClientModule.kt:72`), which is `AuthRepository`, which needs `YiCloudAuthApi` (`auth/AuthRepository.kt:69`), which is created from the `Retrofit` provided by `NetworkModule` (`network/HttpClientModule.kt:62`). The cycle is broken only by `javax.inject.Provider<AuthProvider>` laziness (`:72`) plus the `authInterceptor`'s `auth_token` bypass (`:87`). Do not change `Provider` to a direct dependency.
- **`runBlocking` bridges:** `getAccessTokenBlocking()` and `refreshBlocking()` (`network/HttpClientModule.kt:186-191`) block a thread to call `suspend` members from the synchronous interceptor/authenticator path. The auth interceptor therefore runs `runBlocking` on an OkHttp dispatcher thread for every non-`auth_token` call.
- **Retrofit is a mutable singleton:** `rebuild()` (`network/HttpClientModule.kt:174`) swaps the instance after construction. Services created by `retrofit.create(...)` *before* the rebuild keep pointing at the old base URL if Hilt already instantiated them as singletons — only newly created clients pick up the new URL.
- **Unconfirmed protocol surfaces are load-bearing:** `EventPaths.EVENT_LIST`/`PLAYBACK` (`event/YiCloudEventApi.kt:47-48`), `POST v8/device/control` (`camera/YiCloudCommandApi.kt:24`), and the `CameraListParser`/`EventTimelineParser` key lists are guesses documented as `DEVIATION`/`PROVISIONAL` in `docs/knowledge-graph.xml`. `EventPaths` exists purely so a live capture edits one object.
- **Vendor HMAC key is hardcoded:** `AUTH_HMAC_KEY` at `auth/AuthRepository.kt:48` is a constant extracted from the official Yi Home dex (`docs/PROJECT_STATUS.md` §14). It cannot rotate, and removing it breaks login outright.
- **Logging is a build-type-gated global:** `YiCamAltApp.kt:40` plants trees only under `BuildConfig.DEBUG`, so `Timber` is a no-op in release — trace assertions are impossible in a release build.
- **Redaction is enforced by test, not by type:** `app/src/test/java/com/yicamalt/test/RedactionScanner.kt` scans captured traces for tokens, passwords, AES keys, and 10+ digit runs (Yi serials). `EventDao.kt:62` was rewritten to drop `event_id`/`device_id` because of it.

## Anti-Patterns

### Concrete facade injected into the Activity host

**What happens:** `MainActivity.kt:28` injects `AuthRepository` and calls `auth.getSession()` / `auth.logout()` directly, instead of a port.
**Why it's wrong here:** It is the only screen-side dependency that skips the `Port` rule every other ViewModel follows, so `MainActivity` compiles against the auth module's concrete class and breaks whenever `AuthRepository` is renamed or split.
**Do this instead:** Add a narrow port to `auth/AuthRepository.kt` (alongside `LoginPort`) — e.g. `SessionPort { fun currentSession(): AuthSession?; fun logout() }` — bind it in `auth/AuthModule.kt`, and inject the interface in `MainActivity`.

### Fully-qualified Composable calls instead of imports

**What happens:** `AppShellScreen.kt:87-88` calls `com.yicamalt.ui.cameras.CameraListScreen()` and `com.yicamalt.ui.settings.SettingsScreen(onBack = {})` inline, and `MainActivity.kt:37-51` calls all three roots fully qualified.
**Why it's wrong here:** The shell's tab switch becomes unreadable, IDE "go to definition" is defeated, and adding a third tab means editing an inline FQN instead of an import plus an enum case.
**Do this instead:** Import the composables and extend the `ShellTab` enum (`AppShellScreen.kt:43-46`) — a new tab is one enum constant.

### Raw `JsonElement` kept all the way to the domain value

**What happens:** `LoginResponse.data` (`auth/YiCloudAuthApi.kt:58`), `DeviceListEnvelope.data` (`camera/YiCloudDeviceApi.kt:36`), and `EventListEnvelope.data` (`event/YiCloudEventApi.kt:42`) are all `JsonElement?`, and `ResponseTokens.walk` (`auth/AuthRepository.kt:219`) recurses to depth 4 looking for anything token-shaped.
**Why it's wrong here:** It silently accepts a *wrong* envelope: if the server renames `access_token`, the walker still finds some other key and login "succeeds" with a non-token, rather than failing loudly. It also costs a JSON re-serialise on the event playback path (`EventServiceModule.kt:185`).
**Do this instead:** Keep the tolerance while endpoints are provisional, but once `docs/PROJECT_STATUS.md` records a confirmed envelope for an endpoint, replace its parser with a `@Serializable` DTO and delete the candidate-key list — that is the documented exit condition in `camera/CameraInfo.kt:24-27` and `event/EventInfo.kt:29-33`.

### Duplicated HMAC-SHA1 signing

**What happens:** `CameraRegistry.hmacSha1Base64` (`camera/CameraRegistry.kt:88`) and `EventSigner.hmacSha1Base64` (`event/YiCloudEventApi.kt:81`) are byte-identical implementations with the same algorithm and key composition, and `DeviceSignProbe.mac` (`debug/DeviceSignProbe.kt:51`) is a third copy.
**Why it's wrong here:** The signing scheme is reverse-engineered and version-sensitive; a fix to one copy silently misses the other two, and the probe's divergence is invisible because it lives in a different package.
**Do this instead:** Collapse all three into one `internal object RequestSigner` in `network/` (or an internal member on `HttpClientModule`) exposing `hmacSha1Base64(key, msg)` and `canonicalMessage(userId, seq)`. `event/YiCloudEventApi.kt:32-35` already names this as the intended target.

### Mutable singleton Retrofit reached from a ViewModel

**What happens:** `SettingsViewModel.kt:49` calls `httpClient.rebuild()` on the injected `HttpClientModule` singleton.
**Why it's wrong here:** It gives a screen a write handle on shared infrastructure, and the swap only affects `Retrofit` instances built after the call — an already-created `YiCloudAuthApi` keeps the old base URL, so the "save" appears to work for some calls and not others.
**Do this instead:** Inject `ConfigModule` + a `Provider<Retrofit>` (or an `ApiHost` interface exposing `rebuild()` and `retrofit()`) so the settings screen depends on a contract, and create Retrofit services through the provider so every caller observes the new base URL.

### Dead marker field used as documentation

**What happens:** `config/ConfigModule.kt:58` `private val initializedOnce: Unit = Unit // marker: BuildConfig read exactly once at construction`.
**Why it's wrong here:** It allocates a field, reads no state, and lints as unused; the invariant it claims is already expressed by `apiBaseUrl`/`localRtspEnabled` being `val`s assigned from `BuildConfig` at construction.
**Do this instead:** Delete the field and put the invariant in the `MODULE_CONTRACT` comment block or in `docs/knowledge-graph.xml`; if it must be testable, assert it in `app/src/test/java/com/yicamalt/config/ConfigModuleTest.kt`.

## Error Handling

**Strategy:** Every layer converts foreign failures into a module-owned sealed error; the UI maps those errors to copy.

**Patterns:**

- **Domain API rejection** — the Yi envelope carries its own status. `code` is compared against a module-local pair: `SUCCESS_CODES = setOf("20000", "20200")` and `AUTH_ERROR_CODES = setOf("20201","20202","20203","20205","20253","40110")` (`camera/CameraRegistry.kt:83`, `event/EventServiceModule.kt:252`). Unknown codes fall through to `FetchFailed` rather than being treated as success.
- **Transport failure** — `retrofit2.HttpException` with `code() == 401` maps to `Unauthorized`; every other `HttpException` and any `IOException` maps to `FetchFailed` (`camera/CameraRegistry.kt:67-72`, `event/EventServiceModule.kt:105-110`).
- **Cache writes are best-effort** — `syncToDb` counts successes with `runCatching { … }.isSuccess` and reports `written=$written of=$events.size` instead of throwing (`event/EventServiceModule.kt:211-225`). A partial cache is a logged condition, not a crash.
- **Corrupt cached payloads never throw** — `EventTimelineParser.extractOne` returns `null` for unparseable JSON (`event/EventInfo.kt:111`), and `withPlaybackUrl` returns the original string when the payload is not an object (`event/EventServiceModule.kt:243`).
- **UI maps the specific case, then `Throwable`** — `LoginViewModel.kt:66-76` and `CameraListViewModel.kt:57-61` catch `AuthError.InvalidCredentials` / `CameraError.Unauthorized` separately and fall back to a generic network message for everything else.
- **Config is validated by throwing** — `ConfigError.MissingKey` on a blank base URL or an unknown flag name (`config/ConfigModule.kt:79`, `:98`).
- **Logging never throws** — `YiCamAltApp.kt:88` swallows `IOException` from the file sink so a failed write cannot crash the app.

## Cross-Cutting Concerns

**Logging:** Timber 5.0.1 with a per-module `internal object` indirection — `AuthLog` (`auth/AuthRepository.kt:264`), `TimberLog` (`config/ConfigModule.kt:108`, `network/HttpClientModule.kt:194`), `DbLog` (`database/DbLog.kt:13`), `LoginLog` (`ui/login/LoginViewModel.kt:86`). The indirection exists so `TraceRecorder` (`app/src/test/java/com/yicamalt/test/TraceRecorder.kt`) can capture markers without tests depending on Timber tree state. Format is strictly `[Module][function][BLOCK_NAME] message key=value`; secrets are masked with `██` (U+2588) and validated by `RedactionScanner`.

**Validation:** Two distinct kinds. (1) Contract validation at the boundary: `ConfigModule` checks blank/known keys; `ResponseTokens`/`CameraListParser`/`EventTimelineParser` drop entries with no usable primary key rather than inventing one. (2) Command validation before dispatch: `CameraCommandService.dispatch` (`camera/CameraCommandService.kt:80`) checks camera existence, online flag, and model PTZ capability, raising `CameraCommandError.UnsupportedModel` / `.CameraOffline` before any network call.

**Authentication:** Two mechanisms coexist deliberately. (1) `Authorization: Bearer` via the OkHttp interceptor, with a 401 authenticator that refreshes once and replays (`network/HttpClientModule.kt:82-126`). (2) Query-parameter `hmac` signing, `Base64(HMAC-SHA1("<accessToken>&<token_secret>", "seq=1&userid=<uid>"))`, used by the device and event APIs which do **not** accept the bearer header (`camera/YiCloudDeviceApi.kt:26-29`). Login itself uses a third scheme: `Base64(HMAC-SHA256(password))` under a vendor key (`auth/AuthRepository.kt:176`).

**Persistence:** Two stores with different guarantees. `EncryptedSharedPreferences` ("yicamalt_auth", AES256_SIV keys / AES256_GCM values, Keystore master key) for the session (`auth/AuthStore.kt:41-50`); plain `SharedPreferences` ("yicamalt_settings") for the API base URL override (`config/SettingsStore.kt:40`); Room `yicamalt.db` v1 with `fallbackToDestructiveMigration()` (`database/DatabaseModule.kt:45`) for the camera and event caches.

---

*Architecture analysis: 2026-09-28*
