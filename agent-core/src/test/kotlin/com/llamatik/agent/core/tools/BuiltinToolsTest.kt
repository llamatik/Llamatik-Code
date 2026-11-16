package com.llamatik.agent.core.tools

import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.AutoDeny
import com.llamatik.agent.core.tools.builtin.ApplyPatchTool
import com.llamatik.agent.core.tools.builtin.CreateFileTool
import com.llamatik.agent.core.tools.builtin.GitBranchTool
import com.llamatik.agent.core.tools.builtin.GitChangedFilesTool
import com.llamatik.agent.core.tools.builtin.GitCommitMessageSuggestionTool
import com.llamatik.agent.core.tools.builtin.GitDiffTool
import com.llamatik.agent.core.tools.builtin.GitStatusTool
import com.llamatik.agent.core.tools.builtin.ListFilesTool
import com.llamatik.agent.core.tools.builtin.ReadFileTool
import com.llamatik.agent.core.tools.builtin.ReplaceInFileTool
import com.llamatik.agent.core.tools.builtin.SearchCodeTool
import com.llamatik.agent.core.tools.builtin.SearchFilenamesTool
import com.llamatik.agent.core.tools.builtin.ShowDiffTool
import com.llamatik.agent.core.tools.builtin.TerminalAllowedCommandsTool
import com.llamatik.agent.core.tools.builtin.TerminalLastOutputTool
import com.llamatik.agent.core.tools.builtin.TerminalRunTool
import com.llamatik.agent.core.tools.builtin.WriteFileTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins tool names, argument names, descriptions and result text. Local models were prompted and
 * benchmarked against these exact strings, so they must not drift.
 */
class BuiltinToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var ctx: ToolExecutionContext

    private fun call(name: String, vararg args: Pair<String, String>) = ToolCall(name, mapOf(*args))

    @Before
    fun setUp() {
        root = tmp.newFolder("project")
        File(root, "src").mkdirs()
        File(root, "src/Calc.kt").writeText("fun add(a: Int, b: Int) = a - b\n")
        File(root, "README.md").writeText("readme")
        ctx = ToolExecutionContext(AgentEnvironment.headless(root.toPath(), AutoDeny))
    }

    @Test
    fun `tool names and descriptions are stable`() {
        val expected = listOf(
            ListFilesTool() to ("list_files" to "List project files under a relative path. Arguments: path"),
            ReadFileTool() to ("read_file" to "Read a UTF-8 project file. Arguments: path"),
            SearchCodeTool() to ("search_code" to "Search project contents for text. Arguments: query"),
            SearchFilenamesTool() to ("search_filenames" to "Search project filenames. Arguments: query"),
            WriteFileTool() to ("write_file" to "Prepare a full file replacement. Arguments: path, content"),
            CreateFileTool() to ("create_file" to "Prepare a new file creation. Arguments: path, content"),
            ReplaceInFileTool() to ("replace_in_file" to "Prepare a string replacement in an existing file. Arguments: path, oldText, newText"),
            ShowDiffTool() to ("show_diff" to "Prepare a diff payload. Arguments: path, oldContent, newContent"),
            ApplyPatchTool() to ("apply_patch" to "Reserved safe apply tool. Returns a pending approval notice in MVP. Arguments: path, content"),
            GitStatusTool() to ("git_status" to "Get git status: branch, staged/unstaged/untracked files. No arguments needed."),
            GitDiffTool() to ("git_diff" to "Show git diff. Arguments: staged (true/false, default false)"),
            GitChangedFilesTool() to ("git_changed_files" to "List all files changed since last commit. No arguments needed."),
            GitBranchTool() to ("git_branch" to "Get current git branch name. No arguments needed."),
            GitCommitMessageSuggestionTool() to ("git_commit_message_suggestion" to "Get diff context to help write a commit message. No arguments needed."),
            TerminalAllowedCommandsTool() to ("terminal_allowed_commands" to "List commands the agent can run without user approval. No arguments."),
            TerminalRunTool() to ("terminal_run" to "Run an allowlisted project command. Arguments: command"),
            TerminalLastOutputTool() to ("terminal_last_output" to "Get output from the last terminal command. Arguments: command (optional)"),
        )
        expected.forEach { (tool, nameAndDesc) ->
            assertEquals(nameAndDesc.first, tool.name)
            assertEquals("description of ${tool.name}", nameAndDesc.second, tool.description)
        }
    }

    @Test
    fun `read_file`() {
        assertEquals(ToolResult("read_file", true, "readme"), ReadFileTool().execute(call("read_file", "path" to "README.md"), ctx))
        assertEquals(ToolResult("read_file", false, "Missing path"), ReadFileTool().execute(call("read_file"), ctx))
    }

    @Test
    fun `list_files, search_code and search_filenames join results with newlines`() {
        assertEquals(ToolResult("list_files", true, "src/  (1 files)\nREADME.md"), ListFilesTool().execute(call("list_files"), ctx))
        assertEquals(ToolResult("search_code", true, "src/Calc.kt"), SearchCodeTool().execute(call("search_code", "query" to "add"), ctx))
        assertEquals(ToolResult("search_filenames", true, "src/Calc.kt"), SearchFilenamesTool().execute(call("search_filenames", "query" to "calc"), ctx))
        assertEquals(ToolResult("search_code", false, "Missing query"), SearchCodeTool().execute(call("search_code"), ctx))
        assertEquals(ToolResult("search_filenames", false, "Missing query"), SearchFilenamesTool().execute(call("search_filenames"), ctx))
    }

    @Test
    fun `write_file reports old and new content, treating a missing file as empty`() {
        assertEquals(
            ToolResult("write_file", true, "Prepared file replacement for src/Calc.kt\n---OLD---\nfun add(a: Int, b: Int) = a - b\n\n---NEW---\nNEW"),
            WriteFileTool().execute(call("write_file", "path" to "src/Calc.kt", "content" to "NEW"), ctx),
        )
        assertEquals(
            ToolResult("write_file", true, "Prepared file replacement for fresh.kt\n---OLD---\n\n---NEW---\nX"),
            WriteFileTool().execute(call("write_file", "path" to "fresh.kt", "content" to "X"), ctx),
        )
        assertEquals(ToolResult("write_file", false, "Missing path"), WriteFileTool().execute(call("write_file"), ctx))
        assertEquals(ToolResult("write_file", false, "Missing content"), WriteFileTool().execute(call("write_file", "path" to "a"), ctx))
        // Preparing a change never touches the disk.
        assertEquals("fun add(a: Int, b: Int) = a - b\n", File(root, "src/Calc.kt").readText())
    }

    @Test
    fun `create_file, show_diff and apply_patch`() {
        assertEquals(
            ToolResult("create_file", true, "Prepared new file for A.kt\nclass A"),
            CreateFileTool().execute(call("create_file", "path" to "A.kt", "content" to "class A"), ctx),
        )
        assertEquals(ToolResult("show_diff", true, "Diff ready for A.kt"), ShowDiffTool().execute(call("show_diff", "path" to "A.kt"), ctx))
        assertEquals(
            ToolResult("apply_patch", true, "Patch for A.kt requires explicit approval in the UI before applying."),
            ApplyPatchTool().execute(call("apply_patch", "path" to "A.kt"), ctx),
        )
    }

    @Test
    fun `replace_in_file exact, indentation-fuzzy and failure messages`() {
        File(root, "src/Block.kt").writeText("class A {\n    fun x() {\n        old()\n    }\n}\n")
        val exact = ReplaceInFileTool().execute(
            call("replace_in_file", "path" to "src/Block.kt", "oldText" to "old()", "newText" to "fresh()"), ctx,
        )
        assertTrue(exact.success)
        assertTrue(exact.content.startsWith("Prepared replacement for src/Block.kt\n---OLD---\n"))
        assertTrue(exact.content.endsWith("---NEW---\nclass A {\n    fun x() {\n        fresh()\n    }\n}\n"))

        // Wrong indentation from the model: still found, re-indented.
        val fuzzy = ReplaceInFileTool().execute(
            call("replace_in_file", "path" to "src/Block.kt", "oldText" to "fun x() {\n    old()\n}", "newText" to "fun x() {\n    fresh()\n}"), ctx,
        )
        assertTrue(fuzzy.content, fuzzy.success)

        val missing = ReplaceInFileTool().execute(
            call("replace_in_file", "path" to "src/Block.kt", "oldText" to "nothing like this", "newText" to "z"), ctx,
        )
        assertEquals(
            ToolResult(
                "replace_in_file", false,
                "replace_in_file failed: the text to replace was not found in src/Block.kt.\n" +
                    "The model's oldText may have incorrect indentation or whitespace.\n" +
                    "Read the file with read_file first, then copy the exact text to replace.",
            ),
            missing,
        )
        assertEquals(ToolResult("replace_in_file", false, "Missing oldText"), ReplaceInFileTool().execute(call("replace_in_file", "path" to "a"), ctx))
    }

    @Test
    fun `terminal tools`() {
        val allowed = TerminalAllowedCommandsTool().execute(call("terminal_allowed_commands"), ctx)
        assertTrue(allowed.content.startsWith("Allowed commands:\n  ./gradlew assemble\n"))

        val blocked = TerminalRunTool().execute(call("terminal_run", "command" to "rm -rf /"), ctx)
        assertEquals(
            ToolResult("terminal_run", false, "Command not in allowlist: 'rm -rf /'. Use terminal_allowed_commands to see what's permitted."),
            blocked,
        )
        assertEquals(ToolResult("terminal_run", false, "Missing command"), TerminalRunTool().execute(call("terminal_run"), ctx))
        assertEquals(
            ToolResult("terminal_last_output", false, "No recorded output. Run a command first with terminal_run."),
            TerminalLastOutputTool().execute(call("terminal_last_output"), ctx),
        )
    }

    @Test
    fun `git tools outside a repository`() {
        listOf(GitStatusTool(), GitDiffTool(), GitChangedFilesTool(), GitBranchTool(), GitCommitMessageSuggestionTool()).forEach {
            assertEquals(ToolResult(it.name, false, "Not a git repository"), it.execute(call(it.name), ctx))
        }
    }

    @Test
    fun `git tools in a real repository`() {
        assumeTrue("git not installed", runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        fun git(vararg a: String) = ProcessBuilder("git", "-c", "user.email=t@t", "-c", "user.name=t", *a).directory(root).start().waitFor()
        git("init", "-b", "main"); git("add", "."); git("commit", "-m", "init")

        assertEquals(ToolResult("git_branch", true, "main"), GitBranchTool().execute(call("git_branch"), ctx))
        assertEquals(ToolResult("git_status", true, "branch: main\nworking tree clean"), GitStatusTool().execute(call("git_status"), ctx))
        assertEquals(ToolResult("git_changed_files", true, "No changed files"), GitChangedFilesTool().execute(call("git_changed_files"), ctx))
        assertEquals(ToolResult("git_diff", true, "No diff output (nothing changed)"), GitDiffTool().execute(call("git_diff"), ctx))

        File(root, "README.md").writeText("changed")
        File(root, "new.txt").writeText("n")
        assertEquals(ToolResult("git_status", true, "branch: main\nunstaged: README.md\nuntracked: new.txt"), GitStatusTool().execute(call("git_status"), ctx))
        assertEquals(ToolResult("git_changed_files", true, "README.md"), GitChangedFilesTool().execute(call("git_changed_files"), ctx))
        val diff = GitDiffTool().execute(call("git_diff", "staged" to "false"), ctx)
        assertTrue(diff.content, diff.content.contains("-readme") && diff.content.contains("+changed"))
        val suggestion = GitCommitMessageSuggestionTool().execute(call("git_commit_message_suggestion"), ctx)
        assertTrue(suggestion.content.startsWith("Changed files: README.md\n\nDiff:\n"))
        assertFalse(GitDiffTool().execute(call("git_diff", "staged" to "true"), ctx).content.contains("+changed"))
    }
}
