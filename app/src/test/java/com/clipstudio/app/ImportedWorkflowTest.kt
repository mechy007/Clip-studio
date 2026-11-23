package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedWorkflowTest {
    @Test fun guidedRequestsUseFormatHeadersWithoutLeakingCredentialsOrRanges() {
        val headers = mediaRequestHeaders(
            mapOf("User-Agent" to "Default player", "Cookie" to "private", "Range" to "bytes=0-"),
            mapOf("user-agent" to "Selected format player", "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com\r\nX-Extra: rejected")
        )
        assertEquals("user-agent: Selected format player\r\nreferer: https://www.youtube.com/\r\n", headers)
    }

    @Test fun preciseTimesUseMillisecondsAndValidateTheFraction() {
        assertEquals(5_040_125L, parseEditTime("1:24:00.125"))
        assertEquals(400L, parseEditTime("0:00.4"))
        assertEquals("84:00.125", editTime(5_040_125))
        assertEquals(null, parseEditTime("1:60.200"))
        assertEquals(null, parseEditTime("0:01.1234"))
    }

    @Test fun timedCaptionOverridesOnlyTheOverlappingInterval() {
        val timed = overlayCaptions(listOf(CaptionCue(0, 4_000, "Base")),
            listOf(CaptionCue(1_500, 2_500, "Overlay")), 500, 4_500)
        assertEquals(listOf(CaptionCue(0, 1_000, "Base"), CaptionCue(1_000, 2_000, "Overlay"),
            CaptionCue(2_000, 4_000, "Base")), timed)
    }

    @Test fun captionSplitSnapsToTheNearestTimedWord() {
        val row = TranscriptSegment(100, 700, "first second",
            listOf(TimedWord(100, 380, "first"), TimedWord(400, 700, "second")))
        val result = splitTranscript(row, 370)
        assertEquals(listOf("first", "second"), result.map { it.text })
        assertEquals(400L, result.first().endMs)
        assertEquals(400L, result.last().startMs)
    }

    @Test fun enhancedScalingAppliesToGameplayAndFitLayouts() {
        val crop = Crop(.5f, .5f)
        assertTrue(videoFilter(Layout.Split, crop, crop, qualityScale = true)
            .contains("scale=1080:960:flags=lanczos,setsar=1[bottom]"))
        assertTrue(videoFilter(Layout.Fit, crop, crop, qualityScale = true)
            .contains("force_divisible_by=2:flags=lanczos"))
    }

    @Test fun parsesOptionalRecordingTimestampsWithoutOverflow() {
        assertEquals(0L, recordingStartMs(""))
        assertEquals(5_040_000L, recordingStartMs("1:24:00"))
        assertEquals(5_040_000L, recordingStartMs("84:00"))
        assertEquals(42_000L, recordingStartMs("42"))
        assertEquals(null, recordingStartMs("1:60:00"))
        assertEquals(null, recordingStartMs("1::20"))
        assertEquals(null, recordingStartMs("-20"))
        assertEquals(null, recordingStartMs("9223372036854775807"))
    }

    @Test fun downloadChunksCoverTheRangeFromAChosenOffset() {
        val range = RecordingPartWindow(5_040_000, 5_345_000)
        assertEquals(listOf(
            RecordingPartWindow(5_040_000, 5_160_000),
            RecordingPartWindow(5_160_000, 5_280_000),
            RecordingPartWindow(5_280_000, 5_345_000)
        ), range.downloadChunks())
        assertEquals(15, RecordingPartWindow(0, RECORDING_PART_MS).downloadChunks().size)
    }

    @Test fun remainingTimeUsesOnlyWorkDoneAfterResuming() {
        val total = 1_800_000L
        val saved = 1_200_000L
        assertEquals(GuidedDownloadProgress(66, null), guidedDownloadProgress(total, saved, saved, 0))
        assertEquals(GuidedDownloadProgress(73, 240_000), guidedDownloadProgress(total, saved, 1_320_000, 60_000))
        assertEquals(GuidedDownloadProgress(100, null), guidedDownloadProgress(total, saved, total, 300_000))
    }

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

    @Test fun recordingScanKeepsTheChosenStartAndShortFinalPart() {
        val start = 10 * 60_000L
        val parts = recordingScanRanges(start, 75 * 60_000L)
        assertEquals(listOf(
            RecordingPartWindow(start, 40 * 60_000L),
            RecordingPartWindow(40 * 60_000L, 70 * 60_000L),
            RecordingPartWindow(70 * 60_000L, 75 * 60_000L)
        ), parts)
        val samples = recordingSampleRanges(parts.first())
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.startMs >= start && it.endMs <= parts.first().endMs && it.lengthMs == 5_000L })
        val short = RecordingPartWindow(start, start + 1_000)
        assertEquals(listOf(short), recordingSampleRanges(short))
    }

    @Test fun recordingScanRanksMeasuredActivityAndKeepsUnsampledPartsSelectable() {
        val parts = recordingScanRanges(0, 75 * 60_000L)
        val scores = scoreRecordingScan(parts, listOf(
            listOf(AudioEnergyWindow(10_000, 15_000, .04f, .09f)),
            listOf(AudioEnergyWindow(1_810_000, 1_815_000, .50f, .90f)),
            emptyList()
        ))
        assertEquals(parts, scores.map { it.range })
        assertTrue(requireNotNull(scores[1].score) > requireNotNull(scores[0].score))
        assertEquals(listOf(1, 1, 0), scores.map { it.samples })
        assertEquals(null, scores[2].score)
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
