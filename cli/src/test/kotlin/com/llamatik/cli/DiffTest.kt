package com.llamatik.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {
    @Test
    fun `identical text has no changes`() = assertEquals("(no changes)", Diff.unified("A.kt", "a\nb", "a\nb"))

    @Test
    fun `shows a changed line with context`() {
        val diff = Diff.unified("A.kt", "one\ntwo\nthree\nfour\nfive", "one\ntwo\n3\nfour\nfive")
        assertEquals(
            "--- a/A.kt\n+++ b/A.kt\n@@ -1,5 +1,5 @@\n one\n two\n-three\n+3\n four\n five",
            diff,
        )
    }

    @Test
    fun `far apart changes become separate hunks`() {
        val old = (1..30).joinToString("\n") { "line$it" }
        val new = old.replace("line2\n", "LINE2\n").replace("line29", "LINE29")
        val diff = Diff.unified("A.kt", old, new)
        assertEquals(2, diff.lines().count { it.startsWith("@@") })
    }

    @Test
    fun `new file and emptied file`() {
        assertTrue(Diff.unified("N.kt", "", "class N").contains("+class N"))
        assertTrue(Diff.unified("N.kt", "class N", "").contains("-class N"))
    }

    @Test
    fun `huge inputs fall back to replacing everything instead of exhausting memory`() {
        val a = (1..2500).joinToString("\n") { "a$it" }
        val b = (1..2500).joinToString("\n") { "b$it" }
        val diff = Diff.unified("Big.kt", a, b)
        assertEquals(2500, diff.lines().count { it.startsWith("-a") })
    }
}
