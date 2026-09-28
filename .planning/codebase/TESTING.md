---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
# Testing Patterns

**Analysis Date:** 2026-09-28

## Test Framework

**Runner:**

- JUnit 5 (Jupiter) `5.10.2` is the primary runner — `org.junit.jupiter:junit-jupiter`, `-api`, and `-engine` in `app/build.gradle.kts:101-103`.
- The JUnit 4 vintage engine `junit-vintage-engine:5.10.2` + `junit:junit:4.13.2` (`app/build.gradle.kts:104-105`) exist solely for the two Robolectric-driven tests, which use `org.junit.Test` / `@Before` / `@RunWith`: `app/src/test/java/com/yicamalt/database/YiDatabaseTest.kt` and `app/src/androidTest/java/com/yicamalt/ui/login/LoginScreenTest.kt`.
- Android plugin: `de.mannodermaus.android-junit5` `1.10.0.0` (`build.gradle.kts:8`, applied in `app/build.gradle.kts:7`). It is what makes `useJUnitPlatform()` work for Android unit-test tasks.
- Config: `tasks.withType<Test> { useJUnitPlatform(); jvmArgs(...) }` at `app/build.gradle.kts:123-131`. Test options at `app/build.gradle.kts:51-54`: `unitTests.isReturnDefaultValues = true`, `unitTests.isIncludeAndroidResources = true`.

**Assertion Library:**

- `org.junit.jupiter.api.Assertions.*` static imports — `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotNull`, `assertThrows`. **Do not** use `kotlin.test`; no file imports it.
- Harness assertions use `check(condition) { "message" }`, not an assertion library, so failures throw `IllegalStateException` with a full captured-trace dump (`app/src/test/java/com/yicamalt/test/TraceRecorder.kt:57-59`).

**Supporting libraries:**
| Library | Version | Role |
|---|---|---|
| Robolectric | 4.11.1 | Runs Room + Compose in the JVM; the only way the DB and UI tests execute without an emulator |
| MockWebServer (okhttp3) | 4.12.0 | Real HTTP transport for Retrofit under test |
| kotlinx-coroutines-test | 1.7.3 | `StandardTestDispatcher` / `runTest` / `advanceUntilIdle` for ViewModels |
| MockK | 1.13.9 (+ `mockk-android`) | **Declared but never used** — zero `mockk`/`every {}`/`verify {}`/`coEvery` calls anywhere in `app/src` |

**Run Commands:**

```powershell
.\gradlew :app:testDebugUnitTest                                        # Run all 91 unit tests
.\gradlew :app:testDebugUnitTest --tests "com.yicamalt.event.*"         # One module
.\gradlew :app:testDebugUnitTest --tests "*CameraRegistryModuleTest*"   # One test class
.\tools\gradle.ps1 :app:testDebugUnitTest                               # PowerShell-safe (see note)
.\gradlew :app:compileDebugKotlin                                        # Module-level compile gate
.\gradlew :app:assembleDebug                                             # Build gate
.\gradlew :app:connectedDebugAndroidTest                                 # 1 instrumented test; needs emulator
.\gradlew :app:lint                                                      # Phase-4 gate only
```

Use `.\tools\gradle.ps1` whenever output is piped or captured. It redirects Gradle to `build/logs/gradle-last.log` because a fresh daemon otherwise inherits the client's stdout handle, the pipe never sees EOF, and the caller appears to hang for tens of minutes after a build that already finished (`tools/gradle.ps1:4-13`).

## Test File Organization

**Location:**

- Module-local, mirroring the production package: `app/src/test/java/com/yicamalt/<same-package>/<Subject>Test.kt`. Tests live in the *same* package as the code under test (not `com.yicamalt.<feature>.test`), so they can reach `internal` declarations — `EventTimelineParser`, `ResponseTokens`, `EventServiceModule.AUTH_ERROR_CODES`, `PtzNormalizer` are all tested directly.
- Shared harness lives in one place: `app/src/test/java/com/yicamalt/test/`.
- Instrumented tests live in `app/src/androidTest/java/com/yicamalt/ui/login/`.

**Naming:**

