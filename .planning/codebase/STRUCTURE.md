---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
# Codebase Structure

**Analysis Date:** 2026-09-28

## Directory Layout

```text
E:/Dev/YiCamAlt/
├── app/                                  # the single Gradle application module
│   ├── build.gradle.kts                  # plugins, SDK levels, BuildConfig fields, deps
│   ├── proguard-rules.pro                # release shrinker rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # 3 permissions, 1 Application, 1 Activity
│       │   └── java/com/yicamalt/         # ALL runtime Kotlin lives here
│       │       ├── YiCamAltApp.kt         # @HiltAndroidApp + FileLogTree
│       │       ├── MainActivity.kt        # @AndroidEntryPoint, the only Activity
│       │       ├── auth/                  # M-AUTH
│       │       ├── camera/                # M-CAMERA-LIST + M-CAMERA-CMD
│       │       ├── config/                # M-CONFIG
│       │       ├── database/              # M-LOCAL-DB
│       │       ├── debug/                 # DeviceSignProbe (no GRACE module id)
│       │       ├── event/                 # M-EVENT
│       │       ├── network/               # M-HTTP
│       │       └── ui/                    # all presentation (LAYER=2)
│       │           ├── login/  settings/  shell/  cameras/
│       ├── test/java/com/yicamalt/        # JVM unit tests, mirror the main packages
│       │   ├── test/                      # shared harnesses: TraceRecorder, fakes, RedactionScanner
│       │   └── auth/ camera/ config/ database/ event/ network/ ui/
│       └── androidTest/java/com/yicamalt/ # instrumented Compose tests (ui/login only)
├── docs/                                 # GRACE governance — the project's source of truth
│   ├── requirements.xml  technology.xml
│   ├── development-plan.xml               # M-* module ids, LAYER/ORDER, phases + steps
│   ├── verification-plan.xml              # V-M-* verification ids, scenarios, markers
│   ├── knowledge-graph.xml                # M-* records with real file <path> values
│   ├── operational-packets.xml
│   ├── PROJECT_STATUS.md                  # reverse-engineering journal (newest evidence first)
│   └── logs/classes8.dex.jadx             # decompilation evidence for classes8.dex
├── _apk/                                 # third-party vendor APKs + extracted splits (evidence)
├── tools/gradle.ps1                       # gradle wrapper for PowerShell
├── gradle/wrapper/                        # committed Gradle wrapper
├── build.gradle.kts                       # root: plugin versions only, all `apply false`
├── settings.gradle.kts                    # single-module build: include(":app")
├── gradle.properties                      # JVM args, truststore, AndroidX flags
├── local.properties                       # SDK path — git-ignored
├── AGENTS.md / INSTRUCTIONS.md            # GRACE rules, load-bearing for every edit
└── .planning/                             # GSD planning state (codebase docs live in .planning/codebase/)
```

## Directory Purposes

**`app/src/main/java/com/yicamalt/` (root):**

- Purpose: process-level entry points. Everything below is a GRACE module package.
- Contains: `YiCamAltApp.kt`, `MainActivity.kt` — 2 files, both with full `MODULE_CONTRACT` headers.
- Key files: `app/src/main/java/com/yicamalt/YiCamAltApp.kt`, `app/src/main/java/com/yicamalt/MainActivity.kt`

**`auth/` (M-AUTH):**

- Purpose: Yi Cloud login, token refresh, session lifecycle, secure session storage.
- Contains: 1 facade + 4 contracts, 1 Retrofit interface + 3 DTOs, 1 store interface + 1 impl, 1 Hilt module, 1 value/error file.
- Key files: `app/src/main/java/com/yicamalt/auth/AuthRepository.kt` (facade + `LoginPort`/`SessionSource`/`Clock`/`Tokens`/`ResponseTokens` — 266 lines, the densest file in the package), `auth/AuthModule.kt`, `auth/YiCloudAuthApi.kt`, `auth/AuthStore.kt`, `auth/AuthSession.kt`

**`camera/` (M-CAMERA-LIST + M-CAMERA-CMD):**

