package com.llamatik.teammate.infrastructure

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.teammate.model.DownloadProgressState
import com.llamatik.teammate.model.ModelStatus
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class ModelDownloadManager {
    private val logger = thisLogger()
    private val progressMap = ConcurrentHashMap<String, DownloadProgressState>()
    private val listeners = ConcurrentHashMap<String, MutableList<(DownloadProgressState) -> Unit>>()

    fun getProgress(modelId: String): DownloadProgressState? = progressMap[modelId]

    fun addProgressListener(modelId: String, listener: (DownloadProgressState) -> Unit) {
        listeners.getOrPut(modelId) { mutableListOf() }.add(listener)
    }

    fun removeProgressListener(modelId: String, listener: (DownloadProgressState) -> Unit) {
        listeners[modelId]?.remove(listener)
    }

    /** Stub: real HF download TODO. Updates state and notifies listeners. */
    fun startDownload(modelId: String, totalBytes: Long = 9_000_000_000L) {
        val initial = DownloadProgressState(
            modelId = modelId,
            bytesDownloaded = 0L,
            totalBytes = totalBytes,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = ModelStatus.DOWNLOADING,
        )
        progressMap[modelId] = initial
        notifyListeners(modelId, initial)
        logger.info("Download started for $modelId (stub — real HF download TODO)")
    }

    fun pauseDownload(modelId: String) {
        progressMap[modelId]?.let { current ->
            val paused = current.copy(status = ModelStatus.PAUSED)
            progressMap[modelId] = paused
            notifyListeners(modelId, paused)
        }
    }

    fun resumeDownload(modelId: String) {
        progressMap[modelId]?.let { current ->
            val resumed = current.copy(status = ModelStatus.DOWNLOADING)
            progressMap[modelId] = resumed
            notifyListeners(modelId, resumed)
        }
    }

    fun cancelDownload(modelId: String) {
        progressMap.remove(modelId)
        val cancelled = DownloadProgressState(
            modelId = modelId, bytesDownloaded = 0, totalBytes = 0,
            speedBytesPerSec = 0, etaSeconds = 0, status = ModelStatus.NOT_DOWNLOADED,
        )
        notifyListeners(modelId, cancelled)
    }

    fun markFailed(modelId: String) {
        progressMap[modelId]?.let { current ->
            val failed = current.copy(status = ModelStatus.FAILED)
            progressMap[modelId] = failed
            notifyListeners(modelId, failed)
        }
    }

    fun markDownloaded(modelId: String, localPath: String) {
        val done = DownloadProgressState(
            modelId = modelId,
            bytesDownloaded = progressMap[modelId]?.totalBytes ?: 0,
            totalBytes = progressMap[modelId]?.totalBytes ?: 0,
            speedBytesPerSec = 0,
            etaSeconds = 0,
            status = ModelStatus.DOWNLOADED,
        )
        progressMap[modelId] = done
        notifyListeners(modelId, done)
        service<com.llamatik.teammate.settings.DownloadedModelService>().registerLocalModel(localPath)
    }

    private fun notifyListeners(modelId: String, state: DownloadProgressState) {
        listeners[modelId]?.forEach { it(state) }
    }
}
