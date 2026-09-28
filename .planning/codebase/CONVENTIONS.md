---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
# Coding Conventions

**Analysis Date:** 2026-09-28

## Naming Patterns

**Files:**

- `PascalCase.kt`, one top-level concept per file, named after the concept — never after the layer.
  `AuthRepository.kt`, `CameraInfo.kt`, `CommandModels.kt`, `TraceRecorder.kt`.
- The file name always repeats the primary public symbol: `CameraDao.kt` declares `CameraDao`, `EventDao.kt` declares `EventDao`.
- Retrofit service interface + its envelope DTO live in **one** file: `YiCloudAuthApi.kt` holds `YiCloudAuthApi` + `LoginRequest`/`RefreshRequest`/`LoginResponse`; `YiCloudEventApi.kt` holds `YiCloudEventApi` + `EventListEnvelope` + `EventPaths` + `EventSigner`.
- Error hierarchies live in the file that owns the value object they guard, not in a separate `Errors.kt`:
  `AuthError` is in `app/src/main/java/com/yicamalt/auth/AuthSession.kt:28`, `CameraError` in `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:38`, `EventError` in `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:52`, `HttpError` in `app/src/main/java/com/yicamalt/network/HttpClientModule.kt:47`, `ConfigError` in `app/src/main/java/com/yicamalt/config/ConfigModule.kt:27`, `CameraCommandError` in `app/src/main/java/com/yicamalt/camera/CameraCommandService.kt:30`.
- Test files: `<Subject>Test.kt` mirroring the tested subject, in the **same package** as production code. `AuthRepositoryTest.kt`, `CameraRegistryModuleTest.kt`, `EventServiceModuleTest.kt`, `YiDatabaseTest.kt`, `HttpClientModuleTest.kt`, `CameraCommandModuleTest.kt`, `ConfigModuleTest.kt`, `LoginViewModelTest.kt`, `CameraListViewModelTest.kt`.

**Functions:**

- `camelCase`, verb-first. Prefer intent-revealing names over `handle`/`process`: `getCameraList`, `cacheCameraList`, `resolveHasMore`, `syncToDb`, `getPlaybackUrl`, `assertMarkerAppeared`, `parseFields`, `firstString`, `collectEventObjects`, `hmacSha1Base64`.
- Booleans take `is`/`has`/`can` prefixes: `isLocalRtspEnabled()`, `isPlaying()`, `isRunning()`, `canSubmit()`, `isStringOrPrimitive()`, `hasMore`, `supportsPtz()`.
- Private extensions for formatting/mapping, not static helpers: `private fun String.redact()`, `private fun CameraEntity.toInfo()`, `private fun CameraInfo.toEntity(now: Long)`, `private fun AuthProvider.getAccessTokenBlocking()`.
- KDoc is one line stating the contract, not a restatement of the signature. See `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:87` and `:114`.

**Variables:**

- `camelCase` locals and params; named arguments are used at every multi-arg call site for anything non-obvious (`deviceId = deviceId`, `hmac = hmac`, `online = true`, `cutoff = 5000L`).
- `UPPER_SNAKE_CASE` for `const val` only, and constants live in a `companion object` or at file top as `internal const val`:
  `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:47` (`AUTH_HMAC_ALGORITHM`, `AUTH_HMAC_KEY`, `DEFAULT_EXPIRES_SECONDS`), `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:249` (`PAGE_SIZE`, `DEFAULT_WINDOW_MS`), `app/src/main/java/com/yicamalt/camera/CommandModels.kt:37` (`MAX_DEGREES`), `app/src/main/java/com/yicamalt/event/EventInfo.kt:98` (`MILLIS_FLOOR`).
- Wire/DB field names stay **snake_case** because Room and the Yi Cloud API both require it: `CameraEntity.device_id`, `EventEntity.payload_json`, `CommandPayload.device_id`, `RefreshRequest.refreshToken` (`@SerialName("refresh_token")`). Domain models stay **camelCase**: `CameraInfo.deviceId`, `AuthSession.accessToken`, `EventInfo.playbackUrl`. Do not "fix" either side.
- Envelope `code` values are compared as `String` throughout (`envelope.code == "20000"`), never parsed to int.

