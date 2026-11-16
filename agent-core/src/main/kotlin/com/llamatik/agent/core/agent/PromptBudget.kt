package com.llamatik.agent.core.agent

/**
 * How many characters of file content and tool output fit in a prompt for a model with a
 * [contextLength]-token window. Extracted unchanged from the agent loop.
 *
 * contextLength is the actual model context window in tokens; times 3 is a char estimate (UTF-8 source
 * is ~3 chars per token). Batch size is a KV-cache batching parameter, not the context limit, and must
 * not constrain how much file content is sent.
 */
class PromptBudget(contextLength: Int) {
    /** Mirrors the 90% safety margin the local backend applies, so budget and check agree. */
    val safeContextChars: Int = (contextLength * 3 * 0.9).toInt()

    // SYSTEM_PROMPT_BASE_CHARS (identity, tools, rules, skills, memory) + AGENT_HISTORY_BUDGET_CHARS (prior turns)
    // + USER_INSTRUCTION_MAX_CHARS (current turn) + 1_200 (context hint, file-section wrappers, safety margin).
    private val nonFileOverheadChars = SYSTEM_PROMPT_BASE_CHARS + AGENT_HISTORY_BUDGET_CHARS + USER_INSTRUCTION_MAX_CHARS + 1_200

    val fileBudgetChars: Int = (safeContextChars - nonFileOverheadChars).coerceAtLeast(0)
    val fileAttachmentMax: Int = fileBudgetChars
    val fileContextMax: Int = fileBudgetChars

    /** Per-result cap: the full file budget, floored so small contexts never zero out. */
    val toolResultContentMax: Int = fileBudgetChars.coerceAtLeast(TOOL_RESULT_CONTENT_MAX_CHARS)

    // Round 2 user turn holds BOTH source re-injection AND tool results in the same message, so they are
    // budgeted jointly against safeContextChars. The second-pass system prompt is tiny (~500 chars):
    // 1_200 is reserved for it and misc overhead.
    val round2ContentBudget: Int = (safeContextChars - 1_200).coerceAtLeast(0)
    val toolResultsMax: Int = (round2ContentBudget * 2 / 3).coerceAtLeast(TOOL_RESULTS_MAX_CHARS)
    val round2FileContextMax: Int = (round2ContentBudget / 3).coerceAtLeast(3_000)
}
