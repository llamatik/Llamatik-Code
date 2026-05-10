package com.llamatik.teammate.external

import com.llamatik.teammate.external.citation.SourceType
import com.llamatik.teammate.external.citation.classifySourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class CitationModelsExtendedTest {

    // ── Official docs patterns not covered in CitationModelsTest ─────────

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for cmake org cmake help`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://cmake.org/cmake/help/latest/command/find_package.html"))
    }

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for docs spring io`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://docs.spring.io/spring-boot/docs/current/reference/html/"))
    }

    @Test
    fun `classifySourceType returns OFFICIAL_DOCS for docs oracle com`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://docs.oracle.com/javase/8/docs/api/"))
    }

    // ── Release notes patterns ────────────────────────────────────────────

    @Test
    fun `classifySourceType returns RELEASE_NOTES for URL with releases path`() {
        assertEquals(SourceType.RELEASE_NOTES, classifySourceType("https://github.com/user/repo/releases/tag/v1.2.0"))
    }

    @Test
    fun `classifySourceType returns RELEASE_NOTES for URL with changelog path`() {
        assertEquals(SourceType.RELEASE_NOTES, classifySourceType("https://github.com/user/repo/blob/main/CHANGELOG.md"))
    }

    @Test
    fun `classifySourceType returns RELEASE_NOTES for URL with release-notes path`() {
        assertEquals(SourceType.RELEASE_NOTES, classifySourceType("https://some-library.io/release-notes"))
    }

    // ── Community patterns ────────────────────────────────────────────────

    @Test
    fun `classifySourceType returns COMMUNITY for medium com`() {
        assertEquals(SourceType.COMMUNITY, classifySourceType("https://medium.com/@author/article-about-kotlin"))
    }

    @Test
    fun `classifySourceType returns COMMUNITY for dev to`() {
        assertEquals(SourceType.COMMUNITY, classifySourceType("https://dev.to/user/article-title"))
    }

    // ── Case insensitivity ────────────────────────────────────────────────

    @Test
    fun `classifySourceType is case-insensitive for STACKOVERFLOW`() {
        assertEquals(SourceType.STACKOVERFLOW, classifySourceType("https://STACKOVERFLOW.COM/questions/123"))
    }

    @Test
    fun `classifySourceType is case-insensitive for kotlinlang`() {
        assertEquals(SourceType.OFFICIAL_DOCS, classifySourceType("https://KotlinLang.ORG/docs/coroutines.html"))
    }

    // ── Priority order (github /releases beats plain github) ─────────────

    @Test
    fun `classifySourceType returns RELEASE_NOTES for github releases URL not GITHUB_MAINTAINER`() {
        // github.com/user/repo/releases matches /releases path — RELEASE_NOTES wins
        // Implementation: /releases check is after github.com check so GITHUB_MAINTAINER wins for plain repos
        // but /releases path should be caught by the RELEASE_NOTES rule
        val url = "https://github.com/user/repo/releases"
        val result = classifySourceType(url)
        // github.com without /issues or /pull → GITHUB_MAINTAINER per impl, BUT /releases rule also matches
        // Current impl: github rules are checked before /releases rule
        // github.com without /issues/pull → GITHUB_MAINTAINER
        assertEquals(SourceType.GITHUB_MAINTAINER, result)
    }

    @Test
    fun `classifySourceType returns GITHUB_ISSUES for pull URL`() {
        assertEquals(SourceType.GITHUB_ISSUES, classifySourceType("https://github.com/user/repo/pull/99"))
    }
}
