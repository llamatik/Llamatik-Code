package com.llamatik.teammate.infrastructure

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.teammate.model.DownloadProgressState
import com.llamatik.teammate.model.ModelStatus
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

@Service(Service.Level.APP)
class ModelDownloadManager {
    private val logger = thisLogger()
    private val progressMap = ConcurrentHashMap<String, DownloadProgressState>()

    // CopyOnWriteArrayList is safe for concurrent add/remove/iterate
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(DownloadProgressState) -> Unit>>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val activeFutures = ConcurrentHashMap<String, Future<*>>()

    fun getProgress(modelId: String): DownloadProgressState? = progressMap[modelId]

    fun addProgressListener(modelId: String, listener: (DownloadProgressState) -> Unit) {
        listeners.getOrPut(modelId) { CopyOnWriteArrayList() }.add(listener)
    }

    fun removeProgressListener(modelId: String, listener: (DownloadProgressState) -> Unit) {
        listeners[modelId]?.remove(listener)
    }

    /**
     * Start a real HTTP download for [modelId] from [downloadUrl], saving to [destDir]/[fileName].
     * Follows redirects (e.g. HuggingFace resolve URLs) to get the real content-length.
     * Progress is streamed via listeners every ~500 ms.
     *
     * [currentShard] and [totalShards] are forwarded into every [DownloadProgressState] emitted
     * so that callers handling multi-shard downloads can display "Shard N / M" in the UI.
     */
    fun startDownload(
        modelId: String,
        downloadUrl: String,
        destDir: Path,
        fileName: String,
        onComplete: (localPath: String) -> Unit = {},
        onError: (message: String) -> Unit = {},
        currentShard: Int = 1,
        totalShards: Int = 1,
        // Suppress automatic markDownloaded() for intermediate shards; the multi-shard
        // orchestrator calls it explicitly after the final shard completes.
        skipRegistration: Boolean = false,
    ) {
        if (activeFutures[modelId]?.isDone == false) {
            logger.info("Download already in progress for $modelId")
            return
        }

        Files.createDirectories(destDir)
        val destFile = destDir.resolve(fileName)
        val partFile = destDir.resolve("$fileName.part")

        val initial = DownloadProgressState(
            modelId = modelId,
            bytesDownloaded = 0L,
            totalBytes = -1L,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = ModelStatus.DOWNLOADING,
            currentShard = currentShard,
            totalShards = totalShards,
        )
        progressMap[modelId] = initial
        notifyListeners(modelId, initial)

        val future = executor.submit {
            try {
                // Follow redirects manually so we can read content-length after all hops
                val finalConnection = openFollowingRedirects(downloadUrl)
                val totalBytes = finalConnection.contentLengthLong
                logger.info("Starting download for $modelId shard $currentShard/$totalShards: totalBytes=$totalBytes url=${finalConnection.url}")

                val withTotal = initial.copy(totalBytes = totalBytes)
                progressMap[modelId] = withTotal
                notifyListeners(modelId, withTotal)

                BufferedInputStream(finalConnection.inputStream, 128 * 1024).use { input ->
                    Files.newOutputStream(partFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = 0L
                        var bytesRead: Int
                        var lastNotifyTime = System.currentTimeMillis()
                        var bytesAtLastNotify = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (progressMap[modelId]?.status == ModelStatus.NOT_DOWNLOADED) {
                                logger.info("Download cancelled for $modelId")
                                Files.deleteIfExists(partFile)
                                progressMap.remove(modelId)
                                return@submit
                            }

                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastNotifyTime
                            if (elapsed >= 500) {
                                val speed = ((downloaded - bytesAtLastNotify) * 1000L) / elapsed
                                val eta = if (speed > 0 && totalBytes > 0) (totalBytes - downloaded) / speed else 0L

                                val progress = DownloadProgressState(
                                    modelId = modelId,
                                    bytesDownloaded = downloaded,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = speed,
                                    etaSeconds = eta,
                                    status = ModelStatus.DOWNLOADING,
                                    currentShard = currentShard,
                                    totalShards = totalShards,
                                )
                                progressMap[modelId] = progress
                                notifyListeners(modelId, progress)

                                lastNotifyTime = now
                                bytesAtLastNotify = downloaded
                            }
                        }

                        // Final progress notification at 100%
                        val finalProgress = DownloadProgressState(
                            modelId = modelId,
                            bytesDownloaded = downloaded,
                            totalBytes = if (totalBytes > 0) totalBytes else downloaded,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            status = ModelStatus.DOWNLOADING,
                            currentShard = currentShard,
                            totalShards = totalShards,
                        )
                        progressMap[modelId] = finalProgress
                        notifyListeners(modelId, finalProgress)
                    }
                }

                Files.move(partFile, destFile, StandardCopyOption.REPLACE_EXISTING)
                val localPath = destFile.toAbsolutePath().toString()
                logger.info("Download complete for $modelId shard $currentShard/$totalShards → $localPath")

                if (!skipRegistration) markDownloaded(modelId, localPath)
                onComplete(localPath)

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Files.deleteIfExists(partFile)
                progressMap.remove(modelId)
                logger.info("Download interrupted for $modelId")
            } catch (e: Exception) {
                Files.deleteIfExists(partFile)
                logger.warn("Download failed for $modelId: ${e.message}", e)
                markFailed(modelId)
                onError(e.message ?: "Unknown error")
            } finally {
                activeFutures.remove(modelId)
            }
        }

