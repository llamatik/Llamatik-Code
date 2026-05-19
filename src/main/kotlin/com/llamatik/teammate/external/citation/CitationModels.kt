package com.llamatik.teammate.external.citation

enum class SourceConfidence { HIGH, MEDIUM, LOW }

enum class SourceType {
    OFFICIAL_DOCS, RELEASE_NOTES, GITHUB_MAINTAINER, GITHUB_ISSUES, STACKOVERFLOW, COMMUNITY, UNKNOWN
}

data class Citation(
    val title: String,
    val url: String,
    val sourceType: SourceType,
    val claimIds: List<String> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis(),
    val publishedDate: String = "",
) {
    val confidence: SourceConfidence
        get() = when (sourceType) {
            SourceType.OFFICIAL_DOCS, SourceType.RELEASE_NOTES -> SourceConfidence.HIGH
            SourceType.GITHUB_MAINTAINER -> SourceConfidence.MEDIUM
            SourceType.GITHUB_ISSUES, SourceType.STACKOVERFLOW -> SourceConfidence.MEDIUM
            SourceType.COMMUNITY, SourceType.UNKNOWN -> SourceConfidence.LOW
        }

    val confidenceLabel: String
        get() = when (confidence) {
            SourceConfidence.HIGH -> "Official docs"
            SourceConfidence.MEDIUM -> "Community / maintainer"
            SourceConfidence.LOW -> "Community (lower confidence)"
        }
}

fun classifySourceType(url: String): SourceType {
    val lower = url.lowercase()
    return when {
        lower.contains("github.com") && (lower.contains("/releases/tag") || lower.contains("changelog")) -> SourceType.RELEASE_NOTES
        lower.contains("github.com") && (lower.contains("/issues") || lower.contains("/pull")) -> SourceType.GITHUB_ISSUES
        lower.contains("github.com") -> SourceType.GITHUB_MAINTAINER
        lower.contains("stackoverflow.com") -> SourceType.STACKOVERFLOW
        lower.contains("kotlinlang.org") -> SourceType.OFFICIAL_DOCS
        lower.contains("developer.android.com") -> SourceType.OFFICIAL_DOCS
        lower.contains("docs.gradle.org") -> SourceType.OFFICIAL_DOCS
        lower.contains("developer.apple.com") -> SourceType.OFFICIAL_DOCS
        lower.contains("cmake.org/cmake/help") -> SourceType.OFFICIAL_DOCS
        lower.contains("docs.spring.io") -> SourceType.OFFICIAL_DOCS
        lower.contains("docs.oracle.com") -> SourceType.OFFICIAL_DOCS
        lower.contains("blog.jetbrains.com") -> SourceType.RELEASE_NOTES
        lower.contains("/releases") || lower.contains("/changelog") || lower.contains("/release-notes") -> SourceType.RELEASE_NOTES
        lower.contains("reddit.com") || lower.contains("medium.com") || lower.contains("dev.to") -> SourceType.COMMUNITY
        else -> SourceType.UNKNOWN
    }
}
