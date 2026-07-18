package top.rootu.lampa.helpers

import android.net.Uri
import top.rootu.lampa.BuildConfig

/**
 * D1Vision: невидимая аутентификация клиента на сервере (периметр lampac).
 *
 * Сервер за edge-прокси (Caddy на tv/tv2) требует ключ платформы. Ключ подставляется
 * query-параметром `d1v` ТОЛЬКО для НАШИХ хостов (LAN-IP / *.d1versy.com) — на сторонние
 * адреса (TMDB, jacred, прямые CDN балансеров) ключ не уходит.
 *
 * Каналы: WebView-навигация подписывается один раз (сервер пересаживает ключ в cookie,
 * дальше CookieManager шлёт её сам); внешний плеер получает URL через Intent и cookie не
 * несёт — ему ключ нужен в самом URL (subtitles тоже). Ключ — BuildConfig.d1vKey из
 * local.properties (вне git).
 */
object D1VAuth {
    val key: String get() = BuildConfig.d1vKey

    /** Наш ли это хост: приватный LAN-IP или домен проекта. */
    fun isOurHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        if (h == "d1versy.com" || h.endsWith(".d1versy.com")) return true
        return isPrivateIp(h)
    }

    private fun isPrivateIp(h: String): Boolean {
        if (h == "localhost" || h == "127.0.0.1") return true
        if (h.startsWith("192.168.") || h.startsWith("10.")) return true
        // 172.16.0.0 – 172.31.255.255
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /** Дописать d1v=<key> к URL для наших хостов; иначе вернуть без изменений. */
    fun sign(url: String?): String? {
        if (url.isNullOrEmpty() || key.isEmpty()) return url
        return try {
            val uri = Uri.parse(url)
            if (!isOurHost(uri.host)) return url
            if (uri.getQueryParameter("d1v") != null) return url
            uri.buildUpon().appendQueryParameter("d1v", key).build().toString()
        } catch (_: Exception) {
            url
        }
    }
}
