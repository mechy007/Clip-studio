package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioGeometryTest {
    @Test fun segmentOffsetsUseSourceTimeline() {
        assertEquals(SegmentRow("part-00001.mp4", 40_200, 81_800), parseSegmentRow("part-00001.mp4,40.2,81.8"))
        assertNull(parseSegmentRow("part-00001.mp4,40.2,"))
        assertNull(parseSegmentRow("../part-00001.mp4,0,40"))
    }

    @Test fun splitUsesTwoEqualPanelsWithIndependentPositions() {
        val filter = videoFilter(Layout.Split, Crop(0f, 1f, 3f), Crop(1f, 0f))
        assertEquals(2, Regex("scale=1080:960").findAll(filter).count())
        assertTrue(filter.contains("x='(iw-ow)*0.0':y='(ih-oh)*1.0'"))
        assertTrue(filter.contains("x='(iw-ow)*1.0':y='(ih-oh)*0.0'"))
        assertTrue(filter.endsWith("[top][bottom]vstack[out]"))
    }
}
