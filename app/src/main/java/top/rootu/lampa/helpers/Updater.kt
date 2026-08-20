package top.rootu.lampa.helpers

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.Spanned
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import okhttp3.Request
import org.json.JSONObject
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.R
import top.rootu.lampa.net.HttpHelper
import top.rootu.lampa.net.TlsSocketFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory


/**
 * D1Vision OTA: самообновление APK с НАШЕГО сервера (не upstream GitHub).
 *
 * Источник — манифест на живом хосте Lampac (перебор LAN → tv → tv2 через [HostResolver]).
 * Канал (сегмент пути) — BuildConfig.otaPlatform: `android` у ТВ-сборок, `androidphone` у телефонной.
 * Каналы РАЗНЫЕ намеренно: пакеты разные, и телефон, прочитав ТВ-манифест, поставил бы ТВ-APK
 * отдельным приложением вместо обновления себя.
 *   GET <host>/d1vision/apps/<канал>/manifest.json
 *   {"versionCode":555,"versionName":"1.1","file":"D1Vision-android-555.apk","notes":"..."}
 * Сравнение — по числовому BuildConfig.VERSION_CODE (монотонный, из git rev-list), а НЕ по
 * строке tag_name (прежняя upstream-логика была хрупкой). Адрес сервера апдейтер не трогает —
 * только качает APK и ставит через системный установщик (FileProvider, один тап пользователя).
 * Канон: E:\Media-server\claude\08-clients.md.
 */
object Updater {
    private const val MANIFEST_TIMEOUT_MS = 5000

    private data class AppUpdate(
        val versionCode: Int,
        val versionName: String,
        val file: String,
        val notes: String,
        val host: String,   // живой хост, с которого пришёл манифест — с него же качаем APK
    )

    private var update: AppUpdate? = null

