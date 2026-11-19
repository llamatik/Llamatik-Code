package com.llamatik.agent.backends.remote

import java.io.File
import java.net.InetAddress
import java.util.Properties
import java.util.UUID

/** Where per-server access tokens are kept between runs. */
interface TokenStore {
    fun get(host: String, port: Int): String?
    fun put(host: String, port: Int, token: String?)
    fun clientId(): String
}

/** [TokenStore] in a small properties file, readable by the current user only. */
class FileTokenStore(private val file: File) : TokenStore {
    private fun load(): Properties = Properties().also { p -> if (file.isFile) file.inputStream().use(p::load) }

    private fun save(p: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "Llamatik server access tokens") }
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

    override fun get(host: String, port: Int): String? = load().getProperty("token.$host.$port")

    override fun put(host: String, port: Int, token: String?) {
        val p = load()
        if (token == null) p.remove("token.$host.$port") else p.setProperty("token.$host.$port", token)
        save(p)
    }

    override fun clientId(): String {
        val p = load()
        p.getProperty("clientId")?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        p.setProperty("clientId", id)
        save(p)
        return id
    }
}

/** A connection to a Llamatik server, ready to back a [RemoteBackend]. */
class ServerConnection(val link: StaticServerLink, val endpoint: ServerEndpoint, val capabilities: ServerCapabilities)

sealed interface ConnectOutcome {
    data class Connected(val connection: ServerConnection) : ConnectOutcome
    data class Failed(val reason: String) : ConnectOutcome
}

/** Probes a server, asks for per-device approval when it requires one, and returns a ready connection. */
class ServerConnector(
    private val tokens: TokenStore,
    private val clientName: String = defaultClientName(),
) {
    fun connect(
        host: String,
        port: Int = ServerEndpoint.DEFAULT_PORT,
        onWaitingForApproval: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ConnectOutcome {
        var endpoint = ServerEndpoint(host, port, tokens.get(host, port))
        var probe = LlamatikServerClient(endpoint).probe()
        if (probe is ServerProbe.Unauthorized) {
            // No token yet, or it was revoked: ask the owner to approve this device.
            tokens.put(host, port, null)
            when (val result = ServerPairing.pair(endpoint, tokens.clientId(), clientName, onWaitingForApproval, isCancelled)) {
                is PairingResult.Failed -> return ConnectOutcome.Failed(result.reason)
                is PairingResult.Approved -> {
                    tokens.put(host, port, result.token)
                    endpoint = endpoint.copy(token = result.token)
                }
            }
            probe = LlamatikServerClient(endpoint).probe()
        }
        return when (probe) {
            is ServerProbe.Ok -> {
                if (!probe.capabilities.hasText) {
                    ConnectOutcome.Failed("The server at $host:$port has no text model loaded.")
                } else {
                    val link = StaticServerLink(endpoint, probe.capabilities, "$host:$port")
                    ConnectOutcome.Connected(ServerConnection(link, endpoint, probe.capabilities))
                }
            }
            is ServerProbe.Unauthorized -> ConnectOutcome.Failed("The server did not accept this device.")
            is ServerProbe.Unreachable -> ConnectOutcome.Failed("Could not reach $host:$port (${probe.reason}).")
        }
    }

    companion object {
        fun defaultClientName(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.name") ?: "terminal"
            return "$host (Llamatik CLI)"
        }

        /** Parses `host`, `host:port`, `http://host:port[/…]`. */
        fun parseAddress(address: String): Pair<String, Int>? {
            val trimmed = address.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (trimmed.isEmpty()) return null
            val ipv6 = trimmed.startsWith("[")
            if (ipv6) {
                val host = trimmed.substringAfter('[').substringBefore(']')
                val port = trimmed.substringAfter("]:", "").takeIf { it.isNotEmpty() }?.toIntOrNull() ?: ServerEndpoint.DEFAULT_PORT
                return host to port
            }
            val host = trimmed.substringBefore(':')
            val portText = trimmed.substringAfter(':', "")
            val port = if (portText.isEmpty()) ServerEndpoint.DEFAULT_PORT else portText.toIntOrNull() ?: return null
            return if (host.isBlank() || port !in 1..65535) null else host to port
        }
    }
}
