package com.clipstudio.app

internal const val RECORDING_PART_MS = 30 * 60 * 1000L

internal data class RecordingPartWindow(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

internal fun nextRecordingPart(startMs: Long, durationMs: Long): RecordingPartWindow? {
    require(startMs >= 0 && durationMs > 0)
    if (startMs >= durationMs) return null
    return RecordingPartWindow(startMs, startMs + minOf(RECORDING_PART_MS, durationMs - startMs))
}

/** A blank field starts at zero. Accept seconds, MM:SS, or HH:MM:SS. */
internal fun recordingStartMs(text: String): Long? {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return 0L
    val fields = cleaned.split(':')
    if (fields.size !in 1..3 || fields.any { field -> field.isEmpty() || field.any { it !in '0'..'9' } }) return null
    var seconds = 0L
    for ((index, field) in fields.withIndex()) {
        val value = field.toLongOrNull() ?: return null
        if (index > 0 && value >= 60) return null
        if (seconds > (Long.MAX_VALUE - value) / 60) return null
        seconds = seconds * 60 + value
    }
    return seconds.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000)
}
