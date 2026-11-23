package com.clipstudio.app

internal data class RecordingScanPart(val range: RecordingPartWindow, val score: Int? = null, val samples: Int = 0)

internal fun recordingScanRanges(startMs: Long, endMs: Long): List<RecordingPartWindow> =
    generateSequence(nextRecordingPart(startMs, endMs)) { nextRecordingPart(it.endMs, endMs) }.toList()

internal fun recordingSampleRanges(part: RecordingPartWindow): List<RecordingPartWindow> {
    val length = minOf(5_000, part.lengthMs)
    val available = part.lengthMs - length
    return listOf(15L, 50L, 85L).map { offset ->
        val start = part.startMs + available * offset / 100
        RecordingPartWindow(start, start + length)
    }.distinct()
}

internal fun scoreRecordingScan(
    parts: List<RecordingPartWindow>, samples: List<List<AudioEnergyWindow>>
): List<RecordingScanPart> {
    val reference = AudioSignalScorer.reference(samples.flatten())
    return parts.zip(samples).map { (part, measured) ->
        val score = reference?.takeIf { measured.isNotEmpty() }?.let {
            AudioSignalScorer.score(part.startMs, part.endMs, measured, it.rms, it.peak)
        }
        RecordingScanPart(part, score, measured.size)
    }
}
