import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import proguard.gradle.ProGuardTask
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.9.1")
        classpath("org.ow2.asm:asm:9.7")
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.llamatik.teammate"
version = "1.6.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("org.jetbrains.kotlin")
    }

    implementation("com.llamatik:library:1.3.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
        changeNotes = """
            <h2>1.6.4 — File Reading Fix</h2>

            <h3>Bug Fixes</h3>
            <ul>
                <li><b>Large files were silently truncated when read by the agent</b> — when the agent used <code>read_file</code>, the result was cut off mid-file (e.g. stopping at line 299 of a 571-line file) before being forwarded to the model. Two separate causes were found and fixed:</li>
                <li><b>Batch Size was incorrectly used to cap the file budget</b> — Batch Size is a KV-cache inference parameter, not the model context window. Using <code>min(contextSize, batchSize)</code> meant that the default Batch Size of 2 048 could collapse the entire file budget to zero. The budget is now derived from Context Size only, which is the true model context window. With the default Context Size of 16 384, the agent can now read files up to ~1 150 lines (35 chars/line) without truncation.</li>
                <li><b>A hardcoded 4 000-character cap on tool results was overriding the per-file limit</b> — even when the per-result budget was large enough, a second hard cap on the total tool-results block cut off the content. Both caps now scale dynamically with Context Size.</li>
                <li><b>Floor guarantee added</b> — even with a very small Context Size, <code>read_file</code> results are guaranteed at least 8 000 characters (~570 lines), preventing total content loss regardless of settings.</li>
            </ul>

            <h2>1.6.3 — Prompt Budget Fix</h2>

            <h3>Bug Fixes</h3>
            <ul>
                <li><b>"Prompt too long" error with Batch Size 8192</b> — reviewing or attaching large files (e.g. via <code>@{path}</code>)</li>
            </ul>

            <h2>1.6.2 — Conversation Context &amp; Inference Settings</h2>

            <h3>Agent Conversation History</h3>
            <ul>
                <li><b>Threaded agent conversations</b> — follow-up prompts in the Agent tab now continue in the same conversation instead of starting a new task. Each completed exchange is frozen into the scroll area above the live response, giving a chat-like history view.</li>
                <li><b>New task only on explicit request</b> — a new task is created only when clicking the <b>+</b> button in the task queue. Submitting a follow-up prompt reuses the current task.</li>
                <li><b>File context persists across turns</b> — if you attach a file in your first prompt, follow-up prompts ("add more tests", "fix that error") automatically inherit the same file context without you needing to re-attach it.</li>
                <li><b>Conversation history sent to model</b> — prior exchanges are included in subsequent agent requests so the model knows which file was being worked on and what was already done.</li>
                <li><b>Meaningful assistant summaries</b> — when the agent writes files, the completed turn records which files were changed, so history is informative for future turns.</li>
            </ul>

            <h3>File Size &amp; Context Budget</h3>
            <ul>
                <li><b>File content limits scale with model settings</b> — the amount of a file sent to the model is now derived from your configured Batch Size, so larger batch sizes unlock larger file reads automatically. Previously files were hard-capped at ~2 000 characters (~50 lines) regardless of settings.</li>
                <li><b>read_file tool results also scale</b> — the agent's <code>read_file</code> tool result budget follows the same dynamic limit, so the model sees the full file content when reading files via tools.</li>
            </ul>

            <h3>Inference Settings</h3>
            <ul>
                <li><b>Batch Size now available on Free tier</b> — the Batch Size setting is no longer restricted to Pro. Free users can now increase it to fix "Prompt too long" errors when working with larger files.</li>
                <li><b>Flash Attention now available on Free tier</b> — Flash Attention is no longer restricted to Pro. Free users with compatible hardware can enable it to reduce VRAM usage.</li>
                <li>Context Size remains the only Pro-exclusive inference setting.</li>
            </ul>

            <h2>1.6.1 — File Access Fixes &amp; Copy/Paste Improvements</h2>

            <h3>Bug Fixes</h3>
            <ul>
                <li><b>Files inside <code>build/</code> directories now readable</b> — explicitly referencing a file (e.g. via <code>@path</code>) inside <code>build/</code>, <code>generated/</code>, or other ignored directories no longer returns "Access denied". The ignore list now applies only to file listing, not to explicit reads.</li>
                <li><b>Absolute path references resolved correctly</b> — using <code>@</code> to attach a file with an absolute path caused a silent "File not found" error. Absolute paths are now handled directly via the local filesystem.</li>
                <li><b>Text selection no longer lost on focus change</b> — selecting text in a chat or agent message bubble and then clicking elsewhere no longer clears the selection highlight, making copy/paste reliable.</li>
            </ul>

            <h3>Improvements</h3>
            <ul>
                <li><b>Right-click context menu on message bubbles</b> — right-clicking any message in the Chat panel shows a popup with <i>Copy</i> (selection), <i>Copy All</i>, and <i>Select All</i>. Works on both user and assistant messages.</li>
                <li><b>Right-click context menu in Agent panel</b> — same context menu is available on the response text, reasoning block, agent trace, and build error areas.</li>
            </ul>

            <h2>1.6.0 — Custom Skills &amp; PyCharm Compatibility</h2>

            <p>This release adds user-defined custom skills and fixes a hard dependency on the Kotlin plugin that prevented the plugin from loading in PyCharm and other non-IntelliJ JetBrains IDEs.</p>

            <h3>Custom Skills</h3>
            <ul>
                <li><b>Add your own skills</b> — a new <b>+ Add Skill</b> button in the Skill Library lets you create custom skills in two ways: paste markdown directly, or download a <code>.md</code> file from a URL.</li>
                <li>Custom skills are saved to <code>~/.llamatik/skills/</code> and persist across IDE restarts.</li>
                <li>The first H1 heading in the markdown becomes the skill name; the first paragraph becomes the description; the full file content is injected into the agent system prompt when the skill is active.</li>
                <li>Custom skills appear in the Skill Library with a <b>Custom</b> badge and a <b>Delete</b> button that removes both the entry and the file from disk after confirmation.</li>
                <li>Custom skills support search, activate/deactivate, and the same Free/Pro activation limits as built-in skills.</li>
                <li>Stale skill paths (files deleted outside the IDE) are pruned automatically on next load.</li>
            </ul>

            <h3>IDE Compatibility Fix</h3>
            <ul>
                <li>Fixed a bug that caused the plugin to fail to load in <b>PyCharm</b>, <b>WebStorm</b>, and other JetBrains IDEs that do not bundle the Kotlin plugin. The Kotlin dependency is now correctly declared as optional with a separate config file (<code>llamatik-kotlin.xml</code>), so Kotlin-specific extensions are only registered when the Kotlin plugin is available.</li>
            </ul>

            <h2>1.5.0 — Code Health Monitor &amp; External Knowledge</h2>

            <p>This release ships two major feature areas: a proactive <b>Code Health Monitor</b> that analyses your codebase in the background and surfaces actionable insights, and <b>Context-Aware External Knowledge</b> that gives the agent the ability to research the web, official docs, and GitHub issues in real time.</p>

            <h3>Code Health Monitor (opt-in)</h3>
            <ul>
                <li>Background static analysis detects long files, TODO/FIXME accumulation, empty catch blocks, and deprecated API usage — no model calls required.</li>
                <li><b>Regression risk detection</b> — analyses git diffs for removed public declarations, deleted tests, dependency changes in Gradle files, removed error handling, and AndroidManifest modifications.</li>
                <li><b>Before-commit review</b> — summarises all staged changes and flags potential regressions before you commit.</li>
                <li><b>Watched areas</b> — pin files, directories, packages, or modules; the monitor prioritises insights for areas you care about most.</li>
                <li><b>Refactor opportunity detection</b> — highlights long functions and repeated catch patterns across the project.</li>
                <li><b>Session context</b> — tracks recent files, branch, and build failures to provide richer, context-aware suggestions.</li>
                <li>14 new agent tools: <code>code_health_status</code>, <code>code_health_refresh</code>, <code>code_health_list_insights</code>, <code>code_health_explain_insight</code>, <code>code_health_create_fix_plan</code>, <code>code_health_ignore_insight</code>, <code>watch_area_add/remove/list</code>, <code>session_context_get/clear</code>, <code>before_commit_review</code>, <code>regression_risk_analyze</code>, <code>refactor_analyze_file</code>.</li>
                <li><b>Settings → Llamatik Code → Code Health Monitor</b> — configure analysis mode, run triggers, budget limits, and notification behaviour.</li>
                <li>Free: LIGHT mode, manual trigger only, up to 5 files per scan, 3 model calls/hour. Pro: BALANCED (30 files, 30 s) and AGGRESSIVE (150 files, 120 s) modes with background scheduling and on-save/idle/before-commit triggers.</li>
            </ul>

            <h3>Context-Aware External Knowledge (opt-in)</h3>
            <ul>
                <li>The agent can now search the web via DuckDuckGo and read pages from official documentation, GitHub issues, and other sources — entirely within the IDE.</li>
                <li><b>Research orchestration</b> — detects research intent (library docs, bug/error, API behaviour, migration, latest version) and plans multi-query searches automatically.</li>
                <li><b>Smart source routing</b> — directs queries to the most relevant source (kotlinlang.org, developer.android.com, developer.apple.com, github.com, etc.) based on query context.</li>
                <li><b>Error research</b> — analyses compiler errors, detects the build tool and framework, and produces a structured fix plan with actionable steps.</li>
                <li><b>Private docs indexing</b> — add up to 10 (Pro) or 1 (Free) custom documentation sources; the indexer keeps them fresh and searchable.</li>
                <li><b>Citations</b> — every answer backed by external content includes structured citations with source type, confidence, and URL.</li>
                <li>16 new agent tools: <code>external_search</code>, <code>external_search_official_docs</code>, <code>external_search_github_issues</code>, <code>external_read_page/pages</code>, <code>research_plan/execute/summarize/route_sources/fix_plan</code>, <code>docs_sources_list/add/index_refresh/search</code>, <code>error_research_analyze/fix</code>.</li>
                <li>New editor action: <b>Search Docs &amp; Web</b> in the right-click context menu.</li>
                <li>Free: 5 searches/day, 3 pages/task, 1 docs source. Pro: 50 searches/day, 10 pages/task, 10 docs sources.</li>
            </ul>

            <h2>1.4.0 — MCP Server Support</h2>

            <p>Llamatik Code is now an IDE-native MCP host. Configure local MCP servers, discover their tools, resources, and prompts, and let the agent call them through the existing tool pipeline — all without leaving IntelliJ.</p>

            <h3>MCP server configuration</h3>
            <ul>
                <li><b>Settings → Llamatik Code → MCP Servers</b> — new dedicated settings page to add, edit, and remove MCP servers.</li>
                <li>Per-server configuration: command, arguments (one per line), environment variables, working directory, enabled toggle, auto-start toggle, and trust level.</li>
                <li><b>Test connection</b> — starts the server, initialises the protocol, lists its tools/resources/prompts, and reports success or failure with a tool count summary.</li>
                <li><b>Refresh tools</b> — re-discovers tools from a running server and updates the agent tool list immediately.</li>
                <li><b>View logs</b> — shows the in-memory stderr log ring buffer for each server.</li>
            </ul>

            <h3>MCP agent tools</h3>
            <ul>
                <li>Six new built-in agent tools: <code>mcp_list_servers</code>, <code>mcp_list_tools</code>, <code>mcp_call_tool</code>, <code>mcp_list_resources</code>, <code>mcp_read_resource</code>, <code>mcp_list_prompts</code>.</li>
                <li>Discovered MCP tools are also bridged into the agent as first-class tools under <code>mcp_&lt;serverName&gt;_&lt;toolName&gt;</code> names, visible in the agent trace like any built-in tool.</li>
                <li>MCP tool calls appear in the "What I tried" trace panel with server name, tool name, duration, and outcome.</li>
            </ul>

            <h3>Approval flow</h3>
            <ul>
                <li>Every MCP tool call shows an approval dialog with server name, tool name, arguments, and a risk level (LOW / MEDIUM / HIGH based on the tool name and description).</li>
                <li>HIGH-risk tools (write, delete, execute, deploy, send, and similar) are never auto-approved unless the user explicitly opts in.</li>
                <li>Free: <b>Allow once</b> or <b>Deny</b> on every call — no persistent rules.</li>
                <li>Pro: additional <b>Allow always for this tool</b> and <b>Allow always for this server</b> options store auto-approval rules that skip future dialogs.</li>
            </ul>

            <h3>Free vs Pro</h3>
            <ul>
                <li><b>Free</b> — one active MCP server; every tool call requires manual approval.</li>
                <li><b>Pro</b> — unlimited active servers; optional per-server and per-tool auto-approval; MCP tools usable in autonomous multi-step agent runs.</li>
            </ul>

            <h3>Security &amp; privacy</h3>
            <ul>
                <li>MCP servers run as local child processes. The UI clearly notes they may access external services.</li>
                <li>Secrets (tokens, passwords, API keys, Authorization headers) are redacted from all logs and trace output before display or storage.</li>
                <li>Broken or crashed MCP servers are isolated — the plugin marks them FAILED and continues normally without affecting any other feature.</li>
            </ul>

            <h2>1.3.0 — Agent Tools, Git Awareness &amp; Advanced Intelligence</h2>

            <p>Expanded the agent tool set significantly and added deep project intelligence features including git context, PSI code insight, codebase rules, model benchmarking, and KMP native diagnostics.</p>

            <h3>New agent tools</h3>
            <ul>
                <li><b>Git tools</b>: <code>git_diff</code>, <code>git_status</code>, <code>git_log</code>, <code>git_blame</code>, <code>git_changed_files</code> — full read-only git context for the agent.</li>
                <li><b>Project index tools</b>: <code>index_search_files</code>, <code>index_search_symbols</code>, <code>index_list_recent</code> — fast symbol and file search powered by the persistent project index.</li>
                <li><b>PSI tools</b>: <code>psi_find_usages</code>, <code>psi_get_type</code>, <code>psi_get_declarations</code> — semantic code insight via IntelliJ's PSI layer.</li>
                <li><b>Rules tools</b>: <code>rules_get</code>, <code>rules_set</code>, <code>rules_clear</code> — read and write persistent codebase rules that the agent follows in every session.</li>
                <li><b>Run config tools</b>: <code>run_config_list</code>, <code>run_config_execute</code> — list and trigger IDE run configurations from inside the agent.</li>
                <li><b>KMP native tools</b>: <code>kmp_diagnose</code>, <code>kmp_fix_plan</code> — detect and plan fixes for Kotlin Multiplatform native compilation issues.</li>
            </ul>

            <h3>Aggressive mode (Pro)</h3>
            <ul>
                <li>New <code>AGGRESSIVE</code> execution mode instructs the agent to apply changes decisively without asking for confirmation on each step.</li>
                <li>Configurable fallback policy: <code>ABORT</code>, <code>RETRY</code>, or <code>ESCALATE</code> when a step fails.</li>
            </ul>

            <h3>Model intelligence</h3>
            <ul>
                <li><b>ModelCapabilityService</b> — detects whether the loaded model supports tool use, long context, code tasks, and instruction following.</li>
                <li><b>ModelBenchmarkService</b> — runs a quick benchmark on the active model and records tokens/second for display in settings.</li>
                <li><b>SmartSearchService</b> — fuzzy file and symbol search with ranking by recency, relevance, and file type.</li>
            </ul>

            <h3>Snapshots &amp; context recovery</h3>
            <ul>
                <li><b>ProjectSnapshotService</b> — captures a full snapshot of open files, recent edits, and context state before an agent run.</li>
                <li><b>ContextRecoveryService</b> — detects mid-session context drift and offers recovery options to restore the agent's context.</li>
            </ul>

            <h2>1.2.0 — Multi-task Agent &amp; Session Management</h2>

            <p>Foundation release that established the core agent workflow, parallel task execution, multi-session chat, and the initial tool ecosystem.</p>

            <h3>Agent &amp; tools</h3>
            <ul>
                <li>Tool-driven agent loop with two LLM passes (plan + execute), safe diff preview, and build verification after each apply.</li>
                <li>Initial built-in tool set: <code>read_file</code>, <code>write_file</code>, <code>list_files</code>, <code>search_code</code>, <code>run_terminal</code>, <code>get_diagnostics</code>, and more.</li>
                <li>Agent trace panel — shows every tool call, argument, result, and duration in the "What I tried" sidebar.</li>
            </ul>

            <h3>Multi-task agent (Pro)</h3>
            <ul>
                <li>Run multiple agent tasks in parallel with a live task queue panel.</li>
                <li>Each task runs on a pooled thread; the UI stays responsive throughout.</li>
            </ul>

            <h3>Multi-session chat</h3>
            <ul>
                <li>Persistent chat session store — conversations survive IDE restarts.</li>
                <li>Chat session list panel — switch between multiple simultaneous conversations.</li>
                <li>Parallel chat sessions in Ask mode.</li>
            </ul>

            <h3>Context actions</h3>
            <ul>
                <li>Right-click editor actions: <b>Ask About Selection</b>, <b>Explain This File</b>, <b>Refactor Selection</b>, <b>Fix Selected Code</b>, <b>Generate Tests</b>, <b>Add Documentation</b>, <b>Edit with Prompt</b>, <b>Explain Failing Build</b>, <b>Explain Current Git Changes</b>.</li>
            </ul>
        """.trimIndent()
    }
    buildSearchableOptions = false
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// ── Benchmark task ───────────────────────────────────────────────────────────
// ./gradlew runLlamatikBenchmark                             (skips — no model)
// ./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted   (scripted end-to-end smoke test)
// ./gradlew runLlamatikBenchmark -PbenchmarkModel=/path/to/model.gguf
// ./gradlew runLlamatikBenchmark -PbenchmarkTask=kotlin-simple-fix-001
// ./gradlew runLlamatikBenchmark -PbenchmarkDir=benchmarks/tasks
val runLlamatikBenchmark by tasks.registering(Test::class) {
    description = "Runs the Llamatik Code IDE benchmark suite and writes reports to build/llamatik-benchmark/."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    include("**/LlamatikBenchmarkTest*")

    val taskFilter  = providers.gradleProperty("benchmarkTask").orNull
    val taskDir     = providers.gradleProperty("benchmarkDir").orNull ?: "benchmarks/tasks"
    val modelPath   = providers.gradleProperty("benchmarkModel").orNull

    if (taskFilter != null) systemProperty("benchmarkTask", taskFilter)
    if (modelPath  != null) systemProperty("benchmarkModel", modelPath)
    systemProperty("benchmarkDir", layout.projectDirectory.dir(taskDir).asFile.absolutePath)

    reports {
        junitXml.outputLocation.set(layout.buildDirectory.dir("llamatik-benchmark/junit"))
        html.outputLocation.set(layout.buildDirectory.dir("llamatik-benchmark/html"))
    }
}