- `<Subject>Test.kt` — `AuthRepositoryTest`, `CameraRegistryModuleTest`, `EventServiceModuleTest`, `YiDatabaseTest`, `HttpClientModuleTest`, `CameraCommandModuleTest`, `ConfigModuleTest`, `LoginViewModelTest`, `CameraListViewModelTest`.
- The harness self-test is `TestInfrastructureSmokeTest.kt`.
- Test *functions* use backtick names in a `scenario_N <description>` form when they implement a numbered verification scenario, and a plain sentence otherwise. Sub-variants get a letter suffix: `scenario_1b`, `scenario_1c`, `scenario_1d`, `scenario_1e`, `scenario_2b`, `scenario_2c`, `scenario_3b`, `scenario_4b`, `scenario_4c`, `scenario_4d`, `scenario_5b`…`scenario_5g`, `scenario_6`, `scenario_7`.

**Structure:**

```
app/src/test/java/com/yicamalt/
├── test/                              # shared harnesses — no fakes of their own
│   ├── TraceRecorder.kt               # hard gate: ordered BLOCK-marker capture
│   ├── RedactionScanner.kt            # hard gate: secret/PII leak detection
│   ├── InMemoryRoom.kt                # Room in-memory SQLite
│   ├── FakeYiApi.kt                   # scripted API responses
│   ├── FakeAuthStore.kt               # map-backed token store
│   ├── FakeCameraStreamServer.kt      # AES-encrypted frame bytes
│   ├── StreamHandleFake.kt            # scripted stream state machine
│   └── TestInfrastructureSmokeTest.kt # 7 tests proving the 7 harnesses work
├── auth/AuthRepositoryTest.kt         # 11
├── camera/CameraRegistryModuleTest.kt # 13
├── camera/CameraCommandModuleTest.kt  # 9
├── config/ConfigModuleTest.kt         # 6
├── database/YiDatabaseTest.kt         # 8   (Robolectric + JUnit 4)
├── event/EventServiceModuleTest.kt    # 23
├── network/HttpClientModuleTest.kt    # 6
├── ui/login/LoginViewModelTest.kt     # 5
└── ui/cameras/CameraListViewModelTest.kt # 3
app/src/androidTest/java/com/yicamalt/ui/login/LoginScreenTest.kt  # 1 (Robolectric, deferred)
```

## Suite Inventory

**91 unit tests, 0 failures** as of 2026-09-28 (baseline 68 + 23 for M-EVENT). Recorded at `docs/verification-plan.xml:344` and `docs/PROJECT_STATUS.md:382`.

| Test file | Tests | Verification id | Status |
|---|---|---|---|
| `event/EventServiceModuleTest.kt` | 23 | `V-M-EVENT` | `STATUS="passed"` |
| `camera/CameraRegistryModuleTest.kt` | 13 | `V-M-CAMERA-LIST` | `STATUS="passed"` |
| `auth/AuthRepositoryTest.kt` | 11 | `V-M-AUTH` | `STATUS="deferred"` |
| `camera/CameraCommandModuleTest.kt` | 9 | `V-M-CAMERA-CMD` | no status attr |
| `database/YiDatabaseTest.kt` | 8 | `V-M-LOCAL-DB` | `STATUS="deferred"` |
| `test/TestInfrastructureSmokeTest.kt` | 7 | `V-M-SCAFFOLD` (gate evidence-1) | — |
| `config/ConfigModuleTest.kt` | 6 | `V-M-CONFIG` | `STATUS="deferred"` |
| `network/HttpClientModuleTest.kt` | 6 | `V-M-HTTP` | `STATUS="deferred"` |
| `ui/login/LoginViewModelTest.kt` | 5 | `V-M-UI-LOGIN` | `STATUS="deferred"` |
| `ui/cameras/CameraListViewModelTest.kt` | 3 | `M-UI-SHELL` (in `LINKS:`) | — |
| `androidTest/.../LoginScreenTest.kt` | 1 | `V-M-UI-LOGIN` | DEFERRED, cannot run here |

**Scenario-to-plan mapping is 1:1 and load-bearing.** `app/src/test/java/com/yicamalt/event/EventServiceModuleTest.kt` declares `LINKS: V-M-EVENT`, and `docs/verification-plan.xml:308-332` lists `<scenario-1>` … `<scenario-7>` with the same ids and letter suffixes. When you add a test, add the matching `<scenario-Nx>` entry to the `V-M-*` block in the same change (`AGENTS.md:209`).

## Test Structure

