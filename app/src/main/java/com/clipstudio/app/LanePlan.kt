package com.clipstudio.app

import java.io.File
import java.util.Locale

/** Set to false to always use the single-connection yt-dlp section downloader. */
internal const val PARALLEL_SECTION_COPY_ENABLED = true

/** Each lane covers at least this much of the part, and there are never more than LANE_MAX lanes. */
internal const val LANE_MIN_MS = 120_000L
internal const val LANE_MAX = 6

/** Seeking this far past a keyframe's display time still lands on that same keyframe. */
internal const val KEY_SEEK_PAD_MS = 3L

/** A boundary keyframe farther back than this makes the lane plan unreliable, so we fall back. */
internal const val KEY_MAX_DISTANCE_MS = 20_000L
internal const val LANE_MIN_SPAN_MS = 1_000L

/** First packet found when seeking a stream: decode time and display time, in ms of recording time. */
internal data class KeyPacket(val dtsMs: Long, val ptsMs: Long)

/**
 * One parallel download lane. [seekMs] is the shared -ss for every input, [durationMs] the -t,
 * and [spanMs] the exact decode-time length used as the concat duration (null for the last lane).
 */
internal data class LaneSpec(val seekMs: Long, val durationMs: Long, val spanMs: Long?)

internal fun laneCount(lengthMs: Long, maxLanes: Int = LANE_MAX): Int =
    (lengthMs / LANE_MIN_MS).coerceIn(1L, maxLanes.toLong()).toInt()

/** Nominal lane starts: index 0 is the part start, the rest split the part evenly. */
internal fun laneBoundaries(range: RecordingPartWindow, lanes: Int): List<Long> =
    List(lanes) { range.startMs + range.lengthMs * it / lanes }

/**
 * [keys] holds the keyframe found at or before each boundary. Lane N starts on keyframe N and ends where
 * keyframe N+1 begins, so the lanes join without gaps or repeated video. Returns null when the keyframes
 * look unusable, which makes the caller fall back to the single-connection downloader.
 */
internal fun planLanes(range: RecordingPartWindow, boundaries: List<Long>, keys: List<KeyPacket>): List<LaneSpec>? {
    if (keys.isEmpty() || keys.size != boundaries.size) return null
    for (index in keys.indices) {
        val behind = boundaries[index] - keys[index].ptsMs
        if (behind < -500L || behind > KEY_MAX_DISTANCE_MS) return null
        if (index > 0 && keys[index].dtsMs - keys[index - 1].dtsMs < LANE_MIN_SPAN_MS) return null
    }
    val last = keys.lastIndex
    return keys.indices.map { index ->
        val seek = if (index == 0) range.startMs else keys[index].ptsMs + KEY_SEEK_PAD_MS
        val end = if (index == last) range.endMs else keys[index + 1].dtsMs
        if (end - seek < LANE_MIN_SPAN_MS) return null
        LaneSpec(seek, end - seek, if (index == last) null else keys[index + 1].dtsMs - keys[index].dtsMs)
    }
}

private val TIME_BASE = Regex("""#tb 0: (\d+)/(\d+)""")

/** Reads the first packet line of FFmpeg framecrc output. Columns: stream, dts, pts, duration, size, hash. */
internal fun parseFirstPacket(text: String): KeyPacket? {
    var num = 1L
    var den = 1000L
    for (line in text.lineSequence()) {
        val base = TIME_BASE.find(line)
        if (base != null) {
            num = base.groupValues[1].toLongOrNull() ?: return null
            den = base.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            continue
        }
        if (line.isBlank() || line.startsWith("#")) continue
        val fields = line.split(',').map { it.trim() }
        if (fields.size < 5 || fields[0] != "0") continue
        val dts = fields[1].toLongOrNull() ?: continue
        val pts = fields[2].toLongOrNull() ?: continue
        return KeyPacket(Math.floorDiv(dts * 1000L * num, den), Math.floorDiv(pts * 1000L * num, den))
    }
    return null
}

internal fun laneSeconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

/** FFmpeg concat list. Every lane except the last carries its exact length so timestamps never drift. */
internal fun concatManifest(files: List<File>, specs: List<LaneSpec>): String = buildString {
    appendLine("ffconcat version 1.0")
    files.forEachIndexed { index, file ->
        appendLine("file '" + file.absolutePath.replace("'", "'\\''") + "'")
        specs[index].spanMs?.let { appendLine("duration " + laneSeconds(it)) }
    }
}

private val FORWARDED_HEADERS = setOf("user-agent", "accept-language", "referer", "origin")

/** The -headers value FFmpeg expects: each header ends with CRLF. Only headers a media request needs are kept. */
internal fun headerBlock(headers: Map<String, String>): String =
    headers.entries.filter { it.key.lowercase(Locale.US) in FORWARDED_HEADERS }
        .joinToString("") { it.key + ": " + it.value + "\r\n" }
