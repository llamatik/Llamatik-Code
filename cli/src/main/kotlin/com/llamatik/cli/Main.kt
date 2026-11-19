package com.llamatik.cli

import com.llamatik.agent.backends.BackendFactory
import com.llamatik.agent.backends.remote.FileTokenStore
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var options = try {
        CliArgs.parse(args.toList())
    } catch (e: UsageException) {
        System.err.println("llamatik: ${e.message}")
        System.err.println("Try 'llamatik --help'.")
        exitProcess(64)
    }
    val terminal = SystemTerminal()
    if (options.discover && !options.help && !options.version) {
        when (val picked = ServerPicker(terminal).pick()) {
            is ServerPicker.Result.Picked -> options = options.copy(server = picked.address, discover = false)
            is ServerPicker.Result.Failed -> { terminal.error(terminal.style(Style.RED, "error: ${picked.reason}")); exitProcess(2) }
            ServerPicker.Result.Cancelled -> exitProcess(130)
        }
    }
    val tokens = FileTokenStore(File(System.getProperty("user.home"), ".llamatik/cli.properties"))
    val app = CliApp(options, terminal) {
        BackendFactory.create(
            model = options.model, server = options.server, params = options.params, tokens = tokens,
            onWaitingForApproval = { terminal.println("Waiting for the owner of the server to approve this device…") },
        )
    }
    installInterruptHandler(app)
    exitProcess(app.run())
}

/** Ctrl-C stops the running task; with nothing running it ends the program. */
private fun installInterruptHandler(app: CliApp) {
    try {
        sun.misc.Signal.handle(sun.misc.Signal("INT")) {
            if (!app.cancelCurrent()) exitProcess(130)
        }
    } catch (_: Throwable) {
        // Signals are unavailable on this platform: Ctrl-C then terminates the process as usual.
    }
}