**Suite Organization:**
Every test file opens with the same GRACE header as production code — `ROLE: TEST`, `MAP_MODE: LOCALS` — then package, imports, private fakes, fields, lifecycle, helpers, tests:

```kotlin
// FILE: CameraRegistryModuleTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify M-CAMERA-LIST against MockWebServer: list parse, cache write, 401/5xx mapping.
//   SCOPE: success + failure scenarios for V-M-CAMERA-LIST; trace marker assertions.
//   DEPENDS: M-CAMERA-LIST, TestInfrastructure
//   LINKS: V-M-CAMERA-LIST
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
// …

/** In-memory CameraDao fake (no Room) so plain-JVM tests can capture cache writes. */
private class FakeCameraDao : CameraDao() { … }

class CameraRegistryModuleTest {

    private val server = MockWebServer()
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val dao = FakeCameraDao()
    private lateinit var registry: CameraRegistry

    @BeforeEach
    fun setUp() {
        server.start()
        recorder.plant()
        // real Retrofit + real converter, pointed at the mock server
        registry = CameraRegistry(newApi(), dao, FakeSession())
    }

    @AfterEach
    fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }
```

**Patterns:**

- Harness instances are **fields**, not locals, so every test sees the same `TraceRecorder` and `RedactionScanner`.
- Objects under test are `lateinit` fields assigned in `setUp()`, or rebuilt inline when a test needs a different collaborator (e.g. `AuthRepositoryTest` builds a production `HttpClientModule` + `Provider` to reproduce the refresh deadlock at `AuthRepositoryTest.kt:127-152`).
- Every test that logs calls `recorder.plant()` and tears it down. Where there is no `setUp` (small classes like `ConfigModuleTest`), planting is done inline inside `try { … } finally { recorder.unplant() }`.
- Assertions carry a **reason**, not just an expectation. This is near-universal:
  ```kotlin
  assertTrue(session.expiresAt > clock.nowMillis(), "missing expires_in must NOT produce an already-expired session")
  assertNull(second.playbackUrl, "event without a playback key must resolve to null, not a guess")
  assertEquals(0, server.requestCount, "empty remote list must not touch the cache")
  assertTrue(transport.sent.isEmpty(), "no transport call must happen for unsupported model")
  ```
- Tests assert the *absence* of work, not just the presence of results: `assertEquals(0, server.requestCount)`, `assertEquals(afterFetch, server.requestCount, "a cached URL must not trigger another request")`, `recorder.assertMarkerAbsent("BLOCK_SYNC_TO_DB")`.
- Marker-count assertions pin "exactly once" invariants: `assertEquals(1, initMarkers, "BLOCK_INIT_CONFIG must appear exactly once")` (`ConfigModuleTest.kt:67`).
- Fixtures are private member functions or `private val` constants at the top of the class, never inline string literals inside a test.
- `START_BLOCK_*` anchors also appear *inside* tests to mark the slice under test — `// START_BLOCK_S1` / `END_BLOCK_S1` in `ConfigModuleTest.kt:31-33` and `YiDatabaseTest.kt:58-61`.

## Mocking

**Framework:** hand-written fakes. MockK is on the classpath but has zero call sites; the project's stated policy is `Prefer fakes (MockCameraService, FakeApi) over broad monkey-patching. Use Mockk for interface mocks.` (`docs/technology.xml:69`). In practice even that exception is unused — every dependency is faked by hand.

**What to Mock (by hand):**

| Seam | Fake | Where |
|---|---|---|
| Token store | `TestAuthStore : AuthStore` (private) | `auth/AuthRepositoryTest.kt:34` |
| Token source | `FakeSession` / `NoSession : SessionSource` | `camera/CameraRegistryModuleTest.kt:47,56` |
| Login | `FakeLoginPort : LoginPort` with a `sealed class Outcome` | `ui/login/LoginViewModelTest.kt:34` |
| Camera list | `FakePort : CameraListPort` with `calls` counter | `ui/cameras/CameraListViewModelTest.kt:28` |
| Command delivery | `FakeTransport : CommandTransport` with `sent` list + `ack` flag | `camera/CameraCommandModuleTest.kt:39` |
| Token refresh | `FakeAuthProvider : AuthProvider` with `refreshCount: AtomicInteger` | `network/HttpClientModuleTest.kt:32` |
| Settings | `InMemorySettingsStore` (production file, test-only impl) | `app/src/main/java/com/yicamalt/config/SettingsStore.kt:55` |
| Clock | `FakeClock : Clock` with `advance(ms)` over an `AtomicLong` | `auth/AuthRepositoryTest.kt:42` |

