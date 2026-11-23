package com.clipstudio.app

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal const val DOWNLOAD_CHUNK_MS = 120_000L

internal fun RecordingPartWindow.downloadChunks(): List<RecordingPartWindow> =
    generateSequence(startMs) { start ->
        (start + minOf(DOWNLOAD_CHUNK_MS, endMs - start)).takeIf { it < endMs }
    }.map { start -> RecordingPartWindow(start, start + minOf(DOWNLOAD_CHUNK_MS, endMs - start)) }.toList()

internal data class GuidedDownloadProgress(val percent: Int, val etaMs: Long?, val assembling: Boolean = false)

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

internal class GuidedDownloadFiles(val directory: File) {
    private val settings = AtomicFile(File(directory, "plan.json"))
    val output = File(directory, "part.mp4")
    val staging = File(directory, "writing.mp4")
    val concatList = File(directory, "chunks.ffconcat")

    fun chunk(index: Int) = File(directory, "chunk-$index.mp4")

    fun load(start: Long): GuidedDownloadPlan? {
        val data = try {
            JSONObject(String(settings.readFully(), Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            return null
        }
        val duration = data.getLong("recordingEnd")
        val range = requireNotNull(nextRecordingPart(start, duration))
        return GuidedDownloadPlan(range, duration, data.getString("format"))
    }

    fun save(plan: GuidedDownloadPlan) {
        val data = JSONObject().put("recordingEnd", plan.recordingEndMs).put("format", plan.format)
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
