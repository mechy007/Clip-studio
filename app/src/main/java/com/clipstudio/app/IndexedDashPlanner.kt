package com.clipstudio.app

import org.json.JSONObject
import java.net.URI

/**
 * A timestamp-indexed view of one yt-dlp DASH representation.
 * Fragment durations are relative to the beginning of the representation.
 */
internal data class IndexedDashFragment(
    val index: Int,
    val url: String,
    val startMs: Long,
    val endMs: Long,
    val initialization: Boolean
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

internal data class IndexedDashTrack(
    val formatId: String,
    val fragments: List<IndexedDashFragment>,
    val headers: Map<String, String>
) {
    val initialization: IndexedDashFragment
        get() = fragments.first { it.initialization }

    fun mediaFor(startMs: Long, endMs: Long): List<IndexedDashFragment> {
        require(startMs >= 0L && endMs > startMs)
        return fragments.filter { !it.initialization && it.startMs < endMs && it.endMs > startMs }
    }
}

internal object IndexedDashPlanner {
    fun parse(format: JSONObject): IndexedDashTrack? {
        val formatId = format.optString("format_id").takeIf(String::isNotBlank) ?: return null
        val rawFragments = format.optJSONArray("fragments") ?: return null
        if (rawFragments.length() < 2) return null

        val base = format.optString("fragment_base_url").takeIf(String::isNotBlank)
            ?: format.optString("url").takeIf(String::isNotBlank)
            ?: return null
        val parsed = ArrayList<IndexedDashFragment>(rawFragments.length())
        var timelineMs = 0L
        var initializationSeen = false

        for (index in 0 until rawFragments.length()) {
            val fragment = rawFragments.optJSONObject(index) ?: return null
            val rawUrl = fragment.optString("url").takeIf(String::isNotBlank)
                ?: fragment.optString("path").takeIf(String::isNotBlank)
                ?: return null
            val resolved = runCatching { URI(base).resolve(rawUrl).toString() }.getOrNull()
                ?: return null
            val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null

            val durationSeconds = fragment.optDouble("duration", Double.NaN)
            val hasDuration = durationSeconds.isFinite() && durationSeconds > 0.0
            if (!hasDuration) {
                if (initializationSeen || parsed.isNotEmpty()) return null
                initializationSeen = true
                parsed += IndexedDashFragment(index, resolved, 0L, 0L, initialization = true)
            } else {
                val durationMs = (durationSeconds * 1_000.0).toLong()
                if (durationMs <= 0L) return null
                val end = timelineMs + durationMs
                parsed += IndexedDashFragment(index, resolved, timelineMs, end, initialization = false)
                timelineMs = end
            }
        }

        if (!initializationSeen || parsed.none { !it.initialization }) return null
        val headers = format.optJSONObject("http_headers")?.let { json ->
            json.keys().asSequence().associateWith { json.optString(it) }.filterValues(String::isNotBlank)
        }.orEmpty()
        return IndexedDashTrack(formatId, parsed, headers)
    }

    fun parseSelectedFormats(media: JSONObject): List<IndexedDashTrack>? {
        val selected = media.optJSONArray("requested_formats")
        val formats = if (selected != null && selected.length() > 0) {
            List(selected.length()) { selected.optJSONObject(it) ?: return null }
        } else listOf(media)
        val tracks = formats.map { parse(it) ?: return null }
        return tracks.takeIf { it.isNotEmpty() && it.size <= 2 }
    }
}