Fake ports follow one shape: constructor params carry the scripted result, public `var`s carry the switches and the recorded interactions.

```kotlin
private class FakeTransport : CommandTransport {
    val sent = mutableListOf<CommandPayload>()
    var ack = true
    override suspend fun send(payload: CommandPayload): Boolean {
        sent += payload
        return ack
    }
}
```

**DAO fakes subclass the abstract class.** Because Room DAOs here are `abstract class` (see `app/src/main/java/com/yicamalt/database/CameraDao.kt:22`), a fake can `override` the `open` logging wrapper and inherit nothing from Room:

```kotlin
/** In-memory CameraDao fake (no Room) so plain-JVM tests can capture cache writes. */
private class FakeCameraDao : CameraDao() {
    val stored = mutableListOf<CameraEntity>()
    override fun getAll(): List<CameraEntity> = stored.toList()
    override fun getById(deviceId: String): CameraEntity? = stored.find { it.device_id == deviceId }
    override fun deleteAll() { stored.clear() }
    override fun count(): Int = stored.size
    override fun insertCameraRaw(entity: CameraEntity) {
        // Mirror OnConflictStrategy.REPLACE (Room would upsert on device_id PK).
        stored.removeAll { it.device_id == entity.device_id }
        stored += entity
    }
}
```

Every abstract DAO member must be overridden — that is the cost of this pattern, and it is why `FakeEventDao` in `event/EventServiceModuleTest.kt:38` is 22 lines.

**HTTP is never mocked.** Tests build a *real* `Retrofit` with a real `kotlinx-serialization` converter over `MockWebServer`. Interceptors, the auth header, the authenticator, and query-param encoding are all genuinely executed, which is what makes the signing assertions meaningful:

```kotlin
val q = server.takeRequest().requestUrl!!
assertEquals("1", q.queryParameter("seq"))
assertEquals("u-1", q.queryParameter("userid"))
val mac = javax.crypto.Mac.getInstance("HmacSHA1")
mac.init(javax.crypto.spec.SecretKeySpec("t-1&s-1".toByteArray(Charsets.UTF_8), "HmacSHA1"))
val expected = java.util.Base64.getEncoder()
    .encodeToString(mac.doFinal("seq=1&userid=u-1".toByteArray(Charsets.UTF_8)))
assertEquals(expected, q.queryParameter("hmac"))
```

**What NOT to Mock:**

- **Hilt.** There is no `HiltAndroidRule`, no `@HiltAndroidTest`, no test component. Objects are constructed directly. This is a deliberate simplification: everything is a plain constructor call.
- **Parsers and error mapping.** `CameraListParser`, `EventTimelineParser`, `ResponseTokens` are exercised against real JSON strings, because the shape tolerance *is* the behaviour under test.
- **OkHttp interceptors and the `Authenticator`.** `HttpClientModuleTest` hits the raw `OkHttpClient` so the 401→refresh→retry path runs for real; only the *token source* is faked.
- **Time.** Never `Thread.sleep`. Use `FakeClock.advance(ms)` (`AuthRepositoryTest.kt:44`).
- **Room, when the point is Room.** `YiDatabaseTest` uses Robolectric + real `InMemoryRoom` so SQL, indices, and `OnConflictStrategy` are genuinely exercised.

## Fixtures and Factories

**Test Data:**

Response bodies are built by private members so each test reads as "which shape am I feeding in":

```kotlin
private fun loginBody(access: String, refresh: String, expiresIn: Long = 3600, user: String = "u-1") =
    """{"code":"200","data":{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"user_id":"$user"}}"""

private fun invalidBody() = """{"code":"401","message":"invalid credentials"}"""

private fun timelineBody(vararg events: String, total: Int = events.size) =
    """{"code":"20000","data":{"eventList":[${events.joinToString(",")}],"total":$total}}"""
```

Real observed envelopes are quoted verbatim, not synthesised — `AuthRepositoryTest.kt:113-114` carries the captured 20000 login body with its comment `// Observed live response shape (gw-eu, 2026-09-08)`.

**Location:**

