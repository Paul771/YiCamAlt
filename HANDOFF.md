# HANDOFF — старт работы агента на новом компьютере

Единая точка входа. Этот файл нужен, чтобы агент на другой машине получил план, статус и всё необходимое окружение только из GitHub, без доступа к предыдущей сессии.

## 0. TL;DR

- **Проект:** YiCamAlt — альтернативный Android-клиент камер Yi Home / Yi Dome без платной подписки (GRACE-планирование, Kotlin + Jetpack Compose).
- **Фаза:** Phase-1 (Auth) — verified; Phase-2 (Camera Management) — in progress.
- **Ближайшая задача (2026-09-29):** снять реальный запрос списка камер через эмулятор + mitmproxy с системным CA. Эндпоинт подписи найден зондом, но реальный endpoint домашнего списка камер не найден перебором.
- **Если времени мало:** прочитать `docs/PROJECT_STATUS.md` (разделы 0 и 14), затем `docs/development-plan.xml` (Phase-2), затем `INSTRUCTIONS.md`.

## 1. Получить репозиторий

```powershell
git clone https://github.com/Paul771/YiCamAlt.git
Set-Location .\YiCamAlt
git lfs install
git lfs pull
```

Если репозиторий уже есть, достаточно:

```powershell
git pull --ff-only origin master
git lfs pull
```

Проверка, что контекст на месте:

```powershell
git log -1 --oneline
git status --short --branch
@(
  'AGENTS.md',
  'HANDOFF.md',
  'INSTRUCTIONS.md',
  'docs/PROJECT_STATUS.md',
  'docs/requirements.xml',
  'docs/technology.xml',
  'docs/development-plan.xml',
  'docs/verification-plan.xml',
  'docs/knowledge-graph.xml',
  'docs/operational-packets.xml',
  'gradle/wrapper/gradle-wrapper.jar'
) | ForEach-Object { "{0,-45} {1}" -f $_, (Test-Path $_) }
```

Ожидаемо: все строки `True`, ветка `master...origin/master`, рабочее дерево чистое.

Если файлы плана отсутствуют после `git pull`:

```powershell
git remote -v
git rev-parse --abbrev-ref HEAD
git fetch origin
git log --oneline -3 origin/master
git checkout master
git reset --hard origin/master
```

Если `git lfs pull` не отработал (нет сети или LFS не установлен), план и статус всё равно доступны — они обычные текстовые файлы. LFS нужен только для APK в `_apk/`.

## 2. Порядок чтения

| Файл | Что брать |
|---|---|
| `AGENTS.md` | Протокол GRACE: контракты, семантическая разметка, правила верификации. Действует для всех правок кода. |
| `HANDOFF.md` | Этот файл: bootstrap, загрузки, проверки окружения. |
| `docs/PROJECT_STATUS.md` | Текущий статус, фазы, найденные хосты и эндпоинты, баги, следующие шаги. |
| `docs/development-plan.xml` | План работ: модули, контракты, фазы, data flows. |
| `docs/verification-plan.xml` | Что и как проверяется: тесты, команды, log-маркеры, гейты. |
| `docs/knowledge-graph.xml` | Навигация по модулям и связям. |
| `docs/requirements.xml`, `docs/technology.xml` | Требования и стек. |
| `INSTRUCTIONS.md` | Пошаговая инструкция: эмулятор, mitmproxy, CA, установка Yi Home. |
| `_apk/extracted/` | Оригинальный APK Yi Home (в LFS) — эталон для реверс-инжиниринга. |

Навигация внутри кода — по якорям `START_MODULE_CONTRACT`, `START_CONTRACT:`, `START_BLOCK_`, `START_CHANGE_SUMMARY` и ID модулей `M-*` / верификаций `V-M-*`.

## 3. Что нужно скачать

Обязательное:

