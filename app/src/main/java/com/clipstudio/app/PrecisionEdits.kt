package com.clipstudio.app

import java.util.Locale

internal enum class VideoEnhancement(val label: String, val filter: String) {
    OFF("Off", ""),
    CLEAN("Clean noise", "hqdn3d=1.4:1.1:2.0:1.5"),
    DETAIL("Sharpen detail", "hqdn3d=1.0:0.8:1.4:1.1,unsharp=lx=5:ly=5:la=0.38:ca=0")
}

internal data class ProjectFraming(val layout: Layout, val camera: Crop, val gameplay: Crop)

internal fun editTime(ms: Long): String = "${ms / 60_000}:" +
    String.format(Locale.US, "%02d.%03d", ms / 1000 % 60, ms % 1000)

internal fun parseEditTime(text: String): Long? {
    val fields = text.trim().split('.')
    if (fields.size > 2 || fields[0].isBlank()) return null
    val seconds = recordingStartMs(fields[0]) ?: return null
    val fraction = fields.getOrNull(1) ?: return seconds
    if (fraction.length !in 1..3 || fraction.any { it !in '0'..'9' }) return null
    val millis = fraction.padEnd(3, '0').toLong()
    return if (seconds <= Long.MAX_VALUE - millis) seconds + millis else null
}

internal fun splitTranscript(row: TranscriptSegment, at: Long): List<TranscriptSegment> {
    if (at <= row.startMs || at >= row.endMs) return listOf(row)
    val words = row.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < 2) return listOf(row)
    val timed = row.words.takeIf { it.size == words.size }
    val boundary = if (timed != null) {
        (1 until words.size).minBy { kotlin.math.abs(timed[it].startMs - at) }
    } else {
        (((at - row.startMs).toDouble() / (row.endMs - row.startMs)) * words.size).toInt().coerceIn(1, words.lastIndex)
    }
    val split = timed?.get(boundary)?.startMs?.takeIf { it > row.startMs && it < row.endMs } ?: at
    return listOf(
        TranscriptSegment(row.startMs, split, words.take(boundary).joinToString(" "), timed.orEmpty().take(boundary)),
        TranscriptSegment(split, row.endMs, words.drop(boundary).joinToString(" "), timed.orEmpty().drop(boundary))
    )
}

internal fun overlayCaptions(base: List<CaptionCue>, overlays: List<CaptionCue>, start: Long, end: Long): List<CaptionCue> {
    val custom = overlays.mapNotNull { cue ->
        val first = maxOf(cue.startMs, start)
        val last = minOf(cue.endMs, end)
        if (last <= first || cue.text.isBlank()) null else cue.copy(startMs = first - start, endMs = last - start)
    }
    return custom.fold(base) { current, overlay ->
        current.flatMap { cue ->
            if (overlay.startMs >= cue.endMs || overlay.endMs <= cue.startMs) listOf(cue)
            else listOfNotNull(captionSlice(cue, cue.startMs, overlay.startMs),
                captionSlice(cue, overlay.endMs, cue.endMs))
        } + overlay
    }.sortedBy { it.startMs }
}


private fun captionSlice(cue: CaptionCue, start: Long, end: Long): CaptionCue? {
    if (end <= start) return null
    val words = cue.words.mapNotNull { word ->
        val first = maxOf(start, word.startMs)
        val last = minOf(end, word.endMs)
        if (last <= first) null else word.copy(startMs = first, endMs = last)
    }
    if (cue.words.isNotEmpty() && words.isEmpty()) return null
    val text = if (words.isEmpty()) cue.text else words.joinToString(" ") { it.word }
    return cue.copy(startMs = start, endMs = end, text = text, words = words)
}
