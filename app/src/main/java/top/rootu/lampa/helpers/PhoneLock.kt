package top.rootu.lampa.helpers

/**
 * Телефонные правки веб-Lampa для сборки `phone`
 * ([top.rootu.lampa.BuildConfig.phoneBuild]) — всё, что делается инъекцией JS в свой WebView.
 *
 * Почему здесь, а не на сервере: клиент — тонкая WebView-оболочка, а меню, кнопки и экраны
 * рисует серверный `qdl.js` / бандл Lampa (форк `E:\lampac`), одни и те же для всех клиентов.
 * Осталась мелкая косметика, ради которой серверный флаг заводить не за чем.
 *
 * Что делает сниппет:
 *  1. прячет «Настройки» в нижней панели мобильной вёрстки Lampa;
 *  2. ставит русский язык сразу, без экрана выбора;
 *  3. убирает «Добавить в коллекцию» и «Следить за новыми сериями» из меню «Загрузок»
 *     и кнопку слежения из карточки аниме jut.su.
 *
 * 🔴 ЧЕГО ЗДЕСЬ БОЛЬШЕ НЕТ: лока разделов «D1versy Live» и «D1versy Rec». С qdl 2.54 их выдаёт
 * СЕРВЕР по айди устройства (`Modules/QbitDownload/Perms.cs`, выдача прав в админке `/admin/d1v`),
 * и это уже настоящий запрет, а не сокрытие: без права все 13 роутов `qdl/live/…` отдают 404.
 * Клиентский CSS-лок и обёртка `Lampa.Activity.push` стали лишними — телефон просто не получает
 * права, как и любое другое неразрешённое устройство. Возвращать их сюда не нужно.
 * (Звёздочку после слэша в KDoc не писать: Kotlin считает вложенные блок-комментарии,
 * и `/` + `*` открывает комментарий, который никогда не закроется.)
 *
 * ⚠️ СВЯЗНОСТЬ С ФОРКОМ. Опорные точки живут в `E:\lampac`:
 *  • значения `act` пунктов меню «Загрузок»: `jutwatch`, `watch`, `addcol` (`qdl.js`);
 *  • класс кнопок карточки jut.su `.qdl-btn-focus` и метки `JUT_MODE_LABEL` с 🔔 (`qdl.js`);
 *  • разметка нижней панели Lampa `.navigation-bar__item[data-action="settings"]`.
 * Переименуют — правка молча перестанет работать. При изменениях меню/кнопок в `qdl.js`
 * проверять телефонную сборку.
 */
object PhoneLock {

