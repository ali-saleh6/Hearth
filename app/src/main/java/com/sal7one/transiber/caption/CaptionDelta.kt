package com.sal7one.transiber.caption

/**
 * Subtitles that scroll up repeat the previous line on screen, and OCR of
 * one subtitle can differ by a dash or a quote between frames. Speaking each
 * reading in full would say the same sentence twice, so only the words the
 * screen added since the last spoken reading are passed on.
 */
internal object CaptionDelta {
    /** Fewer shared words than this is coincidence ("the", "and"), not a scroll. */
    private const val MIN_OVERLAP = 2

    /** The part of [current] not already said in [previous], or null when nothing is new. */
    fun newPart(previous: String?, current: String): String? {
        val now = words(current)
        if (now.isEmpty()) return null
        val before = previous?.let(::words).orEmpty()
        if (before.isEmpty()) return current
        val nowKeys = now.map { it.key }
        val beforeKeys = before.map { it.key }
        // Same words, or a reading that is wholly inside the last one.
        if (contains(beforeKeys, nowKeys)) return null
        // The end of the last reading is the start of this one: a scroll.
        val overlap = (minOf(beforeKeys.size, nowKeys.size) downTo MIN_OVERLAP)
            .firstOrNull { size -> beforeKeys.takeLast(size) == nowKeys.take(size) } ?: 0
        if (overlap == 0) return current
        return now.drop(overlap).joinToString(" ") { it.text }.ifBlank { null }
    }

    private data class Word(val text: String, val key: String)

    /** Punctuation and case differ between OCR frames of the same subtitle. */
    private fun words(text: String): List<Word> = text.split(Regex("\\s+"))
        .map { Word(it, it.lowercase().filter(Char::isLetterOrDigit)) }
        .filter { it.key.isNotEmpty() }

    private fun contains(outer: List<String>, inner: List<String>): Boolean =
        inner.size <= outer.size && (0..outer.size - inner.size).any { outer.subList(it, it + inner.size) == inner }
}
