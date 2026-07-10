package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ReplaceInFileTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "replace_in_file"
    override val description: String = "Prepare a string replacement in an existing file. Arguments: path, oldText, newText"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val oldText = call.arguments["oldText"] ?: return ToolResult(name, false, "Missing oldText")
        val newText = call.arguments["newText"] ?: return ToolResult(name, false, "Missing newText")
        val oldContent = contextService.readFile(path)

        // Exact match — fast path
        if (oldContent.contains(oldText)) {
            val newContent = oldContent.replace(oldText, newText)
            return ToolResult(name, true, "Prepared replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$newContent")
        }

        // Fuzzy match: normalize whitespace on each line (strip trailing spaces, unify indent
        // to the minimum indentation present). Local models frequently hallucinate slightly wrong
        // indentation or add/remove trailing spaces, causing exact matches to fail.
        val normalizedContent = normalizeIndent(oldContent)
        val normalizedOld = normalizeIndent(oldText)
        if (normalizedContent.contains(normalizedOld)) {
            // Re-apply using the normalized forms so replacement is positionally correct,
            // then splice the model's newText (which likely has correct indentation) in.
            val indent = detectIndent(oldText)
            val reindentedNew = if (indent.isNotEmpty()) reindent(newText, indent) else newText
            val newContent = normalizedContent.replace(normalizedOld, reindentedNew)
            return ToolResult(name, true, "Prepared replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$newContent")
        }

        // Trimmed-lines match: compare logical lines ignoring all leading/trailing whitespace.
        // Catches cases where the model omits or changes blank lines between code blocks.
        val contentLines = oldContent.lines()
        val oldLines = oldText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (oldLines.isNotEmpty()) {
            val matchStart = findTrimmedMatch(contentLines, oldLines)
            if (matchStart >= 0) {
                // Preserve original indentation from the matched region
                val matchEnd = matchStart + findOriginalSpan(contentLines, matchStart, oldLines)
                val before = contentLines.take(matchStart).joinToString("\n")
                val after = contentLines.drop(matchEnd).joinToString("\n")
                val newContent = listOf(before, newText.trim(), after)
                    .filter { it.isNotEmpty() }.joinToString("\n")
                return ToolResult(name, true, "Prepared replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$newContent")
            }
        }

        return ToolResult(
            name, false,
            "replace_in_file failed: the text to replace was not found in $path.\n" +
            "The model's oldText may have incorrect indentation or whitespace.\n" +
            "Read the file with read_file first, then copy the exact text to replace.",
        )
    }

    private fun normalizeIndent(text: String): String {
        val lines = text.lines()
        val minIndent = lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) "" else line.drop(minIndent).trimEnd()
        }
    }

    private fun detectIndent(text: String): String {
        return text.lines().firstOrNull { it.isNotBlank() }
            ?.let { line -> line.takeWhile { it == ' ' || it == '\t' } }
            ?: ""
    }

    private fun reindent(text: String, indent: String): String {
        val lines = text.lines()
        val minIndent = lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) line else indent + line.drop(minIndent)
        }
    }

    // Finds the starting line index in [fileLines] where all non-empty trimmed lines of
    // [searchLines] appear in order. Returns -1 if not found.
    private fun findTrimmedMatch(fileLines: List<String>, searchLines: List<String>): Int {
        val trimmedFile = fileLines.map { it.trim() }
        outer@ for (i in trimmedFile.indices) {
            var si = 0
            var fi = i
            while (si < searchLines.size && fi < trimmedFile.size) {
                if (trimmedFile[fi] == searchLines[si]) si++
                fi++
            }
            if (si == searchLines.size) return i
        }
        return -1
    }

    // Returns how many lines in [fileLines] starting at [start] span the matched region.
    private fun findOriginalSpan(fileLines: List<String>, start: Int, searchLines: List<String>): Int {
        var si = 0
        var fi = start
        while (si < searchLines.size && fi < fileLines.size) {
            if (fileLines[fi].trim() == searchLines[si]) si++
            fi++
        }
        return fi - start
    }
}
