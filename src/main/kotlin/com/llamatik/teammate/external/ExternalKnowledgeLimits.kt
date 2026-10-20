package com.llamatik.teammate.external

object ExternalKnowledgeLimits {
    const val DAILY_SEARCHES = 50
    const val PAGES_PER_TASK = 10
    const val DOCS_SOURCES = 10
    const val MAX_RESULT_COUNT = 10

    fun dailySearchLimit(isPro: Boolean = true): Int = DAILY_SEARCHES
    fun pagesPerTask(isPro: Boolean = true): Int = PAGES_PER_TASK
    fun docsSourceLimit(isPro: Boolean = true): Int = DOCS_SOURCES
    fun maxResultCount(isPro: Boolean = true): Int = MAX_RESULT_COUNT
}
