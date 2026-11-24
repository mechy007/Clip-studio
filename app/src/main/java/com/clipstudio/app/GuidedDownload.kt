package com.clipstudio.app

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.math.BigDecimal
import java.math.RoundingMode

internal enum class DownloadQuality(val maxHeight: Int, val videoKbps: Int) {
    LOW(360, 900), MEDIUM(480, 1_400), HIGH(720, 2_600), FULL_HD(1080, 4_500), QHD(1440, 8_000);

    val label: String get() = "${maxHeight}p"

    companion object {
        fun fromHeight(height: Int): DownloadQuality? = entries.firstOrNull { it.maxHeight == height }
    }
}

internal data class RecordingDownload(val startMs: Long, val quality: DownloadQuality)

internal fun recordingFormatSelector(height: Int): String {
    val video = "[protocol=https][height<=$height]"
    val audio = "ba[protocol=https]"
    val qhd = if (height > DownloadQuality.FULL_HD.maxHeight) {
        // Preserve the requested resolution when the higher-rung stream uses VP9 or AV1.
        val aboveFullHd = "$video[height>${DownloadQuality.FULL_HD.maxHeight}]"
        "bv$aboveFullHd+$audio/b$aboveFullHd/"
    } else ""
    return qhd + "bv${video}[vcodec^=avc1]+${audio}[acodec^=mp4a]/" +
        "b${video}[ext=mp4][vcodec^=avc1][acodec^=mp4a]/bv$video+$audio/b$video"
}

internal const val DOWNLOAD_CHUNK_MS = 120_000L

internal const val DOWNLOAD_DISK_RESERVE_BYTES = 24L * 1024 * 1024

internal data class DownloadEstimate(val transferBytes: Long, val peakStorageBytes: Long)

internal fun downloadOutputBytes(durationMs: Long, videoKbps: Int): Long =
    durationMs.coerceAtLeast(0) * (videoKbps + 128L) / 8

internal fun downloadStagingBytes(durationMs: Long, videoKbps: Int): Long =
    downloadOutputBytes(durationMs, videoKbps) * 112 / 100

internal fun estimateRecordingDownload(durationMs: Long, quality: DownloadQuality): DownloadEstimate {
    val transfer = downloadOutputBytes(durationMs, quality.videoKbps)
    val staged = downloadStagingBytes(durationMs, quality.videoKbps)
    return DownloadEstimate(transfer, staged * 2 + DOWNLOAD_DISK_RESERVE_BYTES)
}

internal fun estimateDownloadQueue(estimates: Iterable<DownloadEstimate>): DownloadEstimate {
    var retained = 0L
    var peak = 0L
    for (estimate in estimates) {
        peak = maxOf(peak, retained + estimate.peakStorageBytes)
        retained += estimate.transferBytes
    }
    return DownloadEstimate(retained, peak)
}

internal fun RecordingPartWindow.downloadChunks(): List<RecordingPartWindow> =
    generateSequence(startMs) { start ->
        (start + minOf(DOWNLOAD_CHUNK_MS, endMs - start)).takeIf { it < endMs }
    }.map { start -> RecordingPartWindow(start, start + minOf(DOWNLOAD_CHUNK_MS, endMs - start)) }.toList()

internal data class GuidedDownloadProgress(
    val percent: Int, val etaMs: Long?, val assembling: Boolean = false, val newAttempt: Boolean = false,
    val bytesFetched: Long? = null
)

internal fun guidedDownloadProgress(
    totalMs: Long, savedMs: Long, processedMs: Long, elapsedMs: Long
): GuidedDownloadProgress {
    val completed = processedMs.coerceIn(0, totalMs)
    val newWork = completed - savedMs
    val eta = if (elapsedMs >= 5_000 && newWork >= 1_000 && completed < totalMs) {
        ((totalMs - completed).toDouble() * elapsedMs / newWork).toLong()
    } else null
    return GuidedDownloadProgress((100.0 * completed / totalMs).toInt().coerceIn(0, 100), eta)
}