    /** Когда в этом процессе последний раз ходили за манифестом (elapsedRealtime). */
    @Volatile
    private var lastCheckAt = 0L

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                // Only TLSv1.2 and TLSv1.3 protocol available and trust all certs (insecure).
                val socketFactory: SSLSocketFactory = TlsSocketFactory()
                HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
            } catch (_: GeneralSecurityException) {
            }
        }
    }

    /**
     * Найдено ли обновление РАНЕЕ в этом процессе. Нужно, потому что проверка часто
     * отрабатывает в фоновом (headless) старте процесса — его поднимает ContentJobService
     * раз в 15 минут и BootReceiver, — где показать окно некому: [App] ждёт foreground
     * ~60 с и молча сдаётся, а ретрая нет. Результат при этом остаётся здесь, и UI может
     * показать его без повторного похода в сеть.
     */
    fun hasUpdate(): Boolean = update != null

    /**
     * Проверка с троттлингом: не чаще раза в [minIntervalMs] на процесс. Если обновление
     * уже найдено — сразу true, в сеть не идём.
     * Звать только с фонового потока (сеть).
     */
    fun checkThrottled(minIntervalMs: Long = 60_000L): Boolean {
        if (update != null) return true
        val now = SystemClock.elapsedRealtime()
        if (lastCheckAt != 0L && now - lastCheckAt < minIntervalMs) return false
        return check()
    }

    /** Проверка обновления. Звать только с фонового потока (сеть). */
    fun check(): Boolean {
        lastCheckAt = SystemClock.elapsedRealtime()
        try {
            val ctx = App.context
            // qdl 2.53: сперва берём победителя уже прошедшей гонки. Раньше здесь всегда был
            // полный resolve(), и холодный старт устраивал до ТРЁХ гонок вместо одной: эта,
            // такая же из MainActivity.checkUpdateOnStart и та единственная нужная, что
            // гейтит загрузку страницы в onBrowserInitCompleted.
            // 🔴 Заодно чинится латентный баг: resolve() первой строкой зовёт resetFailover(),
            // и OTA-проверка, попавшая в момент перебора хостов после ошибки загрузки
            // (SysView → nextHost), сбрасывала очередь перебора на начало — тот шёл по кругу.
            // Фолбек на полную гонку остаётся: фоновая проверка может случиться и до первого
            // резолва (headless-подъём по ContentJobService), и много позже него.
            val host = HostResolver.cachedLiveHost() ?: HostResolver.resolve(ctx)
            if (host.isEmpty()) return false

            val request = Request.Builder()
                .url("$host/d1vision/apps/${BuildConfig.otaPlatform}/manifest.json")
                .header("User-Agent", HttpHelper.userAgent)
                .build()
            val body = HttpHelper.getOkHttpClient(MANIFEST_TIMEOUT_MS).newCall(request).execute().use {
                if (it.code() != 200) return false
                it.body()?.string() ?: return false
            }

            val j = JSONObject(body)
            val vc = j.optInt("versionCode", 0)
            val file = j.optString("file", "")
            if (vc <= BuildConfig.VERSION_CODE || file.isEmpty()) {
                update = null
                return false
            }
            update = AppUpdate(
                versionCode = vc,
                versionName = j.optString("versionName", vc.toString()),
                file = file,
                notes = j.optString("notes", ""),
                host = host,
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun getVersion(): String = update?.versionName ?: ""

    fun getOverview(): Spanned {
        val upd = update
        val html = if (upd == null) "" else {
            "<font color='white'><b>${upd.versionName}</b></font><br/>" +
                "<i>${upd.notes.replace("\r\n", "<br/>").replace("\n", "<br/>")}</i>"
        }
        return HtmlCompat.fromHtml(html.trim(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private val download = Any()

    private fun downloadApk(file: File, onProgress: ((prc: Int) -> Unit)?) {
        synchronized(download) {
            val upd = update ?: return
            if (file.exists())
                file.delete()
            val link = "${upd.host}/d1vision/apps/${BuildConfig.otaPlatform}/${upd.file}"
            try {
                val url = URL(link)
                val connection = if (link.startsWith("https"))
                    url.openConnection() as HttpsURLConnection?
                else
                    url.openConnection() as HttpURLConnection?
                connection?.connect()
                connection?.inputStream.use { input ->
                    FileOutputStream(file).use { fileOut ->
                        val contentLength = connection?.contentLength ?: 0
                        if (onProgress == null)
                            input?.copyTo(fileOut)
                        else {
                            val buffer = ByteArray(65535)
                            val length = contentLength + 1
                            var offset: Long = 0
                            while (true) {
                                val read = input?.read(buffer) ?: 0
                                offset += read
                                val prc = (offset * 100 / length).toInt()
                                onProgress(prc)
                                if (read <= 0)
                                    break
                                fileOut.write(buffer, 0, read)
                            }
                            fileOut.flush()
                        }
                        fileOut.flush()
                        fileOut.close()
                    }
                }
                connection?.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun installNewVersion(onProgress: ((prc: Int) -> Unit)?) {
        val ctx = App.context
        if (update == null && !check())
            return

        update?.let {
            val destination = File(
                ctx.getExternalFilesDir(null),
                "D1Vision.apk"
            ).apply {
                mkdirs()
                deleteOnExit()
            }

            downloadApk(destination, onProgress)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val uri = Uri.fromFile(destination)
                val install = Intent(Intent.ACTION_VIEW)
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                install.setDataAndType(uri, "application/vnd.android.package-archive")
                if (install.resolveActivity(ctx.packageManager) != null)
                    App.context.startActivity(install)
                else
                    App.toast(R.string.error_app_not_found)
            } else {
                val fileUri =
                    FileProvider.getUriForFile(
                        ctx,
                        BuildConfig.APPLICATION_ID + ".update_provider",
                        destination
                    )
                val install = Intent(Intent.ACTION_VIEW, fileUri)
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                install.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (install.resolveActivity(ctx.packageManager) != null)
                    ctx.startActivity(install)
                else
                    App.toast(R.string.error_app_not_found)
            }
        }
    }
}
