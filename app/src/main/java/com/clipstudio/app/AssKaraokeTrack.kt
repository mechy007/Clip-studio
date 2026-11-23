package com.clipstudio.app

import java.text.Bidi

data class TimedWord(val startMs: Long, val endMs: Long, val word: String)

internal data class CaptionFrame(
    val startMs: Long,
    val endMs: Long,
    val words: List<String>,
    val activeIndex: Int = -1
)

/** Caption timeline shared by the rendered Android preview and MP4 export. */
object AssKaraokeTrack {
    internal const val POP_DURATION_MS = 120L

    internal fun frames(cues: List<CaptionCue>): List<CaptionFrame> = buildList {
        for (cue in cues) {
            val words = (cue.words.ifEmpty { estimateWordTiming(cue) }).sortedBy { it.startMs }
            if (words.isEmpty()) {
                add(CaptionFrame(cue.startMs, cue.endMs, listOf(cue.text)))
                continue
            }
            var offset = 0
            while (offset < words.size) {
                var limit = offset + 1
                var length = words[offset].word.length
                while (limit < words.size && limit - offset < 3) {
                    val next = words[limit]
                    if (next.startMs - words[limit - 1].endMs > 350L ||
                        length + next.word.length + 1 > 24) break
                    length += next.word.length + 1
                    limit++
                }
                val group = words.subList(offset, limit)
                val text = group.map { it.word }
                group.forEachIndexed { index, word ->
                    val nextStart = words.getOrNull(offset + index + 1)?.startMs ?: cue.endMs
                    val end = minOf(word.endMs, nextStart)
                    if (end > word.startMs) add(CaptionFrame(word.startMs, end, text, index))
                    if (index < group.lastIndex && nextStart > end) {
                        add(CaptionFrame(end, nextStart, text))
                    }
                }
                offset = limit
            }
        }
    }.sortedBy { it.startMs }

    private fun estimateWordTiming(cue: CaptionCue): List<TimedWord> {
        val words = cue.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val duration = cue.endMs - cue.startMs
        if (words.isEmpty() || duration < words.size * 35L) return emptyList()
        return words.mapIndexed { index, word ->
            TimedWord(cue.startMs + duration * index / words.size,
                cue.startMs + duration * (index + 1) / words.size, word)
        }
    }

    fun render(
        cues: List<CaptionCue>, preset: CaptionPreset, width: Int, height: Int,
        position: CaptionPosition = CaptionPosition.LOWER, sizePercent: Int = 100
    ): String = buildString {
        val size = (minOf(width, height) * 0.055 * sizePercent.coerceIn(60, 150) / 100).toInt().coerceAtLeast(12)
        val outline = (size / if (preset == CaptionPreset.CLEAN) 18 else 12).coerceAtLeast(1)
        val border = if (preset == CaptionPreset.BOXED) 3 else 1
        val bold = if (preset == CaptionPreset.CLEAN) 0 else -1
        val margin = when (position) {
            CaptionPosition.LOWER -> (height * if (height > width) 0.20 else 0.10).toInt()
            CaptionPosition.CENTER -> 0
            CaptionPosition.UPPER -> height / 10
        }
        val baseColor = if (preset == CaptionPreset.BOUNCE) "&H0088FF00" else "&H00FFFFFF"
        val horizontal = (width * 0.08).toInt()
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine("PlayResX: $width")
        appendLine("PlayResY: $height")
        appendLine("WrapStyle: 0")
        appendLine("ScaledBorderAndShadow: yes")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        appendLine("Style: Default,Roboto,$size,$baseColor,$baseColor,&H00000000,&H80000000,$bold,0,0,0,100,100,0,0,$border,$outline,1,${position.alignment},$horizontal,$horizontal,$margin,1")
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        val timeline = if (preset.animated) frames(cues) else cues.map {
            CaptionFrame(it.startMs, it.endMs, listOf(it.text))
        }
        for (frame in timeline) {
            if (frame.endMs / 10 <= frame.startMs / 10) continue
            val wipe = preset == CaptionPreset.WIPE && frame.activeIndex >= 0 &&
                frame.words.none { word ->
                    val chars = word.toCharArray()
                    Bidi.requiresBidi(chars, 0, chars.size)
                }
            val text = frame.words.mapIndexed { index, word ->
                val safe = word.replace('\\', '＼').replace('{', '｛').replace('}', '｝')
                    .replace("\r", "").replace("\n", "\\N")
                if (index != frame.activeIndex) {
                    if (wipe) "{\\k0}$safe" else safe
                } else {
                    val duration = frame.endMs - frame.startMs
                    val effect = when (preset) {
                        CaptionPreset.POP -> {
                            val transition = minOf(POP_DURATION_MS, duration)
                            "\\c&H6AFF57&\\fscx116\\fscy116\\t(0,$transition,\\fscx100\\fscy100)"
                        }
                        // Bounce and karaoke fill adapted from AI Video Captions (MIT, AutoShorts).
                        CaptionPreset.BOUNCE -> {
                            val settle = minOf(duration, 100L)
                            val peak = settle / 2
                            "\\t(0,$peak,\\fscx120\\fscy120)\\t($peak,$settle,\\fscx100\\fscy100)\\c&HFF00FF&"
                        }
                        CaptionPreset.WIPE -> {
                            val centiseconds = frame.endMs / 10 - frame.startMs / 10
                            if (wipe) "\\kf$centiseconds\\c&H1FD2FF&" else "\\c&H1FD2FF&"
                        }
                        else -> "\\c&H1FD2FF&"
                    }
                    "{$effect}$safe{\\r}"
                }
            }.joinToString(" ")
            appendLine("Dialogue: 0,${timestamp(frame.startMs)},${timestamp(frame.endMs)},Default,,0,0,0,,$text")
        }
    }

    private fun timestamp(ms: Long): String {
        val cs = ms / 10
        val minutes = ((cs / 6000) % 60).toString().padStart(2, '0')
        val seconds = ((cs / 100) % 60).toString().padStart(2, '0')
        return "${cs / 360000}:$minutes:$seconds.${(cs % 100).toString().padStart(2, '0')}"
    }
}