internal data class GuidedDownloadPlan(
    val range: RecordingPartWindow, val recordingEndMs: Long, val format: String
)

internal fun savedRecordingRange(start: Long, recordingEnd: Long, savedEnd: Long?, requestedEnd: Long?): RecordingPartWindow? {
    if (start < 0 || recordingEnd <= start) return null
    val end = minOf(recordingEnd, savedEnd ?: recordingEnd, requestedEnd ?: recordingEnd)
    return if (end > start) nextRecordingPart(start, end) else null
}

internal fun parseRecordingDurationMs(
    durationSeconds: Double?, durationText: String?, formatDurations: List<Double?> = emptyList()
): Long? {
    fun toMillis(seconds: Double?): Long? {
        if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return null
        return runCatching {
            BigDecimal.valueOf(seconds).multiply(BigDecimal.valueOf(1000))
                .setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()?.takeIf { it > 0L }
    }
    toMillis(durationSeconds)?.let { return it }
    formatDurations.firstNotNullOfOrNull(::toMillis)?.let { return it }

    val fields = durationText?.trim()?.takeIf { it.isNotEmpty() }?.split(':') ?: return null
    if (fields.size !in 1..3) return null
    val whole = fields.dropLast(1).map { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: return null }
    val last = runCatching { BigDecimal(fields.last()) }.getOrNull() ?: return null
    if (last.signum() < 0 || (fields.size > 1 && last >= BigDecimal.valueOf(60))) return null
    if (fields.size == 3 && whole[1] >= 60) return null
    val totalSeconds = when (fields.size) {
        1 -> last
        2 -> BigDecimal.valueOf(whole[0]).multiply(BigDecimal.valueOf(60)).add(last)
        else -> BigDecimal.valueOf(whole[0]).multiply(BigDecimal.valueOf(3600))
            .add(BigDecimal.valueOf(whole[1]).multiply(BigDecimal.valueOf(60))).add(last)
    }
    return runCatching {
        totalSeconds.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()?.takeIf { it > 0L }
}

internal fun validatedGuidedDownloadPlan(
    start: Long, recordingEnd: Long, partEnd: Long, format: String, requestedEnd: Long? = null
): GuidedDownloadPlan? {
    if (start < 0 || recordingEnd <= start || partEnd <= start ||
        !format.matches(Regex("[A-Za-z0-9_-]+(\\+[A-Za-z0-9_-]+)?"))) return null
    val range = savedRecordingRange(start, recordingEnd, partEnd, requestedEnd) ?: return null
    return GuidedDownloadPlan(range, recordingEnd, format)
}

internal class GuidedDownloadFiles(val directory: File) {
    private val settings = AtomicFile(File(directory, "plan.json"))
    val output = File(directory, "part.mp4")
    val staging = File(directory, "writing.mp4")
    val concatList = File(directory, "chunks.ffconcat")

    fun chunk(index: Int) = File(directory, "chunk-$index.mp4")

    fun load(start: Long, requestedEnd: Long? = null): GuidedDownloadPlan? {
        val data = try {
            JSONObject(String(settings.readFully(), Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: Exception) {
            settings.delete()
            return null
        }
        val plan = runCatching {
            val duration = data.getLong("recordingEnd")
            validatedGuidedDownloadPlan(start, duration, data.optLong("partEnd", duration),
                data.getString("format"), requestedEnd)
        }.getOrNull()
        if (plan == null) settings.delete()
        return plan
    }

    fun save(plan: GuidedDownloadPlan) {
        val data = JSONObject().put("recordingEnd", plan.recordingEndMs).put("partEnd", plan.range.endMs).put("format", plan.format)
        val stream = settings.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            settings.finishWrite(stream)
        } catch (error: Exception) {
            settings.failWrite(stream)
            throw error
        }
    }
}
