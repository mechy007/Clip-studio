package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedWorkflowTest {
    @Test fun selectsVisualActionWithoutAudioOrSpeech() {
        val moments = rankHighlights(90_000, emptyList(), emptyList(),
            listOf(VisualSample(12_000, .03f), VisualSample(30_000, .52f), VisualSample(60_000, .44f)))
        assertEquals(listOf(22_000L, 52_000L), moments.map { it.startMs })
        assertTrue(moments.all { it.reason == "Sudden visual activity" })
    }

    @Test fun sustainedNoiseDoesNotCreateArtificialPeaks() {
        val energy = (0 until 30).map { AudioEnergyWindow(it * 1000L, (it + 1) * 1000L, .22f, .40f) }
        assertTrue(rankHighlights(30_000, energy, emptyList(), emptyList()).isEmpty())
    }

    @Test fun correlatedReactionWinsAndOverlappingMomentsAreRemoved() {
        val reactions = listOf(AudioEventWindow(12_000, 13_000, .95f, 0f, 0f))
        val visual = listOf(VisualSample(14_000, .50f), VisualSample(42_000, .45f))
        val moments = rankHighlights(70_000, emptyList(), reactions, visual)
        assertEquals(2, moments.size)
        assertEquals("Laughter detected", moments.first().reason)
    }

    @Test fun trimsAndRebasesCaptionWordTimesForTheExport() {
        val transcript = listOf(TranscriptSegment(400, 1400, "before after",
            listOf(TimedWord(400, 800, "before"), TimedWord(950, 1400, "after"))))
        val result = CaptionTrack.fromTranscript(transcript, 850, 1200)
        assertEquals("after", result.single().text)
        assertEquals(TimedWord(100, 350, "after"), result.single().words.single())
        val pop = AssKaraokeTrack.render(result, CaptionPreset.POP, 720, 1280)
        assertTrue(pop.contains("0:00:00.10,0:00:00.35"))
        assertTrue(pop.contains("\\t(0,120"))
        assertFalse(pop.contains("before"))
    }

    @Test fun animatesCaptionsWhenWordTimestampsAreMissing() {
        val transcript = listOf(TranscriptSegment(12_000, 13_200, "one two three"))
        val cues = CaptionTrack.fromTranscript(transcript, 11_500, 14_000)
        val frames = AssKaraokeTrack.frames(cues)
        assertEquals(listOf(0, 1, 2), frames.map { it.activeIndex })
        assertEquals(listOf(500L, 900L, 1300L), frames.map { it.startMs })
        assertEquals(1700L, frames.last().endMs)
        val karaoke = AssKaraokeTrack.render(cues, CaptionPreset.KARAOKE, 1080, 1920)
        assertTrue(karaoke.contains("0:00:00.50,0:00:00.90"))
        assertTrue(karaoke.contains("\\c&H1FD2FF&"))
        val pop = AssKaraokeTrack.render(cues, CaptionPreset.POP, 1080, 1920)
        assertTrue(pop.contains("\\t(0,120"))
    }

    @Test fun retainsTextWhenTheCueIsTooShortToAnimateEachWord() {
        val cue = CaptionCue(300L, 360L, "one two three")
        val frame = AssKaraokeTrack.frames(listOf(cue)).single()
        assertEquals(-1, frame.activeIndex)
        assertEquals(listOf(cue.text), frame.words)
    }

    @Test fun rendersEqual720PanelsAndMapsPreviewCropWithinBounds() {
        val filter = videoFilter(Layout.Split, Crop(0f, 1f, 3f), Crop(1f, 0f), resolution = 720)
        assertEquals(2, Regex("scale=720:640").findAll(filter).count())
        val framing = cropWindow(1920, 1080, 1.125f, Crop(1f, 1f, 3f))
        assertEquals(1920, framing.x + framing.width)
        assertEquals(1080, framing.y + framing.height)
    }
}
