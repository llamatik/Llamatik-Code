package com.llamatik.teammate.external.research

import com.llamatik.teammate.external.citation.SourceConfidence

data class ResearchTask(
    val question: String,
    val localContextSummary: String = "",
    val intent: ResearchIntent = ResearchIntent.GENERAL,
    val preferredSources: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val isPro: Boolean = false,
)

enum class ResearchIntent { GENERAL, LIBRARY_DOCS, BUG_ERROR, API_BEHAVIOR, MIGRATION, LATEST_VERSION }

data class ResearchPlan(
    val searchQueries: List<String>,
    val expectedSources: List<String>,
    val reason: String,
)

data class ResearchFinding(
    val claim: String,
    val evidenceUrls: List<String>,
    val confidence: SourceConfidence,
    val appliesToProject: Boolean,
    val suggestedAction: String = "",
)

data class ResearchReport(
    val answer: String,
    val findings: List<ResearchFinding>,
    val sources: List<com.llamatik.teammate.external.citation.Citation>,
    val confidence: SourceConfidence,
    val suggestedFixPlan: String = "",
    val limitations: String = "",
)
