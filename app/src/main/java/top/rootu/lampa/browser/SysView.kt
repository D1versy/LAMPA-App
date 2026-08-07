package top.rootu.lampa.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.setWebContentsDebuggingEnabled
import androidx.annotation.RequiresApi
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.MainActivity
import top.rootu.lampa.R
import top.rootu.lampa.helpers.D1VAuth
import top.rootu.lampa.helpers.Helpers.isTelegramInstalled
import top.rootu.lampa.helpers.Helpers.debugLog
import top.rootu.lampa.helpers.ErrorHtml
import top.rootu.lampa.helpers.HostResolver
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.getNetworkErrorString
import top.rootu.lampa.helpers.isAttachedToWindowCompat

// https://developer.android.com/develop/ui/views/layout/webapps/webview#kotlin
class SysView(override val mainActivity: MainActivity, override val viewResId: Int) : Browser {
    private var browser: WebView? = null
    override var isDestroyed = false

    // D1Vision: идёт ли цикл авто-переподключения к серверу (экран «Сервер недоступен»).
    private var reconnecting = false

    companion object {
        const val LOG_TAG = "WEB CONSOLE"
        private const val RECONNECT_INTERVAL_MS = 5000L
    }

    /**
     * Показать экран «Сервер недоступен» и запустить нативный цикл переподключения
     * (перепроба LAN → tv → tv2 каждые 5с; как только хост ожил — грузим его).
     */
    private fun showServerUnavailable(view: WebView) {
        view.loadDataWithBaseURL(null, ErrorHtml.createServerUnavailablePage(), "text/html", "UTF-8", null)
        view.invalidate()
        if (!reconnecting) {
            reconnecting = true
            scheduleReconnect(view)
        }
    }

    private fun scheduleReconnect(view: WebView) {
        if (isDestroyed) { reconnecting = false; return }
        view.postDelayed({
            if (isDestroyed) { reconnecting = false; return@postDelayed }
            Thread {
                val live = try { HostResolver.resolveLiveOrNull(view.context) } catch (_: Exception) { null }
                view.post {
                    if (isDestroyed) { reconnecting = false; return@post }
                    if (live != null) {
                        reconnecting = false
                        MainActivity.LAMPA_URL = live
                        HostResolver.resetFailover()
                        view.loadUrl(D1VAuth.sign(live) ?: live)
                    } else {
                        scheduleReconnect(view) // сервер ещё не поднялся — пробуем снова
                    }
                }
            }.start()
        }, RECONNECT_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun initialize() {
        browser = mainActivity.findViewById(viewResId)
        browser?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // No Scrollbars at ALL
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isScrollContainer = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            }
        }
        setFocus()

        val settings = browser?.settings
        @Suppress("DEPRECATION")
        settings?.apply {
            javaScriptEnabled = true
            builtInZoomControls = false
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            setNeedInitialFocus(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // D1Vision: ALWAYS_ALLOW пускал на https-странице активный http-контент
            // (скрипты, XHR) — в чужой сети это подмена кода. COMPATIBILITY блокирует
            // активный, но оставляет пассивный (картинки): LAN идёт по http целиком
            // (правило mixed content к нему не применяется), внешний вход — https целиком.
            settings?.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings?.mediaPlaybackRequiresUserGesture = false
        }

        browser?.webViewClient = object : WebViewClientCompat() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                mainActivity.onBrowserPageFinished(view, url)
            }

            // https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20java.lang.String)
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                debugLog("shouldOverrideUrlLoading(view, url) view $view url $url")
                // Старый колбэк (API < 21) не знает ни фрейма, ни жеста — считаем переход
                // пользовательским в главном фрейме
                return handleNavigation(url, isMainFrame = true, hasGesture = true)
            }

