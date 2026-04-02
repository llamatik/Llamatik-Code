package com.llamatik.teammate.application.services

import com.llamatik.teammate.model.DownloadProgressState
import com.llamatik.teammate.model.ExtendedModelDescriptor
import com.llamatik.teammate.model.LoadedModelState
import com.llamatik.teammate.model.ModelTier
import com.llamatik.teammate.model.PluginReadinessState

interface ModelCatalogService {
    fun allModels(): List<ExtendedModelDescriptor>
    fun byTier(tier: ModelTier): List<ExtendedModelDescriptor>
    fun findById(id: String): ExtendedModelDescriptor?
    fun recommendedModel(): ExtendedModelDescriptor
}

interface ModelDownloadService {
    fun startDownload(modelId: String)
    fun pauseDownload(modelId: String)
    fun resumeDownload(modelId: String)
    fun cancelDownload(modelId: String)
    fun retryDownload(modelId: String)
    fun deletePartialFile(modelId: String)
    fun downloadProgress(modelId: String): DownloadProgressState?
}

interface ModelRuntimeService {
    fun loadModel(modelId: String, modelPath: String)
    fun unloadModel()
    fun loadedModel(): LoadedModelState?
    fun isModelLoaded(): Boolean
}

interface PluginStateService {
    fun readinessState(): PluginReadinessState
}
