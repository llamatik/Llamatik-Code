package com.llamatik.teammate.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalKnowledgeLimitsTest {

    @Test
    fun `free daily search limit is 15`() {
        assertEquals(15, ExternalKnowledgeLimits.dailySearchLimit(isPro = false))
    }

    @Test
    fun `pro daily search limit is 50`() {
        assertEquals(50, ExternalKnowledgeLimits.dailySearchLimit(isPro = true))
    }

    @Test
    fun `free pages per task is 5`() {
        assertEquals(5, ExternalKnowledgeLimits.pagesPerTask(isPro = false))
    }

    @Test
    fun `pro pages per task is 10`() {
        assertEquals(10, ExternalKnowledgeLimits.pagesPerTask(isPro = true))
    }

    @Test
    fun `free docs source limit is 3`() {
        assertEquals(3, ExternalKnowledgeLimits.docsSourceLimit(isPro = false))
    }

    @Test
    fun `pro docs source limit is 10`() {
        assertEquals(10, ExternalKnowledgeLimits.docsSourceLimit(isPro = true))
    }

    @Test
    fun `free max result count is 8`() {
        assertEquals(8, ExternalKnowledgeLimits.maxResultCount(isPro = false))
    }

    @Test
    fun `pro max result count is 10`() {
        assertEquals(10, ExternalKnowledgeLimits.maxResultCount(isPro = true))
    }

    @Test
    fun `pro limits are always greater than or equal to free limits`() {
        assertTrue(ExternalKnowledgeLimits.dailySearchLimit(true) >= ExternalKnowledgeLimits.dailySearchLimit(false))
        assertTrue(ExternalKnowledgeLimits.pagesPerTask(true) >= ExternalKnowledgeLimits.pagesPerTask(false))
        assertTrue(ExternalKnowledgeLimits.docsSourceLimit(true) >= ExternalKnowledgeLimits.docsSourceLimit(false))
        assertTrue(ExternalKnowledgeLimits.maxResultCount(true) >= ExternalKnowledgeLimits.maxResultCount(false))
    }

    @Test
    fun `constants match function return values`() {
        assertEquals(ExternalKnowledgeLimits.FREE_DAILY_SEARCHES, ExternalKnowledgeLimits.dailySearchLimit(false))
        assertEquals(ExternalKnowledgeLimits.PRO_DAILY_SEARCHES, ExternalKnowledgeLimits.dailySearchLimit(true))
        assertEquals(ExternalKnowledgeLimits.FREE_PAGES_PER_TASK, ExternalKnowledgeLimits.pagesPerTask(false))
        assertEquals(ExternalKnowledgeLimits.PRO_PAGES_PER_TASK, ExternalKnowledgeLimits.pagesPerTask(true))
    }
}
