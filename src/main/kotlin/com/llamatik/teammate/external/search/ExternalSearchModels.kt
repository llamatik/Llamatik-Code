package com.llamatik.teammate.external.search

data class SearchQuery(
    val query: String,
    val domains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    val maxResults: Int = 5,
    val freshness: String = "",
    val intent: SearchIntent = SearchIntent.GENERAL,
)

enum class SearchIntent { GENERAL, OFFICIAL_DOCS, GITHUB_ISSUES, LIBRARY_DOCS, ERROR_LOOKUP }

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
    val publishedDate: String = "",
    val relevanceScore: Double = 0.5,
)
