package com.clipstudio.app

internal data class RecordingScanPart(
    val range: RecordingPartWindow, val score: Int? = null, val samples: Int = 0,
    val measurements: List<AudioEnergyWindow> = emptyList()
) {
    val needsSampling: Boolean get() = measurements.size < recordingSampleRanges(range).size
}

internal fun recordingScanRanges(startMs: Long, endMs: Long): List<RecordingPartWindow> =
    generateSequence(nextRecordingPart(startMs, endMs)) { nextRecordingPart(it.endMs, endMs) }.toList()

internal fun recordingSampleRanges(part: RecordingPartWindow): List<RecordingPartWindow> {
    val length = minOf(5_000, part.lengthMs)
    val available = part.lengthMs - length
    return listOf(8L, 29L, 50L, 71L, 92L).map { offset ->
        val start = part.startMs + available * offset / 100
        RecordingPartWindow(start, start + length)
    }.distinct()
}

internal fun scoreRecordingScan(
    parts: List<RecordingPartWindow>, samples: List<List<AudioEnergyWindow>>
): List<RecordingScanPart> {
    // A percentile can pick the quietest sample when only a few were available.
    val measuredWindows = samples.flatten()
    val peakRms = measuredWindows.maxOfOrNull { it.rms } ?: 0f
    val peakAmplitude = measuredWindows.maxOfOrNull { it.peak } ?: 0f
    return parts.zip(samples).map { (part, measured) ->
        val score = if (measured.isNotEmpty() && peakRms > 0f && peakAmplitude > 0f) {
            AudioSignalScorer.score(part.startMs, part.endMs, measured, peakRms, peakAmplitude)
        } else null
        RecordingScanPart(part, score, measured.size, measured)
    }
}
