package com.llamatik.teammate.external.reader

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.net.HttpURLConnection
import java.net.URL

@Service(Service.Level.APP)
class ExternalPageReaderService {

    fun readPage(request: PageReadRequest): PageContent {
        val blocked = blockedDomains()
        val host = runCatching { URL(request.url).host }.getOrElse { "" }
        if (blocked.any { host.contains(it) }) {
            return PageContent(url = request.url, error = "Domain is blocked: $host")
        }
        return try {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; LlamatikCode/1.0)")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return PageContent(url = request.url, error = "HTTP $responseCode")
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            extractContent(html, request.url, request.maxChars)
        } catch (e: Exception) {
            PageContent(url = request.url, error = e.message ?: "Failed to fetch page")
        }
    }

    fun extractContent(html: String, url: String, maxChars: Int): PageContent {
        return try {
            // Extract title
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.stripHtml()?.trim() ?: ""

            // Extract headings
            val headings = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { it.groupValues[1].stripHtml().trim() }
                .filter { it.isNotBlank() }.take(20).toList()

            // Extract code blocks
            val codeBlocks = Regex("<(?:code|pre)[^>]*>(.*?)</(?:code|pre)>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { it.groupValues[1].stripHtml().trim() }
                .filter { it.isNotBlank() }.take(10).toList()

            // Extract links
            val links = Regex("""href="([^"#][^"]*?)"""")
                .findAll(html).map { it.groupValues[1] }
                .filter { it.startsWith("http") }.distinct().take(30).toList()

            // Remove noisy tags and their content
            val cleaned = html
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL), " ")

            // Strip remaining HTML tags and normalize whitespace
            val text = cleaned.stripHtml()
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
                .take(maxChars)

            PageContent(
                url = url,
                title = title,
                text = text,
                headings = headings,
                codeBlocks = codeBlocks,
                links = links,
            )
        } catch (e: Exception) {
            PageContent(url = url, error = "Extraction failed: ${e.message}")
        }
    }

    fun readPages(requests: List<PageReadRequest>): List<PageContent> {
        return requests.map { request ->
            try {
                readPage(request)
            } catch (e: Exception) {
                PageContent(url = request.url, error = e.message ?: "Failed to read page")
            }
        }
    }

    private fun blockedDomains(): List<String> {
        val raw = service<LlamatikAppSettings>().data().externalResearchBlockedDomains
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun String.stripHtml(): String =
        this.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

    companion object {
        @JvmStatic
        fun getInstance(): ExternalPageReaderService = service()
    }
}
