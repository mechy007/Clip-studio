package com.clipstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.hypot

internal object StudioFraming {
    suspend fun frame(context: Context, uri: Uri, atMs: Long, maxEdge: Int = 960): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(atMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, maxEdge, maxEdge)
        } finally {
            retriever.release()
        }
    }

    suspend fun neighboringFrame(context: Context, uri: Uri, timeMs: Long, forward: Boolean): Long? =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext null
                extractor.selectTrack(track)
                extractor.seekTo((timeMs * 1_000 - 1).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var best: Long? = null
                var nextSyncs = 0
                while (extractor.sampleTime >= 0) {
                    val sample = extractor.sampleTime / 1_000
                    if (sample > timeMs && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 && ++nextSyncs > 1) break
                    if (forward && sample > timeMs) best = minOf(best ?: sample, sample)
                    if (!forward && sample < timeMs) best = maxOf(best ?: sample, sample)
                    if (!extractor.advance()) break
                }
                best
            } catch (_: RuntimeException) {
                null
            } finally {
                extractor.release()
            }
        }

    suspend fun sourceDimensions(context: Context, uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            if (width == null || height == null || width < 2 || height < 2) null
            else if (rotation == 90 || rotation == 270) height to width else width to height
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    suspend fun suggestFacecam(context: Context, part: SourcePart, startMs: Long, endMs: Long): Crop? {
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(.07f)
            .build())
        try {
            val candidates = mutableListOf<Crop>()
            for (tick in listOf(.18f, .50f, .80f)) {
                val bitmap = frame(context, part.uri, startMs + ((endMs - startMs) * tick).toLong()) ?: continue
                try {
                    val faces = withContext(NonCancellable) {
                        detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                    }
                    val face = faces.map { it.boundingBox }.filter { box ->
                        val x = box.exactCenterX() / bitmap.width
                        val y = box.exactCenterY() / bitmap.height
                        box.width() < bitmap.width * .28f && (x < .36f || x > .64f || y < .22f || y > .78f)
                    }.maxByOrNull { it.width() * it.height() } ?: continue
                    val desiredWidth = face.width() * 2.5f
                    val baseWidth = minOf(bitmap.width.toFloat(), bitmap.height * 1.125f)
                    val zoom = (baseWidth / desiredWidth).coerceIn(1f, 4f)
                    val box = cropWindow(bitmap.width, bitmap.height, 1.125f, Crop(0f, 0f, zoom))
                    val x = (face.exactCenterX() - box.width / 2f) / maxOf(1, bitmap.width - box.width)
                    val y = (face.exactCenterY() + face.height() * .20f - box.height / 2f) /
                        maxOf(1, bitmap.height - box.height)
                    candidates += Crop(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), zoom)
                } finally {
                    bitmap.recycle()
                }
            }
            val a = candidates.firstOrNull { candidate ->
                candidates.count { hypot(it.x - candidate.x, it.y - candidate.y) <= .24f } >= 2
            } ?: return null
            return a
        } finally {
            detector.close()
        }
    }
    suspend fun track(
        context: Context, part: SourcePart, startMs: Long, endMs: Long, camera: Crop
    ): List<FacePoint> {
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(.055f).build())
        val path = mutableListOf(FacePoint(0, camera.x, camera.y))
        var found = 0
        try {
            val step = maxOf(350L, (endMs - startMs) / 18)
            for (at in startMs until endMs step step) {
                val bitmap = frame(context, part.uri, at) ?: continue
                try {
                    val prior = path.last()
                    val box = cropWindow(bitmap.width, bitmap.height, 1.125f, camera.copy(x = prior.x, y = prior.y))
                    val targetX = (box.x + box.width / 2f) / bitmap.width
                    val targetY = (box.y + box.height / 2f) / bitmap.height
                    fun distance(face: android.graphics.Rect) =
                        hypot(face.exactCenterX() / bitmap.width - targetX, face.exactCenterY() / bitmap.height - targetY)
                    val face = withContext(NonCancellable) {
                        detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                    }.map { it.boundingBox }.filter { it.width() <= bitmap.width * .33f }
                        .minByOrNull(::distance)?.takeIf { distance(it) < .40f } ?: continue
                    val x = (face.exactCenterX() - box.width / 2f) / maxOf(1, bitmap.width - box.width)
                    val y = (face.exactCenterY() + face.height() * .16f - box.height / 2f) /
                        maxOf(1, bitmap.height - box.height)
                    val smoothX = prior.x * .28f + x.coerceIn(0f, 1f) * .72f
                    val smoothY = prior.y * .28f + y.coerceIn(0f, 1f) * .72f
                    val point = FacePoint(at - startMs, smoothX, smoothY)
                    if (point.timeMs == 0L) path[0] = point else path += point
                    found++
                } finally { bitmap.recycle() }
            }
        } finally { detector.close() }
        return if (found >= 2) path else emptyList()
    }

}
