package com.asmr.player

data class FloatingFrame(val index: Int, val previous: String, val current: String, val next: String)

object FloatingFrames {
    fun at(document: SubtitleDocument, position: Long): FloatingFrame {
        val cues = document.cues
        val active = document.at(position)
        val index = active.firstOrNull()
        if (index != null) return FloatingFrame(index,
            cues.getOrNull(index - 1)?.text.orEmpty(),
            active.joinToString("\n") { cues[it].text },
            cues.getOrNull(active.last() + 1)?.text.orEmpty())
        val upcoming = cues.indexOfFirst { it.startMs > position }.let { if (it < 0) cues.size else it }
        return FloatingFrame(-1, cues.getOrNull(upcoming - 1)?.text.orEmpty(), "", cues.getOrNull(upcoming)?.text.orEmpty())
    }
}
