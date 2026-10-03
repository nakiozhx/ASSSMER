package com.asmr.player

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Locale

data class WordCue(val startMs: Long, val text: String)
data class Cue(val startMs: Long, val endMs: Long?, val text: String, val words: List<WordCue> = emptyList())
data class SubtitleDocument(val cues: List<Cue>, val plainText: String, val warnings: List<String>) {
    val timed: Boolean get() = cues.isNotEmpty()
    fun at(positionMs: Long): List<Int> = cues.indices.filter {
        positionMs >= cues[it].startMs && positionMs < (cues[it].endMs ?: Long.MAX_VALUE)
    }
}

object Subtitles {
    private const val TIME = "\\d{1,3}:\\d{2}(?::\\d{2})?(?:[.,]\\d{1,3})?"
    private val stamp = Regex("($TIME)")
    private val bracket = Regex("\\[($TIME)]")
    private val arrow = Regex("($TIME)\\s*-->\\s*($TIME)")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { bytes.toString(Charset.forName("GB18030")) }
    }

    fun time(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        if (seconds < 0 || seconds >= 60 || minutes < 0 || (parts.size == 3 && minutes >= 60) || hours < 0) return null
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }

    fun parse(text: String, extension: String, durationMs: Long? = null): SubtitleDocument {
        val input = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val warnings = mutableListOf<String>()
        val raw = when {
            extension.equals("txt", true) && input.lineSequence().any { arrow.containsMatchIn(it) } -> parseBlocks(input, warnings)
            extension.lowercase(Locale.ROOT) in listOf("vtt", "srt") -> parseBlocks(input, warnings)
            extension.lowercase(Locale.ROOT) in listOf("ass", "ssa") -> parseAss(input, warnings)
            else -> parseLines(input, warnings)
        }
        val valid = mutableListOf<Cue>()
        raw.forEachIndexed { index, cue ->
            if (index > 0 && cue.startMs < raw[index - 1].startMs) warnings += "第 ${index + 1} 条时间早于上一条，已按时间排序"
            if (cue.endMs != null && cue.endMs <= cue.startMs) {
                warnings += "第 ${index + 1} 条结束时间不晚于开始时间，已跳过"
            } else if (durationMs != null && durationMs > 0 && cue.startMs >= durationMs) {
                if (cue.text.isNotBlank() || cue.startMs > durationMs) warnings += "第 ${index + 1} 条超出音频时长，已跳过"
            } else if (cue.text.isNotBlank() || extension.lowercase(Locale.ROOT) in listOf("txt", "lrc")) valid += cue
        }
        val sorted = valid.sortedBy { it.startMs }
        // Multiple LRC lines with identical timestamps are shown together.
        val combined = sorted.groupBy { it.startMs to it.endMs }.map { (_, group) ->
            group.first().copy(text = group.joinToString("\n") { it.text })
        }.sortedBy { it.startMs }
        val cues = combined.mapIndexed { index, cue ->
            val end = cue.endMs ?: combined.drop(index + 1).firstOrNull { it.startMs > cue.startMs }?.startMs ?: durationMs?.takeIf { it > cue.startMs }
            cue.copy(endMs = if (end != null && durationMs != null && durationMs > 0) minOf(end, durationMs) else end)
        }
        if (raw.isEmpty() && warnings.isEmpty() && extension.lowercase() !in listOf("txt", "lrc")) warnings += "没有找到可用的字幕时间轴"
        val visible = cues.filter { it.text.isNotBlank() }
        return SubtitleDocument(visible, if (visible.isEmpty()) input.trim() else "", warnings)
    }

    private fun parseLines(input: String, warnings: MutableList<String>): List<Cue> {
        val cues = mutableListOf<Cue>()
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        input.lines().forEachIndexed { lineIndex, original ->
            val line = original.trim()
            if (line.isBlank() || Regex("^\\[(ar|ti|al|by|offset|length|re):", RegexOption.IGNORE_CASE).containsMatchIn(line)) return@forEachIndexed
            val tags = bracket.findAll(line).toList()
            val firstTag = tags.firstOrNull()
            if (firstTag != null && firstTag.range.first == 0) {
                val content = line.substring(tags.last().range.last + 1).trim()
                val wordRegex = Regex("<($TIME)>([^<]*)")
                val words = wordRegex.findAll(content).mapNotNull { match ->
                    time(match.groupValues[1])?.let { WordCue((it + offset).coerceAtLeast(0), match.groupValues[2]) }
                }.toList()
                val clean = content.replace(Regex("<$TIME>"), "")
                tags.forEach { tag ->
                    val start = time(tag.groupValues[1])
                    if (start == null) warnings += "第 ${lineIndex + 1} 行时间无效"
                    else cues += Cue((start + offset).coerceAtLeast(0), null, clean, words)
                }
            } else {
                val match = Regex("^[【\\[(]?\\s*($TIME)\\s*[】\\])]?\\s*[:：|｜-]?\\s*(.*)$").find(line)
                if (match != null) {
                    val start = time(match.groupValues[1])
                    val content = match.groupValues[2].removeSuffix("】").trim()
                    if (start == null) warnings += "第 ${lineIndex + 1} 行时间无效"
                    else cues += Cue((start + offset).coerceAtLeast(0), null, content)
                } else if (Regex("^[【\\[(]?\\d+[:：]").containsMatchIn(line)) {
                    warnings += "第 ${lineIndex + 1} 行时间格式无法识别"
                } else if (cues.isNotEmpty()) {
                    val last = cues.last()
                    cues[cues.lastIndex] = last.copy(text = last.text + "\n" + line)
                }
            }
        }
        return cues
    }

    private fun parseBlocks(input: String, warnings: MutableList<String>): List<Cue> {
        val lines = input.lines()
        val result = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line == "NOTE" || line.startsWith("NOTE ") || line == "STYLE" || line == "REGION") {
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }
            val match = arrow.find(line)
            if (match != null) {
                val start = time(match.groupValues[1])
                val end = time(match.groupValues[2])
                val number = i + 1
                val content = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    // Some SRT-style TXT files omit blank lines between numbered cues.
                    if (arrow.containsMatchIn(lines[i])) break
                    if (lines[i].trim().matches(Regex("\\d+")) && lines.getOrNull(i + 1)?.let { arrow.containsMatchIn(it) } == true) break
                    content += lines[i++]
                }
                if (start == null || end == null) warnings += "第 $number 行时间无效"
                else result += Cue(start, end, cleanMarkup(content.joinToString("\n")))
            } else {
                if (line.contains("-->")) warnings += "第 ${i + 1} 行时间格式无法识别"
                i++
            }
        }
        return result
    }

    private fun parseAss(input: String, warnings: MutableList<String>): List<Cue> {
        val result = mutableListOf<Cue>()
        var inEvents = false
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        input.lines().forEachIndexed { index, line ->
            if (line.startsWith("[")) inEvents = line.trim().equals("[Events]", true)
            if (!inEvents) return@forEachIndexed
            if (line.startsWith("Format:", true)) fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            if (line.startsWith("Dialogue:", true)) {
                val values = line.substringAfter(':').trim().split(',', limit = fields.size)
                fun value(name: String): String = values.getOrNull(fields.indexOf(name)).orEmpty().trim()
                val start = time(value("start")); val end = time(value("end"))
                if (start == null || end == null) warnings += "第 ${index + 1} 行 ASS 时间无效"
                else result += Cue(start, end, value("text").replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
            }
        }
        return result
    }

    private fun cleanMarkup(text: String) = text.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    fun vtt(document: SubtitleDocument, durationMs: Long): String {
        require(document.timed) { "普通 TXT 没有时间轴，无法导出同步字幕" }
        require(durationMs > 0) { "请先加载音频时长，再导出字幕" }
        return buildString {
            append("WEBVTT\n\n")
            document.cues.forEach { cue ->
                val end = minOf(cue.endMs ?: durationMs, durationMs)
                if (end > cue.startMs) {
                    append(format(cue.startMs)).append(" --> ").append(format(end)).append('\n')
                    // Escape VTT markup so imported TXT remains literal text.
                    append(cue.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n\n", "\n"))
                    append("\n\n")
                }
            }
        }
    }

    fun lrc(document: SubtitleDocument, durationMs: Long): String {
        require(document.timed) { "普通 TXT 没有时间轴，无法导出同步字幕" }
        require(durationMs > 0) { "请先加载音频时长，再导出字幕" }
        val times = (document.cues.flatMap { listOfNotNull(it.startMs, it.endMs) } + durationMs)
            .filter { it in 0..durationMs }.distinct().sorted()
        return times.joinToString("\n", postfix = "\n") { ms ->
            val text = document.at(ms).joinToString(" ") { document.cues[it].text.replace("\n", " ") }
            String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", ms / 60000, ms / 1000 % 60, ms % 1000, text)
        }
    }

    fun format(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", value / 3_600_000, value / 60_000 % 60, value / 1000 % 60, value % 1000)
    }
}

object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val parts = Regex("\\d+|\\D+")
        val aa = parts.findAll(a.lowercase()).map { it.value }.toList()
        val bb = parts.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y)
            if (result != 0) return result
        }
        return aa.size.compareTo(bb.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
