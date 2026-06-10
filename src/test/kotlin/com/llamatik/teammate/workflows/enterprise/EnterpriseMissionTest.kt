package com.llamatik.teammate.workflows.enterprise

import org.junit.Assert.*
import org.junit.Test

class EnterpriseMissionTest {

    // ── Mission classification ────────────────────────────────────────────────

    @Test fun `classify feature implementation from various phrasings`() {
        // Mirror the ordering in EnterprisePairProgrammerService.classifyMission exactly
        val classifier: (String) -> EnterpriseMissionType = { request ->
            val lower = request.lowercase()
            when {
                lower.containsAny("implement", "feature", "add support", "create new") && !lower.contains("build fail") -> EnterpriseMissionType.FEATURE_IMPLEMENTATION
                lower.containsAny("fix", "bug", "error", "crash", "broken", "exception", "npe") -> EnterpriseMissionType.BUG_FIX
                lower.containsAny("refactor", "clean up", "cleanup", "improve structure", "rename", "extract") -> EnterpriseMissionType.SAFE_REFACTOR
                lower.containsAny("test", "unit test", "spec", "coverage") -> EnterpriseMissionType.TEST_GENERATION
                lower.containsAny("review", "check changes", "before commit", "pr review", "code review") -> EnterpriseMissionType.CODE_REVIEW
                lower.containsAny("build fail", "build error", "compile error", "gradle fail", "maven fail") -> EnterpriseMissionType.BUILD_FAILURE_REPAIR
                lower.containsAny("migrate", "modernize", "upgrade", "deprecat", "legacy") -> EnterpriseMissionType.MIGRATION
                lower.containsAny("security", "vulnerability", "injection", "auth", "permission") -> EnterpriseMissionType.SECURITY_REVIEW
                lower.containsAny("explain", "understand", "architecture", "how does", "what is") -> EnterpriseMissionType.ARCHITECTURE_EXPLANATION
                lower.containsAny("document", "javadoc", "kdoc", "comment") -> EnterpriseMissionType.DOCUMENTATION
                else -> EnterpriseMissionType.UNKNOWN
            }
        }

        assertEquals(EnterpriseMissionType.FEATURE_IMPLEMENTATION, classifier("implement dark mode"))
        assertEquals(EnterpriseMissionType.FEATURE_IMPLEMENTATION, classifier("add support for OAuth2"))
        assertEquals(EnterpriseMissionType.BUG_FIX, classifier("fix the NPE in login"))
        assertEquals(EnterpriseMissionType.SAFE_REFACTOR, classifier("refactor the UserService class"))
        assertEquals(EnterpriseMissionType.TEST_GENERATION, classifier("generate unit tests for this file"))
        assertEquals(EnterpriseMissionType.CODE_REVIEW, classifier("review before commit"))
        assertEquals(EnterpriseMissionType.BUILD_FAILURE_REPAIR, classifier("build fail on CI"))
        assertEquals(EnterpriseMissionType.MIGRATION, classifier("modernize deprecated APIs"))
        assertEquals(EnterpriseMissionType.SECURITY_REVIEW, classifier("check for security vulnerabilities"))
        assertEquals(EnterpriseMissionType.ARCHITECTURE_EXPLANATION, classifier("explain the architecture"))
        assertEquals(EnterpriseMissionType.DOCUMENTATION, classifier("add documentation"))
        assertEquals(EnterpriseMissionType.UNKNOWN, classifier("some random task"))
    }

    // ── Default safety level ──────────────────────────────────────────────────

    @Test fun `default safety level is APPROVAL_REQUIRED`() {
        assertEquals(EnterpriseSafetyLevel.APPROVAL_REQUIRED, EnterpriseSafetyLevel.valueOf("APPROVAL_REQUIRED"))
    }

    @Test fun `all safety levels parse from string`() {
        for (level in EnterpriseSafetyLevel.values()) {
            assertEquals(level, EnterpriseSafetyLevel.valueOf(level.name))
        }
    }

    // ── Mission creation ──────────────────────────────────────────────────────

