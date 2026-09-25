# Эмулятор и перехват трафика Yi Home на другом ПК

Инструкция предназначена для переноса текущего окружения на компьютер, где нельзя получить права администратора. Все команды выполняются из корня репозитория в PowerShell.

## 1. Требования

- Windows 10/11 x64.
- Git for Windows с Git LFS.
- Python 3.11 x64.
- Android SDK Command-line Tools, Platform 34 и системный образ `system-images;android-34;google_apis;x86_64`.
- Android Emulator и Platform Tools.
- Свободный TCP-порт `8080`.
- Вход в Yi Home выполняйте только вручную на эмуляторе. Логин, пароль, токены и cookie не записывайте в документацию или Git.

Если корпоративная политика AppLocker блокирует запуск EXE из пользовательских каталогов, установите или скопируйте Android SDK и Python в разрешённый каталог, например `C:\Tools\AndroidSdk` и `C:\Tools\Python311`.

## 2. Клонирование проекта

```powershell
git clone https://github.com/Paul771/YiCamAlt.git
Set-Location .\YiCamAlt
git lfs install
git lfs pull
```

Проверьте, что бинарные файлы загрузились:

```powershell
git lfs ls-files
Test-Path .\_apk\extracted\base.apk
```

## 3. Установка компонентов Android SDK

Пример для SDK в `C:\Tools\AndroidSdk`:

```powershell
$env:ANDROID_SDK_ROOT = 'C:\Tools\AndroidSdk'
& "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_SDK_ROOT" --licenses
& "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_SDK_ROOT" "platform-tools" "emulator" "platforms;android-34" "system-images;android-34;google_apis;x86_64"
```

Если `cmdline-tools` отсутствует, скачайте Android SDK Command-line Tools для Windows и распакуйте его в `$env:ANDROID_SDK_ROOT\cmdline-tools\latest`.

## 4. Создание AVD

```powershell
"No" | & "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\avdmanager.bat" create avd `
  --force `
  --name yicap `
  --package "system-images;android-34;google_apis;x86_64" `
  --device pixel
```

Откройте `%USERPROFILE%\.android\avd\yicap.avd\config.ini` и задайте компактный размер окна:

```ini
hw.ramSize=3072
vm.heapSize=512
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=320
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.keyboard=yes
disk.dataPartition.size=6G
```

Не используйте размер `1080x2280`: на экранах с высоким DPI окно эмулятора может оказаться за верхней границей.

## 5. Установка mitmproxy

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install mitmproxy==11.0.2
.\.venv\Scripts\mitmdump.exe --version
```

Запуск перехвата и сохранение потоков:

```powershell
New-Item -ItemType Directory -Force captures | Out-Null
.\.venv\Scripts\mitmdump.exe `
  --listen-host 0.0.0.0 `
  --listen-port 8080 `
  --set block_global=false `
  --set connection_strategy=lazy `
  --save-stream-file captures\yi-home.flows
```

Оставьте это окно запущенным. Для просмотра сохранённого трафика отдельно запустите:

```powershell
.\.venv\Scripts\mitmweb.exe -r captures\yi-home.flows
```

## 6. Запуск эмулятора

В отдельном окне PowerShell:

```powershell
$env:ANDROID_SDK_ROOT = 'C:\Tools\AndroidSdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:ANDROID_AVD_HOME = "$env:USERPROFILE\.android\avd"

Start-Process `
  -FilePath "$env:ANDROID_SDK_ROOT\emulator\emulator.exe" `
  -ArgumentList '-avd','yicap','-writable-system','-no-snapshot','-no-boot-anim','-no-audio','-no-metrics','-crash-report-mode','disabled','-gpu','host' `
  -WindowStyle Normal
