package top.rootu.lampa.helpers

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.helpers.Prefs.appPrefs
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.Prefs.hasUserUrl
import top.rootu.lampa.net.HttpHelper
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * D1Vision: выбор живого хоста Lampac ПАРАЛЛЕЛЬНОЙ гонкой кандидатов и перебор при ошибке.
 *
 * Порядок кандидатов: кастомный URL пользователя (если задан пользователем явно
 * ЛИБО не входит в bootstrap-список) → bootstrap-хосты из BuildConfig.fallbackHosts
 * по порядку → OTA-кэш из SharedPreferences. Дедуп, трейлинг-слэш нормализуется.
 * OTA-кэш только ДОПОЛНЯЕТ bootstrap-список (никогда не заменяет —
 * защита от окирпичивания кривым hosts.json).
 *
 * Гонка: все пробы стартуют одновременно; приоритет = позиция в списке. Успех
 * кандидата, выше которого живых не осталось, побеждает мгновенно; менее
 * приоритетный успех ждёт старших не дольше grace 300 мс (LAN не проигрывает
 * интернет-хосту из-за джиттера). Кастомный хост пользователя (кандидат №0,
 * если задан) — «защищённый»: grace его не обгоняет, победа уходит другим
 * только после провала его пробы. Проигравшие пробы отменяются.
 */
object HostResolver {
    private const val OTA_HOSTS_KEY = "d1vision_hosts"
    private const val PROBE_TIMEOUT_MS = 2500

    // Grace-окно приоритета — единый контракт клиентов D1Vision
    private const val RACE_GRACE_MS = 300L

    // Очередь перебора хостов при ошибке загрузки (null — перебор не начат)
    private var failoverQueue: MutableList<String>? = null

    // Один клиент на все пробы: у каждого OkHttpClient свой dispatcher-поток (enqueue),
    // клиент-на-пробу копил бы idle-потоки в 5-секундном цикле реконнекта.
    // connectTimeout поднят до полного PROBE_TIMEOUT_MS: фабрика ставит connect =
    // timeout/2 (1.25 с), а контракт гонки даёт хосту 2.5 с целиком — WAN-хост
    // с медленным хендшейком не должен считаться мёртвым раньше, чем на Apple/Tizen
    // (callTimeout 2.5 с всё равно ограничивает сверху).
    private val probeClient by lazy {
        HttpHelper.getOkHttpClient(PROBE_TIMEOUT_MS).newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

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

    /**
     * Сколько первых кандидатов «защищены» от проигрыша по grace: кастомный адрес
     * пользователя (кандидат №0), если он попал в начало списка (условие зеркалит
     * [buildCandidates]) — явный выбор не должен молча проигрывать быстрому LAN.
     */
    private fun protectedCount(context: Context): Int {
        val custom = normalize(context.appUrl)
        val protectedFirst = custom.isNotEmpty() &&
                (context.hasUserUrl || bootstrapHosts().none { it.equals(custom, true) })
        return if (protectedFirst) 1 else 0
    }

    /**
     * Отменяемая проба хоста: GET <host>/lampainit.js, успех = HTTP 200.
     * Через enqueue, а не execute — отмена корутины рвёт запрос (call.cancel()),
     * это нужно гонке, чтобы глушить проигравших.
     */
    private suspend fun probeAsync(host: String): Boolean = suspendCancellableCoroutine { cont ->
        val call = try {
            // Ключ в query обязателен: внешняя проба без него получит 404 и хост сочтётся мёртвым.
            val request = Request.Builder()
                .url(D1VAuth.sign("$host/lampainit.js"))
                .header("User-Agent", HttpHelper.userAgent)
                .build()
            probeClient.newCall(request)
        } catch (_: Exception) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { try { call.cancel() } catch (_: Exception) {} }
        call.enqueue(object : Callback {
            // isActive-гарды обязательны: после cancel() OkHttp всё равно зовёт onFailure,
            // а double-resume роняет корутину
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = try { response.code() == 200 } finally { response.close() }
                if (cont.isActive) cont.resume(ok)
            }
        })
    }

    /**
     * Гонка кандидатов (контракт — в шапке файла). Первые [protectedCount] кандидатов
     * grace не обгоняет: после его истечения победа отдаётся best, только когда все
     * защищённые выше best провалились. null — не ответил НИ ОДИН.
     */
    private suspend fun raceLive(candidates: List<String>, protectedCount: Int): String? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        // Ёмкость = числу проб: send никогда не блокируется, джобы завершаются сразу
        val events = Channel<Pair<Int, Boolean>>(candidates.size)
        val jobs = candidates.mapIndexed { index, host ->
            launch { events.send(index to probeAsync(host)) }
        }
        val failed = BooleanArray(candidates.size)
        var failCount = 0
        var best = -1          // лучший (минимальный индекс) успех, ещё не победивший
        var winner: Int? = null
        var grace: Deferred<Unit>? = null
        var graceDone = false  // grace истёк, ждём только защищённых кандидатов
        fun allHigherFailed(index: Int) = (0 until index).all { failed[it] }
        fun protectedAboveFailed(index: Int) =
            (0 until minOf(protectedCount, index)).all { failed[it] }

        while (winner == null) {
            val graceNow = grace
            select<Unit> {
                events.onReceive { (index, ok) ->
                    if (ok) {
                        if (allHigherFailed(index) || (graceDone && protectedAboveFailed(index)))
                            winner = index
                        else {
                            if (best == -1 || index < best) best = index
                            if (grace == null && !graceDone) grace = async { delay(RACE_GRACE_MS) }
                        }
                    } else {
                        failed[index] = true
                        failCount++
                        if (best != -1 && (allHigherFailed(best) || (graceDone && protectedAboveFailed(best))))
                            winner = best
                        else if (failCount == candidates.size && best == -1) winner = -1
                    }
                }
                if (graceNow != null) graceNow.onAwait {
                    grace = null   // клауза не должна перерегистрироваться на завершённом Deferred
                    if (best >= 0 && protectedAboveFailed(best)) winner = best
                    else graceDone = true   // защищённый кандидат ещё в полёте — ждём его исход
                }
            }
        }
        jobs.forEach { it.cancel() }   // cancel → invokeOnCancellation → call.cancel()
        grace?.cancel()
        winner?.takeIf { it >= 0 }?.let { candidates[it] }
    }

    /**
     * Победитель гонки; если ни один не ответил — первый кандидат
     * (пусть WebView покажет ошибку как раньше). Звать только с фонового потока:
     * runBlocking держит поток не дольше пробы (~2.5 с) — как раньше execute().
     */
    fun resolve(context: Context): String {
        resetFailover() // новый сеанс резолва — перебор начинаем с начала списка кандидатов
        val candidates = buildCandidates(context)
        return runBlocking { raceLive(candidates, protectedCount(context)) }
            ?: candidates.firstOrNull()
            ?: normalize(context.appUrl)
    }

    /**
     * D1Vision: живой победитель гонки или null, если не ответил НИ ОДИН.
     * В отличие от [resolve] не возвращает мёртвый фолбек — нужен для экрана
     * «Сервер недоступен»: пробуем переподключиться, грузим только реально живой хост.
     * Звать только с фонового потока (сеть).
     */
    fun resolveLiveOrNull(context: Context): String? =
        runBlocking { raceLive(buildCandidates(context), protectedCount(context)) }

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