- Purpose: fetch/cache the camera registry, resolve stream URLs, validate + dispatch device commands.
- Contains: 2 facades, 2 Hilt modules, 2 Retrofit interfaces, 2 parser/model files, 1 transport seam.
- Key files: `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt`, `camera/CameraCommandService.kt`, `camera/CameraInfo.kt` (`CameraInfo` + `CameraListParser`), `camera/CommandModels.kt` (`CameraCommandType`/`CameraCommand`/`PtzNormalizer`/`CommandPayload`), `camera/CameraModule.kt`, `camera/YiCloudDeviceApi.kt`, `camera/YiCloudCommandApi.kt`
- Note: two GRACE modules share this package. `M-CAMERA-LIST` maps to `CameraRegistry.kt` and `M-CAMERA-CMD` to `CameraCommandService.kt`; neither knows about the other's file, and `CameraCommandService` reads `CameraDao` directly rather than `CameraRegistry`.

**`config/` (M-CONFIG):**

- Purpose: BuildConfig snapshot, feature flags, runtime API base URL override, settings persistence.
- Contains: `ConfigModule` (config + `ConfigError` + `FeatureFlag` + `SettingsStoreModule`), `SettingsStore` (+ `PrefsSettingsStore`, `InMemorySettingsStore`).
- Key files: `app/src/main/java/com/yicamalt/config/ConfigModule.kt`, `config/SettingsStore.kt`

**`database/` (M-LOCAL-DB):**

- Purpose: Room database for the camera cache, event cache, and app key/value metadata.
- Contains: 1 database class, 3 entities, 3 DAOs, 1 Hilt provider module, 1 log shim.
- Key files: `app/src/main/java/com/yicamalt/database/YiDatabase.kt`, `database/DatabaseModule.kt`, `database/CameraDao.kt`, `database/EventDao.kt`, `database/AppMetaDao.kt`, `database/DbLog.kt`
- Note: both DAO-bearing files use the `abstract class` + `open fun` wrapper pattern (`insertCameraRaw`/`insertCamera`) specifically so the `BLOCK_DB_WRITE` log line can be emitted; `AppMetaDao.kt` is the only `interface` DAO because it needs no marker.

**`debug/`:**

- Purpose: on-device oracle for reverse-engineering the signed device API. No GRACE module id — it is infrastructure for `docs/PROJECT_STATUS.md`, not a product module.
- Contains: 1 file.
- Key files: `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt`

**`event/` (M-EVENT):**

- Purpose: paginated event timeline, Room cache sync, playback-URL resolution.
- Contains: 1 facade, 1 Hilt module + 1 binds module, 1 Retrofit interface + `EventPaths` + `EventSigner`, 1 model/parser file.
- Key files: `app/src/main/java/com/yicamalt/event/EventServiceModule.kt` (the facade — the file name means "facade", not "Hilt module"), `event/EventInfo.kt` (`EventInfo`/`EventPage`/`ParsedTimeline`/`EventTimelineParser`), `event/YiCloudEventApi.kt`, `event/EventModule.kt`
- Warning: the naming collision is real — `EventServiceModule.kt` is the facade class `EventServiceModule`; `EventModule.kt` is the Hilt module. Read the `PURPOSE` header before editing either.

**`network/` (M-HTTP):**

- Purpose: the single OkHttp/Retrofit stack, auth interceptor, redacted logging, 401 refresh+retry, `AuthProvider` contract.
- Contains: `HttpClientModule` (singleton) + `NetworkModule` (Hilt) + `HttpError` + `TimberLog` + `AuthProvider` — 2 files.
- Key files: `app/src/main/java/com/yicamalt/network/HttpClientModule.kt` (196 lines; the only place interceptors are configured), `network/AuthProvider.kt`

**`ui/` (all LAYER=2 UI_COMPONENT modules):**

- Purpose: Compose surfaces and their ViewModels. No domain, no IO types.
- Contains: 4 sub-packages, 2 files each (screen + ViewModel) except `ui/shell` (1 file).
- Key files: `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt` (the `ShellTab` enum is the de-facto navigation registry), `ui/login/LoginScreen.kt` + `ui/login/LoginViewModel.kt`, `ui/cameras/CameraListScreen.kt` + `ui/cameras/CameraListViewModel.kt`, `ui/settings/SettingsScreen.kt` + `ui/settings/SettingsViewModel.kt`

**`app/src/test/java/com/yicamalt/test/`:**

