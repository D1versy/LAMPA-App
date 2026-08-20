package top.rootu.lampa

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDexApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.rootu.lampa.helpers.Helpers.isConnected
import top.rootu.lampa.helpers.HostResolver
import top.rootu.lampa.helpers.Prefs.appLang
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.Updater
import top.rootu.lampa.helpers.handleUncaughtException
import top.rootu.lampa.helpers.setLanguage
import top.rootu.lampa.tmdb.TMDB

class App : MultiDexApplication() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // use vectors on pre-LP devices
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    }

    companion object {
        private val TAG: String = App::class.java.simpleName

        // Сколько секунд ждём появления foreground, отличая пользовательский старт от фонового
        private const val FOREGROUND_WAIT_SEC = 60

        // Сколько ждать победителя гонки хостов от загрузки страницы, прежде чем
        // фоновая проверка обновлений проведёт свою (см. checkForUpdates)
        private const val RESOLVE_WAIT_SEC = 20

        private lateinit var appContext: Context

        @Volatile
        var inForeground: Boolean = false
            private set

        val context: Context
            get() = appContext

        private val lifecycleEventObserver = LifecycleEventObserver { _, event ->
            inForeground = when (event) {
                Lifecycle.Event.ON_START -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "in foreground")
                    true
                }

                Lifecycle.Event.ON_STOP -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "in background")
                    false
                }

                else -> inForeground
            }
        }

        fun toast(txt: String, long: Boolean = true) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showToast(txt, long)
            } else {
                Handler(Looper.getMainLooper()).post { showToast(txt, long) }
            }
        }

        fun toast(txt: Int, long: Boolean = true) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showToast(context.getString(txt), long)
            } else {
                Handler(Looper.getMainLooper()).post {
                    showToast(context.getString(txt), long)
                }
            }
        }

        private fun showToast(text: String, long: Boolean) {
            val duration = if (long) android.widget.Toast.LENGTH_LONG
            else android.widget.Toast.LENGTH_SHORT
            android.widget.Toast.makeText(appContext, text, duration).show()
        }

        fun setAppLanguage(context: Context, langCode: String) {
            context.appLang = langCode
            if (context is BaseActivity) {
                context.recreateWithLanguage()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // setup applicationContext
        appContext = applicationContext.setLanguage()
        // ensure resources are properly initialized
        resources

        // register lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleEventObserver)

        // app crash handler
        handleUncaughtException(showLogs = true)
        //CrashHandler(this).initialize(showLogs = BuildConfig.DEBUG)

        // Initialize components
        initializeComponents()
    }

    private fun initializeComponents() {
        // self-update check
        if (BuildConfig.enableUpdate) {
            applicationScope.launch {
                checkForUpdates()
            }
        }

        // Init TMDB genres
        applicationScope.launch {
            initGenresWhenVisible()
        }

    }

    /**
     * D1Vision: справочник жанров тянем ТОЛЬКО когда приложение реально открыл пользователь.
     *
     * `onCreate` выполняется при ЛЮБОМ старте процесса, включая headless-подъём
     * (`ContentJobService` раз в 15 минут и `BootReceiver`) — раньше это давало сетевой
     * запрос каждые четверть часа, при том что каналы лаунчера собираются из кэша в prefs
     * и жанры им нужны лишь как подпись. Ждём foreground не дольше [FOREGROUND_WAIT_SEC];
     * не дождались — фоновой старт, в сеть не идём вовсе.
     */
    private suspend fun initGenresWhenVisible() {
        try {
            if (appContext.appUrl.isEmpty()) return // сервер не задан — некуда идти
            var count = FOREGROUND_WAIT_SEC
            while (!inForeground && count > 0) {
                delay(1000)
                count--
            }
            if (!inForeground) {
                if (BuildConfig.DEBUG) Log.d(TAG, "headless start, skip TMDB genres")
                return
            }
            TMDB.initGenres()
        } catch (e: Exception) {
            Log.e(TAG, "Genres init failed", e)
        }
    }

    private suspend fun checkForUpdates() {
        var count = 60
        try {
            while (!isConnected(appContext) && count > 0) {
                delay(1000) // wait for network
                count--
            }

            // qdl 2.53: не устраиваем СВОЮ гонку хостов, если её вот-вот проведёт загрузка
            // страницы. Ждём появления победителя не дольше [RESOLVE_WAIT_SEC]; не дождались
            // (headless-подъём, страница не грузится) — Updater.check() сам сходит в гонку,
            // как раньше. Без этого холодный старт устраивал до трёх гонок вместо одной,
            // и лишние две отбирали канал у загружающейся Лампы.
            // 🔴 СВОЙ счётчик, count не трогаем. Он общий бюджет «ждём сеть + ждём foreground»,
            // и если тратить его здесь, то при поздно появившейся сети (например, на 45-й секунде)
            // ожидание съело бы остаток и Updater.check() не выполнился бы вовсе — проверка
            // обновлений молча пропала бы за весь запуск процесса.
            var wait = RESOLVE_WAIT_SEC
            while (HostResolver.cachedLiveHost() == null && wait > 0) {
                delay(1000)
                wait--
            }

            if (count > 0 && Updater.check()) {
                while (count > 0 && !inForeground) { // wait foreground
                    delay(1000)
                    count--
                }
                if (inForeground) {
                    val intent = Intent(appContext, UpdateActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
        }
    }
}