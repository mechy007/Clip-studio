package com.clipstudio.app

/** A timed subtitle cue using milliseconds on the source-video timeline. */
data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<TimedWord> = emptyList()
)

enum class CaptionPreset(val label: String, val animated: Boolean = false) {
    CLEAN("Clean"),
    BOLD("Bold"),
    KARAOKE("Karaoke", animated = true),
    POP("Pop", animated = true),
    BOXED("Boxed"),
    BOUNCE("Bounce", animated = true),
    WIPE("Karaoke Wipe", animated = true)
}

enum class CaptionPosition(val label: String, internal val alignment: Int) {
    LOWER("Lower", 2),
    CENTER("Center", 5),
    UPPER("Upper", 8)
}

/** Converts source-relative transcripts to clip-relative captions for render and SRT export. */
object CaptionTrack {
    fun fromTranscript(
        transcript: List<TranscriptSegment>,
        clipStartMs: Long,
        clipEndMs: Long,
        maxCharactersPerCue: Int = 84
    ): List<CaptionCue> {
        require(clipStartMs >= 0L) { "Clip start must not be negative." }
        require(clipEndMs > clipStartMs) { "Clip end must be after clip start." }
        require(maxCharactersPerCue >= 12) { "Caption cue limit is too small." }

        return transcript.asSequence()
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .mapNotNull { segment ->
                val start = maxOf(segment.startMs, clipStartMs)
                val end = minOf(segment.endMs, clipEndMs)
                if (end <= start) return@mapNotNull null
                val words = segment.words.mapNotNull { word ->
                    val a = maxOf(start, word.startMs)
                    val b = minOf(end, word.endMs)
                    if (b - a < 10L) null else TimedWord(a - clipStartMs, b - clipStartMs, word.word)
                }
                if (segment.words.isNotEmpty() && words.isEmpty()) return@mapNotNull null
                val text = if (words.isEmpty()) segment.text else words.joinToString(" ") { it.word }
                CaptionCue(start - clipStartMs, end - clipStartMs, wrapText(text.trim(), maxCharactersPerCue), words)
            }
            .sortedBy { it.startMs }
            .toList()
    }

    fun toSrt(cues: List<CaptionCue>): String = buildString {
        cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedBy { it.startMs }
            .forEachIndexed { index, cue ->
                append(index + 1).append('\n')
                append(timestamp(cue.startMs)).append(" --> ")
                    .append(timestamp(cue.endMs)).append('\n')
                append(cue.text.replace("\r", "").trim()).append("\n\n")
            }
    }

    private fun wrapText(raw: String, maxCharacters: Int): String {
        val words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        words.forEach { word ->
            if (line.isNotEmpty() && line.length + 1 + word.length > maxCharacters) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.joinToString("\n")
    }

    private fun timestamp(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