- Per-file fixture builders are private members of the test class.
- Cross-module shared fixtures live in `app/src/test/java/com/yicamalt/test/FakeYiApi.kt` inside `// START_BLOCK_FIXTURES` / `END_BLOCK_FIXTURES` (`loginSuccessBody`, `loginInvalidBody`, `cameraListBody`).
- Entity factories in `YiDatabaseTest.kt:45-54` (`camera(id, online)`, `event(id, device, ts, type)`) mirror the Room entity constructor exactly.

**Conventions:**

- URLs use the reserved `.invalid` TLD so nothing can resolve: `https://thumb.invalid/ev-1.jpg`, `rtsp://resolved.invalid/ev-2`.
- Fixtures use short readable ids (`cam-1`, `ev-1`, `t-1`, `s-1`, `u-1`) and short token values (`acc-1`, `ref-1`) so a leak is obvious in a failure dump.
- Leaked-value literals get distinctive names on purpose: `acc-secret-1`, `ref-secret-2`, `secret-token-value`, `super-secret-password` — these are the values handed to `RedactionScanner.assertClean(extraLiterals = …)`.
- **Fixture values must respect the redaction scanner.** `EventServiceModuleTest.kt:83-87` documents why the request window is `1_712_340_000_000L` (13 digits) and the width is `86_400_000L` (9 digits): the module logs `windowMs`, and a 10+ digit run would fail `\b\d{10,}\b`. Epoch-millis values in fixtures are fine as long as they never reach a log line.
- Entity fixtures avoid `user@example.com` in redaction tests and use `[EMAIL]` in wire-shape tests — both are non-matching on purpose.

## The Two Hard Gates

These are not conveniences. `docs/verification-plan.xml:761-766` makes a `RedactionScanner` failure a global stop condition ("redaction is a hard gate, not a warning") and a missing required marker a verification defect.

### TraceRecorder — `app/src/test/java/com/yicamalt/test/TraceRecorder.kt`

Captures Timber output into an ordered list so *trajectory* becomes assertable.

```kotlin
private val recorder = TraceRecorder()

// API
recorder.plant()                              // install a Timber.DebugTree
recorder.unplant()                            // uproot the whole forest
recorder.reset()                              // clear captured entries
recorder.all(): List<Marker>                  // tag, message, parsed fields, index
recorder.bufferText(): String                 // "tag | message" per line, for the scanner
recorder.assertMarkerAppeared("BLOCK_STORE_TOKEN")
recorder.assertMarkerAbsent("BLOCK_SYNC_TO_DB")
recorder.assertSequence("BLOCK_VALIDATE_CREDENTIALS", "BLOCK_STORE_TOKEN")
recorder.assertMaxRetries("BLOCK_RETRY", 3)
```

`plant()` parses each line into `Marker(tag, message, fields, index)` where `fields["markers"]` is the bracketed tokens joined with `->` and every `key=value` pair is captured. `assertSequence` flattens all captured lines, keeps only tokens starting with `BLOCK_`, and requires the expected `A->B->C` string to appear via `contains`.

```kotlin
@Test
fun `scenario_1 valid login returns session and stores token`() {
    server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
    val session = runBlocking { repo.login("user@example.com", "pw") }
    assertEquals("acc-1", session.accessToken)
    recorder.assertMarkerAppeared("BLOCK_VALIDATE_CREDENTIALS")
    recorder.assertSequence("BLOCK_VALIDATE_CREDENTIALS", "BLOCK_STORE_TOKEN")
}
```

`assertSequence` is what proves *ordering* — e.g. `EventServiceModuleTest.kt:416`:

```kotlin
recorder.assertSequence("BLOCK_FETCH_EVENTS", "BLOCK_DB_WRITE", "BLOCK_SYNC_TO_DB")
```

and `assertMarkerAbsent` is what proves a *branch was not taken*:

```kotlin
recorder.assertMarkerAbsent("BLOCK_SYNC_TO_DB")   // empty page must not touch the cache
```

**Two caveats to know before relying on it:**

- `assertSequence` uses `String.contains`, so a *subsequence* matches. `A->B->C` passes on a trace reading `X->A->B->C->Y`. It proves relative order, not adjacency or completeness.
- `unplant()` uproots **every** tree in the forest, not just the one it planted. Do not plant a second tree in a test that also uses `TraceRecorder`.

### RedactionScanner — `app/src/test/java/com/yicamalt/test/RedactionScanner.kt`

