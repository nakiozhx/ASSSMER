package com.asmr.player

data class FloatingFrame(val index: Int, val previous: String, val current: String, val next: String)

object FloatingFrames {
    fun at(document: SubtitleDocument, position: Long): FloatingFrame {
        val cues = document.cues
        val active = document.at(position)
        val index = active.firstOrNull()
        if (index != null) return FloatingFrame(index,
            FloatingText.compact(cues.getOrNull(index - 1)?.text.orEmpty()),
            active.joinToString("\n") { FloatingText.compact(cues[it].text) },
            FloatingText.compact(cues.getOrNull(active.last() + 1)?.text.orEmpty()))
        val upcoming = cues.indexOfFirst { it.startMs > position }.let { if (it < 0) cues.size else it }
        return FloatingFrame(-1, FloatingText.compact(cues.getOrNull(upcoming - 1)?.text.orEmpty()), "", FloatingText.compact(cues.getOrNull(upcoming)?.text.orEmpty()))
    }
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
