package com.llamatik.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Path

class CliArgsTest {
    @Test
    fun `defaults start an interactive session`() {
        val o = CliArgs.parse(emptyList())
        assertNull(o.prompt); assertNull(o.model); assertNull(o.server)
        assertFalse(o.yes)
    }

    @Test
    fun `parses model, server, prompt and flags in both spellings`() {
        val o = CliArgs.parse(listOf("-m", "a.gguf", "--server=box:9000", "-p", "fix the bug", "-y", "-C", "/tmp/p", "--aggressive"))
        assertEquals("a.gguf", o.model)
        assertEquals("box:9000", o.server)
        assertEquals("fix the bug", o.prompt)
        assertTrue(o.yes); assertTrue(o.aggressive)
        assertEquals(Path.of("/tmp/p"), o.workDir)
    }

    @Test
    fun `parses inference parameters`() {
        val o = CliArgs.parse(listOf("--temperature", "0.2", "--max-tokens=512", "--context", "8192", "--threads", "3", "--gpu-layers", "-1"))
        assertEquals(0.2, o.params.temperature, 0.0)
        assertEquals(512, o.params.maxTokens)
        assertEquals(8192, o.params.contextSize)
        assertEquals(3, o.params.threads)
        assertEquals(-1, o.params.gpuLayers)
    }

    @Test
    fun `reports bad usage`() {
        listOf(
            listOf("--bogus") to "Unknown option",
            listOf("-p") to "Missing value",
            listOf("--context", "lots") to "whole number",
            listOf("--temperature", "hot") to "number",
            listOf("fix the bug") to "Unexpected argument",
        ).forEach { (args, message) ->
            try { CliArgs.parse(args); fail("expected a usage error for $args") } catch (e: UsageException) {
                assertTrue("${e.message} should mention '$message'", e.message!!.contains(message))
            }
        }
    }

    @Test
    fun `help and version flags`() {
        assertTrue(CliArgs.parse(listOf("-h")).help)
        assertTrue(CliArgs.parse(listOf("--version")).version)
    }

    @Test
    fun `discover is a flag of its own and cannot be combined with an explicit model or server`() {
        assertTrue(CliArgs.parse(listOf("--discover", "-p", "hi")).discover)
        listOf(listOf("--discover", "-s", "box"), listOf("-m", "a.gguf", "--discover")).forEach {
            try { CliArgs.parse(it); fail("expected a usage error for $it") } catch (e: UsageException) {
                assertTrue(e.message!!.contains("--discover"))
            }
        }
    }
}
