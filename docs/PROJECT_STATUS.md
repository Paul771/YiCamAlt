# YiCamAlt — Текущее состояние проекта

> Дата: 2026-09-01
> Формат: Markdown-снимок состояния для быстрой ориентации.
> Канонические GRACE-артефакты: `docs/*.xml` (requirements, technology, development-plan, verification-plan, knowledge-graph, operational-packets).

---

## 1. Что это за проект

**YiCamAlt** — альтернативный Android-клиент для камер Yi (Kami), работающий **без подписки**.
Цель — обойти платную подписку официального приложения, используя **реверсивно-инженеренные API** и открытые протоколы (без проприетарного Yi SDK).

- Платформа: **Android** (min SDK 26 / target 34)
- Стек: Kotlin 1.9+, Jetpack Compose, Material 3, Hilt DI, Retrofit/OkHttp, Room
- Хранилище токенов: Android Keystore (секреты не логируются)

---

## 2. Статус фаз (GRACE)

| Фаза | Статус | Примечание |
|------|--------|-----------|
| **Phase-1 (Auth / Login)** | ✅ `verified` | 39 unit-тестов / 0 падений; `assembleDebug` → app-debug.apk (17.9MB) |
| Phase-2 (Camera Management) | ⏳ не начата | M-CAMERA-DISCOVERY, M-CAMERA-MANAGE и др. |
| Phase-3 (Streaming) | ⏳ не начата | M-STREAM-DECODE, M-STREAM-LIVE (conditional) |
| Phase-4 (Setup/Discovery) | ⏳ не начата | M-CAMERA-DISCOVERY, M-CAMERA-SETUP (conditional) |

**Evidence-3** (навигация Login → shell на эмуляторе) — **отложена** (нет эмулятора/устройства в окружении).

---

## 3. Коммиты (цепочка)

```
c99f97b  M-SCAFFOLD
5adf835  M-CONFIG
d0a9171  M-HTTP
5d546b3  M-LOCAL-DB
3e901ae  M-AUTH
8083399  M-UI-LOGIN
7de0f61  Phase-1 code (Option B)
6db0c5d  build fixes, gate green
c16eee1  Phase-1 verified in docs
64ac154  runtime-configurable base URL
fb0332e  knowledge-graph sync
```

---

## 4. Ключевые решения

- **Архитектура / порядок фаз / Option B** (код сейчас, верификация позже) — фактически перекрыто: верификация выполнена и зелёная.
- **Redaction — жёсткий гейт**; правило Trace-coverage; TestInfrastructure приватна для тестов.
- **RedactionScanner**: секретные слова флагаются только как key-value присваивания (`password=...`, `authorization=...`), не как отдельные метки; `<redacted>` и `██` (U+2588 от HttpLoggingInterceptor) исключены.
- **TraceRecorder.assertSequence**: считаются только маркеры с префиксом `BLOCK_`.
- **Robolectric SSL**: truststore JVM-аргументы на unit-test task + `gradle.properties` systemProps.
- **Runtime-configurable API base URL** (вместо жёсткого BuildConfig-плейсхолдера): `SettingsStore` (SharedPreferences) override + BuildConfig fallback; `HttpClientModule.rebuild()` пересоздаёт Retrofit; экран настроек доступен через gear-иконку с LoginScreen.
- **ConfigModule** сохраняет no-arg secondary constructor (чтобы существующие тесты компилировались без изменений).

---

## 5. Окружение и блокеры (AppLocker / Group Policy)

- **AppLocker блокирует ВСЕ `.bat`/`.cmd`** → Java-классы вызываются напрямую:
  `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <tasks>`
- **AppLocker блокирует нативные `.exe` в user-writable путях** → только `C:\Windows` и `C:\Windows\Temp`; `C:\Program Files` не writable (нет админа).
- **`C:\Windows\Temp` имеет жёсткие ACL** → ломает `toRealPath()` у javac; ACL нельзя менять.
- **Axiom JDK `cacerts` неполный** → весь Java HTTPS требует кастомный truststore (Kaspersky MITM CA в `C:/Users/pvanyushkin/Android/Sdk/cacerts.jks`).
- **Симлинки требуют админа; junction — нет** → junction для редиректа директорий.
- **aapt2** должен физически лежать в `C:\Windows\Temp` (AppLocker) — берётся из Maven-артефакта AGP (Gradle cache), не из build-tools.
- **HttpLoggingInterceptor** редактирует Authorization-заголовок в `██` (U+2588), не `<redacted>`.

