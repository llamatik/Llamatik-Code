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

    // ── Task loading ─────────────────────────────────────────────────────────

    @Test
    fun `loadAll returns empty list for empty directory`() {
        val result = BenchmarkTaskLoader.loadAll(tmp.newFolder("tasks"))
        assertTrue(result.isEmpty())
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
    fun `load applies metadata fallbacks when metadata json is absent`() {
        val taskDir = makeTaskDir(tmp.root, "no-meta-task", writeMeta = false)
        val task = BenchmarkTaskLoader.load(taskDir)
        assertEquals("no-meta-task", task.id)
        assertEquals(15, task.timeoutMinutes)
        assertEquals(30, task.maxSteps)
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

    @Test
    fun `loadAll reads metadata fields correctly`() {
        val tasksRoot = tmp.newFolder("tasks")
        val taskDir = makeTaskDir(tasksRoot, "meta-task",
            meta = """{"id":"meta-task","name":"Meta Task","language":"kotlin","timeoutMinutes":10,"maxSteps":20}""")
        val tasks = BenchmarkTaskLoader.loadAll(tasksRoot)
        assertEquals(1, tasks.size)
        assertEquals("Meta Task", tasks[0].name)
        assertEquals("kotlin", tasks[0].language)
        assertEquals(10, tasks[0].timeoutMinutes)
        assertEquals(20, tasks[0].maxSteps)
    }

    // ── Changed file detection ───────────────────────────────────────────────

    @Test
    fun `computeChangedFiles detects new file`() {
        val dir = tmp.newFolder("repo")
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        File(dir, "New.kt").writeText("class New")
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertTrue("New.kt should appear in changed", changed.contains("New.kt"))
    }

    @Test
    fun `computeChangedFiles detects modified file`() {
        val dir = tmp.newFolder("repo2")
        val file = File(dir, "Existing.kt").also { it.writeText("original") }
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        Thread.sleep(10) // ensure mtime differs
        file.writeText("modified")
        file.setLastModified(System.currentTimeMillis())
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertTrue("Existing.kt should appear in changed", changed.contains("Existing.kt"))
    }

    @Test
    fun `computeChangedFiles returns empty when nothing changed`() {
        val dir = tmp.newFolder("repo3")
        File(dir, "Unchanged.kt").writeText("content")
        val snapshot = BenchmarkTaskLoader.snapshotFiles(dir)
        val changed = BenchmarkTaskLoader.computeChangedFiles(snapshot, dir)
        assertTrue(changed.isEmpty())
    }

    // ── Report generation ────────────────────────────────────────────────────

    @Test
    fun `writeAll creates results json and results md`() {
        val reportDir = tmp.newFolder("reports")
        val results = listOf(fakeResult("task-001", passed = true), fakeResult("task-002", passed = false))
        BenchmarkReportWriter.writeAll(results, reportDir, Instant.now())
        assertTrue(File(reportDir, "results.json").isFile)
        assertTrue(File(reportDir, "results.md").isFile)
    }

    @Test
    fun `results json contains task id and passed fields`() {
        val reportDir = tmp.newFolder("reports2")
        val results = listOf(fakeResult("my-task", passed = true))
        BenchmarkReportWriter.writeAll(results, reportDir, Instant.now())
        val json = File(reportDir, "results.json").readText()
        assertTrue(json.contains("my-task"))
        assertTrue(json.contains("\"passed\": true"))
    }

    @Test
    fun `results md contains summary table and totals`() {
        val reportDir = tmp.newFolder("reports3")
        val results = listOf(fakeResult("t1", passed = true), fakeResult("t2", passed = false))
        BenchmarkReportWriter.writeAll(results, reportDir, Instant.now())
        val md = File(reportDir, "results.md").readText()
        assertTrue(md.contains("| Task |"))
        assertTrue(md.contains("Total tasks: 2"))
        assertTrue(md.contains("Passed: 1"))
        assertTrue(md.contains("Failed: 1"))
        assertTrue(md.contains("Pass rate:"))
    }

    // ── Command execution timeout ────────────────────────────────────────────

    @Test
    fun `runner marks task as verification failed when command exits non-zero`() {
        val tasksRoot = tmp.newFolder("tasks-run")
        val taskDir = makeTaskDir(tasksRoot, "fail-task",
            testCommand = if (isWindows()) "exit 1" else "exit 1")
        val task = BenchmarkTaskLoader.load(taskDir)

        val backend = AgentLoopTest.FakeBackend("Done, no changes needed.")
        val config = fakeConfig()
        val reportDir = tmp.newFolder("run-reports")
        val runner = BenchmarkRunner(
            backend = backend,
            config = config,
            reportDir = reportDir,
            logsDir = File(reportDir, "logs"),
        )
        val result = runner.runTask(task)
        assertFalse("Should fail when command exits 1", result.passed)
        assertEquals(AgentStatus.VERIFICATION_FAILED, result.agentStatus)
    }

    @Test
    fun `runner marks task as passed when command exits zero`() {
        val tasksRoot = tmp.newFolder("tasks-pass")
        val taskDir = makeTaskDir(tasksRoot, "pass-task",
            testCommand = if (isWindows()) "echo ok" else "echo ok")
        val task = BenchmarkTaskLoader.load(taskDir)

        val backend = AgentLoopTest.FakeBackend("Done, no changes needed.")
        val config = fakeConfig()
        val reportDir = tmp.newFolder("pass-reports")
        val runner = BenchmarkRunner(
            backend = backend,
            config = config,
            reportDir = reportDir,
            logsDir = File(reportDir, "logs"),
        )
        val result = runner.runTask(task)
        assertTrue("Should pass when command exits 0", result.passed)
        assertEquals(AgentStatus.SUCCESS, result.agentStatus)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private fun fakeResult(taskId: String, passed: Boolean) = BenchmarkResult(
        taskId = taskId,
        taskName = taskId,
        language = "kotlin",
        promptPath = "/fake/prompt.md",
        testCommand = "./gradlew test",
        passed = passed,
        agentStatus = if (passed) AgentStatus.SUCCESS else AgentStatus.VERIFICATION_FAILED,
        durationMs = 1234L,
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

    private fun isWindows() = System.getProperty("os.name", "").lowercase().startsWith("win")
}
