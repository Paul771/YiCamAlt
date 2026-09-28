---
last_mapped_commit: 5339106af3d9d44a8887bb616c0e81cfb3ab0aa6
last_mapped_at: 2026-09-28
---
# Technology Stack

**Analysis Date:** 2026-09-28

## Languages

**Primary:**

- Kotlin 1.9.22 — all application and test sources under `app/src/main/java/com/yicamalt/` (44 production files) and `app/src/test/java/com/yicamalt/` (18 test files). Plugin `org.jetbrains.kotlin.android` declared in `build.gradle.kts:4`.
- Kotlin DSL (`.kts`) — all three build scripts: `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`.

**Secondary:**

- Java 17 toolchain — `compileOptions` / `jvmTarget = "17"` in `app/build.gradle.kts:38-42`. The codebase itself is 100% Kotlin; Java 17 is a *toolchain* requirement, not a language choice.
- XML — `app/src/main/AndroidManifest.xml` plus the GRACE documentation set in `docs/*.xml` (`requirements.xml`, `technology.xml`, `development-plan.xml`, `verification-plan.xml`, `knowledge-graph.xml`, `operational-packets.xml`).

## Runtime

**Environment:**

- Android — `compileSdk = 34`, `minSdk = 26` (Android 8.0), `targetSdk = 34` (`app/build.gradle.kts:12-17`).
- JDK 17 — required. `docs/PROJECT_STATUS.md` §5 records the working JDK as `C:/Program Files/Axiom/AxiomJDK-Pro-17-Full` and documents a broken default `cacerts` (see **Configuration → TLS truststore** below).
- Single-activity app, no server component (`docs/technology.xml` `<DeliveryShape>`): `app/src/main/java/com/yicamalt/MainActivity.kt` hosts the entire Compose tree.
- **No `app/src/main/res/` directory exists** — the manifest themes on the *platform* style `@android:style/Theme.Material.Light.NoActionBar`; there are no app-owned layouts, drawables or string resources. Compose owns all UI.

**Package Manager:**

- Gradle wrapper — Gradle **8.5** (`gradle/wrapper/gradle-wrapper.properties:3`, `gradle-8.5-bin.zip`).
- Lockfile: none. There is **no `gradle/libs.versions.toml`** — every dependency version is an inline string literal in `app/build.gradle.kts`. Adding a version catalog is a structural change, not a config tweak.
- `gradlew` + `gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar` all present.

## Frameworks

**Core:**

- Jetpack Compose — BOM `2024.02.00` (`app/build.gradle.kts:65`), compiler extension `1.5.8` (`:47`), `buildFeatures.compose = true` (`:44`). Material 3 only; the legacy View/XML system is not used anywhere.
- Hilt 2.51 — `com.google.dagger:hilt-android` + KSP compiler + `androidx.hilt:hilt-navigation-compose:1.2.0` (`:77-79`). All DI is compile-time; no manual Dagger wiring. Graph root is `@HiltAndroidApp` in `app/src/main/java/com/yicamalt/YiCamAltApp.kt`.
- Room 2.6.1 — `room-runtime` + `room-ktx` + KSP compiler (`:82-84`).
- Retrofit 2.9.0 + OkHttp 4.12.0 (`:87-89`) with `logging-interceptor` and `com.jakewharton.retrofit2:retrofit2-kotlinx-serialization-converter:1.0.0` (`:90`).
- kotlinx.serialization 1.6.3 (`:91`) — the `org.jetbrains.kotlin.plugin.serialization` plugin is applied in `app/build.gradle.kts:4`.
- androidx.security:security-crypto 1.1.0-alpha06 (`:95`) — note this is still an **alpha** artifact.
- kotlinx-coroutines-android 1.7.3 (`:92`).
- Timber 5.0.1 (`:98`).

**Testing:**

- JUnit 5 (Jupiter 5.10.2) as the primary runner, with the `junit-vintage-engine` bridging legacy JUnit 4 tests; `tasks.withType<Test> { useJUnitPlatform() }` in `app/build.gradle.kts:123-124`.
- Robolectric 4.11.1, MockK 1.13.9, MockWebServer 4.12.0, `kotlinx-coroutines-test` 1.7.3 (`:101-112`).
- `de.mannodermaus.android-junit5` 1.10.0.0 plugin (`build.gradle.kts:8`).
- Instrumented: Espresso 3.5.1, `compose.ui:ui-test-junit4`, runner `androidx.test.runner.AndroidJUnitRunner` (`app/build.gradle.kts:20`, `:115-120`).
- Custom test harness in `app/src/test/java/com/yicamalt/test/`: `TraceRecorder.kt` (asserts `BLOCK_`-prefixed log markers), `RedactionScanner.kt` (fails tests on leaked secrets / long digit runs), `FakeYiApi.kt`, `InMemoryRoom.kt`, `FakeCameraStreamServer.kt`, `FakeAuthStore.kt`, `StreamHandleFake.kt`.

