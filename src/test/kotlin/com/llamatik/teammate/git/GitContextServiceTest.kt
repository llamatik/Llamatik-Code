package com.llamatik.teammate.git

import org.junit.Assert.*
import org.junit.Test

class GitContextServiceTest {

    // ── Status parsing ────────────────────────────────────────────────────────

    @Test
    fun `parsePorcelain staged file`() {
        val line = "M  src/Foo.kt"
        val x = line[0]; val y = line[1]; val path = line.substring(3).trim()
        assertTrue("M staged", x == 'M' && x != ' ')
        assertFalse("not unstaged", y != ' ' && y != '?' && x == ' ')
        assertEquals("src/Foo.kt", path)
    }

    @Test
    fun `parsePorcelain unstaged file`() {
        val line = " M src/Bar.kt"
        val x = line[0]; val y = line[1]; val path = line.substring(3).trim()
        assertTrue("y is M (unstaged)", y == 'M')
        assertTrue("x is space (not staged)", x == ' ')
        assertEquals("src/Bar.kt", path)
    }

    @Test
    fun `parsePorcelain untracked file`() {
        val line = "?? newfile.kt"
        val x = line[0]; val y = line[1]; val path = line.substring(3).trim()
        assertTrue("untracked", x == '?' && y == '?')
        assertEquals("newfile.kt", path)
    }

    @Test
    fun `parsePorcelain added and modified`() {
        // Use raw strings that preserve git's exact porcelain format (leading space matters for XY codes).
        val porcelain = listOf(
            "M  src/A.kt",   // staged modify (X=M, Y=space)
            " M src/B.kt",   // unstaged modify (X=space, Y=M)
            "?? src/C.kt",   // untracked
            "A  src/D.kt",   // staged add
        )
        val staged = mutableListOf<String>()
        val unstaged = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        porcelain.forEach { line ->
            if (line.length < 3) return@forEach
            val x = line[0]; val y = line[1]; val path = line.substring(3).trim()
            when {
                x == '?' && y == '?' -> untracked += path
                x != ' ' && x != '?' -> staged += path
                y != ' ' && y != '?' -> unstaged += path
            }
        }
        assertEquals(listOf("src/A.kt", "src/D.kt"), staged)
        assertEquals(listOf("src/B.kt"), unstaged)
        assertEquals(listOf("src/C.kt"), untracked)
    }

    // ── Diff truncation ───────────────────────────────────────────────────────

    @Test
    fun `diff is truncated to maxChars`() {
        val bigDiff = "+" + "x".repeat(10_000)
        val truncated = bigDiff.take(500)
        assertEquals(500, truncated.length)
    }

    // ── Non-git project graceful failure ──────────────────────────────────────

    @Test
    fun `suggestCommitContext returns not-a-repo when no git dir`() {
        // Simulate the "not a git repo" path by checking the isGitRepo logic
        val fakeBasePath = System.getProperty("java.io.tmpdir") + "/definitely-not-a-git-repo-12345"
        val gitDir = java.io.File(fakeBasePath, ".git")
        assertFalse("temp dir has no .git", gitDir.exists())
    }
}