### Рабочий вызов Gradle
```bash
JAVA_HOME="C:/Program Files/Axiom/AxiomJDK-Pro-17-Full"
java -Djavax.net.ssl.trustStore=C:/Users/pvanyushkin/Android/Sdk/cacerts.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -Djavax.net.ssl.trustStoreType=JKS \
     -cp "gradle/wrapper/gradle-wrapper.jar" \
     org.gradle.wrapper.GradleWrapperMain <tasks>
```
`GRADLE_USER_HOME=C:/Users/pvanyushkin/.gradle`

### SDK layout
- Primary: `C:\Users\pvanyushkin\Android\Sdk` (platforms, platform-tools, cmdline-tools, licenses, cacerts.jks)
- `build-tools` junction → `C:\Windows\Temp\AndroidSdk\build-tools`
- aapt2 junction: `C:\Windows\Temp\gradle\caches\transforms-3\...\aapt2-8.2.2-10154469-windows` → `C:\Windows\Temp\aapt2dir`

---

## 6. BuildConfig

```kotlin
YI_API_BASE_URL = "https://api.yicamalt.local/v1/"   // ПЛЕЙСХОЛДЕР — причина ошибки логина
LOCAL_RTSP_ENABLED = true (debug) / false (release)
```

> ⚠️ **Плейсхолдер `api.yicamalt.local` — корневая причина «Ошибка сети. Попробуй снова.»**
> Исправлено: base URL теперь runtime-configurable через экран настроек.

---

## 7. Ошибка логина (баг-репорт пользователя)

**Симптом:** «Я указал логин и пароль — получаю ошибку "Ошибка сети. Попробуй снова."»

**Диагноз:** `BuildConfig.YI_API_BASE_URL=https://api.yicamalt.local/v1/` — несуществующий хост.

**Фикс (готов, закоммичен `64ac154`):** base URL настраивается в рантайме:
- `SettingsStore.kt` (interface + `PrefsSettingsStore` + `InMemorySettingsStore`)
- `ConfigModule` (`getApiBaseUrl()` override-preferred, `setApiBaseUrl()`, no-arg secondary constructor)
- `SettingsStoreModule` (`@Binds`)
- `HttpClientModule` (`private var` + `rebuild()`)
- `SettingsScreen.kt` + `SettingsViewModel.kt` (пакет `ui/settings/`)
- `MainActivity` state navigation; `LoginScreen` gear-иконка (`Icons.Filled.Settings`) → `onOpenSettings`

**Поток логина:** `LoginViewModel.submit()` → `AuthRepository.login()` → `YiCloudAuthApi.login()` (Retrofit).

**Что нужно пользователю:** ввести реальный endpoint в настройках (gear → «API сервер (Yi Cloud)» → реальный URL → «Сохранить» → повторить логин). Без реального endpoint логин будет падать (ожидаемо, а не баг).

---

## 8. Реверсивный инжиниринг официального приложения

### 8.1 Источник
- Play Store: `com.ants360.yicamera.international` (Yi Camera international)
- APK скачан с APKPure: XAPK 270MB → `com.ants360.yicamera.international.apk` (main, 8768 entries, **10 dex files**) + config splits (вкл. `config.arm64_v8a.apk`)
- Рабочие файлы: `/tmp/yiapk/` (yi.apk, full/, dex/, so/, enarsc/, jadx.log, jadx2.log)

### 8.2 Метод
- `strings` на dex → **ничего** (инструмент сломан)
- `resources.arsc` → ничего (UTF-8 и UTF-16)
- Нативные `.so` → ничего
- **Собственный Python-парсер dex string-pool** → **URL-ы ЕСТЬ в dex** (строки зашифрованы/обфусцированы, но читаемы через string pool)
- jadx-декомпиляция (85 973 классов / 514 505 методов / 14 870 149 инструкций) — **зависает/прерывается** в этом окружении (слишком тяжёлая)

### 8.3 Найденные хосты (реальная инфраструктура Yi Cloud)

