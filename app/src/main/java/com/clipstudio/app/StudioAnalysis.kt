package com.clipstudio.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

internal object StudioAnalysis {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = SAMPLE_RATE * 30
    const val MAX_CAPTION_DURATION_MS = 120_000L

    suspend fun highlights(context: Context, part: SourcePart): List<Highlight> = withContext(Dispatchers.IO) {
        val pcm = File.createTempFile("studio-signals-", ".pcm", context.cacheDir)
        try {
            val visual = try {
                SceneChangeAnalyzer.analyze(context, part.uri, part.durationMs)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                emptyList()
            }
            val hasAudio = StudioMedia.decodeAudio(context, part, pcm, 0, part.durationMs)
            val energy = if (hasAudio) readEnergy(pcm) else emptyList()
            val reactions = if (hasAudio) AudioEventClassifier.analyze(context, pcm) else emptyList()
            rankHighlights(part.durationMs, energy, reactions, visual)
        } finally {
            pcm.delete()
        }
    }

    suspend fun captions(context: Context, part: SourcePart, startMs: Long, endMs: Long, language: String?) =
        withContext(Dispatchers.IO) {
            require(endMs - startMs in 1..MAX_CAPTION_DURATION_MS) { "Choose a clip of up to 2 minutes for captions." }
            val pcm = File.createTempFile("studio-speech-", ".pcm", context.cacheDir)
            try {
                if (!StudioMedia.decodeAudio(context, part, pcm, startMs, endMs) || pcm.length() < 4) {
                    return@withContext emptyList<TranscriptSegment>()
                }
                WhisperModelManager.ensureModel(context) { _, _ -> }
                try {
                    WhisperEngine.beginSession(context)
                    val transcript = mutableListOf<TranscriptSegment>()
                    var index = 0L
                    pcm.dataInput().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val audio = input.readFloats(CHUNK_SAMPLES)
                            if (audio.isEmpty()) break
                            if (audio.any { abs(it) > .004f }) {
                                val offset = startMs + index * 30_000L
                                transcript += WhisperEngine.transcribe16Khz(context, audio, language).map { it.shiftedBy(offset) }
                            }
                            index++
                        }
                    }
                    transcript
                } finally {
                    withContext(NonCancellable) { WhisperEngine.endSession() }
                }
            } finally {
                pcm.delete()
            }
        }

    private suspend fun readEnergy(pcm: File): List<AudioEnergyWindow> = buildList {
        pcm.dataInput().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val audio = input.readFloats(SAMPLE_RATE)
                if (audio.isEmpty()) break
                val start = size * 1_000L
                val rms = sqrt(audio.sumOf { it.toDouble() * it } / audio.size).toFloat()
                val peak = audio.maxOf { abs(it) }
                add(AudioEnergyWindow(start, start + maxOf(1, audio.size * 1_000L / SAMPLE_RATE), rms, peak))
            }
        }
    }

    private fun File.dataInput() = DataInputStream(inputStream().buffered())

    private fun DataInputStream.readFloats(limit: Int): FloatArray {
        val samples = FloatArray(limit)
        var read = 0
        while (read < limit) {
            val sample = try { readFloat() } catch (_: java.io.EOFException) { break }
            samples[read++] = sample.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        }
        return if (read == limit) samples else samples.copyOf(read)
    }
}
