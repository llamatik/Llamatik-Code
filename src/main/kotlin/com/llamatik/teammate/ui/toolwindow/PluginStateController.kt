package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.components.service
import com.llamatik.teammate.model.PluginReadinessState
import com.llamatik.teammate.server.ServerConnectionService
import com.llamatik.teammate.settings.LlamatikAppSettings

/**
 * Computes current plugin readiness from persisted settings + runtime state.
 *
 * The native model is lazy-loaded by [LlamatikBackendService] on first inference call,
 * so "path is configured" is sufficient to consider the plugin ready for interaction.
 * We only show LOADING_MODEL during an explicit load operation triggered by the UI.
 */
class PluginStateController {
    private val settings = service<LlamatikAppSettings>()

    fun currentState(isActivelyLoading: Boolean, isIndexing: Boolean): PluginReadinessState {
        val modelPath = settings.data().modelPath
        // A connected Llamatik server provides the model, so no local model file is required.
        val serverReady = service<ServerConnectionService>().status().isUsable
        if (modelPath.isBlank() && !serverReady) return PluginReadinessState.NO_MODEL
        if (isActivelyLoading && !serverReady) return PluginReadinessState.LOADING_MODEL
        if (isIndexing) return PluginReadinessState.INDEXING
        return PluginReadinessState.READY
    }
}