**Build/Dev:**

- Android Gradle Plugin 8.2.2 (`build.gradle.kts:3`).
- KSP 1.9.22-1.0.17 (`build.gradle.kts:6`) — replaces kapt for both Hilt and Room.
- R8/ProGuard — `isMinifyEnabled = true` for release, rules in `app/proguard-rules.pro` (keeps `com.yicamalt.**`, `@kotlinx.serialization.SerialName` fields, `$$serializer` classes).
- **No linter or formatter is configured.** `docs/technology.xml:33-34` names ktlint/Detekt, but there is no ktlint/Detekt plugin, dependency, or `editorconfig` in the repo. Treat the plan as unmet.

## Key Dependencies

**Critical:**

- `com.google.dagger:hilt-android:2.51` + KSP — the entire service graph; every feature module is a `@Module @InstallIn(SingletonComponent)` (see `app/src/main/java/com/yicamalt/auth/AuthModule.kt`, `camera/CameraModule.kt`, `event/EventModule.kt`, `network/HttpClientModule.kt:56-67`, `database/DatabaseModule.kt`).
- `com.squareup.retrofit2:retrofit:2.9.0` + `okhttp:4.12.0` — the only path to the vendor cloud. All four Retrofit services are created from one shared `Retrofit` singleton.
- `androidx.room:room-*` 2.6.1 — offline cache for cameras and events; without it the UI is network-only.
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3` — mandatory for the reverse-engineering work: every vendor envelope keeps `data` as a raw `JsonElement` precisely so unknown shapes survive deserialization (e.g. `app/src/main/java/com/yicamalt/auth/YiCloudAuthApi.kt:55-59`).
- `androidx.security:security-crypto:1.1.0-alpha06` — token at-rest protection via Android Keystore; `docs/requirements.xml` `<constraint-2>` forbids plaintext secrets.

**Infrastructure:**

- `com.jakewharton.timber:timber:5.0.1` — the only logging facade. Release builds plant **no** tree, so release logs are empty by design.
- `androidx.navigation:navigation-compose:2.7.7` — declared but effectively unused: `MainActivity.kt:33-53` switches screens with a `when` over local `remember` state rather than a nav graph. The `NavigationHost` / `BottomNavBar` exports in `docs/development-plan.xml` are not built.
- `androidx.compose.material:material-icons-extended` — used for the settings gear on the login screen.
- `com.squareup.okhttp3:mockwebserver:4.12.0` — HTTP-level test double for the cloud.

**Declared in the plan but NOT on the classpath:**
`docs/technology.xml` lists four dependencies that do not appear in `app/build.gradle.kts`. Nothing in the codebase references them:

| Planned dependency | Planned for | Reality |
|---|---|---|
| `com.google.android.exoplayer:2.39+` | M-STREAM-LIVE / M-STREAM-DECODE / M-EVENT-PLAYBACK | Absent. No `ExoPlayer` symbol anywhere in `app/src/`. |
| `com.google.firebase:firebase-messaging:23+` | M-PUSH | Absent. No `google-services` Gradle plugin, no `FirebaseMessagingService`. |
| `androidx.work:work-runtime:2.9+` | background sync / push | Absent. |
| `io.github.thelour0:arsdk-android:0.1+` | native stream decoding | Absent. |

## Configuration

**Build variants:**

- Two build types only (`app/build.gradle.kts:24-36`), each declaring two `BuildConfig` fields:
  - `YI_API_BASE_URL` = `"https://api.yicamalt.local/v1/"` — **a placeholder host that does not resolve**. `docs/PROJECT_STATUS.md` §6/§7 names it as the root cause of the original "network error" login bug.
  - `LOCAL_RTSP_ENABLED` = `true` (debug) / `false` (release).
- `versionCode = 1`, `versionName = "0.1.0"`, `applicationId = "com.yicamalt"`, `namespace = "com.yicamalt"`.
- `android.nonTransitiveRClass=true` and `android.useAndroidX=true` (`gradle.properties:11-12`).

**Runtime configuration (overrides the placeholder):**

- `SettingsStore` — interface with a `SharedPreferences` impl and an in-memory test impl, `app/src/main/java/com/yicamalt/config/SettingsStore.kt`. Persists the API base URL override under pref file `yicamalt_settings`, key `api_base_url`.
- `ConfigModule.getApiBaseUrl()` prefers the user override, falls back to `BuildConfig` (`app/src/main/java/com/yicamalt/config/ConfigModule.kt:75-82`).
- Feature flags are **not** runtime-configurable: `FeatureFlag` (`local_rtsp`, `cloud_relay`, `push_foreground_service`, `multi_camera_view`) is a hardcoded `Map` built at construction, seeded from `BuildConfig` (`ConfigModule.kt:61-66`).

**TLS truststore (environment-specific, hardcoded):**

- `gradle.properties:7-9` sets `systemProp.javax.net.ssl.trustStore` to `C:/Users/pvanyushkin/Android/Sdk/cacerts.jks`, repeated as `jvmArgs` on every `Test` task in `app/build.gradle.kts:127-131`. `docs/PROJECT_STATUS.md` §5 explains why: the Axiom JDK ships an incomplete `cacerts` and a corporate MITM CA must be trusted for Robolectric to fetch `android-all-instrumented` over HTTPS.
- **This is a portability hazard** — the absolute path is baked into the build script, so the project does not build on another machine without editing it. Nothing reads it from an env var.

**Repositories:**

- `settings.gradle.kts:15-21` — `google()`, `mavenCentral()`, `RepositoriesMode.FAIL_ON_PROJECT_REPOS`. `pluginManagement` adds `gradlePluginPortal()` with regex content filters.
- No JitPack, no internal mirror. The `arsdk-android` dependency contemplated by `docs/technology.xml` would need one.

**Manifest:**

- `app/src/main/AndroidManifest.xml` declares exactly three permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`.
- `allowBackup="false"` (line 10) — deliberate, so the Keystore-wrapped prefs are not backed up.
- `POST_NOTIFICATIONS` is declared but **nothing consumes it** — there is no notification channel, service, or receiver. It is scaffolding for the unbuilt M-PUSH.

