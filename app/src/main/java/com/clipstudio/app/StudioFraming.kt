package com.clipstudio.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
    suspend fun frame(context: Context, uri: Uri, atMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(atMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, 960, 960)
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
}
