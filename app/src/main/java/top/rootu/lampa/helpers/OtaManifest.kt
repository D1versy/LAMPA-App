package top.rootu.lampa.helpers

import org.json.JSONObject

/**
 * D1Vision OTA: чистый разбор манифеста обновления.
 *
 * Вынесено из [Updater] отдельным файлом БЕЗ единого Android-импорта: сам Updater тянет
 * сетевой стек (OkHttp + Conscrypt с нативной библиотекой), которого в JVM-юните нет,
 * и вся суть обновления оказывалась непроверяемой заодно с ним. Здесь — только JSON.
 *
 * Формат манифеста (форма только ДОПОЛНЯЕТСЯ, старые сборки обязаны продолжать работать):
 *   {"versionCode":595,"versionName":"1.1","file":"D1Vision-android-595.apk","notes":"…"}
 *
 * Сравнение строго ЧИСЛОВОЕ по versionCode: прежняя upstream-логика сравнивала строку
 * tag_name и была хрупкой.
 */
object OtaManifest {

    /** Разобранное обновление; null означает «обновления нет». */
    data class Update(
        val versionCode: Int,
        val versionName: String,
        val file: String,
        val notes: String,
        /** Живой хост, с которого пришёл манифест — с него же качаем APK. */
        val host: String,
    )

    /**
     * Адрес манифеста канала.
     *
     * Канал — сегмент ПУТИ (`android` у ТВ-сборок, `androidphone` у телефонной), а не часть UA:
     * телефон, прочитавший ТВ-манифест, поставил бы ТВ-APK отдельным приложением вместо
     * обновления себя — пакеты различаются суффиксом `.phone`.
     */
    fun manifestUrl(host: String, platform: String): String =
        "$host/d1vision/apps/$platform/manifest.json"

    /**
     * Разбор манифеста. Возврат null означает «обновления нет» — вызывающий обязан
     * обнулить состояние, иначе прошлый кандидат остался бы висеть.
     */
    fun parse(body: String, currentCode: Int, host: String): Update? = try {
        val j = JSONObject(body)
        val vc = j.optInt("versionCode", 0)
        val file = j.optString("file", "")
        if (vc <= currentCode || file.isEmpty()) null
        else Update(
            versionCode = vc,
            versionName = j.optString("versionName", vc.toString()),
            file = file,
            notes = j.optString("notes", ""),
            host = host,
        )
    } catch (_: Exception) {
        null
    }
}