**Types:**

- `data class` for every value object: `AuthSession`, `CameraInfo`, `EventInfo`, `EventPage`, `ParsedTimeline`, `CameraCommand`, `CommandPayload`, `CameraEntity`, `EventEntity`, `AppMetaEntity`.
- `object` for stateless helpers with static behavior: `CameraListParser`, `EventTimelineParser`, `ResponseTokens`, `PtzNormalizer`, `EventSigner`, `EventPaths`, `FeatureFlag`, `SystemClock`.
- `sealed interface` for UI state machines: `LoginUiState` (`Idle | Loading | Success | Error(message)`), `CameraListUiState` (`Loading | Content(cameras) | Error(message)`).
- `sealed class ... : Error(message)` for every failure hierarchy, with `object` (singleton, payload-free) members.
- Enums use `UPPER_SNAKE`: `CameraCommandType { PTZ, IR, SOUND, CAPTURE, RECORDING_MODE }`, `AuthMethod { PASSWORD, CLOUD_OAUTH }`.

## Code Style

**Formatting:**

- **No formatter is enforced.** `docs/technology.xml:33-34` declares `ktlint`/`Detekt`, but neither plugin is applied in `build.gradle.kts` or `app/build.gradle.kts`, and there is no `.editorconfig`. `gradle.properties:14` only sets `kotlin.code.style=official`.
- Match the surrounding file. Observed de-facto rules: 4-space indent, trailing commas in multi-line argument lists, opening brace on the same line, imports never wildcarded, expression bodies for single-expression functions, `return` for early exits inside block-bodied functions.
- Soft line width ~100–110; long log calls are wrapped with a trailing `+` and the closing paren on its own line (see `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:116`).
- Semantic markup comments are `//`-only and sit at column 0, so they visually detach from the code they wrap.

**Linting:**

- None. There is no `:app:lint` in any Phase gate except `Gate-Phase-4` (`docs/verification-plan.xml:826`), and no static-analysis config file in the repo. Correctness is enforced by the JUnit suite plus the two trace/redaction harnesses, not by a linter.

**Semantic markup (the load-bearing convention):**

This is the project's dominant convention. Every one of the 57 `.kt` files opens with exactly this header, before `package`:

```kotlin
// FILE: AuthRepository.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Yi Cloud authentication: login, refresh, logout, session access; implements AuthProvider for M-HTTP.
//   SCOPE: validate credentials, exchange for tokens, persist via AuthStore, refresh on expiry; emit trace markers.
//   DEPENDS: M-CONFIG, M-HTTP (AuthProvider), M-LOCAL-DB (AuthStore)
//   LINKS: M-HTTP, M-UI-LOGIN
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth
```

Rules to follow when adding a file:

- Field names are literal: `PURPOSE`, `SCOPE`, `DEPENDS`, `LINKS`, `ROLE`, `MAP_MODE`. Do not rename or translate them.
- `DEPENDS` and `LINKS` use exact module ids (`M-HTTP`, `M-LOCAL-DB`, `V-M-AUTH`) — no prose references.
- `ROLE` is one of `RUNTIME`, `TEST`, `TYPES`, `DATA`, `UI`, `SCRIPT`, `BARREL`, `CONFIG`. The first five are in active use; `DATA` and `UI` are **not** listed in `AGENTS.md:93`, so the code has drifted ahead of the protocol. Keep the existing vocabulary; update `AGENTS.md` if you retire one.
- `MAP_MODE` is one of `EXPORTS` (public API surface) or `LOCALS` (helpers, entities, DAOs, fakes). `SUMMARY` and `NONE` are declared in `AGENTS.md:94` but unused.
- `// START_MODULE_MAP` / `END_MODULE_MAP` follows the imports and lists each exported symbol as `//   symbolName - one-line description`. 44 of 57 files have one. Skip it only for files whose map would be pure noise (e.g. a single private helper).
- `// START_BLOCK_<UPPER_SNAKE>` / `END_BLOCK_<UPPER_SNAKE>` wrap slices that must be readable on their own. Block name = the semantic step (`BLOCK_VALIDATE_CREDENTIALS`, `BLOCK_STORE_TOKEN`, `BLOCK_FETCH_EVENTS`, `BLOCK_SYNC_TO_DB`, `BLOCK_DB_WRITE`, `BLOCK_REDACTED_LOG`). Never reuse one name twice in a file.
- `// START_CHANGE_SUMMARY` / `END_CHANGE_SUMMARY` records *why*, newest first, as `LAST_CHANGE: vX.Y.Z - what changed and why`. It also carries labelled hazard lines. 11 files carry one; these are exactly the files that touch reverse-engineered protocol surfaces or had a real leak. Add one when you change behaviour, and use the labelled forms when they apply:
  ```kotlin
  //   DEVIATION (added exports): getLocalTimeline and EventTimelinePort were added because
  //     the contract's syncEventsToDb / getLocalEventCount are otherwise write-only.
  //   INVARIANT: an empty timeline must NOT reach the cache — V-M-EVENT asserts that an empty
  //     result emits no BLOCK_SYNC_TO_DB marker.
  //   HAZARD: no log line in this module carries device_id or event_id — Yi serial numbers are
  //     10+ digit runs and would trip RedactionScanner's long-digit-run rule.
  ```
  See `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:37-50` for the fullest example.
