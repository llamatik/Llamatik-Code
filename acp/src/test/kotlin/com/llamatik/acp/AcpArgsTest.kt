package com.llamatik.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AcpArgsTest {
    @Test
    fun `defaults`() {
        val o = AcpArgs.parse(emptyList())
        assertNull(o.model); assertNull(o.server); assertFalse(o.autoApprove)
    }

    @Test
    fun `parses model, server and inference flags`() {
        val o = AcpArgs.parse(listOf("--server=box:9000", "-m", "a.gguf", "--auto-approve", "--context", "8192", "--gpu-layers=-1", "--aggressive"))
        assertEquals("box:9000", o.server); assertEquals("a.gguf", o.model)
        assertTrue(o.autoApprove); assertTrue(o.aggressive)
        assertEquals(8192, o.params.contextSize); assertEquals(-1, o.params.gpuLayers)
    }

    @Test
    fun `reports bad usage`() {
        listOf(listOf("--nope"), listOf("-m"), listOf("--threads", "x"), listOf("stray")).forEach {
            try { AcpArgs.parse(it); fail("expected a usage error for $it") } catch (_: UsageException) {}
        }
    }
}
