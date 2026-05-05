package com.llamatik.teammate.agent

import org.junit.Assert.*
import org.junit.Test

class AgentFallbackPolicyTest {

    @Test
    fun `codeSearchFallbacks returns non-empty list`() {
        val fallbacks = AgentFallbackPolicy.codeSearchFallbacks("find the authentication logic")
        assertTrue("Expected fallback steps", fallbacks.isNotEmpty())
    }

    @Test
    fun `codeSearchFallbacks first step is a search tool call`() {
        val fallbacks = AgentFallbackPolicy.codeSearchFallbacks("login validation")
        val first = fallbacks.first()
        assertTrue("First fallback should use a search tool", first.toolName.contains("search"))
    }

    @Test
    fun `codeSearchFallbacks includes list_files step`() {
        val fallbacks = AgentFallbackPolicy.codeSearchFallbacks("UserRepository")
        val tools = fallbacks.map { it.toolName }
        assertTrue("Should include list_files as fallback", tools.any { it == "list_files" })
    }

    @Test
    fun `codeSearchFallbacks broadens the query`() {
        val original = "find the authentication login validation code"
        val fallbacks = AgentFallbackPolicy.codeSearchFallbacks(original)
        val broadened = fallbacks.firstOrNull { it.argument != original }
        assertNotNull("Should produce a broadened query argument", broadened)
        assertTrue("Broadened query should be shorter", broadened!!.argument.length < original.length)
    }

    @Test
    fun `missingTestFallbacks returns steps including list_files src directories`() {
        val fallbacks = AgentFallbackPolicy.missingTestFallbacks()
        val tools = fallbacks.map { it.toolName }
        assertTrue("Should list src files", tools.any { it == "list_files" })
    }

    @Test
    fun `missingTestFallbacks includes tests_missing_analyze`() {
        val fallbacks = AgentFallbackPolicy.missingTestFallbacks()
        val tools = fallbacks.map { it.toolName }
        assertTrue("Should include tests_missing_analyze", tools.contains("tests_missing_analyze"))
    }

    @Test
    fun `projectStructureFallbacks returns steps with list_files`() {
        val fallbacks = AgentFallbackPolicy.projectStructureFallbacks()
        val tools = fallbacks.map { it.toolName }
        assertTrue("Should include list_files", tools.any { it == "list_files" })
    }

    @Test
    fun `aggressiveModeSystemPromptAddition is non-empty`() {
        val prompt = AgentFallbackPolicy.aggressiveModeSystemPromptAddition()
        assertTrue("Should produce a non-empty prompt addition", prompt.isNotBlank())
    }

    @Test
    fun `aggressiveModeSystemPromptAddition mentions fallback`() {
        val prompt = AgentFallbackPolicy.aggressiveModeSystemPromptAddition()
        assertTrue("Should mention fallback strategy", prompt.contains("fallback", ignoreCase = true))
    }
}