- **Function-level `// START_CONTRACT:` / `END_CONTRACT:` blocks are documented in `AGENTS.md:102-112` but have zero occurrences in the codebase.** Do not rely on them existing; if you need a function contract, put it in the KDoc line above the signature instead.

**Import Organization:**

Observed order in `app/src/main/java/com/yicamalt/event/EventServiceModule.kt:15-29`:

1. `com.yicamalt.*` — own project first
2. third-party and library imports, roughly alphabetical but **not strictly enforced** (`com.google.dagger`, `retrofit2`, `kotlinx.*`, `okhttp3`, `timber.log`, `androidx.*`)
3. `java.*` / `javax.*` last
4. wildcards forbidden

Where a symbol was added later, it is simply appended inside the existing block rather than re-sorted — e.g. `retrofit2.Retrofit` sits before `com.jakewharton.retrofit2.converter...` in `app/src/test/java/com/yicamalt/auth/AuthRepositoryTest.kt:29-30`. No import-order enforcement exists, so do not reformat imports as drive-by work.

Fully-qualified names are used deliberately at the call site rather than imported, when the FQN is itself evidence. Examples: `javax.crypto.Mac` / `javax.crypto.spec.SecretKeySpec` in `app/src/main/java/com/yicamalt/camera/CameraRegistry.kt:90-91`, `kotlinx.serialization.json.JsonElement` throughout `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:168`, and `com.yicamalt.camera.CameraError.FetchFailed` inside `app/src/main/java/com/yicamalt/ui/cameras/CameraListViewModel.kt:57`. Do not "tidy" these.

## Error Handling

**Patterns:**

- One `sealed class <Domain>Error(message: String) : Error(message)` per module, with `object` members and **no payload**. The message is `"<DOMAIN>_<CONDITION>: <human sentence>"` — a codespaced prefix followed by a colon and a space. Tests assert the prefix, so the prefix is the stable contract and the sentence after it is free text.
  ```kotlin
  sealed class EventError(message: String) : Error(message) {
      object FetchFailed : EventError("EVENT_FETCH_FAILED: cloud event timeline unavailable")
      object NoRecording : EventError("EVENT_NO_RECORDING: no recording available for this event")
      object Unauthorized : EventError("EVENT_UNAUTHORIZED: session rejected by event API")
      object NotFound : EventError("EVENT_NOT_FOUND: no such event id")
  }
  ```
  Codespaces in use: `AUTH_`, `CAMERA_`, `CMD_`, `CONFIG_`, `EVENT_`, `HTTP_`.
- Network failure mapping is always the same three-step shape — transport, then application code, then domain:
  ```kotlin
  val envelope = try {
      api.eventList(...)
  } catch (e: HttpException) {
      if (e.code() == 401) throw EventError.Unauthorized
      throw EventError.FetchFailed
  } catch (e: IOException) {
      throw EventError.FetchFailed
  }
  if (envelope.code in AUTH_ERROR_CODES) throw EventError.Unauthorized
  if (envelope.code !in SUCCESS_CODES) throw EventError.FetchFailed
  ```
  (`app/src/main/java/com/yicamalt/event/EventServiceModule.kt:95-112`; identical shape at `CameraRegistry.kt:65-74`.)
