package com.llamatik.teammate.benchmark

import com.llamatik.teammate.agent.AgentLoopTest
import com.llamatik.teammate.backend.BackendConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class BenchmarkHarnessTest {

    @get:Rule val tmp = TemporaryFolder()

    // ════════════════════════════════════════════════════════════════════════
    // BenchmarkTaskLoader — loading and validation
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `loadAll returns empty list for empty directory`() {
        val result = BenchmarkTaskLoader.loadAll(tmp.newFolder("tasks"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadAll skips non-directory entries`() {
        val root = tmp.newFolder("tasks")
        File(root, "not-a-dir.txt").writeText("noise")
        makeTaskDir(root, "real-task")
        val tasks = BenchmarkTaskLoader.loadAll(root)
        assertEquals(1, tasks.size)
        assertEquals("real-task", tasks[0].id)
    }

    @Test
    fun `loadAll skips invalid task directories silently`() {
        val root = tmp.newFolder("tasks")
        // directory missing prompt.md — should be skipped, not thrown
        val broken = File(root, "broken").also { it.mkdirs() }
        File(broken, "repo").mkdirs()
        File(broken, "test-command.txt").writeText("./gradlew test")
        // valid task
        makeTaskDir(root, "good-task")
        val tasks = BenchmarkTaskLoader.loadAll(root)
        assertEquals(1, tasks.size)
        assertEquals("good-task", tasks[0].id)
    }

    @Test
    fun `loadAll returns tasks sorted alphabetically by directory name`() {
        val root = tmp.newFolder("tasks")
        makeTaskDir(root, "task-c")
        makeTaskDir(root, "task-a")
        makeTaskDir(root, "task-b")
        val ids = BenchmarkTaskLoader.loadAll(root).map { it.id }
        assertEquals(listOf("task-a", "task-b", "task-c"), ids)
    }

    @Test
    fun `load succeeds for valid task directory`() {
        val taskDir = makeTaskDir(tmp.root, "test-task-001")
        val task = BenchmarkTaskLoader.load(taskDir)
        assertEquals("test-task-001", task.id)
        assertEquals("./gradlew test", task.testCommand)
        assertEquals("Fix the bug.", task.prompt)
    }

    @Test
    fun `load trims whitespace from prompt and test command`() {
        val taskDir = makeTaskDir(tmp.root, "trim-task")
        File(taskDir, "prompt.md").writeText("  Fix the bug.  \n")
        File(taskDir, "test-command.txt").writeText("  ./gradlew test  \n")
        val task = BenchmarkTaskLoader.load(taskDir)
        assertEquals("Fix the bug.", task.prompt)
        assertEquals("./gradlew test", task.testCommand)
    }

    @Test
    fun `load applies metadata fallbacks when metadata json is absent`() {
        val taskDir = makeTaskDir(tmp.root, "no-meta-task", writeMeta = false)
        val task = BenchmarkTaskLoader.load(taskDir)
        assertEquals("no-meta-task", task.id)
        assertEquals("no-meta-task", task.name)
        assertEquals("unknown", task.language)
        assertEquals(15, task.timeoutMinutes)
        assertEquals(30, task.maxSteps)
        assertTrue(task.expectedFiles.isEmpty())
    }

    @Test
    fun `loadAll reads all metadata fields correctly`() {
        val root = tmp.newFolder("tasks")
        makeTaskDir(root, "meta-task",
            meta = """{"id":"meta-task","name":"Meta Task","language":"kotlin","timeoutMinutes":10,"maxSteps":20}""")
        val tasks = BenchmarkTaskLoader.loadAll(root)
        assertEquals(1, tasks.size)
        with(tasks[0]) {
            assertEquals("meta-task", id)
            assertEquals("Meta Task", name)
            assertEquals("kotlin", language)
            assertEquals(10, timeoutMinutes)
            assertEquals(20, maxSteps)
        }
    }

    @Test
    fun `load parses expectedFiles list from metadata`() {
        val taskDir = makeTaskDir(tmp.root, "files-task",
            meta = """{"id":"files-task","name":"Files Task","expectedFiles":["src/Main.kt","src/Util.kt"]}""")
        val task = BenchmarkTaskLoader.load(taskDir)
        assertEquals(listOf("src/Main.kt", "src/Util.kt"), task.expectedFiles)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `load throws when repo directory is missing`() {
        val taskDir = tmp.newFolder("bad-task")
        File(taskDir, "prompt.md").writeText("prompt")
        File(taskDir, "test-command.txt").writeText("./gradlew test")
        BenchmarkTaskLoader.load(taskDir)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `load throws when prompt md is missing`() {
        val taskDir = tmp.newFolder("no-prompt")
        File(taskDir, "repo").mkdirs()
        File(taskDir, "test-command.txt").writeText("./gradlew test")
        BenchmarkTaskLoader.load(taskDir)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `load throws when test-command txt is missing`() {
        val taskDir = tmp.newFolder("no-cmd")
        File(taskDir, "repo").mkdirs()
        File(taskDir, "prompt.md").writeText("prompt")
        BenchmarkTaskLoader.load(taskDir)
    }

    // ════════════════════════════════════════════════════════════════════════
    // BenchmarkTaskLoader — changed file detection
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `computeChangedFiles detects new file`() {
        val dir = tmp.newFolder("repo")
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        File(dir, "New.kt").writeText("class New")
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertTrue(changed.contains("New.kt"))
    }

    @Test
    fun `computeChangedFiles detects modified file via mtime`() {
        val dir = tmp.newFolder("repo2")
        val file = File(dir, "Existing.kt").also { it.writeText("original") }
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        Thread.sleep(10)
        file.writeText("modified")
        file.setLastModified(System.currentTimeMillis())
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertTrue(changed.contains("Existing.kt"))
    }

    @Test
    fun `computeChangedFiles returns empty when nothing changed`() {
        val dir = tmp.newFolder("repo3")
        File(dir, "Unchanged.kt").writeText("content")
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        assertTrue(BenchmarkTaskLoader.computeChangedFiles(snapshot, dir).isEmpty())
    }

    @Test
    fun `computeChangedFiles detects new file in subdirectory`() {
        val dir = tmp.newFolder("repo4")
        File(dir, "src").mkdirs()
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        File(dir, "src/NewSub.kt").writeText("class NewSub")
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertTrue(changed.any { it.contains("NewSub.kt") })
    }

    @Test
    fun `computeChangedFiles result is sorted`() {
        val dir = tmp.newFolder("repo5")
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        File(dir, "C.kt").writeText("c")
        File(dir, "A.kt").writeText("a")
        File(dir, "B.kt").writeText("b")
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertEquals(changed.sorted(), changed)
    }

    @Test
    fun `snapshotFiles excludes ignored directories`() {
        val dir = tmp.newFolder("repo6")
        File(dir, "src").mkdirs()
        File(dir, "src/Real.kt").writeText("class Real")
        File(dir, "build").mkdirs()
        File(dir, "build/Output.class").writeText("bytecode")
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        val paths = snapshot.keys
        assertTrue(paths.any { it.contains("Real.kt") })
        assertFalse("build/ should be excluded from snapshot", paths.any { it.contains("Output.class") })
    }

    // ════════════════════════════════════════════════════════════════════════
    // BenchmarkReportWriter — JSON
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `writeAll creates results json and results md`() {
        val reportDir = tmp.newFolder("reports")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true), fakeResult("t2", false)), reportDir, Instant.now())
        assertTrue(File(reportDir, "results.json").isFile)
        assertTrue(File(reportDir, "results.md").isFile)
    }

    @Test
    fun `results json contains task id and passed true`() {
        val reportDir = tmp.newFolder("r1")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("my-task", passed = true)), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("\"taskId\": \"my-task\""))
        assertTrue(json.contains("\"passed\": true"))
    }

    @Test
    fun `results json contains passed false for failed task`() {
        val reportDir = tmp.newFolder("r2")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("bad-task", passed = false)), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("\"passed\": false"))
    }

    @Test
    fun `results json counts passed and failed correctly`() {
        val reportDir = tmp.newFolder("r3")
        val results = listOf(fakeResult("t1", true), fakeResult("t2", true), fakeResult("t3", false))
        BenchmarkReportWriter.writeAll(results, reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("\"passed\": 2"))
        assertTrue(json.contains("\"failed\": 1"))
        assertTrue(json.contains("\"totalTasks\": 3"))
    }

    @Test
    fun `results json includes agentStatus`() {
        val reportDir = tmp.newFolder("r4")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true)), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("\"agentStatus\": \"SUCCESS\""))
    }

    @Test
    fun `results json includes changedFiles list`() {
        val reportDir = tmp.newFolder("r5")
        val result = fakeResult("t1", true).copy(changedFiles = listOf("src/Foo.kt", "src/Bar.kt"))
        BenchmarkReportWriter.writeAll(listOf(result), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("src/Foo.kt"))
        assertTrue(json.contains("src/Bar.kt"))
    }

    @Test
    fun `results json writes null for null errorMessage`() {
        val reportDir = tmp.newFolder("r6")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true)), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("\"errorMessage\": null"))
    }

    @Test
    fun `results json escapes special characters in error message`() {
        val reportDir = tmp.newFolder("r7")
        val result = fakeResult("t1", false).copy(errorMessage = "error: \"quoted\" path\\here\nnewline")
        BenchmarkReportWriter.writeAll(listOf(result), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        // Must not contain raw newline inside the string value
        val errorLine = json.lines().first { it.contains("errorMessage") }
        assertFalse("raw newline must be escaped in JSON string", errorLine.contains("\n"))
        assertTrue(json.contains("\\\"quoted\\\""))
        assertTrue(json.contains("\\\\here"))
    }

    @Test
    fun `results json includes model and pluginVersion from first result`() {
        val reportDir = tmp.newFolder("r8")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true)), reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("\"pluginVersion\": \"1.0.0\""))
        assertTrue(json.contains("\"model\": \"test-model\""))
    }

    // ════════════════════════════════════════════════════════════════════════
    // BenchmarkReportWriter — Markdown
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `results md contains summary table header`() {
        val reportDir = tmp.newFolder("m1")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("| Task |"))
        assertTrue(md.contains("| Language |"))
        assertTrue(md.contains("| Status |"))
        assertTrue(md.contains("| Passed |"))
        assertTrue(md.contains("| Duration |"))
    }

    @Test
    fun `results md shows checkmark for passed and cross for failed`() {
        val reportDir = tmp.newFolder("m2")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true), fakeResult("t2", false)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("✓"))
        assertTrue(md.contains("✗"))
    }

    @Test
    fun `results md totals are correct for mixed results`() {
        val reportDir = tmp.newFolder("m3")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true), fakeResult("t2", false)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("Total tasks: 2"))
        assertTrue(md.contains("Passed: 1"))
        assertTrue(md.contains("Failed: 1"))
        assertTrue(md.contains("Pass rate:"))
    }

    @Test
    fun `results md shows 100 percent pass rate when all pass`() {
        val reportDir = tmp.newFolder("m4")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true), fakeResult("t2", true)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("100%"))
        assertTrue(md.contains("Passed: 2"))
        assertTrue(md.contains("Failed: 0"))
    }

    @Test
    fun `results md shows 0 percent pass rate when all fail`() {
        val reportDir = tmp.newFolder("m5")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", false), fakeResult("t2", false)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("0%"))
        assertTrue(md.contains("Passed: 0"))
        assertTrue(md.contains("Failed: 2"))
    }

    @Test
    fun `results md includes average duration line`() {
        val reportDir = tmp.newFolder("m6")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("t1", true, durationMs = 2000L)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("Average duration:"))
        assertTrue(md.contains("2.0s"))
    }

    @Test
    fun `results md includes task name and test command in row`() {
        val reportDir = tmp.newFolder("m7")
        BenchmarkReportWriter.writeAll(listOf(fakeResult("my-task", true)), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("my-task"))
        assertTrue(md.contains("./gradlew test"))
    }

    @Test
    fun `results md handles empty result list`() {
        val reportDir = tmp.newFolder("m8")
        BenchmarkReportWriter.writeAll(emptyList(), reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("Total tasks: 0"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // BenchmarkRunner — result fields and log files
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `runner marks task as verification failed when command exits non-zero`() {
        val task = makeTask(testCommand = "exit 1")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertFalse(result.passed)
        assertEquals(AgentStatus.VERIFICATION_FAILED, result.agentStatus)
    }

    @Test
    fun `runner marks task as passed when command exits zero`() {
        val task = makeTask(testCommand = "echo ok")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertTrue(result.passed)
        assertEquals(AgentStatus.SUCCESS, result.agentStatus)
    }

    @Test
    fun `runner populates changedFiles when agent writes a file`() {
        val taskDir = makeTaskDir(tmp.newFolder("runner-tasks"), "write-task",
            testCommand = "echo ok")
        val repoFile = File(taskDir, "repo/Existing.kt").also { it.writeText("original") }
        val task = BenchmarkTaskLoader.load(taskDir)

        val writeResponse = """<tool_call name="write_file"><arg name="path">Existing.kt</arg><arg name="content">updated</arg></tool_call>"""
        val backend = AgentLoopTest.FakeBackend(writeResponse, "Done.")

        val reportDir = tmp.newFolder("wr-reports")
        val result = BenchmarkRunner(backend, fakeConfig(), reportDir = reportDir, logsDir = File(reportDir, "logs"))
            .runTask(task)
        assertTrue(result.passed)
        assertTrue("changedFiles should contain Existing.kt", result.changedFiles.any { it.contains("Existing.kt") })
    }

    @Test
    fun `runner writes stdout and stderr log files`() {
        val task = makeTask(testCommand = "echo hello_stdout")
        val reportDir = tmp.newFolder("log-reports")
        val logsDir = File(reportDir, "logs")
        BenchmarkRunner(AgentLoopTest.FakeBackend("Done."), fakeConfig(), reportDir = reportDir, logsDir = logsDir)
            .runTask(task)
        val stdout = File(logsDir, "${task.id}-stdout.txt")
        assertTrue("stdout log should exist", stdout.isFile)
        assertTrue(stdout.readText().contains("hello_stdout"))
    }

    @Test
    fun `runner writes combined task log file`() {
        val task = makeTask(testCommand = "echo ok")
        val reportDir = tmp.newFolder("combined-reports")
        val logsDir = File(reportDir, "logs")
        BenchmarkRunner(AgentLoopTest.FakeBackend("Done."), fakeConfig(), reportDir = reportDir, logsDir = logsDir)
            .runTask(task)
        val logFile = File(logsDir, "${task.id}.log")
        assertTrue("Combined log should exist", logFile.isFile)
        assertTrue(logFile.readText().contains("Agent output"))
    }

    @Test
    fun `runner result contains modelLabel from backend`() {
        val task = makeTask(testCommand = "echo ok")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertEquals("fake-model", result.modelLabel)
    }

    @Test
    fun `runner result contains pluginVersion`() {
        val task = makeTask(testCommand = "echo ok")
        val reportDir = tmp.newFolder("ver-reports")
        val result = BenchmarkRunner(
            AgentLoopTest.FakeBackend("Done."), fakeConfig(),
            pluginVersion = "9.9.9",
            reportDir = reportDir,
            logsDir = File(reportDir, "logs"),
        ).runTask(task)
        assertEquals("9.9.9", result.pluginVersion)
    }

    @Test
    fun `runAll runs all tasks and returns a result per task`() {
        val root = tmp.newFolder("multi-tasks")
        val t1 = BenchmarkTaskLoader.load(makeTaskDir(root, "t1", testCommand = "echo ok"))
        val t2 = BenchmarkTaskLoader.load(makeTaskDir(root, "t2", testCommand = "echo ok"))
        val results = runnerFor(AgentLoopTest.FakeBackend("Done.")).runAll(listOf(t1, t2))
        assertEquals(2, results.size)
        assertTrue(results.all { it.passed })
    }

    @Test
    fun `runner sets AGENT_ERROR status when backend reports an error`() {
        val task = makeTask(testCommand = "echo ok")
        val reportDir = tmp.newFolder("err-reports")
        val errorBackend = object : com.llamatik.teammate.backend.AssistantBackend {
            override fun isModelLoaded() = true
            override fun selectedModelLabel() = "error-model"
            override fun generate(
                request: com.llamatik.teammate.backend.GenerationRequest,
                callbacks: com.llamatik.teammate.backend.GenerationCallbacks,
            ): com.llamatik.teammate.backend.GenerationHandle {
                callbacks.onError("model exploded")
                return object : com.llamatik.teammate.backend.GenerationHandle {
                    override fun cancel() {}
                    override val isCancelled = false
                }
            }
        }
        val result = BenchmarkRunner(errorBackend, fakeConfig(), reportDir = reportDir, logsDir = File(reportDir, "logs"))
            .runTask(task)
        assertFalse(result.passed)
        assertEquals(AgentStatus.AGENT_ERROR, result.agentStatus)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `runner does not modify original fixture repo`() {
        val taskDir = makeTaskDir(tmp.newFolder("iso-tasks"), "iso-task", testCommand = "echo ok")
        val originalFile = File(taskDir, "repo/Original.kt").also { it.writeText("untouched") }
        val writeResponse = """<tool_call name="write_file"><arg name="path">Original.kt</arg><arg name="content">changed</arg></tool_call>"""
        val reportDir = tmp.newFolder("iso-reports")
        BenchmarkRunner(AgentLoopTest.FakeBackend(writeResponse, "Done."), fakeConfig(),
            reportDir = reportDir, logsDir = File(reportDir, "logs"))
            .runTask(BenchmarkTaskLoader.load(taskDir))
        assertEquals("untouched", originalFile.readText())
    }

    @Test
    fun `runner errorMessage is null for successful task`() {
        val task = makeTask(testCommand = "echo ok")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertNull(result.errorMessage)
    }

    @Test
    fun `runner errorMessage is set for verification failure`() {
        val task = makeTask(testCommand = "exit 1")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.isNotBlank() == true)
    }

    @Test
    fun `runner durationMs is positive`() {
        val task = makeTask(testCommand = "echo ok")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `runner startedAt is before finishedAt`() {
        val task = makeTask(testCommand = "echo ok")
        val result = runnerFor(AgentLoopTest.FakeBackend("Done.")).runTask(task)
        assertFalse(result.startedAt.isAfter(result.finishedAt))
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun makeTask(testCommand: String = "echo ok"): BenchmarkTask {
        val root = tmp.newFolder()
        val taskDir = makeTaskDir(root, "task-${System.nanoTime()}", testCommand = testCommand)
        return BenchmarkTaskLoader.load(taskDir)
    }

    private fun runnerFor(backend: com.llamatik.teammate.backend.AssistantBackend): BenchmarkRunner {
        val reportDir = tmp.newFolder()
        return BenchmarkRunner(backend, fakeConfig(), reportDir = reportDir, logsDir = File(reportDir, "logs"))
    }

    private fun makeTaskDir(
        root: File,
        name: String,
        writeMeta: Boolean = true,
        meta: String? = null,
        testCommand: String = "./gradlew test",
    ): File {
        val dir = File(root, name).also { it.mkdirs() }
        File(dir, "repo").mkdirs()
        File(dir, "prompt.md").writeText("Fix the bug.")
        File(dir, "test-command.txt").writeText(testCommand)
        if (writeMeta) {
            File(dir, "metadata.json").writeText(
                meta ?: """{"id":"$name","name":"$name","language":"kotlin","timeoutMinutes":15,"maxSteps":30}"""
            )
        }
        return dir
    }

    private fun fakeResult(taskId: String, passed: Boolean, durationMs: Long = 1234L) = BenchmarkResult(
        taskId = taskId,
        taskName = taskId,
        language = "kotlin",
        promptPath = "/fake/prompt.md",
        testCommand = "./gradlew test",
        passed = passed,
        agentStatus = if (passed) AgentStatus.SUCCESS else AgentStatus.VERIFICATION_FAILED,
        durationMs = durationMs,
        stdoutPath = null,
        stderrPath = null,
        changedFiles = emptyList(),
        errorMessage = if (!passed) "exit code 1" else null,
        startedAt = Instant.now(),
        finishedAt = Instant.now(),
        modelLabel = "test-model",
        pluginVersion = "1.0.0",
    )

    private fun fakeConfig() = BackendConfig(
        modelIdOrPath = "fake.gguf",
        temperature = 0.0,
        maxTokens = 512,
        contextLength = 4096,
        numThreads = 1,
        useMmap = false,
        flashAttention = false,
        batchSize = 512,
        localOnly = true,
    )
}