- Purpose: cross-module test harnesses shared by every unit test. Not a product package.
- Contains: `TraceRecorder` (captures `[Module][fn][BLOCK]` markers from Timber), `RedactionScanner` (rejects tokens/passwords/AES keys/10+ digit runs in captured traces), `FakeAuthStore`, `InMemoryRoom`, `FakeYiApi`, `FakeCameraStreamServer`, `StreamHandleFake`, `TestInfrastructureSmokeTest`.
- Key files: `app/src/test/java/com/yicamalt/test/TraceRecorder.kt`, `app/src/test/java/com/yicamalt/test/RedactionScanner.kt`
- Note: `FakeCameraStreamServer.kt` and `StreamHandleFake.kt` target `M-STREAM-LIVE`, which has no implementation yet — the harness is ahead of the module.

**`docs/`:**

- Purpose: GRACE governance artifacts. `development-plan.xml` (module ids, layers, phases, steps), `verification-plan.xml` (`V-M-*` scenarios, log markers, commands), `knowledge-graph.xml` (`CrossLink` edges + per-module real file paths), `PROJECT_STATUS.md` (the reverse-engineering journal — read before trusting any endpoint path or request shape), `operational-packets.xml` (canonical packet/delta templates).
- Contains: 7 files + `logs/`.
- Key files: `docs/development-plan.xml`, `docs/verification-plan.xml`, `docs/knowledge-graph.xml`, `docs/PROJECT_STATUS.md`

## Key File Locations

**Entry Points:**

- `app/src/main/java/com/yicamalt/YiCamAltApp.kt`: Hilt bootstrap, Timber + `FileLogTree` sinks (debug only).
- `app/src/main/java/com/yicamalt/MainActivity.kt`: the only Activity; three-way `when` picks Settings / Shell / Login.
- `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt`: post-login bottom-nav host; `ShellTab` at line 43 is where a new tab goes.
- `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt`: debug entry point for the API-signature probe.

**Configuration:**

- `app/build.gradle.kts`: SDK 34 / min 26, JVM 17, Compose compiler 1.5.8, `buildConfigField` for `YI_API_BASE_URL` and `LOCAL_RTSP_ENABLED`, and the JUnit5 `useJUnitPlatform()` + truststore block.
- `build.gradle.kts` (root): plugin version catalog; every plugin is `apply false`.
- `settings.gradle.kts`: `include(":app")` — single-module build, no multi-module split.
- `gradle.properties`: `-Xmx2048m`, parallel + caching on, configuration-cache off, the SSL truststore system properties.
- `app/proguard-rules.pro`: release shrinker rules.

**Core Logic:**

- `app/src/main/java/com/yicamalt/auth/AuthRepository.kt`: login/refresh/logout + the 4 contracts implemented by it.
- `app/src/main/java/com/yicamalt/network/HttpClientModule.kt`: interceptors, authenticator, redacted logging, `rebuild()`.
- `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt`: signed device-list fetch + cache refresh.
- `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt`: command validation + dispatch.
- `app/src/main/java/com/yicamalt/event/EventServiceModule.kt`: event fetch/sync/playback-URL.
- `app/src/main/java/com/yicamalt/config/ConfigModule.kt`: flags, base URL, `ConfigError`.

**Testing:**

- `app/src/test/java/com/yicamalt/<module>/…Test.kt`: mirrors the main package, one `*Test` per module class.
- `app/src/test/java/com/yicamalt/test/`: shared harnesses (see above).
- `app/src/androidTest/java/com/yicamalt/ui/login/LoginScreenTest.kt`: the only instrumented test; requires an emulator, currently `deferred`.
- `docs/verification-plan.xml`: the authoritative list of `V-M-*` gates and their commands.

**Governance (read before editing any source file):**

- `AGENTS.md`: contract-before-code rule, semantic markup rules, XML unique-tag convention.
- `docs/development-plan.xml`, `docs/knowledge-graph.xml`, `docs/verification-plan.xml`: must be updated together when a module, export, test, or log marker changes.

## Naming Conventions

**Files:**

