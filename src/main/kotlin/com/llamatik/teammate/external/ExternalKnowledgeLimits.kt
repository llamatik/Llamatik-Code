package com.llamatik.teammate.external

object ExternalKnowledgeLimits {
    const val FREE_DAILY_SEARCHES = 15
    const val PRO_DAILY_SEARCHES = 50
    const val FREE_PAGES_PER_TASK = 5
    const val PRO_PAGES_PER_TASK = 10
    const val FREE_DOCS_SOURCES = 3
    const val PRO_DOCS_SOURCES = 10
    const val FREE_MAX_RESULT_COUNT = 8
    const val PRO_MAX_RESULT_COUNT = 10

    fun dailySearchLimit(isPro: Boolean): Int = if (isPro) PRO_DAILY_SEARCHES else FREE_DAILY_SEARCHES
    fun pagesPerTask(isPro: Boolean): Int = if (isPro) PRO_PAGES_PER_TASK else FREE_PAGES_PER_TASK
    fun docsSourceLimit(isPro: Boolean): Int = if (isPro) PRO_DOCS_SOURCES else FREE_DOCS_SOURCES
    fun maxResultCount(isPro: Boolean): Int = if (isPro) PRO_MAX_RESULT_COUNT else FREE_MAX_RESULT_COUNT
}