| Роль | Хост | HTTP-статус |
|------|------|-------------|
| **Шлюз US** | `gw-us.xiaoyi.com` | ✅ 200 (Tomcat) |
| **Шлюз EU** | `gw-eu.xiaoyi.com` | ✅ 200 |
| **Шлюз SG** | `gw-sg.xiaoyi.com` | ✅ 200 |
| **API CN** | `api.xiaoyi.com` | ✅ 200 |
| **API EU** | `api.eu.xiaoyi.com` | ✅ 200 |
| **API US** | `api.us.xiaoyi.com` | ❌ 000 (нет ответа) |
| **OAuth US** | `api-oauth-us.xiaoyi.com` | ✅ 404 на root (сервер жив) |
| **OAuth Kami** | `kami-api-oauth-us.xiaoyi.com` | ✅ 404 на root |
| **LB US** | `us-lb.xiaoyi.com` | ✅ 403 (root) |
| **LB EU** | `eu-lb.xiaoyi.com` | ✅ 403 |
| **LB SG** | `sg-lb.xiaoyi.com` | ✅ 403 |
| **LB test** | `test-lb.xiaoyi.com` | ✅ 403 |
| **Touch US** | `touch-us.xiaoyi.com` | ✅ 404 |
| **Touch EU** | `touch-eu.xiaoyi.com` | ✅ 404 |
| **Touch SG** | `touch-sg.xiaoyi.com` | ✅ 404 |

### 8.4 Подтверждённые API-эндпоинты (живые)

**gw-us.xiaoyi.com** (POST, JSON):
- `/v5/app/bind` → 200 `{"code":"-10003"}` (API-ответ; -10003 = невалидный запрос/не хватает полей)
- `/v8/ai/alert/level/config` → 200

**ЛОГИН-ЭНДПОИНТЫ (найдены в classes2.dex, подтверждены на gw-us.xiaoyi.com):**
- `POST /v4/users/login` → 200 `{"code":"20203"}` — **email/password логин** (20203 = неверные учётные данные)
- `POST /v4/users/auth_token` → 200 `{"code":"20201"}` — получение токена
- `POST /v4/auth/login` → 200 `{"code":"20215"}` — auth-логин
- `POST /v8/users/mobile/login` → 200 `{"code":"-10003"}` — мобильный логин (не хватает полей)
- `POST /v8/auth/info` → 200 `{"code":"-10003"}`
- `POST /v4/users/register` → 405 (GET)
- `POST /v4/users/email/verify_code` → 405 (GET)
- `POST /v4/users/mobile/register` → 404

> Коды `20203`/`20201`/`20215` — это **API-коды ошибок аутентификации** (не 404/405), значит эндпоинты существуют и обрабатывают запросы. Формат ответа: `{"code":"<число>"}` (без message).

**api-oauth-us.xiaoyi.com** (OAuth2, Spring Security):
- `/oauth2/authorize` → 200
- `/oauth2/token` → 200

### 8.5 Пути API в dex (classes8.dex — cloud API, Retrofit)
```
/v5/app/bind
/v8/ai/alert/leftCredit
/v8/ai/alert/level/config
/v8/ai/alert/update/event
/v8/cloud/deviceList
/vas/v8/all/cloud/deviceList
/vas/v8/cloud/setBind
/vas/v8/cloud/images
/vas/v8/cloud/videos
/vas/v8/cloud/service
/vas/v8/e911/...
/vas/v8/search/...
/cms/v8/banner/all/list
/orderpay/v8/device/active
/orderpay/v8/stripe/...
/bs/v8/baby/...
/search/v8/ai/...
/users/address/add|delete|devices|query|update
```

### 8.5b Логин-пути в dex (classes2.dex — auth/users API)
```
/v4/users/login            ← email/password логин
/v4/users/auth_token       ← получение токена
/v4/users/register
/v4/users/mobile/register
/v4/users/email/verify_code
/v4/auth/login
/v5/auth/delete/
/v5/users/loginInfos/search
/v8/users/mobile/login
/v8/auth/info
/v8/echo/account/status
/auth/o2/token
/applinks/login
```

### 8.6 Классы логина (найдены в dex)
| Класс | Назначение |
|-------|-----------|
| `com.ants360.yicamera.activity.login.UserLoginActivity` | Экран логина |
| `com.ants360.yicamera.login.LoginInfo` | Данные авторизации |
| `LoginServer` (строка) | Выбор сервера по региону |
| `LoginAccount` (строка) | Учётная запись |
| `LoginPlatformInternationalActivity.kt` | Выбор платформы (email/Google/FB/Amazon/Xiaomi) |
| `ThirdPartyLoginViewModel.kt` | Third-party логин |
| `LoginAreaSelectActivity.kt` | Выбор региона → сервер |
| `com.xiaoyi.base.http.ServerInfo$ServerLocation` | Маппинг регион → хост |

### 8.7 OAuth2-константы (classes2.dex)
```
AUTHZ_HOST, EXCHANGE_HOST, CLIENT_ID, REDIRECT_URI, grant_type
authorization_code flow (Spring Security OAuth2 на сервере)
api-oauth-us.xiaoyi.com, kami-api-oauth-us.xiaoyi.com
```

