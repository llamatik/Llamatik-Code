package com.llamatik.teammate.external.search

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

@Service(Service.Level.APP)
class ExternalSearchService {

    fun search(query: SearchQuery, isPro: Boolean): List<SearchResult> {
        resetCountIfNewDay()
        if (!canSearch(isPro)) {
            val limit = dailyLimit(isPro)
            return listOf(
                SearchResult(
                    title = "Daily limit reached",
                    url = "",
                    snippet = "Daily search limit reached ($limit/$limit). Resets tomorrow.",
                    source = "system",
                )
            )
        }
        val blockedDomains = blockedDomains()
        val filteredQuery = query.copy(
            blockedDomains = (query.blockedDomains + blockedDomains).distinct()
        )
        return try {
            val results = fetchDuckDuckGo(filteredQuery, isPro)
            incrementCount()
            results
        } catch (e: Exception) {
            listOf(
                SearchResult(
                    title = "Search failed",
                    url = "",
                    snippet = "Search failed: ${e.message ?: "Unknown error"}",
                    source = "system",
                )
            )
        }
    }

    fun canSearch(isPro: Boolean): Boolean {
        resetCountIfNewDay()
        return searchUsedToday() < dailyLimit(isPro)
    }

    fun searchUsedToday(): Int = service<LlamatikAppSettings>().data().externalSearchUsedToday

    fun dailyLimit(isPro: Boolean): Int = ExternalKnowledgeLimits.dailySearchLimit(isPro)

    fun resetCountIfNewDay() {
        val state = service<LlamatikAppSettings>().data()
        val today = LocalDate.now().toString()
        if (state.externalSearchCountDate != today) {
            state.externalSearchUsedToday = 0
            state.externalSearchCountDate = today
        }
    }

    fun buildSearchUrl(query: SearchQuery): String {
        val encoded = URLEncoder.encode(query.query, "UTF-8")
        return "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
    }

    private fun blockedDomains(): List<String> {
        val raw = service<LlamatikAppSettings>().data().externalResearchBlockedDomains
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun incrementCount() {
        val state = service<LlamatikAppSettings>().data()
        state.externalSearchUsedToday = state.externalSearchUsedToday + 1
    }

    private fun fetchDuckDuckGo(query: SearchQuery, isPro: Boolean): List<SearchResult> {
        val url = buildSearchUrl(query)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; LlamatikCode/1.0)")
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            return listOf(
                SearchResult(
                    title = "Search error",
                    url = "",
                    snippet = "Search API returned HTTP $responseCode",
                    source = "system",
                )
            )
        }

        val body = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        return parseResults(body, query, isPro)
    }

    private fun parseResults(json: String, query: SearchQuery, isPro: Boolean): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val maxResults = ExternalKnowledgeLimits.maxResultCount(isPro).coerceAtMost(query.maxResults)

        // Extract RelatedTopics array manually
        val relatedTopicsStart = json.indexOf("\"RelatedTopics\"")
        if (relatedTopicsStart < 0) return results

        val arrayStart = json.indexOf('[', relatedTopicsStart)
        if (arrayStart < 0) return results

        // Find all Text and FirstURL pairs within RelatedTopics
        var pos = arrayStart
        val depth = intArrayOf(0)
        var inTopic = false
        var topicStart = -1

        val topicRegex = Regex(""""Text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val urlRegex = Regex(""""FirstURL"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        // Simple approach: find all { } objects at top level within the array
        val topicStrings = mutableListOf<String>()
        var i = arrayStart
        var bracketDepth = 0
        var inString = false
        var escape = false
        var objectStart = -1

        while (i < json.length && results.size < maxResults) {
            val c = json[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> bracketDepth++
                !inString && c == ']' -> {
                    bracketDepth--
                    if (bracketDepth <= 0) break
                }
                !inString && c == '{' -> {
                    if (bracketDepth == 1) objectStart = i
                    bracketDepth++
                }
                !inString && c == '}' -> {
                    bracketDepth--
                    if (bracketDepth == 1 && objectStart >= 0) {
                        topicStrings.add(json.substring(objectStart, i + 1))
                        objectStart = -1
                    }
                }
            }
            i++
        }

        for (topicJson in topicStrings) {
            if (results.size >= maxResults) break
            val textMatch = topicRegex.find(topicJson) ?: continue
            val urlMatch = urlRegex.find(topicJson) ?: continue

            val text = textMatch.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            val firstUrl = urlMatch.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")

            if (text.isBlank() || firstUrl.isBlank()) continue

            // Check blocked domains
            val urlHost = runCatching { URL(firstUrl).host }.getOrElse { "" }
            if (query.blockedDomains.any { urlHost.contains(it) }) continue

            // If domain filters specified, check them
            if (query.domains.isNotEmpty() && !query.domains.any { urlHost.contains(it) }) continue

            val title = text.substringBefore(" - ").take(120).ifBlank { text.take(80) }
            val snippet = text.take(300)

            results.add(
                SearchResult(
                    title = title,
                    url = firstUrl,
                    snippet = snippet,
                    source = urlHost,
                )
            )
        }

        return results
    }

    companion object {
        @JvmStatic
        fun getInstance(): ExternalSearchService = service()
    }
}
