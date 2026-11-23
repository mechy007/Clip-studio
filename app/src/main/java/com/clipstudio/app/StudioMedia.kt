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
import kotlinx.coroutines.withTimeoutOrNull
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

    private data class MediaStream(val url: String, val headers: String)
    private data class RecordingStreams(val endMs: Long, val format: String, val inputs: List<MediaStream>)

    private fun JSONObject.requestHeaders(): Map<String, String> = optJSONObject("http_headers")?.let { headers ->
        headers.keys().asSequence().associateWith { headers.optString(it) }
    }.orEmpty()

    private suspend fun resolveRecording(context: Context, link: String, previousFormat: String?): RecordingStreams {
        val id = YouTubeLinkClassifier.parseVideoId(link)
        val media = runInterruptible {
            YouTubeVideoImporter.prepare(context)
            val format = previousFormat?.also {
                check(it.matches(Regex("[A-Za-z0-9_-]+(\\+[A-Za-z0-9_-]+)?"))) { "Saved video format is unavailable." }
            } ?: "bv[protocol=https][vcodec^=avc1][height<=1080]+ba[protocol=https][acodec^=mp4a]/" +
                "b[protocol=https][ext=mp4][vcodec^=avc1][acodec^=mp4a][height<=1080]"
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
                .addOption("--no-playlist")
                .addOption("--no-warnings")
                .addOption("--ignore-no-formats-error")
                .addOption("--socket-timeout", 20)
                .addOption("--dump-single-json")
                .addOption("-f", format)
            JSONObject(YoutubeDL.execute(request).out.trim())
        }
        when (media.optString("live_status")) {
            "is_live", "is_upcoming", "post_live" -> error(
                "30-minute parts are available when YouTube finishes processing the livestream replay."
            )
        }
        val durationMs = media.optDouble("duration", 0.0).let { if (it.isFinite()) (it * 1000).toLong() else 0L }
        check(durationMs > 0) { "YouTube has not provided the recording length yet. Try again when the replay is ready." }
        val selectedFormat = media.optString("format_id")
        check(selectedFormat.isNotBlank() && (previousFormat == null || selectedFormat == previousFormat)) {
            "The saved YouTube format is unavailable. Completed parts are still available."
        }
        val separate = media.optJSONArray("requested_formats")
        val streams = if (separate != null && separate.length() == 2) {
            List(2) { separate.getJSONObject(it) }
        } else listOf(media)
        val inputs = streams.map { stream ->
            MediaStream(stream.optString("url"), mediaRequestHeaders(media.requestHeaders(), stream.requestHeaders()))
        }
        check(inputs.all { input ->
            runCatching { URI(input.url) }.getOrNull()?.let { it.scheme == "https" && it.host != null } == true
        }) { "No compatible video and audio streams were available for this recording." }

        return RecordingStreams(durationMs, selectedFormat, inputs)
    }

    internal fun guidedFullFile(context: Context, projectId: String): File =
        File(guidedDirectory(context, projectId), "full.mp4")

    private fun guidedDirectory(context: Context, projectId: String): File =
        File(context.filesDir, "imports/guided/${UUID.fromString(projectId)}")

    /** Use the standard full downloader once, then prepare one local range at a time. */
    suspend fun preparePartFromFull(
        context: Context, projectId: String, link: String, startMs: Long,
        downloading: (Int) -> Unit, cutting: (Int) -> Unit, publish: suspend (SourcePart, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val source = guidedFullFile(context, projectId)
        if (!YouTubeDownloadValidation.isValidMp4(source)) {
            check(source.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Could not create download storage." }
            downloading(0)
            val downloaded = YouTubeVideoImporter.download(context, link, 1080, downloading)
            val file = File(requireNotNull(downloaded.path))
            check(file.renameTo(source)) {
                file.delete()
                "Could not save the full recording for local clipping."
            }
        }
        val durationMs = duration(context, Uri.fromFile(source))
        val range = nextRecordingPart(startMs, durationMs) ?: return@withContext
        val directory = File(guidedDirectory(context, projectId), startMs.toString())
        check(directory.isDirectory || directory.mkdirs()) { "Could not create part storage." }
        val output = File(directory, "from-full.mp4")
        var actual = recordingDuration(context, output, range.lengthMs)
        if (actual == null) {
            val staging = File(directory, "preparing-full-part.mp4")
            cutting(0)
            try {
                check(encodeRecordingChunk(context, listOf(MediaStream(source.absolutePath, "")),
                    range.startMs, range.lengthMs, staging, remote = false) { time ->
                    cutting((100.0 * time / range.lengthMs).toInt().coerceIn(0, 99))
                }) { "The full recording downloaded, but this part could not be prepared. Retry to use the saved recording." }
                actual = duration(context, Uri.fromFile(staging))
                check(staging.renameTo(output)) { "Could not save this part." }
            } finally {
                staging.delete()
            }
        }
        val part = SourcePart(Uri.fromFile(output), range.startMs, requireNotNull(actual), range.endMs)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { publish(part, range.endMs == durationMs) }
    }

    private suspend fun resolveGuidedRecording(context: Context, link: String, previousFormat: String?): RecordingStreams =
        withTimeoutOrNull(60_000) { resolveRecording(context, link, previousFormat) }
            ?: error("The part downloader did not start. Use Download full video first to prepare parts locally.")

    /** Commit each short piece locally so a retry does not fetch completed pieces again. */
    suspend fun downloadRecordingPart(
        context: Context, projectId: String, link: String, startMs: Long, previousFormat: String?,
        fast: Boolean = false, progress: (GuidedDownloadProgress) -> Unit, publish: suspend (SourcePart, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val directory = File(guidedDirectory(context, projectId), startMs.toString())
        val files = GuidedDownloadFiles(directory)
        var recording: RecordingStreams? = null
        val plan = files.load(startMs) ?: run {
            val streams = resolveGuidedRecording(context, link, previousFormat)
            recording = streams
            val range = nextRecordingPart(startMs, streams.endMs) ?: return@withContext
            check(directory.isDirectory || directory.mkdirs()) { "Could not create download storage." }
            GuidedDownloadPlan(range, streams.endMs, streams.format).also(files::save)
        }
        val range = plan.range
        val chunks = range.downloadChunks()
        val publishedDuration = recordingDuration(context, files.output, range.lengthMs)
        val tryFast = fast && publishedDuration == null && chunks.indices.none { files.chunk(it).isFile }
        val copied = tryFast && copyRecordingRange(context, link, plan, files, progress)
        if (tryFast && !copied) progress(GuidedDownloadProgress(0, null, newAttempt = true))
        if (publishedDuration == null && !copied) {
            files.output.delete()
            val completed = chunks.mapIndexed { index, chunk ->
                val file = files.chunk(index)
                val ready = recordingDuration(context, file, chunk.lengthMs) != null
                if (!ready) file.delete()
                ready
            }
            val saved = chunks.zip(completed).sumOf { (chunk, ready) -> if (ready) chunk.lengthMs else 0L }
            var processed = saved
            val started = SystemClock.elapsedRealtime()
            progress(guidedDownloadProgress(range.lengthMs, saved, processed, 0))
            var useSectionDownloader = false
            try {
                for ((index, chunk) in chunks.withIndex()) {
                    if (completed[index]) continue
                    currentCoroutineContext().ensureActive()
                    val streams = recording ?: resolveGuidedRecording(context, link, plan.format).also { recording = it }
                    check(streams.endMs >= range.endMs) { "The recording is now shorter than this part. Saved pieces were kept." }
                    files.staging.delete()
                    val beforeChunk = processed
                    val report: (Long) -> Unit = { time ->
                        progress(guidedDownloadProgress(range.lengthMs, saved, beforeChunk + time,
                            SystemClock.elapsedRealtime() - started))
                    }
                    if (!useSectionDownloader) {
                        useSectionDownloader = !encodeRecordingChunk(context, streams.inputs, chunk.startMs,
                            chunk.lengthMs, files.staging, remote = true, progress = report)
                    }
                    if (useSectionDownloader) {
                        downloadAndNormalizeSection(context, link, plan.format, chunk, files.staging, report)
                    }
                    check(files.staging.renameTo(files.chunk(index))) { "Could not save the downloaded piece." }
                    processed += chunk.lengthMs
                    progress(guidedDownloadProgress(range.lengthMs, saved, processed, SystemClock.elapsedRealtime() - started))
                }
                progress(GuidedDownloadProgress(100, null, assembling = true))
                files.concatList.writeText(buildString {
                    appendLine("ffconcat version 1.0")
                    chunks.indices.forEach { index -> appendLine("file ${files.chunk(index).name}") }
                })
                check(runFfmpeg(arrayOf("-nostdin", "-loglevel", "error", "-y", "-f", "concat", "-safe", "1",
                    "-i", files.concatList.absolutePath, "-c", "copy", "-movflags", "+faststart", files.staging.absolutePath)) &&
                    recordingDuration(context, files.staging, range.lengthMs) != null && files.staging.renameTo(files.output)
                ) { "Could not assemble this part. Retry to reuse the downloaded pieces." }
            } finally {
                files.staging.delete()
                files.concatList.delete()
            }
        }
        val url = Uri.fromFile(files.output)
        val actualDuration = publishedDuration ?: duration(context, url)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            publish(SourcePart(url, range.startMs, actualDuration, range.endMs, plan.format), range.endMs == plan.recordingEndMs)
            chunks.indices.forEach { files.chunk(it).delete() }
        }
    }

    private suspend fun copyRecordingRange(
        context: Context, link: String, plan: GuidedDownloadPlan, files: GuidedDownloadFiles,
        progress: (GuidedDownloadProgress) -> Unit
    ): Boolean {
        val folder = File(files.directory, "quick-download")
        folder.deleteRecursively()
        if (!folder.mkdir()) return false
        val range = plan.range
        val started = SystemClock.elapsedRealtime()
        progress(GuidedDownloadProgress(0, null))
        try {
            val media = YouTubeVideoImporter.downloadSection(context, link, plan.format, range, folder, copyTracks = true) {
                progress(guidedDownloadProgress(range.lengthMs, 0, it, SystemClock.elapsedRealtime() - started))
            }
            val actual = recordingDuration(context, media, range.lengthMs) ?: return false
            if (abs(actual - range.lengthMs) > 350L || !FastMedia.matchesDownloadedPart(context, Uri.fromFile(media))) {
                return false
            }
            return media.renameTo(files.output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: com.yausername.youtubedl_android.YoutubeDLException) {
            return false
        } finally {
            folder.deleteRecursively()
        }
    }

    private suspend fun downloadAndNormalizeSection(
        context: Context, link: String, format: String, range: RecordingPartWindow, output: File,
        progress: (Long) -> Unit
    ) {
        val staging = File(output.parentFile, "section-download")
        staging.deleteRecursively()
        check(staging.mkdir()) { "Could not prepare storage for this part." }
        try {
            val section = try {
                YouTubeVideoImporter.downloadSection(context, link, format, range, staging) { progress(it * 3 / 4) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: com.yausername.youtubedl_android.YoutubeDLException) {
                val message = if (YouTubeDownloadValidation.isForbidden(failure.message.orEmpty()))
                    "YouTube refused the requested video range." else "The alternate downloader could not fetch this range."
                error("$message Saved pieces were kept. Retry this part or use full import.")
            }
            check(recordingDuration(context, section, range.lengthMs) != null) {
                "The alternate downloader returned an incomplete range. Saved pieces were kept."
            }
            check(encodeRecordingChunk(context, listOf(MediaStream(section.absolutePath, "")),
                0L, range.lengthMs, output, remote = false) { progress(range.lengthMs * 3 / 4 + it / 4) }) {
                "The range downloaded but could not be converted to MP4. Saved pieces were kept."
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun encodeRecordingChunk(
        context: Context, inputs: List<MediaStream>, startMs: Long, lengthMs: Long, output: File,
        remote: Boolean, progress: (Long) -> Unit
    ): Boolean {
        val seek = if (startMs == 0L) emptyList() else listOf("-ss", seconds(startMs))
        val inputArgs = inputs.flatMap { stream ->
            val network = if (remote) listOf("-rw_timeout", "30000000") else emptyList()
            val headers = if (stream.headers.isEmpty()) emptyList() else listOf("-headers", stream.headers)
            network + headers + seek + listOf("-i", stream.url)
        }
        val codec = listOf("-threads", "2", "-c:v", "libopenh264", "-b:v", "4500k", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart")
        val args = listOf("-nostdin", "-loglevel", "error", "-y") + inputArgs + listOf("-t", seconds(lengthMs),
            "-map", "0:v:0", "-map", "${inputs.lastIndex}:a:0") + codec + output.absolutePath
        val lastUpdate = java.util.concurrent.atomic.AtomicLong()
        val succeeded = runFfmpeg(args.toTypedArray(), idleTimeoutMs = if (remote) 45_000L else null) { time ->
            val now = SystemClock.elapsedRealtime()
            val before = lastUpdate.get()
            if (now - before >= 1_000 && lastUpdate.compareAndSet(before, now)) {
                progress(time.coerceIn(0, lengthMs))
            }
        }
        return succeeded && recordingDuration(context, output, lengthMs) != null
    }

    private suspend fun recordingDuration(context: Context, file: File, expected: Long): Long? {
        if (!YouTubeDownloadValidation.isValidMp4(file)) return null
        return try {
            duration(context, Uri.fromFile(file)).takeIf { abs(it - expected) <= minOf(1500L, expected / 5) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
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
            val enhance = !clip.originalFraming && clip.enhancement != VideoEnhancement.OFF
            val filter = if (clip.originalFraming) "[0:v]pad=ceil(iw/2)*2:ceil(ih/2)*2[out]"
                else videoFilter(layout, camera, gameplay, width, clip.facePath, clip.enhanceFacecam, qualityScale = enhance)
            val editedVideo = if (enhance) "[enhanced]" else "[out]"
            val enhancedFilter = if (enhance) "$filter;[out]${clip.enhancement.filter}$editedVideo" else filter
            val renderFilter = if (captions.isEmpty()) enhancedFilter else {
                val script = File.createTempFile("studio-captions-", ".ass", context.cacheDir)
                subtitle = script
                script.writeText(AssKaraokeTrack.render(captions, clip.preset, frameWidth, frameHeight,
                    clip.captionPosition, clip.captionSize))
                "$enhancedFilter;${editedVideo}setpts=PTS-STARTPTS,ass=filename='${script.absolutePath}':fontsdir=/system/fonts[captioned]"
            }
            val video = if (subtitle == null) editedVideo else "[captioned]"
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

    private suspend fun runFfmpeg(
        arguments: Array<String>, idleTimeoutMs: Long? = null, progress: ((Long) -> Unit)? = null
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        val lastAdvance = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
        val lastTime = java.util.concurrent.atomic.AtomicLong(0)
        val session = FFmpegKit.executeWithArgumentsAsync(arguments, { completed ->
            done.complete(ReturnCode.isSuccess(completed.returnCode))
        }, null, if (progress == null) null else { stats ->
            val time = stats.time.toLong()
            if (time > lastTime.getAndUpdate { previous -> maxOf(previous, time) }) {
                lastAdvance.set(SystemClock.elapsedRealtime())
            }
            progress(time)
        })
        return try {
            if (idleTimeoutMs == null) done.await() else {
                while (!done.isCompleted) {
                    val remaining = idleTimeoutMs - (SystemClock.elapsedRealtime() - lastAdvance.get())
                    if (remaining <= 0) {
                        FFmpegKit.cancel(session.sessionId)
                        done.await()
                        return false
                    }
                    withTimeoutOrNull(remaining) { done.await() }?.let { return it }
                }
                done.await()
            }
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) { done.await() }
            throw cancelled
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
}
