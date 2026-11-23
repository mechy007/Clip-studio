package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReuseEditsTest {
    @Test fun trimsAnExistingFacePathAndInterpolatesBothEnds() {
        val path = listOf(FacePoint(0, 0f, 1f), FacePoint(1000, 1f, 0f), FacePoint(2000, 0f, 1f))
        assertEquals(listOf(FacePoint(0, .5f, .5f), FacePoint(500, 1f, 0f), FacePoint(1000, .5f, .5f)),
            rebaseTracking(path, 500, 1500))
    }

    @Test fun holdsTheLastSampleThroughTheEndOfTheClip() {
        val path = listOf(FacePoint(0, 0f, .3f), FacePoint(1000, .5f, .4f))
        assertEquals(listOf(FacePoint(0, .5f, .4f), FacePoint(500, .5f, .4f)), rebaseTracking(path, 1500, 2000))
        assertTrue(rebaseTracking(emptyList(), 0, 1000).isEmpty())
    }
}
