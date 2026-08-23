package top.rootu.lampa.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTA: разбор манифеста обновления (OtaManifest).
 *
 * Ошибка здесь не ломает сборку — она ломает обновление у уже установленных копий,
 * и узнаётся это постфактум, когда апдейт «почему-то не приходит». Три отозванных
 * билда за вечер в истории проекта — ровно про эту цену публикации вслепую.
 */
class OtaManifestTest {

    private val host = "https://tv.d1versy.com:9443"

    private fun manifest(
        versionCode: Int = 600,
        versionName: String = "1.0",
        file: String = "D1Vision-android-600.apk",
        notes: String = "заметки",
    ) = """{"versionCode":$versionCode,"versionName":"$versionName","file":"$file","notes":"$notes"}"""

    @Test
    fun `новая версия распознаётся`() {
        val u = OtaManifest.parse(manifest(versionCode = 600), currentCode = 595, host = host)

        assertNotNull(u)
        assertEquals(600, u!!.versionCode)
        assertEquals("D1Vision-android-600.apk", u.file)
        assertEquals(host, u.host)
    }

    @Test
    fun `сравнение строго числовое, а не строковое`() {
        // 🔴 Прежняя upstream-логика сравнивала строку tag_name. Строкой «600» < «95»,
        // и обновление молча не приходило бы.
        assertNotNull(OtaManifest.parse(manifest(versionCode = 600), 95, host))
        assertNull(OtaManifest.parse(manifest(versionCode = 95), 600, host))
    }

    @Test
    fun `та же версия обновлением не считается`() {
        assertNull(OtaManifest.parse(manifest(versionCode = 595), 595, host))
    }

    @Test
    fun `откат версии обновлением не считается`() {
        // Регресс кода откатил бы пользователей на старый билд.
        assertNull(OtaManifest.parse(manifest(versionCode = 500), 595, host))
    }

    @Test
    fun `манифест без файла не годится`() {
        // Иначе апдейтер предложил бы обновление и пошёл качать пустой адрес.
        assertNull(OtaManifest.parse(manifest(file = ""), 1, host))
    }

    @Test
    fun `битый json не роняет проверку`() {
        assertNull(OtaManifest.parse("{ это не json", 1, host))
        assertNull(OtaManifest.parse("", 1, host))
        assertNull(OtaManifest.parse("[]", 1, host))
    }

    @Test
    fun `отсутствие versionCode это отсутствие обновления`() {
        assertNull(OtaManifest.parse("""{"file":"x.apk"}""", 1, host))
    }

    @Test
    fun `versionName необязателен и подменяется кодом`() {
        // Форма фида только ДОПОЛНЯЕТСЯ: старые сборки, которые не обновятся,
        // обязаны продолжать работать.
        val u = OtaManifest.parse("""{"versionCode":600,"file":"x.apk"}""", 1, host)

        assertNotNull(u)
        assertEquals("600", u!!.versionName)
        assertEquals("", u.notes)
    }

    @Test
    fun `лишние поля манифеста игнорируются`() {
        val u = OtaManifest.parse(
            """{"versionCode":600,"file":"x.apk","поле-из-будущего":{"a":1}}""", 1, host
        )
        assertNotNull(u)
    }

    @Test
    fun `канал живёт в пути, а не в UA`() {
        // 🔴 Телефон, прочитавший ТВ-манифест, поставит ТВ-APK ОТДЕЛЬНЫМ приложением
        // вместо обновления себя: пакеты различаются суффиксом .phone.
        val tv = OtaManifest.manifestUrl(host, "android")
        val phone = OtaManifest.manifestUrl(host, "androidphone")

        assertEquals("$host/d1vision/apps/android/manifest.json", tv)
        assertEquals("$host/d1vision/apps/androidphone/manifest.json", phone)
        assertTrue("каналы обязаны различаться", tv != phone)
    }

    @Test
    fun `путь манифеста лежит под публичным префиксом периметра`() {
        // /d1vision/apps/ открыт снаружи без ключа — иначе клиент после ротации ключа
        // не смог бы скачать обновление, которое этот ключ и чинит.
        assertTrue(OtaManifest.manifestUrl(host, "android").contains("/d1vision/apps/"))
    }

    @Test
    fun `хост манифеста запоминается для скачивания APK`() {
        // APK обязан качаться С ТОГО ЖЕ хоста: иначе вне дома манифест приедет с tv,
        // а за файлом апдейтер пойдёт в мёртвый LAN.
        val u = OtaManifest.parse(manifest(), 1, "https://tv2.d1versy.com:9443")
        assertEquals("https://tv2.d1versy.com:9443", u!!.host)
    }
}