    /**
     * Идемпотентный сниппет: можно звать на каждый onPageFinished, повторные вызовы — no-op
     * (гарды по `id` стиля и по флагам `__d1v*` на объектах Lampa).
     *
     * Часть правок применяется к DOM, которого в момент `onPageFinished` ещё нет (бандл Lampa
     * догружается позже), поэтому обёртки добиваются по таймеру, а кнопка карточки jut.su
     * ловится MutationObserver'ом.
     */
    const val JS: String = """
(function () {
    var STYLE_ID = 'd1v-phone-lock';
    // Пункты меню «Загрузок» из qdl.js. Фильтруем по act, а не по подписи: значение служебное,
    // не зависит от языка и от смены текста, и каждое из трёх встречается в форке ровно один раз.
    var LOCKED_ACTS = { jutwatch: 1, watch: 1, addcol: 1 };

    // ── 1. CSS: «Настройки» в нижней панели ───────────────────────────────────────
    // Сервер прячет служебные входы у всех (кука qdl_unlock), но его правило написано под
    // .menu__item — в мобильной вёрстке Lampa рисует ДРУГУЮ разметку, .navigation-bar__item,
    // и пункт протекал. ⚠️ Дыра классовая: любой серверный CSS-гейт под .menu__item на телефоне
    // не сработает.
    try {
        if (!document.getElementById(STYLE_ID)) {
            var st = document.createElement('style');
            st.id = STYLE_ID;
            st.textContent = '.navigation-bar__item[data-action="settings"]{display:none!important}';
            (document.head || document.documentElement).appendChild(st);
        }
    } catch (e) {}

    // ── 2. Русский язык без экрана выбора ─────────────────────────────────────────
    // Lampa на старте делает: if (localStorage.getItem('language')) грузим приложение;
    // иначе показываем экран выбора. Значение хранится обычной строкой, не JSON.
    // Сидим ДО того, как экран понадобится; если он всё же успел открыться (наш JS
    // приезжает на onPageFinished, а решение принимается раньше) — жмём «Русский» сами:
    // так отрабатывает штатный колбэк Lampa и загрузка продолжается без перезагрузки страницы.
    try {
        if (!window.localStorage.getItem('language')) {
            window.localStorage.setItem('language', 'ru');
            window.localStorage.setItem('tmdb_lang', 'ru');
        }
    } catch (e) {}

    function pickRu() {
        try {
            var el = document.querySelector('.lang__selector-item[data-code="ru"]');
            if (!el) return false;
            el.click();   // обработчик Lampa висит на 'hover:enter click' — нативного клика хватает
            return true;
        } catch (e) {
            return false;
        }
    }

    // ── 3. Пункты меню «Загрузок»: коллекции и слежение ───────────────────────────
    function wrapSelect() {
        try {
            var S = window.Lampa && window.Lampa.Select;
            if (!S || typeof S.show !== 'function') return false;
            if (S.__d1vPhoneFilter) return true;
            S.__d1vPhoneFilter = true;
            var orig = S.show;
            S.show = function (params) {
                try {
                    if (params && params.items && params.items.length) {
                        var kept = params.items.filter(function (it) {
                            return !(it && LOCKED_ACTS[it.act]);
                        });
                        if (kept.length !== params.items.length) {
                            // копия параметров: исходный объект принадлежит вызывающему коду
                            var p = {};
                            for (var k in params) p[k] = params[k];
                            p.items = kept;
                            return orig.call(this, p);
                        }
                    }
                } catch (e) {}
                return orig.apply(this, arguments);
            };
            return true;
        } catch (e) {
            return false;
        }
    }

    // ── 3b. Кнопка слежения в карточке аниме jut.su ───────────────────────────────
    // Отличительного класса или data-атрибута у неё нет — кнопки карточки строит общий mkBtn,
    // поэтому ловим по метке: 🔔 есть только у слежения (JUT_MODE_LABEL в qdl.js).
    function hideBellButtons() {
        try {
            var btns = document.querySelectorAll('.qdl-btn-focus');
            for (var i = 0; i < btns.length; i++) {
                var sp = btns[i].querySelector('span');
                if (sp && sp.textContent.indexOf('\uD83D\uDD14') === 0) btns[i].style.display = 'none';
            }
        } catch (e) {}
    }

    // Наблюдатель за кнопкой карточки: она приезжает асинхронно, на ответ /qdl/jut/...
    function startObserver() {
        try {
            if (window.__d1vPhoneObserver || !window.MutationObserver || !document.body) return;
            var pending = null;
            window.__d1vPhoneObserver = new MutationObserver(function () {
                if (pending) return;                       // дебаунс: мутаций в Lampa много
                pending = setTimeout(function () { pending = null; hideBellButtons(); }, 120);
            });
            window.__d1vPhoneObserver.observe(document.body, { childList: true, subtree: true });
        } catch (e) {}
    }

    // onPageFinished может прийти раньше, чем догрузится бандл Lampa, — добиваем по таймеру:
    // до ~10 с с шагом 250 мс, пока все обёртки не встанут.
    var tries = 0;
    function apply() {
        var s = wrapSelect();
        pickRu();
        hideBellButtons();
        startObserver();
        return s;
    }
    if (!apply()) {
        var timer = setInterval(function () {
            if (apply() || ++tries > 40) clearInterval(timer);
        }, 250);
    }
})();
"""
}
