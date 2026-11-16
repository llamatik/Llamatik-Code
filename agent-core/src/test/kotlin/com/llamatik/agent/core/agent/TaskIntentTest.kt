package com.llamatik.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskIntentTest {
    private fun check(expected: Boolean, vararg prompts: String) =
        prompts.forEach { assertEquals(it, expected, TaskIntent.wantsEdit(it)) }

    @Test
    fun `imperative requests that change files want an edit`() = check(
        true,
        "fix the bug in Calc.kt", "Add a skip button to Screen.kt", "refactor this class", "Create tests for Foo",
        "write tests for the current file", "please update the README", "Can you fix this?", "rename foo to bar",
        "Implement Circle.area()", "make it thread safe",
    )

    @Test
    fun `questions and explanation requests do not`() = check(
        false,
        "What files are in src?", "explain @{src/Calc.kt}", "How does Calc.kt work?", "how do I fix this bug?",
        "Why is the build failing?", "review my code", "summarize the project", "Is this thread safe?",
        "list the files", "Show me git status", "find usages of add", "Do something", "hello", "",
    )
}
