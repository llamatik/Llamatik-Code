package com.llamatik.agent.core.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NioWorkspaceFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var files: NioWorkspaceFiles

    private fun put(path: String, content: String = "x") {
        File(root, path).apply { parentFile.mkdirs(); writeText(content) }
    }

    @Before
    fun setUp() {
        root = tmp.newFolder("project")
        put("README.md", "hello readme")
        put("src/main/kotlin/App.kt", "fun main() { println(\"needle\") }")
        put("src/main/kotlin/Util.kt", "object Util")
        put("src/test/kotlin/AppTest.kt", "class AppTest")
        put("build/classes/Gen.class", "binary needle")
        put(".git/config", "[core]")
        put("local.properties", "sdk.dir=/x")
        put("config/local.properties", "sdk.dir=/y")
        put("config/.env", "SECRET=1")
        put("docs/notes.txt", "needle in docs")
        files = NioWorkspaceFiles(root.toPath())
    }

    @Test
    fun `root listing is a directory tree with file counts, dirs first, ignoring build and git`() {
        val listing = files.list(null)
        assertEquals(listOf("docs/  (1 files)", "src/  (3 files)"), listing.filter { it.endsWith(")") })
        assertTrue(listing.indexOf("docs/  (1 files)") < listing.indexOf("README.md"))
        assertTrue(listing.contains("README.md"))
        assertTrue(listing.none { it.startsWith("build") || it.startsWith(".git") })
    }

    @Test
    fun `directories holding only denied files are omitted from the tree`() {
        // config/ holds only a denied .env and a denied local.properties.
        assertTrue(files.list(null).none { it.startsWith("config") })
    }

    @Test
    fun `deeper path lists flat sorted files`() {
        assertEquals(
            listOf("src/main/kotlin/App.kt", "src/main/kotlin/Util.kt"),
            files.list("src/main/kotlin"),
        )
    }

    @Test
    fun `a file path lists just that file`() {
        assertEquals(listOf("src/main/kotlin/App.kt"), files.list("src/main/kotlin/App.kt"))
    }

    @Test
    fun `unknown path falls back to the root summary`() {
        assertEquals(files.list(null), files.list("does-not-exist"))
    }

    @Test
    fun `read returns utf8 content and rejects denied, missing and directory paths`() {
        assertEquals("hello readme", files.read("README.md"))
        listOf("config/.env", "config/local.properties").forEach { denied ->
            try { files.read(denied); fail("expected denial for $denied") } catch (e: IllegalArgumentException) {
                assertEquals("Access denied for file: $denied", e.message)
            }
        }
        try { files.read("nope.txt"); fail() } catch (e: IllegalStateException) {
            assertEquals("File not found: nope.txt", e.message)
        }
        try { files.read("src"); fail() } catch (e: IllegalArgumentException) {
            assertEquals("Access denied for file: src", e.message)
        }
    }

    @Test
    fun `explicit read may open files inside ignored directories`() {
        assertEquals("binary needle", files.read("build/classes/Gen.class"))
    }

    @Test
    fun `read rejects files over the size limit`() {
        val small = NioWorkspaceFiles(root.toPath(), maxFileBytes = 5)
        try { small.read("README.md"); fail() } catch (e: IllegalArgumentException) {
            assertEquals("File too large for context: README.md", e.message)
        }
    }

    @Test
    fun `searchCode skips ignored and denied files and is case insensitive`() {
        assertEquals(listOf("docs/notes.txt", "src/main/kotlin/App.kt"), files.searchCode("NEEDLE"))
        assertTrue(files.searchCode("").isEmpty())
    }

    @Test
    fun `searchFilenames ranks prefix matches before contains matches`() {
        put("src/main/kotlin/MyApp.kt")
        assertEquals(
            listOf("src/main/kotlin/App.kt", "src/test/kotlin/AppTest.kt", "src/main/kotlin/MyApp.kt"),
            files.searchFilenames("App"),
        )
    }

    @Test
    fun `user ignored directories are honoured`() {
        val custom = NioWorkspaceFiles(root.toPath(), userIgnoredDirs = { setOf("docs") })
        assertTrue(custom.list(null).none { it.startsWith("docs") })
        assertTrue(custom.searchCode("needle") == listOf("src/main/kotlin/App.kt"))
    }

    @Test
    fun `isIgnored looks at path components below the root only`() {
        assertTrue(files.isIgnored("build/classes/Gen.class"))
        assertTrue(files.isIgnored(".git/config"))
        assertFalse(files.isIgnored("src/main/kotlin/App.kt"))
        // The temp root itself may sit in a directory with an ignored name; that must not matter.
        val nested = NioWorkspaceFiles(File(root, "build").also { it.mkdirs() }.toPath())
        assertFalse(nested.isIgnored("anything.kt"))
    }

    @Test
    fun `write creates parent directories`() {
        files.write("new/dir/Created.kt", "class Created")
        assertEquals("class Created", File(root, "new/dir/Created.kt").readText())
        assertEquals("class Created", files.read("new/dir/Created.kt"))
    }

    @Test
    fun `secret-looking files are denied at the root as well as in subdirectories`() {
        put(".env", "TOP=1")
        put("secrets.json", "{}")
        put("release.keystore", "k")
        listOf(".env", "local.properties", "secrets.json", "release.keystore", "config/.env").forEach { denied ->
            try { files.read(denied); fail("expected denial for $denied") } catch (e: IllegalArgumentException) {
                assertEquals("Access denied for file: $denied", e.message)
            }
        }
        val listing = files.list(null)
        assertTrue(listing.none { it in setOf(".env", "local.properties", "secrets.json", "release.keystore") })
        assertEquals(listOf("README.md"), listing.filter { !it.endsWith(")") })
    }

    @Test
    fun `similarly named files that are not secrets stay readable`() {
        put("environment.md", "ok")
        assertEquals("ok", files.read("environment.md"))
    }
}
