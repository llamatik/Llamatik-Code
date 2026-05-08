package com.llamatik.teammate.external.research

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings

@Service(Service.Level.APP)
class ResearchSourceRouter {

    fun routeSources(query: String, preferredSources: List<String>): List<String> {
        val lower = query.lowercase()
        val routedDomains = mutableListOf<String>()

        when {
            lower.containsAny("kotlin", "coroutine", "flow", "suspend") ->
                routedDomains.addAll(listOf("kotlinlang.org", "blog.jetbrains.com"))
            lower.containsAny("android", "jetpack", "compose") ->
                routedDomains.add("developer.android.com")
            lower.containsAny("gradle") ->
                routedDomains.add("docs.gradle.org")
            lower.containsAny("cmake") ->
                routedDomains.add("cmake.org")
            lower.containsAny("swift", "ios", "xcode") ->
                routedDomains.add("developer.apple.com")
            lower.containsAny("kmp", "multiplatform") ->
                routedDomains.addAll(listOf("kotlinlang.org", "blog.jetbrains.com"))
            lower.containsAny("github issue", "bug", "regression") ->
                routedDomains.add("github.com")
        }

        // Prepend user preferred sources from settings
        val settingsSources = userPreferredSources()
        val allSources = (preferredSources + settingsSources + routedDomains).distinct()
        return allSources
    }

    fun blockedDomains(): List<String> {
        val raw = service<LlamatikAppSettings>().data().externalResearchBlockedDomains
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun userPreferredSources(): List<String> {
        val raw = service<LlamatikAppSettings>().data().externalResearchPreferredSources
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }

    companion object {
        @JvmStatic
        fun getInstance(): ResearchSourceRouter = service()
    }
}