// ── Debug config generation ───────────────────────────────────────────────────
// Pass -PforceLicensed=true on the Gradle command line (or in a run config) to
// enable Pro features locally. The generated file must have FORCE_LICENSED=false
// before building a release JAR.
val forceLicensed = providers.gradleProperty("forceLicensed").orNull == "true"
val generateDebugConfig by tasks.registering {
    val outFile = layout.projectDirectory.file("src/main/kotlin/com/llamatik/teammate/licensing/DebugConfig.kt")
    val licensed = forceLicensed
    inputs.property("forceLicensed", licensed)
    outputs.file(outFile)
    doLast {
        outFile.asFile.writeText("""package com.llamatik.teammate.licensing

internal object DebugConfig {
    const val FORCE_LICENSED: Boolean = $licensed
}
""")
    }
}
tasks.named("compileKotlin") { dependsOn(generateDebugConfig) }

// ── ProGuard obfuscation ──────────────────────────────────────────────────────

val proguardJar = layout.buildDirectory.file("libs/${rootProject.name}-${version}-obfuscated.jar")

// Stable symbol mapping kept across releases.  Committing this file means that
// obfuscated names stay consistent between versions, so stack traces from older
// releases can still be decoded with the matching mapping file.
// See: https://plugins.jetbrains.com/docs/marketplace/obfuscate-the-plugin.html
val proguardMappingFile = rootProject.file("proguard-mapping.txt")

