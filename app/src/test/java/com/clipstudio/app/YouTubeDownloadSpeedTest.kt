package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeDownloadSpeedTest {
    @Test
    fun parsesBinaryMegabytesPerSecond() {
        assertEquals(1_572_864L, parseDownloadSpeedBytes("[download] 12.0% of 50.00MiB at 1.50MiB/s ETA 00:30"))
    }

    @Test
    fun parsesKilobytesPerSecond() {
        assertEquals(12_000L, parseDownloadSpeedBytes("[download] at 12.0KB/s ETA 00:20"))
    }

    @Test
    fun ignoresLinesWithoutTransferRate() {
        assertNull(parseDownloadSpeedBytes("[download] Destination: video.mp4"))
    }
}
