package com.clipstudio.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.abs

/**
 * Copies one part of a recording over several connections at once.
 *
 * YouTube serves each connection at roughly playback speed, so a single FFmpeg or yt-dlp transfer of a
 * 30-minute part is slow however fast the network is. This splits the part into lanes that start on
 * keyframes, copies every lane in parallel without re-encoding, then joins them with stream copy.
 * Any problem returns null and the caller uses the existing single-connection downloader instead.
 * Watch logcat for the tag ClipStudioLanes to see which path ran.
 */
internal object ParallelSectionCopy {
    private const val TAG = "ClipStudioLanes"
    private const val DURATION_TOLERANCE_MS = 400L

    private data class Source(val url: String, val headers: String)
    private class Prepared(val sources: List<Source>, val keys: List<KeyPacket>, val specs: List<LaneSpec>)

    suspend fun download(
        context: Context, link: String, format: String, range: RecordingPartWindow, directory: File,
        progress: (Long) -> Unit
    ): File? {
        if (!PARALLEL_SECTION_COPY_ENABLED) return null
        val lanes = laneCount(range.lengthMs)
        if (lanes < 2) return null
        val target = File(directory, "section.mp4")
        val scratch = mutableListOf<File>()
        var succeeded = false
        try {
            succeeded = withContext(Dispatchers.IO) { merge(context, link, format, range, directory, target, lanes, scratch, progress) }
            return if (succeeded) target else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Parallel copy failed; using the single-connection downloader", error)
            return null
        } finally {
            scratch.forEach { it.delete() }
            if (!succeeded) target.delete()
        }
    }

    private suspend fun merge(
        context: Context, link: String, format: String, range: RecordingPartWindow, directory: File,
        target: File, lanes: Int, scratch: MutableList<File>, progress: (Long) -> Unit
    ): Boolean = coroutineScope {
        val started = SystemClock.elapsedRealtime()
        // The caller cancels a download that shows no activity for 45 s, so keep signalling while setting up.
        val heartbeat = launch {
            var tick = 0L
            while (true) {
                delay(4_000)
                progress(++tick)
            }
        }
        val prepared = try {
            prepare(context, link, format, range, directory, lanes)
        } finally {
            heartbeat.cancel()
        }
        if (prepared == null) return@coroutineScope false
        val (sources, keys, specs) = Triple(prepared.sources, prepared.keys, prepared.specs)
        Log.i(TAG, "Lane plan: " + specs.joinToString { "${laneSeconds(it.seekMs)}s+${laneSeconds(it.durationMs)}s" })

        val laneFiles = List(lanes) { File(directory, "lane-$it.mp4") }
        scratch += laneFiles
        val manifest = File(directory, "lanes.ffconcat")
        scratch += manifest
        val done = AtomicLongArray(lanes)
        val cap = range.lengthMs * 95 / 100
        specs.mapIndexed { index, spec ->
            async(Dispatchers.IO) {
                val finished = runLane(sources, spec, laneFiles[index]) { time ->
                    done.set(index, time.coerceIn(0L, spec.durationMs))
                    var sum = 0L
                    for (lane in 0 until lanes) sum += done.get(lane)
                    progress(minOf(sum, cap))
                }
                check(finished) { "Lane $index did not finish." }
                done.set(index, spec.durationMs)
            }
        }.awaitAll()

        manifest.writeText(concatManifest(laneFiles, specs))
        // The first lane starts on a keyframe before the requested start. Seeking into the joined file with
        // an edit list hides that lead-in, the same way the single-connection downloader trims it.
        val offset = maxOf(0L, range.startMs - keys.first().ptsMs)
        val joined = runFfmpeg(arrayOf(
            "-nostdin", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
            "-ss", laneSeconds(offset), "-i", manifest.absolutePath, "-t", laneSeconds(range.lengthMs),
            "-map", "0:v:0", "-map", "0:a:0", "-c", "copy",
            "-avoid_negative_ts", "disabled", "-use_editlist", "1", "-movflags", "+faststart",
            target.absolutePath
        ), idleTimeoutMs = 120_000L)
        if (!joined || !YouTubeDownloadValidation.isValidMp4(target)) {
            Log.w(TAG, "Joining the lanes failed")
            return@coroutineScope false
        }
        val actual = durationMs(target)
        if (actual == null || abs(actual - range.lengthMs) > DURATION_TOLERANCE_MS) {
            Log.w(TAG, "Joined part is ${actual}ms but ${range.lengthMs}ms was expected")
            return@coroutineScope false
        }
        Log.i(TAG, "Copied ${range.lengthMs / 1000}s over $lanes lanes in ${(SystemClock.elapsedRealtime() - started) / 1000}s")
        true
    }

