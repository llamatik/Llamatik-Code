package com.llamatik.teammate.search

import com.llamatik.teammate.context.ProjectContextService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameSearchRankingTest {

    private val sampleFiles = listOf(
        "src/main/kotlin/com/example/ChatSessionController.kt",
        "src/main/kotlin/com/example/ChatMessagePanel.kt",
        "src/main/kotlin/com/example/AtMentionHandler.kt",
        "src/main/kotlin/com/example/ChatTabPanel.kt",
        "src/main/kotlin/com/example/ui/chat/ChatListPanel.kt",
        "src/main/kotlin/com/example/persistence/ChatSessionStore.kt",
        "src/main/kotlin/com/example/model/ChatModels.kt",
        "docs/chat-feature.md",
        "src/main/kotlin/com/example/SomethingElse.kt",
    )

    @Test
    fun `returns only matching files`() {
        val results = ProjectContextService.rankFilenames(sampleFiles, "Chat")
        assertTrue(results.none { "SomethingElse" in it })
        assertTrue(results.none { "AtMentionHandler" in it })
    }

    @Test
    fun `filename-starts-with ranks before filename-contains`() {
        val results = ProjectContextService.rankFilenames(sampleFiles, "Chat")
        // All files whose filename starts with "Chat" must come before those where only
        // the path (e.g. docs/chat-feature.md) contains the query.
        val filenameStartsWith = results.filter {
            it.substringAfterLast('/').lowercase().startsWith("chat")
        }
        val pathOnly = results.filter {
            val fn = it.substringAfterLast('/').lowercase()
            !fn.startsWith("chat") && it.lowercase().contains("chat")
        }
        if (filenameStartsWith.isNotEmpty() && pathOnly.isNotEmpty()) {
            val lastStartsWithIdx = results.indexOf(filenameStartsWith.last())
            val firstPathOnlyIdx = results.indexOf(pathOnly.first())
            assertTrue(
                "filename-startsWith results must precede path-only results",
                lastStartsWithIdx < firstPathOnlyIdx
            )
        }
    }

    @Test
    fun `blank query returns empty list`() {
        val results = ProjectContextService.rankFilenames(sampleFiles, "  ")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `query with no matches returns empty list`() {
        val results = ProjectContextService.rankFilenames(sampleFiles, "zzznomatch")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `matching is case insensitive`() {
        val lower = ProjectContextService.rankFilenames(sampleFiles, "chat")
        val upper = ProjectContextService.rankFilenames(sampleFiles, "CHAT")
        assertEquals(lower, upper)
    }

    @Test
    fun `within same tier results are sorted alphabetically by filename`() {
        // All these files have filenames starting with "Chat" — they should be alpha-sorted
        val results = ProjectContextService.rankFilenames(sampleFiles, "Chat")
        val tier1 = results.filter {
            it.substringAfterLast('/').lowercase().startsWith("chat")
        }
        val filenames = tier1.map { it.substringAfterLast('/').lowercase() }
        assertEquals(filenames.sorted(), filenames)
    }

    @Test
    fun `does not drop files from large input list`() {
        // Simulate a project with many files — none should be silently excluded before ranking
        val manyFiles = (1..2000).map { "src/File$it.kt" }
        val results = ProjectContextService.rankFilenames(manyFiles, "File1")
        // "File1", "File10".."File19", "File100".."File199", "File1000".."File1999" all match
        assertTrue("Should find more than 10 results in a 2000-file project", results.size > 10)
    }
}
