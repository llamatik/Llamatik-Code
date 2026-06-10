package com.llamatik.teammate.external

import com.llamatik.teammate.external.search.ExternalSearchService
import com.llamatik.teammate.external.search.SearchIntent
import com.llamatik.teammate.external.search.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSearchServiceTest {

    private fun makeService(): ExternalSearchService = ExternalSearchService()

    @Test
    fun `buildSearchUrl encodes query correctly`() {
        val svc = makeService()
        val query = SearchQuery(query = "kotlin coroutines")
        val url = svc.buildSearchUrl(query)
        assertTrue("URL should encode query", url.contains("kotlin+coroutines") || url.contains("kotlin%20coroutines"))
        assertTrue("URL should point to DuckDuckGo", url.contains("api.duckduckgo.com"))
        assertTrue("URL should have format=json", url.contains("format=json"))
    }

    @Test
    fun `buildSearchUrl contains required parameters`() {
        val svc = makeService()
        val url = svc.buildSearchUrl(SearchQuery(query = "test"))
        assertTrue("URL should have no_html=1", url.contains("no_html=1"))
        assertTrue("URL should have skip_disambig=1", url.contains("skip_disambig=1"))
    }

    @Test
    fun `buildSearchUrl does not include API keys or secrets`() {
        val svc = makeService()
        val url = svc.buildSearchUrl(SearchQuery(query = "my query"))
        assertFalse("URL should not contain apikey", url.contains("apikey"))
        assertFalse("URL should not contain api_key", url.contains("api_key"))
        assertFalse("URL should not contain secret", url.contains("secret"))
    }

    @Test
    fun `free daily searches constant is 15`() {
        assertEquals(15, ExternalKnowledgeLimits.FREE_DAILY_SEARCHES)
    }

    @Test
    fun `pro daily searches constant is 50`() {
        assertEquals(50, ExternalKnowledgeLimits.PRO_DAILY_SEARCHES)
    }

    @Test
    fun `SearchQuery default values are correct`() {
        val q = SearchQuery(query = "hello")
        assertEquals(SearchIntent.GENERAL, q.intent)
        assertEquals(5, q.maxResults)
        assertTrue(q.domains.isEmpty())
        assertTrue(q.blockedDomains.isEmpty())
        assertEquals("", q.freshness)
    }

    @Test
    fun `SearchQuery with blocked domains stores them`() {
        val q = SearchQuery(query = "test", blockedDomains = listOf("example.com", "bad.org"))
        assertEquals(2, q.blockedDomains.size)
        assertTrue(q.blockedDomains.contains("example.com"))
    }

    @Test
    fun `buildSearchUrl uses query from SearchQuery`() {
        val svc = makeService()
        val url1 = svc.buildSearchUrl(SearchQuery(query = "kotlin"))
        val url2 = svc.buildSearchUrl(SearchQuery(query = "android"))
        assertTrue("URLs for different queries should differ", url1 != url2)
    }
}
