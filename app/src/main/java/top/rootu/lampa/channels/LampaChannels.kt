package top.rootu.lampa.channels

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.content.LampaProvider
import top.rootu.lampa.helpers.Helpers.isTvContentProviderAvailable
import top.rootu.lampa.player.PlayerActivity

object LampaChannels {
    private const val TAG = "LampaChannels"
    private val lock = Any()
    private const val MAX_RECS_CAP = 30

    // Пауза перед догоном отложенных полок: даём системе забрать память за освободившимся
    // декодером, иначе первый же запрос к TV-провайдеру снова упрётся в ENOMEM.
    private const val FLUSH_DELAY = 3000L

    // ─── Полки главного экрана не трогаем, пока открыт плеер ───
    // Обновление полки — это чтение TV-провайдера через CursorWindow (общая память).
    // На 2-ГБ телевизоре рядом с работающим декодером аллокация падает с ENOMEM,
    // исключение прилетает в фоновую корутину и роняет приложение целиком.
    // Полки — украшение главного экрана, ждать они умеют. См. claude/06 §DE.
    private val pendingNames = linkedSetOf<String>()

    @Volatile
    private var pendingAll = false

    @Volatile
    private var pendingRecs = false

    @Volatile
    private var pendingWatchNext = false

    private val playerBusy: Boolean
        get() = PlayerActivity.isActive

    /** Плеер закрылся — догоняем то, что отложили. */
    fun flushPending() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pendingAll && !pendingRecs && !pendingWatchNext &&
            synchronized(pendingNames) { pendingNames.isEmpty() }
        ) return

        CoroutineScope(Dispatchers.Default).launch {
            delay(FLUSH_DELAY)
            if (playerBusy) return@launch // включили следующую серию — флаги ждут дальше

            val all = pendingAll.also { pendingAll = false }
            val recs = pendingRecs.also { pendingRecs = false }
            val watchNext = pendingWatchNext.also { pendingWatchNext = false }
            val names = synchronized(pendingNames) {
                pendingNames.toList().also { pendingNames.clear() }
            }
            if (BuildConfig.DEBUG) Log.d(
                TAG, "flushPending(all=$all, recs=$recs, watchNext=$watchNext, names=$names)"
            )

            if (all) {
                update(true) // внутри обновит и WatchNext
                return@launch
            }
            if (recs) updateRecsChannel()
            names.forEach { updateChanByName(it) }
            if (watchNext) runCatching { WatchNext.updateWatchNext() }
                .onFailure { Log.e(TAG, "updateWatchNext failed", it) }
        }
    }

    /** WatchNext через тот же шлагбаум, что и полки. */
    fun updateWatchNextGuarded() {
        if (!isTvContentProviderAvailable) return
        if (playerBusy) {
            pendingWatchNext = true
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { WatchNext.updateWatchNext() }
                .onFailure { Log.e(TAG, "updateWatchNext failed", it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun update(sync: Boolean = true) {
        if (!isTvContentProviderAvailable) return
        if (playerBusy) {
            pendingAll = true
            return
        }

        synchronized(lock) {
            if (BuildConfig.DEBUG) Log.d(TAG, "update(sync: $sync)")

            // List of channel names and their corresponding update functions
            val channels = listOf(
                LampaProvider.RECS to {
                    LampaProvider.get(LampaProvider.RECS, true)?.items.orEmpty().take(MAX_RECS_CAP)
                },
                LampaProvider.LIKE to {
                    LampaProvider.get(
                        LampaProvider.LIKE,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.BOOK to {
                    LampaProvider.get(
                        LampaProvider.BOOK,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.HIST to {
                    LampaProvider.get(
                        LampaProvider.HIST,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.LOOK to {
                    LampaProvider.get(
                        LampaProvider.LOOK,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.VIEW to {
                    LampaProvider.get(
                        LampaProvider.VIEW,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.SCHD to {
                    LampaProvider.get(
                        LampaProvider.SCHD,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.CONT to {
                    LampaProvider.get(
                        LampaProvider.CONT,
                        false
                    )?.items.orEmpty()
                },
                LampaProvider.THRW to {
                    LampaProvider.get(
                        LampaProvider.THRW,
                        false
                    )?.items.orEmpty()
                }
            )

            if (!sync) {
                // Use coroutines to update data concurrently
                CoroutineScope(Dispatchers.Default).launch {
                    val deferredResults = channels.map { (name, fetchFunction) ->
                        async { name to fetchFunction() }
                    }
                    deferredResults.forEach { deferred ->
                        val (name, items) = deferred.await()
                        ChannelManager.update(name, items)
                    }
                    // Update WatchNext after all channels are updated
                    WatchNext.updateWatchNext()
                }
            } else {
                // Fetch data sequentially
                channels.forEach { (name, fetchFunction) ->
                    val items = fetchFunction()
                    ChannelManager.update(name, items)
                }
                // Update WatchNext after all channels are updated
                CoroutineScope(Dispatchers.Default).launch {
                    WatchNext.updateWatchNext()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateRecsChannel() {
        if (!isTvContentProviderAvailable) return
        if (playerBusy) {
            pendingRecs = true
            return
        }
        synchronized(lock) {
            if (BuildConfig.DEBUG) Log.d(TAG, "updateRecsChannel()")
            val list =
                LampaProvider.get(LampaProvider.RECS, true)?.items.orEmpty().take(MAX_RECS_CAP)
            ChannelManager.update(LampaProvider.RECS, list)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateChanByName(name: String) {
        if (!isTvContentProviderAvailable) return
        if (playerBusy) {
            synchronized(pendingNames) { pendingNames.add(name) }
            return
        }
        synchronized(lock) {
            if (BuildConfig.DEBUG) Log.d(TAG, "updateChanByName($name)")
            val list = LampaProvider.get(name, false)?.items.orEmpty()
            ChannelManager.update(name, list)
        }
    }
}