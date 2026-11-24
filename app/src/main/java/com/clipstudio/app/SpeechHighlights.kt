package com.clipstudio.app

import kotlin.math.roundToInt
import java.util.Locale

private val speechTerm = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]|[\p{L}\p{N}]+""")
private val soundCue = Regex("""^\s*(?:\[[^\]]+\]|\([^)]*\)|[♪♫]+)\s*$""")
private val reactionTerms = setOf("wow", "insane", "what", "wait", "finally", "clutch", "unbelievable", "seriously")

internal fun speechFirstHighlights(
    durationMs: Long,
    transcript: List<TranscriptSegment>,
    energy: List<AudioEnergyWindow>,
    reactions: List<AudioEventWindow>,
    visual: List<VisualSample>,
    mode: HighlightMode
): List<Highlight> {
    val fallback = rankHighlights(durationMs, energy, reactions, visual, mode = mode)
    if (durationMs < 2_000) return fallback
    val speech = transcript.sortedBy { it.startMs }.filter {
        it.startMs in 0 until durationMs && it.endMs > it.startMs &&
            !soundCue.matches(it.text) && speechTerm.findAll(it.text).count() >= 2
    }
    if (speech.isEmpty()) return fallback

    val groups = mutableListOf<MutableList<TranscriptSegment>>()
    for (segment in speech) {
        val group = groups.lastOrNull()
        if (group != null && segment.startMs - group.last().endMs <= 1_600 &&
            segment.endMs - group.first().startMs <= 22_000) {
            group += segment
        } else groups += mutableListOf(segment)
    }

    val candidates = groups.mapNotNull { group ->
        val first = group.first().startMs
        val last = group.maxOf { it.endMs }.coerceAtMost(durationMs)
        val spoken = last - first
        val quote = group.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ")
        val terms = speechTerm.findAll(quote.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (terms.size < 4 && quote.count(Char::isLetter) < 12 || spoken < 650) return@mapNotNull null

        val distinct = terms.distinct().size.toFloat() / terms.size
        val emphasis = (terms.count { it in reactionTerms } * .14f + quote.count { it == '!' || it == '?' } * .10f)
            .coerceAtMost(.55f)
        val coverage = (spoken / 14_000f).coerceAtMost(1f)
        val contextStart = (first - 2_500).coerceAtLeast(0)
        val contextEnd = (last + 3_000).coerceAtMost(durationMs)
        val clipLength = (contextEnd - contextStart).coerceIn(minOf(10_000L, durationMs), minOf(30_000L, durationMs))
        val start = contextStart.coerceAtMost(durationMs - clipLength)
        val end = start + clipLength
        val supporting = fallback.filter { it.startMs < end && it.endMs > start }.maxOfOrNull { it.score } ?: 0
        val bonus = when (mode) {
            HighlightMode.FUNNY -> emphasis * 18f + supporting * .08f
            HighlightMode.GAMEPLAY -> supporting * .18f
            HighlightMode.TALKING -> coverage * 15f
            HighlightMode.BALANCED -> supporting * .09f
        }
        val score = (64 + distinct * 6 + coverage * 9 + emphasis * 15 + bonus).roundToInt().coerceIn(64, 100)
        val excerpt = if (quote.length <= 150) quote else quote.take(147).substringBeforeLast(' ', quote.take(147)) + "…"
        Highlight(start, end, score, if (emphasis >= .30f) "Spoken reaction" else "Spoken moment", excerpt)
    }

    val selected = mutableListOf<Highlight>()
    fun add(candidate: Highlight) {
        val length = candidate.endMs - candidate.startMs
        if (selected.none {
                val overlap = minOf(it.endMs, candidate.endMs) - maxOf(it.startMs, candidate.startMs)
                overlap > minOf(length, it.endMs - it.startMs) / 3
            }) selected += candidate
    }

    val speechLimit = if (mode == HighlightMode.GAMEPLAY && fallback.isNotEmpty()) 5 else 8
    for (candidate in candidates.sortedByDescending { it.score }) {
        if (selected.size >= speechLimit) break
        add(candidate)
    }
    for (candidate in fallback) {
        if (selected.size >= 8) break
        add(if (candidates.isEmpty()) candidate else candidate.copy(score = candidate.score.coerceAtMost(63)))
    }
    return selected
}
