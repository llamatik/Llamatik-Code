package com.llamatik.agent.core.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellAndAllowlistTest {
    @get:Rule val tmp = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `allowlist accepts exact commands, commands with arguments and module qualified gradle tasks`() {
        assertTrue(CommandAllowlist.isAllowed("./gradlew test"))
        assertTrue(CommandAllowlist.isAllowed("  ./gradlew test --info "))
        assertTrue(CommandAllowlist.isAllowed("npm run build"))
        assertTrue(CommandAllowlist.isAllowed("./gradlew :shared:compileDebugKotlinAndroid"))
    }

    @Test
    fun `allowlist rejects everything else`() {
        listOf("rm -rf /", "git push --force", "sudo rm /etc/hosts", "./gradlew publish", "./gradlew :a:publish", "", "   ")
            .forEach { assertFalse(it, CommandAllowlist.isAllowed(it)) }
    }

    @Test
    fun `splitCommand honours quotes`() {
        assertEquals(listOf("echo", "a b", "c"), CommandAllowlist.splitCommand("echo 'a b'  c"))
        assertEquals(listOf("echo", "x y"), CommandAllowlist.splitCommand("echo \"x y\""))
    }

    @Test
    fun `process shell runner captures output and remembers it`() {
        assumeTrue(!isWindows)
        val shell = ProcessShellRunner()
        val r = shell.run("echo hello", tmp.root.absolutePath, 10_000)
        assertTrue(r.succeeded)
        assertEquals(0, r.exitCode)
        assertEquals("hello", r.stdout.trim())
        assertEquals(r, shell.lastOutput("echo hello"))
        assertNotNull(shell.lastOutputAny())
        assertNull(shell.lastOutput("never ran"))
    }

    @Test
    fun `process shell runner reports failures without throwing`() {
        assumeTrue(!isWindows)
        val shell = ProcessShellRunner()
        val missing = shell.run("definitely-not-a-command-xyz", tmp.root.absolutePath, 10_000)
        assertFalse(missing.succeeded)
        assertEquals(-1, missing.exitCode)
        val failing = shell.run("ls /definitely/not/here", tmp.root.absolutePath, 10_000)
        assertFalse(failing.succeeded)
        assertTrue(failing.stderr.isNotBlank())
    }

    @Test
    fun `process shell runner kills commands that exceed the timeout`() {
        assumeTrue(!isWindows)
        val r = ProcessShellRunner().run("sleep 5", tmp.root.absolutePath, 300)
        assertFalse(r.succeeded)
        assertEquals(-1, r.exitCode)
    }

    @Test
    fun `lastOutputAny is the most recent command, not the slowest`() {
        assumeTrue(!isWindows)
        val shell = ProcessShellRunner()
        shell.run("sleep 0.3", tmp.root.absolutePath, 10_000)
        shell.run("echo fast", tmp.root.absolutePath, 10_000)
        assertEquals("echo fast", shell.lastOutputAny()?.command)
    }
}