val obfuscate by tasks.registering(ProGuardTask::class) {
    dependsOn(tasks.named("composedJar"))

    // Input: the assembled plugin JAR
    injars(tasks.named("composedJar").flatMap { task ->
        (task as org.gradle.api.tasks.bundling.AbstractArchiveTask).archiveFile
    })

    // Output: obfuscated JAR
    outjars(proguardJar)

    // Library JARs from runtime classpath (IntelliJ platform + dependencies)
    libraryjars(provider {
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.file }
            .filter { it.extension == "jar" }
    })

    // JDK libs: jrt-fs.jar plus the java.desktop jmod (contains javax.swing, java.awt)
    // needed so ProGuard can resolve method signatures in keepclassmembers rules.
    libraryjars(provider {
        fileTree("${System.getProperty("java.home")}/lib") { include("*.jar") }.files
    })

    configuration(file("proguard-rules.pro"))
    allowaccessmodification()

    // Write the mapping file so obfuscated names can be decoded in crash reports.
    // On subsequent builds, re-apply the existing mapping to keep names stable
    // across releases (JetBrains Marketplace recommendation).
    printmapping(proguardMappingFile)
    if (proguardMappingFile.exists()) {
        applymapping(proguardMappingFile)
    }

    verbose()
}

// Recompute StackMapTable attributes that ProGuard's -dontpreverify strips.
// Java 21 class files (major version 65) require StackMapTable for any method
// containing branch instructions; without it MethodHandles.Lookup.findConstructor
// throws a VerifyError that IntelliJ's findConstructorOrNull silently swallows,
// producing "Cannot find suitable constructor".
val recomputedJar = layout.buildDirectory.file("libs/${rootProject.name}-${version}-frames.jar")

