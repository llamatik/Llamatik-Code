package com.llamatik.teammate.external

import com.llamatik.teammate.external.research.ErrorResearchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorResearchServiceTest {

    private fun makeService(): ErrorResearchService = ErrorResearchService()

    // ── Gradle errors ──────────────────────────────────────────────────────

    @Test
    fun `detects Gradle as tool from FAILURE build failed message`() {
        val svc = makeService()
        val error = "FAILURE: Build failed with an exception.\n* What went wrong:\nTask :app:compileDebugKotlin FAILED"
        val ctx = svc.parseErrorContext(error)
        assertEquals("Gradle", ctx.tool)
    }

    @Test
    fun `detects Gradle from gradlew reference`() {
        val svc = makeService()
        val error = "Run './gradlew build' to reproduce"
        val ctx = svc.parseErrorContext(error)
        assertEquals("Gradle", ctx.tool)
    }

    @Test
    fun `extracts task name as error code for Gradle`() {
        val svc = makeService()
        val error = "Task :app:buildRelease FAILED\nExecution failed..."
        val ctx = svc.parseErrorContext(error)
        assertTrue("Error code should contain task reference", ctx.errorCode.contains("Task"))
    }

    // ── Kotlin compiler errors ─────────────────────────────────────────────

    @Test
    fun `detects Kotlin compiler from kt file error message`() {
        val svc = makeService()
        val error = "src/main/kotlin/com/example/Foo.kt:42:5: error: Unresolved reference: Bar"
        val ctx = svc.parseErrorContext(error)
        assertEquals("Kotlin compiler", ctx.tool)
    }

    @Test
    fun `extracts unresolved reference as library name`() {
        val svc = makeService()
        val error = "error: Unresolved reference: MyMissingClass"
        val ctx = svc.parseErrorContext(error)
        assertEquals("MyMissingClass", ctx.libraryName)
    }

    @Test
    fun `extracts Could not resolve dependency`() {
        val svc = makeService()
        val error = "Could not resolve: com.example:awesome-lib:1.2.3"
        val ctx = svc.parseErrorContext(error)
        assertEquals("com.example:awesome-lib", ctx.libraryName)
    }

    // ── Android errors ─────────────────────────────────────────────────────

    @Test
    fun `extracts duplicate class as library name`() {
        val svc = makeService()
        val error = "Duplicate class com.example.foo.Bar found in modules jetified-lib-1.jar"
        val ctx = svc.parseErrorContext(error)
        assertEquals("com.example.foo.Bar", ctx.libraryName)
    }

    @Test
    fun `detects Android framework from Manifest merger message`() {
        val svc = makeService()
        val error = "Manifest merger failed: Attribute application@android:allowBackup value=(false)"
        val ctx = svc.parseErrorContext(error)
        assertEquals("Android", ctx.framework)
    }

    @Test
    fun `detects Android framework from android keyword`() {
        val svc = makeService()
        val error = "Task :app:mergeDebugResources FAILED\nandroid resource compilation failed"
        val ctx = svc.parseErrorContext(error)
        assertEquals("Android", ctx.framework)
    }

    // ── KMP errors ─────────────────────────────────────────────────────────

    @Test
    fun `detects KMP framework from multiplatform keyword`() {
        val svc = makeService()
        val error = "Could not resolve: org.jetbrains.kotlin:kotlin-multiplatform:1.9.0"
        val ctx = svc.parseErrorContext(error)
        assertEquals("KMP", ctx.framework)
    }

    // ── CMake errors ───────────────────────────────────────────────────────

    @Test
    fun `detects CMake as tool from CMake Error message`() {
        val svc = makeService()
        val error = "CMake Error at CMakeLists.txt:10 (find_package):\n  Could not find a configuration file"
        val ctx = svc.parseErrorContext(error)
        assertEquals("CMake", ctx.tool)
    }

    // ── Version extraction ─────────────────────────────────────────────────

    @Test
    fun `extracts version number from dependency error`() {
        val svc = makeService()
        val error = "Could not resolve: com.example:lib:2.3.1"
        val ctx = svc.parseErrorContext(error)
        assertFalse("Should extract version", ctx.version.isBlank())
    }

    // ── File extraction ────────────────────────────────────────────────────

    @Test
    fun `extracts kt file paths from error`() {
        val svc = makeService()
        val error = "src/main/kotlin/com/example/MainActivity.kt:10: error: Unresolved reference"
        val ctx = svc.parseErrorContext(error)
        assertTrue("Should extract .kt files", ctx.relevantFiles.any { it.contains(".kt") })
    }

    @Test
    fun `extracts gradle file paths from error`() {
        val svc = makeService()
        val error = "Could not evaluate build file 'app/build.gradle'. Line 42..."
        val ctx = svc.parseErrorContext(error)
        assertTrue("Should extract .gradle files", ctx.relevantFiles.any { it.contains(".gradle") })
    }

    // ── Robustness ────────────────────────────────────────────────────────

    @Test
    fun `parseErrorContext does not crash on empty string`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("")
        assertEquals("", ctx.tool)
        assertEquals("", ctx.framework)
        assertEquals("", ctx.libraryName)
    }

    @Test
    fun `parseErrorContext does not crash on special characters`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("!@#\$%^&*()_+{}|")
        assertTrue("rawError should be preserved", ctx.rawError.isNotBlank())
    }

    @Test
    fun `rawError is preserved in context`() {
        val svc = makeService()
        val error = "Some build error message"
        val ctx = svc.parseErrorContext(error)
        assertEquals(error, ctx.rawError)
    }
}
