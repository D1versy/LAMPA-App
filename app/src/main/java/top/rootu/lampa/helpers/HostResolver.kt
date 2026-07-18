package top.rootu.lampa.helpers

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.helpers.Prefs.appPrefs
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.Prefs.hasUserUrl
import top.rootu.lampa.net.HttpHelper

/**
 * D1Vision: выбор живого хоста Lampac из списка кандидатов и перебор при ошибке.
 *
 * Порядок кандидатов: кастомный URL пользователя (если задан пользователем явно
 * ЛИБО не входит в bootstrap-список) → bootstrap-хосты из BuildConfig.fallbackHosts
 * по порядку → OTA-кэш из SharedPreferences. Дедуп, трейлинг-слэш нормализуется.
 * OTA-кэш только ДОПОЛНЯЕТ bootstrap-список (никогда не заменяет —
 * защита от окирпичивания кривым hosts.json).
 */
object HostResolver {
    private const val OTA_HOSTS_KEY = "d1vision_hosts"
    private const val PROBE_TIMEOUT_MS = 2500

    // Очередь перебора хостов при ошибке загрузки (null — перебор не начат)
    private var failoverQueue: MutableList<String>? = null

    private fun normalize(url: String?): String = url?.trim()?.trimEnd('/') ?: ""

    private fun bootstrapHosts(): List<String> =
        BuildConfig.fallbackHosts.split(",").map { normalize(it) }.filter { it.isNotEmpty() }

    // OTA-список из SharedPreferences (JSON-массив строк)
    private fun otaHosts(context: Context): List<String> = try {
        val json = context.appPrefs.getString(OTA_HOSTS_KEY, null)
        if (json.isNullOrEmpty()) emptyList()
        else {
            val arr = JSONArray(json)
            (0 until arr.length()).map { normalize(arr.optString(it)) }.filter { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Список кандидатов по приоритету, с дедупликацией. */
    fun buildCandidates(context: Context): List<String> {
        val result = mutableListOf<String>()
        fun addUnique(host: String) {
            if (host.isNotEmpty() && result.none { it.equals(host, true) }) result.add(host)
        }

        val bootstrap = bootstrapHosts()
        // Кастомный URL пользователя — первым. Если пользователь ЯВНО сохранил адрес
        // (hasUserUrl), ставим его первым даже при совпадении с bootstrap — приоритет
        // не потеряется, если порядок bootstrap когда-то изменится. Дефолтный кейс
        // (ключ не сохранён, appUrl вернул зашитый дефолт) — как раньше, без дубля.
        val custom = normalize(context.appUrl)
        if (custom.isNotEmpty() && (context.hasUserUrl || bootstrap.none { it.equals(custom, true) }))
            addUnique(custom)
        bootstrap.forEach { addUnique(it) }
        otaHosts(context).forEach { addUnique(it) }
        return result
    }

    /** Проба хоста: GET <host>/lampainit.js, успех = HTTP 200. Звать только с фонового потока. */
    fun probe(host: String): Boolean = try {
        // Ключ в query обязателен: внешняя проба без него получит 404 и хост сочтётся мёртвым.
        val request = Request.Builder()
            .url(D1VAuth.sign("$host/lampainit.js"))
            .header("User-Agent", HttpHelper.userAgent)
            .build()
        HttpHelper.getOkHttpClient(PROBE_TIMEOUT_MS).newCall(request).execute()
            .use { it.code() == 200 }
    } catch (_: Exception) {
        false
    }

    /**
     * Первый живой хост из кандидатов; если ни один не ответил — первый кандидат
     * (пусть WebView покажет ошибку как раньше). Звать только с фонового потока.
     */
    fun resolve(context: Context): String {
        resetFailover() // новый сеанс резолва — перебор начинаем с начала списка кандидатов
        val candidates = buildCandidates(context)
        return candidates.firstOrNull { probe(it) }
            ?: candidates.firstOrNull()
            ?: normalize(context.appUrl)
    }

    /**
     * D1Vision: первый ЖИВОЙ хост из кандидатов или null, если не ответил НИ ОДИН.
     * В отличие от [resolve] не возвращает мёртвый фолбек — нужен для экрана
     * «Сервер недоступен»: пробуем переподключиться, грузим только реально живой хост.
     * Звать только с фонового потока (сеть).
     */
    fun resolveLiveOrNull(context: Context): String? =
        buildCandidates(context).firstOrNull { probe(it) }

    /**
     * Перебор при ошибке загрузки: следующий кандидат после [failedUrl];
     * null — кандидаты исчерпаны (пора показывать диалог ввода URL).
     */
    @Synchronized
    fun nextHost(context: Context, failedUrl: String): String? {
        val failed = normalize(failedUrl)
        val queue = failoverQueue ?: buildCandidates(context)
            .filterNot { it.equals(failed, true) }
            .toMutableList()
            .also { failoverQueue = it }
        return if (queue.isEmpty()) {
            failoverQueue = null // раунд исчерпан — следующая ошибка начнёт перебор заново
            null
        } else queue.removeAt(0)
    }

    /** Сброс перебора — звать при успешной загрузке страницы. */
    @Synchronized
    fun resetFailover() {
        failoverQueue = null
    }

    /**
     * Фоновое обновление OTA-списка: GET <activeHost>/d1vision/hosts.json
     * ({"ver":1,"brand":"D1Vision","hosts":[...]}) → SharedPreferences.
     * Ошибки молча глотаем — OTA-список необязателен. Звать только с фонового потока.
     */
    fun fetchOtaHosts(context: Context, activeHost: String) {
        try {
            val request = Request.Builder()
                .url(D1VAuth.sign("${normalize(activeHost)}/d1vision/hosts.json"))
                .header("User-Agent", HttpHelper.userAgent)
                .build()
            HttpHelper.getOkHttpClient(0).newCall(request).execute().use { response ->
                if (response.code() != 200) return
                val body = response.body()?.string() ?: return
                val arr = JSONObject(body).optJSONArray("hosts") ?: return
                val hosts = JSONArray()
                for (i in 0 until arr.length()) {
                    val host = normalize(arr.optString(i))
                    // Только валидные http(s)-URL — отсекает мусор вроде "httpfoo"/"http:"
                    if (HttpUrl.parse(host) != null) hosts.put(host)
                }
                context.appPrefs.edit().putString(OTA_HOSTS_KEY, hosts.toString()).apply()
            }
        } catch (_: Exception) {
            // OTA-кэш — best effort, без него работают bootstrap-хосты
        }
    }
}
