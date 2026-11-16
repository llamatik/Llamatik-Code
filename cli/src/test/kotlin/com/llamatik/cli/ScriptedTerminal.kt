package com.llamatik.cli

/** A terminal that answers prompts from a script and records everything printed. */
class ScriptedTerminal(vararg answers: String, override val interactive: Boolean = true) : Terminal {
    private val queue = ArrayDeque(answers.toList())
    val out = StringBuilder()
    val err = StringBuilder()
    val prompts = mutableListOf<String>()

    override fun print(text: String) { out.append(text) }
    override fun println(text: String) { out.append(text).append('\n') }
    override fun error(text: String) { err.append(text).append('\n') }
    override fun readLine(prompt: String): String? { prompts += prompt; return queue.removeFirstOrNull() }
    override fun style(kind: Style, text: String) = text
}
