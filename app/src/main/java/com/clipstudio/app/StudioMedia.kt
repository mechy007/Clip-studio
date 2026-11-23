package com.clipstudio.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

internal data class SourcePart(val uri: Uri, val offsetMs: Long, val durationMs: Long)

internal object StudioMedia {
    suspend fun duration(context: Context, source: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: error("This video could not be opened.")
        } finally {
            retriever.release()
        }
    }

    /** Publish only MP4s whose mux has completed. CSV rows are flushed by FFmpeg after closing each file. */
    suspend fun downloadInParts(
        context: Context,
        link: String,
        onPart: suspend (SourcePart) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val videoId = YouTubeLinkClassifier.parseVideoId(link)
        val source = runInterruptible {
            YoutubeDL.init(context)
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                .addOption("--no-playlist")
                .addOption("--match-filter", "!is_live & !is_upcoming")
                .addOption("--socket-timeout", 20)
                .addOption("-f", "b[protocol=https][ext=mp4][vcodec^=avc1][acodec^=mp4a][height<=1080]")
                .addOption("--get-url")
            YoutubeDL.execute(request).out.lineSequence()
                .map(String::trim).filter(String::isNotEmpty).singleOrNull()
        } ?: return@withContext false
        val remote = runCatching { URI(source) }.getOrNull()
        if (remote?.scheme != "https" || remote.host == null) return@withContext false

        val dir = File(context.filesDir, "imports/parts-${UUID.randomUUID()}")
        check(dir.mkdirs()) { "Could not create download storage." }
        val list = File(dir, "segments.csv")
        var count = 0
        suspend fun publishReady() {
            val csv = if (list.isFile) list.readText() else ""
            for (row in csv.substringBeforeLast('\n', "").lineSequence().drop(count)) {
                val entry = parseSegmentRow(row) ?: break
                val file = File(dir, entry.name)
                if (!YouTubeDownloadValidation.isValidMp4(file)) break
                val uri = Uri.fromFile(file)
                onPart(SourcePart(uri, entry.startMs, duration(context, uri)))
                count++
            }
        }
        try {
            val complete = coroutineScope {
                val transfer = async {
                    runFfmpeg(arrayOf(
                        "-nostdin", "-loglevel", "error", "-rw_timeout", "20000000",
                        "-i", source, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
                        "-f", "segment", "-segment_time", "40", "-reset_timestamps", "1",
                        "-segment_format_options", "movflags=+faststart",
                        "-segment_list", list.absolutePath, "-segment_list_type", "csv",
                        File(dir, "part-%05d.mp4").absolutePath
                    ))
                }
                while (!transfer.isCompleted) {
                    publishReady()
                    delay(400)
                }
                val success = transfer.await()
                publishReady()
                success
            }
            check(complete || count == 0) { "Download stopped. Complete sections are still available." }
            complete && count > 0
        } finally {
            if (count == 0) dir.deleteRecursively()
        }
    }

    suspend fun export(
        context: Context, part: SourcePart, startMs: Long, endMs: Long,
        layout: Layout, camera: Crop, gameplay: Crop
    ): Uri = withContext(Dispatchers.IO) {
        require(startMs in 0 until endMs && endMs <= part.durationMs)
        val output = File.createTempFile("studio-export-", ".mp4", context.cacheDir)
        val copy = if (part.uri.scheme == "file") null else
            File.createTempFile("studio-input-", ".media", context.cacheDir)
        try {
            val source = if (copy == null) requireNotNull(part.uri.path) else {
                context.contentResolver.openInputStream(part.uri)?.use { input ->
                    copy.outputStream().buffered().use { target -> input.copyTo(target) }
                } ?: error("Could not read the source video.")
                copy.absolutePath
            }
            val success = runFfmpeg(arrayOf(
                "-nostdin", "-loglevel", "error", "-y",
                "-ss", seconds(startMs), "-i", source, "-t", seconds(endMs - startMs),
                "-filter_complex", videoFilter(layout, camera, gameplay), "-map", "[out]", "-map", "0:a:0?",
                "-r", "30", "-threads", "2", "-c:v", "libopenh264", "-b:v", "4500k", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", output.absolutePath
            ))
            check(success && YouTubeDownloadValidation.isValidMp4(output)) { "Could not render this clip." }
            saveToGallery(context, output)
        } finally {
            output.delete()
            copy?.delete()
        }
    }

    private fun saveToGallery(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Clip Studio ${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Clip Studio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the clip in Movies.")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Could not open the clip for writing.")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "Could not publish the exported clip." }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private suspend fun runFfmpeg(arguments: Array<String>): Boolean =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
                continuation.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
}
