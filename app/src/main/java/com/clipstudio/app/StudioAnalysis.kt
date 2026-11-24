package com.clipstudio.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    suspend fun highlights(
        context: Context, part: SourcePart, mode: HighlightMode, progress: suspend (String) -> Unit
    ): List<Highlight> = withContext(Dispatchers.IO) {
        val pcm = File.createTempFile("studio-signals-", ".pcm", context.cacheDir)
        try {
            progress("Reading source audio")
            val hasAudio = StudioMedia.decodeAudio(context, part, pcm, 0, part.durationMs)
            val energy = if (hasAudio) readEnergy(pcm) else emptyList()
            val speech = mutableListOf<TranscriptSegment>()
            if (energy.any { it.rms >= .0015f && it.peak >= .004f }) {
                try {
                    transcribePcm(context, pcm, startMs = 0, language = null, wordTiming = false,
                        progress = { position ->
                            val percent = (position.coerceAtMost(part.durationMs) * 100.0 / part.durationMs).toInt()
                            progress("Whisper speech scan · $percent%")
                        }, collect = { speech.addAll(it) })
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    android.util.Log.w("ClipStudioHighlights", "Speech scan was incomplete", failure)
                } catch (failure: LinkageError) {
                    android.util.Log.w("ClipStudioHighlights", "Offline Whisper unavailable", failure)
                }
            }

            progress("Checking motion and audio cues")
            val visual = try {
                SceneChangeAnalyzer.analyze(context, part.uri, part.durationMs)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                emptyList()
            }
            val reactions = if (hasAudio) {
                try {
                    AudioEventClassifier.analyze(context, pcm)
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    emptyList()
                } catch (_: LinkageError) {
                    emptyList()
                }
            } else emptyList()
            speechFirstHighlights(part.durationMs, speech, energy, reactions, visual, mode)
        } finally {
            pcm.delete()
        }
    }

    suspend fun captions(context: Context, part: SourcePart, startMs: Long, endMs: Long, language: String?, audioTrack: Int) =
        withContext(Dispatchers.IO) {
            require(endMs - startMs in 1..MAX_CAPTION_DURATION_MS) { "Choose a clip of up to 2 minutes for captions." }
            val pcm = File.createTempFile("studio-speech-", ".pcm", context.cacheDir)
            try {
                check(StudioMedia.decodeAudio(context, part, pcm, startMs, endMs, audioTrack)) {
                    "Could not read audio track ${audioTrack + 1} for captions."
                }
                if (pcm.length() < 4) return@withContext emptyList<TranscriptSegment>()
                val transcript = mutableListOf<TranscriptSegment>()
                transcribePcm(context, pcm, startMs, language, wordTiming = true, progress = {}) { transcript.addAll(it) }
                transcript.toList()
            } finally {
                pcm.delete()
            }
        }

    private suspend fun transcribePcm(
        context: Context, pcm: File, startMs: Long, language: String?, wordTiming: Boolean,
        progress: suspend (Long) -> Unit, collect: (List<TranscriptSegment>) -> Unit
    ) {
        progress(0L)
        WhisperModelManager.ensureModel(context) { _, _ -> }
        WhisperEngine.session(context) {
            var consumedMs = 0L
            pcm.dataInput().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val audio = input.readFloats(CHUNK_SAMPLES)
                    if (audio.isEmpty()) break
                    if (audio.any { abs(it) > .004f }) {
                        val offset = startMs + consumedMs
                        collect(WhisperEngine.transcribe16Khz(context, audio, language, wordTiming).map { it.shiftedBy(offset) })
                    }
                    consumedMs += audio.size * 1000L / SAMPLE_RATE
                    progress(consumedMs)
                }
            }
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
