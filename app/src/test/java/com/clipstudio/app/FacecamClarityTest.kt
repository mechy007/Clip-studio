package com.clipstudio.app

import org.junit.Assert.assertEquals
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
}
