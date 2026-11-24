package com.clipstudio.app

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

internal data class FacecamRescue(val liftShadows: Boolean = false, val reduceNoise: Boolean = false) {
    fun filters(): String = buildString {
        if (reduceNoise) append("hqdn3d=1.8:1.3:2.8:2.0,")
        if (liftShadows) append("eq=gamma=1.22:gamma_weight=0.65,")
    }
}

internal data class FacecamFrameSignal(val median: Int, val bright: Int, val noise: Float?)

internal fun facecamFrameSignal(width: Int, height: Int, luma: IntArray): FacecamFrameSignal? {
    if (width < 12 || height < 12 || luma.size != width * height) return null
    val histogram = IntArray(256)
    val residuals = IntArray(256)
    val stride = (minOf(width, height) / 100).coerceAtLeast(1)
    var pixels = 0
    var flat = 0
    for (y in 1 until height - 1 step stride) {
        for (x in 1 until width - 1 step stride) {
            val at = y * width + x
            val value = luma[at]
            histogram[value]++
            pixels++
            val left = luma[at - 1]
            val right = luma[at + 1]
            val above = luma[at - width]
            val below = luma[at + width]
            val low = minOf(left, right, above, below)
            val high = maxOf(left, right, above, below)
            // Estimate fine-grained variation in flatter regions without mistaking edges for noise.
            if (high - low > 21 || value !in 22..220) continue
            val center = (left + right + above + below) / 4
            residuals[abs(value - center)]++
            flat++
        }
    }
    fun percentile(bins: IntArray, target: Int): Int {
        var accum = 0
        for ((index, count) in bins.withIndex()) {
            accum += count
            if (accum >= target) return index
        }
        return bins.lastIndex
    }
    return FacecamFrameSignal(percentile(histogram, pixels / 2), percentile(histogram, pixels * 9 / 10),
        if (flat >= maxOf(40, pixels / 7)) percentile(residuals, flat * 3 / 4).toFloat() else null)
}

internal fun chooseFacecamRescue(signals: List<FacecamFrameSignal>): FacecamRescue {
    if (signals.size < 2) return FacecamRescue()
    fun consistent(count: Int) = count >= 2 && count * 2 > signals.size
    return FacecamRescue(
        liftShadows = consistent(signals.count { it.median in 18..78 && it.bright in 42..170 }),
        reduceNoise = consistent(signals.count { (it.noise ?: 0f) >= 5f })
    )
}

internal fun facecamCropAt(camera: Crop, path: List<FacePoint>, timeMs: Long): Crop {
    if (path.isEmpty()) return camera
    val next = path.indexOfFirst { it.timeMs >= timeMs }
    val a = if (next == -1) path.last() else path[(next - 1).coerceAtLeast(0)]
    val b = if (next == -1) a else path[next]
    val fraction = if (a.timeMs == b.timeMs) 0f else
        ((timeMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs)).coerceIn(0f, 1f)
    return Crop(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction, camera.zoom)
}

internal suspend fun inspectFacecam(context: Context, clip: ExportClip): FacecamRescue {
    val retriever = MediaMetadataRetriever()
    val signals = mutableListOf<FacecamFrameSignal>()
    try {
        retriever.setDataSource(context, clip.part.uri)
        val length = clip.end - clip.start
        for (portion in listOf(.18, .50, .82)) {
            currentCoroutineContext().ensureActive()
            val offset = (length * portion).toLong()
            val bitmap = retriever.getScaledFrameAtTime((clip.start + offset) * 1_000,
                MediaMetadataRetriever.OPTION_CLOSEST, 960, 960) ?: continue
            try {
                val camera = facecamCropAt(clip.camera, clip.facePath, offset)
                val window = cropWindow(bitmap.width, bitmap.height, 1.125f, camera)
                val pixels = IntArray(window.width * window.height)
                bitmap.getPixels(pixels, 0, window.width, window.x, window.y, window.width, window.height)
                for (index in pixels.indices) {
                    val argb = pixels[index]
                    pixels[index] = ((argb shr 16 and 255) * 54 + (argb shr 8 and 255) * 183 +
                        (argb and 255) * 19) shr 8
                }
                facecamFrameSignal(window.width, window.height, pixels)?.let(signals::add)
            } finally {
                bitmap.recycle()
            }
        }
    } catch (_: RuntimeException) {
        currentCoroutineContext().ensureActive()
    } finally {
        runCatching { retriever.release() }
    }
    return chooseFacecamRescue(signals)
}
