package com.clipstudio.app

import java.util.Locale
import java.io.File
import kotlin.math.abs

internal fun adjacent(first: SourcePart, second: SourcePart): Boolean =
    !first.joined && !second.joined &&
        (first.formatId != null && first.formatId == second.formatId ||
            first.uri.scheme == "file" && second.uri.scheme == "file" &&
            first.uri.path?.let(::File)?.parent == second.uri.path?.let(::File)?.parent &&
            first.uri.lastPathSegment?.startsWith("part-") == true && second.uri.lastPathSegment?.startsWith("part-") == true) &&
        second.offsetMs > first.offsetMs && abs(first.nextOffsetMs - second.offsetMs) <= 750

internal fun audioFinishFilter(audio: AudioFinish, tracks: Int, durationMs: Long): String? {
    if (tracks == 0 || durationMs <= 0L) return null
    val mix = audio.mixTracks && tracks >= 2
    val effects = buildList {
        if (audio.normalize) add("loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000")
        if (audio.fade) {
            val fade = minOf(400L, durationMs / 3L)
            fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
            add("afade=t=in:st=0:d=${seconds(fade)}")
            add("afade=t=out:st=${seconds(durationMs - fade)}:d=${seconds(fade)}")
        }
        if (mix) add("alimiter=limit=0.95:level=false:latency=true")
    }
    if (!mix && effects.isEmpty()) return null
    val chain = effects.joinToString(",")
    if (!mix) return "[0:a:0]$chain[finishedAudio]"
    val levels = "${audio.voice.coerceIn(0f, 1f)} ${audio.game.coerceIn(0f, 1f)}"
    return "[0:a:0][0:a:1]amix=inputs=2:duration=longest:normalize=0:weights='$levels',$chain[finishedAudio]"
}
