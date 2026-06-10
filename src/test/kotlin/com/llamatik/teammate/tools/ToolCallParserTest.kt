package com.llamatik.teammate.tools

import org.junit.Assert.*
import org.junit.Test

class ToolCallParserTest {

    // ── stripThinking ───────────────────────────────────────────────────────

    @Test
    fun `stripThinking removes complete think block`() {
        val input = "<think>let me reason</think>\n<tool_call name=\"list_files\"><arg name=\"path\">.</arg></tool_call>"
        val result = ToolCallParser.stripThinking(input)
        assertFalse("Should not contain think block", result.contains("<think>"))
        assertTrue("Should retain tool call", result.contains("<tool_call"))
    }

    @Test
    fun `stripThinking removes unclosed think block to end of string`() {
        val input = "some prefix<think>partial reasoning that never closes"
        val result = ToolCallParser.stripThinking(input)
        assertFalse(result.contains("<think>"))
        assertFalse(result.contains("partial reasoning"))
    }

    @Test
    fun `stripThinking removes im_end stop token`() {
        val input = "answer text<|im_end|>"
        val result = ToolCallParser.stripThinking(input)
        assertFalse(result.contains("<|im_end|>"))
    }

    @Test
    fun `stripThinking returns blank for think-only response`() {
        val input = "<think>pure reasoning only</think>"
        val result = ToolCallParser.stripThinking(input)
        assertTrue("Should be blank after stripping", result.isBlank())
    }

    // ── parse (strict XML) ──────────────────────────────────────────────────

    @Test
    fun `parse extracts single tool call with one arg`() {
        val input = """<tool_call name="read_file"><arg name="path">src/Main.kt</arg></tool_call>"""
        val calls = ToolCallParser.parse(input)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("src/Main.kt", calls[0].arguments["path"])
    }

    @Test
    fun `parse extracts multiple tool calls`() {
        val input = """
            <tool_call name="list_files"><arg name="path">.</arg></tool_call>
            <tool_call name="read_file"><arg name="path">src/Foo.kt</arg></tool_call>
        """.trimIndent()
        val calls = ToolCallParser.parse(input)
        assertEquals(2, calls.size)
        assertEquals("list_files", calls[0].name)
        assertEquals("read_file", calls[1].name)
    }

    @Test
    fun `parse extracts write_file with path and content args`() {
        val input = """<tool_call name="write_file"><arg name="path">src/A.kt</arg><arg name="content">fun main() {}</arg></tool_call>"""
        val calls = ToolCallParser.parse(input)
        assertEquals(1, calls.size)
        assertEquals("write_file", calls[0].name)
        assertEquals("src/A.kt", calls[0].arguments["path"])
        assertEquals("fun main() {}", calls[0].arguments["content"])
    }

