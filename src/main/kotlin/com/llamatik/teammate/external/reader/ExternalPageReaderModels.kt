package com.llamatik.teammate.external.reader

data class PageReadRequest(
    val url: String,
    val maxChars: Int = 8000,
    val extractionMode: ExtractionMode = ExtractionMode.MAIN_TEXT,
)

enum class ExtractionMode { MAIN_TEXT, CODE_FOCUSED, HEADINGS_ONLY }

data class PageContent(
    val url: String,
    val title: String = "",
    val text: String = "",
    val headings: List<String> = emptyList(),
    val codeBlocks: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val publishedDate: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
)
