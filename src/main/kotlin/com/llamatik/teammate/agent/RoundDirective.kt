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
        /** Path of the file that was read this round, if any. Used to give concrete write instructions. */
        val readFilePath: String? = null,
    )

    /**
     * Returns the directive string to append to the next round's user message.
     *
     * Rules (checked in priority order):
     * 1. Final round approaching → demand a write now, no stubs
     * 2. Wrote files but proposals were all rejected → echo the rejection reason back
     * 3. Already read content → demand write now, naming the concrete test file path
     * 4. No reads yet and only read tools ran → direct to read first
     * 5. Default → demand a complete write
     */
    fun get(state: RoundState): String {
        val rejectionDetail = if (state.rejectionReasons.isNotEmpty())
            " Rejection reason: ${state.rejectionReasons.joinToString("; ")}."
        else ""

        val writeInstruction = if (state.readFilePath != null) {
            val testPath = deriveTestPath(state.readFilePath)
            "call write_file or create_file with path=\"$testPath\""
        } else {
            "call write_file or create_file"
        }

        return when {
            state.round + 1 >= state.maxRounds ->
                "FINAL ROUND. You MUST $writeInstruction NOW with COMPLETE, compilable code. " +
                "No TODO stubs. No placeholders. Real implementation only.$rejectionDetail"

            state.wroteFilesThisRound && !state.validProposalsProduced ->
                "Your previous write was rejected.$rejectionDetail " +
                "You MUST $writeInstruction again with a COMPLETE, real implementation. " +
                "No TODO comments. No stubs. Write actual working code."

            state.hasReadContent ->
                "You have read the file content. " +
                "NOW you MUST $writeInstruction with the COMPLETE implementation. " +
                "Do NOT call read_file, search_code, or search_filenames again. Write the full code now."

            state.allToolsWereReadOnly && !state.hasReadContent ->
                "Call read_file on the source file shown in the task before writing."

            else ->
                "You MUST $writeInstruction with the COMPLETE implementation. No TODO stubs."
        }
    }

    /**
     * Derives a conventional test file path from a source file path.
     * e.g. "shared/src/commonMain/kotlin/com/foo/Bar.kt"
     *   → "shared/src/commonTest/kotlin/com/foo/BarTest.kt"
     */
    private fun deriveTestPath(sourcePath: String): String {
        val withTest = sourcePath
            .replace("/commonMain/", "/commonTest/")
            .replace("/main/kotlin/", "/test/kotlin/")
            .replace("/main/java/", "/test/java/")
        val fileName = withTest.substringAfterLast('/')
        val dir = withTest.substringBeforeLast('/', "")
        val testFileName = if (fileName.endsWith("Test.kt") || fileName.endsWith("Test.java")) {
            fileName
        } else {
            fileName.substringBeforeLast('.') + "Test." + fileName.substringAfterLast('.')
        }
        return if (dir.isBlank()) testFileName else "$dir/$testFileName"
    }
}
