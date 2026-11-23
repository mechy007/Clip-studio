package com.clipstudio.app

import kotlin.math.roundToLong

internal enum class Layout(val label: String) {
    Split("50/50 facecam"), Fill("Vertical fill"), Fit("Vertical fit"), Landscape("Landscape"), Square("Square")
}

internal data class Crop(val x: Float, val y: Float, val zoom: Float = 1f)

internal data class SegmentRow(val name: String, val startMs: Long, val endMs: Long)

internal fun parseSegmentRow(row: String): SegmentRow? {
    val fields = row.trim().split(',')
    if (fields.size != 3) return null
    val name = fields[0].trim('"')
    val start = fields[1].toDoubleOrNull() ?: return null
    val end = fields[2].toDoubleOrNull() ?: return null
    if (!name.matches(Regex("part-[0-9]{5,}\\.mp4")) ||
        !start.isFinite() || !end.isFinite() || start < 0 || end <= start
    ) return null
    return SegmentRow(name, (start * 1000).roundToLong(), (end * 1000).roundToLong())
}

internal data class FacePoint(val timeMs: Long, val x: Float, val y: Float)

internal fun trackingCoordinate(points: List<FacePoint>, fallback: Float, coordinate: (FacePoint) -> Float): String {
    val path = points.filter { it.timeMs >= 0 && coordinate(it).isFinite() }
        .sortedBy { it.timeMs }.distinctBy { it.timeMs }
    if (path.isEmpty()) return fallback.coerceIn(0f, 1f).toString()
    fun coordinateOf(point: FacePoint) = coordinate(point).coerceIn(0f, 1f)
    var expression = coordinateOf(path.last()).toString()
    for ((a, b) in path.zipWithNext().asReversed()) {
        val t = a.timeMs / 1000.0
        val next = b.timeMs / 1000.0
        val from = coordinateOf(a)
        val shift = coordinateOf(b) - from
        expression = "if(lt(t,$next),$from+($shift)*clip((t-$t)/($next-$t),0,1),$expression)"
    }
    return expression
}

internal fun videoFilter(
    layout: Layout, camera: Crop, game: Crop, resolution: Int = 1080, facePath: List<FacePoint> = emptyList(),
    enhanceFacecam: Boolean = false, qualityScale: Boolean = false
): String {
    val (outputWidth, outputHeight) = layout.dimensions(resolution)
    val panelHeight = outputHeight / 2
    val qualityFlags = if (qualityScale) ":flags=lanczos" else ""
    fun fill(
        width: Int, height: Int, crop: Crop, path: List<FacePoint> = emptyList(), scaleFlags: String = ""
    ): String {
        val ratio = "$width/$height"
        val zoom = crop.zoom.coerceIn(1f, 4f)
        val x = trackingCoordinate(path, crop.x) { it.x }
        val y = trackingCoordinate(path, crop.y) { it.y }
        val w = "max(2,trunc(min(iw,ih*$ratio)/$zoom/2)*2)"
        val h = "max(2,trunc(min(ih,iw/($ratio))/$zoom/2)*2)"
        return "crop=w='$w':h='$h':x='(iw-ow)*$x':y='(ih-oh)*$y'," +
            "scale=$width:$height$scaleFlags,setsar=1"
    }
    val clarity = if (enhanceFacecam) ",unsharp=lx=5:ly=5:la=0.45:ca=0" else ""
    return when (layout) {
        Layout.Split -> "[0:v]split=2[a][b];[a]${fill(outputWidth, panelHeight, camera, facePath, ":flags=lanczos")}$clarity[top];" +
            "[b]${fill(outputWidth, panelHeight, game, scaleFlags = qualityFlags)}[bottom];[top][bottom]vstack[out]"
        Layout.Fill, Layout.Square -> "[0:v]${fill(outputWidth, outputHeight, game, scaleFlags = qualityFlags)}[out]"
        Layout.Fit, Layout.Landscape -> "[0:v]scale=$outputWidth:$outputHeight:force_original_aspect_ratio=decrease:" +
            "force_divisible_by=2$qualityFlags,pad=$outputWidth:$outputHeight:(ow-iw)/2:(oh-ih)/2,setsar=1[out]"
    }
}

internal fun Layout.dimensions(width: Int): Pair<Int, Int> =
    when (this) {
        Layout.Landscape -> width * 16 / 9 to width
        Layout.Square -> width to width
        else -> width to width * 16 / 9
    }

internal data class CropWindow(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun cropWindow(imageWidth: Int, imageHeight: Int, aspect: Float, crop: Crop): CropWindow {
    val width = (minOf(imageWidth.toFloat(), imageHeight * aspect) / crop.zoom).toInt().coerceIn(1, imageWidth)
    val height = (minOf(imageHeight.toFloat(), imageWidth / aspect) / crop.zoom).toInt().coerceIn(1, imageHeight)
    return CropWindow(
        ((imageWidth - width) * crop.x.coerceIn(0f, 1f)).toInt(),
        ((imageHeight - height) * crop.y.coerceIn(0f, 1f)).toInt(),
        width, height
    )
}

internal fun facecamCropPixels(sourceWidth: Int, sourceHeight: Int, zoom: Float): Pair<Int, Int> {
    val window = cropWindow(sourceWidth, sourceHeight, 1080f / 960f, Crop(0f, 0f, zoom.coerceIn(1f, 4f)))
    fun even(value: Int) = (value / 2 * 2).coerceAtLeast(2)
    return even(window.width) to even(window.height)
}
