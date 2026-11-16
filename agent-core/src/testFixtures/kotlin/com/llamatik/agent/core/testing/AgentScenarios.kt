package com.llamatik.agent.core.testing

import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole

/** Scripted runs that together walk every branch of the agent loop and of chat. */
object AgentScenarios {
    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    private fun write(path: String, content: String) = call("write_file", "path" to path, "content" to content)

    private fun prepared(path: String, old: String, new: String) =
        "Prepared file replacement for $path\n---OLD---\n$old\n---NEW---\n$new"

    private val calc = "fun add(a: Int, b: Int): Int {\n    return a - b\n}\n"
    private val calcFixed = "fun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private val files = mapOf(
        "src/Calc.kt" to calc,
        "src/Util.kt" to "object Util { fun id(x: Int) = x }\n",
        "build.gradle.kts" to "plugins { kotlin(\"jvm\") }\n",
        "README.md" to "# Demo\n",
    )
    private val user = { s: String -> ChatMessage(role = ChatRole.USER, content = s) }
    private val assistant = { s: String -> ChatMessage(role = ChatRole.ASSISTANT, content = s) }

    val all: List<AgentScenario> = listOf(
        AgentScenario("plain-answer-round1", "What does this project do?",
            listOf("<think>look at it</think>This project is a small demo library with arithmetic helpers.")),
        AgentScenario("think-only-fallback", "hello", listOf("<think>Just thinking about it quietly.</think>")),
        AgentScenario("list-then-answer", "What files are in src?",
            listOf(call("list_files", "path" to "src"), "The src folder contains Calc.kt and Util.kt with small helpers."),
            files = files, toolResults = mapOf("list_files" to listOf("src/Calc.kt\nsrc/Util.kt"))),
        AgentScenario("read-then-write", "fix the add function in src/Calc.kt",
            listOf(call("read_file", "path" to "src/Calc.kt"), write("src/Calc.kt", calcFixed)),
            files = files,
            toolResults = mapOf(
                "read_file" to listOf(calc),
                "write_file" to listOf(prepared("src/Calc.kt", calc, calcFixed)),
            )),
        AgentScenario("create-file-stops", "create src/New.kt with a hello function",
            listOf(call("create_file", "path" to "src/New.kt", "content" to "fun hello() = \"hi\"\n"), "never reached"),
            files = files,
            toolResults = mapOf("create_file" to listOf("Prepared new file for src/New.kt\nfun hello() = \"hi\"\n"))),
        AgentScenario("replace-in-file", "change a - b to a + b in src/Calc.kt",
            listOf(call("replace_in_file", "path" to "src/Calc.kt", "oldText" to "a - b", "newText" to "a + b")),
            files = files,
            toolResults = mapOf("replace_in_file" to listOf(prepared("src/Calc.kt", calc, calcFixed)))),
        AgentScenario("hallucinated-tool-filtered", "do something",
            listOf(call("diff_tool", "path" to "x") + " I could not find a tool for that, sorry about that.")),
        AgentScenario("tool-call-cap", "inspect everything",
            listOf(
                call("list_files", "path" to "a") + call("list_files", "path" to "b") + call("list_files", "path" to "c") +
                    call("list_files", "path" to "d") + call("list_files", "path" to "e") + call("list_files", "path" to "f"),
                "Everything has been inspected and nothing unusual was found in those folders.",
            ), files = files),
        AgentScenario("max-rounds-exhausted", "keep exploring forever",
            (1..8).map { call("search_code", "query" to "term$it") }, files = files),
        AgentScenario("duplicate-read-escalates-to-write", "fix src/Calc.kt",
            listOf(
                call("read_file", "path" to "src/Calc.kt"),
                call("read_file", "path" to "src/Calc.kt"),
                write("src/Calc.kt", calcFixed),
            ), files = files,
            toolResults = mapOf("read_file" to listOf(calc), "write_file" to listOf(prepared("src/Calc.kt", calc, calcFixed)))),
        AgentScenario("attached-file-prose-escalates", "fix the bug in @{src/Calc.kt}",
            listOf("I think the problem is the minus sign in the add function, which should be a plus.", write("src/Calc.kt", calcFixed)),
            files = files, toolResults = mapOf("write_file" to listOf(prepared("src/Calc.kt", calc, calcFixed)))),
        AgentScenario("attached-file-then-reads-escalates", "refactor @{src/Calc.kt}",
            listOf(call("read_file", "path" to "src/Util.kt"), call("list_files"), write("src/Calc.kt", calcFixed)),
            files = files,
            toolResults = mapOf("read_file" to listOf("object Util { fun id(x: Int) = x }\n"),
                "write_file" to listOf(prepared("src/Calc.kt", calc, calcFixed)))),
        AgentScenario("attached-missing-file", "explain @{src/Missing.kt}", listOf("That file could not be found so I cannot explain it."), files = files),
        AgentScenario("current-file-reference", "write tests for this file",
            listOf("Here is a short explanation of how the tests for that file could be structured."),
            files = files, currentFile = "src/Calc.kt"),
        AgentScenario("build-error-uri", "build failed: e: file:///work/demo/src/Calc.kt:2:12 Type mismatch",
            listOf("The build error comes from the add function returning the wrong expression here."),
            files = files),
        AgentScenario("history-file-inheritance", "now add a comment to it",
            listOf("Added the requested explanation about the file in plain words for you to read."),
            files = files,
            history = listOf(
                user("fix @{src/Calc.kt}\n\n[attached_file_context]\n### File: src/Calc.kt\n```\n$calc```"),
                assistant("Applied changes to: src/Calc.kt"),
            )),
        AgentScenario("tool-error-reflection", "update src/Calc.kt",
            listOf(call("write_file", "path" to "src/Calc.kt"), write("src/Calc.kt", calcFixed)),
            files = files,
            toolResults = mapOf("write_file" to listOf("Missing content", prepared("src/Calc.kt", calc, calcFixed)))),
        AgentScenario("empty-output-retry", "say hi", listOf("", "", "Hello there, how can I help you with this project today?")),
        AgentScenario("proposal-rejected-hallucinated-content", "rewrite src/Calc.kt",
            listOf(write("src/Calc.kt", "<tool_call name=\"read_file\">oops</tool_call>"), "I was unable to produce a valid change for that file.", "still nothing useful to report here, sorry."),
            files = files,
            toolResults = mapOf("write_file" to listOf(prepared("src/Calc.kt", calc, "<tool_call name=\"read_file\">oops</tool_call>")))),
        AgentScenario("xml-edit-proposal", "fix src/Calc.kt",
            listOf("<edit_proposal path=\"src/Calc.kt\" summary=\"fix add\"><new_content><![CDATA[$calcFixed]]></new_content></edit_proposal>"),
            files = files),
        AgentScenario("xml-edit-proposal-truncated", "fix src/Calc.kt",
            listOf("<edit_proposal path=\"src/Calc.kt\" summary=\"fix add\"><new_content><![CDATA[fun add(a: Int, b: Int) = a + b"),
            files = files),
        AgentScenario("dynamic-mcp-tool", "ask the server",
            listOf(call("mcp_custom_tool", "q" to "ping"), "The server answered that everything is fine and healthy today."),
            dynamicTools = setOf("mcp_custom_tool"), toolResults = mapOf("mcp_custom_tool" to listOf("pong"))),
        AgentScenario("enterprise-mission", "Run an offline enterprise pair programmer mission to review src",
            listOf("Plan: first inspect the files, assess risks, and then propose small reviewed patches carefully.")),
        AgentScenario("system-prompt-extras", "explain the build",
            listOf("The build uses the Kotlin JVM Gradle plugin as declared in the build script."),
            files = files, currentFile = "build.gradle.kts", selectedText = "plugins { kotlin(\"jvm\") }",
            pinned = "Always answer in English.", skills = "\nSkill: be brief.\n", rules = "RULES: use 4 spaces.", memory = "MEMORY: project uses Gradle.",
            aggressive = true),
        AgentScenario("long-history-budget", "continue",
            listOf("Sure, continuing from where we left off with the earlier discussion about it."),
            history = (1..30).flatMap { listOf(user("question number $it ".repeat(20)), assistant("answer number $it ".repeat(20))) }),
        AgentScenario("small-context-budgets", "read big file",
            listOf(call("read_file", "path" to "src/Big.kt"), "The big file is large and mostly repeats the same line over and over again."),
            files = files + ("src/Big.kt" to "val x = 1\n".repeat(3000)),
            toolResults = mapOf("read_file" to listOf("val x = 1\n".repeat(3000))), contextLength = 4096),
        AgentScenario("tiny-token-chunks-split-think", "go",
            listOf("<think>reasoning about <wait it</think>" + call("list_files", "path" to "src") + "<think>x</think>", "All done with the listing of the source folder, nothing else to add."),
            files = files, tokenChunk = 1),
        AgentScenario("think-closed-by-im-end", "go", listOf("<think>endless reasoning<|im_end|>The final answer is forty two for the given question."), tokenChunk = 2),

        AgentScenario("backend-error-round1", "anything", listOf("unused"), errorAtCall = 1),
        AgentScenario("backend-error-round2", "list the src files",
            listOf(call("list_files", "path" to "src"), "unused"), files = files, errorAtCall = 2,
            toolResults = mapOf("list_files" to listOf("src/Calc.kt"))),
        AgentScenario("chat-backend-error", "hi", listOf("unused"), chat = true, errorAtCall = 1, history = listOf(user("hi"))),

        // ── Regression scenarios for the Phase 4 fixes ─────────────────────────────
        AgentScenario("question-about-attached-file-keeps-its-answer", "explain @{src/Calc.kt}",
            listOf("The add function subtracts its arguments instead of adding them, which is a bug."), files = files),
        AgentScenario("read-then-explain-keeps-its-answer", "how does Calc.kt work?",
            listOf(call("read_file", "path" to "src/Calc.kt"), "It defines one function, add, that is meant to sum two integers."),
            files = files, toolResults = mapOf("read_file" to listOf(calc))),
        AgentScenario("repeated-identical-read-is-bounded", "fix the add function in src/Calc.kt",
            List(12) { call("read_file", "path" to "src/Calc.kt") }, files = files,
            toolResults = mapOf("read_file" to listOf(calc))),
        AgentScenario("repeated-identical-read-question-is-bounded", "what does src/Calc.kt do?",
            List(12) { call("read_file", "path" to "src/Calc.kt") }, files = files,
            toolResults = mapOf("read_file" to listOf(calc))),
        AgentScenario("forced-write-pass-blank-reply-writes-nothing", "fix the bug in @{src/Calc.kt}",
            listOf("I believe the minus sign is wrong in the add function, it should be a plus.", "", "", ""),
            files = files),
        AgentScenario("forced-write-pass-reply-with-own-tool-call", "fix the bug in @{src/Calc.kt}",
            listOf("I believe the minus sign is wrong in the add function, it should be a plus.",
                call("read_file", "path" to "src/Util.kt"), write("src/Calc.kt", calcFixed)),
            files = files,
            toolResults = mapOf("read_file" to listOf("object Util"), "write_file" to listOf(prepared("src/Calc.kt", calc, calcFixed)))),

        AgentScenario("command-then-answer-keeps-its-answer", "run the tests",
            listOf(call("terminal_run", "command" to "./gradlew test"), "The test run succeeded and everything passed as expected."),
            toolResults = mapOf("terminal_run" to listOf("Command: ./gradlew test\nExit code: 0  Duration: 3000ms\nStdout:\nBUILD SUCCESSFUL"))),

        // ── Ask / chat ──────────────────────────────────────────────────────────
        AgentScenario("chat-simple", "What is Kotlin?", listOf("Kotlin is a modern language for the JVM."), chat = true,
            history = listOf(user("What is Kotlin?"))),
        AgentScenario("chat-think-and-open-file", "explain this", listOf("<think>hidden</think>It adds two numbers (incorrectly).<end_of_turn>ignored"), chat = true,
            files = files, currentFile = "src/Calc.kt", selectedText = "return a - b", pinned = "Be terse.",
            history = listOf(user("explain this")), tokenChunk = 3),
        AgentScenario("chat-attached-file-echo", "summarise @{src/Util.kt}", listOf("summarise Util.kt\nIt defines an identity function."), chat = true,
            files = files, history = listOf(user("summarise @{src/Util.kt}"))),
        AgentScenario("chat-split-think-tag", "hi", listOf("Hello <think>secret</think>world"), chat = true, tokenChunk = 1,
            history = listOf(user("hi"))),
    )
}
