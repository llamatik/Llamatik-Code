package com.llamatik.acp

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.llamatik.agent.backends.BackendFactory
import com.llamatik.agent.backends.remote.FileTokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        AcpArgs.parse(args.toList())
    } catch (e: UsageException) {
        System.err.println("llamatik-acp: ${e.message}")
        System.err.println("Try 'llamatik-acp --help'.")
        exitProcess(64)
    }
    if (options.help) { println(AcpArgs.USAGE); return }
    if (options.version) { println("llamatik-acp ${LlamatikAgent.VERSION}"); return }

    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") ?: "warn")

    // stdout carries the protocol and nothing else. Keep a private handle to it and point everything
    // else that prints (including the model library's own messages) at stderr.
    val protocolOut: PrintStream = System.out
    System.setOut(System.err)

    val tokens = FileTokenStore(File(System.getProperty("user.home"), ".llamatik/cli.properties"))
    val agent = LlamatikAgent(options) {
        BackendFactory.create(
            model = options.model, server = options.server, params = options.params, tokens = tokens,
            onWaitingForApproval = { System.err.println("Waiting for the owner of the server to approve this device…") },
        )
    }
    // The native model must be released before the process exits (the GPU runtime aborts otherwise), also
    // when the editor simply terminates us.
    Runtime.getRuntime().addShutdownHook(Thread { agent.close() })
    serve(agent, System.`in`.let { BufferedReader(InputStreamReader(it)) }, protocolOut)
    agent.close()
    exitProcess(0)
}

private fun serve(agent: LlamatikAgent, input: BufferedReader, output: PrintStream) = runBlocking {
    val closed = CompletableDeferred<Unit>()
    val lines = flow {
        while (true) emit(input.readLine() ?: break)
    }.flowOn(Dispatchers.IO).onCompletion { closed.complete(Unit) }
    val transport = StdioTransport(
        parentScope = this,
        ioDispatcher = Dispatchers.IO,
        input = lines,
        output = { line -> synchronized(output) { output.print(line); output.print('\n'); output.flush() } },
    )
    val protocol = Protocol(this, transport)
    Agent(protocol, agent)
    protocol.start()
    closed.await()
    protocol.close()
}
