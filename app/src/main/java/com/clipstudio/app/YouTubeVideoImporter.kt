package com.clipstudio.app

import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.Locale
import java.util.UUID

object YouTubeVideoImporter {
    @Volatile private var initialized = false

    @Synchronized
    internal fun prepare(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        YoutubeDL.init(app)
        FFmpeg.init(app)
        try {
            YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
        } catch (error: Exception) {
            android.util.Log.w("ClipStudioYouTube", "Using the installed extractor", error)
        }
        initialized = true
    }

    internal suspend fun downloadSection(
        context: Context, link: String, format: String, range: RecordingPartWindow, directory: File,
        progress: (Long) -> Unit
    ): File = runInterruptible(Dispatchers.IO) {
        val videoId = YouTubeLinkClassifier.parseVideoId(link)
        val bounds = String.format(Locale.US, "*%.3f-%.3f", range.startMs / 1000.0, range.endMs / 1000.0)
        val target = File(directory, "section.mp4")
        prepare(context)
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            .addOption("--no-playlist")
            .addOption("--match-filter", "!is_live & !is_upcoming")
            .addOption("--socket-timeout", 30)
            .addOption("--retries", 2)
            .addOption("-f", format)
            .addOption("--download-sections", bounds)
            .addOption("--force-keyframes-at-cuts")
            .addOption("--merge-output-format", "mp4")
            .addOption("-o", target.absolutePath)
        YoutubeDL.execute(request, UUID.randomUUID().toString(), false) { percent, _, _ ->
            if (percent >= 0) progress((range.lengthMs * percent.toDouble().coerceIn(0.0, 95.0) / 100).toLong())
        }
        target
    }

    suspend fun download(context: Context, url: String, height: Int, onProgress: (Int) -> Unit): Uri =
        runInterruptible(Dispatchers.IO) {
            require(YouTubeLinkClassifier.isYouTubeUrl(url))
            val videoId = YouTubeLinkClassifier.parseVideoId(url)
            require(height in setOf(720, 1080, 2160))
            val imports = File(context.filesDir, "imports")
            val id = UUID.randomUUID().toString()
            val staging = File(imports, ".$id")
            check(staging.mkdirs()) { "Could not create the download directory." }
            try {
                prepare(context)
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                    .addOption("--no-playlist")
                    .addOption("--match-filter", "!is_live & !is_upcoming")
                    .addOption("--socket-timeout", 30)
                    .addOption("--retries", 3)
                    .addOption("--fragment-retries", 3)
                    .addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/best")
                    .addOption("--merge-output-format", "mp4")
                    .addOption("-o", File(staging, "video.%(ext)s").absolutePath)
                try {
                    YoutubeDL.execute(request, id, false) { progress, _, _ ->
                        if (progress >= 0) onProgress(progress.toInt().coerceIn(0, 99))
                    }
                } catch (error: YoutubeDLException) {
                    val detail = error.message.orEmpty()
                    if (YouTubeDownloadValidation.isForbidden(detail)) {
                        throw IllegalStateException(
                            "YouTube refused this download (HTTP 403). Try a different permitted recording, or import a local MP4. The bundled downloader may need updating.", error
                        )
                    }
                    throw error
                }
                val rendered = File(staging, "video.mp4")
                check(YouTubeDownloadValidation.isValidMp4(rendered)) {
                    "Download did not produce a valid MP4. Try a lower quality or import a local video."
                }
                val destination = File(imports, "youtube-$videoId-$id.mp4")
                check(rendered.renameTo(destination)) { "Could not save the downloaded video." }
                Uri.fromFile(destination).also { onProgress(100) }
            } finally {
                staging.deleteRecursively()
            }
        }
}
