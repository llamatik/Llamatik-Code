package com.llamatik.teammate.skills

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings

@Service(Service.Level.APP)
class SkillService {

    private val settings = service<LlamatikAppSettings>()

    fun catalog(): List<SkillDescriptor> = BuiltinSkillCatalog.all

    fun skillStatus(id: String): SkillStatus = when {
        isActive(id) -> SkillStatus.ACTIVE
        isDownloaded(id) -> SkillStatus.DOWNLOADED
        else -> SkillStatus.NOT_DOWNLOADED
    }

    fun isDownloaded(id: String): Boolean = id in settings.data().downloadedSkillIds

    fun isActive(id: String): Boolean = id in settings.data().activeSkillIds

    fun downloadedSkills(): List<SkillDescriptor> =
        settings.data().downloadedSkillIds.mapNotNull { BuiltinSkillCatalog.findById(it) }

    fun activeSkills(): List<SkillDescriptor> =
        settings.data().activeSkillIds.mapNotNull { BuiltinSkillCatalog.findById(it) }

    /** Marks a skill as downloaded (instantly available — content is bundled). */
    fun download(id: String) {
        if (!isDownloaded(id)) settings.data().downloadedSkillIds.add(id)
    }

    /** Removes the skill from both downloaded and active lists. */
    fun delete(id: String) {
        settings.data().downloadedSkillIds.remove(id)
        settings.data().activeSkillIds.remove(id)
    }

    /** Activates a downloaded skill so it is injected into the agent system prompt. */
    fun activate(id: String) {
        if (isDownloaded(id) && !isActive(id)) settings.data().activeSkillIds.add(id)
    }

    /** Deactivates an active skill without deleting it. */
    fun deactivate(id: String) {
        settings.data().activeSkillIds.remove(id)
    }

    /**
     * Builds the text block appended to the system prompt when skills are active.
     * Returns an empty string when no skills are active.
     */
    fun buildSkillsPromptAddition(): String {
        val active = activeSkills()
        if (active.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("=== Active Coding Skills ===")
            active.forEach { skill ->
                appendLine()
                appendLine("### ${skill.displayName}")
                appendLine(skill.prompt)
            }
            append("=== End Skills ===")
        }
    }
}