- Yi Cloud application codes are held in a `companion object` as two sets, not scattered `if` chains:
  `internal val AUTH_ERROR_CODES = setOf("20201", "20202", "20203", "20205", "20253", "40110")` and `internal val SUCCESS_CODES = setOf("20000", "20200")`. These two sets are currently **duplicated** between `EventServiceModule.kt:252-253` and `CameraRegistry.kt:83-84`. That duplication is deliberate-but-declared in `app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt:32-35`; collapse both into `M-HTTP` only after a live capture confirms the event API.
- Untrusted JSON is parsed defensively, never with an exception contract: `runCatching { Json.parseToJsonElement(payloadJson) }.getOrNull()` (`EventServiceModule.kt:242`), `runCatching { … }.isSuccess` per cache write (`EventServiceModule.kt:212`).
- Non-fatal failures return `null` or a sentinel, never throw, when the contract is a query: `AuthRepository.doRefresh()` returns `AuthSession?` and keeps the old session on failure (`app/src/main/java/com/yicamalt/auth/AuthRepository.kt:120-140`).
- UI layers catch the specific error first, then a broad fallback, and surface a Russian user string. Ordering matters — the specific catch must precede `Throwable`:
  ```kotlin
  } catch (e: AuthError.InvalidCredentials) {
      _state.value = LoginUiState.Error("Неверный email или пароль")
  } catch (e: Throwable) {
      _state.value = LoginUiState.Error("Ошибка сети. Попробуйте снова.")
  }
  ```
  (`app/src/main/java/com/yicamalt/ui/login/LoginViewModel.kt:66-76`.)
- Logging must never crash the app. `FileLogTree.log` swallows `IOException` with an empty catch and a comment (`app/src/main/java/com/yicamalt/YiCamAltApp.kt:88-90`).
- **Coerce, don't reject, at the boundary.** `page.coerceAtLeast(1)` in `EventServiceModule.kt:84`; `safePage` is then used everywhere downstream, including the returned `EventPage.page`.

## Logging

**Framework:** Timber 5.0.1, debug trees only.

**Patterns:**

- Every important line carries a three-part marker prefix, then structured `key=value` fields:
  `Timber.d("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=${cameras.size} code=${envelope.code}")`
  — `[ModuleTag][fnName][BLOCK_NAME]`. Documented in `docs/technology.xml:74` and `docs/verification-plan.xml:3`.
- Prefer structured fields over prose. Counts, page numbers, codes, and flags are fields; sentences are not.
- Each package gets a tiny `internal object <Name>Log { fun d(message: String) = timber.log.Timber.d(message) }` shim so tests can swap the sink without depending on global Timber state: `AuthLog` (`AuthRepository.kt:264`), `DbLog` (`app/src/main/java/com/yicamalt/database/DbLog.kt:13`), `TimberLog` (`ConfigModule.kt:108` and `HttpClientModule.kt:194`), `LoginLog` (`LoginViewModel.kt:86`). Do not create a new shim when an existing package-local one is reachable.
- Redaction happens at the log call site, never in a formatter. Two redaction idioms:
  - identity-preserving: `private fun String.redact() = this.first() + "***@" + substringAfterLast('@', "unknown")` — `p***@example.com` does not match the email regex, which is why it passes `RedactionScanner` (`AuthRepository.kt:164`, `LoginViewModel.kt:82`, `ConfigModule.kt:103` for the `://***@` host form).
  - value-masking with `██` (U+2588), which is the allow-listed "already redacted" token in `RedactionScanner`: `.replace(Regex("(password=)[^&\\s]+"), "$1██")` (`HttpClientModule.kt:101-102`), and the JSON body masker at `HttpClientModule.kt:141`.
