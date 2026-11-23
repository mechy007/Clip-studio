package com.clipstudio.app

import kotlin.math.roundToLong

internal enum class Layout(val label: String) {
    Split("50/50 facecam"), Fill("Vertical fill"), Fit("Vertical fit"), Landscape("Landscape")
}

internal data class Crop(val x: Float, val y: Float, val zoom: Float = 1f)

internal data class SegmentRow(val name: String, val startMs: Long)

internal fun parseSegmentRow(row: String): SegmentRow? {
    val fields = row.trim().split(',')
    if (fields.size != 3) return null
    val name = fields[0].trim('"')
    val start = fields[1].toDoubleOrNull() ?: return null
    val end = fields[2].toDoubleOrNull() ?: return null
    if (!name.matches(Regex("part-[0-9]{5,}\\.mp4")) ||
        !start.isFinite() || !end.isFinite() || start < 0 || end <= start
    ) return null
    return SegmentRow(name, (start * 1000).roundToLong())
}

internal fun videoFilter(layout: Layout, camera: Crop, game: Crop): String {
    fun fill(width: Int, height: Int, crop: Crop): String {
        val ratio = "$width/$height"
        val zoom = crop.zoom.coerceIn(1f, 4f)
        val x = crop.x.coerceIn(0f, 1f)
        val y = crop.y.coerceIn(0f, 1f)
        val w = "max(2,trunc(min(iw,ih*$ratio)/$zoom/2)*2)"
        val h = "max(2,trunc(min(ih,iw/($ratio))/$zoom/2)*2)"
        return "crop=w='$w':h='$h':x='(iw-ow)*$x':y='(ih-oh)*$y'," +
            "scale=$width:$height,setsar=1"
    }
    return when (layout) {
        Layout.Split -> "[0:v]split=2[a][b];[a]${fill(1080, 960, camera)}[top];" +
            "[b]${fill(1080, 960, game)}[bottom];[top][bottom]vstack[out]"
        Layout.Fill -> "[0:v]${fill(1080, 1920, game)}[out]"
        Layout.Fit -> "[0:v]scale=1080:1920:force_original_aspect_ratio=decrease:" +
            "force_divisible_by=2,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,setsar=1[out]"
        Layout.Landscape -> "[0:v]scale=1920:1080:force_original_aspect_ratio=decrease:" +
            "force_divisible_by=2,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1[out]"
    }
}
