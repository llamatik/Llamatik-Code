package com.llamatik.cli

import com.llamatik.agent.backends.InferenceParams
import java.nio.file.Path

/** Everything the command line can ask for. */
data class CliOptions(
    val model: String? = null,
    val server: String? = null,
    /** Look for Llamatik servers on the local network and pick one (instead of `--server`). */
    val discover: Boolean = false,
    /** One-shot prompt (`-p`); null starts the interactive session. */
    val prompt: String? = null,
    val workDir: Path = Path.of("").toAbsolutePath(),
    /** Approve every edit and command without asking. */
    val yes: Boolean = false,
    val aggressive: Boolean = false,
    /** Print every model reply as it arrives (for diagnosing odd behaviour). */
    val debug: Boolean = false,
    val params: InferenceParams = InferenceParams(),
    val help: Boolean = false,
    val version: Boolean = false,
)

class UsageException(message: String) : Exception(message)

object CliArgs {
    fun parse(args: List<String>): CliOptions {
        var o = CliOptions()
        var p = o.params
        var i = 0
        fun value(flag: String): String {
            if (i + 1 >= args.size) throw UsageException("Missing value for $flag")
            return args[++i]
        }
        fun int(flag: String): Int = value(flag).toIntOrNull() ?: throw UsageException("$flag needs a whole number")
        while (i < args.size) {
            val a = args[i]
            // --flag=value form
            val (flag, inline) = if (a.startsWith("--") && '=' in a) a.substringBefore('=') to a.substringAfter('=') else a to null
            fun v(): String = inline ?: value(flag)
            fun n(): Int = inline?.toIntOrNull() ?: if (inline != null) throw UsageException("$flag needs a whole number") else int(flag)
            when (flag) {
                "-h", "--help" -> o = o.copy(help = true)
                "--version" -> o = o.copy(version = true)
                "-m", "--model" -> o = o.copy(model = v())
                "-s", "--server" -> o = o.copy(server = v())
                "--discover" -> o = o.copy(discover = true)
                "-p", "--prompt" -> o = o.copy(prompt = v())
                "-C", "--cwd" -> o = o.copy(workDir = Path.of(v()).toAbsolutePath().normalize())
                "-y", "--yes" -> o = o.copy(yes = true)
                "--aggressive" -> o = o.copy(aggressive = true)
                "--debug" -> o = o.copy(debug = true)
                "--temperature" -> p = p.copy(temperature = v().toDoubleOrNull() ?: throw UsageException("--temperature needs a number"))
                "--max-tokens" -> p = p.copy(maxTokens = n())
                "--context" -> p = p.copy(contextSize = n())
                "--threads" -> p = p.copy(threads = n())
                "--gpu-layers" -> p = p.copy(gpuLayers = n())
                else -> throw UsageException(
                    if (a.startsWith("-")) "Unknown option: $a"
                    else "Unexpected argument: $a (put the prompt after -p)",
                )
            }
            i++
        }
        if (o.discover && (o.server != null || o.model != null)) {
            throw UsageException("--discover looks for a server by itself; do not combine it with --server or --model")
        }
        return o.copy(params = p)
    }

    val USAGE = """
        |Usage: llamatik [options]
        |
        |Runs the Llamatik coding agent in your terminal, on a local model or a Llamatik server.
        |
        |Model (one of):
        |  -m, --model <file.gguf>     run a local GGUF model in-process
        |  -s, --server <host[:port]>  use a Llamatik server (e.g. the Llamatik app with "Share this device")
        |      --discover              scan the local network for Llamatik servers and choose one
        |
        |Session:
        |  -p, --prompt <text>         run one task and exit (otherwise an interactive session starts)
        |  -C, --cwd <dir>             project directory (default: current directory)
        |  -y, --yes                   apply edits and run commands without asking
        |      --aggressive            use the aggressive execution mode
        |      --debug                 print each raw model reply
        |
        |Inference:
        |      --temperature <n>       sampling temperature (default 0.7)
        |      --max-tokens <n>        tokens per reply (default 8192)
        |      --context <n>           context size in tokens (default 16384)
        |      --threads <n>           CPU threads
        |      --gpu-layers <n>        layers offloaded to the GPU (0 = CPU only, -1 = all)
        |
        |  -h, --help                  show this help
        |      --version               show the version
        |
        |In a session: /help, /clear (forget the conversation), /yes (toggle auto-approve), /exit
    """.trimMargin()
}