Scans a captured buffer for anything that must never be logged. Four always-on regexes:

| Pattern | Catches |
|---|---|
| `Bearer\s+[A-Za-z0-9._-]+` | Authorization header values |
| `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` | emails |
| `\b\d{10,}\b` | tokens, camera serials, raw epoch-millis |
| `(?i)(password\|authorization\|secret\|aeskey\|aes_key\|accesstoken\|refreshtoken)\s*[=:]\s*(?!<redacted>\|\u2588\u2588)\S+` | secret-shaped `key=value` assignments |

The negative lookahead is the contract with production code: masking a value with `██` (U+2588) or `<redacted>` is the only way to log a secret-bearing key. That is exactly what `HttpClientModule.kt:101-102` does.

```kotlin
@Test
fun `scenario_5 redaction tokens never appear in logs`() {
    server.enqueue(MockResponse().setBody(loginBody("acc-secret-1", "ref-secret-2")))
    runBlocking { repo.login("user@example.com", "pw") }
    redaction.assertClean(recorder.bufferText(), extraLiterals = listOf("acc-secret-1", "ref-secret-2"))
}
```

```kotlin
redaction.assertClean(
    recorder.bufferText(),
    extraLiterals = listOf("ev-1", "ev-2", "cam-1", "t-1", "s-1"),
)
```

`extraLiterals` are the per-test must-not-appear values; `extraPatterns` adds one-off regexes. `scan()` returns `List<ForbiddenMatch>` when you want to inspect rather than assert. One match fails the gate.

`RedactionScanner` is exercised in 6 of the 10 unit test files: `AuthRepositoryTest`, `CameraRegistryModuleTest`, `EventServiceModuleTest`, `HttpClientModuleTest`, `ConfigModuleTest`, `CameraCommandModuleTest`, `LoginViewModelTest`, `YiDatabaseTest`. `HttpClientModuleTest` also asserts the negative directly: `assertTrue(!logBuffer.contains("secret-token-value"))`.

## Async Testing

**Repository / DAO tests** use `runBlocking` — no dispatcher swapping, because the code under test is `withContext(Dispatchers.IO)` and a real thread is fine:

```kotlin
@Test
fun `scenario_1 paginated list parsed with thumbnails and paging hint`() {
    server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent, total = 3)))
    val page = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
    assertEquals(2, page.events.size)
    recorder.assertMarkerAppeared("BLOCK_FETCH_EVENTS")
}
```

