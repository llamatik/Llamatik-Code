package com.llamatik.teammate.agent

import org.junit.Assert.*
import org.junit.Test

class ProposalValidatorTest {

    private fun valid(path: String, content: String) =
        assertEquals(ProposalValidator.Result.Valid, ProposalValidator.validate(path, content))

    private fun invalid(path: String, content: String) =
        assertNotEquals(ProposalValidator.Result.Valid, ProposalValidator.validate(path, content))

    private fun realContent() = "package com.example\n\nclass Foo {\n    fun bar() {}\n}"

    // ── Path validation ──────────────────────────────────────────────────────

    @Test
    fun `blank path is rejected`() = invalid("", realContent())

    @Test
    fun `whitespace-only path is rejected`() = invalid("   ", realContent())

    @Test
    fun `path without extension is rejected`() = invalid("MyClass", realContent())

    @Test
    fun `path with extension is accepted`() = valid("src/MyClassTest.kt", realContent())

    @Test
    fun `path with newline is rejected — model wrote code into path arg`() {
        val pathWithNewline = "src/Foo.kt\nfun test() { }"
        invalid(pathWithNewline, realContent())
    }

    // ── Content validation ───────────────────────────────────────────────────

    @Test
    fun `blank content is rejected`() = invalid("Foo.kt", "")

    @Test
    fun `whitespace-only content is rejected`() = invalid("Foo.kt", "   \n\n  ")

    @Test
    fun `single-line stub is accepted — user should review it`() =
        valid("FooTest.kt", "// TODO: Add tests here")

    @Test
    fun `two non-blank lines is accepted — user decides quality`() =
        valid("FooTest.kt", "package com.example\nclass FooTest")

    // ── Tool invocation detection ────────────────────────────────────────────

    @Test
    fun `content with search_code invocation is rejected — model confused tool syntax with code`() {
        val content = "class T {\n    autorun(search_code(\"test\"))\n\n\n}"
        invalid("T.kt", content)
    }

    @Test
    fun `content with list_files invocation is rejected`() {
        val content = "class T {\n    list_files(\"src\")\n\n\n}"
        invalid("T.kt", content)
    }

    @Test
    fun `content with write_file invocation is rejected`() {
        val content = "class T {\n    write_file(\"path\", \"content\")\n\n\n}"
        invalid("T.kt", content)
    }

    @Test
    fun `function name containing tool keyword is NOT rejected — only invocations matter`() {
        val content = "fun readFileHelper(path: String): String {\n    return File(path).readText()\n}\nfun test() {}"
        valid("FileHelper.kt", content)
    }

    // ── Valid real-world content ─────────────────────────────────────────────

    @Test
    fun `realistic test file is accepted`() {
        val content = """
            package com.example

            import org.junit.Test
            import org.junit.Assert.*

            class DCSAuthRepositoryTest {
                @Test
                fun `login returns token on success`() {
                    val repo = DCSAuthRepository()
                    assertNotNull(repo.login("user", "pass"))
                }
            }
        """.trimIndent()
        valid("shared/src/test/kotlin/DCSAuthRepositoryTest.kt", content)
    }
}
