package com.clipstudio.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedDashPlannerTest {
    private fun format(): JSONObject = JSONObject()
        .put("format_id", "137")
        .put("fragment_base_url", "https://cdn.example/video/")
        .put("fragments", JSONArray()
            .put(JSONObject().put("url", "init.mp4"))
            .put(JSONObject().put("url", "seg-1.m4s").put("duration", 10.0))
            .put(JSONObject().put("url", "seg-2.m4s").put("duration", 10.0))
            .put(JSONObject().put("url", "seg-3.m4s").put("duration", 10.0))
            .put(JSONObject().put("url", "seg-4.m4s").put("duration", 10.0)))

    @Test
    fun selectsOnlyFragmentsOverlappingRequestedTime() {
        val track = IndexedDashPlanner.parse(format())
        assertNotNull(track)
        val selected = requireNotNull(track).mediaFor(12_000L, 29_000L)
        assertEquals(listOf("seg-2.m4s", "seg-3.m4s"), selected.map { it.url.substringAfterLast('/') })
    }

    @Test
    fun keepsInitializationSeparateFromMediaSelection() {
        val track = requireNotNull(IndexedDashPlanner.parse(format()))
        assertTrue(track.initialization.initialization)
        assertEquals(1, track.mediaFor(0L, 10_000L).first().index)
        assertEquals("init.mp4", track.initialization.url.substringAfterLast('/'))
    }

    @Test
    fun rejectsMissingInitializationOrUnknownDurations() {
        val missingInit = format().put("fragments", JSONArray()
            .put(JSONObject().put("url", "seg-1.m4s").put("duration", 10.0)))
        assertNull(IndexedDashPlanner.parse(missingInit))
    }

    @Test
    fun preservesSignedBaseQueryForRelativeFragments() {
        val signed = JSONObject()
            .put("format_id", "137")
            .put("fragment_base_url", "https://cdn.example/video/?token=a%2Fb")
            .put("fragments", JSONArray()
                .put(JSONObject().put("url", "init.mp4"))
                .put(JSONObject().put("url", "seg-1.m4s").put("duration", 10.0)))
        val track = requireNotNull(IndexedDashPlanner.parse(signed))
        assertEquals(
            "https://cdn.example/video/seg-1.m4s?token=a%2Fb",
            track.fragments[1].url
        )
    }

    @Test
    fun resolvesRelativePathsAgainstFragmentBaseUrl() {
        val track = requireNotNull(IndexedDashPlanner.parse(format()))
        assertEquals("https://cdn.example/video/seg-1.m4s", track.fragments[1].url)
    }
}
