package com.clipstudio.app

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build

internal data class RenderStats(
    val encoder: String, val preparationMs: Long, val renderMs: Long, val saveMs: Long,
    val retried: Boolean
)

internal object FastMedia {
    fun hardwareAvc(width: Int, height: Int): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
                codec.isEncoder && codec.isHardwareAccelerated && runCatching {
                    val capabilities = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    capabilities.colorFormats.any {
                        it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    } && capabilities.videoCapabilities.areSizeAndRateSupported(width, height, 30.0)
                }.getOrDefault(false)
            }?.name
        }.getOrNull()
    }

    fun canCopyTrim(context: Context, uri: Uri, startMs: Long): Boolean =
        inspect(context, uri) { extractor, formats ->
            if (formats.size !in 1..2 || formats.first().mime() != MediaFormat.MIMETYPE_VIDEO_AVC ||
                formats.drop(1).any { it.mime() != MediaFormat.MIMETYPE_AUDIO_AAC }) return@inspect false
            extractor.selectTrack(0)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            extractor.sampleTime in startMs * 1000..(startMs * 1000 + 499) &&
                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        } ?: false

    fun canCopyJoin(context: Context, first: SourcePart, next: SourcePart): Boolean {
        if (first.formatId == null || first.formatId != next.formatId ||
            first.uri.path?.substringBeforeLast('/') != next.uri.path?.substringBeforeLast('/') ||
            first.nextOffsetMs != next.offsetMs) return false
        val a = signature(context, first.uri) ?: return false
        val b = signature(context, next.uri) ?: return false
        return a == b
    }

    private fun signature(context: Context, uri: Uri): List<List<Any?>>? =
        inspect(context, uri) { extractor, formats ->
            if (formats.size != 2 || formats[0].mime() != MediaFormat.MIMETYPE_VIDEO_AVC ||
                formats[1].mime() != MediaFormat.MIMETYPE_AUDIO_AAC) return@inspect null
            extractor.selectTrack(0)
            if (extractor.sampleTime !in 0..1000 ||
                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) return@inspect null
            formats.map { format ->
                listOf(format.mime()) + formatFields.map { key ->
                    if (format.containsKey(key)) format.getInteger(key) else null
                } + (0..2).map { index ->
                    format.getByteBuffer("csd-$index")?.duplicate()?.let { bytes ->
                        List(bytes.remaining()) { bytes.get() }
                    }
                }
            }.takeIf { it.all { track -> track[formatFields.size + 1] != null } }
        }

    private val formatFields = listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION,
        MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_PROFILE)

    private fun MediaFormat.mime(): String? = getString(MediaFormat.KEY_MIME)

    private inline fun <T> inspect(context: Context, uri: Uri, block: (MediaExtractor, List<MediaFormat>) -> T): T? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            block(extractor, (0 until extractor.trackCount).map(extractor::getTrackFormat))
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
