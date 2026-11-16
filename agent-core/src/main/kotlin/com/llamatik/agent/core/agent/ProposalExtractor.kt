package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.EditProposal

data class ProposalResult(
    val proposals: List<EditProposal>,
    val rejectionReasons: List<String>,
)

/** Turns model output and tool results into [EditProposal]s. Extracted unchanged from AgentOrchestrator. */
class ProposalExtractor(
    private val env: AgentEnvironment,
    private val editToolNames: Set<String>,
) {
    // Matches path and summary in any order, with single or double quotes, arbitrary spacing.
    val editRegex = Regex(
        "<edit_proposal\\b([^>]*)>\\s*<new_content>(?:<!\\[CDATA\\[(.*?)]]>|(.*?))</new_content>\\s*</edit_proposal>",
        RegexOption.DOT_MATCHES_ALL,
    )
    val attrPath    = Regex("""path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val attrSummary = Regex("""summary\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    val editHeaderRegex = Regex(
        "<edit_proposal\\b([^>]*)>",
    )

    fun extractEditProposals(text: String): List<EditProposal> {
        val complete = editRegex.findAll(text).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val path    = attrPath.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val summary = attrSummary.find(attrs)?.groupValues?.get(1).orEmpty()
            // group 2 = CDATA content, group 3 = plain content
            val content = match.groupValues[2].ifEmpty { match.groupValues[3] }
            buildProposal(path, summary, content)
        }.toList()
        if (complete.isNotEmpty()) return complete

        // Fallback: generation was truncated before closing tags — extract whatever content arrived.
        return editHeaderRegex.findAll(text).mapNotNull { headerMatch ->
            val attrs   = headerMatch.groupValues[1]
            val path    = attrPath.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val summary = attrSummary.find(attrs)?.groupValues?.get(1).orEmpty()
            val afterHeader = text.substring(headerMatch.range.last + 1)
            val content = extractNewContent(afterHeader)
            buildProposal(path, summary, content)
        }.toList()
    }

    fun extractNewContent(afterHeader: String): String {
        // Try CDATA first (inside or outside <new_content>)
        val cdataIdx = afterHeader.indexOf(CDATA_OPEN)
        if (cdataIdx >= 0) {
            val raw = afterHeader.substring(cdataIdx + CDATA_OPEN.length)
            val closing = raw.indexOf(CDATA_CLOSE)
            return if (closing >= 0) raw.substring(0, closing).trim() else raw.trim()
        }
        // Plain <new_content>…</new_content> — strip any stray CDATA markers
        val openIdx = afterHeader.indexOf(NEW_CONTENT_OPEN)
        if (openIdx >= 0) {
            val raw = afterHeader.substring(openIdx + NEW_CONTENT_OPEN.length)
            val closeIdx = raw.indexOf(NEW_CONTENT_CLOSE)
            val content = if (closeIdx >= 0) raw.substring(0, closeIdx) else raw
            return content.trim().removePrefix(CDATA_OPEN).removeSuffix(CDATA_CLOSE).trim()
        }
        return ""
    }

    fun buildProposal(path: String, summary: String, newContent: String): EditProposal? {
        val trimmedPath = path.trim()
        if (!trimmedPath.contains('.')) return null
        val oldContent = runCatching { env.files.read(trimmedPath) }.getOrDefault("")
        val cleanContent = newContent.trim()
            .removePrefix(CDATA_OPEN).removeSuffix(CDATA_CLOSE).trim()
        return EditProposal(filePath = trimmedPath, oldContent = oldContent, newContent = cleanContent, summary = summary)
    }


    /**
     * Extracts EditProposals directly from tool result messages.
     * Only hard-rejects structurally broken proposals (corrupt path, hallucinated tool syntax).
     * Stub or low-quality content is surfaced as a proposal so the user can review and decide.
     */
    fun buildProposalsFromToolMessages(toolMessages: List<ChatMessage>): ProposalResult {
        val proposals = mutableListOf<EditProposal>()
        val rejections = mutableListOf<String>()

        for (msg in toolMessages) {
            val toolName = msg.toolName ?: continue
            if (toolName !in editToolNames) continue
            val content = msg.content

            val path = when {
                content.startsWith("Prepared file replacement for ") ->
                    content.removePrefix("Prepared file replacement for ").substringBefore("\n")
                content.startsWith("Prepared replacement for ") ->
                    content.removePrefix("Prepared replacement for ").substringBefore("\n")
                content.startsWith("Prepared new file for ") ->
                    content.removePrefix("Prepared new file for ").substringBefore("\n")
                else -> {
                    // Capture write tool errors (e.g. "Missing content") as rejections so the
                    // caller can reflect them back to the model for a corrective retry.
                    rejections += "$toolName failed: ${content.lines().firstOrNull().orEmpty().take(200)}"
                    continue
                }
            }.trim()

            val newContent = when {
                content.contains("\n---NEW---\n") -> content.substringAfter("\n---NEW---\n").trimKeepingFinalNewline()
                content.startsWith("Prepared new file for ") -> content.substringAfter("\n").trimKeepingFinalNewline()
                else -> continue
            }
            val oldContent = when {
                content.contains("\n---OLD---\n") -> content.substringAfter("\n---OLD---\n").substringBefore("\n---NEW---\n").trimKeepingFinalNewline()
                else -> runCatching { env.files.read(path) }.getOrDefault("")
            }

            when (val v = ProposalValidator.validate(path, newContent)) {
                is ProposalValidator.Result.Invalid -> {
                    rejections += "write to '$path' rejected: ${v.reason}"
                    continue
                }
                is ProposalValidator.Result.Valid -> Unit
            }
            // Tool arguments arrive trimmed, which would strip the file's final newline from every edit
            // ("No newline at end of file" in each diff). Keep the convention of the file being replaced; new
            // files get the usual trailing newline.
            val finalContent = if (!newContent.endsWith("\n") && (oldContent.isEmpty() || oldContent.endsWith("\n"))) newContent + "\n" else newContent
            proposals += EditProposal(filePath = path, oldContent = oldContent, newContent = finalContent, summary = "Changes from $toolName")
        }

        return ProposalResult(proposals, rejections)
    }

    /**
     * Trims surrounding whitespace but keeps one final newline, so applying a proposal does not strip the
     * trailing newline of the file (which shows up as "No newline at end of file" in every diff).
     */
    private fun String.trimKeepingFinalNewline(): String {
        val body = trim()
        return if (body.isNotEmpty() && endsWith("\n")) body + "\n" else body
    }
}
