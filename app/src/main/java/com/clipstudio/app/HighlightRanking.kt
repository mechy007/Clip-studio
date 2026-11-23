package com.clipstudio.app

internal data class VisualSample(val timeMs: Long, val change: Float)
internal data class Highlight(val startMs: Long, val endMs: Long, val score: Int, val reason: String)

internal fun rankHighlights(
    durationMs: Long,
    energy: List<AudioEnergyWindow>,
    reactions: List<AudioEventWindow>,
    visual: List<VisualSample>,
    targetMs: Long = 24_000
): List<Highlight> {
    if (durationMs < 2_000L) return emptyList()
    val clipDuration = targetMs.coerceIn(2_000, durationMs)
    val reference = AudioSignalScorer.reference(energy)
    val typical = energy.map { it.rms }.sorted().let { it.getOrNull(it.size / 2) } ?: 0f
    val motionSamples = visual.map { it.change }.sorted()
    val typicalMotion = if (motionSamples.size >= 5) motionSamples[motionSamples.size * 2 / 5] else 0f
    val audioBySecond = energy.associateBy { it.startMs / 1_000 }
    val reactionBySecond = reactions.associateBy { it.startMs / 1_000 }
    val visualBySecond = visual.associateBy { it.timeMs / 1_000 }
    val seconds = (audioBySecond.keys + reactionBySecond.keys + visualBySecond.keys).distinct()
    val candidates = seconds.mapNotNull { second ->
        val time = (second * 1_000L).coerceIn(0, durationMs - 1)
        val loudness = if (reference == null) 0f else {
            val rms = audioBySecond[second]?.rms ?: 0f
            ((rms - typical * 1.08f) / reference.rms).coerceIn(0f, 1f)
        }
        val reaction = reactionBySecond[second]
        val laughter = reaction?.laughter ?: 0f
        val shout = reaction?.shouting ?: 0f
        val cheer = reaction?.cheering ?: 0f
        val reactionStrength = maxOf(laughter, shout, cheer)
        val difference = visualBySecond[second]?.change ?: 0f
        val motion = ((difference - maxOf(0.035f, typicalMotion * 1.12f)) * 4).coerceIn(0f, 1f)
        val main = maxOf(reactionStrength, loudness * .85f, motion * .9f)
        if (main < .12f) return@mapNotNull null
        val score = ((main * .72f + (reactionStrength + loudness + motion) * .093f) * 100)
            .toInt().coerceIn(0, 100)
        val reason = when {
            reactionStrength >= .24f && reactionStrength == main -> when (reactionStrength) {
                laughter -> "Laughter detected"
                shout -> "Shouting detected"
                else -> "Cheering detected"
            }
            motion * .9f >= loudness * .85f -> "Sudden visual activity"
            else -> "Audio spike"
        }
        val start = (time - clipDuration / 3).coerceIn(0, durationMs - clipDuration)
        Highlight(start, start + clipDuration, score, reason)
    }
    val selected = mutableListOf<Highlight>()
    for (candidate in candidates.sortedWith(compareByDescending<Highlight> { it.score }.thenBy { it.startMs })) {
        if (selected.size == 8) break
        if (selected.none { minOf(it.endMs, candidate.endMs) - maxOf(it.startMs, candidate.startMs) > clipDuration / 3 }) {
            selected += candidate
        }
    }
    return selected
}
