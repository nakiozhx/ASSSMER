package com.asmr.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.media3.common.Player
import kotlinx.coroutines.*

/** Owned by playback, so subtitles continue after the activity leaves the foreground. */
class FloatingLyrics(private val context: Context, private val player: Player) {
    private val store = LibraryStore(context)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var window: LinearLayout? = null
    private var viewport: FrameLayout? = null
    private var strip: LinearLayout? = null
    private var lockButton: TextView? = null
    private var closeButton: TextView? = null
    private var controls: LinearLayout? = null
    private var document: SubtitleDocument? = null
    private var timeline: FloatingTimeline? = null
    private var loadedKey = ""
    private var enabled = false
    private var frame: FloatingFrame? = null
    private var styleKey = ""
    private var lastPosition = -1L
    private val dp = context.resources.displayMetrics.density
    private fun px(value: Int) = (value * dp).toInt()
    private val previewHeight get() = px((store.fontSize() * 1.55f + 4).toInt().coerceAtLeast(26))
    private val currentHeight get() = px(((store.fontSize() + 4) * 2.15f + 4).toInt())
    private val textHeight get() = previewHeight * 2 + currentHeight
    private val params = WindowManager.LayoutParams(
        minOf(px(360), context.resources.displayMetrics.widthPixels - px(24)), px(48) + textHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = px(12); y = px(120) }

    init {
        PlaybackService.overlayLocked.value = store.setting("overlayLocked", "false").toBoolean()
        PlaybackService.overlayTransparent.value = store.setting("overlayTransparent", "false").toBoolean()
    }

