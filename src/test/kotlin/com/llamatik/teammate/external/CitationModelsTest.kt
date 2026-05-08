package com.llamatik.teammate.external

import com.llamatik.teammate.external.citation.Citation
import com.llamatik.teammate.external.citation.SourceConfidence
import com.llamatik.teammate.external.citation.SourceType
import com.llamatik.teammate.external.citation.classifySourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class CitationModelsTest {

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for kotlinlang org`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://kotlinlang.org/docs/coroutines.html"))
    }

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for developer android com`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://developer.android.com/jetpack/compose"))
    }

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for docs gradle org`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://docs.gradle.org/current/userguide/"))
    }

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for developer apple com`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://developer.apple.com/documentation/swift"))
    }

    @Test
    fun `classifySourceType returns GITHUB_ISSUES for github issues URL`() {
        assertEquals(SourceType.GITHUB_ISSUES, classifySourceType("https://github.com/user/repo/issues/123"))
    }

    @Test
    fun `classifySourceType returns GITHUB_ISSUES for github pull request URL`() {
        assertEquals(SourceType.GITHUB_ISSUES, classifySourceType("https://github.com/user/repo/pull/456"))
    }

    @Test
    fun `classifySourceType returns GITHUB_MAINTAINER for github non-issue URL`() {
        assertEquals(SourceType.GITHUB_MAINTAINER, classifySourceType("https://github.com/user/repo"))
    }

    @Test
    fun `classifySourceType returns STACKOVERFLOW for stackoverflow com`() {
        assertEquals(SourceType.STACKOVERFLOW, classifySourceType("https://stackoverflow.com/questions/12345"))
    }

    @Test
    fun `classifySourceType returns RELEASE_NOTES for blog jetbrains com`() {
        assertEquals(SourceType.RELEASE_NOTES, classifySourceType("https://blog.jetbrains.com/kotlin/2024/release"))
    }

    @Test
    fun `classifySourceType returns COMMUNITY for reddit com`() {
        assertEquals(SourceType.COMMUNITY, classifySourceType("https://reddit.com/r/androiddev/comments/abc"))
    }

    @Test
    fun `classifySourceType returns UNKNOWN for unknown domains`() {
        assertEquals(SourceType.UNKNOWN, classifySourceType("https://somerandomblog.io/post/123"))
    }

    @Test
    fun `Citation confidence is HIGH for OFFICIAL_DOCS`() {
        val citation = Citation(title = "Kotlin Docs", url = "https://kotlinlang.org", sourceType = SourceType.OFFICIAL_DOCS)
        assertEquals(SourceConfidence.HIGH, citation.confidence)
    }

    @Test
    fun `Citation confidence is HIGH for RELEASE_NOTES`() {
        val citation = Citation(title = "Release", url = "https://blog.jetbrains.com", sourceType = SourceType.RELEASE_NOTES)
        assertEquals(SourceConfidence.HIGH, citation.confidence)
    }

    @Test
    fun `Citation confidence is MEDIUM for GITHUB_MAINTAINER`() {
        val citation = Citation(title = "GitHub", url = "https://github.com/user/repo", sourceType = SourceType.GITHUB_MAINTAINER)
        assertEquals(SourceConfidence.MEDIUM, citation.confidence)
    }

    @Test
    fun `Citation confidence is MEDIUM for STACKOVERFLOW`() {
        val citation = Citation(title = "SO", url = "https://stackoverflow.com", sourceType = SourceType.STACKOVERFLOW)
        assertEquals(SourceConfidence.MEDIUM, citation.confidence)
    }

    @Test
    fun `Citation confidence is LOW for COMMUNITY`() {
        val citation = Citation(title = "Reddit", url = "https://reddit.com", sourceType = SourceType.COMMUNITY)
        assertEquals(SourceConfidence.LOW, citation.confidence)
    }

    @Test
    fun `Citation confidence is LOW for UNKNOWN`() {
        val citation = Citation(title = "Blog", url = "https://randomblog.com", sourceType = SourceType.UNKNOWN)
        assertEquals(SourceConfidence.LOW, citation.confidence)
    }

    @Test
    fun `Citation confidenceLabel is Official docs for HIGH confidence`() {
        assertEquals("Official docs", Citation("", "", SourceType.OFFICIAL_DOCS).confidenceLabel)
    }

    @Test
    fun `Citation confidenceLabel is Community maintainer for MEDIUM confidence`() {
        assertEquals("Community / maintainer", Citation("", "", SourceType.GITHUB_ISSUES).confidenceLabel)
    }

    @Test
    fun `Citation confidenceLabel is Community lower confidence for LOW confidence`() {
        assertEquals("Community (lower confidence)", Citation("", "", SourceType.UNKNOWN).confidenceLabel)
    }
}
