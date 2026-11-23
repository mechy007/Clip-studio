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
