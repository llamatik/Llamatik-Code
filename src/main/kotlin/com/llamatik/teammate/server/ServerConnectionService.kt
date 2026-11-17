package com.llamatik.teammate.server

import com.llamatik.agent.backends.remote.ServerApi
import com.llamatik.agent.backends.remote.ServerEndpoint
import com.llamatik.agent.backends.remote.PairingResult
import com.llamatik.agent.backends.remote.ServerLink
import com.llamatik.agent.backends.remote.ServerPairing
import com.llamatik.agent.backends.remote.ServerCapabilities
import com.llamatik.agent.backends.remote.ServerProbe
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ServerConnectionState {
    /** No server configured / the feature is switched off. Everything runs locally. */
    DISABLED,

    /** First connection attempt in progress. */
    CONNECTING,

    /** Asking the owner of the server to approve this device. */
    PAIRING,

    /** Reachable; inference runs on the server. */
    CONNECTED,

    /** Probes are failing but not for long enough to give up: in-flight work keeps retrying. */
    RECONNECTING,

    /** The server has been unreachable for a while. Inference falls back to the local model. */
    LOST,

    /** The server requires approval and this device has none (token missing or revoked). */
    APPROVAL_REQUIRED,
}

data class ServerStatus(
    val state: ServerConnectionState = ServerConnectionState.DISABLED,
    val host: String = "",
    val port: Int = ServerEndpoint.DEFAULT_PORT,
    val name: String = "",
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val message: String? = null,
) {
    val displayName: String get() = name.ifBlank { host }

    /** True while inference can be sent to the server (it may be briefly reconnecting). */
    val isUsable: Boolean
        get() = (state == ServerConnectionState.CONNECTED || state == ServerConnectionState.RECONNECTING) &&
            capabilities.hasText
}

sealed interface ConnectResult {
    data class Success(val status: ServerStatus) : ConnectResult
    data class Failed(val reason: String) : ConnectResult
}

/**
 * Owns the connection to a Llamatik server: which one, whether it is healthy, what it can do.
 *
 * A background monitor probes the server while a connection is configured. When probes keep
 * failing the state becomes [ServerConnectionState.LOST] and [LlamatikRouter] sends work to the
 * local model; the monitor keeps probing and switches back to the server by itself as soon as it
 * answers again.
 */