- `PascalCase.kt`, named after the file's primary public type: `AuthRepository.kt` → `AuthRepository`, `CameraListScreen.kt` → `CameraListScreen`.
- **Do not infer a type's role from `*Module.kt`.** `EventServiceModule.kt` is a *facade*, `EventModule.kt` is the *Hilt module*, `ConfigModule.kt` is *both* (config + `SettingsStoreModule`), `HttpClientModule.kt` is the *HTTP singleton*, `AuthModule.kt`/`CameraModule.kt` are *Hilt modules*. Read the `PURPOSE` line of the `MODULE_CONTRACT` header.
- Retrofit service interfaces: `YiCloud<Domain>Api.kt` → `YiCloudAuthApi`, `YiCloudDeviceApi`, `YiCloudCommandApi`, `YiCloudEventApi`.
- Domain value types: `XxxInfo.kt` → `CameraInfo`, `EventInfo` (never `XxxModel`, never `XxxDto` — DTOs are `@Serializable data class`es inside the `*Api.kt` file).
- Multi-type files group one concern: `CameraInfo.kt` holds `CameraInfo` + `CameraListParser`; `EventInfo.kt` holds `EventInfo` + `EventPage` + `ParsedTimeline` + `EventTimelineParser`; `CommandModels.kt` holds 4 types; `AuthStore.kt` holds the interface + impl; `AuthRepository.kt` holds the facade + 5 helpers.
- Test doubles in `app/src/test/java/com/yicamalt/test/`: `Fake<Thing>` for behavioural fakes (`FakeAuthStore`, `FakeYiApi`, `StreamHandleFake`), `InMemory<Thing>` for in-memory adapters (`InMemoryRoom`), `<Thing>Fake` only where the `Fake` prefix would read badly (`StreamHandleFake`).

**Functions:**

- `camelCase`; boolean-returning functions use `is`/`has`/`supports`/`can` prefixes: `isLocalRtspEnabled()` (`config/ConfigModule.kt:93`), `supportsPtz()` (`camera/CameraCommandService.kt:109`), `canSubmit()` (`ui/login/LoginViewModel.kt:49`), `resolveHasMore()` (`event/EventServiceModule.kt:132`).
- Hilt provider/bind functions repeat the bound type: `provideYiCloudAuthApi`, `bindAuthProvider`, `bindCameraListPort` (`auth/AuthModule.kt:51`, `:32`, `camera/CameraModule.kt:40`, `:31`).
- Interfaces read as capabilities, not implementations: `getAccessToken()`, `getCameraList()`, `getEventTimeline()`, `putSession()`, `send(payload)`.
- Pure computation gets a named `internal object` (never a top-level function): `PtzNormalizer`, `ResponseTokens`, `EventSigner`, `EventTimelineParser`, `CameraListParser`, `Tokens`, `AuthLog`, `DbLog`, `LoginLog`, `TimberLog`, `EventPaths`, `FeatureFlag`.
- Two explicit bridge helpers for the sync interceptor path, both suffixed `Blocking`: `getAccessTokenBlocking()`, `refreshBlocking()` (`network/HttpClientModule.kt:186`, `:190`).

**Variables:**

- `camelCase` for locals and properties; `private val` for injected dependencies.
- State flows follow `_state` (private `MutableStateFlow`) + `state` (public `StateFlow`): `ui/login/LoginViewModel.kt:45-46`, `ui/cameras/CameraListViewModel.kt:41-42`.
- Booleans read as predicates: `localRtspEnabled`, `isMinifyEnabled`, `hasMore`, `explicitHasMore`, `bearer`, `refreshed`, `acked`.

**Types:**

- Types: `PascalCase`; sealed hierarchies `XxxError` (all extend `Error`, all `sealed class` with `object` cases) and `XxxUiState` (all `sealed interface` with `data object`/`data class` cases).
- Contracts: `XxxPort` (feature → UI), `XxxProvider` (auth → HTTP), `XxxSource` (auth → domain signing), `XxxTransport` (delivery seam), `XxxStore` (persistence).
- Entities/DAOs use snake_case field names to match the wire/SQL: `CameraEntity.device_id`, `EventEntity.payload_json`, `CommandPayload.device_id` — the Room/Retrofit `@SerialName` names are the source of truth.
- The wall-clock abstraction is always `Clock` with a `SystemClock` companion object.

**Constants:**