**ViewModel tests** install a `StandardTestDispatcher` as `Dispatchers.Main` and drive with `advanceUntilIdle()`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recorder.plant()
    }

    @AfterEach fun tearDown() {
        recorder.unplant()
        Dispatchers.resetMain()
    }

    @Test
    fun `scenario_1 success shows loading then success`() = runTest(testDispatcher) {
        val vm = LoginViewModel(FakeLoginPort())
        vm.email.value = "user@example.com"
        vm.password.value = "secret-pw"
        vm.submit()
        assertEquals(LoginUiState.Loading, vm.state.value)   // mid-flight, before advancing
        advanceUntilIdle()
        assertEquals(LoginUiState.Success, vm.state.value)
    }
}
```

Note the deliberate mid-flight assertion: with `StandardTestDispatcher` the coroutine has not run yet when `submit()` returns, so `Loading` is observable. The class-level `@OptIn(ExperimentalCoroutinesApi::class)` is required for `Dispatchers.setMain`/`resetMain`.

`runTest(testDispatcher)` is used when the test drives virtual time; `CameraListViewModelTest` uses plain `runTest` and lets the default dispatcher apply.

## Error Testing

**Pattern:** assert on the thrown sealed-error *class* (identity) and then on the codespaced message prefix (stable contract):

```kotlin
@Test
fun `scenario_2 invalid credentials throws AUTH_INVALID_CREDENTIALS`() {
    server.enqueue(MockResponse().setBody(invalidBody()))
    val err = assertThrows(AuthError.InvalidCredentials::class.java) {
        runBlocking { repo.login("user@example.com", "wrong") }
    }
    assertTrue(err.message!!.contains("AUTH_INVALID_CREDENTIALS"))
    assertNull(store.getSession())
}
```

When the message is not the point, the class alone is asserted — `assertThrows(EventError.NoRecording::class.java) { … }` at `EventServiceModuleTest.kt:328`.

**Failure-injection matrix.** Both network-facing modules test the same four failure axes, because the mapping is the contract:

| Axis | Fixture | Asserted result |
|---|---|---|
| HTTP 401 | `MockResponse().setResponseCode(401)` | `…Error.Unauthorized` |
| HTTP 5xx | `setResponseCode(503).setBody("oops")` | `…Error.FetchFailed` |
| App-level auth code | `{"code":"20201"}` | `…Error.Unauthorized` |
| Unknown success code | `{"code":"99999"}` | `…Error.FetchFailed` |
| Missing session | `NoSession()` fake, no `enqueue` | `…Error.Unauthorized` **and** `assertEquals(0, server.requestCount)` |
| Not found | no fixture, local lookup only | `…Error.NotFound` |

**Empty is not an error.** A distinct convention: an empty remote result returns an empty page, skips the cache write entirely, and must not evict what is already cached — asserted with `assertMarkerAbsent` plus a row count (`scenario_4`, `scenario_4b`, `scenario_4c`).

**Rejection happens before side effects.** `CameraCommandModuleTest.kt:93-106` asserts the exception *and* that the transport was never called:

```kotlin
assertThrows(CameraCommandError.UnsupportedModel::class.java) { runBlocking { service.sendPTZ("bulb-1", 0.5f, 0.5f) } }
assertTrue(transport.sent.isEmpty(), "no transport call must happen for unsupported model")
```

## Robolectric

Two tests use it, and for different reasons.

**Room, so the SQL is real** — `database/YiDatabaseTest.kt`:

```kotlin
@RunWith(RobolectricTestRunner::class)
class YiDatabaseTest {
    @Before fun setUp() {
        recorder.plant()
        db = InMemoryRoom.build()
        db.openHelper.writableDatabase  // forces creation so BLOCK_INIT_DB is emitted once
    }
    @After fun tearDown() { db.close(); recorder.unplant() }
```

`InMemoryRoom.build()` uses `Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YiDatabase::class.java).allowMainThreadQueries()` and installs the same `RoomDatabase.Callback` as production, so `BLOCK_INIT_DB` is asserted from the real callback path (`YiDatabaseTest.kt:106-109`).

**Compose, so the UI test can run headless** — `androidTest/.../LoginScreenTest.kt` uses `createComposeRule()`. It is explicitly **DEFERRED**: the file ends with a note that it needs an Android SDK plus Compose test artifacts, and that `LoginViewModelTest` is the authoritative unit coverage for submit/success/error. Do not treat it as a passing gate.

## Coverage

**Requirements:** none enforced. There is no `jacoco` plugin, no coverage report task, and no threshold anywhere in `app/build.gradle.kts` or `docs/verification-plan.xml`.

Coverage is replaced by two verifiable substitutes:

1. **Scenario coverage.** Each `V-M-*` entry in `docs/verification-plan.xml` enumerates its scenarios with `kind="success"|"failure"` and `evidence-type="deterministic"|"trace"|"integration"`. Every module needs *both* success and failure scenarios (`docs/verification-plan.xml:5`, the `<autonomy-gate>`).
2. **Trace coverage.** `<trace-coverage-rule>`: *every required-log-marker listed in a V-M-* entry must appear at least once in the module's passing test trace; a missing marker is a verification defect.* This is what `assertMarkerAppeared` exists for.

Evidence precedence is fixed: `deterministic > trace > integration > semantic` (`docs/verification-plan.xml:13`). Reach for `assertEquals` on a return value first; reach for a marker assertion only when the trajectory is the thing under test; never replace a working exact assertion with a fuzzy check.

## Test Types

**Unit Tests (91, all in `app/src/test/`):**

- Retrofit + MockWebServer integration over the real HTTP stack — `AuthRepositoryTest`, `CameraRegistryModuleTest`, `EventServiceModuleTest`, `HttpClientModuleTest`.
- Pure logic — `CameraCommandModuleTest` (`PtzNormalizer`), the parser paths inside the network tests.
- Room + Robolectric — `YiDatabaseTest`.
- ViewModel state machines on a test dispatcher — `LoginViewModelTest`, `CameraListViewModelTest`.
- Harness self-test — `TestInfrastructureSmokeTest` (7 tests, one per harness).

**Integration Tests:**

- In-JVM only, expressed as *combinations* of the above: real Retrofit → real interceptors → real parser → in-memory DAO fake, asserted through `TraceRecorder` + `RedactionScanner`. This is the project's default integration level; there is no separate integration source set.

**E2E Tests:**

- `connectedDebugAndroidTest` runs exactly one test, `LoginScreenTest`, and it is currently deferred.
- Phase gates 2, 3 and 4 all list `./gradlew :app:connectedDebugAndroidTest` (`docs/verification-plan.xml:791`, `:807`, `:825`), so the surface is declared but not exercised. There is no CI workflow file in the repo, so every gate is run by hand.

## Common Patterns

**Async Testing:** see [Async Testing](#async-testing) — `runBlocking` for services, `runTest(StandardTestDispatcher)` + `advanceUntilIdle()` for ViewModels, `FakeClock.advance()` for expiry, never `Thread.sleep`.

**Error Testing:** see [Error Testing](#error-testing).

**Trace assertion:**

```kotlin
recorder.assertMarkerAppeared("BLOCK_RESOLVE_PLAYBACK_URL")
recorder.assertMarkerAbsent("BLOCK_DB_WRITE")
recorder.assertSequence("BLOCK_FETCH_EVENTS", "BLOCK_DB_WRITE", "BLOCK_SYNC_TO_DB")
assertEquals(1, recorder.all().count { it.message.contains("BLOCK_INIT_DB") })
```

**Redaction assertion:**

```kotlin
redaction.assertClean(recorder.bufferText(), extraLiterals = listOf("acc-secret-1", "ref-secret-2"))
```

**Protocol pinning:** reverse-engineered wire details are asserted by recomputing the expected value in the test rather than hard-coding a string. `scenario_1d` recomputes the HMAC-SHA1 over the canonical `seq=1&userid=u-1`; `scenario_6` recomputes the HMAC-SHA256 password digest against the literal key. If the protocol changes, these fail loudly — which is the point. Keys and algorithm names that the vendor hard-codes are annotated `Do not rename` in production (`app/src/main/java/com/yicamalt/auth/AuthRepository.kt:46`).

## Known Infrastructure Risks

- **Hardcoded truststore from the original author's machine.** `app/build.gradle.kts:127-131` sets `-Djavax.net.ssl.trustStore=C:/Users/pvanyushkin/Android/Sdk/cacerts.jks` on every `Test` task, and `gradle.properties:7-9` repeats it as a `systemProp`. The path does not exist on any other machine. The reason is documented in the build file — Robolectric must download `android-all-instrumented` over HTTPS and needs the Kaspersky MITM CA in the chain — but the fix is to resolve the truststore from `local.properties`/an env var and skip the flag when absent. **A fresh clone on another machine cannot run the unit suite.**
- **MockK is a dead dependency.** `io.mockk:mockk:1.13.9` and `mockk-android:1.13.9` (`app/build.gradle.kts:106-107`) have zero call sites. Either use it for the interface-mocking case `docs/technology.xml:69` sanctions, or drop it.
- **`TraceRecorder.assertMaxRetries` is unused** (`TraceRecorder.kt:79-82`), and the reconnect-cap scenario it was written for (`Gate-Phase-3` evidence-6, "Reconnect cap (max 3)") is not implemented.
- **`assertSequence` matches subsequences**, so it cannot detect a missing intermediate marker.
- **`unplant()` clears the entire Timber forest**, which will silently disable any other planted tree.
- **`FakeAuthStore` does not implement `AuthStore`.** It exposes `putString`/`getString` (`app/src/test/java/com/yicamalt/test/FakeAuthStore.kt:20-24`) while production `AuthStore` is session-shaped (`app/src/main/java/com/yicamalt/auth/AuthStore.kt:30`). `AuthRepositoryTest` ignores it and declares its own `TestAuthStore`. The harness is spec-shaped, not implementation-shaped.
- **`HttpClientModuleTest` declares an unused `private interface PingApi`** (`HttpClientModuleTest.kt:46`); the test drives raw `OkHttpClient` calls instead.
- **`CameraListViewModelTest.failure resolves to error then retry recovers` does not exercise retry** — it constructs a second ViewModel, and the test comment at line 68 admits it.
- **No CI.** No workflow file, no lint/format enforcement, no coverage report. All gates in `docs/verification-plan.xml` are executed manually, and only the test count (91/0) is machine-checkable.

---

*Testing analysis: 2026-09-28*
