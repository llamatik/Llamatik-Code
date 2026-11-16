package com.llamatik.agent.core.agent

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Splits a model's token stream into visible answer text and `<think>` reasoning, holding back
 * partial tags that arrive split across tokens.
 *
 * Two behaviours exist, both extracted unchanged from the original orchestrator:
 *  - [Mode.AGENT]: answer text is never streamed (it arrives via the final message so planning prose
 *    before `<tool_call>` tags cannot leak into the panel); only reasoning goes to [onThinking].
 *  - [Mode.CHAT]: answer text is streamed to [onAnswer]; reasoning is discarded.
 */
class StreamFilter(
    private val mode: Mode,
    private val onAnswer: (String) -> Unit = {},
    private val onThinking: ((String) -> Unit)? = null,
) {
    enum class Mode { AGENT, CHAT }

    private val tokenBuffer = StringBuilder()
    private val inThink = AtomicBoolean(false)

    /** Forgets buffered text and leaves any open think block (agent mode starts each round clean). */
    fun reset() {
        tokenBuffer.clear()
        inThink.set(false)
    }

    fun accept(token: String) = when (mode) {
        Mode.AGENT -> acceptAgent(token)
        Mode.CHAT -> acceptChat(token)
    }

    private fun acceptAgent(token: String) {
        tokenBuffer.append(token)
        while (tokenBuffer.isNotEmpty()) {
            val buf = tokenBuffer.toString()
            if (!inThink.get()) {
                val openIdx = buf.indexOf(THINK_OPEN)
                if (openIdx >= 0) {
                    inThink.set(true)
                    tokenBuffer.clear()
                    tokenBuffer.append(buf.substring(openIdx + THINK_OPEN.length))
                    continue
                }
                // Discard answer text — hold only a partial tag prefix tail to avoid split tags.
                val holdLen = holdLenForTags(buf, ANSWER_HOLD_TAGS)
                if (buf.length > holdLen) {
                    tokenBuffer.clear()
                    if (holdLen > 0) tokenBuffer.append(buf.takeLast(holdLen))
                }
                break
            } else {
                // Any of these terminates the think block; <wait covers all <wait...> variants.
                val bufLow = buf.lowercase()
                val closeMatch = THINK_CLOSERS.mapNotNull { tag ->
                    val idx = bufLow.indexOf(tag.lowercase())
                    if (idx >= 0) idx to tag else null
                }.minByOrNull { it.first }
                if (closeMatch != null) {
                    val (idx, tag) = closeMatch
                    val thinkContent = buf.substring(0, idx)
                    if (thinkContent.isNotEmpty()) onThinking?.invoke(thinkContent)
                    inThink.set(false)
                    tokenBuffer.clear()
                    if (tag == THINK_CLOSE) tokenBuffer.append(buf.substring(idx + tag.length))
                    continue
                }
                val holdLen = holdLenForStartsWith(buf, THINK_CLOSERS)
                val safeEnd = buf.length - holdLen
                if (safeEnd > 0) {
                    onThinking?.invoke(buf.substring(0, safeEnd))
                    tokenBuffer.clear()
                    tokenBuffer.append(buf.substring(safeEnd))
                }
                break
            }
        }
    }

    private fun acceptChat(token: String) {
        tokenBuffer.append(token)
        while (tokenBuffer.isNotEmpty()) {
            val buf = tokenBuffer.toString()
            if (!inThink.get()) {
                val openIdx = buf.indexOf(THINK_OPEN)
                if (openIdx >= 0) {
                    if (openIdx > 0) onAnswer(buf.substring(0, openIdx))
                    inThink.set(true)
                    tokenBuffer.clear()
                    tokenBuffer.append(buf.substring(openIdx + THINK_OPEN.length))
                    continue
                }
                val holdLen = holdLenForTags(buf, ANSWER_HOLD_TAGS)
                val safeEnd = buf.length - holdLen
                if (safeEnd > 0) {
                    onAnswer(buf.substring(0, safeEnd))
                    tokenBuffer.clear()
                    tokenBuffer.append(buf.substring(safeEnd))
                }
                break
            } else {
                val closeIdx = buf.indexOf(THINK_CLOSE)
                if (closeIdx >= 0) {
                    inThink.set(false)
                    tokenBuffer.clear()
                    tokenBuffer.append(buf.substring(closeIdx + THINK_CLOSE.length))
                    continue
                }
                // Reasoning is discarded, but a closing tag split across tokens must still be recognised.
                val holdLen = holdLenForTags(buf, listOf(THINK_CLOSE))
                tokenBuffer.clear()
                if (holdLen > 0) tokenBuffer.append(buf.takeLast(holdLen))
                break
            }
        }
    }

    companion object {
        /** Returns how many trailing characters of [buf] must be held back because they could be the start of any [tags] (suffix match). */
        fun holdLenForTags(buf: String, tags: List<String>): Int {
            val maxLen = tags.maxOf { it.length }
            return (1..minOf(maxLen, buf.length)).reversed()
                .firstOrNull { len -> tags.any { tag -> buf.lowercase().endsWith(tag.lowercase().take(len)) } } ?: 0
        }

        /** Returns how many trailing characters of [buf] must be held back because they could be the start of any [tags] (startsWith match). */
        fun holdLenForStartsWith(buf: String, tags: List<String>): Int {
            val bufLow = buf.lowercase()
            val maxLen = tags.maxOf { it.length }
            return (1..minOf(maxLen, buf.length)).reversed()
                .firstOrNull { len -> tags.any { tag -> tag.lowercase().startsWith(bufLow.takeLast(len)) } } ?: 0
        }
    }
}