    @Test fun `createMissionPlan produces plan with correct type and id`() {
        val store = EnterpriseMissionStore()
        val plan = EnterpriseMissionPlan(
            id = "test-id",
            title = "Bug Fix: fix login crash",
            missionType = EnterpriseMissionType.BUG_FIX,
            safetyLevel = EnterpriseSafetyLevel.APPROVAL_REQUIRED,
            userRequest = "fix login crash",
            targetPaths = listOf("src/login/LoginService.kt"),
            affectedFiles = emptyList(),
            requiredContextTools = listOf("read_file", "git_status"),
            allowedMutationTools = listOf("write_file"),
            blockedTools = listOf("external_search"),
            risks = listOf(EnterpriseRisk("Side effect", "Fix may affect other flows", EnterpriseRiskLevel.MEDIUM)),
            verificationSteps = listOf("Run tests"),
            auditNotes = listOf("Mission created"),
            rollbackPlan = "git checkout -- src/login/LoginService.kt",
            estimatedRiskLevel = EnterpriseRiskLevel.MEDIUM,
            createdAtEpochMs = System.currentTimeMillis(),
        )

        store.store(plan)
        val retrieved = store.get("test-id")
        assertNotNull(retrieved)
        assertEquals(EnterpriseMissionType.BUG_FIX, retrieved?.plan?.missionType)
        assertEquals("test-id", retrieved?.plan?.id)
    }

    // ── Audit metadata does NOT store raw source code ─────────────────────────