- **Never log** tokens, auth headers, passwords, camera serials, user emails, or full stream URLs. A real Yi serial is a 10+ digit run that trips `RedactionScanner`'s `\b\d{10,}\b` rule — this is why `EventDao.insertEvent` logs `type=` and `hasThumbnail=` instead of ids (`app/src/main/java/com/yicamalt/database/EventDao.kt:58-68`) and why `CameraRegistry.getLiveStreamUrl` logs `url=***` (`CameraRegistry.kt:120`). When a new field would be identifying, log a derived boolean or a length instead.
- Missing log anchors on a critical branch are a verification defect, not a style nit (`AGENTS.md:149`). If you add a branch that changes observable behaviour, add a marker inside the matching `START_BLOCK_*` and register it in `docs/verification-plan.xml`.
- Interceptor output is redirectable for tests: `HttpClientModule.logSink: ((String) -> Unit)?` routes the redacted logger to a `StringBuilder` instead of Timber (`HttpClientModule.kt:76`, `app/src/test/java/com/yicamalt/network/HttpClientModuleTest.kt:73`).
- `HttpClientModule` deliberately bypasses the token interceptor for `auth_token` calls to break a real interceptor→refresh→interceptor recursion; keep the comment at `HttpClientModule.kt:84-87` if you touch that branch.

## Comments

**When to Comment:**

- Comment the *why* and the provenance, never the *what*. The most valuable comments in this repo record how a protocol detail was discovered: `app/src/main/java/com/yicamalt/auth/AuthRepository.kt:32-36` cites the official Yi Home dex classes (`Lva/h login builder -> Lmc/d2.a`) as the source of the HMAC scheme; `app/src/main/java/com/yicamalt/camera/YiCloudDeviceApi.kt:25-30` records the live code ladder.
- Mark unconfirmed protocol knowledge with `PROVISIONAL`, `unconfirmed`, or `Do not rename` so a future agent does not "clean it up": `AuthRepository.kt:46`, `app/src/main/java/com/yicamalt/event/YiCloudEventApi.kt:28-31`, `app/src/main/java/com/yicamalt/camera/CommandModels.kt:35`.
- Mark deliberate debt explicitly as `DEVIATION`, `INVARIANT`, or `HAZARD` inside `CHANGE_SUMMARY`, with the reason and the condition that would let you remove it.
- Cross-reference the graph by exact id: `// V-M-EVENT invariant: an empty page must not touch the cache.` (`EventServiceModule.kt:121`).
- File-local anchors (`// FILE:`, `// VERSION:`, the contract/map/summary/blocks) are structure, not commentary. Never delete or reorder them (`AGENTS.md:211`).

**JSDoc/TSDoc:**

- KDoc `/** ... */` on any function whose contract is not obvious from the name — one sentence, then optional blank line and detail. `@param` is used only where a parameter's unit or domain is non-obvious (`app/src/main/java/com/yicamalt/camera/CommandModels.kt:23-27`).
- `[hasMore]`, `[ID_KEYS]` KDoc links reference the private constant in the same file, documenting the tolerance contract for the shape-tolerant parsers (`app/src/main/java/com/yicamalt/event/EventInfo.kt:47`, `:65-69`).
- No KDoc on trivial getters or Compose previews.

## Function Design

**Size:**

- Facade functions are 15–40 lines and carry exactly one `START_BLOCK_*` per logical phase. `EventServiceModule.getEventTimeline` (`EventServiceModule.kt:77-125`) is the reference shape: block it, sign, call, map codes, parse, resolve paging, log, guard, return, close block.
- Extract when a function needs a block marker to stay readable — the block *is* the extraction signal.
- Parser internals are small and single-purpose with early returns: `firstString`, `firstInt`, `firstBool`, `firstTimestamp` each do one coercion pass over a key list.

**Parameters:**

- Injectable seams for time and storage use interface + default argument so production and tests construct the same class: `AuthRepository(api, store, clock: Clock = SystemClock)` (`AuthRepository.kt:71`).
- Secondary no-arg constructors exist purely for tests: `ConfigModule` has `constructor() : this(InMemorySettingsStore())` (`ConfigModule.kt:53`).
- Prefer named arguments for anything beyond two obvious positional args.

**Return Values:**

