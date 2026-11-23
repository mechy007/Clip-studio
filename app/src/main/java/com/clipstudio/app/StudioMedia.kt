package com.clipstudio.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlin.math.abs
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
        val published = mutableSetOf<String>()
        var latestEnd = resumeAtMs
        suspend fun publishReady(includeTail: Boolean = false) {
            val csv = if (list.isFile) list.readText() else ""
            val rows = csv.substringBeforeLast('\n', "").lineSequence().mapNotNull(::parseSegmentRow).toList()
            for ((index, entry) in rows.withIndex().drop(published.size)) {
                val tail = index == rows.lastIndex
                if (tail && !includeTail && entry.endMs - entry.startMs < 40_000L) break
                val file = File(dir, entry.name)
                if (!YouTubeDownloadValidation.isValidMp4(file)) break
                val uri = Uri.fromFile(file)
                if (entry.startMs + resumeAtMs < latestEnd - 750) break
                val offset = entry.startMs + resumeAtMs
                val end = entry.endMs + resumeAtMs
                val part = SourcePart(uri, offset, duration(context, uri), end, selectedFormat)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    onPart(part)
                    latestEnd = end
                    published += entry.name
                }
            }
        }
        try {
            val complete = coroutineScope {
                val transfer = async {
                    val seek = if (resumeAtMs == 0L) emptyList() else listOf("-ss", seconds(resumeAtMs))
                    // Stream-copy the initial import. A restarted import needs an accurate seek
                    // that decodes and discards preceding GOP frames before producing new parts.
                    val codec = if (resumeAtMs == 0L) listOf("-c", "copy") else listOf(
                        "-threads", "2", "-c:v", "libopenh264", "-b:v", "4500k",
                        "-force_key_frames", "expr:gte(t,n_forced*40)", "-c:a", "aac", "-b:a", "128k"
                    )
                    val arguments = listOf("-nostdin", "-loglevel", "error", "-rw_timeout", "20000000") + seek +
                        listOf("-i", source, "-map", "0:v:0", "-map", "0:a:0") + codec + listOf(
                        "-avoid_negative_ts", "disabled", "-f", "segment", "-segment_time", "40", "-reset_timestamps", "1",
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
                publishReady(includeTail = finished)
                finished && last != null && latestEnd >= resumeAtMs + last.endMs
            }
            check(complete || published.isEmpty() && resumeAtMs == 0L) {
                "Download interrupted. Use Resume download to keep the completed sections."
            }
            complete && published.isNotEmpty()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { publishReady() } }
            throw cancelled
        } finally {
            if (published.isEmpty()) dir.deleteRecursively()
            else dir.listFiles().orEmpty().filter { it.name !in published }.forEach { it.delete() }
        }
    }

    suspend fun join(context: Context, sections: List<SourcePart>): SourcePart = withContext(Dispatchers.IO) {
        require(sections.size == 2 && sections.all { it.uri.scheme == "file" && !it.joined })
        val first = sections.first()
        val next = sections.last()
        require(adjacent(first, next)) { "These sections have a gap." }
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val result = File.createTempFile("studio-joined-", ".mp4", dir)
        var complete = false
        try {
            // Decode each file separately so initial copied parts and accurately
            // resumed parts can be joined even when their encoder settings differ.
            val a = seconds(first.nextOffsetMs - first.offsetMs)
            val b = seconds(next.nextOffsetMs - next.offsetMs)
            val filter = "[0:v:0]trim=duration=$a,setpts=PTS-STARTPTS,setsar=1,fps=30[v0];" +
                "[1:v:0]trim=duration=$b,setpts=PTS-STARTPTS,setsar=1,fps=30[v1];" +
                "[0:a:0]aresample=48000,apad,atrim=duration=$a,asetpts=PTS-STARTPTS[a0];" +
                "[1:a:0]aresample=48000,apad,atrim=duration=$b,asetpts=PTS-STARTPTS[a1];" +
                "[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]"
            check(copyJoin(context, sections, result) || runFfmpeg(arrayOf("-nostdin", "-loglevel", "error", "-y",
                "-i", input(context, first.uri), "-i", input(context, next.uri),
                "-filter_complex_threads", "2", "-filter_complex", filter, "-map", "[v]", "-map", "[a]",
                "-threads", "2", "-c:v", "libopenh264", "-b:v", "6500k", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart",
                result.absolutePath)) && YouTubeDownloadValidation.isValidMp4(result)) { "Could not join these sections." }
            val part = SourcePart(Uri.fromFile(result), first.offsetMs, duration(context, Uri.fromFile(result)),
                next.nextOffsetMs, first.formatId, joined = true)
            complete = true
            part
        } finally {
            if (!complete) result.delete()
        }
    }

    private suspend fun copyJoin(context: Context, sections: List<SourcePart>, result: File): Boolean {
        if (!FastMedia.canCopyJoin(context, sections[0], sections[1])) return false
        val manifest = File.createTempFile("studio-join-", ".ffconcat", context.cacheDir)
        return try {
            manifest.writeText(buildString {
                appendLine("ffconcat version 1.0")
                sections.forEach { part ->
                    val path = requireNotNull(part.uri.path).replace("'", "'\\''")
                    appendLine("file '$path'")
                    appendLine("duration ${seconds(part.nextOffsetMs - part.offsetMs)}")
                }
            })
            runFfmpeg(arrayOf("-nostdin", "-loglevel", "error", "-xerror", "-y", "-f", "concat", "-safe", "0",
                "-i", manifest.absolutePath, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
                "-movflags", "+faststart", result.absolutePath)) &&
                validDuration(context, result, sections.last().nextOffsetMs - sections.first().offsetMs)
        } finally { manifest.delete() }
    }

    suspend fun export(
        context: Context, clip: ExportClip, phase: (String) -> Unit,
        record: suspend (Uri, RenderStats) -> Unit
    ) = withContext(Dispatchers.IO) {
        val output = render(context, clip, preview = false, phase)
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                phase("Saving MP4")
                val start = SystemClock.elapsedRealtime()
                val uri = saveToGallery(context, output.file)
                record(uri, output.stats.copy(saveMs = SystemClock.elapsedRealtime() - start))
            }
        } finally { output.file.delete() }
    }

    suspend fun preview(context: Context, clip: ExportClip): Uri =
        Uri.fromFile(render(context, clip, preview = true) {}.file)

    private data class Rendered(val file: File, val stats: RenderStats)

    private suspend fun render(
        context: Context, clip: ExportClip, preview: Boolean, phase: (String) -> Unit
    ): Rendered = withContext(Dispatchers.IO) {
        val (part, startMs, endMs, layout, camera, gameplay) = clip
        require(startMs in 0 until endMs && endMs <= part.durationMs)
        val started = SystemClock.elapsedRealtime()
        val length = endMs - startMs
        val width = if (preview) 540 else clip.width
        val captions = if (clip.originalFraming) emptyList() else clip.captions
        val output = File.createTempFile(if (preview) "studio-preview-" else "studio-export-", ".mp4", context.cacheDir)
        var subtitle: File? = null
        var done = false
        var firstAttempt: Long? = null
        var retried = false
        fun result(encoder: String): Rendered {
            val renderStart = requireNotNull(firstAttempt)
            done = true
            return Rendered(output, RenderStats(encoder, renderStart - started,
                SystemClock.elapsedRealtime() - renderStart, 0, retried))
        }
        suspend fun attempt(encoder: String, args: Array<String>, durationToleranceMs: Long? = null): Boolean {
            phase(encoder)
            output.delete()
            if (firstAttempt == null) firstAttempt = SystemClock.elapsedRealtime()
            return runFfmpeg(args) && if (durationToleranceMs != null) validDuration(context, output, length, durationToleranceMs)
            else YouTubeDownloadValidation.isValidMp4(output)
        }
        fun inputArgs() = listOf("-nostdin", "-loglevel", "error", "-y", "-ss", seconds(startMs),
            "-i", input(context, part.uri), "-t", seconds(length))
        val outputArgs = listOf("-movflags", "+faststart", output.absolutePath)
        try {
            if (clip.originalFraming && !preview && FastMedia.canCopyTrim(context, part.uri, startMs)) {
                val args = inputArgs() + listOf("-map", "0:v:0", "-map", "0:a:0?", "-c", "copy",
                    "-avoid_negative_ts", "disabled", "-use_editlist", "1") + outputArgs
                if (attempt("Copying source tracks", args.toTypedArray(), 50)) return@withContext result("Stream copy")
                retried = true
            }
            val (frameWidth, frameHeight) = layout.dimensions(width)
            val filter = if (clip.originalFraming) "[0:v]pad=ceil(iw/2)*2:ceil(ih/2)*2[out]"
                else videoFilter(layout, camera, gameplay, width, clip.facePath, clip.enhanceFacecam)
            val renderFilter = if (captions.isEmpty()) filter else {
                val script = File.createTempFile("studio-captions-", ".ass", context.cacheDir)
                subtitle = script
                script.writeText(AssKaraokeTrack.render(captions, clip.preset, frameWidth, frameHeight,
                    clip.captionPosition, clip.captionSize))
                "$filter;[out]setpts=PTS-STARTPTS,ass=filename='${script.absolutePath}':fontsdir=/system/fonts[captioned]"
            }
            val video = if (subtitle == null) "[out]" else "[captioned]"
            val tracks = if (clip.muted && !clip.originalFraming) 0 else audioTrackCount(context, part.uri)
            val audioFilter = if (clip.originalFraming) null else audioFinishFilter(clip.audio, tracks, length)
            val audio = when {
                tracks == 0 -> listOf("-an")
                audioFilter != null -> listOf("-map", "[finishedAudio]", "-c:a", "aac", "-b:a", "128k")
                else -> listOf("-map", "0:a:0?", "-c:a", "aac", "-b:a", "128k")
            }
            val graph = if (audioFilter == null) renderFilter else "$renderFilter;$audioFilter"
            fun arguments(codec: List<String>, pixels: String): Array<String> {
                val rate = if (clip.originalFraming) emptyList() else listOf("-r", "30")
                val bitrate = when {
                    !preview && !clip.originalFraming && width == 1440 -> if (clip.highDetail) "16000k" else "12000k"
                    !preview && !clip.originalFraming && clip.highDetail -> if (width == 1080) "8500k" else "4500k"
                    width == 1080 || clip.originalFraming -> "4500k"
                    else -> "2600k"
                }
                return (inputArgs() + listOf("-filter_complex_threads", "2", "-filter_complex", graph, "-map", video) +
                    rate + codec + listOf("-b:v", bitrate, "-pix_fmt", pixels) + audio + outputArgs).toTypedArray()
            }
            if (clip.fastExport && !preview && !clip.originalFraming) {
                val hardware = FastMedia.hardwareAvc(frameWidth, frameHeight)
                if (hardware != null) {
                    val codec = listOf("-c:v", "h264_mediacodec", "-codec_name", hardware, "-bf", "0")
                    if (attempt("Rendering with hardware H.264", arguments(codec, "nv12"), 150) &&
                        FastMedia.matchesVideo(context, Uri.fromFile(output), frameWidth, frameHeight, tracks > 0)) {
                        return@withContext result("Hardware H.264")
                    }
                    retried = true
                }
            }
            val software = arguments(listOf("-threads", "2", "-c:v", "libopenh264"), "yuv420p")
            check(attempt(if (retried) "Retrying with software H.264" else "Rendering with software H.264",
                software)) {
                if (subtitle == null) "Could not render this clip." else "Could not render the clip with its captions."
            }
            result("Software H.264")
        } finally {
            if (!done) output.delete()
            subtitle?.delete()
        }
    }

    private suspend fun validDuration(context: Context, file: File, expected: Long, toleranceMs: Long = 150): Boolean {
        if (!YouTubeDownloadValidation.isValidMp4(file)) return false
        return try {
            abs(duration(context, Uri.fromFile(file)) - expected) <= toleranceMs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
    }

    internal fun audioTrackCount(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).count {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Exception) {
            // The renderer retains optional audio mapping for unknown containers.
            -1
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
                "-map", "0:a:0", "-vn", "-ar", "16000", "-af", "aformat=channel_layouts=mono,aresample=16000,asetpts=N/SR/TB,$filter", "-f", "null", "-"))
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

    internal suspend fun decodeAudio(
        context: Context, part: SourcePart, file: File, startMs: Long, endMs: Long, audioTrack: Int = 0
    ): Boolean =
        runFfmpeg(arrayOf(
            "-nostdin", "-loglevel", "error", "-y", "-ss", seconds(startMs),
            "-i", input(context, part.uri), "-t", seconds(endMs - startMs), "-map", "0:a:$audioTrack",
            "-vn", "-ar", "16000", "-af", "aformat=channel_layouts=mono", "-c:a", "pcm_f32be", "-f", "f32be", file.absolutePath
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
