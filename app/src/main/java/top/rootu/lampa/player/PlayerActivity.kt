package top.rootu.lampa.player

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import kotlin.math.abs
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import top.rootu.lampa.App
import top.rootu.lampa.BaseActivity
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.R
import top.rootu.lampa.helpers.D1VAuth
import top.rootu.lampa.helpers.hideSystemUI

/**
 * D1Vision: ВСТРОЕННЫЙ плеер на libVLC — паритет с mac/iOS (там VLCKit встроен в приложение,
 * эталон — mac-app/LampaKit PlayerCoordinator.swift + PlayerOverlayController.swift).
 * Внешние плееры (ViMu/MX/...) больше не используются — их код в MainActivity оставлен мёртвым
 * для мгновенного отката.
 *
 * Вход:  Intent extras — EXTRA_STATE (state-JSON из PlayerStateManager.getStateJson, URL
 *        НЕподписанные), EXTRA_TITLE, EXTRA_IPTV. Подпись периметра (?d1v=) добавляется
 *        ЗДЕСЬ перед созданием Media — наружу возвращаем оригинальный URL, чтобы
 *        resultPlayer.isCurrentPlaybackItem матчился без фокусов.
 * Выход: результат под СУЩЕСТВУЮЩУЮ generic-ветку MainActivity.handleGenericPlayerResult:
 *        action НЕ ставим; досмотр → RESULT_FIRST_USER (+data=url); выход → RESULT_OK +
 *        data=url + position/duration (Int, мс); live/нет прогресса → RESULT_CANCELED без data.
 *        Всю пост-обработку (Lampa.Timeline.update, пометка предыдущих серий 100%, WatchNext,
 *        clearState) делает существующий resultPlayer — здесь её НЕТ.
 *
 * Грабли, унаследованные с мака:
 *  - seek ДО реального старта воспроизведения libVLC молча игнорирует → pendingSeekMs
 *    применяется по первому Event.Playing;
 *  - stop()/setMedia() из колбэка события = дедлок libVLC → EndReached/Error через Handler.post;
 *  - после stop() позиция/длительность обнуляются → кэшируем последние ненулевые lastPos/lastDur.
 */
class PlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_STATE = "state"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IPTV = "isIPTV"
        private const val TAG = "PlayerActivity"
        private const val OSD_HIDE_MS = 4000L
        private const val ENDED_PERCENT = 96      // как VIDEO_COMPLETED_DURATION_MAX_PERCENTAGE
        private const val SEEK_BASE_MS = 10_000L
        // Тач-скраб: протащить палец через ВСЮ ширину экрана = столько перемотки.
        // 120 с — компромисс: и по минуте отлистать, и секунду поймать не невозможно.
        private const val SCRUB_FULL_MS = 120_000f
    }

    private data class Sub(val url: String, val label: String)
    private data class Item(
        val url: String,
        val title: String?,
        val startMs: Long,      // timeline.time*1000; 0 если percent>=96 (смотрим с начала)
        val quality: List<Pair<String, String>>,   // label → url (порядок сервера)
        val subs: List<Sub>
    )

    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout

    private lateinit var osdPanel: View
    private lateinit var osdTitle: TextView
    private lateinit var osdTimeCur: TextView
    private lateinit var osdTimeDur: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var btnPlayPause: Button

    // Тач-управление — только в телефонной сборке; на ТВ всё остаётся ровно как было.
    private var osdSeekBar: SeekBar? = null
    private var seekBarDragging = false   // пользователь тащит полосу — не перебивать её обновлениями
    private var scrubbing = false         // идёт горизонтальный драг по видео
    private var scrubBaseMs = 0L          // позиция на момент начала драга
    private var scrubTargetMs = -1L       // куда встанем при отпускании

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOsd() }

    private var items = listOf<Item>()
    private var index = 0
    private var isIPTV = false
    private var fallbackTitle = ""

    private var pendingSeekMs = -1L
    private var slavesAdded = false
    private var lastPosMs = 0L
    private var lastDurMs = 0L
    private var osdInteractive = false   // панель с фокусом на кнопках (не индикатор перемотки)
    private var qualityLabel: String? = null   // выбранное качество — переживает auto-next

    // акселерация перемотки: подряд идущие нажатия наращивают шаг 10с → 30с → 60с
    private var seekStreak = 0
    private var lastSeekAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        hideSystemUI()

        videoLayout = findViewById(R.id.videoLayout)
        osdPanel = findViewById(R.id.osdPanel)
        osdTitle = findViewById(R.id.osdTitle)
        osdTimeCur = findViewById(R.id.osdTimeCur)
        osdTimeDur = findViewById(R.id.osdTimeDur)
        osdProgress = findViewById(R.id.osdProgress)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        isIPTV = intent.getBooleanExtra(EXTRA_IPTV, false)
        fallbackTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""

        if (!parseState(intent.getStringExtra(EXTRA_STATE))) {
            Log.e(TAG, "no playable items in state")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        libVLC = LibVLC(this, arrayListOf("--http-reconnect"))
        player = MediaPlayer(libVLC)
        player.attachViews(videoLayout, null, false, false)
        player.setEventListener { ev -> onPlayerEvent(ev) }

        setupOsd()
        playItem(index)
    }

    /** state-JSON от PlayerStateManager.getStateJson: playlist + current_index. */
    private fun parseState(raw: String?): Boolean {
        try {
            val st = JSONObject(raw ?: return false)
            val arr = st.optJSONArray("playlist") ?: return false
            val list = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "")
                if (url.isEmpty()) continue
                val tl = o.optJSONObject("timeline")
                val percent = tl?.optInt("percent", 0) ?: 0
                // резюме как на маке (resolveStartPosition): ≥96% — считаем досмотренным, с начала
                val startMs =
                    if (tl != null && percent in 1 until ENDED_PERCENT) (tl.optDouble("time", 0.0) * 1000).toLong()
                    else 0L
                val quality = mutableListOf<Pair<String, String>>()
                o.optJSONObject("quality")?.let { q ->
                    q.keys().forEach { k -> q.optString(k, "").takeIf { it.isNotEmpty() }?.let { quality.add(k to it) } }
                }
                val subs = mutableListOf<Sub>()
                o.optJSONArray("subtitles")?.let { sa ->
                    for (j in 0 until sa.length()) {
                        val s = sa.optJSONObject(j) ?: continue
                        val su = s.optString("url", "")
                        if (su.isNotEmpty()) subs.add(Sub(su, s.optString("label", "#${j + 1}")))
                    }
                }
                list.add(Item(url, o.optString("title", null.toString()).takeIf { it != "null" && it.isNotEmpty() }, startMs, quality, subs))
            }
            if (list.isEmpty()) return false
            items = list
            index = st.optInt("current_index", 0).coerceIn(0, list.lastIndex)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "parseState failed", e)
            return false
        }
    }

    // ─────────────────────────── воспроизведение ───────────────────────────

    private fun playItem(i: Int) {
        index = i.coerceIn(0, items.lastIndex)
        val item = items[index]
        val rawUrl = qualityUrl(item) ?: item.url
        val signed = D1VAuth.sign(rawUrl) ?: rawUrl
        pendingSeekMs = if (item.startMs > 0) item.startMs else -1
        slavesAdded = false
        lastPosMs = 0; lastDurMs = 0

        Log.i(TAG, "playItem[$index] ${item.title ?: ""} seek=${pendingSeekMs}ms")
        val media = Media(libVLC, Uri.parse(signed))
        media.addOption(":network-caching=1500")
        player.media = media
        media.release()
        player.play()

        osdTitle.text = item.title ?: fallbackTitle
        updatePlayPauseLabel()
        updateProgressUi()
    }

    private fun qualityUrl(item: Item): String? {
        val label = qualityLabel ?: return null
        return item.quality.firstOrNull { it.first == label }?.second
    }

    private fun onPlayerEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                // seek только ПОСЛЕ реального старта (грабля libVLC, зафиксирована на маке)
                if (pendingSeekMs > 0) {
                    player.time = pendingSeekMs
                    pendingSeekMs = -1
                }
                if (!slavesAdded) {
                    slavesAdded = true
                    items[index].subs.forEach { s ->
                        try {
                            val su = D1VAuth.sign(s.url) ?: s.url
                            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(su), false)
                        } catch (e: Exception) {
                            Log.e(TAG, "addSlave failed", e)
                        }
                    }
                }
                handler.post { updatePlayPauseLabel() }
            }

            MediaPlayer.Event.TimeChanged -> {
                if (player.time > 0) lastPosMs = player.time
                handler.post { updateProgressUi() }
            }

            MediaPlayer.Event.LengthChanged -> {
                if (player.length > 0) lastDurMs = player.length
                handler.post { updateProgressUi() }
            }

            MediaPlayer.Event.EndReached -> handler.post { onEndReached() }

            MediaPlayer.Event.EncounteredError -> handler.post {
                Log.e(TAG, "EncounteredError on ${items[index].url}")
                App.toast(R.string.player_error)
                finishWithResult(false)
            }
        }
    }

    private fun onEndReached() {
        // авто-next: гейт playlist_next уже применён при сборке state (runPlayer кладёт
        // плейлист только при playerAutoNext); live не листаем
        if (!isIPTV && index + 1 < items.size) {
            playItem(index + 1)
        } else {
            finishWithResult(true)
        }
    }

    /**
     * Возврат под generic-ветку handleGenericPlayerResult. data = ОРИГИНАЛЬНЫЙ url текущего
     * элемента (не подписанный, не quality-вариант — хотя матчер понимает и его).
     */
    private fun finishWithResult(ended: Boolean) {
        val cur = items.getOrNull(index)
        val out = Intent()
        if (cur != null) out.data = Uri.parse(cur.url)
        val pos = lastPosMs
        val dur = lastDurMs
        val endedByPosition = dur > 0 && pos >= dur * ENDED_PERCENT / 100
        when {
            cur != null && (ended || endedByPosition) -> setResult(RESULT_FIRST_USER, out)
            cur != null && pos > 0 && dur > 0 -> {
                out.putExtra("position", pos.toInt())   // generic-ветка читает getIntExtra
                out.putExtra("duration", dur.toInt())
                setResult(RESULT_OK, out)
            }
            else -> setResult(RESULT_CANCELED)   // live/нет прогресса: без data → тихий выход
        }
        finish()
    }

    // ─────────────────────────── OSD и управление ───────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOsd() {
        findViewById<View>(R.id.playerRoot).setOnClickListener { if (osdVisible()) hideOsd() else showOsd() }

        val prev = findViewById<Button>(R.id.btnPrev)
        val next = findViewById<Button>(R.id.btnNext)
        val audio = findViewById<Button>(R.id.btnAudio)
        val subs = findViewById<Button>(R.id.btnSubs)
        val quality = findViewById<Button>(R.id.btnQuality)

        if (isIPTV || items.size <= 1) { prev.visibility = View.GONE; next.visibility = View.GONE }
        if (isIPTV) findViewById<View>(R.id.osdSeekRow).visibility = View.GONE

        prev.setOnClickListener { bumpOsd(); if (index > 0) playItem(index - 1) }
        next.setOnClickListener { bumpOsd(); if (index + 1 < items.size) playItem(index + 1) }
        btnPlayPause.setOnClickListener { bumpOsd(); togglePause() }
        audio.setOnClickListener { bumpOsd(); showTrackDialog(isAudio = true) }
        subs.setOnClickListener { bumpOsd(); showTrackDialog(isAudio = false) }
        quality.setOnClickListener { bumpOsd(); showQualityDialog() }

        if (BuildConfig.phoneBuild) setupTouchControls()
    }

    /**
     * D1Vision, телефонная сборка: пальцем то, что на ТВ делается пультом.
     *  • перетаскиваемая полоса вместо индикатора-ProgressBar;
     *  • одиночный тап по видео — показать/скрыть OSD (это уже умеет клик по playerRoot,
     *    здесь он переезжает в детектор жестов, иначе двойной тап давал бы и два одиночных);
     *  • двойной тап слева/справа — перемотка тем же seekBy (с той же акселерацией),
     *    по центру — пауза;
     *  • горизонтальный драг по видео — скраб: пока тащим, только рисуем позицию,
     *    а сам seek делаем ОДИН раз на отпускании (иначе libVLC дёргается на каждом кадре).
     * На ТВ ничего из этого не создаётся — dispatchKeyEvent и фокус-навигация не затронуты.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchControls() {
        val root = findViewById<View>(R.id.playerRoot)
        // клик-слушатель больше не нужен: тап обрабатывает детектор (см. onSingleTapConfirmed)
        root.setOnClickListener(null)
        root.isClickable = false

        osdSeekBar = findViewById<SeekBar>(R.id.osdSeekBar).also { bar ->
            if (!isIPTV) {
                osdProgress.visibility = View.GONE
                bar.visibility = View.VISIBLE
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val dur = lastDurMs
                    if (dur > 0) osdTimeCur.text = fmt(dur * progress / 1000)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    seekBarDragging = true
                    handler.removeCallbacks(hideOsdRunnable)   // не гасить панель под пальцем
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    seekBarDragging = false
                    val dur = lastDurMs
                    if (dur > 0) seekTo(dur * sb.progress / 1000)
                    bumpOsd()
                }
            })
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (osdVisible()) hideOsd() else showOsd()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = root.width
                when {
                    isIPTV -> togglePause()
                    e.x < w / 3f -> seekBy(-1)
                    e.x > w * 2f / 3f -> seekBy(+1)
                    else -> { togglePause(); showOsd() }
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (isIPTV || e1 == null) return false
                val dur = lastDurMs
                if (dur <= 0) return false
                val totalX = e2.x - e1.x
                val totalY = e2.y - e1.y
                if (!scrubbing) {
                    // жест засчитываем как скраб, только если он явно горизонтальный —
                    // иначе вертикальные свайпы (шторка, будущая яркость) начинали бы мотать
                    if (abs(totalX) < abs(totalY) || abs(totalX) < 24f) return false
                    scrubbing = true
                    scrubBaseMs = if (lastPosMs > 0) lastPosMs else player.time
                }
                val w = if (root.width > 0) root.width else 1
                scrubTargetMs = (scrubBaseMs + (totalX / w * SCRUB_FULL_MS).toLong())
                    .coerceIn(0L, dur - 1000)
                showScrubUi(scrubTargetMs, dur)
                return true
            }
        })

        root.setOnTouchListener { _, ev ->
            val handled = gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (scrubbing) {
                    scrubbing = false
                    if (scrubTargetMs >= 0) seekTo(scrubTargetMs)
                    scrubTargetMs = -1
                    bumpOsd()
                }
            }
            handled
        }
    }

    /** Пока палец на экране — только рисуем будущую позицию, плеер не трогаем. */
    private fun showScrubUi(targetMs: Long, durMs: Long) {
        osdPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsdRunnable)
        osdTimeCur.text = fmt(targetMs)
        osdTimeDur.text = fmt(durMs)
        val p = (targetMs * 1000 / durMs).toInt()
        osdProgress.progress = p
        osdSeekBar?.progress = p
    }

    /** Единая точка перемотки на абсолютную позицию (тач); клавиши идут через seekBy. */
    private fun seekTo(ms: Long) {
        val dur = lastDurMs
        val target = if (dur > 0) ms.coerceIn(0L, dur - 1000) else ms.coerceAtLeast(0L)
        player.time = target
        lastPosMs = target   // мгновенный отклик: player.time после записи отдаёт старое значение
        updateProgressUi()
    }

    private fun osdVisible() = osdPanel.visibility == View.VISIBLE

    /**
     * Панель в ИНТЕРАКТИВНОМ режиме: фокус на кнопках, стрелки ходят по ним.
     * Отличать от showOsdProgressOnly() (панель как индикатор при перемотке) —
     * решает dispatchKeyEvent: НЕЛЬЗЯ судить по видимости панели. Баг билда 568:
     * гейт стоял на osdVisible() → первая же перемотка показывала панель, и все
     * следующие нажатия стрелок вместо seek лишь продлевали её показ.
     */
    private fun showOsd() {
        osdInteractive = true
        osdPanel.visibility = View.VISIBLE
        updateProgressUi()
        btnPlayPause.requestFocus()
        bumpOsd()
    }

    private fun hideOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        osdInteractive = false
        osdPanel.visibility = View.GONE
    }

    /** продлить показ OSD; на паузе не прячем */
    private fun bumpOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        if (player.isPlaying) handler.postDelayed(hideOsdRunnable, OSD_HIDE_MS)
    }

    private fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
        updatePlayPauseLabel()
        bumpOsd()
    }

    private fun updatePlayPauseLabel() {
        btnPlayPause.setText(if (player.isPlaying) R.string.player_pause else R.string.player_play)
    }

    private fun updateProgressUi() {
        if (!osdVisible()) return
        // пока пользователь тащит полосу или скрабит пальцем — позиция его, не плеера
        if (seekBarDragging || scrubbing) return
        val dur = lastDurMs
        osdTimeCur.text = fmt(lastPosMs)
        osdTimeDur.text = fmt(dur)
        val p = if (dur > 0) (lastPosMs * 1000 / dur).toInt() else 0
        osdProgress.progress = p
        osdSeekBar?.progress = p
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun seekBy(deltaSign: Int) {
        if (isIPTV) return
        val now = System.currentTimeMillis()
        seekStreak = if (now - lastSeekAt < 1000) seekStreak + 1 else 0
        lastSeekAt = now
        val step = when {
            seekStreak >= 8 -> 60_000L
            seekStreak >= 3 -> 30_000L
            else -> SEEK_BASE_MS
        }
        val dur = lastDurMs
        // считаем от lastPosMs, не от player.time: seek в libVLC асинхронный, чтение
        // player.time сразу после записи возвращает СТАРОЕ значение — серия нажатий
        // мотала бы от одной и той же точки
        val base = if (lastPosMs > 0) lastPosMs else player.time
        var target = (base + deltaSign * step).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        player.time = target
        lastPosMs = target   // мгновенный отклик прогресс-бара
        showOsdProgressOnly()
    }

    /** при перемотке панель — только индикатор: НЕ интерактивная, фокус не воруем */
    private fun showOsdProgressOnly() {
        osdPanel.visibility = View.VISIBLE
        updateProgressUi()
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, OSD_HIDE_MS)
    }

    /**
     * Весь пульт — через dispatchKeyEvent, ДО фокус-навигации вью-иерархии.
     * Activity.onKeyDown не годится: (1) кликабельный корень (touch-тап по экрану)
     * съедал DPAD_CENTER — «OK» не ставил паузу; (2) гейт по видимости панели
     * убивал перемотку (см. showOsd). Правило: не интерактивная панель → стрелки
     * мотают; интерактивная → стрелки ходят по кнопкам (super), BACK закрывает.
     * ACTION_UP обработанных клавиш глотаем: незаглоченный BACK-UP дошёл бы до
     * onBackPressed и закрыл плеер без результата.
     */
    private val swallowUp = mutableSetOf<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && swallowUp.remove(event.keyCode)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val key = event.keyCode

        // глобальные медиа-клавиши — в любом режиме
        when (key) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_SPACE -> { togglePause(); swallowUp.add(key); return true }

            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (!isIPTV && index + 1 < items.size) playItem(index + 1); swallowUp.add(key); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (!isIPTV && index > 0) playItem(index - 1); swallowUp.add(key); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(+1); swallowUp.add(key); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-1); swallowUp.add(key); return true }
        }

        if (!osdInteractive) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-1); swallowUp.add(key); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(+1); swallowUp.add(key); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePause(); showOsd(); swallowUp.add(key); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showOsd(); swallowUp.add(key); return true }
                KeyEvent.KEYCODE_BACK -> {
                    swallowUp.add(key)
                    if (osdVisible()) hideOsd() else finishWithResult(false)   // индикатор перемотки закрываем, не выходим
                    return true
                }
            }
        } else {
            if (key == KeyEvent.KEYCODE_BACK) { hideOsd(); swallowUp.add(key); return true }
            // стрелки/OK ходят по кнопкам панели (фокус-навигация); показ продлеваем
            bumpOsd()
        }
        return super.dispatchKeyEvent(event)
    }

    // ─────────────────────────── дорожки и качество ───────────────────────────

    private fun showTrackDialog(isAudio: Boolean) {
        val tracks = (if (isAudio) player.audioTracks else player.spuTracks) ?: emptyArray()
        if (tracks.isEmpty()) { App.toast(R.string.player_no_tracks); return }
        val current = if (isAudio) player.audioTrack else player.spuTrack
        val names = tracks.map { it.name + if (it.id == current) "  ✓" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (isAudio) R.string.player_audio else R.string.player_subs)
            .setItems(names) { d, which ->
                d.dismiss()
                if (isAudio) player.setAudioTrack(tracks[which].id) else player.setSpuTrack(tracks[which].id)
            }
            .setOnDismissListener { bumpOsd() }
            .show()
    }

    private fun showQualityDialog() {
        val q = items[index].quality
        if (q.isEmpty()) { App.toast(R.string.player_no_tracks); return }
        val names = q.map { it.first + if (it.first == qualityLabel) "  ✓" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.player_quality)
            .setItems(names) { d, which ->
                d.dismiss()
                qualityLabel = q[which].first
                // смена качества с сохранением позиции (как на маке)
                val resume = lastPosMs
                playItem(index)
                if (resume > 0) pendingSeekMs = resume
            }
            .setOnDismissListener { bumpOsd() }
            .show()
    }

    // ─────────────────────────── жизненный цикл ───────────────────────────

    override fun onPause() {
        super.onPause()
        // сворачивание/выключение экрана — на ТВ также приход HDMI-CEC; ставим на паузу
        try { if (player.isPlaying) player.pause() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            if (player.time > 0) lastPosMs = player.time
            player.stop()
            player.detachViews()
            player.release()
            libVLC.release()
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
        super.onDestroy()
    }
}
