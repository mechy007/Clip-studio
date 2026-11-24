package com.clipstudio.app

import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
        copyTracks: Boolean = false,
        onSpeed: (Long) -> Unit = {},
        onBytesFetched: (Long) -> Unit = {},
        progress: (Long) -> Unit
    ): File {
        val processId = UUID.randomUUID().toString()
        try {
            return runInterruptible(Dispatchers.IO) {
                val videoId = YouTubeLinkClassifier.parseVideoId(link)
                val bounds = String.format(Locale.US, "*%.3f-%.3f", range.startMs / 1000.0, range.endMs / 1000.0)
                val target = File(directory, "section.mp4")
                prepare(context)
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                    .addOption("--no-playlist")
                    .addOption("--match-filter", "!is_live & !is_upcoming")
                    .addOption("--socket-timeout", 30)
                    .addOption("--retries", 3)
                    .addOption("--fragment-retries", 8)
                    .addOption("-f", format)
                    .addOption("--download-sections", bounds)
                    .addOption("--merge-output-format", "mp4")
                    .addOption("-o", target.absolutePath)
                // --download-sections is handled by FFmpeg on this path, so yt-dlp's
                // fragment-concurrency option does not increase section throughput.
                if (copyTracks) {
                    request.addOption("--no-force-keyframes-at-cuts")
                        .addOption("--downloader-args", "ffmpeg_i:-rw_timeout 15000000")
                        .addOption("--downloader-args", "ffmpeg_o:-avoid_negative_ts disabled -use_editlist 1")
                } else {
                    request.addOption("--force-keyframes-at-cuts")
                }
                YoutubeDL.execute(request, processId, false) { percent, _, line ->
                    if (percent >= 0) progress((range.lengthMs * percent.toDouble().coerceIn(0.0, 95.0) / 100).toLong())
                    parseDownloadSpeedBytes(line)?.let(onSpeed)
                    val partial = File(target.absolutePath + ".part")
                    onBytesFetched(maxOf(target.length(), partial.length()))
                }
                onBytesFetched(target.length())
                target
            }
        } catch (cancelled: CancellationException) {
            runCatching { YoutubeDL.destroyProcessById(processId) }
            throw cancelled
        }
    }

    suspend fun download(
        context: Context,
        url: String,
        height: Int,
        onSpeed: (Long) -> Unit = {},
        onProgress: (Int) -> Unit
    ): Uri {
        val activeProcessId = AtomicReference<String?>(null)
        return try {
            runInterruptible(Dispatchers.IO) {
                require(YouTubeLinkClassifier.isYouTubeUrl(url))
                val videoId = YouTubeLinkClassifier.parseVideoId(url)
                require(height in setOf(720, 1080, 2160))
                val imports = File(context.filesDir, "imports")
                check(imports.mkdirs() || imports.isDirectory) { "Could not create the download directory." }
                val id = UUID.randomUUID().toString()
                val staging = File(imports, ".$id")
                check(staging.mkdirs()) { "Could not create the download directory." }
                try {
                    prepare(context)
                    fun request(preferDash: Boolean): YoutubeDLRequest {
                        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                            .addOption("--no-playlist")
                            .addOption("--match-filter", "!is_live & !is_upcoming")
                            .addOption("--socket-timeout", 30)
                            .addOption("--concurrent-fragments", 4)
                            .addOption("--buffer-size", "64K")
                            .addOption("--retries", 5)
                            .addOption("--fragment-retries", 10)
                            .addOption("--file-access-retries", 3)
                            .addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/best")
                            .addOption("--merge-output-format", "mp4")
                            .addOption("-o", File(staging, "video.%(ext)s").absolutePath)
                        if (preferDash) request.addOption("--extractor-args", "youtube:formats=dashy")
                        return request
                    }

                    fun executeAttempt(preferDash: Boolean) {
                        val processId = UUID.randomUUID().toString()
                        activeProcessId.set(processId)
                        YoutubeDL.execute(request(preferDash), processId, false) { percent, _, line ->
                            if (percent >= 0) onProgress(percent.toInt().coerceIn(0, 99))
                            parseDownloadSpeedBytes(line)?.let(onSpeed)
                        }
                        activeProcessId.compareAndSet(processId, null)
                    }

                    fun clearStaging() {
                        staging.listFiles()?.forEach { it.deleteRecursively() }
                    }

                    val rendered = File(staging, "video.mp4")
                    var firstFailure: Exception? = null
                    var forbiddenFailure: Exception? = null
                    var completed = false
                    for ((attempt, preferDash) in listOf(true, false).withIndex()) {
                        if (attempt > 0) clearStaging()
                        try {
                            executeAttempt(preferDash)
                            if (YouTubeDownloadValidation.isValidMp4(rendered)) {
                                completed = true
                                break
                            }
                            firstFailure = IllegalStateException(
                                if (preferDash) "DASH download did not produce a valid MP4."
                                else "The downloader did not produce a valid MP4."
                            )
                        } catch (interrupted: InterruptedException) {
                            throw interrupted
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (YouTubeDownloadValidation.isForbidden(error.message.orEmpty())) {
                                forbiddenFailure = error
                            }
                            if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
                            android.util.Log.w(
                                "ClipStudioYouTube",
                                if (preferDash) "DASH import failed; trying the compatible format fallback"
                                else "Compatible format fallback failed",
                                error
                            )
                        }
                    }
                    if (!completed) {
                        val failure = forbiddenFailure ?: firstFailure ?: IllegalStateException("YouTube download failed.")
                        if (forbiddenFailure != null || YouTubeDownloadValidation.isForbidden(failure.message.orEmpty())) {
                            throw IllegalStateException(
                                "YouTube refused this download (HTTP 403). Try a different permitted recording, or import a local MP4. The bundled extractor may need updating.",
                                failure
                            )
                        }
                        throw failure
                    }
                    val destination = File(imports, "youtube-$videoId-$id.mp4")
                    check(rendered.renameTo(destination)) { "Could not save the downloaded video." }
                    Uri.fromFile(destination).also { onProgress(100) }
                } finally {
                    staging.deleteRecursively()
                }
            }
        } catch (cancelled: CancellationException) {
            activeProcessId.getAndSet(null)?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
            throw cancelled
        }
    }
}
