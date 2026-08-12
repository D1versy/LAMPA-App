package top.rootu.lampa.helpers

/**
 * Лок разделов «D1versy Live» (эфир камер) и «D1versy Rec» (записи регистратора)
 * в телефонной сборке (флавор `phone`, [top.rootu.lampa.BuildConfig.phoneBuild]).
 *
 * Почему это здесь, а не на сервере: клиент — тонкая WebView-оболочка, а сами пункты меню и экраны
 * рисует серверный плагин `qdl.js` (форк `E:\lampac`, `Modules/QbitDownload/plugins/qdl.js`),
 * один и тот же для всех клиентов. Владелец выбрал лок именно на клиенте — сервер не трогаем вовсе.
 *
 * ⚠️ ЧЕСТНО О ГРАНИЦАХ. Это СОКРЫТИЕ, а не защита: пункты не появляются и экраны недостижимы из UI,
 * но роуты `qdl/live/…` на сервере остаются открытыми — их можно дёрнуть браузером или curl'ом
 * из LAN. Лок «от нечаянно», не «от злоумышленника».
 * (Звёздочку после слэша в KDoc не писать: Kotlin считает вложенные блок-комментарии,
 * и `/` + `*` открывает комментарий, который никогда не закроется.)
 *
 * ⚠️ СВЯЗНОСТЬ С ФОРКОМ. Опорные точки — CSS-классы `.qdl-watch-menu` / `.qdl-live-menu` и имена
 * компонентов `qdl_live*` из `qdl.js`. Переименуют их там — лок молча перестанет работать,
 * и телефонная сборка снова покажет разделы. При правках меню в `qdl.js` проверять телефон.
 * Имена классов обманчивы (так в форке): `watch` = Live/эфир, `live` = Rec/записи.
 */
object PhoneLock {

    /**
     * Идемпотентный сниппет: можно звать на каждый onPageFinished, повторные вызовы — no-op.
     *
     * Два независимых слоя:
     *  1. CSS `display:none` на пунктах меню. Тот же приём, что и в форке для «Настроек»/«Консоли»
     *     под кукой `qdl_unlock` (`lampainit-invc.js`): навигатор пульта скрытые элементы
     *     пропускает, а стиль висит на классе и переживает пере-рендер меню — `ensureMenu()`
     *     в `qdl.js` детачит и вставляет `<li>` заново, добивая по таймерам до 6 секунд.
     *  2. Обёртка `Lampa.Activity.push`/`replace` — на случай истории, дип-линка или
     *     восстановленного состояния. Именно обёртка, а не «снять регистрацию компонента»:
     *     компоненты регистрирует `qdl.js` безусловно, а что Lampa делает при переходе на
     *     незарегистрированный компонент — не проверено, и бросок внутри стека активити
     *     был бы плохим отказом.
     */
    const val JS: String = """
(function () {
    var STYLE_ID = 'd1v-phone-lock';
    var LOCKED = { qdl_live: 1, qdl_live_watch: 1, qdl_live_camera: 1 };

    // ── Слой 1: прячем пункты меню ────────────────────────────────────────────────
    try {
        if (!document.getElementById(STYLE_ID)) {
            var st = document.createElement('style');
            st.id = STYLE_ID;
            st.textContent = '.menu__item.qdl-watch-menu,.menu__item.qdl-live-menu{display:none!important}';
            (document.head || document.documentElement).appendChild(st);
        }
    } catch (e) {}

    // ── Слой 2: не пускаем в сами экраны ──────────────────────────────────────────
    // onPageFinished может прийти раньше, чем догрузится бандл Lampa, поэтому добиваем
    // по таймеру: до ~10 с с шагом 250 мс, до первого успеха.
    var tries = 0;
    function wrapActivity() {
        try {
            var A = window.Lampa && window.Lampa.Activity;
            if (!A) return false;
            if (A.__d1vPhoneLock) return true;
            A.__d1vPhoneLock = true;
            ['push', 'replace'].forEach(function (name) {
                var orig = A[name];
                if (typeof orig !== 'function') return;
                A[name] = function (object) {
                    if (object && LOCKED[object.component]) return;
                    return orig.apply(this, arguments);
                };
            });
            return true;
        } catch (e) {
            return false;
        }
    }
    if (!wrapActivity()) {
        var timer = setInterval(function () {
            if (wrapActivity() || ++tries > 40) clearInterval(timer);
        }, 250);
    }
})();
"""
}
