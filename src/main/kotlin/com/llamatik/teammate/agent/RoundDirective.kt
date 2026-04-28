package com.llamatik.teammate.agent

/**
 * Pure logic for choosing the next-round directive message sent to the model.
 *
 * Stateless, model-agnostic, and fully unit-testable.
 */
object RoundDirective {

    data class RoundState(
        val round: Int,
        val maxRounds: Int,
        val hasReadContent: Boolean,
        val wroteFilesThisRound: Boolean,
        val validProposalsProduced: Boolean,
        val allToolsWereReadOnly: Boolean,
        /** Reasons why write proposals were rejected this round, if any. */
        val rejectionReasons: List<String> = emptyList(),
        /** True when the user has a file open or attached — a concrete source file exists in context. */
        val hasFileContext: Boolean = false,
    )

    /**
     * Returns the directive string to append to the next round's user message.
     *
     * Rules (checked in priority order):
     * 1. Final round → demand output now (write or text answer)
     * 2. Wrote files but proposals rejected → echo rejection, demand retry
     * 3. Read content AND running low on rounds (≥60% used) OR a specific file was attached → stop reading, produce output
     * 4. Read content but still have rounds left AND exploring project freely → keep reading more files
     * 5. No reads yet, file context exists → read the attached file
     * 6. No reads yet, no file context → explore with list_files + read_file
     * 7. Default → proceed with task
     */
    fun get(state: RoundState): String {
        val rejectionDetail = if (state.rejectionReasons.isNotEmpty())
            " Rejection reason: ${state.rejectionReasons.joinToString("; ")}."
        else ""

        val writeInstruction = "call write_file or create_file with the appropriate output path for your task"
        val roundsLeft = state.maxRounds - state.round
        val nearingEnd = roundsLeft <= (state.maxRounds * 0.4).coerceAtLeast(2.0)

        return when {
            state.round + 1 >= state.maxRounds ->
                "FINAL ROUND. If this task requires file changes, you MUST $writeInstruction NOW with COMPLETE, " +
                "compilable code. No TODO stubs. No placeholders. Real implementation only. " +
                "If this is a read-only task (review, analysis), write your findings as a plain-text answer.$rejectionDetail"

            state.wroteFilesThisRound && !state.validProposalsProduced ->
                "Your previous write was rejected.$rejectionDetail " +
                "You MUST $writeInstruction again with a COMPLETE, real implementation. " +
                "No TODO comments. No stubs. Write actual working code."

            state.hasReadContent && (nearingEnd || state.hasFileContext) ->
                "You have read enough files. Now complete the task: " +
                "if changes are needed, $writeInstruction with the COMPLETE implementation. " +
                "If this is a review or analysis, write your findings as a plain-text answer. " +
                "Do NOT call read_file, search_code, or search_filenames again."

            state.hasReadContent && !state.hasFileContext ->
                "Good — you have read some files. Continue reading more relevant files with read_file or search_code " +
                "to get a complete picture, then produce your output."

            state.allToolsWereReadOnly && !state.hasReadContent && state.hasFileContext ->
                "Call read_file on the source file shown in the task before proceeding."

            state.allToolsWereReadOnly && !state.hasReadContent && !state.hasFileContext ->
                "Use list_files and read_file to explore the relevant source files, then complete the task."

            else ->
                "Proceed with the task. If changes are needed, $writeInstruction with the COMPLETE implementation. No TODO stubs."
        }
    }


}
