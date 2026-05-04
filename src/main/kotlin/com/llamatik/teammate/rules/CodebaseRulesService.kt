package com.llamatik.teammate.rules

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.util.PluginLogger

@State(
    name = "LlamatikCodebaseRules",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
@Service(Service.Level.PROJECT)
class CodebaseRulesService(private val project: Project) :
    PersistentStateComponent<CodebaseRulesService.State> {

    private val log = PluginLogger.logger<CodebaseRulesService>()

    data class Rule(
        var id: String = "",
        var text: String = "",
        var confidence: String = "medium",
        var evidence: String = "",
        var enabled: Boolean = true,
        var source: String = "detected",
    )

    data class State(
        var rules: MutableList<Rule> = mutableListOf(),
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun getRules(): List<Rule> = myState.rules.filter { it.enabled }
    fun getAllRules(): List<Rule> = myState.rules.toList()

    fun setRuleEnabled(id: String, enabled: Boolean) {
        myState.rules.find { it.id == id }?.enabled = enabled
    }

    fun updateRuleText(id: String, text: String) {
        myState.rules.find { it.id == id }?.text = text
    }

    /** Runs deterministic detectors and overwrites discovered rules. User-edited rules are preserved. */
    fun refresh() {
        val ctx = ProjectContextService(project)
        val files = runCatching { ctx.listFiles(null) }.getOrDefault(emptyList())
        val detected = detect(files, ctx)
        // Merge: preserve user-sourced rules; replace detected ones
        val preserved = myState.rules.filter { it.source == "user" }
        myState.rules.clear()
        myState.rules += preserved
        myState.rules += detected
        log.info("Codebase rules refreshed: ${detected.size} detected rules")
    }

    fun buildPromptBlock(maxChars: Int = 600): String {
        val rules = getRules()
        if (rules.isEmpty()) return ""
        return buildString {
            appendLine("=== Project rules ===")
            rules.take(15).forEach { appendLine("- ${it.text}") }
            appendLine("=== End project rules ===")
        }.take(maxChars)
    }

    private fun detect(files: List<String>, ctx: ProjectContextService): List<Rule> {
        val rules = mutableListOf<Rule>()
        val fileSet = files.toSet()
        val lowerFiles = files.map { it.lowercase() }

        // Language / build system
        when {
            lowerFiles.any { it.endsWith("build.gradle.kts") } -> {
                rules += rule("lang-kotlin", "Write all new code in Kotlin", "high", "build.gradle.kts present")
                tryReadGradleKts(ctx, files, rules)
            }
            lowerFiles.any { it.endsWith("build.gradle") } -> {
                rules += rule("lang-java", "Write new code in Java/Kotlin (Gradle project)", "high", "build.gradle present")
            }
            lowerFiles.any { it.endsWith("pom.xml") } -> {
                rules += rule("lang-java-maven", "Write new code in Java (Maven project)", "high", "pom.xml present")
            }
        }

        // Detect Compose
        if (lowerFiles.any { it.contains("compose") || it.endsWith(".kt") && it.contains("ui") }) {
            val sampleKt = files.firstOrNull { it.lowercase().contains("screen") && it.endsWith(".kt") }
            val hasCompose = sampleKt?.let {
                runCatching { ctx.readFile(it) }.getOrNull()?.contains("@Composable")
            } ?: false
            if (hasCompose) {
                rules += rule("ui-compose", "Use Jetpack Compose for all UI", "high", "found @Composable in source")
                // Detect screen package
                val screenPkg = files.filter { it.lowercase().contains("screen") && it.endsWith(".kt") }
                    .firstOrNull()?.substringBeforeLast('/')
                if (screenPkg != null) {
                    rules += rule("ui-compose-screens", "Compose screens live under $screenPkg", "medium", screenPkg)
                }
            }
        }

        // Detect test framework
        val testFiles = files.filter { f -> f.lowercase().contains("/test/") && f.endsWith(".kt") }
        if (testFiles.isNotEmpty()) {
            val sample = testFiles.take(3).mapNotNull { runCatching { ctx.readFile(it) }.getOrNull() }.joinToString("\n")
            when {
                sample.contains("import io.mockk") -> rules += rule("test-mockk", "Use MockK for mocking in tests", "high", "import io.mockk found in tests")
                sample.contains("import org.mockito") -> rules += rule("test-mockito", "Use Mockito for mocking in tests", "high", "import org.mockito found")
            }
            when {
                sample.contains("import org.junit.jupiter") || sample.contains("@ExtendWith") ->
                    rules += rule("test-junit5", "Use JUnit 5 for tests", "high", "JUnit5 imports found")
                sample.contains("import org.junit.Test") ->
                    rules += rule("test-junit4", "Use JUnit 4 for tests", "medium", "JUnit4 imports found")
            }
            when {
                sample.contains("import io.kotest") -> rules += rule("test-kotest", "Use Kotest for tests", "high", "Kotest imports found")
            }
        }

        // Detect DI framework
        val srcFiles = files.filter { it.endsWith(".kt") && !it.contains("/test/") }
        val diSample = srcFiles.take(10).mapNotNull { runCatching { ctx.readFile(it) }.getOrNull() }.joinToString("\n").take(20000)
        when {
            diSample.contains("@HiltAndroidApp") || diSample.contains("import dagger.hilt") ->
                rules += rule("di-hilt", "Use Hilt for dependency injection", "high", "@HiltAndroidApp found")
            diSample.contains("import org.koin") ->
                rules += rule("di-koin", "Use Koin for dependency injection", "high", "Koin imports found")
            diSample.contains("@Inject") && diSample.contains("import javax.inject") ->
                rules += rule("di-dagger", "Use Dagger/inject for dependency injection", "medium", "@Inject + javax.inject")
        }

        // Detect StateFlow / LiveData
        when {
            diSample.contains("StateFlow") || diSample.contains("MutableStateFlow") ->
                rules += rule("state-stateflow", "Use StateFlow / MutableStateFlow for observable state in ViewModels", "high", "StateFlow found in source")
            diSample.contains("LiveData") || diSample.contains("MutableLiveData") ->
                rules += rule("state-livedata", "Use LiveData / MutableLiveData for observable state", "medium", "LiveData found")
        }

        // Detect coroutines
        if (diSample.contains("import kotlinx.coroutines")) {
            rules += rule("async-coroutines", "Use Kotlin Coroutines for async operations", "high", "kotlinx.coroutines imports found")
        }

        // Detect Retrofit / Ktor
        when {
            diSample.contains("import retrofit2") ->
                rules += rule("http-retrofit", "Use Retrofit for HTTP calls", "high", "retrofit2 imports found")
            diSample.contains("import io.ktor") ->
                rules += rule("http-ktor", "Use Ktor client for HTTP calls", "high", "io.ktor imports found")
        }

        return rules
    }

    private fun tryReadGradleKts(ctx: ProjectContextService, files: List<String>, rules: MutableList<Rule>) {
        val gradleFile = files.firstOrNull { it.endsWith("build.gradle.kts") } ?: return
        val content = runCatching { ctx.readFile(gradleFile) }.getOrNull() ?: return
        when {
            content.contains("com.android.application") || content.contains("com.android.library") ->
                rules += rule("platform-android", "This is an Android project", "high", gradleFile)
        }
    }

    private fun rule(id: String, text: String, confidence: String, evidence: String) =
        Rule(id = id, text = text, confidence = confidence, evidence = evidence, enabled = true, source = "detected")
}
