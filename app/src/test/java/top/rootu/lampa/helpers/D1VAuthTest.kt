package top.rootu.lampa.helpers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Периметр на стороне клиента: какие хосты «наши».
 *
 * Кейсы берутся из ОБЩЕЙ фикстуры `app/src/test/resources/parity/d1v-hosts.json` —
 * ту же самую гоняют Windows- и Apple-клиенты. Смысл именно в общности: одна и та же
 * логика написана трижды (Kotlin, Swift, C#), и расхождение не ловится ничем —
 * каждая реализация по отдельности «работает».
 *
 * Реальный пример, найденный при заведении этих тестов: `isPrivateIp` на Swift и C#
 * проверял префикс `127.`, а здесь стояло точное сравнение с `127.0.0.1`.
 * Адрес `127.0.0.2` три клиента классифицировали по-разному.
 *
 * ⚠️ `sign()` тянет `android.net.Uri` и в JVM-юните не проверяется — это осознанная
 * дыра до появления Robolectric, а не забывчивость.
 */
class D1VAuthTest {

    private val platform = "android"

    private fun fixture(name: String): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("parity/$name")
            ?: error("нет фикстуры parity/$name — синхронизируйте: scripts\\test-all.ps1 -SyncFixtures")
        return JSONObject(stream.bufferedReader().readText())
    }

    /** Кейсы, обязательные для этой платформы. */
    private fun cases(name: String): List<JSONObject> {
        val arr = fixture(name).getJSONArray("cases")
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { appliesToUs(it.opt("platforms")) }
    }

    private fun appliesToUs(platforms: Any?): Boolean = when (platforms) {
        null -> true
        is String -> platforms == "*"
        is JSONArray -> (0 until platforms.length()).any { platforms.getString(it) == platform }
        else -> true
    }

    @Test
    fun `вердикт по хостам совпадает с общей фикстурой`() {
        val failures = mutableListOf<String>()

        for (c in cases("d1v-hosts.json")) {
            val host = c.getString("host")
            val expected = c.getBoolean("ours")
            val actual = D1VAuth.isOurHost(host)
            if (actual != expected) {
                failures += "$host: ожидалось ours=$expected, получено $actual — ${c.optString("why")}"
            }
        }

        assertTrue(
            "вердикт разошёлся с остальными клиентами:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `петля это подсеть, а не один адрес`() {
        // Именно здесь Kotlin расходился со Swift и C#.
        assertTrue(D1VAuth.isOurHost("127.0.0.1"))
        assertTrue(D1VAuth.isOurHost("127.0.0.2"))
        assertTrue(D1VAuth.isOurHost("127.255.255.254"))
        assertTrue(D1VAuth.isOurHost("localhost"))
    }

    @Test
    fun `пустой и null хост не наши`() {
        assertFalse(D1VAuth.isOurHost(null))
        assertFalse(D1VAuth.isOurHost(""))
    }

    @Test
    fun `домен-приманка не проходит суффиксный матч`() {
        // Наш домен как ПРЕФИКС чужого — классический способ увести ключ периметра.
        assertFalse(D1VAuth.isOurHost("d1versy.com.evil.tld"))
        assertFalse(D1VAuth.isOurHost("tv.d1versy.com.evil.tld"))
        assertFalse(D1VAuth.isOurHost("xd1versy.com"))
    }

    @Test
    fun `границы приватного диапазона 172`() {
        assertTrue(D1VAuth.isOurHost("172.16.0.1"))
        assertTrue(D1VAuth.isOurHost("172.31.255.255"))
        assertFalse(D1VAuth.isOurHost("172.15.0.1"))
        assertFalse(D1VAuth.isOurHost("172.32.0.1"))
    }

    @Test
    fun `регистр хоста не важен`() {
        assertEquals(D1VAuth.isOurHost("TV.D1VERSY.COM"), D1VAuth.isOurHost("tv.d1versy.com"))
    }

    @Test
    fun `OTA-хосты в isOurHost не входят`() {
        // 🔴 Список приезжает по сети и по домену не валидируется. Подменённый hosts.json
        // увёл бы и ключ платформы, и права нативного моста на чужой адрес.
        val spec = fixture("d1v-hosts.json").getJSONObject("otaHostsAreNotOurs")
        assertFalse(spec.getString("about"), D1VAuth.isOurHost(spec.getString("host")))
    }

    @Test
    fun `расхождение с другими клиентами задокументировано`() {
        // Кастомный хост пользователя добавляет обёртка HostResolver.isOurHost(context, …),
        // а не сам D1VAuth. Это осознанно и записано в фикстуре — здесь канарейка на то,
        // что запись не потерялась.
        val divergences = fixture("d1v-hosts.json").getJSONArray("divergences")
        val ours = (0 until divergences.length())
            .map { divergences.getJSONObject(it) }
            .filter { it.getString("platform") == platform }

        assertEquals("расхождение android обязано быть описано ровно одно", 1, ours.size)
        assertTrue(ours[0].getString("rule").contains("HostResolver"))
    }
}
