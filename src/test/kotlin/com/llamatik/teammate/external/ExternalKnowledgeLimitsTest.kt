package com.llamatik.teammate.external

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plugin is free for individuals, so external-knowledge limits no longer depend on a tier.
 * The `isPro` parameters are kept for API compatibility and must not change the result.
 */
class ExternalKnowledgeLimitsTest {

    @Test
    fun `daily search limit is 50`() {
        assertEquals(50, ExternalKnowledgeLimits.dailySearchLimit(isPro = false))
        assertEquals(50, ExternalKnowledgeLimits.dailySearchLimit(isPro = true))
    }

    @Test
    fun `pages per task is 10`() {
        assertEquals(10, ExternalKnowledgeLimits.pagesPerTask(isPro = false))
        assertEquals(10, ExternalKnowledgeLimits.pagesPerTask(isPro = true))
    }

    @Test
    fun `docs source limit is 10`() {
        assertEquals(10, ExternalKnowledgeLimits.docsSourceLimit(isPro = false))
        assertEquals(10, ExternalKnowledgeLimits.docsSourceLimit(isPro = true))
    }

    @Test
    fun `max result count is 10`() {
        assertEquals(10, ExternalKnowledgeLimits.maxResultCount(isPro = false))
        assertEquals(10, ExternalKnowledgeLimits.maxResultCount(isPro = true))
    }

    @Test
    fun `limits do not depend on the isPro flag`() {
        assertEquals(ExternalKnowledgeLimits.dailySearchLimit(true), ExternalKnowledgeLimits.dailySearchLimit(false))
        assertEquals(ExternalKnowledgeLimits.pagesPerTask(true), ExternalKnowledgeLimits.pagesPerTask(false))
        assertEquals(ExternalKnowledgeLimits.docsSourceLimit(true), ExternalKnowledgeLimits.docsSourceLimit(false))
        assertEquals(ExternalKnowledgeLimits.maxResultCount(true), ExternalKnowledgeLimits.maxResultCount(false))
    }

    @Test
    fun `constants match function return values`() {
        assertEquals(ExternalKnowledgeLimits.DAILY_SEARCHES, ExternalKnowledgeLimits.dailySearchLimit())
        assertEquals(ExternalKnowledgeLimits.PAGES_PER_TASK, ExternalKnowledgeLimits.pagesPerTask())
        assertEquals(ExternalKnowledgeLimits.DOCS_SOURCES, ExternalKnowledgeLimits.docsSourceLimit())
        assertEquals(ExternalKnowledgeLimits.MAX_RESULT_COUNT, ExternalKnowledgeLimits.maxResultCount())
    }
}