            // https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20android.webkit.WebResourceRequest)
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                debugLog("shouldOverrideUrlLoading(view, request) view $view request $request")
                return handleNavigation(
                    request.url?.toString(),
                    isMainFrame = request.isForMainFrame,
                    hasGesture = request.hasGesture()
                )
            }

            /**
             * D1Vision: allowlist навигации. Главный фрейм грузим только с наших хостов
             * ([HostResolver.isOurUrl]): страница в WebView видит мост `AndroidJS`
             * (плеер, хранилище, произвольные HTTP-запросы), поэтому чужой документ здесь —
             * чужой код внутри приложения. Внешнюю ссылку, нажатую пользователем, отдаём
             * системному браузеру; автоматические переходы просто глушим.
             *
             * @return true — переход обработан/запрещён, WebView его не грузит.
             */
            private fun handleNavigation(
                url: String?,
                isMainFrame: Boolean,
                hasGesture: Boolean
            ): Boolean {
                if (url.isNullOrEmpty()) return false
                if (url.startsWith("tg://", true)) {
                    // Handle Telegram link
                    if (isTelegramInstalled(mainActivity)) {
                        mainActivity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } else {
                        // App.toast("Telegram app is not installed. Get in on Google Play.")
                        redirectToTelegramPlayStore()
                    }
                    return true // Indicate that the URL has been handled
                }
                // Служебные схемы самого WebView (about:blank, data:, blob:) — не трогаем
                if (url.startsWith("about:", true) || url.startsWith("data:", true)
                    || url.startsWith("blob:", true)
                ) return false
                // Подресурсы и iframe не фильтруем: их второй контур — мост (AndroidJS.httpReq)
                if (!isMainFrame) return false
                if (HostResolver.isOurUrl(mainActivity, url)) return false // грузим как раньше
                Log.w(LOG_TAG, "Blocked navigation to foreign host: $url")
                // Клик пользователя по внешней ссылке уводим наружу, в системный браузер
                if (hasGesture && (url.startsWith("http://", true) ||
                            url.startsWith("https://", true))
                ) openExternally(url)
                return true
            }

            private fun openExternally(url: String) {
                try {
                    mainActivity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "No app to open $url: ${e.message}")
                }
            }


            private fun redirectToTelegramPlayStore() {
                // Open the Telegram app page on the Play Store
                val playStoreIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=org.telegram.messenger")
                )
                try {
                    mainActivity.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=org.telegram.messenger")
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    mainActivity.startActivity(playStoreIntent)
                }
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onReceivedError( // Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat
            ) {
                if (WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE
                    ) &&
                    WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION
                    )
                ) {
                    debugLog("ERROR ${error.errorCode} ${error.description} on load ${request.url}")
                    if (request.url.toString().trimEnd('/')
                            .equals(MainActivity.LAMPA_URL, true)
                    ) {
                        view.loadUrl("about:blank")
                        // net::ERR_NAME_NOT_RESOLVED [-2]
                        // net::ERR_TIMED_OUT [-8]
                        // ...
                        val reason =
                            view.context.getNetworkErrorString(error.description.toString())
                        val msg = "${
                            view.context.getString(R.string.download_failed_message)
                        } ${MainActivity.LAMPA_URL} – $reason"
                        // net::ERR_INTERNET_DISCONNECTED [-2]
                        val noInternetErr = "net::ERR_INTERNET_DISCONNECTED"
                        if (error.description == noInternetErr) {
                            val html =
                                ErrorHtml.createErrorHtmlPage(view.context.getNetworkErrorString(noInternetErr))
                            view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            view.invalidate()
                        } else {
                            // D1Vision: сервер лежит — пробуем следующий хост из кандидатов,
                            // диалог ввода URL только когда список исчерпан
                            val next = HostResolver.nextHost(view.context, MainActivity.LAMPA_URL)
                            if (next != null) {
                                MainActivity.LAMPA_URL = next // в памяти; Prefs.appUrl не трогаем
                                view.loadUrl(D1VAuth.sign(next) ?: next)
                            } else {
                                // Кандидаты исчерпаны — экран «Сервер недоступен» + авто-переподключение
                                // (вместо чёрного экрана/диалога). LAMPA_URL откатываем на сохранённый.
                                MainActivity.LAMPA_URL = view.context.appUrl
                                showServerUnavailable(view)
                            }
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError( // Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                debugLog("ERROR $errorCode $description on load $failingUrl")
                if (failingUrl.toString().trimEnd('/').equals(MainActivity.LAMPA_URL, true)) {
                    view?.loadUrl("about:blank")
                    val reason = App.context.getNetworkErrorString(description.toString())
                    val msg =
                        "${App.context.getString(R.string.download_failed_message)} ${MainActivity.LAMPA_URL} – $reason"
                    val noInternetErr = "net::ERR_INTERNET_DISCONNECTED"
                    if (description == noInternetErr) {
                        view?.apply {
                            val html =
                                ErrorHtml.createErrorHtmlPage(this.context.getNetworkErrorString(noInternetErr))
                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            invalidate()
                        }
                    } else {
                        // D1Vision: сервер лежит — пробуем следующий хост из кандидатов,
                        // диалог ввода URL только когда список исчерпан
                        val next = HostResolver.nextHost(App.context, MainActivity.LAMPA_URL)
                        if (next != null) {
                            MainActivity.LAMPA_URL = next // в памяти; Prefs.appUrl не трогаем
                            view?.loadUrl(D1VAuth.sign(next) ?: next)
                        } else {
                            // Кандидаты исчерпаны — экран «Сервер недоступен» + авто-переподключение.
                            MainActivity.LAMPA_URL = App.context.appUrl
                            view?.let { showServerUnavailable(it) }
                        }
                    }
                }
            }

            /**
             * D1Vision: сертификат не принимаем НИ ПРИ КАКИХ условиях (было `proceed()`
             * на любой). Наши домены (`tv/tv2.d1versy.com`) отдают настоящий Let's Encrypt,
             * а LAN идёт по http и до TLS не доходит вовсе — то есть законного повода для
             * ошибки TLS у клиента нет: она означает подмену в чужой сети. Отказ роняет
             * загрузку в `onReceivedError`, а там уже работает штатный перебор хостов и
             * экран «Сервер недоступен» — пользователь не остаётся с чёрным экраном.
             */
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                Log.w(LOG_TAG, "SSL error, connection cancelled: $error")
                handler?.cancel()
            }
        }

        if (BuildConfig.DEBUG)
            browser?.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.LOG -> Log.v(LOG_TAG, consoleMessage.message())
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(LOG_TAG, consoleMessage.message())
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(LOG_TAG, consoleMessage.message())
                        ConsoleMessage.MessageLevel.DEBUG -> Log.d(LOG_TAG, consoleMessage.message())
                        else -> Log.i(LOG_TAG,  consoleMessage.message())
                    }
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    if (message != null) {
                        App.toast(message)
                    }
                    return false
                }
            }
        

        mainActivity.onBrowserInitCompleted()
    }

    override fun setUserAgentString(ua: String?) {
        browser?.settings?.userAgentString = ua
    }

    override fun getUserAgentString(): String? {
        return browser?.settings?.userAgentString
    }

    @SuppressLint("AddJavascriptInterface", "JavascriptInterface")
    override fun addJavascriptInterface(jsObject: Any, name: String) {
        browser?.addJavascriptInterface(jsObject, name)
    }

    override fun loadUrl(url: String) {
        browser?.loadUrl(url)
    }

    override fun pauseTimers() {
        if (!isDestroyed)
            browser?.pauseTimers()
    }

    override fun resumeTimers() {
        browser?.resumeTimers()
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun evaluateJavascript(script: String, resultCallback: (String) -> Unit) {
        browser?.evaluateJavascript(script, resultCallback)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        browser?.clearCache(includeDiskFiles)
    }

    override fun destroy() {
        browser?.let {
            // Remove from parent if attached
            if (it.isAttachedToWindowCompat())
                (it.parent as? ViewGroup)?.removeView(it)
        }
        browser?.stopLoading()
        browser?.destroy()
        isDestroyed = true
    }

    override fun setBackgroundColor(color: Int) {
        browser?.setBackgroundColor(color)
    }

    override fun canGoBack(): Boolean {
        return browser?.canGoBack() == true
    }

    override fun goBack() {
        browser?.goBack()
    }

    override fun setFocus() {
        browser?.requestFocus(View.FOCUS_DOWN)
    }

    override fun getView(): View? {
        return browser
    }
}