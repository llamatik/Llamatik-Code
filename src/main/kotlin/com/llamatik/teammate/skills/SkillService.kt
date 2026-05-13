package com.llamatik.teammate.skills

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Service(Service.Level.APP)
class SkillService {

    private val settings = service<LlamatikAppSettings>()

    fun catalog(): List<SkillDescriptor> = BuiltinSkillCatalog.all

    /** All skills: builtin catalog + user custom skills loaded from disk. */
    fun allSkills(): List<SkillDescriptor> = BuiltinSkillCatalog.all + loadCustomSkills()

    fun skillStatus(id: String): SkillStatus = when {
        isActive(id) -> SkillStatus.ACTIVE
        isDownloaded(id) -> SkillStatus.DOWNLOADED
        else -> SkillStatus.NOT_DOWNLOADED
    }

    fun isDownloaded(id: String): Boolean = id in settings.data().downloadedSkillIds

    fun isActive(id: String): Boolean = id in settings.data().activeSkillIds

    fun downloadedSkills(): List<SkillDescriptor> =
        settings.data().downloadedSkillIds.mapNotNull { findById(it) }

    fun activeSkills(): List<SkillDescriptor> =
        settings.data().activeSkillIds.mapNotNull { findById(it) }

    private fun findById(id: String): SkillDescriptor? =
        BuiltinSkillCatalog.findById(id) ?: loadCustomSkills().find { it.id == id }

    /** Marks a skill as downloaded (instantly available — content is bundled). */
    fun download(id: String) {
        if (!isDownloaded(id)) settings.data().downloadedSkillIds.add(id)
    }

    /** Removes the skill from both downloaded and active lists. For custom skills also deletes the file. */
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
     * Saves pasted markdown text as a custom skill file under ~/.llamatik/skills/,
     * registers its path, marks it downloaded and returns the descriptor.
     */
    fun addCustomSkillFromText(name: String, markdownText: String): SkillDescriptor {
        val dir = settings.skillsDir()
        Files.createDirectories(dir)
        val id = customIdFrom(name)
        val fileName = "$id.md"
        val path = dir.resolve(fileName)
        Files.writeString(path, markdownText)
        registerCustomPath(path)
        val descriptor = parseCustomSkill(path)
        download(descriptor.id)
        return descriptor
    }

    /**
     * Downloads a remote .md file into ~/.llamatik/skills/, registers it,
     * marks it downloaded and returns the descriptor.
     */
    fun addCustomSkillFromUrl(url: String): SkillDescriptor {
        val dir = settings.skillsDir()
        Files.createDirectories(dir)
        val fileName = url.substringAfterLast('/').substringBefore('?')
            .takeIf { it.endsWith(".md") } ?: "custom-skill-${System.currentTimeMillis()}.md"
        val dest = dir.resolve(fileName)
        val connection = URI(url).toURL().openConnection()
        connection.setRequestProperty("User-Agent", "LlamatikCode/1.0")
        connection.connect()
        connection.getInputStream().use { input ->
            Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
        }
        registerCustomPath(dest)
        val descriptor = parseCustomSkill(dest)
        download(descriptor.id)
        return descriptor
    }

    /** Deletes a custom skill: removes from all lists and deletes the file from disk. */
    fun deleteCustomSkill(id: String) {
        val skill = loadCustomSkills().find { it.id == id } ?: return
        delete(id)
        val path = Path.of(skill.filePath)
        settings.data().customSkillPaths.remove(path.toAbsolutePath().toString())
        runCatching { Files.deleteIfExists(path) }
    }

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

    // ── Custom skill helpers ──────────────────────────────────────────────────

    private fun registerCustomPath(path: Path) {
        val abs = path.toAbsolutePath().toString()
        if (abs !in settings.data().customSkillPaths) {
            settings.data().customSkillPaths.add(abs)
        }
    }

    /** Loads all registered custom skill files from disk, pruning missing ones. */
    fun loadCustomSkills(): List<SkillDescriptor> {
        val paths = settings.data().customSkillPaths.toList()
        val valid = mutableListOf<SkillDescriptor>()
        val stale = mutableListOf<String>()
        for (pathStr in paths) {
            val path = Path.of(pathStr)
            if (Files.exists(path)) {
                valid.add(parseCustomSkill(path))
            } else {
                stale.add(pathStr)
            }
        }
        settings.data().customSkillPaths.removeAll(stale)
        return valid
    }

    /**
     * Parses a markdown file into a SkillDescriptor.
     * The first H1 (`# Title`) becomes the display name; the first paragraph becomes the description;
     * the rest of the file becomes the prompt.
     */
    private fun parseCustomSkill(path: Path): SkillDescriptor {
        val text = runCatching { Files.readString(path) }.getOrElse { "" }
        val lines = text.lines()
        val h1 = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        val displayName = h1 ?: path.fileName.toString().removeSuffix(".md")
            .replace('-', ' ').replace('_', ' ')
            .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val bodyLines = if (h1 != null) lines.drop(lines.indexOf(lines.first { it.startsWith("# ") }) + 1) else lines
        val descLine = bodyLines.firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val description = descLine.removePrefix("> ").take(120)
        val id = "custom-${path.fileName.toString().removeSuffix(".md")}"
        return SkillDescriptor(
            id = id,
            displayName = displayName,
            description = description,
            category = "Custom",
            prompt = text,
            author = "Custom",
            isCustom = true,
            filePath = path.toAbsolutePath().toString(),
        )
    }

    private fun customIdFrom(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .take(48) + "-${System.currentTimeMillis() % 100000}"
}
