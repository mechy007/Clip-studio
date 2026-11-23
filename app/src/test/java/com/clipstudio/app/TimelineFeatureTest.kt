package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineFeatureTest {
    @Test fun interpolatesFacePositionBetweenSamples() {
        val points = listOf(FacePoint(0, .20f, .70f), FacePoint(2000, .80f, .40f))
        assertTrue(trackingCoordinate(points, 0f) { it.x }.contains("clip((t-0.0)/(2.0-0.0),0,1)"))
        assertEquals("0.2", trackingCoordinate(emptyList(), .2f) { it.x })
    }

    @Test fun audioMixUsesBothTracksAndSingleTrackStillFades() {
        val options = AudioFinish(normalize = true, fade = true, mixTracks = true, voice = .8f, game = .2f)
        val mix = requireNotNull(audioFinishFilter(options, 2, 4_000))
        assertTrue(mix.startsWith("[0:a:0][0:a:1]amix"))
        assertTrue(mix.contains("weights='0.8 0.2'"))
        assertTrue(mix.contains("loudnorm=I=-16"))
        assertTrue(mix.contains("afade=t=out:st=3.600:d=0.400"))
        assertFalse(requireNotNull(audioFinishFilter(options, 1, 4_000)).contains("amix"))
        assertNull(audioFinishFilter(options, 0, 4_000))
    }

    @Test fun highlightsUseTheSelectedFocus() {
        val events = listOf(AudioEventWindow(20_000, 21_000, .95f, 0f, 0f),
            AudioEventWindow(62_000, 63_000, 0f, 0f, 0f, .95f))
        val visual = listOf(VisualSample(42_000, .70f))
        val funny = rankHighlights(90_000, emptyList(), events, visual, mode = HighlightMode.FUNNY)
        val gaming = rankHighlights(90_000, emptyList(), events, visual, mode = HighlightMode.GAMEPLAY)
        val talking = rankHighlights(90_000, emptyList(), events, visual, mode = HighlightMode.TALKING)
        assertEquals("Laughter detected", funny.single().reason)
        assertEquals("Sudden visual activity", gaming.single().reason)
        assertEquals("Sustained speech", talking.single().reason)
    }
}