    fun enable(value: Boolean) {
        if (!value || !Settings.canDrawOverlays(context)) { disable(); return }
        if (window != null) { loadedKey = ""; return }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(12), px(8))
            clipChildren = true
        }
        val bar = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        controls = bar
        lockButton = TextView(context).apply {
            textSize = 12f; gravity = Gravity.CENTER
            setOnClickListener {
                val locked = !PlaybackService.overlayLocked.value
                store.setSetting("overlayLocked", locked.toString())
                PlaybackService.overlayLocked.value = locked
                update()
            }
        }
        bar.addView(lockButton, LinearLayout.LayoutParams(px(70), px(40)))
        closeButton = TextView(context).apply {
            text = "×"; textSize = 24f; gravity = Gravity.CENTER; contentDescription = "关闭悬浮字幕"
            setOnClickListener { disable() }
        }
        bar.addView(closeButton, LinearLayout.LayoutParams(px(44), px(40)))
        row.addView(bar, LinearLayout.LayoutParams(-1, px(40)))
        viewport = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
        row.addView(viewport, LinearLayout.LayoutParams(-1, textHeight))
        var startX = 0f; var startY = 0f; var originX = 0; var originY = 0
        val drag = View.OnTouchListener { view, event ->
            if (PlaybackService.overlayLocked.value) return@OnTouchListener false
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
        row.setOnTouchListener(drag); viewport?.setOnTouchListener(drag)
        try {
            manager.addView(row, params); window = row; enabled = true
            PlaybackService.overlayVisible.value = true; loadedKey = ""; styleKey = ""; frame = null; update()
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
            loadedKey = key; document = null; timeline = null; frame = null; job?.cancel()
            job = scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val track = store.load().tracks.find { it.id == id }
                        val file = track?.subtitles?.find { it.uri == selected } ?: track?.subtitles?.firstOrNull()
                        file?.let { store.readSubtitle(it, duration.takeIf { value -> value > 0 }) }
                    }.getOrNull()?.let { it to FloatingTimeline(it) }
                }
                if (loadedKey == key) { document = result?.first; timeline = result?.second }
            }
        }
        val dark = when (store.setting("theme", "system")) {
            "light" -> false; "dark" -> true
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val transparent = PlaybackService.overlayTransparent.value
        val locked = PlaybackService.overlayLocked.value
        val colorChoice = store.setting("overlayColor", "auto")
        val nextStyle = "$dark|$transparent|$locked|${store.fontSize()}|$colorChoice"
        val styleChanged = nextStyle != styleKey
        if (styleChanged) {
            styleKey = nextStyle
            // Fixed for the chosen font size, independent of text length and cue gaps.
            params.flags = if(locked) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            // Android 12+ requires an untrusted pass-through overlay to stay below
            // the system obscuring-opacity threshold when touching another app.
            params.alpha = if(locked) 0.75f else 1f
            controls?.visibility = if(locked) View.GONE else View.VISIBLE
            params.height = px(if(locked) 8 else 48) + textHeight
            viewport?.layoutParams = viewport?.layoutParams?.apply { height = textHeight }
            window?.let { row -> runCatching { manager.updateViewLayout(row, params) }.onFailure { disable() } }

            window?.background = if (transparent) null else GradientDrawable().apply {
                setColor(if (dark) Color.argb(235, 28, 41, 35) else Color.argb(245, 240, 245, 240)); cornerRadius = px(16).toFloat()
            }
            val color = FloatingPalette.color(colorChoice, transparent || dark)
            listOfNotNull(lockButton, closeButton).forEach {
                it.setTextColor(color)
                it.setShadowLayer(if (transparent) 5f * dp else 0f, 0f, dp, Color.BLACK)
            }
            lockButton?.text = if (locked) "解锁" else "锁定"
            lockButton?.contentDescription = if (locked) "解锁悬浮字幕位置" else "锁定悬浮字幕位置"
        }
        val position = player.currentPosition - (id?.let(store::offset) ?: 0)
        val next = when {
            id == null -> FloatingFrame(-1, "", "请选择音频", "")
            document == null -> FloatingFrame(-1, "", "暂无字幕", "")
            document?.timed != true -> FloatingFrame(-1, "", "此字幕没有时间轴", "")
            else -> timeline?.at(position) ?: FloatingFrame(-1, "", "", "")
        }
        if (next != frame || styleChanged) {
            val old = frame
            val advance = !styleChanged && old != null && old.index >= 0 && next.index == old.index + 1 &&
                position >= lastPosition && position - lastPosition < 1500
            render(next, if (advance) old else null, dark, transparent)
            frame = next
        }
        lastPosition = position
    }

    private fun render(next: FloatingFrame, old: FloatingFrame?, dark: Boolean, transparent: Boolean) {
        val host = viewport ?: return
        val outgoing = strip
        for(i in 0 until host.childCount) host.getChildAt(i).animate().cancel()
        host.removeAllViews()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(next.previous, next.current, next.next)
        rows.forEachIndexed { index, text ->
            val isCurrent = index == 1
            val label = TextView(context).apply {
                this.text = text; gravity = Gravity.CENTER_VERTICAL; maxLines = 2
                ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                setPadding(px(4), px(1), px(4), px(1))
                setLineSpacing(0f, 0.95f)
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(FloatingPalette.color(store.setting("overlayColor", "auto"), transparent || dark))
                alpha = if (isCurrent) { if(next.active) 1f else 0.75f } else 0.55f
                setShadowLayer(dp * 2, 0f, dp * 0.5f, if(currentTextColor == Color.BLACK) Color.WHITE else Color.BLACK)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, (store.fontSize().toInt() + if(isCurrent) 4 else 0).coerceAtLeast(12), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            content.addView(label, LinearLayout.LayoutParams(-1, if(isCurrent) currentHeight else previewHeight))
        }
        if(old != null && outgoing != null) host.addView(outgoing, FrameLayout.LayoutParams(-1, textHeight))
        host.addView(content, FrameLayout.LayoutParams(-1, textHeight)); strip = content
        if (old != null && outgoing != null) {
            val distance = (previewHeight + currentHeight) / 2f
            content.translationY = distance; content.alpha = 0f
            outgoing.animate().translationY(-distance).alpha(0f).setDuration(280).start()
            content.animate().translationY(0f).alpha(1f).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).withEndAction {
                    if (strip === content) host.removeView(outgoing)
                }.start()
        }
    }

    private fun disable() {
        enabled = false; job?.cancel(); strip?.animate()?.cancel()
        window?.let { runCatching { manager.removeView(it) } }
        window = null; viewport = null; strip = null; lockButton = null; closeButton = null; controls = null
        document = null; timeline = null; frame = null; PlaybackService.overlayVisible.value = false
    }
    fun destroy() { disable(); scope.cancel() }
}
