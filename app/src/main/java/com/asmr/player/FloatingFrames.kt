package com.asmr.player

data class FloatingFrame(val index: Int, val previous: String, val current: String, val next: String, val active: Boolean = true)

class FloatingTimeline(document: SubtitleDocument) {
    private val cues = document.cues.map { it to FloatingText.compact(it.text) }.filter { it.second.isNotEmpty() }
    fun at(position: Long): FloatingFrame {
        if(cues.isEmpty()) return FloatingFrame(-1,"","", "")
        val active = cues.indices.filter { position >= cues[it].first.startMs && position < (cues[it].first.endMs ?: Long.MAX_VALUE) }
        // Keep the most recent sentence centered during a timestamp gap. Upcoming text
        // remains a preview and is only highlighted once its actual start time arrives.
        val index = active.firstOrNull() ?: cues.indexOfLast { it.first.startMs <= position }.coerceAtLeast(0)
        return FloatingFrame(index,
            cues.getOrNull(index - 1)?.second.orEmpty(),
            if(active.isEmpty()) cues[index].second else active.joinToString("\n") { cues[it].second },
            cues.getOrNull((active.lastOrNull() ?: index) + 1)?.second.orEmpty(),
            active.isNotEmpty())
    }
}

object FloatingFrames {
    fun at(document: SubtitleDocument, position: Long) = FloatingTimeline(document).at(position)
}

/** Display-only cleanup: retain the source document and its timing exactly as imported. */
object FloatingText {
    private val whitespace = Regex("""[\s\p{Z}\u200B\uFEFF]+""")
    fun compact(text: String): String {
        val words = text.split(whitespace).filter { it.isNotEmpty() }
        return buildString {
            words.forEach { word ->
                if (isNotEmpty() && !(eastAsian(last()) && eastAsian(word.first()))) append(' ')
                append(word)
            }
        }
    }
    private fun eastAsian(c: Char): Boolean = when(Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> c in '\u3000'..'\u303F' || c in '\uFF01'..'\uFF0F' || c in '\uFF1A'..'\uFF20' || c in '\uFF3B'..'\uFF40'
    }
}

object FloatingPalette {
    val choices = linkedMapOf("auto" to "自动", "white" to "白色", "black" to "黑色", "yellow" to "米黄", "green" to "浅绿", "blue" to "浅蓝", "pink" to "粉色")
    fun color(choice: String, dark: Boolean): Int = when(choice) {
        "white" -> 0xFFFFFFFF.toInt()
        "black" -> 0xFF000000.toInt()
        "yellow" -> 0xFFFFE6A3.toInt()
        "green" -> 0xFFB9E8C5.toInt()
        "blue" -> 0xFFB7D9FF.toInt()
        "pink" -> 0xFFFFC4DE.toInt()
        else -> if(dark) 0xFFFFFFFF.toInt() else 0xFF18231C.toInt()
    }
}
