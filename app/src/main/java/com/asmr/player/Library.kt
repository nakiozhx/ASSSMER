package com.asmr.player

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class SubtitleFile(val uri: String, val name: String, val manual: Boolean = false) {
    val extension get() = name.substringAfterLast('.', "txt").lowercase(Locale.ROOT)
}
data class Track(val id: String, val uri: String, val name: String, val folder: String, val root: String,
                 val subtitles: List<SubtitleFile>, val parentUri: String = "", val modified: Long = 0) {
    fun mediaItem() = MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(name.substringBeforeLast('.')).setArtist(folder)
            .setAlbumTitle(folder.substringBefore('/')).build()).build()
}
data class Library(val roots: List<String> = emptyList(), val tracks: List<Track> = emptyList())
data class ScanResult(val tracks: List<Track>, val warnings: List<String>)

class LibraryStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val prefs = context.getSharedPreferences("library-settings", Context.MODE_PRIVATE)
    fun load(): Library = try {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        Library(json.getJSONArray("roots").strings(), (json.getJSONArray("tracks")).let { rows ->
            (0 until rows.length()).map { i ->
                val t = rows.getJSONObject(i)
                Track(t.getString("id"), t.getString("uri"), t.getString("name"), t.getString("folder"), t.getString("root"),
                    t.getJSONArray("subtitles").let { list -> (0 until list.length()).map { j ->
                        val s = list.getJSONObject(j); SubtitleFile(s.getString("uri"), s.getString("name"), s.optBoolean("manual", false))
                    } }, t.optString("parentUri"), t.optLong("modified"))
            }
        })
    } catch (_: java.io.FileNotFoundException) { Library() }

    fun save(library: Library) {
        val json = JSONObject().put("roots", JSONArray(library.roots)).put("tracks", JSONArray(library.tracks.map { t ->
            JSONObject().put("id", t.id).put("uri", t.uri).put("name", t.name).put("folder", t.folder).put("root", t.root)
                .put("parentUri", t.parentUri).put("modified", t.modified)
                .put("subtitles", JSONArray(t.subtitles.map { JSONObject().put("uri", it.uri).put("name", it.name).put("manual", it.manual) }))
        }))
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun setting(key: String, default: String = ""): String = prefs.getString(key, default) ?: default
    fun setSetting(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun folderFavorites(): Set<String> = prefs.getStringSet("folderFavorites", emptySet()).orEmpty().toSet()
    fun setFolderFavorites(ids: Set<String>) { prefs.edit().putStringSet("folderFavorites", ids).apply() }
    fun parent(track: Track): Uri? = runCatching {
        if (track.parentUri.isNotEmpty()) Uri.parse(track.parentUri)
        else DocumentsContract.findDocumentPath(context.contentResolver, Uri.parse(track.uri))?.path?.dropLast(1)?.lastOrNull()
            ?.let { DocumentsContract.buildDocumentUriUsingTree(Uri.parse(track.root), it) }
    }.getOrNull()
    // null means access could not be checked; do not mistake revoked access for deletion.
    fun exists(uri: String, accessRoot: String? = null): Boolean? {
        val document=Uri.parse(uri)
        try {
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { return it.moveToFirst() }
        } catch (_: java.io.FileNotFoundException) { return false }
          catch (_: Exception) { /* A deleted tree child may raise SecurityException. */ }
        // External storage IDs encode the actual parent. An accessible directory listing
        // distinguishes deletion from loss of permission without discarding inaccessible files.
        if(document.authority=="com.android.externalstorage.documents") {
            try {
                val id=DocumentsContract.getDocumentId(document)
                val parentId=id.substringBeforeLast('/', "")
                if(parentId.isNotEmpty()) {
                    val tree=accessRoot?.let(Uri::parse)?.takeIf {it.authority==document.authority} ?: document
                    val parent=if(DocumentsContract.isTreeUri(tree)) DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId)
                        else DocumentsContract.buildChildDocumentsUri(document.authority,parentId)
                    context.contentResolver.query(parent,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use { cursor ->
                        while(cursor.moveToNext()) if(cursor.getString(0)==id) return true
                        return false
                    }
                }
            } catch (_: Exception) { }
        }
        return try {
            context.contentResolver.openFileDescriptor(document,"r")?.use { true }
        } catch (_: java.io.FileNotFoundException) { false }
          catch (_: Exception) { null }
    }
    fun clearSubtitleChoice(id: String) { prefs.edit().remove("subtitle:$id").apply() }
    fun hasWrite(root: String) = context.contentResolver.persistedUriPermissions.any { it.uri.toString()==root && it.isWritePermission }
    fun writableTree(track: Track): Uri? = runCatching {
        val directory = parent(track) ?: return@runCatching null
        val path = DocumentsContract.findDocumentPath(context.contentResolver,directory)?.path.orEmpty() + DocumentsContract.getDocumentId(directory)
        context.contentResolver.persistedUriPermissions.filter { grant ->
            grant.isWritePermission && grant.uri.authority==directory.authority &&
                runCatching { DocumentsContract.getTreeDocumentId(grant.uri) in path }.getOrDefault(false)
        }.maxByOrNull { path.indexOf(DocumentsContract.getTreeDocumentId(it.uri)) }?.uri
    }.getOrNull()
    fun batchExport(tracks: List<Track>, format: String, writeTree: Uri?): List<String> {
        require(format in listOf("vtt", "lrc"))
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        tracks.forEach { track ->
            val originalParent = parent(track) ?: run { results += "${track.name}：找不到所在文件夹"; return@forEach }
            val tree = writeTree ?: writableTree(track) ?: Uri.parse(track.root)
            val directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(originalParent))
            val path = DocumentsContract.findDocumentPath(context.contentResolver, originalParent)?.path.orEmpty()
            require(DocumentsContract.getTreeDocumentId(tree) in path || DocumentsContract.getTreeDocumentId(tree)==DocumentsContract.getDocumentId(originalParent)) { "请选择音频所在文件夹或其上级目录" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory))
            val files = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> buildList<Pair<String,String>> { while(cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
                ?: throw IllegalStateException("文件夹无法读取")
            val txts = files.filter { it.second.endsWith(".txt", true) && Sidecars.matches(track.name, it.second) }
            txts.forEach txtLoop@ { (id, name) ->
                if(!seen.add("$directory|$id")) return@txtLoop
                val target = name.substringBeforeLast('.') + ".$format"
                if(files.any { it.second.equals(target, true) }) { results += "$target：同名文件已存在，跳过"; return@txtLoop }
                var created: Uri? = null
                try {
                    val source = SubtitleFile(DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),name)
                    val preliminary = readSubtitle(source,null)
                    if(!preliminary.timed) { results += "$name：没有时间轴，跳过"; return@txtLoop }
                    val retriever = MediaMetadataRetriever()
                    val duration = try {
                        retriever.setDataSource(context,Uri.parse(track.uri))
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    } finally { retriever.release() }
                    require(duration>0) { "无法读取音频时长" }
                    val doc = readSubtitle(source,duration)
                    val text = if(format=="vtt") Subtitles.vtt(doc,duration) else Subtitles.lrc(doc,duration)
                    created = DocumentsContract.createDocument(context.contentResolver,directory,"application/octet-stream",target)
                        ?: throw IllegalStateException("文件夹不可写")
                    context.contentResolver.openOutputStream(created,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                        ?: throw IllegalStateException("文件无法写入")
                    results += "$target：已导出"
                } catch(e: Exception) {
                    created?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver,it) } }
                    results += "$name：失败，${e.message}"
                }
            }
        }
        return results.ifEmpty { listOf("此文件夹没有与音频同名的 TXT 字幕") }
    }
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    fun setFavorites(ids: Set<String>) { prefs.edit().putStringSet("favorites", ids).apply() }
    fun subtitleChoice(id: String): String? = prefs.getString("subtitle:$id", null)
    fun setSubtitleChoice(id: String, uri: String) { prefs.edit().putString("subtitle:$id", uri).apply() }
    fun offset(id: String): Long = prefs.getLong("offset:$id", 0)
    fun setOffset(id: String, offset: Long) { prefs.edit().putLong("offset:$id", offset).apply() }
    fun fontSize(): Float = prefs.getFloat("fontSize", 20f)
    fun setFontSize(size: Float) { prefs.edit().putFloat("fontSize", size).apply() }

    fun scan(root: Uri): ScanResult {
        val tracks = mutableListOf<Track>()
        val warnings = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val audio = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "mp4", "mka", "wma", "aiff")
        val sub = setOf("txt", "vtt", "srt", "lrc", "ass", "ssa")
        data class Entry(val id: String, val name: String, val mime: String, val modified: Long)
        val rootId = DocumentsContract.getTreeDocumentId(root)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(root, rootId)
        val rootName = context.contentResolver.query(rootDoc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "导入目录"
        fun visit(id: String, path: String, depth: Int) {
            if (!visited.add(id)) return
            if (depth > 64) { warnings += "$path：目录层级过深，已跳过"; return }
            val entries = mutableListOf<Entry>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
            try {
                val cursor = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
                    ?: throw IllegalStateException("目录无法读取")
                cursor.use { while (it.moveToNext()) entries += Entry(it.getString(0), it.getString(1) ?: "未命名", it.getString(2).orEmpty(), it.getLong(3)) }
            } catch (e: Exception) { warnings += "$path：无法读取，请重新授权目录"; return }
            val subs = entries.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in sub }
            entries.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.forEach { entry ->
                if (entry.mime == DocumentsContract.Document.MIME_TYPE_DIR) visit(entry.id, "$path/${entry.name}", depth + 1)
                else if (entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in audio || entry.mime.startsWith("audio/")) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(root, entry.id).toString()
                    val candidates = subs.filter { Sidecars.matches(entry.name, it.name) }
                        .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                        .map { SubtitleFile(DocumentsContract.buildDocumentUriUsingTree(root, it.id).toString(), it.name) }
                    tracks += Track(uri, uri, entry.name, path, root.toString(), candidates, DocumentsContract.buildDocumentUriUsingTree(root, id).toString(), entry.modified)
                }
            }
        }
        visit(rootId, rootName, 0)
        convertMatchingTxt(tracks, warnings)
        return ScanResult(tracks, warnings)
    }

    // Import/rescan converts subtitles without requiring each track to be opened.
    private fun convertMatchingTxt(tracks: List<Track>, warnings: MutableList<String>) {
        tracks.forEach { track ->
            var duration: Long? = null
            track.subtitles.filter { it.extension == "txt" }.forEach txtLoop@ { txt ->
                try {
                    if (!readSubtitle(txt, null).timed) return@txtLoop
                    if (duration == null) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, Uri.parse(track.uri))
                            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                        } finally { retriever.release() }
                    }
                    if (duration != null) {
                        val document = readSubtitle(txt, duration)
                        warnings += document.warnings.map { "${track.folder}/${txt.name}：$it" }
                    } else {
                        warnings += "${track.folder}/${txt.name}：暂未读取到音频时长，将在播放时自动转换"
                    }
                } catch (_: Exception) {
                    warnings += "${track.folder}/${txt.name}：预转换未完成，将在播放时重试"
                }
            }
        }
    }

    fun readSubtitle(file: SubtitleFile, durationMs: Long?): SubtitleDocument {
        val input = context.contentResolver.openInputStream(Uri.parse(file.uri)) ?: throw IllegalStateException("字幕文件无法读取")
        val bytes = input.use { stream ->
            val limit = 8 * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                require(out.size() + n <= limit) { "字幕超过 8MB，请缩小文件后重试" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val document = Subtitles.parse(Subtitles.decode(bytes), file.extension, durationMs)
        if (file.extension == "txt" && document.timed && durationMs != null && durationMs > 0) {
            val dir = File(context.filesDir, "converted").apply { mkdirs() }
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(file.uri.toByteArray()).joinToString("") { "%02x".format(it) }
            val cache = AtomicFile(File(dir, "$key.vtt"))
            val out = cache.startWrite()
            try { out.write(Subtitles.vtt(document, durationMs).toByteArray(Charsets.UTF_8)); cache.finishWrite(out) }
            catch (e: Exception) { cache.failWrite(out); throw e }
        }
        return document
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

object Sidecars {
    fun matches(audio: String, subtitle: String): Boolean {
        val base = subtitle.substringBeforeLast('.').lowercase(Locale.ROOT)
        val name = audio.lowercase(Locale.ROOT)
        return base == name || base == name.substringBeforeLast('.')
    }
}
