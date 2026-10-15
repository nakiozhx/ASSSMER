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
