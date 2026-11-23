package com.clipstudio.app

import java.net.URI

/** Identifies YouTube watch-page links so they are never mistaken for direct media files. */
object YouTubeLinkClassifier {
    private val allowedHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "music.youtube.com", "youtu.be"
    )

    fun isYouTubeUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return false
        if (uri.userInfo != null) return false
        return uri.host?.lowercase()?.let { it in allowedHosts } == true
    }

    fun parseVideoId(input: String): String {
        val raw = input.trim()
        val id = if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            raw
        } else {
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: throw IllegalArgumentException("Enter a valid YouTube URL or video ID.")
            require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
                "Use a valid YouTube http(s) URL."
            }
            val host = uri.host?.lowercase().orEmpty()
            require(host == "youtube.com" || host == "www.youtube.com" ||
                host == "m.youtube.com" || host == "music.youtube.com" || host == "youtu.be") {
                "Only YouTube video links are supported."
            }
            val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
            when {
                host == "youtu.be" -> pathSegments.firstOrNull().orEmpty()
                uri.path == "/watch" -> uri.rawQuery.orEmpty()
                    .split('&')
                    .firstOrNull { it.substringBefore('=') == "v" }
                    ?.substringAfter('=', "")
                    ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
                    .orEmpty()
                pathSegments.firstOrNull() in setOf("live", "embed", "shorts") ->
                    pathSegments.getOrNull(1).orEmpty()
                else -> ""
            }
        }
        require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            "Could not find a valid 11-character YouTube video ID."
        }
        return id
    }
}
