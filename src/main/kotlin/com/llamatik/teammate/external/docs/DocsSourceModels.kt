package com.llamatik.teammate.external.docs

enum class DocsSourceType { URL, WEBSITE, GITHUB, LOCAL_FOLDER }
enum class DocsRefreshMode { MANUAL, AUTO }

data class DocsSource(
    val id: String,
    val name: String,
    val type: DocsSourceType,
    val location: String,
    val enabled: Boolean = true,
    val refreshMode: DocsRefreshMode = DocsRefreshMode.MANUAL,
    val lastIndexedAt: Long = 0L,
)

data class DocsIndexEntry(
    val sourceId: String,
    val url: String,
    val title: String,
    val content: String,
    val headings: List<String>,
    val indexedAt: Long = System.currentTimeMillis(),
)