- `suspend` for anything touching network or DAO, always wrapped in `withContext(Dispatchers.IO)`. There is no exception to this in the current code (`CameraRegistry.kt:58`, `:95`, `:107`, `:115`; `EventServiceModule.kt:82`, `:142`, `:148`, `:159`, `:198`, `:203`; `CameraCommandService.kt:80`).
- Domain objects out, wire types in. DAOs return `CameraEntity`/`EventEntity`; the mapper extension converts to `CameraInfo`/`EventInfo` at the boundary.
- Nullable means "not available", never "empty": `fun getLiveStreamUrl(deviceId: String): String` throws rather than returning null, while `fun getCameraById(deviceId: String): CameraInfo?` returns null. Pick by whether absence is exceptional for the caller.

## Module Design

**Exports:**

- No barrel or index files. There is no `UiModule.kt`, no `Facades.kt` — each package is self-contained and imports resolve directly to the declaring file.
- Every cross-layer dependency goes through a **thin port interface** so the consumer needs no network or DAO types. This is the single most consistent design rule in the repo:
  - `AuthProvider` (`app/src/main/java/com/yicamalt/network/AuthProvider.kt:17`) — HTTP → auth
  - `SessionSource` (`AuthRepository.kt:63`) — camera/event services → auth
  - `LoginPort` (`AuthRepository.kt:58`) — login ViewModel → auth
  - `CameraListPort` (`CameraRegistry.kt:46`) — camera list ViewModel → registry
  - `EventTimelinePort` (`EventServiceModule.kt:60`) — events UI → event service
  - `CommandTransport` (`CameraCommandService.kt:38`) — command service → cloud control
  - `AuthStore` / `SettingsStore` (`app/src/main/java/com/yicamalt/auth/AuthStore.kt:30`, `app/src/main/java/com/yicamalt/config/SettingsStore.kt:26`) — persistence seams
- Hilt wires the port to the implementation with `@Binds` in an `abstract class` module; `@Provides` lives in an `object` module. Both get `@InstallIn(SingletonComponent::class)` and `@Singleton` on every method. Canonical example: `app/src/main/java/com/yicamalt/camera/CameraModule.kt:26-52`.
- `Provider<T>` (not direct injection) is used where a cycle exists: `HttpClientModule(config: ConfigModule, authProvider: Provider<AuthProvider>)` (`HttpClientModule.kt:72`).
- Room DAOs are `abstract class`, not `interface`, specifically so the logging wrapper can be a non-abstract `open fun` layered on the annotated `abstract` insert. See `app/src/main/java/com/yicamalt/database/CameraDao.kt:22-46`. This is load-bearing for tests — do not convert to interfaces.

**Barrel Files:**

- None, by design. Do not introduce one.

## Anti-Patterns

### Removing or renaming a semantic anchor

**What happens:** a `// START_BLOCK_FETCH_EVENTS` is renamed, deleted, or "tidied" while refactoring.
**Why it's wrong:** `TraceRecorder.assertSequence("BLOCK_FETCH_EVENTS", "BLOCK_DB_WRITE", "BLOCK_SYNC_TO_DB")` in `app/src/test/java/com/yicamalt/event/EventServiceModuleTest.kt:416` matches on the literal name, and `docs/verification-plan.xml:334-336` lists the same markers as `<required-log-markers>`. The test fails and the plan is now stale.
**Do this instead:** keep the name byte-identical. If the logic genuinely moved, add a new block and keep the old name on the surrounding markers, then update `docs/verification-plan.xml` in the same change.

### Logging an identifier "just for debugging"

**What happens:** adding `Timber.d("[Event][syncToDb] deviceId=$deviceId")`.
**Why it's wrong:** `RedactionScanner` fails the build on `\b\d{10,}\b` and on any email shape, and a `RedactionScanner` failure is a global stop condition, not a warning (`docs/verification-plan.xml:763`). This already happened once: `EventDao` leaked `event_id` and was fixed at `app/src/main/java/com/yicamalt/database/EventDao.kt:23-28`.
**Do this instead:** log a derived, non-identifying fact — `type=`, `count=`, `hasThumbnail=`, `urlLen=`, or a redacted form. See `EventDao.kt:62-65` and `EventServiceModule.kt:88`.

