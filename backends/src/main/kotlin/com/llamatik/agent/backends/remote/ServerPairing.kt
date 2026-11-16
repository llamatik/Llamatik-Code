package com.llamatik.agent.backends.remote

/** Outcome of asking a server's owner to approve this device. */
sealed interface PairingResult {
    data class Approved(val token: String) : PairingResult
    /** Human-readable reason the device was not approved. */
    data class Failed(val reason: String) : PairingResult
}

/**
 * The per-device approval handshake: send a request, then poll until the owner of the server approves
 * or denies it on their device. Shared by the IDE plugin and the command-line hosts.
 */
object ServerPairing {
    const val DEFAULT_TIMEOUT_MS = 65_000L
    const val DEFAULT_POLL_MS = 1_000L

    fun pair(
        endpoint: ServerEndpoint,
        clientId: String,
        clientName: String,
        /** Called once the request was sent and the owner has to act. */
        onWaiting: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): PairingResult {
        val client = LlamatikServerClient(endpoint.copy(token = null))
        val requestId = client.requestPairing(clientId, clientName)
            ?: return PairingResult.Failed("The server refused the approval request.")
        onWaiting()
        var waited = 0L
        while (waited < timeoutMs) {
            if (isCancelled()) return PairingResult.Failed("Approval request cancelled.")
            val poll = client.pollPairing(requestId)
            if (poll != null) {
                when (poll.status) {
                    "approved" -> return poll.token?.let { PairingResult.Approved(it) }
                        ?: PairingResult.Failed("The server approved the request but sent no token.")
                    "denied" -> return PairingResult.Failed("The server denied this device.")
                    "expired" -> return PairingResult.Failed("The approval request expired.")
                }
            }
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return PairingResult.Failed("Approval request cancelled.")
            }
            waited += pollMs
        }
        return PairingResult.Failed("Timed out waiting for approval.")
    }
}
