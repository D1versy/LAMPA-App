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

**Исторически все правки были в [app/build.gradle](app/build.gradle)** (upstream-код не трогали → лёгкий rebase). Теперь правок больше: `strings.xml` (ребренд `app_name`), `MainActivity`, `SysView`, `XWalk`, `helpers` (мульти-хост фолбек + UA-токен, см. раздел «Фолбек хостов и OTA» ниже). Исходные правки build.gradle:
1. `def defaultServerUrl = "http://192.168.87.24:9118"` → в `BuildConfig.defaultAppUrl` всех флейворов. При первом запуске приложение грузит наш сервер без диалога ввода URL.
2. Флейвор `lite`/`full`: **`enableUpdate=true`** — self-update теперь тянет НАШ APK с сервера Lampac (не upstream GitHub), см. раздел «OTA-самообновление» ниже. `ruStore` — `false` (там удалён `REQUEST_INSTALL_PACKAGES`, обновление через магазин). Гейт — `App.kt:113 if (BuildConfig.enableUpdate)`.
3. Релизная `signingConfigs.release` обёрнута в `else if (System.getenv('KEYSTORE_FILE'))` — без keystore `file(null)` роняло даже debug-сборку.

Больше ничего менять **не потребовалось**, потому что:
- **Cleartext HTTP уже разрешён** — [network_security_config.xml](app/src/main/res/xml/network_security_config.xml) содержит `<base-config cleartextTrafficPermitted="true">`, так что `http://192.168.87.24:9118` (без TLS, LAN) работает из коробки.
- **Кнопка «Скачать» из `qdl.js` появляется сама.** Приложение — это управляемый WebView, который грузит **удалённый** интерфейс с `appUrl` ([MainActivity.kt:391](app/src/main/java/top/rootu/lampa/MainActivity.kt#L391) `browser.loadUrl(LAMPA_URL)`). Т.е. оно открывает `index.html` НАШЕГО Lampac, а тот сервер-сайд авто-инжектит `/lampainit.js` → `/qdl.js`. Никаких ручных плагинов добавлять не нужно.

## Фолбек хостов и OTA (D1Vision)
Канонический документ по всей клиентской стратегии — **`E:\Media-server\claude\08-clients.md`**.

**Хосты (приоритет кандидатов)**:
1. кастомный адрес из настроек пользователя (если задан);
2. `http://192.168.87.24:9118` — LAN, **primary** (участвует в гонке при каждом старте — не залипаем на фолбеке);
3. `https://tv.d1versy.com:9443` — фолбек №1, внешний вход (Caddy + периметр по ключу);
4. `https://tv2.d1versy.com:9443` — фолбек №2 (резервный домен);
5. хосты из OTA-кэша (`/d1vision/hosts.json`).

**Как выбирается хост**: при старте `HostResolver.resolve` гонит ВСЕХ кандидатов параллельно — `GET <host>/lampainit.js`, таймаут 2.5 с, успех = HTTP 200. Побеждает самый приоритетный из ответивших: успех кандидата, выше которого живых не осталось, — мгновенно; менее приоритетный успех ждёт старших не дольше grace 300 мс (LAN не проигрывает интернет-хосту из-за джиттера); проигравшие пробы отменяются. Кастомный хост пользователя (кандидат №1) — «защищённый»: grace его не обгоняет, победа уходит другим только после провала его пробы. Худший случай ≈ 2.5 с (раньше — 2.5 с × число мёртвых хостов). Если WebView падает с ошибкой загрузки — последовательный перебор со следующего хоста (`nextHost`, без ре-пробы); диалог ошибки пользователю — только когда исчерпаны ВСЕ.

- **`fallbackHosts` в [app/build.gradle](app/build.gradle)** — единственная точка bootstrap-списка хостов в бинаре.
- **OTA-кэш**: после успешного старта клиент забирает `GET /d1vision/hosts.json` с сервера (`{"ver":1,"brand":"D1Vision","hosts":[...]}`), кэширует в SharedPreferences и на следующем запуске добавляет к bootstrap. ⚠️ OTA-список только **ДОПОЛНЯЕТ** зашитый bootstrap, никогда не заменяет (защита от «окирпичивания»).
- **UA-токен**: приложение добавляет в User-Agent суффикс ` d1vision_android/<версия>` (прежний ` lampa_client` остаётся). Сервер по токену сам форсит `platform/player/player_torrent/player_iptv=android` + `internal_torrclient=true` (единая точка — `lampainit-invc.js` форка lampac, обновляется по воздуху).
- **Ребренд**: `app_name = D1Vision` (`app/src/main/res/values/strings.xml`). ⚠️ `applicationId top.rootu.lampa` **НЕ менять** — смена пакета теряет данные пользователя (SharedPreferences).

## OTA-самообновление приложения (бинаря)

Приложение обновляет **само себя** по воздуху с нашего сервера — без ручной переустановки.

- `helpers/Updater.kt` тянет манифест на живом хосте (`HostResolver.resolve` → LAN/tv/tv2): `GET /d1vision/apps/android/manifest.json` (`{versionCode,versionName,file,notes}`), сравнивает по числовому **`versionCode`** (прежняя хрупкая tag_name/Double-логика убрана), качает APK с того же хоста, ставит через системный установщик (FileProvider — `.update_provider`). Адрес сервера Updater не трогает.
- `versionCode` растёт из `git rev-list --count origin/main` → **OTA-коммит должен быть запушен в origin/main ДО сборки** нового билда (иначе версия не вырастет).
- Первый OTA-переход требует один раз разрешить «Установка неизвестных приложений» для D1Vision (системный тумблер) → дальше: обнаружил → скачал → «Обновить?» → один тап. Проверено сквозным тестом на живом ТВ (555→556).
- Билды публикует сервер из папки `client-builds/android/` (репо медиасервера); публикация — `E:\Media-server\scripts\publish-android-build.ps1`. Канон — `E:\Media-server\claude\08-clients.md` → «Самообновление бинарей».

## Встроенный плеер libVLC (с versionCode 568; внешние плееры больше не используются)

Видео играет **встроенный** плеер [player/PlayerActivity.kt](app/src/main/java/top/rootu/lampa/player/PlayerActivity.kt) на `org.videolan.android:libvlc-all` — паритет с mac/iOS, где VLCKit встроен в приложение (эталон — `mac-app/LampaKit/PlayerCoordinator.swift`). Все кодеки (AC3/EAC3/DTS/HEVC) декодирует libVLC, отдельные плееры ставить не нужно.

Схема: `AndroidJS.openPlayer` → `MainActivity.runPlayer` (парсит JSON, сохраняет state в `PlayerStateManager`) → **ранний выход** во внутренний `PlayerActivity` (state-JSON неподписанный — активность сама подписывает URL через `D1VAuth` и возвращает оригинальный url) → результат в тот же `resultLauncher` → generic-ветка `handleGenericPlayerResult` → `resultPlayer` → `Lampa.Timeline.update` + пометка предыдущих серий + WatchNext.

Фичи: резюме позиции (seek строго ПОСЛЕ первого `Event.Playing` — libVLC до старта молча игнорирует seek), auto-next по плейлисту, аудио/суб-дорожки, внешние субтитры (`addSlave` после старта), меню качества с сохранением позиции, D-pad: ←/→ перемотка ±10с (удержание 30с/60с), OK — пауза+OSD, BACK — OSD/выход. `EndReached`/`Error` обрабатываются через `Handler.post` — вызов `stop()`/`setMedia()` из колбэка события = дедлок libVLC.

- ⚠️ `minSdkVersion` поднят 16 → **21** (libVLC требует ≥17), `ndk.abiFilters arm64-v8a + armeabi-v7a` → APK ≈ **55 МБ** (было 14.4).
- **Код внешних плееров в MainActivity НЕ удалён** (configurePlayerIntent, showPlayerSelectionDialog, handle*PlayerResult) — мёртвый код после раннего выхода в `runPlayer`; откат = убрать один блок.
- Прогресс просмотра уезжает и на сервер (qdl 2.18, Modules/Sync/TimeCode) — см. `E:\Media-server\claude\08-clients.md`.

## Телефонная сборка «D1Vision Mobile» (флавор `phone`)

Из этого же репо собирается **вторая аппка — под смартфон**, в которой разделы **«D1versy Live»**
(эфир камер) и **«D1versy Rec»** (записи регистратора) **залочены**. Отдельного репозитория нет
намеренно: весь код общий (`app/src/main`), поэтому любой фикс ТВ-аппки автоматически попадает
и в телефонную. Отличия живут в флаворе `phone` и оверлей-директории **`app/src/phone/`**.

```powershell
cmd /c "E:\LAMPA-App\gradlew.bat -p E:\LAMPA-App --no-daemon assemblePhoneDebug"
#  → app/build/outputs/apk/phone/debug/app-phone-debug.apk
```

**Что отличается:**

| | ТВ (`lite`/`full`/`ruStore`) | Телефон (`phone`) |
|---|---|---|
| `applicationId` | `top.rootu.lampa` | `top.rootu.lampa.phone` (суффикс) — обе аппки стоят рядом |
| `app_name` | `D1Vision` | `D1Vision Mobile` |
| `provider_auth` | `top.rootu.lampa` | `top.rootu.lampa.phone` |
| OTA-канал (`BuildConfig.otaPlatform`) | `android` | `androidphone` |
| `BuildConfig.phoneBuild` | `false` | `true` |
| ориентация | `userLandscape` на всё приложение | снята (плеер остаётся в альбоме) |
| лончер | LAUNCHER + LEANBACK_LAUNCHER, ТВ-баннер | только LAUNCHER, адаптивная иконка |
| ТВ-компоненты | `HomeWatch`, `SearchProvider`, `ContentJobService`/`ContentAlarmManager` | вырезаны из манифеста |
| системные бары | sticky-immersive везде | видны в оболочке, фуллскрин только в плеере |
| меню (`showMenuDialog`) | только «Выйти», полное — через adb | полное по умолчанию (нужна смена адреса сервера) |
| плеер | пульт (`dispatchKeyEvent`) | пульт **+** тач: сикбар, тап, двойной тап ±10 с, драг-скраб |
| «Настройки» в нижней панели | — (на ТВ мобильной вёрстки нет) | скрыты |
| «Добавить в коллекцию», «Следить за новыми сериями» | на месте | убраны (и в «Загрузках», и в карточке jut.su) |
| язык | как выберет пользователь | русский сразу, экрана выбора нет |

⚠️ **`provider_auth`** обязателен: это хост дип-линков `lampa://` (`AndroidManifest.xml`,
`data android:host="@string/provider_auth"`). Если оставить общий — обе установленные аппки
claim'ят один хост, и Android на каждый дип-линк спрашивает, какую открыть.

⚠️ **OTA-каналы разные** намеренно: пакеты разные, и телефон, прочитав ТВ-манифест, поставил бы
ТВ-APK **отдельным приложением** вместо обновления себя. Публикация —
`pwsh scripts/publish-android-build.ps1 -Variant phoneDebug -Channel androidphone` в репо
медиасервера.

⚠️ **Новый флавор обязан иметь свою строку `phoneApi fileTree(...)`** в `dependencies`
(рядом с `liteApi`/`fullApi`/`ruStoreApi`) — иначе `org/xwalk/core/My*.java` не компилируется.

### Как устроены телефонные правки веб-UI — и что лок на самом деле значит

Меню, кнопки и экраны рисует **серверный** `qdl.js` / бандл Lampa (форк `E:\lampac`), одни и те же
для всех клиентов, поэтому «собрать другой APK» само по себе ничего не меняет. Все правки сделаны
**на клиенте** (решение владельца — сервер не трогаем): `helpers/PhoneLock.kt` инжектит в свой
WebView JS-сниппет из `MainActivity.onBrowserPageFinished`. Что в нём:
1. CSS `display:none` на `.menu__item.qdl-watch-menu` и `.qdl-live-menu` (Live/Rec) и на
   `.navigation-bar__item[data-action="settings"]` — «Настройки» в нижней панели мобильной вёрстки;
2. обёртка `Lampa.Activity.push`/`replace`, глушащая компоненты `qdl_live`, `qdl_live_watch`,
   `qdl_live_camera` (история, дип-линки, восстановленное состояние);
3. обёртка `Lampa.Select.show` — выкидывает пункты меню «Загрузок» по `act`
   (`jutwatch`/`watch`/`addcol`), плюс MutationObserver прячет кнопку слежения в карточке jut.su
   (у неё нет своего класса — ловим по метке 🔔 из `JUT_MODE_LABEL`);
4. сид `localStorage['language']='ru'` + автоклик по «Русский», если экран выбора успел открыться.

⚠️ Почему «Настройки» вообще протекали: сервер прячет служебные входы у всех (кука `qdl_unlock`),
но его правило написано под `.menu__item`, а в **мобильной** вёрстке Lampa рисует другую разметку —
`.navigation-bar__item`. Тот же класс дыры возможен и для других пунктов.

⚠️ Язык: Lampa на старте делает `if (localStorage.getItem('language'))` — есть, грузим приложение;
нет — показываем экран выбора. Значение хранится **обычной строкой**, не JSON. Решение принимается
раньше, чем приезжает наш `onPageFinished`, поэтому одного сида мало — второй веткой жмём «Русский»
сами; обработчик Lampa висит на `'hover:enter click'`, так что нативного `.click()` хватает и
страница не перезагружается.

**Границы честно:** это СОКРЫТИЕ, не защита. Роуты `qdl/live/*` на сервере открыты — их можно
дёрнуть браузером или curl'ом из LAN. Лок «от нечаянно», не «от злоумышленника».

🔗 **Связность с форком:** опорные точки — CSS-классы `.qdl-watch-menu` / `.qdl-live-menu` и имена
компонентов `qdl_live*` из `qdl.js`. Переименуют их там — лок молча перестанет работать.
**При правках меню в `qdl.js` проверять телефонную сборку.** Имена классов, к слову, обманчивы
(так в форке): `watch` = Live/эфир, `live` = Rec/записи.

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

Флейворы: **`lite`** (собран; апдейтер + Crosswalk shared-lib), `full` (встраивает Crosswalk core, ~50 МБ AAR), `ruStore`, **`phone`** (телефонная аппка D1Vision Mobile — см. раздел выше).

> **JDK:** сборка на **JDK 17** (в `.toolchain/`). Системный JDK не нужен. Если собирать своим — годится JDK 17 или 11, **но НЕ 21** (Gradle 7.5.1 его не тянет).
> **Release-подпись:** задать env `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`RELEASE_SIGN_KEY_ALIAS`/`RELEASE_SIGN_KEY_PASSWORD` (или `app/keystore/keystore_config`) → `assembleLiteRelease`. Без keystore релиз-подпись **пропускается** — обёрнута в `else if (System.getenv('KEYSTORE_FILE'))` в [app/build.gradle](app/build.gradle), иначе `file(null)` роняет даже debug-сборку на конфигурации.

Установка на ТВ:
```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/lite/debug/app-lite-debug.apk
```
(или закинуть APK через Downloader/файловый менеджер на ТВ).

> `versionCode`/`versionName` берутся из git (`rev-list --count origin/main` / `describe --tags`). На форке без тегов будет `0.0.0`/`1` — не критично.

## Меню приложения = только «Выйти из приложения»

Долгое удержание «Назад» (а также кнопка MENU пульта и экранная FAB) открывает диалог
**«Выход»** с **единственным** пунктом **«Выйти из приложения»** → `appExit()`.
Причина: штатный диалог выхода Lampa показывается только на корне стека активностей
(`event.count == 1`), а за сеанс стек копит десятки экранов — выйти можно было лишь
досхлопнув всё обычным «назад».

### Выход ЖЁСТКИЙ — процесс убивается (важно для OTA)

`appExit()` = `flushPrefsToDisk()` + `finishAffinity()` + `exitProcess(0)` (через 300 мс).
Раньше был только `finishAffinity()`, который закрывает активности, **но оставляет процесс живым**.

⚠️ Почему это критично: проверка OTA-обновления висит на `App.onCreate()` →
[App.kt](app/src/main/java/top/rootu/lampa/App.kt) `initializeComponents()` → `checkForUpdates()`,
а `App.onCreate()` выполняется **ровно один раз на процесс**. С мягким выходом пользователь
«перезапускал» приложение, попадал в тот же живой процесс — и апдейт не искался вообще.
Теперь каждый выход через меню даёт настоящий **холодный старт**, и новый билд подхватывается.

`flushPrefsToDisk()` обязателен: всё приложение пишет настройки через `.apply()` (в память сразу,
на диск фоном), а `exitProcess` фоновую запись не ждёт — без синхронного `commit()` терялись бы
последние позиции просмотра (`storage` — зеркало Lampa, `last_played`) и настройки.

### Вторая точка проверки OTA — MainActivity.onCreate

Жёсткого выхода **мало**. Процесс на ТВ регулярно поднимается в фоне сам: `ContentJobService` —
периодический job раз в 15 минут ([Scheduler.kt](app/src/main/java/top/rootu/lampa/sched/Scheduler.kt)
`setPeriodic(15 мин)`, `NETWORK_TYPE_ANY`, без idle/charging) и `BootReceiver` на BOOT_COMPLETED.
При таком **headless-старте** `App.onCreate` отрабатывает, `Updater.check()` честно находит новую
версию — но показать окно некому: `App.checkForUpdates()` ждёт foreground не дольше ~60 с и молча
сдаётся, **ретрая нет**. Процесс остаётся кэшированным, и когда пользователь открывает приложение,
`App.onCreate` уже не вызывается → апдейт не приходит никогда.

Поэтому в `MainActivity.onCreate` добавлен `checkUpdateOnStart()` — попадает ровно туда, где UI есть:
- `Updater.hasUpdate()` — апдейт уже найден в этом процессе (в том числе сгоревшим фоновым стартом)
  → показываем `UpdateActivity` **без сети**;
- иначе `Updater.checkThrottled()` — один поход за манифестом, не чаще раза в минуту на процесс
  (чтобы на холодном старте не дублировать запрос из `App`).

`UpdateActivity` — `singleInstance`, поэтому двойной `startActivity` (из `App` и отсюда) второго окна
не создаёт.

Прежнее полное меню (обновить канал, **сменить адрес Lampa**, сменить движок, бэкап/
восстановление) из UI убрано, но код цел — `showMenuDialog(fullMenu = true)`. Единственная
точка входа в него теперь — интент:
```bash
adb shell am start -n top.rootu.lampa/.MainActivity --es cmd open_settings
```
⚠️ Следствие: если разом умрут LAN + оба домена + OTA-кэш, перенацелить приложение с пульта
нечем (экран «Сервер недоступен» диалог ввода URL не показывает — он крутит
авто-переподключение). Остаются: adb-интент выше, первый запуск с пустым URL, невалидный URL,
очистка данных приложения. Вернуть меню в UI = снять `fullMenu = true` в вызовах
[MainActivity.kt](app/src/main/java/top/rootu/lampa/MainActivity.kt) `showMenuDialog`.

## Гочи
- Стартовый адрес — **дефолт**, а не жёсткая привязка. Если приложение уже запускалось со старым URL, он лежит в SharedPreferences (`settings/url`) и перебивает дефолт → очистить данные приложения или сменить сервер через adb-интент `cmd open_settings` (см. раздел выше).
- Порядок хостов **совпадает** с Apple-клиентами (LAN primary → tv → tv2 → OTA) — расхождений нет. Домен `tv.d1versy.com` **изнутри дома не отвечает** (нет hairpin NAT на роутере), поэтому LAN и остаётся первым: домен-primary дал бы 2.5 с ожидания на каждом домашнем старте. Проверить, кто выиграл гонку: `adb logcat -s HostResolver`.
- Меняется/добавляется адрес сервера — либо `clientHosts` в `init.conf` сервера (OTA, без пересборки), либо bootstrap `fallbackHosts` в [app/build.gradle](app/build.gradle) + пересборка. См. раздел «Фолбек хостов и OTA».
- Полная база знаний — [claude/README.md](claude/README.md).

## Правила (наследуются от основного репо)
- ⚠️ В коммитах **НЕ указывать соавторство Anthropic** (требование владельца).
- Общение и контент — на русском.