```

Дождитесь загрузки Android:

```powershell
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" wait-for-device
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" shell getprop sys.boot_completed
```

Если окно всё ещё находится за верхней границей экрана, выберите его через `Alt+Tab` и нажмите `Alt+Space`, затем `M` и стрелку вниз. После появления заголовка окно можно перетащить мышью. Также можно применить `Win+Shift+Left` для привязки к левой половине экрана.

## 7. Установка CA-сертификата mitmproxy

Сначала один раз запустите mitmdump, чтобы он создал `$env:USERPROFILE\.mitmproxy\mitmproxy-ca-cert.pem`.

Для стандартного mitmproxy CA используется имя:

```text
0d0f75c86196c3eb8bd1f3ac6403a28d.0
```

Установите сертификат в Android 14 emulator:

```powershell
$adb = "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
$ca = "$env:USERPROFILE\.mitmproxy\mitmproxy-ca-cert.pem"
$certName = '0d0f75c86196c3eb8bd1f3ac6403a28d.0'

& $adb root
& $adb wait-for-device
& $adb remount
& $adb push $ca /data/local/tmp/$certName
& $adb shell "cp /data/local/tmp/$certName /system/etc/security/cacerts/$certName"
& $adb shell "chmod 644 /system/etc/security/cacerts/$certName"
& $adb shell "chown root:root /system/etc/security/cacerts/$certName"
& $adb shell "mkdir -p /apex/com.android.conscrypt/cacerts"
& $adb shell "cp /data/local/tmp/$certName /apex/com.android.conscrypt/cacerts/$certName"
& $adb shell "chmod 644 /apex/com.android.conscrypt/cacerts/$certName"
& $adb shell "chown root:root /apex/com.android.conscrypt/cacerts/$certName"
& $adb reboot
& $adb wait-for-device
```

Проверьте обе копии:

```powershell
& $adb shell "ls -l /system/etc/security/cacerts/$certName"
& $adb shell "ls -l /apex/com.android.conscrypt/cacerts/$certName"
```

Если сгенерированный CA имеет другой OpenSSL subject hash, замените `$certName` перед копированием.

## 8. Настройка прокси

Адрес `10.0.2.2` — это шлюз Android Emulator к хостовому компьютеру.

```powershell
& $adb shell settings put global http_proxy 10.0.2.2:8080
& $adb shell settings get global http_proxy
```

Ожидаемый вывод:

```text
10.0.2.2:8080
```

Проверка прокси с хоста:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen
curl.exe -x http://127.0.0.1:8080 -I http://example.com/
```

## 9. Установка Yi Home

```powershell
& $adb install-multiple -r `
  .\_apk\extracted\base.apk `
  .\_apk\extracted\split_config.arm64_v8a.apk `
  .\_apk\extracted\split_config.xxhdpi.apk `
  .\_apk\extracted\split_config.en.apk `
  .\_apk\extracted\split_config.ru.apk
```

x86_64 emulator использует ARM64 translation из Google APIs system image.

Очистите старые настройки приложения и запустите его:

```powershell
& $adb shell pm clear com.ants360.yicamera.international
& $adb shell am start -n com.ants360.yicamera.international/.LoginAreaSelectActivity
```

Введите логин и пароль вручную. Не сохраняйте их в командной строке, shell history или Git.

## 10. Проверка результата

Если mitmproxy показывает потоки, сохраните их в `captures/yi-home.flows`. Каталог `captures/` уже исключён из Git, потому что flow-файлы могут содержать токены, cookie и другие секреты.

Если приложение всё ещё показывает `Network connection timeout`:

1. Проверьте `http_proxy` через `adb shell settings get global http_proxy`.
2. Проверьте, что mitmdump запущен и слушает порт `8080`.
3. Проверьте обе копии CA-сертификата.
4. Перезапустите приложение после настройки прокси и сертификата.
5. Отключите VPN и другие прокси на хосте.
6. Проверьте `mitmdump`: если соединение обрывается до появления запроса, вероятна проверка TLS или certificate pinning. Обычный системный CA не bypass-ит application pinning.

## 11. Диагностика

Сохранённые логи ADB:

```powershell
& $adb logcat -c
& $adb logcat -v threadtime | Tee-Object -FilePath captures\adb-logcat.txt
```

Состояние прокси:

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
Get-Process mitmdump -ErrorAction SilentlyContinue
```

Не добавляйте `captures/`, `*.flows`, `*.logcat.txt`, логины, пароли, токены или cookie в Git.
