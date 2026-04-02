package com.llamatik.teammate.infrastructure

import com.intellij.openapi.components.Service
import com.llamatik.teammate.model.LoadedModelState

@Service(Service.Level.APP)
class ModelRuntimeTracker {
    @Volatile private var loadedState: LoadedModelState? = null

    fun recordLoaded(modelId: String, modelPath: String) {
        loadedState = LoadedModelState(modelId, modelPath, System.currentTimeMillis())
    }

    fun recordUnloaded() {
        loadedState = null
    }

    fun loadedModel(): LoadedModelState? = loadedState
    fun isModelLoaded(): Boolean = loadedState != null
}
