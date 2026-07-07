# База знаний — LAMPA-App (Android TV клиент)

Форк `lampa-app/LAMPA` под домашний Lampac (`http://192.168.87.24:9118`). Быстрый старт и «зачем/что изменено» — в корневом [../CLAUDE.md](../CLAUDE.md). Здесь — устройство приложения для будущих правок.

## Архитектура (главное)
Приложение — **не самостоятельный фронтенд, а управляемый браузер** вокруг веб-интерфейса Lampa:
- Движок браузера: системный WebView (`browser/SysView.kt`) или **Crosswalk/XWalk** (`browser/XWalk.kt`) для старых боксов; выбор в `browser/Browser.kt`, пресет `appBrowser`.
- Что грузит: **удалённый URL** `appUrl` (`helpers/Prefs.kt` → `BuildConfig.defaultAppUrl`). `MainActivity.onCreate` → `LAMPA_URL = appUrl`; `onBrowserInitCompleted` → пусто ? диалог ввода : `browser.loadUrl(LAMPA_URL)` ([MainActivity.kt:386-392](../app/src/main/java/top/rootu/lampa/MainActivity.kt#L386)).
- Мост JS↔native: `AndroidJS.kt`, инжектится как `window.AndroidJS` ([MainActivity.kt:383](../app/src/main/java/top/rootu/lampa/MainActivity.kt#L383)). Через него веб-Lampa зовёт нативное: `openPlayer`, `openTorrentLink`, `httpReq` (нативный HTTP в обход CORS/TLS), `storageChange` (синк настроек/закладок), `voiceStart` и т.д.

**Следствие для нашего сервера:** приложение открывает `index.html` нашего Lampac → сервер сам инжектит `/lampainit.js` → `/qdl.js`. Все серверные плагины (онлайн-балансеры, торренты, **кнопка «Скачать»**) приезжают автоматически. Отдельно добавлять плагины в приложении НЕ нужно. Ключевое отличие от гипотезы «нативка тащит свой bundle» — тут bundle не тащится, грузится живой сервер.

## Плеер и кодеки
`AndroidJS.openPlayer` → `MainActivity.runPlayer` ([MainActivity.kt:2193](../app/src/main/java/top/rootu/lampa/MainActivity.kt#L2193)) → `Intent(ACTION_VIEW)` во внешний плеер. Известные пакеты (`MainActivity` companion): MX (`com.mxtech.*`), ViMu (`net.gtvbox.*`), UPlayer, DDDPlayer, ExoPlayer demo. Результат воспроизведения (таймкод) ловится по result-экшенам (`processIntent`, ветки `com.mxtech.intent.result.VIEW`, `net.gtvbox.*.result` и т.п.) и уходит обратно в Lampa через `PlayerStateManager`.

- Сам APK кодеки не несёт (нет ExoPlayer/FFmpeg в зависимостях — `app/build.gradle`). «Все кодеки» = поставить **ViMu/MX Pro/VLC** на ТВ и выбрать плеером.
- Без выбранного внешнего плеера играет HTML5-плеер WebView → кодеки ограничены. Это by design апстрима.
- Если захочется **встроенный** плеер с полным декодом — это крупная доработка (добавить media3 + FFmpeg-extension + PlayerActivity, ловить `openPlayer` внутри). Пока НЕ делаем: на Android TV внешний плеер (ViMu) — стандартный и правильный путь.

## Наши правки к апстриму
1. `app/build.gradle` — `def defaultServerUrl = "http://192.168.87.24:9118"`, проброшен в `defaultAppUrl` всех флейворов (было `""` у lite/full, `http://lampa.mx` у ruStore).

Всё остальное — апстрим. Cleartext уже был разрешён (`res/xml/network_security_config.xml`), баннер/иконка/leanback-intent на месте (`AndroidManifest.xml`). Правок минимум → лёгкий rebase на апстрим (аналогично форку Lampac).

## Синк с апстримом
```bash
git remote add upstream https://github.com/lampa-app/LAMPA.git   # один раз
git fetch upstream && git rebase upstream/main
```
Конфликт возможен только в `app/build.gradle` (флейворы) — оставить нашу строку `defaultServerUrl`.

## Сборка / установка / гочи
См. [../CLAUDE.md](../CLAUDE.md) (раздел «Сборка»). Кратко: `./gradlew assembleLiteDebug` → APK в `app/build/outputs/apk/lite/debug/`, ставить `adb install -r`.

Гочи:
- Стартовый URL — дефолт поверх пустого. Уже сохранённый `settings/url` перебивает → очистить данные приложения при смене.
- targetSdk 28, minSdk 16 (ruStore 24). Старые API-совместимые версии либ — не апать бездумно (в `app/build.gradle` комментарии почему какая версия).
- Релиз требует keystore.
