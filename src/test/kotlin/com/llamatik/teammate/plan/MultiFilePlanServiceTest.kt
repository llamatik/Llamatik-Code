package com.llamatik.teammate.plan

import org.junit.Assert.*
import org.junit.Test

class MultiFilePlanServiceTest {

    private fun makePlan(
        id: String,
        title: String = "Plan $id",
        approved: Boolean = false,
        rejected: Boolean = false,
        files: List<MultiFilePlanService.PlannedFile> = emptyList(),
    ) = MultiFilePlanService.AgentPlan(
        id = id, title = title, goal = "Goal of $id",
        files = files.toMutableList(),
        approved = approved, rejected = rejected,
    )

    // ── currentPlan ───────────────────────────────────────────────────────────

    @Test
    fun `currentPlan returns null when list is empty`() {
        val plans = mutableListOf<MultiFilePlanService.AgentPlan>()
        val current = plans.lastOrNull { !it.approved && !it.rejected }
        assertNull(current)
    }

    @Test
    fun `currentPlan returns last non-approved non-rejected plan`() {
        val plans = mutableListOf(makePlan("1"), makePlan("2"), makePlan("3"))
        val current = plans.lastOrNull { !it.approved && !it.rejected }
        assertEquals("3", current?.id)
    }

    @Test
    fun `currentPlan ignores approved plans`() {
        val plans = mutableListOf(makePlan("1"), makePlan("2", approved = true))
        val current = plans.lastOrNull { !it.approved && !it.rejected }
        assertEquals("1", current?.id)
    }

    @Test
    fun `currentPlan ignores rejected plans`() {
        val plans = mutableListOf(makePlan("1", rejected = true), makePlan("2"))
        val current = plans.lastOrNull { !it.approved && !it.rejected }
        assertEquals("2", current?.id)
    }

    @Test
    fun `currentPlan returns null when all plans are approved or rejected`() {
        val plans = mutableListOf(makePlan("1", approved = true), makePlan("2", rejected = true))
        val current = plans.lastOrNull { !it.approved && !it.rejected }
        assertNull(current)
    }

    // ── addPlan ───────────────────────────────────────────────────────────────

    @Test
    fun `addPlan replaces existing plan with same id`() {
        val plans = mutableListOf(makePlan("a", title = "Original"))
        val updated = makePlan("a", title = "Updated")
        plans.removeIf { it.id == updated.id }
        plans.add(updated)
        assertEquals(1, plans.size)
        assertEquals("Updated", plans[0].title)
    }

    @Test
    fun `addPlan caps list at 20 entries`() {
        val plans = mutableListOf<MultiFilePlanService.AgentPlan>()
        repeat(21) { i -> plans.add(makePlan("p$i")) }
        if (plans.size > 20) plans.removeAt(0)
        assertEquals(20, plans.size)
    }

    // ── approvePlan / rejectPlan ──────────────────────────────────────────────

    @Test
    fun `approvePlan sets approved flag on matching plan`() {
        val plans = mutableListOf(makePlan("x"), makePlan("y"))
        plans.find { it.id == "x" }?.approved = true
        assertTrue(plans.find { it.id == "x" }!!.approved)
        assertFalse(plans.find { it.id == "y" }!!.approved)
    }

    @Test
    fun `rejectPlan sets rejected flag on matching plan`() {
        val plans = mutableListOf(makePlan("x"), makePlan("y"))
        plans.find { it.id == "y" }?.rejected = true
        assertTrue(plans.find { it.id == "y" }!!.rejected)
        assertFalse(plans.find { it.id == "x" }!!.rejected)
    }

    @Test
    fun `approvePlan is a no-op for unknown id`() {
        val plans = mutableListOf(makePlan("x"))
        plans.find { it.id == "does-not-exist" }?.approved = true
        assertFalse(plans[0].approved)
    }

    // ── formatPlanForDisplay ──────────────────────────────────────────────────

    private fun formatPlan(plan: MultiFilePlanService.AgentPlan): String = buildString {
        appendLine("Plan: ${plan.title}")
        appendLine("Goal: ${plan.goal}")
        appendLine()
        appendLine("Files (${plan.files.size}):")
        plan.files.forEach { f ->
            appendLine("  [${f.action.uppercase()}] ${f.path}  risk:${f.risk}")
            if (f.reason.isNotBlank()) appendLine("    → ${f.reason}")
        }
        if (plan.verificationSteps.isNotEmpty()) {
            appendLine()
            appendLine("Verification:")
            plan.verificationSteps.forEach { appendLine("  - $it") }
        }
        if (plan.rollbackInfo.isNotBlank()) { appendLine(); appendLine("Rollback: ${plan.rollbackInfo}") }
    }.trim()

    @Test
    fun `formatPlanForDisplay shows title and goal`() {
        val plan = makePlan("1", title = "Add auth middleware")
        val output = formatPlan(plan)
        assertTrue(output.contains("Add auth middleware"))
        assertTrue(output.contains("Goal of 1"))
    }

