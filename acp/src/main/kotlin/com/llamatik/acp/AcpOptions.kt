package com.llamatik.acp

import com.llamatik.agent.backends.InferenceParams

data class AcpOptions(
    val model: String? = null,
    val server: String? = null,
    val params: InferenceParams = InferenceParams(),
    /** Skip permission requests (the editor may have its own policy). */
    val autoApprove: Boolean = false,
    val aggressive: Boolean = false,
    val help: Boolean = false,
    val version: Boolean = false,
)

class UsageException(message: String) : Exception(message)

object AcpArgs {
    fun parse(args: List<String>): AcpOptions {
        var o = AcpOptions()
        var p = o.params
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val (flag, inline) = if (a.startsWith("--") && '=' in a) a.substringBefore('=') to a.substringAfter('=') else a to null
            fun v(): String = inline ?: args.getOrNull(++i) ?: throw UsageException("Missing value for $flag")
            fun n(): Int = v().toIntOrNull() ?: throw UsageException("$flag needs a whole number")
            when (flag) {
                "-h", "--help" -> o = o.copy(help = true)
                "--version" -> o = o.copy(version = true)
                "-m", "--model" -> o = o.copy(model = v())
                "-s", "--server" -> o = o.copy(server = v())
                "--auto-approve" -> o = o.copy(autoApprove = true)
                "--aggressive" -> o = o.copy(aggressive = true)
                "--temperature" -> p = p.copy(temperature = v().toDoubleOrNull() ?: throw UsageException("--temperature needs a number"))
                "--max-tokens" -> p = p.copy(maxTokens = n())
                "--context" -> p = p.copy(contextSize = n())
                "--threads" -> p = p.copy(threads = n())
                "--gpu-layers" -> p = p.copy(gpuLayers = n())
                else -> throw UsageException(if (a.startsWith("-")) "Unknown option: $a" else "Unexpected argument: $a")
            }
            i++
        }
        return o.copy(params = p)
    }

    val USAGE = """
        |Usage: llamatik-acp [options]
        |
        |Llamatik as an Agent Client Protocol agent. An ACP editor launches this command and talks to it over
        |stdin/stdout; the editor chooses the project folder for each session.
        |
        |Model (one of):
        |  -m, --model <file.gguf>     run a local GGUF model in-process
        |  -s, --server <host[:port]>  use a Llamatik server
        |
        |Options:
        |      --auto-approve          do not ask the editor before edits and commands
        |      --aggressive            use the aggressive execution mode
        |      --temperature <n>  --max-tokens <n>  --context <n>  --threads <n>  --gpu-layers <n>
        |  -h, --help                  show this help
        |      --version               show the version
        |
        |Editor configuration (example, Zed):
        |  "agent_servers": { "Llamatik": { "command": "llamatik-acp", "args": ["--server", "mac-studio.local"] } }
    """.trimMargin()
}
