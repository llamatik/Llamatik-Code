package com.llamatik.cli

/** Minimal line diff for showing a proposed edit before it is applied. */
object Diff {
    private const val MAX_CELLS = 4_000_000

    /** Unified-diff text of [old] to [new], or a notice when they are equal. */
    fun unified(path: String, old: String, new: String, context: Int = 3): String {
        if (old == new) return "(no changes)"
        val a = old.lines()
        val b = new.lines()
        val ops = if (a.size.toLong() * b.size > MAX_CELLS) replaceAll(a, b) else lcsOps(a, b)

        // Group changed ops into hunks with [context] unchanged lines around them.
        val changed = ops.indices.filter { ops[it].kind != ' ' }
        if (changed.isEmpty()) return "(no changes)"
        val out = StringBuilder("--- a/$path\n+++ b/$path\n")
        var idx = 0
        while (idx < changed.size) {
            var start = maxOf(0, changed[idx] - context)
            var end = minOf(ops.size - 1, changed[idx] + context)
            var next = idx + 1
            while (next < changed.size && changed[next] - context <= end + 1) {
                end = minOf(ops.size - 1, changed[next] + context)
                next++
            }
            val slice = ops.subList(start, end + 1)
            val oldStart = ops.take(start).count { it.kind != '+' } + 1
            val newStart = ops.take(start).count { it.kind != '-' } + 1
            out.append("@@ -$oldStart,${slice.count { it.kind != '+' }} +$newStart,${slice.count { it.kind != '-' }} @@\n")
            slice.forEach { out.append(it.kind).append(it.text).append('\n') }
            idx = next
        }
        return out.toString().trimEnd('\n')
    }

    private class Op(val kind: Char, val text: String)

    private fun replaceAll(a: List<String>, b: List<String>) =
        a.map { Op('-', it) } + b.map { Op('+', it) }

    private fun lcsOps(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops += Op(' ', a[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { ops += Op('-', a[i]); i++ }
                else -> { ops += Op('+', b[j]); j++ }
            }
        }
        while (i < n) ops += Op('-', a[i++])
        while (j < m) ops += Op('+', b[j++])
        return ops
    }
}