| Инструмент | Версия | Зачем | Как получить |
|---|---|---|---|
| Git for Windows | любая свежая | клонирование, коммиты | `https://git-scm.com/download/win` (Git LFS включён в комплект) |
| JDK | 17 | сборка Android-модуля | Temurin/Adoptium JDK 17, `https://adoptium.net/temurin/releases/?version=17` |
| Android SDK Command-line Tools | актуальная | `adb`, `avdmanager`, `sdkmanager` | `https://developer.android.com/studio` → Command-line Tools, либо Android Studio |
| Android Platform-Tools | актуальная | `adb` | `sdkmanager "platform-tools"` |
| Android Emulator | актуальная | AVD для перехвата трафика | `sdkmanager "emulator"` |
| Android Platform 34 | 34 | сборка и запуск | `sdkmanager "platforms;android-34"` |
| System image | `system-images;android-34;google_apis;x86_64` | AVD с root/adb remount | `sdkmanager "system-images;android-34;google_apis;x86_64"` |
| Python | 3.11 x64 | mitmproxy | `https://www.python.org/downloads/` (версия 3.11 обязательна) |
| mitmproxy | 11.0.2 | перехват HTTPS | `py -3.11 -m pip install mitmproxy==11.0.2` |

Опциональное:

| Инструмент | Зачем | Как получить |
|---|---|---|
| jadx | декомпиляция dex из `_apk` | `https://github.com/skylot/jadx/releases` |
| apktool | ресурсы и манифест | `https://apktool.org` |
| OpenSSL | subject hash для имени CA-файла Android | `https://github.com/openssl/openssl/releases` |

Не нужно скачивать:

- **Gradle** — wrapper в репозитории (`gradle/wrapper/gradle-wrapper.jar` + `.properties`).
- **APK Yi Home** — уже в репозитории под Git LFS (`_apk/`).
- **Документы плана/статуса** — обычные файлы в репозитории.

## 4. Что проверить на новой машине

1. **AppLocker / Group Policy.** Если `.cmd`/`.bat` заблокированы, запускайте Gradle напрямую:
   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
   & "$env:JAVA_HOME\bin\java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest
   ```
2. **Исполняемые файлы в пользовательских каталогах.** Если корпоративная политика блокирует `.exe` из user-writable путей, разместите Android SDK и Python в разрешённом каталоге (например `C:\Tools\...`) и укажите `ANDROID_SDK_ROOT`.
3. **Кастомный truststore.** Если корпоративный TLS-перехват (MITM CA) ломает HTTPS для Java/Gradle, нужен `cacerts.jks` с корпоративным корнем. Он **не** в репозитории (машино-зависимый):
   ```powershell
   & "$env:JAVA_HOME\bin\keytool.exe" -importcert -noprompt `
     -alias corp -file corp-root.cer `
     -keystore "$env:USERPROFILE\Android\Sdk\cacerts.jks" `
     -storepass changeit -storetype JKS
   ```
   И передавать его в Gradle:
   ```powershell
   & "$env:JAVA_HOME\bin\java.exe" `
     -Djavax.net.ssl.trustStore="$env:USERPROFILE\Android\Sdk\cacerts.jks" `
     -Djavax.net.ssl.trustStorePassword=changeit `
     -Djavax.net.ssl.trustStoreType=JKS `
     -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <tasks>
   ```
   Симптом без truststore: `PKIX path building failed` / `SSLHandshakeException` при загрузке зависимостей.
4. **`adb` не в PATH.** Используйте полный путь: `$env:ANDROID_SDK_ROOT\platform-tools\adb.exe`.
5. **Порт 8080 свободен** — иначе mitmproxy не стартует.
6. **Python 3.11, не 3.13/3.14.** На 3.14 mitmproxy ломает TLS (`Recv failure: Connection was reset`).

## 5. Куда писать результат

1. Работать в ветке `master` (или своей feature-ветке от `master`).
2. Коммиты — по образцу существующей истории (`grace(M-...): ...`, `fix: ...`).
3. После успешных изменений: обновить `docs/PROJECT_STATUS.md` (раздел 0 и релевантные разделы), при изменении модулей — `docs/knowledge-graph.xml`, при изменении тестов/команд/лог-маркеров — `docs/verification-plan.xml`.
4. Отправлять в GitHub: `git push origin master`.
5. Проверка перед отправкой: `git status --short --branch` (чисто), `git log -1 --oneline`, при необходимости `:app:testDebugUnitTest`.

## 6. Секреты и персональные данные

- Логин и пароль Yi-аккаунта вводятся пользователем вручную и **не** должны появляться в чате, командах, логах, коммитах или документации.
- `captures/` (flow-файлы mitmproxy), `auth_log.txt`, токены и cookie не коммитить — они исключены из Git, но проверяйте `git status` перед коммитом.
- Логи приложения уже маскируют `password=`, `account=` и `Authorization` значениями `██`. Не отключайте `RedactionScanner` и не возвращайте plaintext-пароль в URL.
