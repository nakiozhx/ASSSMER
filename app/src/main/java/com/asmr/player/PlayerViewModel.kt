package com.asmr.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackState(val trackId: String? = null, val position: Long = 0, val duration: Long = 0,
                         val playing: Boolean = false, val speed: Float = 1f, val repeat: Int = 0,
                         val sleepRemaining: Long = 0, val buffering: Boolean = false)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LibraryStore(app)
    var pendingExport: String? = null
    var library by mutableStateOf(Library()); private set
    var favorites by mutableStateOf(store.favorites()); private set
    var folderFavorites by mutableStateOf(store.folderFavorites()); private set
    var theme by mutableStateOf(store.setting("theme", "system")); private set
    var accent by mutableStateOf(store.setting("accent", "green")); private set
    var sort by mutableStateOf(store.setting("sort", "name")); private set
    var grid by mutableStateOf(store.setting("grid", "list")); private set
    fun theme(value: String) { theme = value; store.setSetting("theme", value) }
    fun accent(value: String) { accent = value; store.setSetting("accent", value) }
    fun sort(value: String) { sort = value; store.setSetting("sort", value) }
    fun grid(value: String) { grid = value; store.setSetting("grid", value) }
    fun favoriteFolder(key: String) { folderFavorites = if (key in folderFavorites) folderFavorites - key else folderFavorites + key; store.setFolderFavorites(folderFavorites) }
    fun sorted(tracks: List<Track>): List<Track> = tracks.sortedWith { a, b ->
        val name = NaturalOrder.compare(a.name, b.name)
        when (sort) { "reverse" -> -name; "newest" -> b.modified.compareTo(a.modified).takeIf { it != 0 } ?: name
            "oldest" -> a.modified.compareTo(b.modified).takeIf { it != 0 } ?: name; else -> name }
    }
    fun parentUri(): Uri? = currentTrack?.let(store::parent)
    var overlayColor by mutableStateOf(store.setting("overlayColor", "auto")); private set
    fun overlayColor(value: String) { if(value in FloatingPalette.choices) { overlayColor=value;store.setSetting("overlayColor",value) } }
    fun overlayLocked(value: Boolean) { store.setSetting("overlayLocked",value.toString());PlaybackService.overlayLocked.value=value }
    fun overlayTransparent(value: Boolean) { store.setSetting("overlayTransparent",value.toString());PlaybackService.overlayTransparent.value=value }
    fun overlay(enabled: Boolean) { controller?.sendCustomCommand(SessionCommand(PlaybackService.OVERLAY_COMMAND, Bundle.EMPTY), Bundle().apply { putBoolean("enabled", enabled) }) }
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var scanWarnings by mutableStateOf<List<String>>(emptyList()); private set
    var playback by mutableStateOf(PlaybackState()); private set
    var document by mutableStateOf<SubtitleDocument?>(null); private set
    var selectedSubtitle by mutableStateOf<SubtitleFile?>(null); private set
    var subtitleBusy by mutableStateOf(false); private set
    var subtitleError by mutableStateOf<String?>(null); private set
    var offset by mutableStateOf(0L); private set
    var fontSize by mutableStateOf(store.fontSize()); private set
    var selectedFolder by mutableStateOf<String?>(null)
    var screen by mutableStateOf("library")
    var filter by mutableStateOf("")
    var favoriteView by mutableStateOf("all")
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var subtitleJob: Job? = null
    private var loadedDuration = -1L
    val currentTrack get() = library.tracks.find { it.id == playback.trackId }
    val filteredTracks get() = library.tracks.filter {
        (when (favoriteView) { "folders" -> folderKey(it) in folderFavorites; "tracks" -> it.id in favorites; else -> true }) && (filter.isBlank() || it.name.contains(filter, true) || it.folder.contains(filter, true))
    }
    fun folderKey(t: Track) = "${t.root}|${t.folder}"

    init {
        viewModelScope.launch {
            library = withContext(Dispatchers.IO) {
                try { store.load() } catch (_: Exception) {
                    withContext(Dispatchers.Main) { message = "音频库记录无法读取，请重新导入目录" }; Library()
                }
            }
            connect()
        }
    }

    private fun connect() {
        val future = MediaController.Builder(getApplication(), SessionToken(getApplication(), ComponentName(getApplication(), PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get(); ready = true
                viewModelScope.launch {
                    while (true) {
                        val p = controller ?: break
                        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
                        val next = PlaybackState(p.currentMediaItem?.mediaId, p.currentPosition.coerceAtLeast(0), duration, p.isPlaying,
                            p.playbackParameters.speed, p.repeatMode,
                            (PlaybackService.sleepDeadline.value - SystemClock.elapsedRealtime()).coerceAtLeast(0), p.playbackState == Player.STATE_BUFFERING)
                        val changed = next.trackId != playback.trackId
                        playback = next
                        if (changed) {
                            offset = next.trackId?.let(store::offset) ?: 0
                            val track = currentTrack
                            val choice = track?.id?.let(store::subtitleChoice)
                            loadSubtitle(track?.subtitles?.find { it.uri == choice } ?: track?.subtitles?.firstOrNull())
                            checkSubtitles()
                        } else if (next.duration > 0 && next.duration != loadedDuration && selectedSubtitle != null) loadSubtitle(selectedSubtitle)
                        delay(250)
                    }
                }
            } catch (_: Exception) { message = "播放器连接失败，请关闭应用后重新打开" }
        }, androidx.core.content.ContextCompat.getMainExecutor(getApplication()))
    }

    fun clearMessage() { message = null }
    fun notify(text: String) { message = text }
    fun importRoot(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.scan(uri) }
                scanWarnings = result.warnings
                // A complete root failure must not erase an existing library.
                if (result.tracks.isEmpty() && result.warnings.isNotEmpty()) throw IllegalStateException(result.warnings.first())
                val root = uri.toString()
                val rescanned = result.tracks.map { scanned ->
                    val attached = library.tracks.find { it.id == scanned.id }?.subtitles.orEmpty().filter { it.manual && store.exists(it.uri, scanned.root) != false }
                    scanned.copy(subtitles = (scanned.subtitles + attached).distinctBy { it.uri })
                }
                val updated = Library((library.roots + root).distinct(), library.tracks.filter { it.root != root } + rescanned)
                withContext(Dispatchers.IO) { store.save(updated) }
                library = updated
                if (currentTrack?.root == root) reloadCurrentSubtitle()
                message = "已识别 ${result.tracks.size} 个音频，${result.tracks.map { it.folder }.distinct().size} 个分组" + if (result.warnings.isNotEmpty()) "；部分目录无法读取，请查看扫描提示" else ""
            } catch (e: Exception) { message = "导入失败：${e.message}" }
            finally { busy = false }
        }
    }

    fun refresh() {
        if (busy || library.roots.isEmpty()) return
        busy = true
        viewModelScope.launch {
            try {
                val previous = library
                val warnings = mutableListOf<String>()
                val tracks = withContext(Dispatchers.IO) {
                    previous.roots.flatMap { root ->
                        try {
                            val result = store.scan(Uri.parse(root)); warnings += result.warnings
                            if (result.tracks.isEmpty() && result.warnings.isNotEmpty()) previous.tracks.filter { it.root == root }
                            else result.tracks.map { scanned ->
                                val old = previous.tracks.find { it.id == scanned.id }
                                // Keep manually attached subtitles when rescanning.
                                scanned.copy(subtitles = (scanned.subtitles + old?.subtitles.orEmpty().filter { it.manual && store.exists(it.uri, scanned.root) != false }).distinctBy { it.uri })
                            }
                        } catch (_: Exception) {
                            warnings += "目录授权已失效，请重新选择导入目录"
                            previous.tracks.filter { it.root == root }
                        }
                    }
                }
                val updated = previous.copy(tracks = tracks)
                withContext(Dispatchers.IO) { store.save(updated) }; library = updated; scanWarnings = warnings
                reloadCurrentSubtitle()
                message = if (warnings.isEmpty()) "音频库已更新，共 ${tracks.size} 个音频" else "扫描完成，部分目录无法读取；已保留原记录"
            } catch (e: Exception) { message = "更新失败：${e.message}" }
            finally { busy = false }
        }
    }

    fun removeRoot(root: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val updated = library.copy(roots = library.roots - root, tracks = library.tracks.filter { it.root != root })
                withContext(Dispatchers.IO) { store.save(updated) }
                if (currentTrack?.root == root) { controller?.pause(); controller?.clearMediaItems() }
                library = updated; selectedFolder = null
                message = "已从音频库移除，原文件保留"
            } catch (e: Exception) { message = "移除失败：${e.message}" }
            finally { busy = false }
        }
    }

    fun play(track: Track) {
        val p = controller ?: return
        if (p.currentMediaItem?.mediaId == track.id) { p.play(); screen = "player"; return }
        val queue = sorted(library.tracks.filter { it.root == track.root && it.folder == track.folder })
        val index = queue.indexOfFirst { it.id == track.id }
        val saved = getApplication<Application>().getSharedPreferences("playback", 0).getLong("position:${track.id}", 0)
        p.setMediaItems(queue.map { it.mediaItem() }, index, saved); p.prepare(); p.play(); screen = "player"
    }
    fun togglePlay() { controller?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() } }
    fun seek(position: Long) { controller?.seekTo(position.coerceIn(0, playback.duration.coerceAtLeast(0))) }
    fun skip(ms: Long) { seek(playback.position + ms) }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun next() { controller?.seekToNextMediaItem() }
    fun speed(value: Float) { controller?.setPlaybackSpeed(value) }
    fun repeat(value: Int) { controller?.repeatMode = value }
    fun timer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(PlaybackService.TIMER_COMMAND, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }
    fun favorite(track: Track) { favorites = if (track.id in favorites) favorites - track.id else favorites + track.id; store.setFavorites(favorites) }
    fun offset(value: Long) { offset = value.coerceIn(-60_000, 60_000); currentTrack?.let { store.setOffset(it.id, offset) } }
    fun font(value: Float) { fontSize = value.coerceIn(14f, 32f); store.setFontSize(fontSize) }

    fun loadSubtitle(file: SubtitleFile?) {
        subtitleJob?.cancel(); selectedSubtitle = file; document = null; subtitleError = null
        loadedDuration = playback.duration
        if (file == null) { subtitleBusy = false; return }
        val duration = playback.duration.takeIf { it > 0 }
        subtitleBusy = true
        subtitleJob = viewModelScope.launch {
            try {
                document = withContext(Dispatchers.IO) { store.readSubtitle(file, duration) }
                currentTrack?.let { store.setSubtitleChoice(it.id, file.uri) }
                if (PlaybackService.overlayVisible.value) overlay(true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { subtitleError = "字幕读取失败：${e.message}" }
            finally { subtitleBusy = false }
        }
    }
    private fun reloadCurrentSubtitle() {
        val track = currentTrack
        loadSubtitle(track?.subtitles?.find { it.uri == selectedSubtitle?.uri } ?: track?.subtitles?.firstOrNull())
    }
    fun attachSubtitle(uri: Uri, trackId: String? = playback.trackId) {
        val track = library.tracks.find { it.id == trackId } ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val name = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "字幕.txt"
                    SubtitleFile(uri.toString(), name, manual = true)
                }
                require(file.extension in setOf("txt", "vtt", "srt", "lrc", "ass", "ssa")) { "请选择 TXT、VTT、SRT、LRC、ASS 或 SSA 文件" }
                val updated = library.copy(tracks = library.tracks.map { if (it.id == track.id) it.copy(subtitles = (it.subtitles + file).distinctBy { s -> s.uri }) else it })
                withContext(Dispatchers.IO) { store.save(updated) }; library = updated
                if (currentTrack?.id == track.id) loadSubtitle(file)
                message = "已关联 ${file.name}"
            } catch (e: Exception) { message = "字幕关联失败：${e.message}" }
            finally { busy = false }
        }
    }
    var batchReport by mutableStateOf<List<String>>(emptyList()); private set
    fun clearBatchReport() { batchReport=emptyList() }
    fun batchNeedsPermission(key: String) = library.tracks.firstOrNull { folderKey(it)==key }?.let { store.writableTree(it)==null } ?: true
    fun batchInitialUri(key: String): Uri? = library.tracks.firstOrNull { folderKey(it)==key }?.let(store::parent)
    fun batchExport(key: String, format: String, writeTree: Uri? = null) {
        if(busy) return
        busy=true
        val tracks=library.tracks.filter {folderKey(it)==key}
        viewModelScope.launch {
            try { batchReport=withContext(Dispatchers.IO) {store.batchExport(tracks,format,writeTree)} }
            catch(e: Exception) {batchReport=listOf("导出失败：${e.message}")}
            finally {busy=false}
            refresh()
        }
    }
    private var checkJob: Job? = null
    fun checkSubtitles(onComplete: () -> Unit = {}) {
        if(busy) {onComplete();return}
        val track=currentTrack ?: return
        checkJob?.cancel()
        checkJob=viewModelScope.launch {
            val missing=withContext(Dispatchers.IO) {track.subtitles.filter {store.exists(it.uri, track.root)==false}.map {it.uri}.toSet()}
            if(missing.isNotEmpty()) {
                val updated=library.copy(tracks=library.tracks.map {if(it.id==track.id) it.copy(subtitles=it.subtitles.filterNot {s->s.uri in missing}) else it})
                withContext(Dispatchers.IO) {store.save(updated)};library=updated
                if(store.subtitleChoice(track.id) in missing) store.clearSubtitleChoice(track.id)
                if(currentTrack?.id==track.id && selectedSubtitle?.uri in missing) reloadCurrentSubtitle()
            }
            onComplete()
        }
    }
    fun exportText(format: String = "vtt"): String? = try { document?.let { if (format == "lrc") Subtitles.lrc(it, playback.duration) else Subtitles.vtt(it, playback.duration) } }
        catch (e: Exception) { message = e.message; null }
    fun writeExport(uri: Uri, text: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("目标位置不可写")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                message = "字幕已导出，原文件保留"
            } catch (e: Exception) { message = "导出失败：${e.message}" }
        }
    }
    override fun onCleared() {
        controllerFuture?.let(MediaController::releaseFuture)
        super.onCleared()
    }
}
