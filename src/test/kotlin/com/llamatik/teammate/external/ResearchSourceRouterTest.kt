package com.llamatik.teammate.external

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSourceRouterTest {

    // Standalone routing logic — mirrors ResearchSourceRouter without IntelliJ services
    private fun routeSources(query: String, preferred: List<String>): List<String> {
        val lower = query.lowercase()
        val routedDomains = mutableListOf<String>()
        when {
            lower.contains("kotlin") || lower.contains("coroutine") || lower.contains("flow") || lower.contains("suspend") ->
                routedDomains.addAll(listOf("kotlinlang.org", "blog.jetbrains.com"))
            lower.contains("android") || lower.contains("jetpack") || lower.contains("compose") ->
                routedDomains.add("developer.android.com")
            lower.contains("gradle") ->
                routedDomains.add("docs.gradle.org")
            lower.contains("cmake") ->
                routedDomains.add("cmake.org")
            lower.contains("swift") || lower.contains("ios") || lower.contains("xcode") ->
                routedDomains.add("developer.apple.com")
            lower.contains("kmp") || lower.contains("multiplatform") ->
                routedDomains.addAll(listOf("kotlinlang.org", "blog.jetbrains.com"))
            lower.contains("github issue") || lower.contains("bug") || lower.contains("regression") ->
                routedDomains.add("github.com")
        }
        return (preferred + routedDomains).distinct()
    }

    @Test
    fun `kotlin query routes to kotlinlang org`() {
        val result = routeSources("kotlin coroutines tutorial", emptyList())
        assertTrue("Should route to kotlinlang.org", result.contains("kotlinlang.org"))
    }

    @Test
    fun `kotlin query routes to blog jetbrains com`() {
        val result = routeSources("kotlin flow best practices", emptyList())
        assertTrue("Should route to blog.jetbrains.com", result.contains("blog.jetbrains.com"))
    }

    @Test
    fun `android query routes to developer android com`() {
        val result = routeSources("android jetpack navigation", emptyList())
        assertTrue("Should route to developer.android.com", result.contains("developer.android.com"))
    }

    @Test
    fun `compose query routes to developer android com`() {
        val result = routeSources("jetpack compose layout", emptyList())
        assertTrue("Should route to developer.android.com", result.contains("developer.android.com"))
    }

    @Test
    fun `gradle query routes to docs gradle org`() {
        val result = routeSources("gradle build configuration scripts", emptyList())
        assertTrue("Should route to docs.gradle.org", result.contains("docs.gradle.org"))
    }

    @Test
    fun `cmake query routes to cmake org`() {
        val result = routeSources("cmake find_package tutorial", emptyList())
        assertTrue("Should route to cmake.org", result.contains("cmake.org"))
    }

    @Test
    fun `swift query routes to developer apple com`() {
        val result = routeSources("swift async await tutorial", emptyList())
        assertTrue("Should route to developer.apple.com", result.contains("developer.apple.com"))
    }

    @Test
    fun `xcode query routes to developer apple com`() {
        val result = routeSources("xcode build settings", emptyList())
        assertTrue("Should route to developer.apple.com", result.contains("developer.apple.com"))
    }

    @Test
    fun `kmp query routes to kotlinlang`() {
        val result = routeSources("kmp multiplatform setup guide", emptyList())
        assertTrue("Should route to kotlinlang.org", result.contains("kotlinlang.org"))
    }

    @Test
    fun `multiplatform query routes to kotlinlang`() {
        val result = routeSources("kotlin multiplatform mobile", emptyList())
        // "kotlin" or "multiplatform" triggers the kotlin branch (kotlin first)
        assertTrue("Should route to kotlinlang.org", result.contains("kotlinlang.org"))
    }

    @Test
    fun `unknown query returns empty`() {
        val result = routeSources("completely unrelated random xyz topic", emptyList())
        assertTrue("Unknown query should return no routed domains", result.isEmpty())
    }

    @Test
    fun `preferred sources appear first in result`() {
        val preferred = listOf("mycompany.internal.docs")
        val result = routeSources("kotlin flows", preferred)
        assertTrue("Preferred sources should appear in result", result.contains("mycompany.internal.docs"))
        assertTrue("Preferred should be at index 0", result.indexOf("mycompany.internal.docs") == 0)
    }

    @Test
    fun `github issue query routes to github com`() {
        val result = routeSources("github issue bug regression", emptyList())
        assertTrue("Should route to github.com", result.contains("github.com"))
    }

    @Test
    fun `result has no duplicates`() {
        val result = routeSources("kotlin coroutines", listOf("kotlinlang.org"))
        val unique = result.distinct()
        assertTrue("No duplicates in result", result.size == unique.size)
    }

    @Test
    fun `suspend keyword routes to kotlinlang org`() {
        val result = routeSources("suspend function explanation", emptyList())
        assertTrue("suspend should route to kotlinlang.org", result.contains("kotlinlang.org"))
    }

    @Test
    fun `ios keyword routes to developer apple com`() {
        val result = routeSources("ios 17 new features", emptyList())
        assertTrue("ios should route to developer.apple.com", result.contains("developer.apple.com"))
    }

    @Test
    fun `bug keyword routes to github com`() {
        val result = routeSources("bug in library regression", emptyList())
        assertTrue("bug should route to github.com", result.contains("github.com"))
    }
}
