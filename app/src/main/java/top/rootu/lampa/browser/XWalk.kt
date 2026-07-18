package top.rootu.lampa.browser

import android.view.View
import org.xwalk.core.XWalkResourceClient
import org.xwalk.core.XWalkView
import top.rootu.lampa.App
import top.rootu.lampa.MainActivity
import top.rootu.lampa.R
import top.rootu.lampa.helpers.D1VAuth
import top.rootu.lampa.helpers.ErrorHtml
import top.rootu.lampa.helpers.HostResolver
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.getNetworkErrorString

class XWalk(override val mainActivity: MainActivity, override val viewResId: Int) : Browser {
    private var browser: XWalkView? = null
    override var isDestroyed = false
    override fun initialize() {
        if (browser == null) {
            browser = mainActivity.findViewById(viewResId)
            browser?.let { xWalkView ->
                xWalkView.setLayerType(View.LAYER_TYPE_NONE, null)
                xWalkView.setResourceClient(object : XWalkResourceClient(xWalkView) {
                    override fun onLoadFinished(view: XWalkView, url: String) {
                        super.onLoadFinished(view, url)
                        mainActivity.onBrowserPageFinished(view, url)
                    }

                    override fun onReceivedLoadError(
                        view: XWalkView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        super.onReceivedLoadError(view, errorCode, description, failingUrl)
                        if (failingUrl.toString().trimEnd('/')
                                .equals(MainActivity.LAMPA_URL, true)
                        ) {
                            val reason = App.context.getNetworkErrorString(description.toString())
                            val msg =
                                "${view?.context?.getString(R.string.download_failed_message)} ${MainActivity.LAMPA_URL} – $reason"
                            // D1Vision: сети нет вообще — перебор хостов бессмыслен; показываем
                            // ту же страницу-заглушку, что и SysView, и выходим (симметрия с SysView).
                            val noInternetErr = "net::ERR_INTERNET_DISCONNECTED"
                            if (description == noInternetErr) {
                                val html = ErrorHtml.createErrorHtmlPage(
                                    App.context.getNetworkErrorString(noInternetErr)
                                )
                                view?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            } else {
                                // Сервер лежит — пробуем следующий хост из кандидатов,
                                // диалог ввода URL только когда список исчерпан
                                val next =
                                    HostResolver.nextHost(App.context, MainActivity.LAMPA_URL)
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
                })
                mainActivity.onBrowserInitCompleted()
            }
        }
    }

    // D1Vision: идёт ли цикл авто-переподключения (экран «Сервер недоступен»).
    private var reconnecting = false

    private companion object {
        const val RECONNECT_INTERVAL_MS = 5000L
    }

    /** Экран «Сервер недоступен» + нативный цикл переподключения (симметрия с SysView). */
    private fun showServerUnavailable(view: XWalkView) {
        view.loadDataWithBaseURL(null, ErrorHtml.createServerUnavailablePage(), "text/html", "UTF-8", null)
        if (!reconnecting) {
            reconnecting = true
            scheduleReconnect(view)
        }
    }

    private fun scheduleReconnect(view: XWalkView) {
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
                        scheduleReconnect(view)
                    }
                }
            }.start()
        }, RECONNECT_INTERVAL_MS)
    }

    override fun setUserAgentString(ua: String?) {
        browser?.userAgentString = ua
    }

    override fun getUserAgentString(): String? {
        return browser?.userAgentString
    }

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

    override fun evaluateJavascript(script: String, resultCallback: (String) -> Unit) {
        browser?.evaluateJavascript(script, resultCallback)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        browser?.clearCache(includeDiskFiles)
    }

    override fun destroy() {
        browser?.onDestroy()
        isDestroyed = true
    }

    override fun setBackgroundColor(color: Int) {
        browser?.setBackgroundColor(color)
    }

    override fun canGoBack(): Boolean {
        return false
    }

    override fun goBack() {}

    override fun setFocus() {}

    override fun getView(): View? {
        return browser
    }

}