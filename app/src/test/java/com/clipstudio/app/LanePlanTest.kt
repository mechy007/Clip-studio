package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LanePlanTest {
    @Test
    fun laneCountGrowsWithLengthAndIsCapped() {
        assertEquals(1, laneCount(60_000L))
        assertEquals(3, laneCount(400_000L))
        assertEquals(LANE_MAX, laneCount(30 * 60 * 1000L))
        assertEquals(LANE_MAX, laneCount(10 * 60 * 60 * 1000L))
    }

    @Test
    fun boundariesSplitThePartEvenly() {
        val range = RecordingPartWindow(60_000L, 1_860_000L)
        assertEquals(listOf(60_000L, 660_000L, 1_260_000L), laneBoundaries(range, 3))
    }

    @Test
    fun parsesTheFirstFramecrcPacket() {
        val text = """
            #format: frame checksums
            #version: 2
            #hash: CRC32
            #tb 0: 1/90000
            #media_type 0: video
            #codec_id 0: h264
            #stream#, dts,        pts, duration,     size, hash
            0, 5409000, 5412000, 3000, 12345, 0xabcdef12
            0, 5412000, 5418000, 3000, 2345, 0x12345678
        """.trimIndent()
        assertEquals(KeyPacket(60_100L, 60_133L), parseFirstPacket(text))
    }

    @Test
    fun parseReturnsNullWithoutPackets() {
        assertNull(parseFirstPacket("#tb 0: 1/90000\n#media_type 0: video\n"))
        assertNull(parseFirstPacket(""))
    }

    @Test
    fun lanesJoinOnKeyframes() {
        val range = RecordingPartWindow(0L, 600_000L)
        val boundaries = laneBoundaries(range, 3)
        val keys = listOf(KeyPacket(0L, 66L), KeyPacket(198_000L, 198_066L), KeyPacket(398_000L, 398_066L))
        val plan = planLanes(range, boundaries, keys)
        assertNotNull(plan)
        assertEquals(LaneSpec(0L, 198_000L, 198_000L), plan!![0])
        assertEquals(LaneSpec(198_069L, 199_931L, 200_000L), plan[1])
        assertEquals(LaneSpec(398_069L, 201_931L, null), plan[2])
    }

    @Test
    fun rejectsKeyframesThatAreTooFarBack() {
        val range = RecordingPartWindow(0L, 600_000L)
        val boundaries = laneBoundaries(range, 3)
        val keys = listOf(KeyPacket(0L, 66L), KeyPacket(98_000L, 98_066L), KeyPacket(398_000L, 398_066L))
        assertNull(planLanes(range, boundaries, keys))
    }

    @Test
    fun rejectsLanesThatCollapseOntoOneKeyframe() {
        val range = RecordingPartWindow(0L, 6_000L)
        val boundaries = laneBoundaries(range, 2)
        val keys = listOf(KeyPacket(-100L, -34L), KeyPacket(300L, 366L))
        assertNull(planLanes(range, boundaries, keys))
    }

    @Test
    fun rejectsMismatchedKeyCount() {
        val range = RecordingPartWindow(0L, 600_000L)
        assertNull(planLanes(range, laneBoundaries(range, 3), listOf(KeyPacket(0L, 66L))))
    }

    @Test
    fun manifestCarriesDurationsForAllButTheLastLane() {
        val files = listOf(File("lane-0.mp4"), File("lane-1.mp4"))
        val specs = listOf(LaneSpec(0L, 198_000L, 198_000L), LaneSpec(198_069L, 199_931L, null))
        val text = concatManifest(files, specs)
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals("ffconcat version 1.0", lines.first())
        assertEquals(2, lines.count { it.startsWith("file ") })
        assertEquals(listOf("duration 198.000"), lines.filter { it.startsWith("duration") })
        assertTrue(lines[1].contains("lane-0.mp4"))
    }

    @Test
    fun headerBlockKeepsOnlyForwardedHeaders() {
        val block = headerBlock(mapOf("User-Agent" to "UA", "Accept-Encoding" to "gzip", "referer" to "R"))
        assertTrue(block.contains("User-Agent: UA\r\n"))
        assertTrue(block.contains("referer: R\r\n"))
        assertFalse(block.contains("Accept-Encoding"))
    }
}