- `SCREAMING_SNAKE_CASE` `const val` for module-level and object-level constants: `AUTH_HMAC_ALGORITHM`/`AUTH_HMAC_KEY`/`DEFAULT_EXPIRES_SECONDS` (`auth/AuthRepository.kt:47-55`), `PAGE_SIZE`/`DEFAULT_WINDOW_MS` (`event/EventServiceModule.kt:250-251`), `MILLIS_FLOOR`/`DEFAULT_TYPE` (`event/EventInfo.kt:98`, `:201`), `KEY_ACCESS`… (`auth/AuthStore.kt:78`), `KEY_API_BASE_URL` (`config/SettingsStore.kt:50`), `EventPaths.EVENT_LIST`/`PLAYBACK` (`event/YiCloudEventApi.kt:47-48`).
- Non-const fixed tables are `private val` sets/lists in `UPPER_SNAKE` or `UpperCamel`: `FeatureFlag.LOCAL_RTSP` (`config/ConfigModule.kt:34`), `AUTH_ERROR_CODES`/`SUCCESS_CODES` (`camera/CameraRegistry.kt:83-84`), `ID_KEYS`/`THUMB_KEYS`/`LIST_KEYS` (`event/EventInfo.kt:73-95`), `ACCESS_KEYS`/`REFRESH_KEYS` (`auth/AuthRepository.kt:200-203`).
- Enums: `UPPER_SNAKE` cases — `CameraCommandType.PTZ`, `AuthMethod.PASSWORD`, `ShellTab.Cameras`, `Error.Patch` variants.

**Log lines and semantic blocks:**

- Marker shape: `// START_BLOCK_<UPPER_SNAKE>` … `// END_BLOCK_<UPPER_SNAKE>`, paired and uniquely named per file.
- Block names are prefixed by intent, not by function: `BLOCK_VALIDATE_CREDENTIALS`, `BLOCK_STORE_TOKEN`, `BLOCK_REFRESH_RETRY`, `BLOCK_FETCH_CAMERA_LIST`, `BLOCK_SEND_COMMAND`, `BLOCK_SYNC_TO_DB`, `BLOCK_RENDER_SHELL`, `BLOCK_NAV_ON_SUCCESS`, `BLOCK_INIT_DB`, `BLOCK_DB_WRITE`, `BLOCK_REDACTED_LOG`.
- Log line shape: `"[<Module>][<function>][<BLOCK_NAME>] <message> key=value key=value"`.
- Files without a `MODULE_MAP` line per type still use a `// START_MODULE_MAP` block listing each exported symbol with a one-line description — keep it in sync when exports change.

**Packages:**

- Domain packages are lowercase **singular** feature slugs: `auth`, `camera`, `config`, `database`, `debug`, `event`, `network`.
- UI packages are lowercase **screen slugs**: `login`, `settings`, `shell` (singular) but `cameras` (plural) — the one inconsistency in the tree; match the existing package for an existing screen, and prefer singular for new ones.
- Feature test packages mirror the main package exactly (`app/src/test/java/com/yicamalt/event/EventServiceModuleTest.kt` ↔ `app/src/main/java/com/yicamalt/event/EventServiceModule.kt`).

## Where to Add New Code

**New cloud capability / domain module:**

- Facade + contracts: `app/src/main/java/com/yicamalt/<slug>/<Feature>Service.kt` (name it `…Service` or `…Module`; both exist — pick the one that reads better and state the choice in the `PURPOSE` line).
- Hilt wiring: `app/src/main/java/com/yicamalt/<slug>/<Feature>Module.kt` — split into an `abstract class` for `@Binds` and an `object` for `@Provides`, exactly as `camera/CameraModule.kt:28-36` does.
- Retrofit interface + DTOs: `app/src/main/java/com/yicamalt/<slug>/YiCloud<Domain>Api.kt`; if the path is unconfirmed, add a `XxxPaths` object like `event/YiCloudEventApi.kt:46` so a capture edits one place.
- Value types + tolerant parser: `app/src/main/java/com/yicamalt/<slug>/<Feature>Info.kt`.
- Tests: `app/src/test/java/com/yicamalt/<slug>/<Feature>ServiceTest.kt`.
- Governance: add `<M-SLUG …>` to `docs/development-plan.xml` (with `LAYER`, `ORDER`, `STATUS`) and to `docs/knowledge-graph.xml` (with the real `<path>`), plus `<V-M-SLUG>` in `docs/verification-plan.xml`.

**New screen:**

