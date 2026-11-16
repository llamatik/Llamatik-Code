package com.llamatik.cli

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream

/** The user's terminal; an interface so the whole session can be scripted in tests. */
interface Terminal {
    /** True when a person can answer questions (stdin is a terminal). */
    val interactive: Boolean
    fun print(text: String)
    fun println(text: String = "")
    fun error(text: String)
    /** Prompts and reads one line; null at end of input. */
    fun readLine(prompt: String): String?
    fun style(kind: Style, text: String): String
}

enum class Style { DIM, BOLD, RED, GREEN, CYAN, YELLOW }

class SystemTerminal(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val input: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    override val interactive: Boolean = System.console() != null,
    private val color: Boolean = System.console() != null && System.getenv("NO_COLOR") == null,
) : Terminal {
    override fun print(text: String) { out.print(text); out.flush() }
    override fun println(text: String) { out.println(text); out.flush() }
    override fun error(text: String) { err.println(text); err.flush() }

    override fun readLine(prompt: String): String? {
        print(prompt)
        return input.readLine()
    }

    override fun style(kind: Style, text: String): String {
        if (!color) return text
        val code = when (kind) {
            Style.DIM -> "2"; Style.BOLD -> "1"; Style.RED -> "31"; Style.GREEN -> "32"
            Style.CYAN -> "36"; Style.YELLOW -> "33"
        }
        return "\u001B[${code}m$text\u001B[0m"
    }
}
