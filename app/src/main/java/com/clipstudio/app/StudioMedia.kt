package com.clipstudio.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

internal data class SourcePart(
    val uri: Uri, val offsetMs: Long, val durationMs: Long,
    val nextOffsetMs: Long = offsetMs + durationMs, val formatId: String? = null, val joined: Boolean = false
)

internal data class AudioFinish(
    val normalize: Boolean = false, val fade: Boolean = false,
    val mixTracks: Boolean = false, val voice: Float = 1f, val game: Float = .6f
)


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
        resumeAtMs: Long = 0,
        formatId: String? = null,
        onPart: suspend (SourcePart) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val videoId = YouTubeLinkClassifier.parseVideoId(link)
        val media = runInterruptible {
            YouTubeVideoImporter.prepare(context)
            val format = formatId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
                ?: "b[protocol=https][ext=mp4][vcodec^=avc1][acodec^=mp4a][height<=1080]"
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                .addOption("--no-playlist")
                .addOption("--match-filter", "!is_live & !is_upcoming")
                .addOption("--socket-timeout", 20)
                .addOption("-f", format)
                .addOption("--print", """{"url":%(url)j,"format":%(format_id)j,"duration":%(duration)j}""")
            YoutubeDL.execute(request).out.lineSequence().map(String::trim).firstOrNull { it.startsWith("{") }
                ?.let(::JSONObject)
        } ?: return@withContext false
        val source = media.getString("url")
        val selectedFormat = media.getString("format")
        val expectedEnd = media.optDouble("duration", 0.0).let { if (it.isFinite()) (it * 1000).toLong() else 0L }
        val remote = runCatching { URI(source) }.getOrNull()
        if (remote?.scheme != "https" || remote.host == null) return@withContext false
        if (formatId != null && selectedFormat != formatId) return@withContext false
        if (expectedEnd > 0 && resumeAtMs >= expectedEnd - 300) return@withContext true

        val dir = File(context.filesDir, "imports/parts-${UUID.randomUUID()}")
        check(dir.mkdirs()) { "Could not create download storage." }
        val list = File(dir, "segments.csv")
        var count = 0
        var latestEnd = resumeAtMs
        suspend fun publishReady(includeTail: Boolean = false) {
            val csv = if (list.isFile) list.readText() else ""
            val rows = csv.substringBeforeLast('\n', "").lineSequence().mapNotNull(::parseSegmentRow).toList()
            for ((index, entry) in rows.withIndex().drop(count)) {
                val tail = index == rows.lastIndex
                if (tail && !includeTail && entry.endMs - entry.startMs < 36_000L) break
                val file = File(dir, entry.name)
                if (!YouTubeDownloadValidation.isValidMp4(file)) break
                val uri = Uri.fromFile(file)
                if (entry.startMs + resumeAtMs < latestEnd - 750) break
                val offset = entry.startMs + resumeAtMs
                latestEnd = entry.endMs + resumeAtMs
                onPart(SourcePart(uri, offset, duration(context, uri), latestEnd, selectedFormat))
                count++
            }
        }
        try {
            val complete = coroutineScope {
                val transfer = async {
                    val seek = if (resumeAtMs == 0L) emptyList() else listOf("-ss", seconds(resumeAtMs))
                    val arguments = listOf("-nostdin", "-loglevel", "error", "-rw_timeout", "20000000") + seek + listOf(
                        "-i", source, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
                        "-f", "segment", "-segment_time", "40", "-reset_timestamps", "1",
                        "-segment_format_options", "movflags=+faststart",
                        "-segment_list", list.absolutePath, "-segment_list_type", "csv",
                        File(dir, "part-%05d.mp4").absolutePath
                    )
                    runFfmpeg(arguments.toTypedArray())
                }
                while (!transfer.isCompleted) {
                    publishReady()
                    delay(400)
                }
                val success = transfer.await()
                val last = if (list.isFile) list.readLines().lastOrNull()?.let(::parseSegmentRow) else null
                val finished = success && (expectedEnd == 0L || last != null && resumeAtMs + last.endMs >= expectedEnd - 1800)
                if (finished) publishReady(includeTail = true)
                finished
            }
            check(complete || count == 0 && resumeAtMs == 0L) {
                "Download interrupted. Use Resume download to keep the completed sections."
            }
            complete && count > 0
        } finally {
            if (count == 0) dir.deleteRecursively()
        }
    }

    suspend fun join(context: Context, sections: List<SourcePart>): SourcePart = withContext(Dispatchers.IO) {
        require(sections.size == 2 && sections.all { it.uri.scheme == "file" && !it.joined })
        val first = sections.first()
        val next = sections.last()
        require(adjacent(first, next)) { "These sections have a gap." }
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val list = File.createTempFile("studio-join-", ".ffconcat", context.cacheDir)
        val result = File.createTempFile("studio-joined-", ".mp4", dir)
        var complete = false
        try {
            list.writeText(buildString {
                appendLine("ffconcat version 1.0")
                sections.forEach { part ->
                    val path = requireNotNull(part.uri.path).replace("'", "'\\''")
                    appendLine("file '$path'")
                    appendLine("duration ${seconds(part.nextOffsetMs - part.offsetMs)}")
                }
            })
            check(runFfmpeg(arrayOf("-nostdin", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
                "-i", list.absolutePath, "-map", "0:v:0", "-map", "0:a?", "-c", "copy", "-movflags", "+faststart",
                result.absolutePath)) && YouTubeDownloadValidation.isValidMp4(result)) { "Could not join these sections." }
            val part = SourcePart(Uri.fromFile(result), first.offsetMs, duration(context, Uri.fromFile(result)),
                next.nextOffsetMs, first.formatId, joined = true)
            complete = true
            part
        } finally {
            list.delete()
            if (!complete) result.delete()
        }
    }

    suspend fun export(context: Context, clip: ExportClip): Uri = withContext(Dispatchers.IO) {
        val output = render(context, clip, preview = false)
        try { saveToGallery(context, output) } finally { output.delete() }
    }

    suspend fun preview(context: Context, clip: ExportClip): Uri =
        Uri.fromFile(render(context, clip, preview = true))

    private suspend fun render(context: Context, clip: ExportClip, preview: Boolean): File = withContext(Dispatchers.IO) {
        val (part, startMs, endMs, layout, camera, gameplay) = clip
        require(startMs in 0 until endMs && endMs <= part.durationMs)
        val width = if (preview) 540 else clip.width
        val captions = clip.captions
        val output = File.createTempFile(if (preview) "studio-preview-" else "studio-export-", ".mp4", context.cacheDir)
        val subtitle = if (captions.isEmpty()) null else File.createTempFile("studio-captions-", ".ass", context.cacheDir)
        var done = false
        try {
            val (frameWidth, frameHeight) = layout.dimensions(width)
            val filter = videoFilter(layout, camera, gameplay, width, clip.facePath)
            val renderFilter = if (subtitle == null) filter else {
                subtitle.writeText(AssKaraokeTrack.render(captions, clip.preset, frameWidth, frameHeight))
                "$filter;[out]subtitles=filename='${subtitle.absolutePath}':fontsdir=/system/fonts[captioned]"
            }
            val video = if (subtitle == null) "[out]" else "[captioned]"
            val tracks = if (clip.muted) 0 else audioTrackCount(context, part.uri)
            val audioFilter = audioFinishFilter(clip.audio, tracks, endMs - startMs)
            val audio = when {
                tracks == 0 -> listOf("-an")
                audioFilter != null -> listOf("-map", "[finishedAudio]", "-c:a", "aac", "-b:a", "128k")
                else -> listOf("-map", "0:a:0?", "-c:a", "aac", "-b:a", "128k")
            }
            val graph = if (audioFilter == null) renderFilter else "$renderFilter;$audioFilter"
            val arguments = listOf(
                "-nostdin", "-loglevel", "error", "-y", "-ss", seconds(startMs),
                "-i", input(context, part.uri), "-t", seconds(endMs - startMs),
                "-filter_complex_threads", "2", "-filter_complex", graph, "-map", video,
                "-r", "30", "-threads", "2", "-c:v", "libopenh264",
                "-b:v", if (width == 1080) "4500k" else "2600k", "-pix_fmt", "yuv420p"
            ) + audio + listOf("-movflags", "+faststart", output.absolutePath)
            check(runFfmpeg(arguments.toTypedArray()) && YouTubeDownloadValidation.isValidMp4(output)) {
                "Could not render this clip."
            }
            done = true
            output
        } finally {
            if (!done) output.delete()
            subtitle?.delete()
        }
    }

    internal fun audioTrackCount(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).count {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Exception) {
            // Preserve the standard optional audio mapping when the container is only supported by FFmpeg.
            1
        } finally { extractor.release() }
    }

    suspend fun waveform(context: Context, part: SourcePart): List<Float> = withContext(Dispatchers.IO) {
        val data = File.createTempFile("studio-wave-", ".txt", context.cacheDir)
        try {
            val samples = (part.durationMs * 16L / 150L).coerceIn(160L, Int.MAX_VALUE.toLong())
            val filter = "asetnsamples=n=$samples:p=0,astats=metadata=1:reset=1:" +
                "measure_perchannel=none:measure_overall=Peak_level," +
                "ametadata=print:key=lavfi.astats.Overall.Peak_level:file=${data.absolutePath}"
            val success = runFfmpeg(arrayOf("-nostdin", "-loglevel", "error", "-i", input(context, part.uri),
                "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-af", "aresample=16000,asetpts=N/SR/TB,$filter", "-f", "null", "-"))
            if (!success) emptyList() else data.useLines { lines ->
                lines.filter { it.startsWith("lavfi.astats.Overall.Peak_level=") }.take(160).map { row ->
                    val db = row.substringAfter('=').toFloatOrNull() ?: -60f
                    ((db + 60f) / 60f).coerceIn(0f, 1f)
                }.toList()
            }
        } finally { data.delete() }
    }

    internal fun input(context: Context, uri: Uri): String =
        if (uri.scheme == "file") requireNotNull(uri.path) else FFmpegKitConfig.getSafParameterForRead(context, uri)

    internal suspend fun decodeAudio(context: Context, part: SourcePart, file: File, startMs: Long, endMs: Long): Boolean =
        runFfmpeg(arrayOf(
            "-nostdin", "-loglevel", "error", "-y", "-ss", seconds(startMs),
            "-i", input(context, part.uri), "-t", seconds(endMs - startMs), "-map", "0:a:0",
            "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_f32be", "-f", "f32be", file.absolutePath
        ))

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

    private suspend fun runFfmpeg(arguments: Array<String>): Boolean {
        val done = CompletableDeferred<Boolean>()
        val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
            done.complete(ReturnCode.isSuccess(completed.returnCode))
        }
        return try {
            done.await()
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) { done.await() }
            throw cancelled
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
}
