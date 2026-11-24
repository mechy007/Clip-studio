package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedDownloadConcurrencyTest {
    @Test
    fun fastGuidedDownloadsUseThreeParallelSections() {
        assertEquals(3, guidedDownloadConcurrency(fast = true))
    }

    @Test
    fun standardGuidedDownloadsUseTwoParallelSections() {
        assertEquals(2, guidedDownloadConcurrency(fast = false))
    }
}