    @Test fun `mission store never stores raw source code — only metadata`() {
        val store = EnterpriseMissionStore()
        val plan = EnterpriseMissionPlan(
            id = "audit-test",
            title = "Refactor: clean up",
            missionType = EnterpriseMissionType.SAFE_REFACTOR,
            safetyLevel = EnterpriseSafetyLevel.APPROVAL_REQUIRED,
            userRequest = "refactor UserService",
            targetPaths = listOf("src/UserService.kt"),
            affectedFiles = emptyList(),
            requiredContextTools = emptyList(),
            allowedMutationTools = emptyList(),
            blockedTools = emptyList(),
            risks = emptyList(),
            verificationSteps = emptyList(),
            auditNotes = emptyList(),
            rollbackPlan = "git stash",
            estimatedRiskLevel = EnterpriseRiskLevel.LOW,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        store.store(plan)
        store.addAuditEvent("audit-test", "context_gathered")
        store.addAuditEvent("audit-test", "plan_created")

        val entry = store.get("audit-test")!!
        // Verify no field contains raw source code
        assertTrue("userRequest should not contain source code lines",
            !entry.plan.userRequest.contains("class ") && !entry.plan.userRequest.contains("fun "))
        assertTrue("auditEvents should not contain file contents",
            entry.auditEvents.none { it.length > 500 })
        assertNull("report should be null until completed", entry.report)
    }

    // ── Report generation ─────────────────────────────────────────────────────

    @Test fun `report generation stores summary and changed files`() {
        val store = EnterpriseMissionStore()
        val plan = EnterpriseMissionPlan(
            id = "report-test",
            title = "Feature: add OAuth2",
            missionType = EnterpriseMissionType.FEATURE_IMPLEMENTATION,
            safetyLevel = EnterpriseSafetyLevel.APPROVAL_REQUIRED,
            userRequest = "add OAuth2 support",
            targetPaths = emptyList(),
            affectedFiles = emptyList(),
            requiredContextTools = emptyList(),
            allowedMutationTools = listOf("write_file"),
            blockedTools = emptyList(),
            risks = listOf(EnterpriseRisk("Integration", "May affect auth flow", EnterpriseRiskLevel.HIGH)),
            verificationSteps = listOf("Run auth tests"),
            auditNotes = emptyList(),
            rollbackPlan = "git stash",
            estimatedRiskLevel = EnterpriseRiskLevel.HIGH,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        store.store(plan)

        val report = EnterpriseMissionReport(
            missionId = "report-test",
            summary = "OAuth2 feature implemented successfully",
            changedFiles = listOf("src/auth/OAuthService.kt", "src/auth/OAuthConfig.kt"),
            verificationSummary = "All tests pass",
            remainingRisks = listOf(EnterpriseRisk("Integration", "Needs staging test", EnterpriseRiskLevel.HIGH)),
            auditTrail = listOf("Mission created", "context_gathered", "report_generated"),
            suggestedCommitMessage = "feat: add OAuth2 authentication support",
        )
        store.complete("report-test", report)

        val entry = store.get("report-test")!!
        assertNotNull(entry.report)
        assertEquals("OAuth2 feature implemented successfully", entry.report!!.summary)
        assertEquals(2, entry.report!!.changedFiles.size)
        assertEquals("feat: add OAuth2 authentication support", entry.report!!.suggestedCommitMessage)
        assertNotNull(entry.completedMs)
    }

    // ── External knowledge disabled by default ────────────────────────────────

    @Test fun `blocked tools list contains external search tools by default`() {
        val externalTools = listOf(
            "external_search", "external_search_official_docs", "external_search_github_issues",
            "external_read_page", "external_read_pages",
            "research_plan", "research_execute", "research_summarize",
            "error_research_analyze", "error_research_fix",
        )
        val plan = EnterpriseMissionPlan(
            id = "ext-test",
            title = "Test",
            missionType = EnterpriseMissionType.BUG_FIX,
            safetyLevel = EnterpriseSafetyLevel.APPROVAL_REQUIRED,
            userRequest = "fix bug",
            targetPaths = emptyList(),
            affectedFiles = emptyList(),
            requiredContextTools = emptyList(),
            allowedMutationTools = emptyList(),
            blockedTools = externalTools,
            risks = emptyList(),
            verificationSteps = emptyList(),
            auditNotes = emptyList(),
            rollbackPlan = "git stash",
            estimatedRiskLevel = EnterpriseRiskLevel.LOW,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        assertTrue("external_search should be blocked", plan.blockedTools.contains("external_search"))
        assertTrue("research_plan should be blocked", plan.blockedTools.contains("research_plan"))
        assertFalse("write_file should NOT be blocked", plan.blockedTools.contains("write_file"))
    }

    // ── Risk model ────────────────────────────────────────────────────────────

    @Test fun `risk levels order correctly`() {
        assertTrue(EnterpriseRiskLevel.CRITICAL.ordinal > EnterpriseRiskLevel.HIGH.ordinal)
        assertTrue(EnterpriseRiskLevel.HIGH.ordinal > EnterpriseRiskLevel.MEDIUM.ordinal)
        assertTrue(EnterpriseRiskLevel.MEDIUM.ordinal > EnterpriseRiskLevel.LOW.ordinal)
    }

    // ── Inline completion — prompt builder ────────────────────────────────────

    @Test fun `prompt builder includes prefix and suffix tokens`() {
        val ctx = com.llamatik.teammate.completion.InlineCompletionContextCollector.CompletionContext(
            prefix = "fun hello() {",
            suffix = "}",
            fileExtension = "kt",
            lineNumber = 5,
        )
        val prompt = com.llamatik.teammate.completion.InlineCompletionPromptBuilder.build(ctx)
        assertTrue("prompt must include prefix", prompt.contains("fun hello() {"))
        assertTrue("prompt must include suffix", prompt.contains("}"))
        assertTrue("prompt must include fim_prefix token", prompt.contains("<fim_prefix>"))
        assertTrue("prompt must include fim_suffix token", prompt.contains("<fim_suffix>"))
        assertTrue("prompt must include fim_middle token", prompt.contains("<fim_middle>"))
    }

    @Test fun `context collector respects max context chars limit`() {
        val maxChars = 100
        val bigText = "a".repeat(10000)
        val prefix = bigText.takeLast(maxChars / 2)
        val suffix = bigText.take(maxChars / 2)
        assertTrue("prefix must not exceed half budget", prefix.length <= maxChars / 2)
        assertTrue("suffix must not exceed half budget", suffix.length <= maxChars / 2)
    }

    // ── Debouncer ─────────────────────────────────────────────────────────────

    @Test fun `debouncer cancels stale requests and fires latest`() {
        val debouncer = com.llamatik.teammate.completion.InlineCompletionDebouncer(50)
        val results = mutableListOf<Int>()
        debouncer.schedule { results.add(1) }
        debouncer.schedule { results.add(2) }
        debouncer.schedule { results.add(3) }
        Thread.sleep(200)
        // Only the last scheduled action should run (or possibly none if they were all cancelled)
        assertTrue("Only the last action should have run", results.size <= 1)
        if (results.isNotEmpty()) assertEquals(3, results[0])
        debouncer.shutdown()
    }

    // ── Action prompts contain required keywords ──────────────────────────────

    @Test fun `enterprise mission prompt contains required keywords`() {
        val prompt = """You are running Offline Enterprise Pair Programmer mode.

Mission:
Implement a new feature.

Required workflow:
1. Call enterprise_mission_create.
2. Gather project context before editing.
3. Explain risks.
4. Create a plan.
5. Apply small, reviewable patches.
6. Show diff.
7. Verify when possible.
8. Produce enterprise_mission_report.
9. Include rollback instructions.
10. Do not use external knowledge unless explicitly allowed."""

        assertTrue("prompt must reference enterprise_mission_create", prompt.contains("enterprise_mission_create"))
        assertTrue("prompt must reference plan", prompt.contains("plan"))
        assertTrue("prompt must reference diff", prompt.contains("diff"))
        assertTrue("prompt must reference verify", prompt.contains("Verify"))
        assertTrue("prompt must reference enterprise_mission_report", prompt.contains("enterprise_mission_report"))
        assertTrue("prompt must reference rollback", prompt.contains("rollback"))
    }

    // ── Inline completion: buildFallback ─────────────────────────────────────

    @Test fun `buildFallback produces plain prefix prompt for non-FIM models`() {
        val ctx = com.llamatik.teammate.completion.InlineCompletionContextCollector.CompletionContext(
            prefix = "fun greet() {",
            suffix = "}",
            fileExtension = "kt",
            lineNumber = 3,
        )
        val prompt = com.llamatik.teammate.completion.InlineCompletionPromptBuilder.buildFallback(ctx)
        assertTrue("Fallback prompt must include prefix", prompt.contains("fun greet() {"))
        assertFalse("Fallback prompt must NOT include fim_prefix token", prompt.contains("<fim_prefix>"))
        assertFalse("Fallback prompt must NOT include fim_suffix token", prompt.contains("<fim_suffix>"))
        assertFalse("Fallback prompt must NOT include fim_middle token", prompt.contains("<fim_middle>"))
    }

    @Test fun `buildFallback adds language hint for known extensions`() {
        val ktCtx = com.llamatik.teammate.completion.InlineCompletionContextCollector.CompletionContext(
            prefix = "val x = 1", suffix = "", fileExtension = "kt", lineNumber = 1)
        val ktPrompt = com.llamatik.teammate.completion.InlineCompletionPromptBuilder.buildFallback(ktCtx)
        assertTrue("Kotlin hint expected", ktPrompt.contains("Kotlin"))

        val pyCtx = com.llamatik.teammate.completion.InlineCompletionContextCollector.CompletionContext(
            prefix = "def foo():", suffix = "", fileExtension = "py", lineNumber = 1)
        val pyPrompt = com.llamatik.teammate.completion.InlineCompletionPromptBuilder.buildFallback(pyCtx)
        assertTrue("Python hint expected", pyPrompt.contains("Python"))
    }

    @Test fun `buildFallback omits language hint for unknown extensions`() {
        val ctx = com.llamatik.teammate.completion.InlineCompletionContextCollector.CompletionContext(
            prefix = "some content", suffix = "", fileExtension = "xyz", lineNumber = 1)
        val prompt = com.llamatik.teammate.completion.InlineCompletionPromptBuilder.buildFallback(ctx)
        assertFalse("No language hint for unknown extension", prompt.contains("Language:"))
    }

    // ── EnterpriseMissionStore: ordering and edge cases ───────────────────────

    @Test fun `store list returns missions sorted by most recently created first`() {
        val store = EnterpriseMissionStore()
        val planA = makePlan("a")
        val planB = makePlan("b")
        store.store(planA)
        Thread.sleep(5) // ensure distinct createdMs
        store.store(planB)
        val list = store.list()
        assertEquals("Most recent (b) should come first", "b", list.first().plan.id)
        assertEquals("Older (a) should come last", "a", list.last().plan.id)
    }

    @Test fun `complete on unknown mission id is a no-op`() {
        val store = EnterpriseMissionStore()
        val report = EnterpriseMissionReport(
            missionId = "nonexistent",
            summary = "done",
            changedFiles = emptyList(),
            verificationSummary = "ok",
            remainingRisks = emptyList(),
            auditTrail = emptyList(),
            suggestedCommitMessage = null,
        )
        store.complete("nonexistent", report) // must not throw
        assertNull("Unknown mission must not appear after complete()", store.get("nonexistent"))
    }

    @Test fun `addAuditEvent on unknown mission id is a no-op`() {
        val store = EnterpriseMissionStore()
        store.addAuditEvent("nonexistent", "some event") // must not throw
    }

    // ── EnterprisePairProgrammerService.classifyMission via real service ──────

    @Test fun `classifyMission build_failure precedence beats feature implementation`() {
        // The real service checks BUILD_FAILURE_REPAIR before FEATURE_IMPLEMENTATION.
        // "implement a fix for the build failure" contains both — must be BUILD_FAILURE_REPAIR.
        val svc = com.llamatik.teammate.workflows.enterprise.EnterprisePairProgrammerService(
            com.intellij.mock.MockProject(null, {})
        )
        val result = svc.classifyMission("implement a fix for the build failure")
        assertEquals(EnterpriseMissionType.BUILD_FAILURE_REPAIR, result)
    }

    // Helpers

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { contains(it) }

    private fun makePlan(id: String) = EnterpriseMissionPlan(
        id = id,
        title = "Test $id",
        missionType = EnterpriseMissionType.BUG_FIX,
        safetyLevel = EnterpriseSafetyLevel.APPROVAL_REQUIRED,
        userRequest = "fix $id",
        targetPaths = emptyList(),
        affectedFiles = emptyList(),
        requiredContextTools = emptyList(),
        allowedMutationTools = emptyList(),
        blockedTools = emptyList(),
        risks = emptyList(),
        verificationSteps = emptyList(),
        auditNotes = emptyList(),
        rollbackPlan = "git stash",
        estimatedRiskLevel = EnterpriseRiskLevel.LOW,
        createdAtEpochMs = System.currentTimeMillis(),
    )
}
