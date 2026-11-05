# Релизная сборка YiCamAlt

Связанный модуль: `M-RELEASE-BUILD`. Решения фазы: `.planning/phases/06-release-build/06-CONTEXT.md` (D-01, D-02).

## 1. Что это

- Релиз — подписанный release-APK `app-release.apk`, который ставится на свой телефон.
- R8 / минификация выключены (D-02): JNI-мост к вендорским библиотекам ищется по именам классов и методов, обфускация его сломает.
- ABI: `arm64-v8a` и `armeabi-v7a`.
- Вендорские `.so` (PPPP / TUTK) извлекаются при сборке из **вашего собственного** APK Yi Home (см. `HANDOFF.md`, раздел 3). В Git их нет и быть не должно.

## 2. Создать ключ подписи (один раз)

Один ключ на все обновления (D-01). Скрипт создаёт его вне репозитория, в `%USERPROFILE%\.yicamalt\`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/new-release-key.ps1
```

- Появятся два файла: `yicamalt-release.p12` (ключ, PKCS12, RSA 4096) и `release-signing.properties` (путь к ключу, псевдоним и пароль).
- Пароль по умолчанию генерируется (32 символа, криптографический генератор) и записывается только в `release-signing.properties`. На экран он не выводится.
- Свой пароль: добавьте `-Prompt` (ввод дважды, скрыто; от 12 символов, без `\` `#` `!` `=` `:` и без пробелов по краям).
- Проверка без вывода значений:
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/new-release-key.ps1 -Check
  ```
  Ожидаемо: `keys=4/4 store=found acl=ok`. `acl=open` значит, что к файлам есть доступ не только у вашей учётной записи.
- Повторный запуск ничего не перезапишет: `code=RELEASE_KEY_EXISTS` (код выхода 3). Папка внутри репозитория отклоняется: `code=RELEASE_KEY_PATH_IN_REPO`.
- Другая папка: `-OutDir <папка>`. Тогда Gradle нужно сказать, где лежит файл свойств: строка `yicamaltSigningFile=<путь к release-signing.properties>` в `%USERPROFILE%\.gradle\gradle.properties` (не в `gradle.properties` репозитория).
- Содержимое `release-signing.properties` не открывайте при посторонних, не копируйте в чат, тикеты и логи. Для проверки достаточно `-Check`.

## 3. Резервная копия (делает пользователь)

Скрипт резервную копию не делает — это ваша задача, сразу после создания ключа:

- Скопируйте **оба** файла — `yicamalt-release.p12` и `release-signing.properties` — на внешний носитель или в менеджер паролей (как вложение). Храните копию офлайн.
- **Потеря ключа:** новые версии больше не встанут поверх установленной. Останется только удалить приложение — вместе с ним сбросятся вход и данные приложения.
- **Утечка ключа:** любой, у кого есть оба файла, сможет подписать «обновление», которое телефон примет как настоящее.
- Не кладите файлы в облачные папки без шифрования. Никогда — в репозиторий (хук `pre-commit` блокирует `.p12` / `.jks` / `.keystore` / `.pfx` и `release-signing.properties`, но это последний рубеж, а не разрешение).

## 4. Сборка

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleRelease
```

Успех: `BUILD SUCCESSFUL` и `--- gradle exit=0`. Результат: `app/build/outputs/apk/release/app-release.apk`.

## 5. Проверка

- `powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-release-apk.ps1` — проверка подписи (v2/v3), ABI (только arm64-v8a и armeabi-v7a), 8 вендорских .so по `app/vendor-natives.sha256`, версии и `debuggable=false`. Успех: `RESULT=PASS`. С `-CompareApk app/build/outputs/apk/debug/app-debug.apk` печатает `signerSame=<true|false>` (у release другой ключ, чем у debug: первую установку поверх debug-сборки нужно делать после удаления). `-SkipVersion` отключает проверку версии. Вывод не содержит путей, DN и отпечатков сертификата.
- `powershell -NoProfile -ExecutionPolicy Bypass -File tools/clean-clone-build.ps1` — сборка из чистого клона текущего коммита во временной папке (вне репозитория), чтобы убедиться, что ничего не берётся из рабочей копии. Передаёт только `-PvendorApkDir` (по умолчанию `_apk` репозитория), собирает `:app:assembleRelease --no-build-cache`, сверяет подписанта с APK из репозитория (`signerSame=true`), проверяет, что в клоне не изменилось ни одного отслеживаемого файла, и удаляет клон. Успех: `RESULT=PASS`. Требует закоммиченного рабочего дерева. Ключи: `-VendorApk <file>`, `-Offline` (при ошибках загрузки/trustStore), `-KeepClone`.

## 6. Установка

- Первая установка поверх debug-версии **невозможна**: у debug и release разные подписи. Сначала удалите debug-версию — при этом сбросятся вход и данные приложения.
- Дальше релизы ставятся поверх тем же ключом: `adb install -r app/build/outputs/apk/release/app-release.apk`.

## 7. Версии

- Первая версия: `versionName 1.0.0`.
- `versionCode = major*10000 + minor*100 + patch` (1.0.0 → 10000). Только по возрастанию — иначе Android не поставит обновление.

## 8. Осторожно с выводом

- Не цитируйте строки AGP / Gradle, в которых виден путь к keystore (в отчётах, чатах, issue).
- Промежуточные файлы AGP с данными подписи лежат только в `app/build` и в клоне проверки. `tools/clean-clone-build.ps1 -KeepClone` оставляет клон в TEMP — удалите его после просмотра.