    @Test
    fun `formatPlanForDisplay shows file action in uppercase`() {
        val plan = makePlan("1", files = listOf(
            MultiFilePlanService.PlannedFile("src/Auth.kt", "create", "New auth class", "low")
        ))
        val output = formatPlan(plan)
        assertTrue(output.contains("[CREATE]"))
        assertTrue(output.contains("src/Auth.kt"))
    }

    @Test
    fun `formatPlanForDisplay shows verification steps`() {
        val plan = makePlan("1").copy(
            verificationSteps = mutableListOf("Run ./gradlew test", "Check auth flow manually")
        )
        val output = formatPlan(plan)
        assertTrue(output.contains("Verification"))
        assertTrue(output.contains("Run ./gradlew test"))
    }

    @Test
    fun `formatPlanForDisplay shows rollback info when present`() {
        val plan = makePlan("1").copy(rollbackInfo = "git revert HEAD")
        val output = formatPlan(plan)
        assertTrue(output.contains("Rollback"))
        assertTrue(output.contains("git revert HEAD"))
    }

    @Test
    fun `formatPlanForDisplay omits rollback section when blank`() {
        val plan = makePlan("1")
        val output = formatPlan(plan)
        assertFalse(output.contains("Rollback"))
    }

    // ── parsePlanFromText ─────────────────────────────────────────────────────

    private fun parsePlan(text: String): MultiFilePlanService.AgentPlan? {
        val title = Regex("""(?i)PLAN[_\s]TITLE:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: return null
        val goal = Regex("""(?i)PLAN[_\s]GOAL:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val fileRegex = Regex("""(?i)FILE:\s*([^\|]+)\|([^\|]+)\|([^\|]+)\|(.*)""")
        val files = fileRegex.findAll(text).map { m ->
            MultiFilePlanService.PlannedFile(
                path = m.groupValues[1].trim(),
                action = m.groupValues[2].trim().lowercase(),
                risk = m.groupValues[3].trim().lowercase(),
                reason = m.groupValues[4].trim(),
            )
        }.toMutableList()
        val steps = Regex("""(?i)VERIFY:\s*(.+)""").findAll(text).map { it.groupValues[1].trim() }.toMutableList()
        val rollback = Regex("""(?i)ROLLBACK:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        return MultiFilePlanService.AgentPlan(
            id = "test", title = title, goal = goal, files = files,
            verificationSteps = steps, rollbackInfo = rollback,
        )
    }

    @Test
    fun `parsePlanFromText returns null when PLAN_TITLE is missing`() {
        val text = "PLAN_GOAL: Add authentication\nFILE: src/Auth.kt|create|low|New file"
        assertNull(parsePlan(text))
    }

    @Test
    fun `parsePlanFromText extracts title and goal`() {
        val text = """
            PLAN_TITLE: Add authentication middleware
            PLAN_GOAL: Secure all API endpoints
        """.trimIndent()
        val plan = parsePlan(text)
        assertNotNull(plan)
        assertEquals("Add authentication middleware", plan!!.title)
        assertEquals("Secure all API endpoints", plan.goal)
    }

    @Test
    fun `parsePlanFromText extracts FILE entries`() {
        val text = """
            PLAN_TITLE: Refactor service
            PLAN_GOAL: Improve structure
            FILE: src/AuthService.kt | edit | medium | Refactor auth logic
            FILE: src/NewHelper.kt | create | low | Extract helper class
        """.trimIndent()
        val plan = parsePlan(text)!!
        assertEquals(2, plan.files.size)
        assertEquals("src/AuthService.kt", plan.files[0].path)
        assertEquals("edit", plan.files[0].action)
        assertEquals("medium", plan.files[0].risk)
        assertEquals("src/NewHelper.kt", plan.files[1].path)
        assertEquals("create", plan.files[1].action)
    }

    @Test
    fun `parsePlanFromText extracts VERIFY steps`() {
        val text = """
            PLAN_TITLE: Add tests
            VERIFY: Run ./gradlew test
            VERIFY: Check coverage report
        """.trimIndent()
        val plan = parsePlan(text)!!
        assertEquals(2, plan.verificationSteps.size)
        assertTrue(plan.verificationSteps.contains("Run ./gradlew test"))
    }

    @Test
    fun `parsePlanFromText extracts ROLLBACK info`() {
        val text = """
            PLAN_TITLE: Risky migration
            ROLLBACK: git revert HEAD~2
        """.trimIndent()
        val plan = parsePlan(text)!!
        assertEquals("git revert HEAD~2", plan.rollbackInfo)
    }

    @Test
    fun `parsePlanFromText is case-insensitive for keywords`() {
        val text = """
            plan_title: lowercase title
            plan_goal: lowercase goal
        """.trimIndent()
        val plan = parsePlan(text)
        assertNotNull(plan)
        assertEquals("lowercase title", plan!!.title)
    }
}