## Platform Requirements

**Development:**

- JDK 17 on `PATH`/`JAVA_HOME`; Android SDK with platform 34 and accepted licenses.
- Windows-specific: the whole environment is shaped by corporate AppLocker policy (`docs/PROJECT_STATUS.md` §5) — `.bat`/`.cmd` in user-writable paths are blocked, native `.exe` is only allowed under `C:\Windows`, and `aapt2` is junctioned into `C:\Windows\Temp`. `install_sdk_elevated.ps1` at the repo root provisions the SDK around these rules.
- **Do not invoke `gradlew` directly from an agent or CI harness.** `tools/gradle.ps1` is the supported entry point: it redirects Gradle's output to `build/logs/gradle-last.log` so the long-lived daemon does not hold the caller's stdout pipe open (the failure mode is a build that prints `BUILD SUCCESSFUL` and then appears to hang for tens of minutes). The script documents the mechanism at length in its own header comment.
  ```powershell
  .\tools\gradle.ps1 :app:testDebugUnitTest
  ```

**Production:**

- Single Android APK/AAB, no server component (`docs/technology.xml` `<DeliveryShape>`). Debug APK measured at 17.2 MB (`docs/PROJECT_STATUS.md` §12).
- minSdk 26 excludes ~pre-2017 devices; the stated rationale (`docs/technology.xml:24`) is modern Java/Kotlin support breadth.
- Release builds are minified; `app/proguard-rules.pro` is only 6 lines and keeps all of `com.yicamalt.**` plus the kotlinx-serialization surface. The Retrofit interfaces have no explicit keep rules but are covered by the `com.yicamalt.**` blanket.

## Documentation Framework

- `AGENTS.md` and `INSTRUCTIONS.md` define the in-house **GRACE** protocol: every module carries a `START_MODULE_CONTRACT` / `END_MODULE_CONTRACT` header, a `START_MODULE_MAP`, `START_BLOCK_*`/`END_BLOCK_*` semantic anchors, and a `START_CHANGE_SUMMARY`. Contracts use a fixed field vocabulary (`PURPOSE`, `SCOPE`, `DEPENDS`, `LINKS`, `ROLE`, `MAP_MODE`).
- This is a load-bearing convention, not decoration: `docs/verification-plan.xml` asserts on `BLOCK_`-prefixed log markers (see `app/src/test/java/com/yicamalt/test/TraceRecorder.kt`), so renaming a `START_BLOCK_*` anchor breaks verification. Module IDs follow `M-<TOKEN>` / `V-M-<TOKEN>` exactly.
- `docs/*.xml` uses unique tag names per entity (`<M-AUTH ...>`, `<V-M-EVENT ...>`) rather than generic tags with `ID` attributes, per the `AGENTS.md` table.

---

*Stack analysis: 2026-09-28*
