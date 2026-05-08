package com.llamatik.teammate.codehealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHealthInsightStoreTest {

    private inner class TestStore {
        private val insights = mutableMapOf<String, MutableList<CodeHealthInsight>>()
        private val ignoredIds = mutableSetOf<String>()
        private val ignoredTypes = mutableSetOf<InsightType>()

        fun upsert(list: List<CodeHealthInsight>) {
            for (ins in list) {
                insights.getOrPut(ins.filePath) { mutableListOf() }.apply {
                    removeAll { it.id == ins.id }
                    add(ins)
                }
            }
        }

        fun allInsights() = insights.values.flatten()
            .filter { it.id !in ignoredIds && it.type !in ignoredTypes }

        fun insightsFor(path: String) = insights[path].orEmpty()
            .filter { it.id !in ignoredIds && it.type !in ignoredTypes }

        fun ignoreInsight(id: String) { ignoredIds.add(id) }
        fun ignoreType(type: InsightType) { ignoredTypes.add(type) }
        fun unignoreType(type: InsightType) { ignoredTypes.remove(type) }
        fun removeForFile(path: String) { insights.remove(path) }
        fun removeStaleFor(active: Set<String>) {
            insights.keys.filter { it !in active }.forEach { insights.remove(it) }
        }
        fun clear() { insights.clear() }
        fun highSeverity() = allInsights().filter { it.severity == InsightSeverity.HIGH }
    }

    private fun ins(
        id: String,
        filePath: String = "Foo.kt",
        type: InsightType = InsightType.COMPLEXITY,
        severity: InsightSeverity = InsightSeverity.WARNING,
    ) = CodeHealthInsight(id = id, type = type, severity = severity, title = "T", description = "D", filePath = filePath)

    @Test fun `upsert adds new insight`() {
        val s = TestStore(); s.upsert(listOf(ins("1")))
        assertEquals(1, s.allInsights().size)
    }

    @Test fun `upsert replaces insight with same id`() {
        val s = TestStore()
        s.upsert(listOf(ins("1")))
        s.upsert(listOf(ins("1")))
        assertEquals(1, s.allInsights().size)
    }

    @Test fun `insightsFor returns only matching file`() {
        val s = TestStore()
        s.upsert(listOf(ins("1", "A.kt"), ins("2", "B.kt")))
        assertEquals(1, s.insightsFor("A.kt").size)
        assertEquals("A.kt", s.insightsFor("A.kt").first().filePath)
    }

    @Test fun `ignoreInsight hides insight from allInsights`() {
        val s = TestStore(); s.upsert(listOf(ins("1")))
        s.ignoreInsight("1")
        assertTrue(s.allInsights().isEmpty())
    }

    @Test fun `ignoreInsight does not affect other insights`() {
        val s = TestStore(); s.upsert(listOf(ins("1"), ins("2")))
        s.ignoreInsight("1")
        assertEquals(1, s.allInsights().size)
        assertEquals("2", s.allInsights().first().id)
    }

    @Test fun `ignoreType hides all insights of that type`() {
        val s = TestStore()
        s.upsert(listOf(ins("1", type = InsightType.COMPLEXITY), ins("2", type = InsightType.MISSING_TESTS)))
        s.ignoreType(InsightType.COMPLEXITY)
        assertEquals(1, s.allInsights().size)
        assertEquals(InsightType.MISSING_TESTS, s.allInsights().first().type)
    }

    @Test fun `unignoreType restores insights`() {
        val s = TestStore(); s.upsert(listOf(ins("1", type = InsightType.COMPLEXITY)))
        s.ignoreType(InsightType.COMPLEXITY)
        assertTrue(s.allInsights().isEmpty())
        s.unignoreType(InsightType.COMPLEXITY)
        assertEquals(1, s.allInsights().size)
    }

    @Test fun `removeForFile removes insights for that file only`() {
        val s = TestStore()
        s.upsert(listOf(ins("1", "A.kt"), ins("2", "B.kt")))
        s.removeForFile("A.kt")
        assertEquals(1, s.allInsights().size)
        assertEquals("B.kt", s.allInsights().first().filePath)
    }

    @Test fun `removeStaleFor removes inactive file insights`() {
        val s = TestStore()
        s.upsert(listOf(ins("1", "A.kt"), ins("2", "B.kt"), ins("3", "C.kt")))
        s.removeStaleFor(setOf("A.kt"))
        assertEquals(1, s.allInsights().size)
        assertEquals("A.kt", s.allInsights().first().filePath)
    }

    @Test fun `clear removes all insights`() {
        val s = TestStore(); s.upsert(listOf(ins("1"), ins("2"), ins("3")))
        s.clear()
        assertTrue(s.allInsights().isEmpty())
    }

    @Test fun `highSeverity returns only HIGH insights`() {
        val s = TestStore()
        s.upsert(listOf(
            ins("1", severity = InsightSeverity.HIGH),
            ins("2", severity = InsightSeverity.WARNING),
            ins("3", severity = InsightSeverity.INFO),
        ))
        assertEquals(1, s.highSeverity().size)
        assertEquals(InsightSeverity.HIGH, s.highSeverity().first().severity)
    }

    @Test fun `allInsights returns distinct entries across files`() {
        val s = TestStore()
        s.upsert(listOf(ins("1", "A.kt"), ins("2", "B.kt"), ins("3", "C.kt")))
        assertEquals(3, s.allInsights().size)
    }
}
