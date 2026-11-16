package com.llamatik.agent.backends.remote

/**
 * What [RemoteBackend] needs to know about the server connection it runs on. The IDE plugin backs it
 * with a health-monitored connection; command-line hosts use a fixed [StaticServerLink].
 */
interface ServerLink {
    /** Where to send requests, or null when no server is configured. */
    fun endpoint(): ServerEndpoint?
    fun capabilities(): ServerCapabilities
    fun displayName(): String

    /** True while requests can be sent (the server is up and has a text model loaded). */
    fun isUsable(): Boolean

    /** A request hit a connection problem; hosts may probe sooner / show it. */
    fun reportConnectionProblem()

    /** A request succeeded, proving the connection works. */
    fun reportConnectionOk()
}

/** A server that is assumed reachable: for one-shot command-line runs. */
class StaticServerLink(
    private val endpoint: ServerEndpoint,
    private var capabilities: ServerCapabilities = ServerCapabilities(hasText = true),
    private val name: String = endpoint.host,
) : ServerLink {
    override fun endpoint(): ServerEndpoint = endpoint
    override fun capabilities(): ServerCapabilities = capabilities
    override fun displayName(): String = name
    override fun isUsable(): Boolean = true
    override fun reportConnectionProblem() = Unit
    override fun reportConnectionOk() = Unit

    /** Replaces the capabilities learned from a probe (model name, finetune type). */
    fun update(newCapabilities: ServerCapabilities) { capabilities = newCapabilities }
}
