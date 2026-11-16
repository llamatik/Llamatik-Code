package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.AgentEnvironment

/**
 * [PromptSources] for hosts without an IDE: the project name is the directory name and the project
 * rules come from an instructions file at the project root (`LLAMATIK.md`, else `AGENTS.md`).
 */
class FilePromptSources(
    private val env: AgentEnvironment,
    private val hostName: String,
    private val aggressive: Boolean = false,
) : PromptSources {
    override fun projectName(): String = env.files.rootPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    override fun pinnedInstructions(): String = ""

    override fun agentIdentity(): String =
        "You are Llamatik Code, an autonomous local development agent for the $hostName. " +
            "You plan, patch, review, and verify code locally — all source code stays on the user's machine."

    override fun chatIdentity(): String = "You are Llamatik Code, a local AI coding assistant for the $hostName."

    override fun skillsPromptAddition(): String = ""

    override fun rulesPromptBlock(): String {
        for (name in INSTRUCTION_FILES) {
            val text = runCatching { env.files.read(name) }.getOrNull()?.trim()
            if (!text.isNullOrBlank()) return "Project instructions ($name):\n${text.take(MAX_INSTRUCTION_CHARS)}\n"
        }
        return ""
    }

    override fun memoryPromptBlock(): String = ""
    override fun aggressiveMode(): Boolean = aggressive

    companion object {
        val INSTRUCTION_FILES = listOf("LLAMATIK.md", "AGENTS.md")
        const val MAX_INSTRUCTION_CHARS = 2_000
    }
}
