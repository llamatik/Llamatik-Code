package com.llamatik.teammate.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.agent.core.agent.PromptSources
import com.llamatik.teammate.agent.AgentExecutionMode
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.memory.ProjectMemoryService
import com.llamatik.teammate.rules.CodebaseRulesService
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.skills.SkillService

/** Prompt inputs from the open IDE project: its name, user rules, skills and agent memory. */
class IdePromptSources(private val project: Project) : PromptSources {
    private val context = ProjectContextService(project)

    override fun projectName(): String = context.projectName()
    override fun pinnedInstructions(): String = context.pinnedInstructions()

    override fun agentIdentity(): String =
        "You are Llamatik Code, an autonomous local development agent for IntelliJ. You plan, patch, review, and verify code locally — all source code stays on the user's machine."

    override fun chatIdentity(): String = "You are Llamatik Code, a local AI coding assistant inside IntelliJ."

    override fun skillsPromptAddition(): String = service<SkillService>().buildSkillsPromptAddition()
    override fun rulesPromptBlock(): String = project.service<CodebaseRulesService>().buildPromptBlock()
    override fun memoryPromptBlock(): String = project.service<ProjectMemoryService>().buildPromptBlock()

    override fun aggressiveMode(): Boolean =
        service<LlamatikAppSettings>().data().agentExecutionMode == AgentExecutionMode.AGGRESSIVE.name
}
