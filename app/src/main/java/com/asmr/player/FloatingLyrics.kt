package com.asmr.player

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import kotlinx.coroutines.*

/** Owned by playback, so subtitles continue after the activity leaves the foreground. */
class FloatingLyrics(private val context: Context, private val player: Player) {
    private val store = LibraryStore(context)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var window: LinearLayout? = null
    private var label: TextView? = null
    private var document: SubtitleDocument? = null
    private var loadedKey = ""
    private var enabled = false
    private val dp = context.resources.displayMetrics.density
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (20 * dp).toInt(); y = (120 * dp).toInt() }

    fun enable(value: Boolean) {
        if (!value || !Settings.canDrawOverlays(context)) { disable(); return }
        if (window != null) { loadedKey = ""; return }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
        }
        label = TextView(context).apply {
            textSize = store.fontSize(); maxLines = 5
            maxWidth = (context.resources.displayMetrics.widthPixels - 100 * dp).toInt().coerceAtLeast((120 * dp).toInt())
            text = "等待字幕"; setTextColor(Color.WHITE)
        }
        row.addView(label)
        val close = TextView(context).apply {
            text = "×"; textSize = 24f; contentDescription = "关闭悬浮字幕"
            setTextColor(Color.WHITE); setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener { disable() }
        }
        row.addView(close)
        var startX = 0f; var startY = 0f; var originX = 0; var originY = 0
        val drag = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originX = params.x; originY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (originX + event.rawX - startX).toInt().coerceIn(0, (context.resources.displayMetrics.widthPixels - row.width).coerceAtLeast(0))
                    params.y = (originY + event.rawY - startY).toInt().coerceIn(0, (context.resources.displayMetrics.heightPixels - row.height).coerceAtLeast(0))
                    runCatching { manager.updateViewLayout(row, params) }.onFailure { disable() }; true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                else -> false
            }
        }
        row.setOnTouchListener(drag); label?.setOnTouchListener(drag)
        try {
            manager.addView(row, params); window = row; enabled = true
            PlaybackService.overlayVisible.value = true; loadedKey = ""; update()
        } catch (_: Exception) { disable() }
    }

    fun update() {
        if (!enabled) return
        if (!Settings.canDrawOverlays(context)) { disable(); return }
        val id = player.currentMediaItem?.mediaId
        val selected = id?.let(store::subtitleChoice)
        val duration = player.duration.takeIf { it > 0 } ?: 0
        val key = "$id|$selected|$duration"
        if (key != loadedKey) {
            loadedKey = key; document = null; job?.cancel()
            job = scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val track = store.load().tracks.find { it.id == id }
                        val file = track?.subtitles?.find { it.uri == selected } ?: track?.subtitles?.firstOrNull()
                        file?.let { store.readSubtitle(it, duration.takeIf { value -> value > 0 }) }
                    }.getOrNull()
                }
                if (loadedKey == key) document = result
            }
        }
        val doc = document
        label?.text = when {
            id == null -> "请选择音频"
            doc == null -> "暂无字幕"
            !doc.timed -> "此字幕没有时间轴"
            else -> doc.at(player.currentPosition - store.offset(id)).joinToString("\n") { doc.cues[it].text }.ifBlank { "…" }
        }
        label?.textSize = store.fontSize()
        val dark = when (store.setting("theme", "system")) {
            "light" -> false; "dark" -> true
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        label?.setTextColor(if (dark) Color.WHITE else Color.rgb(24, 35, 28))
        (window?.getChildAt(1) as? TextView)?.setTextColor(if (dark) Color.WHITE else Color.rgb(24, 35, 28))
        window?.background = GradientDrawable().apply {
            setColor(if (dark) Color.argb(235, 28, 41, 35) else Color.argb(245, 240, 245, 240)); cornerRadius = 16 * dp
        }
    }

    private fun disable() {
        enabled = false; job?.cancel(); window?.let { runCatching { manager.removeView(it) } }
        window = null; label = null; document = null; PlaybackService.overlayVisible.value = false
    }
    fun destroy() { disable(); scope.cancel() }
}