- `app/src/main/java/com/yicamalt/ui/<screen>/<Screen>Screen.kt` — stateless `@Composable`, default arg `viewModel: <Screen>ViewModel = hiltViewModel()`, read state via `collectAsState()`.
- `app/src/main/java/com/yicamalt/ui/<screen>/<Screen>ViewModel.kt` — `@HiltViewModel`, inject a **port** (not the facade), expose `sealed interface <Screen>UiState` with a `Loading`/`Content`/`Error` shape mirroring `ui/cameras/CameraListViewModel.kt:30-34`.
- Register the screen: add a `ShellTab` case in `app/src/main/java/com/yicamalt/ui/shell/AppShellScreen.kt:43-46` and a branch in the `when` at `:86-89`. For a pre-login or full-screen route, extend the `when` in `app/src/main/java/com/yicamalt/MainActivity.kt:36-52` instead.
- Instrumented test (needs an emulator): `app/src/androidTest/java/com/yicamalt/ui/<screen>/<Screen>ScreenTest.kt`, following `LoginScreenTest.kt`.

**New capability the UI needs but the facade does not expose:**

- Add the method to the **facade** first (`AuthRepository`, `CameraRegistry`, `EventServiceModule`) with its own `START_BLOCK_*` log marker, then add/extend the port in the same package, then bind it if it is a new interface, then have the ViewModel call the port. Never call the facade directly from a ViewModel.

**New Room table:**

- `app/src/main/java/com/yicamalt/database/<Name>Entity.kt` (snake_case column properties), `<Name>Dao.kt`, then add to `entities = [...]` in `app/src/main/java/com/yicamalt/database/YiDatabase.kt:21` and add `@Provides fun provide<Name>Dao` in `app/src/main/java/com/yicamalt/database/DatabaseModule.kt:48-50`. Bump `version` in `YiDatabase.kt:22` — `fallbackToDestructiveMigration()` means existing installs lose data on any bump.

**New camera command:**

- Add the case to `CameraCommandType` in `app/src/main/java/com/yicamalt/camera/CommandModels.kt:20`, add a branch to `commandParams` in `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:102`, add a `suspend fun` on the service, and add any capability check to `dispatch` (`:80`).

**New feature flag:**

- Add the name to `FeatureFlag` in `app/src/main/java/com/yicamalt/config/ConfigModule.kt:33-38` **and** an entry to the `featureFlags` map at `:61-66`, then read it with `flag(name)` or a typed accessor. Both halves must change together or `flag()` throws `ConfigError.MissingKey`.

**New shared test harness:**

- `app/src/test/java/com/yicamalt/test/<Name>.kt`, and prove it compiles in `app/src/test/java/com/yicamalt/test/TestInfrastructureSmokeTest.kt` before relying on it in another module.

## Special Directories

**`docs/`:**

- Purpose: GRACE governance — requirements, technology decisions, development plan, verification plan, knowledge graph, operational-packet templates, and the reverse-engineering journal.
- Generated: No — hand-edited XML/Markdown. `docs/knowledge-graph.xml` records each module's **real** file `<path>`; if the source moves, the graph must move with it.
- Committed: Yes. These files gate every change per `AGENTS.md`.

**`_apk/`:**

- Purpose: the vendor APK under reverse engineering — `Yi+Home_6.9.8_….xapk`, an `.apkm` bundle, and `extracted/` with `base.apk` plus ~40 `split_config.*.apk` splits. This is the evidence behind every "confirmed on device" claim in `docs/PROJECT_STATUS.md`.
- Generated: Partly — `extracted/` is the output of unzipping the bundle.
- Committed: **Yes** (~400 MB across the bundle and extracted splits). This is a deliberate evidence archive, not build output; treat it as read-only and never add to it from a build.

**`docs/logs/`:**

- Purpose: decompilation output used as protocol evidence (currently `classes8.dex.jadx`).
- Generated: Yes — by JADX against `_apk/extracted/base.apk`.
- Committed: Yes.

**`app/build/`, `build/`, `.gradle/`:**

- Purpose: Gradle build output and caches.
- Generated: Yes.
- Committed: No — listed in `.gitignore` (`build/`, `.gradle/`).

**`local.properties`:**

- Purpose: Android SDK path.
- Generated: Yes.
- Committed: No — in `.gitignore`. Do not read or reproduce its contents in docs.

**`.planning/`:**

- Purpose: GSD planning state; `codebase/` holds the generated map documents (`ARCHITECTURE.md`, `STRUCTURE.md`, …).
- Generated: Partly — `codebase/` documents are generated by the mapper agents; phase/plan files are authored.
- Committed: Yes, by the orchestrator (not by mapping agents).

**`tools/`:**

- Purpose: `gradle.ps1`, a PowerShell Gradle wrapper for environments without a usable `gradlew`.
- Generated: No.
- Committed: Yes.

---

*Structure analysis: 2026-09-28*
