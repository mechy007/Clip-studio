package com.clipstudio.app

internal data class CaptionMemo(
    val part: SourcePart, val start: Long, val end: Long, val language: String?,
    val words: List<TranscriptSegment>, val audioTrack: Int
) {
    fun covers(source: SourcePart, from: Long, to: Long, lang: String?, track: Int) =
        part == source && language == lang && audioTrack == track && start <= from && end >= to
}

internal data class TrackingMemo(
    val part: SourcePart, val start: Long, val end: Long, val camera: Crop, val path: List<FacePoint>
)

internal fun focusAt(points: List<FacePoint>, time: Long): FacePoint? {
    val next = points.indexOfFirst { it.timeMs >= time }
    if (points.isEmpty()) return null
    if (next < 1) return (if (next == 0) points.first() else points.last()).copy(timeMs = time)
    val a = points[next - 1]
    val b = points[next]
    val f = (time - a.timeMs).toFloat() / (b.timeMs - a.timeMs)
    return FacePoint(time, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
}

internal fun rebaseTracking(points: List<FacePoint>, from: Long, to: Long): List<FacePoint> {
    if (points.isEmpty() || to <= from) return emptyList()
    val first = requireNotNull(focusAt(points, from))
    val last = requireNotNull(focusAt(points, to))
    return (listOf(first) + points.filter { it.timeMs in (from + 1) until to } + last)
        .map { it.copy(timeMs = it.timeMs - from) }
}
