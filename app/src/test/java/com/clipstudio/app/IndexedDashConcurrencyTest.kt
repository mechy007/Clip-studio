package com.clipstudio.app

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexedDashConcurrencyTest {
    @Test
    fun indexedWorkerPoolNeverExceedsConfiguredParallelism() {
        assertEquals(8, indexedDashWorkerCount(parallelism = 8, workItems = 1_000))
        assertEquals(4, indexedDashWorkerCount(parallelism = 4, workItems = 1_000))
    }

    @Test
    fun indexedWorkerPoolShrinksForSmallJobs() {
        assertEquals(2, indexedDashWorkerCount(parallelism = 8, workItems = 2))
        assertEquals(0, indexedDashWorkerCount(parallelism = 8, workItems = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun indexedWorkerPoolRejectsUnboundedParallelism() {
        indexedDashWorkerCount(parallelism = 9, workItems = 100)
    }

    @Test
    fun fastAndStandardModesUseExpectedIndexedConcurrency() {
        assertEquals(8, indexedDashParallelism(fast = true))
        assertEquals(4, indexedDashParallelism(fast = false))
    }
}