    @Test
    fun `parse returns empty list for plain prose`() {
        val calls = ToolCallParser.parse("I will now read the file and suggest improvements.")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `parse is case-insensitive on tool_call tag`() {
        val input = """<TOOL_CALL name="list_files"><arg name="path">.</arg></TOOL_CALL>"""
        val calls = ToolCallParser.parse(input)
        assertEquals(1, calls.size)
    }

    // ── parseIncludingIncomplete ────────────────────────────────────────────

    @Test
    fun `parseIncludingIncomplete handles truncated tool call missing closing tag`() {
        val input = """<tool_call name="read_file"><arg name="path">src/Main.kt</arg>"""
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("src/Main.kt", calls[0].arguments["path"])
    }

    @Test
    fun `parseIncludingIncomplete handles plain-text fallback format`() {
        val input = "read_file:\nsrc/Main.kt"
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("src/Main.kt", calls[0].arguments["path"])
    }

    @Test
    fun `parseIncludingIncomplete handles python-style fallback`() {
        val input = """read_file("src/Main.kt")"""
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("src/Main.kt", calls[0].arguments["path"])
    }

    @Test
    fun `parseIncludingIncomplete handles space-separated single arg`() {
        val input = "search_filenames src/"
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        assertEquals(1, calls.size)
        assertEquals("search_filenames", calls[0].name)
        assertEquals("src/", calls[0].arguments["query"])
    }

    @Test
    fun `parseIncludingIncomplete handles space-separated glob pattern`() {
        val input = "search_filenames *.kt"
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        assertEquals(1, calls.size)
        assertEquals("search_filenames", calls[0].name)
        assertEquals("*.kt", calls[0].arguments["query"])
    }

    @Test
    fun `parseIncludingIncomplete handles space-separated list_files`() {
        val input = "list_files src/main"
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        assertEquals(1, calls.size)
        assertEquals("list_files", calls[0].name)
        assertEquals("src/main", calls[0].arguments["path"])
    }

    @Test
    fun `parseIncludingIncomplete ignores hallucinated tool names`() {
        val input = """<tool_call name="diff_tool"><arg name="path">src/A.kt</arg></tool_call>"""
        // diff_tool is not in knownTools; after KNOWN_TOOL_NAMES filter in orchestrator it gets dropped,
        // but parseIncludingIncomplete itself returns it. We test that known tools ARE returned.
        val calls = ToolCallParser.parseIncludingIncomplete(input)
        // parser returns whatever matches; the orchestrator filters with KNOWN_TOOL_NAMES
        assertEquals(1, calls.size)
        assertEquals("diff_tool", calls[0].name)
    }

    @Test
    fun `parseIncludingIncomplete returns empty for pure prose after think strip`() {
        val text = ToolCallParser.stripThinking(
            "<think>I should call list_files first to understand the structure.</think>\n" +
            "Let me start by exploring the project."
        )
        val calls = ToolCallParser.parseIncludingIncomplete(text)
        assertTrue("Pure prose should yield no tool calls", calls.isEmpty())
    }

    @Test
    fun `parseIncludingIncomplete finds XML tool call after think block stripped`() {
        val raw = "<think>reasoning</think>\n<tool_call name=\"list_files\"><arg name=\"path\">.</arg></tool_call>"
        val stripped = ToolCallParser.stripThinking(raw)
        val calls = ToolCallParser.parseIncludingIncomplete(stripped)
        assertEquals(1, calls.size)
        assertEquals("list_files", calls[0].name)
    }

    // ── cleanForDisplay ─────────────────────────────────────────────────────

    @Test
    fun `cleanForDisplay removes tool call XML`() {
        val input = """Here is the result: <tool_call name="read_file"><arg name="path">x</arg></tool_call>"""
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse(clean.contains("<tool_call"))
    }

    @Test
    fun `cleanForDisplay removes edit_proposal XML`() {
        val input = """<edit_proposal path="a.kt" summary="fix"><new_content>code</new_content></edit_proposal>"""
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse(clean.contains("<edit_proposal"))
    }

    @Test
    fun `cleanForDisplay returns think content as fallback when response is think-only`() {
        val input = "<think>only thinking here, model produced nothing else</think>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertTrue("Should fall back to think content", clean.isNotBlank())
        assertFalse(clean.contains("<think>"))
    }

    @Test
    fun `cleanForDisplay strips stray result tags`() {
        val input = "<result tool=\"read_file\">content</result>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse(clean.contains("<result"))
    }

    @Test
    fun `cleanForDisplay suppresses think fallback when allowThinkFallback is false`() {
        // Simulates the code-review case: model produced only think + tool calls, and the
        // orchestrator already has edit proposals — the thinking text must not leak into the panel.
        val input = "<think>Okay, let me plan the code review... Looking at the badges...</think>" +
            """<tool_call name="read_file"><arg name="path">src/Main.kt</arg></tool_call>"""
        val clean = ToolCallParser.cleanForDisplay(input, allowThinkFallback = false)
        assertTrue("Answer panel should be blank when proposals exist", clean.isBlank())
    }

    @Test
    fun `cleanForDisplay still falls back to think content when allowThinkFallback is true`() {
        val input = "<think>only thinking, no answer produced</think>"
        val clean = ToolCallParser.cleanForDisplay(input, allowThinkFallback = true)
        assertTrue("Should fall back to think content when no proposals", clean.isNotBlank())
    }

    // ── cleanForDisplay: orphan closing tag stripping (1.8.0) ───────────────

    @Test
    fun `cleanForDisplay strips orphan closing think tag`() {
        // Happens when stripThinking removes the opening tag via echo-strip but the closing
        // tag was generated after the context window for echo detection.
        val input = "The answer is 42.</think>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse("Orphan </think> must not appear in output", clean.contains("</think>"))
        assertTrue("Actual answer text must be preserved", clean.contains("The answer is 42."))
    }

    @Test
    fun `cleanForDisplay strips orphan closing arg tag`() {
        val input = "Here is my review of the code.</arg>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse("Orphan </arg> must not appear in output", clean.contains("</arg>"))
        assertTrue("Answer text must be preserved", clean.contains("Here is my review"))
    }

    @Test
    fun `cleanForDisplay strips orphan closing tool_call tag`() {
        val input = "I have applied the fix.</tool_call>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse("Orphan </tool_call> must not appear in output", clean.contains("</tool_call>"))
        assertTrue("Answer text must be preserved", clean.contains("I have applied the fix"))
    }

    @Test
    fun `cleanForDisplay strips orphan closing edit_proposal tag`() {
        val input = "Changes proposed successfully.</edit_proposal>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse("Orphan </edit_proposal> must not appear in output", clean.contains("</edit_proposal>"))
        assertTrue("Answer text must be preserved", clean.contains("Changes proposed successfully"))
    }

    @Test
    fun `cleanForDisplay strips multiple orphan closing tags in one response`() {
        val input = "Done.</think> All files updated.</arg> Check the diff.</tool_call>"
        val clean = ToolCallParser.cleanForDisplay(input)
        assertFalse(clean.contains("</think>"))
        assertFalse(clean.contains("</arg>"))
        assertFalse(clean.contains("</tool_call>"))
    }
}
