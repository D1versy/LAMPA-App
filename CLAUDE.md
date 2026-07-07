# CLAUDE.md — LAMPA-App (Android TV клиент для домашнего Lampac)

Форк **`lampa-app/LAMPA`** (origin: `github.com/D1versy/LAMPA-App`, пакет `top.rootu.lampa`) — нативное Android / **Android TV** приложение-клиент для домашнего медиасервера.

Это **третий репозиторий** экосистемы (см. `E:\Media-server\CLAUDE.md`):
| Репо | Роль |
|---|---|
| `E:\Media-server` | оркестрация (docker-compose, конфиги) |
| `E:\lampac` | форк сервера Lampac (модуль QbitDownload, `qdl.js`) |
| **`E:\LAMPA-App`** (этот) | **клиент под Android TV**, коннектится к серверу `http://192.168.87.24:9118` |

## Зачем форк / что изменено
Задача: приложение под Android TV, которое **сразу** открывает наш сервер `http://192.168.87.24:9118` со всеми кодеками.

**Все правки — в [app/build.gradle](app/build.gradle)** (upstream-код не трогали → лёгкий rebase):
1. `def defaultServerUrl = "http://192.168.87.24:9118"` → в `BuildConfig.defaultAppUrl` всех флейворов. При первом запуске приложение грузит наш сервер без диалога ввода URL.
2. Флейвор `lite`: **`enableUpdate=false`**. Иначе self-update тянет STOCK-APK из чужого upstream (`api.github.com/repos/lampa-app/LAMPA`, см. `helpers/Updater.kt`) и затирает наш зашитый адрес сервера. Гейт — `App.kt:113 if (BuildConfig.enableUpdate)`.
3. Релизная `signingConfigs.release` обёрнута в `else if (System.getenv('KEYSTORE_FILE'))` — без keystore `file(null)` роняло даже debug-сборку.

Больше ничего менять **не потребовалось**, потому что:
- **Cleartext HTTP уже разрешён** — [network_security_config.xml](app/src/main/res/xml/network_security_config.xml) содержит `<base-config cleartextTrafficPermitted="true">`, так что `http://192.168.87.24:9118` (без TLS, LAN) работает из коробки.
- **Кнопка «Скачать» из `qdl.js` появляется сама.** Приложение — это управляемый WebView, который грузит **удалённый** интерфейс с `appUrl` ([MainActivity.kt:391](app/src/main/java/top/rootu/lampa/MainActivity.kt#L391) `browser.loadUrl(LAMPA_URL)`). Т.е. оно открывает `index.html` НАШЕГО Lampac, а тот сервер-сайд авто-инжектит `/lampainit.js` → `/qdl.js`. Никаких ручных плагинов добавлять не нужно.

## Кодеки = внешний плеер (важно понимать)
Приложение **само видео НЕ декодирует**. Воспроизведение уходит во внешний плеер через `Intent(ACTION_VIEW)` ([AndroidJS.kt:432](app/src/main/java/top/rootu/lampa/AndroidJS.kt#L432) `openPlayer` → `MainActivity.runPlayer`). Fallback — HTML5-плеер внутри WebView (кодеки ограничены: обычно нет AC3/EAC3/DTS).

**Чтобы были «все кодеки» (AC3/EAC3/DTS/TrueHD/HEVC/HDR) — поставить на ТВ внешний плеер и выбрать его в приложении:**
- **ViMu Player** (`net.gtvbox.vimuhd`) — рекомендованный для Android TV (passthrough звука, HEVC/HDR). Приложение уже умеет его находить.
- Альтернативы: **MX Player Pro**, **VLC**, **just-player** (media3/ExoPlayer).

Выбор плеера — в самом Lampa-интерфейсе (при первом запуске плеера предложит список) или Настройки Lampa → плеер. Оффлайн-файлы из «Загрузок» (`/qdl/stream`) играют по тому же пути → тоже через внешний плеер.

## Сборка
Поднят **self-contained тулчейн прямо в репо** — папка `.toolchain/` (в `.gitignore`, в гит не уедет), система не трогается:
- `.toolchain/jdk/` — JDK 17 (Temurin)
- `.toolchain/android-sdk/` — Android SDK: `platform-34`, `build-tools;34.0.0`, `platform-tools`; лицензии приняты (`.../licenses/`)
- `.toolchain/gradle-home/` — `GRADLE_USER_HOME` (дистрибутив Gradle 7.5.1 + кэш зависимостей, тоже вне системы)
- `local.properties` → `sdk.dir=E:/LAMPA-App/.toolchain/android-sdk` (gitignored)

**Пересобрать APK** (PowerShell; тулчейн уже установлен):
```powershell
$tc='E:\LAMPA-App\.toolchain'
$env:JAVA_HOME=(Get-ChildItem "$tc\jdk" -Directory)[0].FullName
$env:ANDROID_HOME="$tc\android-sdk"; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME="$tc\gradle-home"
cmd /c "E:\LAMPA-App\gradlew.bat -p E:\LAMPA-App --no-daemon assembleLiteDebug"
#  → app/build/outputs/apk/lite/debug/app-lite-debug.apk   (~14.7 МБ, debug-подпись)
```
Собрано и проверено: `top.rootu.lampa`, leanback-лаунчер (Android TV), `BuildConfig.defaultAppUrl = http://192.168.87.24:9118`.

Флейворы: **`lite`** (собран; апдейтер + Crosswalk shared-lib), `full` (встраивает Crosswalk core, ~50 МБ AAR), `ruStore`.

> **JDK:** сборка на **JDK 17** (в `.toolchain/`). Системный JDK не нужен. Если собирать своим — годится JDK 17 или 11, **но НЕ 21** (Gradle 7.5.1 его не тянет).
> **Release-подпись:** задать env `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`RELEASE_SIGN_KEY_ALIAS`/`RELEASE_SIGN_KEY_PASSWORD` (или `app/keystore/keystore_config`) → `assembleLiteRelease`. Без keystore релиз-подпись **пропускается** — обёрнута в `else if (System.getenv('KEYSTORE_FILE'))` в [app/build.gradle](app/build.gradle), иначе `file(null)` роняет даже debug-сборку на конфигурации.

Установка на ТВ:
```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/lite/debug/app-lite-debug.apk
```
(или закинуть APK через Downloader/файловый менеджер на ТВ).

> `versionCode`/`versionName` берутся из git (`rev-list --count origin/main` / `describe --tags`). На форке без тегов будет `0.0.0`/`1` — не критично.

## Гочи
- Стартовый адрес — **дефолт**, а не жёсткая привязка. Если приложение уже запускалось со старым URL, он лежит в SharedPreferences (`settings/url`) и перебивает дефолт → очистить данные приложения или сменить сервер в диалоге (меню в интерфейсе).
- Меняется адрес сервера — правится одна строка `defaultServerUrl` в [app/build.gradle](app/build.gradle), пересборка.
- Полная база знаний — [claude/README.md](claude/README.md).

## Правила (наследуются от основного репо)
- ⚠️ В коммитах **НЕ указывать соавторство Anthropic** (требование владельца).
- Общение и контент — на русском.
