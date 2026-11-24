package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacecamClarityTest {
    @Test fun reportsSourcePixelsInsteadOfOutputPixels() {
        assertEquals(240 to 212, facecamCropPixels(1280, 720, 3.375f))
        assertEquals(810 to 720, facecamCropPixels(1280, 720, 1f))
    }

    @Test fun qualityScalerAppliesToTheFacecamPanel() {
        val filter = videoFilter(Layout.Split, Crop(.2f, .3f, 3f), Crop(.5f, .5f))
        assertTrue(filter.contains("scale=1080:960:flags=lanczos,setsar=1[top]"))
        assertTrue(filter.contains("scale=1080:960,setsar=1[bottom]"))
    }
    @Test fun rescueNeedsAgreementAcrossSampledFrames() {
        val dark = FacecamFrameSignal(52, 100, 7f)
        val normal = FacecamFrameSignal(110, 190, 1f)
        assertEquals(FacecamRescue(), chooseFacecamRescue(listOf(normal, dark, normal)))
        assertEquals(FacecamRescue(), chooseFacecamRescue(listOf(dark)))
        assertEquals(FacecamRescue(true, true), chooseFacecamRescue(listOf(dark, normal, dark)))
    }

    @Test fun noiseMeasurementDistinguishesFineNoiseFromCleanLuma() {
        fun sample(noisy: Boolean) = IntArray(64 * 64) { index ->
            if (!noisy) 90 else 90 + ((index * 7 + index / 64 * 13) % 17 - 8)
        }
        val clean = requireNotNull(facecamFrameSignal(64, 64, sample(false)))
        val noisy = requireNotNull(facecamFrameSignal(64, 64, sample(true)))
        assertTrue(requireNotNull(noisy.noise) > requireNotNull(clean.noise))
    }

    @Test fun rescueTouchesOnlyTheFacecamBeforeUpscaling() {
        val crop = Crop(.5f, .5f)
        val rescue = FacecamRescue(true, true)
        val graph = videoFilter(Layout.Split, crop, crop, rescue = rescue)
        assertTrue(graph.substringBefore("[top]").contains("hqdn3d=1.8:1.3:2.8:2.0,eq=gamma="))
        assertTrue(graph.substringBefore("[top]").indexOf("eq=gamma=") < graph.indexOf("scale="))
        assertFalse(graph.substringAfter("[top]").contains("hqdn3d"))
        assertFalse(videoFilter(Layout.Fill, crop, crop, rescue = rescue).contains("eq=gamma="))
    }

    @Test fun enhancementSharpensOnlyTheFacecamAt1440p() {
        val camera = Crop(.2f, .3f, 3f)
        val game = Crop(.5f, .5f)
        val graph = videoFilter(Layout.Split, camera, game, 1440, enhanceFacecam = true)
        assertTrue(graph.contains("scale=1440:1280:flags=lanczos,setsar=1,unsharp=lx=5:ly=5:la=0.45:ca=0[top]"))
        assertTrue(graph.contains("scale=1440:1280,setsar=1[bottom]"))
        assertEquals(1440 to 2560, Layout.Split.dimensions(1440))
        assertFalse(videoFilter(Layout.Fill, camera, game, 1440, enhanceFacecam = true).contains("unsharp"))
    }
}
