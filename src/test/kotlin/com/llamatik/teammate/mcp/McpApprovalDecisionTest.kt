package com.llamatik.teammate.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpApprovalDecisionTest {

    private fun approval(
        isPro: Boolean,
        risk: McpRiskLevel,
        explicitTool: Boolean = false,
        serverLevel: Boolean = false,
    ) = McpPermissionService.computeApprovalRequired(
        isPro = isPro,
        riskLevel = risk,
        isExplicitlyAutoApproved = explicitTool,
        isServerAutoApproved = serverLevel,
    )

    // ── Free tier — always requires approval ──────────────────────────────────

    @Test fun `free - LOW risk still requires approval`() =
        assertTrue(approval(isPro = false, risk = McpRiskLevel.LOW))

    @Test fun `free - MEDIUM risk requires approval`() =
        assertTrue(approval(isPro = false, risk = McpRiskLevel.MEDIUM))

    @Test fun `free - HIGH risk requires approval`() =
        assertTrue(approval(isPro = false, risk = McpRiskLevel.HIGH))

    @Test fun `free - explicit tool approval flag has no effect`() =
        assertTrue(approval(isPro = false, risk = McpRiskLevel.LOW, explicitTool = true))

    @Test fun `free - server-level approval flag has no effect`() =
        assertTrue(approval(isPro = false, risk = McpRiskLevel.LOW, serverLevel = true))

    // ── Pro tier — default (no auto-approval configured) ─────────────────────

    @Test fun `pro - LOW risk requires approval when no auto-approval configured`() =
        assertTrue(approval(isPro = true, risk = McpRiskLevel.LOW))

    @Test fun `pro - MEDIUM risk requires approval when no auto-approval configured`() =
        assertTrue(approval(isPro = true, risk = McpRiskLevel.MEDIUM))

    @Test fun `pro - HIGH risk requires approval when no auto-approval configured`() =
        assertTrue(approval(isPro = true, risk = McpRiskLevel.HIGH))

    // ── Pro tier — explicit per-tool approval ─────────────────────────────────

    @Test fun `pro - LOW risk skips approval when tool is explicitly approved`() =
        assertFalse(approval(isPro = true, risk = McpRiskLevel.LOW, explicitTool = true))

    @Test fun `pro - MEDIUM risk skips approval when tool is explicitly approved`() =
        assertFalse(approval(isPro = true, risk = McpRiskLevel.MEDIUM, explicitTool = true))

    @Test fun `pro - HIGH risk skips approval when tool is explicitly approved`() =
        assertFalse(approval(isPro = true, risk = McpRiskLevel.HIGH, explicitTool = true))

    // ── Pro tier — server-level approval ─────────────────────────────────────

    @Test fun `pro - LOW risk skips approval when server-level approval is on`() =
        assertFalse(approval(isPro = true, risk = McpRiskLevel.LOW, serverLevel = true))

    @Test fun `pro - MEDIUM risk skips approval when server-level approval is on`() =
        assertFalse(approval(isPro = true, risk = McpRiskLevel.MEDIUM, serverLevel = true))

    @Test fun `pro - HIGH risk still requires approval even with server-level approval`() =
        assertTrue(approval(isPro = true, risk = McpRiskLevel.HIGH, serverLevel = true))

    @Test fun `pro - HIGH risk skips approval only when explicitly approved per-tool`() =
        assertFalse(approval(isPro = true, risk = McpRiskLevel.HIGH, explicitTool = true, serverLevel = true))
}