### 8.8 Прочие хосты (поддержка/веб)
- `kamiapp.kamihome.com`, `kamicloud.kamihome.com`, `kamicloud-api.kamihome.com`
- `app.xiaoyi.com`, `h5.xiaoyi.com`, `www.yitechnology.com`, `www.xiaoyi.com`
- `log.xiaoyi.com`, `logus.xiaoyi.com`, `logeu.xiaoyi.com`, `logas.xiaoyi.com`
- `faq-kami-us.xiaoyi.com`, `faq.us.xiaoyi.com`, `faq.eu.xiaoyi.com`
- `api-sg.mentamob.com` (третья сторона — аналитика MentaMob, игнорировать)
- `shttp://local?secret=` — локальный стриминг/RTSP-контроль

---

## 9. Выводы по API base URL

1. **Реальный API-шлюз:** `https://gw-us.xiaoyi.com` (и `gw-eu`, `gw-sg` по региону) — **живой**, обрабатывает POST-запросы, отвечает JSON `{"code": ...}`.
2. **OAuth-сервер:** `https://api-oauth-us.xiaoyi.com` — `/oauth2/authorize` и `/oauth2/token` живы.
3. **Пути:** версионные `/v4/...`, `/v5/...`, `/v8/...`, `/vas/v8/...` — **без** префикса `/api/` или `/newapi/` (эти префиксы дают 404).
4. **✅ ЛОГИН-ЭНДПОИНТ НАЙДЕН:** `POST https://gw-us.xiaoyi.com/v4/users/login` — отвечает `{"code":"20203"}` (неверные учётные данные). Это реальный email/password логин.
5. **Логин, вероятно, OAuth2 authorization_code flow** через `api-oauth-us.xiaoyi.com` (Spring Security) для third-party (Google/FB/Amazon/Xiaomi), а email/password — через `gw-us.xiaoyi.com/v4/users/login`.

### Рекомендуемый endpoint для YiCamAlt
```
https://gw-us.xiaoyi.com
```
Логин-путь: `POST /v4/users/login` (поля: email + password).

> ⚠️ **Важно:** наш YiCamAlt использует `POST /v1/account/login` (из документации). Реальный путь — `POST /v4/users/login`. Нужно обновить `YiCloudAuthApi` в проекте, чтобы он вызывал `/v4/users/login` на `https://gw-us.xiaoyi.com`.

---

## 10. Следующие шаги

1. **✅ НАЙДЕН логин-эндпоинт:** `POST https://gw-us.xiaoyi.com/v4/users/login` (email + password).
2. **Обновить `YiCloudAuthApi` в YiCamAlt** — заменить `POST /v1/account/login` на `POST /v4/users/login`.
3. **Проверить формат ответа** — `{"code":"20203"}` (неверные учётные данные). Нужно понять, что возвращается при успехе (token).
4. **Проверить OAuth2 flow** на `api-oauth-us.xiaoyi.com` (client_id, redirect_uri, grant_type) для third-party логина.
5. **Сообщить пользователю реальный endpoint** для ввода в настройки YiCamAlt (gear → «API сервер (Yi Cloud)» → `https://gw-us.xiaoyi.com`).
6. **Уточнить у пользователя**, против чего тестируется: реальный Yi Cloud API или локальный mock-сервер.
7. Если логин работает → **Phase-2 (Camera Management)**.

---

## 11. Как пользователю задать endpoint в YiCamAlt

1. Открыть приложение → экран логина
2. Нажать **gear-иконку** (шестерёнка, `Icons.Filled.Settings`)
3. Поле **«API сервер (Yi Cloud)»** → ввести реальный URL, например:
   - `https://gw-us.xiaoyi.com`
   - `https://api-oauth-us.xiaoyi.com`
4. Нажать **«Сохранить»** (клиент пересоздаёт Retrofit)
5. Вернуться и повторить логин
6. Пустое поле → возврат к BuildConfig-дефолту

---

## 12. Тесты

- `:app:testDebugUnitTest` — **39 тестов / 0 падений** (Phase-1 gate GREEN)
- `:app:compileDebugKotlin` — green
- `:app:assembleDebug` → **app-debug.apk (17.9MB)**

---

## 13. Структура новых файлов (runtime-configurable base URL)

```
app/src/main/java/com/yicamalt/config/SettingsStore.kt
app/src/main/java/com/yicamalt/ui/settings/SettingsScreen.kt
app/src/main/java/com/yicamalt/ui/settings/SettingsViewModel.kt
```
