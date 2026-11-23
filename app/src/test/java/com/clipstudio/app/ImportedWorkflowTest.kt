package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedWorkflowTest {
    @Test fun guidedRecordingStartsAtTheLastCompletedBoundary() {
        val duration = 75 * 60_000L
        val first = requireNotNull(nextRecordingPart(0, duration))
        val second = requireNotNull(nextRecordingPart(first.endMs, duration))
        val tail = requireNotNull(nextRecordingPart(second.endMs, duration))
        assertEquals(RecordingPartWindow(0, 30 * 60_000L), first)
        assertEquals(RecordingPartWindow(first.endMs, 60 * 60_000L), second)
        assertEquals(15 * 60_000L, tail.lengthMs)
        assertEquals(duration, tail.endMs)
        assertEquals(null, nextRecordingPart(tail.endMs, duration))
    }

    @Test fun exactlyThirtyMinutesNeedsOnlyOnePart() {
        val part = requireNotNull(nextRecordingPart(0, RECORDING_PART_MS))
        assertEquals(RECORDING_PART_MS, part.lengthMs)
        assertEquals(null, nextRecordingPart(part.endMs, RECORDING_PART_MS))
    }

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

    @Test fun bouncesOnlyTheActiveWordAndClampsTheMotionToItsDuration() {
        val cue = CaptionCue(200, 800, "one two",
            listOf(TimedWord(200, 260, "one"), TimedWord(260, 800, "two")))
        val script = AssKaraokeTrack.render(listOf(cue), CaptionPreset.BOUNCE, 1080, 1920)
        val events = script.lineSequence().filter { it.startsWith("Dialogue:") }.toList()
        assertTrue(events[0].contains("\\t(0,30,\\fscx120\\fscy120)\\t(30,60,\\fscx100\\fscy100)"))
        assertTrue(events[0].endsWith("one{\\r} two"))
        assertTrue(events[1].contains(",,one {\\t(0,50"))
    }

    @Test fun wipesTheTimedWordAndUsesHighlightForBidirectionalText() {
        val cue = CaptionCue(25, 318, "one two",
            listOf(TimedWord(25, 183, "one"), TimedWord(183, 318, "two")))
        val script = AssKaraokeTrack.render(listOf(cue), CaptionPreset.WIPE, 720, 1280)
        assertTrue(script.contains("{\\kf16\\c&H1FD2FF&}one{\\r} {\\k0}two"))
        assertTrue(script.contains("{\\k0}one {\\kf13\\c&H1FD2FF&}two"))
        val rtl = AssKaraokeTrack.render(listOf(CaptionCue(0, 900, "مرحبا بالعالم")),
            CaptionPreset.WIPE, 720, 1280)
        assertFalse(rtl.contains("\\kf"))
        assertTrue(rtl.contains("{\\c&H1FD2FF&}مرحبا"))
    }

    @Test fun captionPlacementPreservesTheExistingDefaultAndSupportsUpperAndCenter() {
        val cues = listOf(CaptionCue(0, 500, "sample"))
        val lower = AssKaraokeTrack.render(cues, CaptionPreset.CLEAN, 1080, 1920)
        val center = AssKaraokeTrack.render(cues, CaptionPreset.CLEAN, 1080, 1920, CaptionPosition.CENTER)
        val upper = AssKaraokeTrack.render(cues, CaptionPreset.CLEAN, 1080, 1920, CaptionPosition.UPPER)
        assertTrue(lower.lineSequence().first { it.startsWith("Style:") }.endsWith(",2,86,86,384,1"))
        assertTrue(center.lineSequence().first { it.startsWith("Style:") }.endsWith(",5,86,86,0,1"))
        assertTrue(upper.lineSequence().first { it.startsWith("Style:") }.endsWith(",8,86,86,192,1"))
    }

    @Test fun customTextNeedsNoTranscriptAndFillsOnlyTheSelectedTrim() {
        val cue = CaptionTrack.custom("  My custom caption  ", 1200).single()
        assertEquals(CaptionCue(0, 1200, "My custom caption"), cue)
        assertTrue(CaptionTrack.custom("  ", 1200).isEmpty())
        assertTrue(CaptionTrack.custom("caption", 0).isEmpty())
        val animation = AssKaraokeTrack.frames(listOf(cue))
        assertEquals(0L, animation.first().startMs)
        assertEquals(1200L, animation.last().endMs)
    }

    @Test fun changingCaptionSizeScalesTheRenderedStyle() {
        val cues = CaptionTrack.custom("sample", 1000)
        val smaller = AssKaraokeTrack.render(cues, CaptionPreset.CLEAN, 1080, 1920, sizePercent = 60)
        val larger = AssKaraokeTrack.render(cues, CaptionPreset.CLEAN, 1080, 1920, sizePercent = 150)
        assertTrue(smaller.contains("Style: Default,Roboto,35,"))
        assertTrue(larger.contains("Style: Default,Roboto,89,"))
    }

    @Test fun rendersEqual720PanelsAndMapsPreviewCropWithinBounds() {
        val filter = videoFilter(Layout.Split, Crop(0f, 1f, 3f), Crop(1f, 0f), resolution = 720)
        assertEquals(2, Regex("scale=720:640").findAll(filter).count())
        val framing = cropWindow(1920, 1080, 1.125f, Crop(1f, 1f, 3f))
        assertEquals(1920, framing.x + framing.width)
        assertEquals(1080, framing.y + framing.height)
    }
}
