package com.llamatik.teammate.external

import com.llamatik.teammate.external.docs.DocsRefreshMode
import com.llamatik.teammate.external.docs.DocsSource
import com.llamatik.teammate.external.docs.DocsSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocsSourceServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Standalone implementation for testing without IntelliJ services
    private inner class TestDocsSourceService {
        private val sources = mutableListOf<DocsSource>()

        fun listSources(): List<DocsSource> = sources.toList()

        fun addSource(source: DocsSource, isPro: Boolean): Boolean {
            if (!canAddSource(isPro)) return false
            if (sources.any { it.id == source.id }) return false
            sources.add(source)
            return true
        }

        fun removeSource(id: String) {
            sources.removeAll { it.id == id }
        }

        fun enableSource(id: String, enabled: Boolean) {
            val idx = sources.indexOfFirst { it.id == id }
            if (idx >= 0) sources[idx] = sources[idx].copy(enabled = enabled)
        }

        fun canAddSource(isPro: Boolean): Boolean = sources.size < sourcesLimit(isPro)

        fun sourcesLimit(isPro: Boolean): Int = ExternalKnowledgeLimits.docsSourceLimit(isPro)
    }

    @Test
    fun `source limit is 10 regardless of tier`() {
        val svc = TestDocsSourceService()
        assertEquals(10, svc.sourcesLimit(isPro = false))
        assertEquals(10, svc.sourcesLimit(isPro = true))
    }

    @Test
    fun `canAddSource returns true when under limit`() {
        val svc = TestDocsSourceService()
        assertTrue(svc.canAddSource(isPro = false))
    }

    @Test
    fun `canAddSource returns false when at limit`() {
        val svc = TestDocsSourceService()
        for (i in 1..10) svc.addSource(makeSource("s$i"), isPro = false)
        assertFalse("Should be at limit after adding 10 sources", svc.canAddSource(isPro = false))
    }

    @Test
    fun `addSource allows fourth source on non-pro tier`() {
        val svc = TestDocsSourceService()
        for (i in 1..3) svc.addSource(makeSource("s$i"), isPro = false)
        assertTrue("Fourth add should succeed: limits no longer depend on tier", svc.addSource(makeSource("s4"), isPro = false))
        assertEquals(4, svc.listSources().size)
    }

    @Test
    fun `addSource allows up to 10 for pro tier`() {
        val svc = TestDocsSourceService()
        for (i in 1..10) {
            val added = svc.addSource(makeSource("s$i"), isPro = true)
            assertTrue("Add #$i should succeed for pro", added)
        }
        val added11 = svc.addSource(makeSource("s11"), isPro = true)
        assertFalse("11th add should fail even for pro", added11)
        assertEquals(10, svc.listSources().size)
    }

    @Test
    fun `removeSource removes the correct entry`() {
        val svc = TestDocsSourceService()
        svc.addSource(makeSource("s1"), isPro = true)
        svc.addSource(makeSource("s2"), isPro = true)
        assertEquals(2, svc.listSources().size)
        svc.removeSource("s1")
        assertEquals(1, svc.listSources().size)
        assertEquals("s2", svc.listSources().first().id)
    }

    @Test
    fun `removeSource on nonexistent id does not crash`() {
        val svc = TestDocsSourceService()
        svc.removeSource("nonexistent") // should not throw
        assertEquals(0, svc.listSources().size)
    }

    @Test
    fun `enableSource toggles enabled state`() {
        val svc = TestDocsSourceService()
        svc.addSource(makeSource("s1"), isPro = true)
        assertTrue("Default should be enabled", svc.listSources().first().enabled)
        svc.enableSource("s1", false)
        assertFalse("Should be disabled", svc.listSources().first().enabled)
        svc.enableSource("s1", true)
        assertTrue("Should be enabled again", svc.listSources().first().enabled)
    }

    @Test
    fun `addSource does not add duplicate IDs`() {
        val svc = TestDocsSourceService()
        val source = makeSource("s1")
        svc.addSource(source, isPro = true)
        val addedAgain = svc.addSource(source, isPro = true)
        assertFalse("Should not add duplicate ID", addedAgain)
        assertEquals(1, svc.listSources().size)
    }

    @Test
    fun `listSources returns empty list when no sources added`() {
        val svc = TestDocsSourceService()
        assertTrue("Should be empty initially", svc.listSources().isEmpty())
    }

    private fun makeSource(id: String) = DocsSource(
        id = id,
        name = "Test Source $id",
        type = DocsSourceType.URL,
        location = "https://example.com/$id",
        enabled = true,
        refreshMode = DocsRefreshMode.MANUAL,
    )
}