@Service(Service.Level.APP)
class ServerConnectionService : ServerLink {
    private val logger = thisLogger()
    private val settings get() = service<LlamatikAppSettings>()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "llamatik-server-monitor").apply { isDaemon = true }
    }
    private val worker = Executors.newCachedThreadPool { r ->
        Thread(r, "llamatik-server-connect").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(ServerStatus) -> Unit>()
    private val started = AtomicBoolean(false)
    private val cancelPairing = AtomicBoolean(false)

    @Volatile private var status = ServerStatus()
    @Volatile private var failures = 0
    @Volatile private var token: String? = null
    @Volatile private var tokenLoadedFor: String? = null
    @Volatile private var nextTick: ScheduledFuture<*>? = null
    @Volatile private var everConnected = false

    fun status(): ServerStatus = status

    // ── ServerLink: what the (shared) remote backend needs ─────────────────
    override fun capabilities(): ServerCapabilities = status.capabilities
    override fun displayName(): String = status.displayName
    override fun isUsable(): Boolean = status.isUsable

    /** Registers [listener] (called on a background thread!). Returns a function that removes it. */
    fun addListener(listener: (ServerStatus) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Starts monitoring the saved server, if any. Safe to call repeatedly. */
    fun start() {
        val data = settings.data()
        if (!data.serverEnabled || data.serverHost.isBlank()) {
            publish(ServerStatus())
            return
        }
        publishIfNew(ServerConnectionState.CONNECTING)
        if (started.compareAndSet(false, true) || nextTick?.isDone != false) scheduleTick(0)
    }

    /** The endpoint to talk to right now, or null when no server is configured. */
    override fun endpoint(): ServerEndpoint? {
        val data = settings.data()
        if (!data.serverEnabled || data.serverHost.isBlank()) return null
        return ServerEndpoint(data.serverHost, data.serverPort, token)
    }

    /**
     * True when inference should be sent to the server: a server is configured and either healthy
     * or there is no local model to fall back to.
     */
    fun shouldUseRemote(localAvailable: Boolean): Boolean {
        val data = settings.data()
        if (!data.serverEnabled || data.serverHost.isBlank()) return false
        val s = status
        if (s.isUsable) return true
        // Not healthy: use the local model when we may and can; otherwise still try the server.
        return !(data.serverFallbackToLocal && localAvailable)
    }

    /**
     * Connects to [host]:[port], requesting per-device approval if the server asks for it, and
     * makes it the active server. Blocking: the pairing wait can take up to a minute.
     */
    fun connect(host: String, port: Int, name: String = host): ConnectResult {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return ConnectResult.Failed("Enter the server address.")
        cancelPairing.set(false)
        stopMonitor()
        loadToken(cleanHost, port)
        publish(ServerStatus(ServerConnectionState.CONNECTING, cleanHost, port, name, message = "Connecting…"))

        var probe = ServerApi(ServerEndpoint(cleanHost, port, token)).probe()
        if (probe is ServerProbe.Unauthorized) {
            // No token yet, or it was revoked: ask the owner to approve this device.
            saveToken(cleanHost, port, null)
            val paired = pairWith(cleanHost, port, name)
            if (paired != null) {
                publish(ServerStatus(ServerConnectionState.DISABLED, message = paired))
                return ConnectResult.Failed(paired)
            }
            probe = ServerApi(ServerEndpoint(cleanHost, port, token)).probe()
        }
        return when (probe) {
            is ServerProbe.Ok -> {
                val data = settings.data()
                data.serverEnabled = true
                data.serverHost = cleanHost
                data.serverPort = port
                data.serverName = name.ifBlank { cleanHost }
                failures = 0
                everConnected = true
                val s = ServerStatus(ServerConnectionState.CONNECTED, cleanHost, port, data.serverName, probe.capabilities)
                publish(s)
                started.set(true)
                scheduleTick(HEALTHY_INTERVAL_MS)
                ConnectResult.Success(s)
            }
            is ServerProbe.Unauthorized -> fail(cleanHost, port, name, "The server did not accept this device.")
            is ServerProbe.Unreachable -> fail(cleanHost, port, name, "Could not reach $cleanHost:$port (${probe.reason}).")
        }
    }

    fun connectAsync(host: String, port: Int, name: String = host, onDone: (ConnectResult) -> Unit) {
        worker.execute { onDone(runCatching { connect(host, port, name) }.getOrElse { ConnectResult.Failed(it.message ?: "Connection failed") }) }
    }

    fun cancelPairing() {
        cancelPairing.set(true)
    }

    /** Stops using the server; everything runs locally again. */
    fun disconnect() {
        stopMonitor()
        settings.data().serverEnabled = false
        failures = 0
        everConnected = false
        publish(ServerStatus())
    }

    /** Called by in-flight generation when the connection dropped, so the UI reacts without waiting for the next probe. */
    override fun reportConnectionProblem() {
        if (status.state == ServerConnectionState.CONNECTED) {
            publish(status.copy(state = ServerConnectionState.RECONNECTING, message = "Connection interrupted — retrying…"))
        }
        // Probe again soon instead of waiting out the healthy interval.
        if (started.get()) scheduleTick(0)
    }

    /** Called when generation traffic worked, proving the connection is fine. */
    override fun reportConnectionOk() {
        if (status.state == ServerConnectionState.RECONNECTING) {
            failures = 0
            publish(status.copy(state = ServerConnectionState.CONNECTED, message = null))
        }
    }

    // ── Monitor ─────────────────────────────────────────────────────────────

    private fun stopMonitor() {
        nextTick?.cancel(false)
        nextTick = null
        started.set(false)
    }

    private fun scheduleTick(delayMs: Long) {
        nextTick?.cancel(false)
        nextTick = runCatching { executor.schedule({ tick() }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun tick() {
        val data = settings.data()
        if (!data.serverEnabled || data.serverHost.isBlank()) {
            started.set(false)
            publish(ServerStatus())
            return
        }
        val host = data.serverHost
        val port = data.serverPort
        loadToken(host, port)

        var nextDelay = HEALTHY_INTERVAL_MS
        try {
            when (val probe = ServerApi(ServerEndpoint(host, port, token)).probe()) {
                is ServerProbe.Ok -> {
                    val previous = status.state
                    failures = 0
                    publish(
                        ServerStatus(
                            ServerConnectionState.CONNECTED, host, port,
                            data.serverName.ifBlank { host }, probe.capabilities,
                        ),
                    )
                    if (previous == ServerConnectionState.LOST) {
                        notify("Reconnected to ${status.displayName}", "Inference runs on the Llamatik server again.", NotificationType.INFORMATION)
                    }
                    everConnected = true
                }
                is ServerProbe.Unauthorized -> {
                    failures = 0
                    nextDelay = LOST_INTERVAL_MS
                    val wasUsable = status.isUsable
                    publish(
                        ServerStatus(
                            ServerConnectionState.APPROVAL_REQUIRED, host, port, data.serverName.ifBlank { host },
                            message = "The server no longer accepts this device. Reconnect from Settings to request approval again.",
                        ),
                    )
                    if (wasUsable) {
                        notify("Llamatik server access revoked", "Using the local model. Reconnect from Settings → Llamatik Code → Server.", NotificationType.WARNING)
                    }
                }
                is ServerProbe.Unreachable -> {
                    failures++
                    val previous = status.state
                    if (failures >= LOST_AFTER_FAILURES) {
                        nextDelay = LOST_INTERVAL_MS
                        publish(
                            ServerStatus(
                                ServerConnectionState.LOST, host, port, data.serverName.ifBlank { host },
                                message = "Connection lost: ${probe.reason}",
                            ),
                        )
                        if (previous != ServerConnectionState.LOST && everConnected) {
                            val fallback = if (data.serverFallbackToLocal) "Switched back to the local model." else "Waiting for the server to come back."
                            notify("Llamatik server connection lost", fallback, NotificationType.WARNING)
                        }
                    } else {
                        nextDelay = RETRY_INTERVAL_MS
                        if (previous == ServerConnectionState.CONNECTED || previous == ServerConnectionState.RECONNECTING) {
                            publish(status.copy(state = ServerConnectionState.RECONNECTING, message = "Reconnecting…"))
                        } else if (previous == ServerConnectionState.CONNECTING) {
                            publish(ServerStatus(ServerConnectionState.CONNECTING, host, port, data.serverName.ifBlank { host }, message = "Connecting…"))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            logger.warn("Server monitor tick failed", t)
        } finally {
            if (started.get()) scheduleTick(nextDelay)
        }
    }

    // ── Pairing / credentials ───────────────────────────────────────────────

    /** Returns null on success (token stored), or a human-readable failure reason. */
    private fun pairWith(host: String, port: Int, name: String): String? {
        val result = ServerPairing.pair(
            endpoint = ServerEndpoint(host, port),
            clientId = clientId(),
            clientName = deviceName(),
            onWaiting = {
                publish(
                    ServerStatus(
                        ServerConnectionState.PAIRING, host, port, name,
                        message = "Waiting for the owner of ${name.ifBlank { host }} to approve this device…",
                    ),
                )
            },
            isCancelled = { cancelPairing.get() },
        )
        return when (result) {
            is PairingResult.Approved -> { saveToken(host, port, result.token); null }
            is PairingResult.Failed -> result.reason
        }
    }

    private fun clientId(): String {
        val data = settings.data()
        if (data.serverClientId.isBlank()) data.serverClientId = UUID.randomUUID().toString().replace("-", "")
        return data.serverClientId
    }

    private fun deviceName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name") ?: "IDE"
        return "$host (Llamatik Code)"
    }

    private fun credentialAttributes(host: String, port: Int) =
        CredentialAttributes(generateServiceName("Llamatik Code", "server:$host:$port"))

    private fun loadToken(host: String, port: Int) {
        val key = "$host:$port"
        if (tokenLoadedFor == key) return
        token = runCatching { PasswordSafe.instance.getPassword(credentialAttributes(host, port)) }.getOrNull()
        tokenLoadedFor = key
    }

    private fun saveToken(host: String, port: Int, value: String?) {
        token = value
        tokenLoadedFor = "$host:$port"
        runCatching {
            PasswordSafe.instance.set(
                credentialAttributes(host, port),
                value?.let { Credentials("llamatik", it) },
            )
        }.onFailure { logger.warn("Could not store the server token", it) }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun fail(host: String, port: Int, name: String, reason: String): ConnectResult {
        val data = settings.data()
        // Keep an already-saved server active so a failed attempt to add another one doesn't break it.
        publish(
            if (data.serverEnabled) ServerStatus(ServerConnectionState.LOST, data.serverHost, data.serverPort, data.serverName, message = reason)
            else ServerStatus(),
        )
        if (data.serverEnabled) {
            started.set(true)
            scheduleTick(RETRY_INTERVAL_MS)
        }
        logger.info("Connect to $host:$port ($name) failed: $reason")
        return ConnectResult.Failed(reason)
    }

    private fun publishIfNew(state: ServerConnectionState): Boolean {
        if (status.state != ServerConnectionState.DISABLED) return false
        val d = settings.data()
        publish(ServerStatus(state, d.serverHost, d.serverPort, d.serverName.ifBlank { d.serverHost }, message = "Connecting…"))
        return true
    }

    private fun publish(next: ServerStatus) {
        val changed = next != status
        status = next
        if (changed) listeners.forEach { runCatching { it(next) } }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Llamatik Code")
                ?.createNotification(title, content, type)
                ?.notify(null)
        }
    }

    companion object {
        private const val HEALTHY_INTERVAL_MS = 10_000L
        private const val RETRY_INTERVAL_MS = 3_000L
        private const val LOST_INTERVAL_MS = 5_000L
        /** Consecutive failed probes before the connection is considered lost (~10 s). */
        private const val LOST_AFTER_FAILURES = 3
    }
}