val recomputeFrames by tasks.registering {
    dependsOn(obfuscate)
    inputs.file(proguardJar)
    outputs.file(recomputedJar)
    // The anonymous ClassWriter subclass is compiled as a Kotlin script inner class,
    // which holds an implicit reference to the Gradle script object.  That reference
    // cannot be serialised by the configuration cache, so we opt this task out.
    notCompatibleWithConfigurationCache("Uses a script-inner ClassWriter that captures Gradle script state")

    doLast {
        val inFile  = proguardJar.get().asFile
        val outFile = recomputedJar.get().asFile
        if (!inFile.exists()) return@doLast

        // Pre-load all class bytes from the JAR so we can resolve superclass names
        // without attempting to load IntelliJ/JDK classes from the Gradle classloader.
        val allClassBytes = mutableMapOf<String, ByteArray>()
        ZipFile(inFile).use { zf ->
            zf.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .forEach { e -> allClassBytes[e.name.removeSuffix(".class")] = zf.getInputStream(e).readBytes() }
        }

        ZipFile(inFile).use { zin ->
            ZipOutputStream(outFile.outputStream().buffered()).use { zout ->
                zin.entries().asSequence().forEach { entry ->
                    zout.putNextEntry(ZipEntry(entry.name))
                    val bytes = zin.getInputStream(entry).readBytes()
                    if (entry.name.endsWith(".class")) {
                        try {
                            val cr = ClassReader(bytes)
                            // SafeClassWriter overrides getCommonSuperClass to avoid
                            // loading platform classes that are absent from the Gradle classloader.
                            val cw = object : ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                                override fun getCommonSuperClass(type1: String, type2: String): String {
                                    // Walk up the superclass chain for type1 until we find an
                                    // ancestor that is also an ancestor of type2.  For types we
                                    // don't have bytes for (platform / JDK classes) fall back to
                                    // "java/lang/Object", which is always safe.
                                    fun ancestors(name: String): List<String> {
                                        val result = mutableListOf<String>()
                                        var current: String? = name
                                        while (current != null && current != "java/lang/Object") {
                                            result += current
                                            val b = allClassBytes[current] ?: break
                                            current = try { ClassReader(b).superName } catch (_: Exception) { break }
                                        }
                                        result += "java/lang/Object"
                                        return result
                                    }
                                    val ancestors2 = ancestors(type2).toSet()
                                    return ancestors(type1).firstOrNull { it in ancestors2 } ?: "java/lang/Object"
                                }
                            }
                            // ClassReader.SKIP_FRAMES (= 4): discard existing (absent) frame info,
                            // let ClassWriter.COMPUTE_FRAMES regenerate StackMapTable from scratch.
                            cr.accept(cw, ClassReader.SKIP_FRAMES)
                            zout.write(cw.toByteArray())
                        } catch (e: Exception) {
                            logger.warn("recomputeFrames: skipped ${entry.name}: ${e.message}")
                            zout.write(bytes)
                        }
                    } else {
                        zout.write(bytes)
                    }
                    zout.closeEntry()
                }
            }
        }
        logger.lifecycle("StackMapTable frames recomputed → ${outFile.name}")
    }
}

