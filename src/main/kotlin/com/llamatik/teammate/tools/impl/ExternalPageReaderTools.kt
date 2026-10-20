package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import com.llamatik.teammate.external.reader.ExternalPageReaderService
import com.llamatik.teammate.external.reader.PageContent
import com.llamatik.teammate.external.reader.PageReadRequest
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.ui.Strings

class ExternalReadPageTool(private val project: Project) : LlamatikTool {
    override val name = "external_read_page"
    override val description = "Fetch and extract text content from a URL. Arguments: url, maxChars (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isExternalEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val url = call.arguments["url"] ?: return ToolResult(name, false, "Missing argument: url")
        val maxChars = call.arguments["maxChars"]?.toIntOrNull() ?: 4000
        val readerSvc = service<ExternalPageReaderService>()
        val page = readerSvc.readPage(PageReadRequest(url = url, maxChars = maxChars.coerceIn(500, 16000)))
        if (page.error != null) return ToolResult(name, false, "Failed to read page: ${page.error}")
        return ToolResult(name, true, formatPageContent(page))
    }
}

class ExternalReadPagesTool(private val project: Project) : LlamatikTool {
    override val name = "external_read_pages"
    override val description = "Fetch multiple URLs. Arguments: urls (comma-separated), maxChars (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isExternalEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val urlsArg = call.arguments["urls"] ?: return ToolResult(name, false, "Missing argument: urls")
        val maxChars = call.arguments["maxChars"]?.toIntOrNull() ?: 4000
        val urls = urlsArg.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(ExternalKnowledgeLimits.pagesPerTask())
        if (urls.isEmpty()) return ToolResult(name, false, "No valid URLs provided")

        val readerSvc = service<ExternalPageReaderService>()
        val requests = urls.map { PageReadRequest(url = it, maxChars = maxChars.coerceIn(500, 16000)) }
        val pages = readerSvc.readPages(requests)

        val content = buildString {
            pages.forEachIndexed { i, page ->
                if (i > 0) appendLine("\n---\n")
                appendLine("Page ${i + 1}: ${page.url}")
                if (page.error != null) {
                    appendLine("Error: ${page.error}")
                } else {
                    append(formatPageContent(page))
                }
            }
        }.trim()

        return ToolResult(name, true, content)
    }
}

private fun isExternalEnabled(): Boolean =
    runCatching { service<LlamatikAppSettings>().data().externalResearchEnabled }.getOrElse { false }

private fun formatPageContent(page: PageContent): String = buildString {
    if (page.title.isNotBlank()) appendLine("Title: ${page.title}")
    if (page.headings.isNotEmpty()) {
        appendLine("Headings: ${page.headings.take(5).joinToString(" | ")}")
    }
    if (page.text.isNotBlank()) {
        appendLine()
        appendLine(page.text.take(4000))
    }
    if (page.codeBlocks.isNotEmpty()) {
        appendLine()
        appendLine("Code blocks (${page.codeBlocks.size}):")
        page.codeBlocks.take(3).forEach { code ->
            appendLine("```")
            appendLine(code.take(500))
            appendLine("```")
        }
    }
}.trim()