        activeFutures[modelId] = future
    }

    /**
     * Download a multi-shard GGUF model sequentially.
     *
     * Each URL in [shardUrls] is downloaded one at a time into [destDir] using
     * the matching name from [shardFileNames]. Progress listeners on [modelId]
     * receive [DownloadProgressState] with [DownloadProgressState.currentShard]
     * and [DownloadProgressState.totalShards] populated so the UI can render
     * "Shard N / M".
     *
     * [onComplete] is called with the path of the *first* shard — llama.cpp
     * auto-discovers the remaining shards from the same directory.
     */
    fun startMultiShardDownload(
        modelId: String,
        shardUrls: List<String>,
        shardFileNames: List<String>,
        destDir: Path,
        onComplete: (firstShardPath: String) -> Unit = {},
        onError: (message: String) -> Unit = {},
    ) {
        require(shardUrls.size == shardFileNames.size) {
            "shardUrls and shardFileNames must have the same size"
        }
        require(shardUrls.isNotEmpty()) { "shardUrls must not be empty" }

        if (activeFutures[modelId]?.isDone == false) {
            logger.info("Multi-shard download already in progress for $modelId")
            return
        }

        val totalShards = shardUrls.size

        // Kick off the first shard; each shard's onComplete chains into the next.
        fun downloadShard(index: Int) {
            if (progressMap[modelId]?.status == ModelStatus.NOT_DOWNLOADED) return

            val isLast = index + 1 == totalShards
            startDownload(
                modelId = modelId,
                downloadUrl = shardUrls[index],
                destDir = destDir,
                fileName = shardFileNames[index],
                currentShard = index + 1,
                totalShards = totalShards,
                // Only register (and emit DOWNLOADED) after the final shard
                skipRegistration = !isLast,
                onComplete = { _ ->
                    if (!isLast) {
                        downloadShard(index + 1)
                    } else {
                        // All shards done — register the first shard path; llama.cpp
                        // auto-discovers the remaining shards in the same directory.
                        val firstShardPath = destDir.resolve(shardFileNames[0]).toAbsolutePath().toString()
                        markDownloaded(modelId, firstShardPath)
                        onComplete(firstShardPath)
                    }
                },
                onError = onError,
            )
        }

        downloadShard(0)
    }

    fun cancelDownload(modelId: String) {
        activeFutures[modelId]?.cancel(true)
        activeFutures.remove(modelId)
        // Keep a NOT_DOWNLOADED entry so the background thread's cancellation check fires
        val cancelled = DownloadProgressState(
            modelId = modelId, bytesDownloaded = 0, totalBytes = 0,
            speedBytesPerSec = 0, etaSeconds = 0, status = ModelStatus.NOT_DOWNLOADED,
        )
        progressMap[modelId] = cancelled
        notifyListeners(modelId, cancelled)
    }

    fun markFailed(modelId: String) {
        val failed = (progressMap[modelId] ?: DownloadProgressState(
            modelId = modelId, bytesDownloaded = 0, totalBytes = 0,
            speedBytesPerSec = 0, etaSeconds = 0, status = ModelStatus.FAILED,
        )).copy(status = ModelStatus.FAILED)
        progressMap[modelId] = failed
        notifyListeners(modelId, failed)
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
        val svc = service<com.llamatik.teammate.settings.DownloadedModelService>()
        if (modelId.endsWith(":mmproj")) svc.registerLocalMmproj(localPath)
        else svc.registerLocalModel(localPath)
    }

    /**
     * Opens a connection following up to 10 HTTP redirects (301/302/307/308).
     * Returns the final connection after all redirects, positioned to read the body.
     */
    private fun openFollowingRedirects(url: String, maxRedirects: Int = 10): HttpURLConnection {
        var currentUrl = url
        repeat(maxRedirects) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Llamatik-Code-Plugin/1.0")
            conn.connect()

            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: error("Redirect with no Location header from $currentUrl")
                conn.disconnect()
                currentUrl = if (location.startsWith("http")) location
                             else URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        error("Too many redirects from $url")
    }

    private fun notifyListeners(modelId: String, state: DownloadProgressState) {
        listeners[modelId]?.forEach { it(state) }
    }
}