// Obfuscation only runs for buildPlugin (distribution), NOT for runIde (development).
// After Gradle assembles the zip, we rewrite it in-place: replace the unobfuscated
// composedJar inside the archive with the ProGuard output.  This avoids fighting
// Gradle's build cache (mutating composedJar's declared output breaks cache correctness).
// Resolve composedJar archive name at configuration time (outside doLast) so no
// Gradle script object reference is captured, satisfying the configuration cache.
val composedJarArchiveName = tasks.named("composedJar", org.gradle.api.tasks.bundling.AbstractArchiveTask::class)
    .flatMap { it.archiveFileName }

tasks.named("buildPlugin") {
    dependsOn(recomputeFrames)
    // Wire the composedJar archive name as an input so it is recorded in the cache entry.
    val composedJarNameProvider = composedJarArchiveName
    val obfuscatedJarProvider = recomputedJar

    doLast {
        val obfuscatedFile = obfuscatedJarProvider.get().asFile
        if (!obfuscatedFile.exists()) return@doLast

        val zipTask = this as org.gradle.api.tasks.bundling.Zip
        val zipFile = zipTask.archiveFile.get().asFile
        if (!zipFile.exists()) return@doLast

        val composedJarName = composedJarNameProvider.get()
        val entryName = "LlamatikCode/lib/$composedJarName"

        // Rewrite the zip in-place: replace the unobfuscated composedJar entry with the
        // ProGuard output. Uses python3 (always available on macOS) to avoid JDK zip API
        // classloader issues inside the Gradle Kotlin DSL doLast action.
        val pyScript = """
import zipfile, os, sys
src = sys.argv[1]; entry = sys.argv[2]; replacement = sys.argv[3]
tmp = src + '.obftmp'
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == entry:
            zout.write(replacement, item.filename)
        else:
            zout.writestr(item, zin.read(item.filename))
os.replace(tmp, src)
print('Replaced', entry, 'with obfuscated jar')
""".trimIndent()
        val proc = ProcessBuilder("python3", "-c", pyScript, zipFile.absolutePath, entryName, obfuscatedFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) error("python3 zip rewrite failed (exit $exitCode):\n$output")
        logger.lifecycle("Obfuscated jar injected into ${zipFile.name}")
    }
}
