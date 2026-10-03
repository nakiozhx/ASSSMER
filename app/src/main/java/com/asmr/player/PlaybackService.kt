package com.asmr.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        const val OVERLAY_COMMAND = "com.asmr.player.OVERLAY"
        val overlayVisible = MutableStateFlow(false)
        const val TIMER_COMMAND = "com.asmr.player.SLEEP_TIMER"
        val sleepDeadline = MutableStateFlow(0L)
        val playbackError = MutableStateFlow<String?>(null)
    }
    private lateinit var player: ExoPlayer
    private lateinit var lyrics: FloatingLyrics
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSavedId: String? = null
    private var lastSavedPosition = 0L
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var lastProgressSave = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (sleepDeadline.value > 0 && SystemClock.elapsedRealtime() >= sleepDeadline.value) {
                player.pause(); sleepDeadline.value = 0
            }
            if (SystemClock.elapsedRealtime() - lastProgressSave >= 1000) { saveProgress(); lastProgressSave = SystemClock.elapsedRealtime() }
            lyrics.update()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            setSeekBackIncrementMs(10_000)
            setSeekForwardIncrementMs(10_000)
        }
        lyrics = FloatingLyrics(this, player)
        session = MediaSession.Builder(this, player).setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val base = super.onConnect(s, controller)
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(SessionCommand(TIMER_COMMAND, Bundle.EMPTY)).add(SessionCommand(OVERLAY_COMMAND, Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(s: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction == OVERLAY_COMMAND) {
                        lyrics.enable(args.getBoolean("enabled"))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction != TIMER_COMMAND) return super.onCustomCommand(s, controller, command, args)
                    val minutes = args.getInt("minutes", 0).coerceIn(0, 480)
                    sleepDeadline.value = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                lastSavedId?.let { id -> prefs.edit().putLong("position:$id",
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) 0 else lastSavedPosition).apply() }
                lastSavedId = mediaItem?.mediaId; lastSavedPosition = player.currentPosition
                saveQueue(); saveProgress()
                playbackError.value = null
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { saveQueue() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { saveProgress() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let { prefs.edit().putLong("position:$it", 0).apply() }
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackError.value = "无法播放此文件：可能已移动、目录授权失效，或设备不支持该音频编码。请重新扫描或选择其他文件。"
            }
        })
        restoreQueue()
        handler.post(tick)
    }

    private fun saveQueue() {
        prefs.edit().putString("queue", JSONArray((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }).toString())
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0)).apply()
    }
    private fun saveProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        lastSavedId = id; lastSavedPosition = player.currentPosition.coerceAtLeast(0)
        prefs.edit().putLong("position:$id", if (player.playbackState == Player.STATE_ENDED) 0 else lastSavedPosition)
            .putString("lastTrack", id).putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putFloat("speed", player.playbackParameters.speed).putInt("repeat", player.repeatMode).apply()
    }
    private fun restoreQueue() {
        runCatching {
            val library = LibraryStore(this).load().tracks.associateBy { it.id }
            val items = JSONArray(prefs.getString("queue", "[]")).strings().mapNotNull { library[it]?.mediaItem() }
            if (items.isNotEmpty()) {
                val lastId = prefs.getString("lastTrack", null)
                val index = items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
                val position = prefs.getLong("position:${items[index].mediaId}", 0)
                player.setMediaItems(items, index, position); player.prepare()
            }
            player.setPlaybackSpeed(prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f))
            player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2)
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        saveProgress(); handler.removeCallbacks(tick); sleepDeadline.value = 0
        lyrics.destroy(); session?.release(); player.release(); super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }
}