    private suspend fun prepare(
        context: Context, link: String, format: String, range: RecordingPartWindow, directory: File, lanes: Int
    ): Prepared? {
        val sources = resolveSources(context, link, format) ?: return null
        val boundaries = laneBoundaries(range, lanes)
        val found = coroutineScope {
            boundaries.mapIndexed { index, at ->
                async(Dispatchers.IO) { probe(sources.first(), at, File(directory, "probe-$index.txt")) }
            }.awaitAll()
        }
        if (found.any { it == null }) {
            Log.w(TAG, "Could not find keyframes near the lane boundaries")
            return null
        }
        val keys = found.filterNotNull()
        val specs = planLanes(range, boundaries, keys)
        if (specs == null) {
            Log.w(TAG, "Keyframes did not allow a lane plan: " + keys.joinToString { "${it.dtsMs}/${it.ptsMs}" })
            return null
        }
        return Prepared(sources, keys, specs)
    }

    /** Resolves direct stream URLs for the saved format, the same way the part scanner does. */
    private suspend fun resolveSources(context: Context, link: String, format: String): List<Source>? {
        if (!format.matches(Regex("[A-Za-z0-9_-]+(\\+[A-Za-z0-9_-]+)?"))) return null
        val processId = UUID.randomUUID().toString()
        val media = try {
            runInterruptible(Dispatchers.IO) {
                YouTubeVideoImporter.prepare(context)
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=" + YouTubeLinkClassifier.parseVideoId(link))
                    .addOption("--no-playlist")
                    .addOption("--no-warnings")
                    .addOption("--socket-timeout", 20)
                    .addOption("--dump-single-json")
                    .addOption("-f", format)
                JSONObject(YoutubeDL.execute(request, processId).out.trim())
            }
        } catch (cancelled: CancellationException) {
            runCatching { YoutubeDL.destroyProcessById(processId) }
            throw cancelled
        }
        val requested = media.optJSONArray("requested_formats")
        val streams = if (requested != null && requested.length() == 2) List(2) { requested.getJSONObject(it) } else listOf(media)
        val common = headerMap(media.optJSONObject("http_headers"))
        return streams.map { stream ->
            val url = stream.optString("url")
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host == null) return null
            Source(url, headerBlock(common + headerMap(stream.optJSONObject("http_headers"))))
        }
    }

    private fun headerMap(headers: JSONObject?): Map<String, String> =
        headers?.let { json -> json.keys().asSequence().associateWith { json.optString(it) } }.orEmpty()

    /** Finds the keyframe FFmpeg lands on when seeking to [boundaryMs] and returns its decode and display times. */
    private suspend fun probe(video: Source, boundaryMs: Long, file: File): KeyPacket? {
        file.delete()
        val ok = runFfmpeg(
            arrayOf("-nostdin", "-loglevel", "error", "-y") + input(video, boundaryMs) +
                arrayOf("-map", "0:v:0", "-c", "copy", "-frames:v", "1", "-f", "framecrc", file.absolutePath),
            idleTimeoutMs = 25_000L
        )
        val text = if (ok && file.isFile) file.readText() else null
        file.delete()
        val packet = text?.let(::parseFirstPacket) ?: return null
        // The packet times are relative to the seek point.
        return KeyPacket(boundaryMs + packet.dtsMs, boundaryMs + packet.ptsMs)
    }

    private fun input(source: Source, seekMs: Long): Array<String> {
        val args = mutableListOf(
            "-rw_timeout", "30000000", "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "4"
        )
        if (source.headers.isNotEmpty()) args += listOf("-headers", source.headers)
        args += listOf("-ss", laneSeconds(seekMs), "-i", source.url)
        return args.toTypedArray()
    }

    private suspend fun runLane(sources: List<Source>, spec: LaneSpec, output: File, onTime: (Long) -> Unit): Boolean {
        repeat(2) {
            output.delete()
            val args = mutableListOf("-nostdin", "-loglevel", "error", "-y")
            sources.forEach { args += input(it, spec.seekMs).toList() }
            args += listOf(
                "-t", laneSeconds(spec.durationMs), "-map", "0:v:0", "-map", "${sources.lastIndex}:a:0",
                "-c", "copy", "-avoid_negative_ts", "make_zero", output.absolutePath
            )
            if (runFfmpeg(args.toTypedArray(), idleTimeoutMs = 40_000L, onTime = onTime) &&
                YouTubeDownloadValidation.isValidMp4(output)) return true
        }
        return false
    }

    private fun durationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Runs FFmpeg and gives up when its output time stops advancing for [idleTimeoutMs]. */
    private suspend fun runFfmpeg(
        arguments: Array<String>, idleTimeoutMs: Long, onTime: (Long) -> Unit = {}
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        val lastAdvance = AtomicLong(SystemClock.elapsedRealtime())
        val lastTime = AtomicLong(0)
        val session = FFmpegKit.executeWithArgumentsAsync(arguments, { completed ->
            done.complete(ReturnCode.isSuccess(completed.returnCode))
        }, null, { stats ->
            val time = stats.time.toLong()
            if (time > lastTime.getAndUpdate { previous -> maxOf(previous, time) }) {
                lastAdvance.set(SystemClock.elapsedRealtime())
            }
            onTime(time)
        })
        return try {
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
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) { done.await() }
            throw cancelled
        }
    }
}