### Returning `null` where a domain error is the contract

**What happens:** a repository returns `null` because the token is missing.
**Why it's wrong:** every caller of `CameraRegistry.getCameraList` and `EventServiceModule.getEventTimeline` expects a thrown `Unauthorized` object, and `V-M-*` scenarios assert on the thrown type plus its codespaced message.
**Do this instead:** `val s = session.currentSession() ?: throw EventError.Unauthorized` before any I/O (`EventServiceModule.kt:90`).

### Coercing an unconfirmed wire shape into a hard DTO

**What happens:** modelling the Yi Cloud event envelope with fixed `@SerialName` fields and a typed data class.
**Why it's wrong:** the envelope has never been captured live, so a fixed DTO would be a guess dressed as a contract.
**Do this instead:** keep `data` as `JsonElement?` in the Retrofit envelope and put the tolerance in a shape-tolerant parser that enumerates candidate keys (`app/src/main/java/com/yicamalt/camera/CameraInfo.kt:40-112`, `app/src/main/java/com/yicamalt/event/EventInfo.kt:71-202`). Adding a key to `ID_KEYS`/`LIST_KEYS` must remain the only change a live capture requires.

### Building a Retrofit graph per call site

**What happens:** a new `OkHttpClient.Builder()` in a feature module.
**Why it's wrong:** the auth interceptor, the redacted logger, the 401 authenticator, and the refresh guard are all wired in `HttpClientModule.init` and are not optional. A second client silently loses token refresh and redaction.
**Do this instead:** inject `Retrofit` or `HttpClientModule`; if a genuinely separate client is needed (only `app/src/main/java/com/yicamalt/debug/DeviceSignProbe.kt:36` does this today), say so in a comment explaining why.

### Writing prose documentation that duplicates an anchor

**What happens:** a README section explaining the event paging rule.
**Why it's wrong:** `AGENTS.md:76-80` requires that any fact maintainable in code/XML/contract markup stay there, so the copy will drift.
**Do this instead:** put it in the `INVARIANT:` line of `CHANGE_SUMMARY` and cross-reference it from `docs/verification-plan.xml` by id.

## Cross-Cutting Concerns

**Validation:**

- Validate at the boundary by coercion (`coerceAtLeast(1)`, `takeIf { it.isNotBlank() }`, `?.trim()?.takeIf { it.isNotBlank() }`) and by throwing a codespaced error when there is no sensible default (`ConfigModule.kt:79`).
- Unconfirmed-input tolerance lives in the parser, not in the caller: `EventTimelineParser` drops entries with no usable id rather than guessing (`EventInfo.kt:120`), and normalises epoch seconds to millis via `MILLIS_FLOOR` (`EventInfo.kt:196`).

**Authentication:**

- `AuthRepository` is the single owner of session state, held in a `@Volatile` field mirrored to `AuthStore` and guarded on refresh by an `AtomicReference` in-flight token (`AuthRepository.kt:74-75`).
- Nothing else reads tokens. `SessionSource.currentSession()` returns the session for *signing* only; `AuthProvider.getAccessToken()` is the only path that may trigger a refresh (`AuthRepository.kt:142-149`).
- The password never leaves the process in plaintext — only `Base64(HMAC-SHA256(password))` (`AuthRepository.kt:176-181`).

**Persistence:**

- Room is a cache, never a source of truth. Remote fetch → parse → cache write is the canonical flow, and an empty remote result must not evict the cache (`EventServiceModule.kt:122`, asserted by `scenario_4c`).
- `fallbackToDestructiveMigration()` with `version = 1` (`app/src/main/java/com/yicamalt/database/DatabaseModule.kt:45`); the destructive path is a known placeholder pinned by `scenario_4 migration path is flagged as destructive placeholder`.

**UI:**

- Compose Material 3 only; XML layouts are a discouraged surface (`docs/technology.xml:52`).
- Screens are stateless and take a `viewModel: XViewModel = hiltViewModel()` default parameter so a preview or test can substitute one (`LoginScreen.kt:51`, `CameraListScreen.kt:46`).
- User-facing strings are Russian literals inline — there is no string resource file.

---

*Convention analysis: 2026-09-28*
